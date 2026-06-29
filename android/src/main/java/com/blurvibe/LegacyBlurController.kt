package com.blurvibe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.Choreographer
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
    private const val DOWNSAMPLE_FACTOR = 6f   // matches BlurVibeViewApi31's SCALE_FACTOR
    private const val ROUNDING_VALUE    = 64   // stride alignment (Samsung OEM requirement)
    private const val BLUR_RADIUS       = 25f  // max RenderScript kernel
    private const val BLUR_ROUNDS       = 1    // single pass — small bitmap already looks smooth

    // ~45fps cap. Raised from the original 30fps now that each capture is
    // cheap (downsampled-only) — still far below uncapped 90/120Hz, but
    // fresh enough that fast underlying animations look smoother.
    private const val FRAME_INTERVAL_NS = 22_222_222L
  }

  // ── Single downsampled bitmap + canvas ────────────────────────────────────
  //
  // Replaces the old captureBitmap (full-res) + scaledBitmap (downsampled)
  // pair. There is only ONE bitmap now, allocated directly at the downsampled
  // size — root.draw() writes into it directly via a scaled canvas.

  private var capturedBitmap: Bitmap? = null
  private var captureCanvas:  Canvas? = null
  private var initialized = false

  private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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

  // isCapturing: set true before root.draw() so BlurVibeView.draw() is a no-op
  // preventing stale self-capture. Accessed by BlurVibeView.draw().
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
    val w = view.measuredWidth
    val h = view.measuredHeight
    if (w > 0 && h > 0) initBitmaps(w, h)
    safeAddPreDrawListener()
  }

  private fun initRenderScript() {
    try {
      rs = RenderScript.create(view.context)
      blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    } catch (_: Exception) {}
  }

  // ── Bitmap init (downsampled size, stride-aligned) ────────────────────────

  private fun initBitmaps(w: Int, h: Int) {
    val scaledW = roundToStride((w / DOWNSAMPLE_FACTOR).toInt().coerceAtLeast(1))
    val roundingScale = w.toFloat() / scaledW
    val scaledH = (h / roundingScale).toInt().coerceAtLeast(1)

    capturedBitmap?.recycle()
    capturedBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
    captureCanvas  = Canvas(capturedBitmap!!)
    initialized    = true
  }

  private fun roundToStride(v: Int): Int {
    if (v % ROUNDING_VALUE == 0) return v
    return v - (v % ROUNDING_VALUE) + ROUNDING_VALUE
  }

  // ── Capture + blur (direct-downsampled, the perf fix) ─────────────────────

  private fun captureAndBlur() {
    if (!initialized) {
      val w = view.measuredWidth
      val h = view.measuredHeight
      if (w > 0 && h > 0) initBitmaps(w, h) else return
    }
    val bitmap = capturedBitmap ?: return
    val canvas = captureCanvas  ?: return
    if (bitmap.isRecycled) return

    val vw = view.width;  if (vw <= 0) return
    val vh = view.height; if (vh <= 0) return

    // getLocationInWindow — correct for split-screen/freeform/PiP (window-
    // relative, unlike getLocationOnScreen which breaks when the window
    // doesn't start at screen origin).
    val myLoc   = IntArray(2); view.getLocationInWindow(myLoc)
    val rootLoc = IntArray(2); rootView.getLocationInWindow(rootLoc)
    val left = myLoc[0] - rootLoc[0]
    val top  = myLoc[1] - rootLoc[1]

    // Scale factors: how much smaller the bitmap is than the actual view.
    val scaleFactorW = vw.toFloat() / bitmap.width.toFloat()
    val scaleFactorH = vh.toFloat() / bitmap.height.toFloat()

    bitmap.eraseColor(Color.TRANSPARENT)
    canvas.save()
    // Scale DOWN before drawing — root.draw() below produces low-res output
    // directly, instead of drawing full-res and downsampling afterward.
    canvas.translate(-left / scaleFactorW, -top / scaleFactorH)
    canvas.scale(1f / scaleFactorW, 1f / scaleFactorH)

    // isCapturing = true → BlurVibeView.draw() is a no-op → root.draw()
    // skips us → capture contains ONLY content behind us
    isCapturing = true
    try {
      rootView.draw(canvas)
    } catch (_: Exception) {
      isCapturing = false
      canvas.restore()
      return
    }
    isCapturing = false
    canvas.restore()

    repeat(BLUR_ROUNDS) { blurBitmap(bitmap) }

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
    capturedBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
      canvas.drawBitmap(bmp, null, RectF(0f, 0f, viewWidth, viewHeight), drawPaint)
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

  fun onSizeChanged() {
    initialized = false
    val w = view.measuredWidth
    val h = view.measuredHeight
    if (w > 0 && h > 0) initBitmaps(w, h)
  }

  private fun invalidatePool() {
    capturedBitmap?.recycle(); capturedBitmap = null
    captureCanvas = null
    inputAlloc?.destroy();  inputAlloc  = null
    outputAlloc?.destroy(); outputAlloc = null
    initialized = false
  }

  fun destroy() {
    rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    inputAlloc?.destroy()
    outputAlloc?.destroy()
    blurScript?.destroy()
    rs?.destroy()
    capturedBitmap?.recycle()
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private fun reuseAlloc(existing: Allocation?, src: Bitmap, rs: RenderScript): Allocation {
    if (existing != null && existing.type.x == src.width && existing.type.y == src.height)
      return existing
    existing?.destroy()
    return Allocation.createFromBitmap(rs, src,
      Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
  }
}