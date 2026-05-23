package com.blurvibe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * LegacyBlurController — zero-dependency backdrop blur for Android API 21–30.
 *
 * Replaces QmBlurView with a direct RenderScript implementation.
 * RenderScript is part of the Android SDK — no external library needed.
 *
 * Pipeline per vsync:
 *   preDrawListener (sets dirty flag only)
 *     → Choreographer.FrameCallback (once per vsync)
 *       → rootView.draw() into captureBitmap (main thread)
 *       → downsample into scaledBitmap
 *       → ScriptIntrinsicBlur.forEach() (RenderScript, GPU-accelerated)
 *       → view.invalidate()
 *         → onDraw: drawBitmap(scaledBitmap) + overlay tint
 *
 * Key optimisations vs naive implementation:
 *   - Choreographer gate: max 1 capture per vsync regardless of invalidation count
 *   - Bitmap pool: captureBitmap + scaledBitmap reused each frame (zero GC)
 *   - RenderScript Allocation pool: inputAlloc + outputAlloc reused (zero GC)
 *   - Blur rounds = 2: two passes for smooth spread without pixelation
 *   - Downsample factor = 4: captures at 1/16 resolution, blur hides pixel detail
 */
@Suppress("DEPRECATION")  // RenderScript deprecated in API 31 — we only use this on API < 31
internal class LegacyBlurController(
  private val view: View,
  private val rootView: ViewGroup
) {

  companion object {
    private const val DOWNSAMPLE_FACTOR = 4f   // capture at 1/4 linear resolution (1/16 pixels)
    private const val BLUR_RADIUS       = 8f   // RenderScript Gaussian radius (1–25)
    private const val BLUR_ROUNDS       = 2    // passes — 2 gives smooth spread
  }

  // ── Bitmap pool ────────────────────────────────────────────────────────────

  private var captureBitmap: Bitmap? = null  // full-res root capture
  private var scaledBitmap:  Bitmap? = null  // downsampled before blur
  private val capturePaint   = Paint(Paint.FILTER_BITMAP_FLAG)
  private val drawPaint      = Paint(Paint.FILTER_BITMAP_FLAG)

  // ── RenderScript pool ──────────────────────────────────────────────────────

  private var rs:          RenderScript?        = null
  private var blurScript:  ScriptIntrinsicBlur? = null
  private var inputAlloc:  Allocation?          = null
  private var outputAlloc: Allocation?          = null

  // ── State ──────────────────────────────────────────────────────────────────

  var overlayColor: Int = Color.TRANSPARENT
  var blurRadius:   Float = BLUR_RADIUS
  var enabled:      Boolean = true
    set(value) { field = value; if (!value) invalidatePool() }
  var autoUpdate:   Boolean = true
    set(value) {
      field = value
      if (value) rootView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
      else rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    }

  private var frameScheduled = false
  private var isCapturing    = false

  // ── Choreographer gate ────────────────────────────────────────────────────

  private val frameCallback = Choreographer.FrameCallback {
    frameScheduled = false
    if (enabled) captureAndBlur()
  }

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (!frameScheduled && enabled) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
    true
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    initRenderScript()
    rootView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
  }

  private fun initRenderScript() {
    try {
      rs = RenderScript.create(view.context)
      blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    } catch (_: Exception) {}
  }

  // ── Capture + blur ─────────────────────────────────────────────────────────

  private fun captureAndBlur() {
    if (isCapturing) return
    val rw = rootView.width;  if (rw <= 0) return
    val rh = rootView.height; if (rh <= 0) return
    val vw = view.width;      if (vw <= 0) return
    val vh = view.height;     if (vh <= 0) return

    val sw = (vw / DOWNSAMPLE_FACTOR).toInt().coerceAtLeast(1)
    val sh = (vh / DOWNSAMPLE_FACTOR).toInt().coerceAtLeast(1)

    isCapturing = true
    try {
      // ① Compute offset of view within root
      val myLoc   = IntArray(2); view.getLocationInWindow(myLoc)
      val rootLoc = IntArray(2); rootView.getLocationInWindow(rootLoc)
      val offsetX = myLoc[0] - rootLoc[0]
      val offsetY = myLoc[1] - rootLoc[1]

      // ② Allocate bitmaps (reuse if size matches)
      val capture = reuseBitmap(captureBitmap, vw, vh).also { captureBitmap = it }
      val scaled  = reuseBitmap(scaledBitmap,  sw, sh).also { scaledBitmap  = it }

      // ③ Capture just the region behind this view from root
      val c = Canvas(capture)
      c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
      c.translate(-offsetX.toFloat(), -offsetY.toFloat())
      rootView.draw(c)

      // ④ Downsample
      val sc = Canvas(scaled)
      sc.drawBitmap(capture,
        Rect(0, 0, capture.width, capture.height),
        Rect(0, 0, scaled.width,  scaled.height),
        capturePaint)

      // ⑤ Blur (2 rounds for smooth spread)
      repeat(BLUR_ROUNDS) { blurBitmap(scaled) }

      // ⑥ Trigger redraw with new bitmap
      view.invalidate()

    } catch (_: Exception) {
    } finally {
      isCapturing = false
    }
  }

  private fun blurBitmap(bitmap: Bitmap) {
    val rs = this.rs ?: return softwareBlur(bitmap)
    val sc = this.blurScript ?: return softwareBlur(bitmap)
    try {
      val inA  = reuseAlloc(inputAlloc,  bitmap, rs).also { inputAlloc  = it }
      val outA = reuseAlloc(outputAlloc, bitmap, rs).also { outputAlloc = it }
      inA.copyFrom(bitmap)
      sc.setRadius(blurRadius.coerceIn(1f, 25f))
      sc.setInput(inA)
      sc.forEach(outA)
      outA.copyTo(bitmap)
    } catch (_: Exception) {
      softwareBlur(bitmap)
    }
  }

  private fun softwareBlur(bitmap: Bitmap) {
    // Pure software Gaussian fallback (slower but always works)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      maskFilter = android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, paint)
  }

  // ── Draw — called from BlurVibeView.onDraw() ──────────────────────────────

  fun draw(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
    scaledBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
      canvas.drawBitmap(bmp, null,
        android.graphics.RectF(0f, 0f, viewWidth, viewHeight), drawPaint)
    }
    if (Color.alpha(overlayColor) > 0) {
      canvas.drawColor(overlayColor)
    }
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  fun onSizeChanged() {
    invalidatePool()
  }

  private fun invalidatePool() {
    captureBitmap?.recycle(); captureBitmap = null
    scaledBitmap?.recycle();  scaledBitmap  = null
    inputAlloc?.destroy();    inputAlloc    = null
    outputAlloc?.destroy();   outputAlloc   = null
  }

  fun destroy() {
    rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    inputAlloc?.destroy()
    outputAlloc?.destroy()
    blurScript?.destroy()
    rs?.destroy()
    captureBitmap?.recycle()
    scaledBitmap?.recycle()
  }

  // ── Bitmap / Allocation helpers ───────────────────────────────────────────

  private fun reuseBitmap(existing: Bitmap?, w: Int, h: Int): Bitmap {
    if (existing != null && !existing.isRecycled
        && existing.width == w && existing.height == h) return existing
    existing?.recycle()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  }

  private fun reuseAlloc(existing: Allocation?, src: Bitmap, rs: RenderScript): Allocation {
    if (existing != null
        && existing.type.x == src.width
        && existing.type.y == src.height) return existing
    existing?.destroy()
    return Allocation.createFromBitmap(rs, src,
      Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
  }
}