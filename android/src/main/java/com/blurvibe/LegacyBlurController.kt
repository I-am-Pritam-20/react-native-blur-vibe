package com.blurvibe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LegacyBlurController — per-view crop + blur for Android API 21–30.
 *
 * ─── Shared capture ───────────────────────────────────────────────────────────
 *
 * ─── Self-exclusion ───────────────────────────────────────────────────────────
 *
 * ─── Update trigger ───────────────────────────────────────────────────────────
 *
 * ─── Shared RenderScript context (resource-usage fix) ────────────────────────
 *
 */
@Suppress("DEPRECATION")
internal class LegacyBlurController(
  private val view: BlurVibeView,
  private val rootView: ViewGroup
) {

  companion object {
    private const val ROUNDING_VALUE = 64   // stride alignment (Samsung OEM requirement)
    private const val BLUR_RADIUS    = 25f  // default/fallback radius
    // 2 full pass-pairs (horizontal+vertical, twice) for the native box-blur
    // path — two iterations of box blur closely approximate a Gaussian, a
    // well-established technique. This does NOT apply to the RenderScript
    // fallback below, which is a real single-call Gaussian intrinsic — see
    // blurBitmap()'s doc for why doubling that specifically is avoided.
    private const val BLUR_ROUNDS    = 2

    // ── Shared RenderScript — global, ref-counted, main-thread only ─────────
    private var sharedRs: RenderScript? = null
    private var sharedBlurScript: ScriptIntrinsicBlur? = null
    private var activeInstanceCount = 0

    private fun acquireRenderScript(context: android.content.Context): Pair<RenderScript?, ScriptIntrinsicBlur?> {
      if (sharedRs == null) {
        try {
          val rs = RenderScript.create(context.applicationContext)
          sharedRs = rs
          sharedBlurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        } catch (_: Exception) {}
      }
      activeInstanceCount++
      return sharedRs to sharedBlurScript
    }

    private fun releaseRenderScript() {
      activeInstanceCount = (activeInstanceCount - 1).coerceAtLeast(0)
      if (activeInstanceCount == 0) {
        sharedBlurScript?.destroy()
        sharedRs?.destroy()
        sharedBlurScript = null
        sharedRs = null
      }
    }
  }

  private val coordinator = BlurCaptureCoordinator.forRoot(rootView)

  // ── Own crop destination bitmap ───────────────────────────────────────────
  //
  // Sized to THIS view's own downsampled, stride-rounded dimensions.
  // Populated by cropping a region out of coordinator.currentBitmap, then
  // blurred in place — same role as before, just sourced differently.

  private var capturedBitmap: Bitmap? = null
  private var cropCanvas: Canvas? = null
  private var initialized = false

  private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

  // ── Reused per-frame objects (avoid GC pressure from allocating every frame) ─

  private val myLoc = IntArray(2)
  private val rootLoc = IntArray(2)
  private val cropSrcRect = Rect()
  private val cropDstRect = RectF()
  private val drawDstRect = RectF()

  // ── RenderScript (shared, acquired in init) ────────────────────────────────

  private var rs:         RenderScript?        = null
  private var blurScript: ScriptIntrinsicBlur? = null
  private var inputAlloc:  Allocation?          = null
  private var outputAlloc: Allocation?          = null

  // ── State ──────────────────────────────────────────────────────────────────

  var overlayColor: Int    = Color.TRANSPARENT
  var blurRadius:   Float  = BLUR_RADIUS
  var enabled:      Boolean = true
  var autoUpdate:   Boolean = true

  // Per-instance re-entrancy guard — prevents this view's blur pipeline
  // from being entered again while a previous call is still in flight
  // (each BlurVibeView has its OWN guard, so DIFFERENT views' blur calls
  // are unaffected by each other — this only protects a single instance
  // against being re-entered on itself).
  private val isBlurring = AtomicBoolean(false)

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    val (r, s) = acquireRenderScript(view.context)
    rs = r
    blurScript = s
    val w = view.measuredWidth
    val h = view.measuredHeight
    if (w > 0 && h > 0) initBitmaps(w, h)
    coordinator.register(view)
  }

  private fun initBitmaps(w: Int, h: Int) {
    val factor = BlurCaptureCoordinator.DOWNSAMPLE_FACTOR
    val scaledW = roundToStride((w / factor).toInt().coerceAtLeast(1))
    val roundingScale = w.toFloat() / scaledW
    val scaledH = (h / roundingScale).toInt().coerceAtLeast(1)

    capturedBitmap?.recycle()
    capturedBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
    cropCanvas = Canvas(capturedBitmap!!)
    initialized = true
  }

  private fun roundToStride(v: Int): Int {
    if (v % ROUNDING_VALUE == 0) return v
    return v - (v % ROUNDING_VALUE) + ROUNDING_VALUE
  }

  // ── Crop + blur (replaces the old per-view capture step) ──────────────────

  private fun refreshFromSharedCapture() {
    if (!initialized) {
      val w = view.measuredWidth
      val h = view.measuredHeight
      if (w > 0 && h > 0) initBitmaps(w, h) else return
    }
    val shared = coordinator.currentBitmap ?: return   // not ready yet — keep prior content
    val bitmap = capturedBitmap ?: return
    val canvas = cropCanvas ?: return
    if (bitmap.isRecycled) return

    val vw = view.width;  if (vw <= 0) return
    val vh = view.height; if (vh <= 0) return

    view.getLocationInWindow(myLoc)
    rootView.getLocationInWindow(rootLoc)
    val leftPx = (myLoc[0] - rootLoc[0]).toFloat()
    val topPx  = (myLoc[1] - rootLoc[1]).toFloat()

    val factor = BlurCaptureCoordinator.DOWNSAMPLE_FACTOR
    val srcLeft   = (leftPx / factor).toInt().coerceIn(0, shared.width)
    val srcTop    = (topPx  / factor).toInt().coerceIn(0, shared.height)
    val srcRight  = (srcLeft + (vw / factor).toInt()).coerceIn(srcLeft, shared.width)
    val srcBottom = (srcTop  + (vh / factor).toInt()).coerceIn(srcTop, shared.height)
    if (srcRight <= srcLeft || srcBottom <= srcTop) return  // off-screen / zero-size this frame

    bitmap.eraseColor(Color.TRANSPARENT)
    cropSrcRect.set(srcLeft, srcTop, srcRight, srcBottom)
    cropDstRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    canvas.drawBitmap(shared, cropSrcRect, cropDstRect, cropPaint)

    blurBitmap(bitmap)
  }

  // ── Blur dispatch: native (multi-threaded) first, then RenderScript, then software ─

  private fun blurBitmap(bitmap: Bitmap) {
    if (!isBlurring.compareAndSet(false, true)) return
    try {
      val radius = blurRadius.toInt().coerceIn(BlurThreadPool.MIN_RADIUS, BlurThreadPool.MAX_RADIUS)

      var nativeOk = true
      repeat(BLUR_ROUNDS) {
        if (nativeOk) {
          nativeOk = BlurThreadPool.blurRound(bitmap, radius, BlurThreadPool.DIRECTION_HORIZONTAL) &&
                     BlurThreadPool.blurRound(bitmap, radius, BlurThreadPool.DIRECTION_VERTICAL)
        }
      }
      if (nativeOk) return

      blurBitmapRenderScript(bitmap)
    } finally {
      isBlurring.set(false)
    }
  }

  private fun blurBitmapRenderScript(bitmap: Bitmap) {
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

  // ── Draw — called from BlurVibeView.onDraw() ──────────────────────────────

  fun draw(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
    if (!enabled) return
    if (autoUpdate) refreshFromSharedCapture()
    capturedBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
      drawDstRect.set(0f, 0f, viewWidth, viewHeight)
      canvas.drawBitmap(bmp, null, drawDstRect, drawPaint)
    }
    if (Color.alpha(overlayColor) > 0) canvas.drawColor(overlayColor)
  }

  // ── Multi-window ──────────────────────────────────────────────────────────

  fun reAttach() {
    coordinator.reAttachIfNeeded()
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  fun onSizeChanged() {
    initialized = false
    val w = view.measuredWidth
    val h = view.measuredHeight
    if (w > 0 && h > 0) initBitmaps(w, h)
  }

  fun destroy() {
    coordinator.unregister(view)
    inputAlloc?.destroy()
    outputAlloc?.destroy()
    releaseRenderScript()
    rs = null
    blurScript = null
    capturedBitmap?.recycle()
    capturedBitmap = null
    cropCanvas = null
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
