package com.blurvibe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
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
 */
@Suppress("DEPRECATION")
internal class LegacyBlurController(
  private val view: BlurVibeView,
  private val rootView: ViewGroup
) {

  companion object {
    private const val DOWNSAMPLE_FACTOR = 6f

    private const val BLUR_RADIUS = 25f  // max RenderScript kernel

    private const val BLUR_ROUNDS = 1

    private const val FRAME_INTERVAL_NS = 33_333_333L  // ~30fps cap
  }

  // ── Bitmap pool ────────────────────────────────────────────────────────────

  private var captureBitmap: Bitmap? = null
  private var scaledBitmap:  Bitmap? = null
  private val capturePaint   = Paint(Paint.FILTER_BITMAP_FLAG)
  private val drawPaint      = Paint(Paint.FILTER_BITMAP_FLAG)

  // ── RenderScript pool ──────────────────────────────────────────────────────

  private var rs:          RenderScript?        = null
  private var blurScript:  ScriptIntrinsicBlur? = null
  private var inputAlloc:  Allocation?          = null
  private var outputAlloc: Allocation?          = null

  // ── State ──────────────────────────────────────────────────────────────────

  var overlayColor: Int  = Color.TRANSPARENT
  var blurRadius:   Float = BLUR_RADIUS
  var enabled:      Boolean = true
    set(value) { field = value; if (!value) invalidatePool() }
  var autoUpdate:   Boolean = true
    set(value) {
      field = value
      if (value) safeAddPreDrawListener()
      else rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    }

  var isCapturing = false
    private set

  private var frameScheduled = false
  private var lastCaptureNs  = 0L

  // ── Choreographer gate ────────────────────────────────────────────────────

  private val frameCallback = Choreographer.FrameCallback {
    frameScheduled = false
    if (enabled) {
      val now = System.nanoTime()
      if (now - lastCaptureNs >= FRAME_INTERVAL_NS) {
        lastCaptureNs = now
        captureAndBlur()
      }
    }
  }

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (!frameScheduled && enabled && autoUpdate) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
    true
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    initRenderScript()
    safeAddPreDrawListener()
  }

  private fun initRenderScript() {
    try {
      rs = RenderScript.create(view.context)
      blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    } catch (_: Exception) {}
  }

  // ── Capture + blur ─────────────────────────────────────────────────────────

  private fun captureAndBlur() {
    val rw = rootView.width;  if (rw <= 0) return
    val rh = rootView.height; if (rh <= 0) return
    val vw = view.width;      if (vw <= 0) return
    val vh = view.height;     if (vh <= 0) return

    val sw = (vw / DOWNSAMPLE_FACTOR).toInt().coerceAtLeast(1)
    val sh = (vh / DOWNSAMPLE_FACTOR).toInt().coerceAtLeast(1)

    val myLoc   = IntArray(2); view.getLocationInWindow(myLoc)
    val rootLoc = IntArray(2); rootView.getLocationInWindow(rootLoc)
    val offsetX = (myLoc[0] - rootLoc[0]).toFloat()
    val offsetY = (myLoc[1] - rootLoc[1]).toFloat()

    val capture = reuseBitmap(captureBitmap, vw, vh).also { captureBitmap = it }
    val scaled  = reuseBitmap(scaledBitmap,  sw, sh).also { scaledBitmap  = it }

    // Set isCapturing BEFORE root.draw() so BlurVibeView.draw() is skipped
    isCapturing = true
    val c = Canvas(capture)
    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    c.translate(-offsetX, -offsetY)
    try {
      rootView.draw(c)
    } catch (_: Exception) {
      isCapturing = false
      return
    }
    isCapturing = false

    // Downsample
    Canvas(scaled).drawBitmap(
      capture,
      Rect(0, 0, capture.width, capture.height),
      Rect(0, 0, scaled.width, scaled.height),
      capturePaint
    )

    // Blur (2 rounds)
    repeat(BLUR_ROUNDS) { blurBitmap(scaled) }

    view.invalidate()
  }

  private fun blurBitmap(bitmap: Bitmap) {
    val r  = rs          ?: return softwareBlur(bitmap)
    val sc = blurScript  ?: return softwareBlur(bitmap)
    try {
      val iA = reuseAlloc(inputAlloc,  bitmap, r).also { inputAlloc  = it }
      val oA = reuseAlloc(outputAlloc, bitmap, r).also { outputAlloc = it }
      iA.copyFrom(bitmap)
      sc.setRadius(blurRadius.coerceIn(1f, 25f))
      sc.setInput(iA)
      sc.forEach(oA)
      oA.copyTo(bitmap)
    } catch (_: Exception) { softwareBlur(bitmap) }
  }

  private fun softwareBlur(bitmap: Bitmap) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      maskFilter = android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, paint)
  }

  // ── Draw ─────────────────────────────────────────────────────────────────

  fun draw(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
    scaledBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
      canvas.drawBitmap(bmp, null,
        android.graphics.RectF(0f, 0f, viewWidth, viewHeight), drawPaint)
    }
    if (Color.alpha(overlayColor) > 0) canvas.drawColor(overlayColor)
  }

  // ── Multi-window ──────────────────────────────────────────────────────────

  fun reAttach() {
    if (enabled && autoUpdate) safeAddPreDrawListener()
  }

  private fun safeAddPreDrawListener() {
    val vto = rootView.viewTreeObserver
    vto.removeOnPreDrawListener(preDrawListener)
    if (vto.isAlive) vto.addOnPreDrawListener(preDrawListener)
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  fun onSizeChanged() { invalidatePool() }

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

  // ── Helpers ───────────────────────────────────────────────────────────────

  private fun reuseBitmap(existing: Bitmap?, w: Int, h: Int): Bitmap {
    if (existing != null && !existing.isRecycled
        && existing.width == w && existing.height == h) return existing
    existing?.recycle()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  }

  private fun reuseAlloc(existing: Allocation?, src: Bitmap, rs: RenderScript): Allocation {
    if (existing != null && existing.type.x == src.width && existing.type.y == src.height)
      return existing
    existing?.destroy()
    return Allocation.createFromBitmap(rs, src,
      Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
  }
}