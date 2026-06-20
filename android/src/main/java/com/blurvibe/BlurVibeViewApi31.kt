package com.blurvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import com.facebook.react.views.view.ReactViewGroup
import kotlin.math.min
import kotlin.random.Random

/**
 * BlurVibeViewApi31 — Backdrop blur for Android API 31+
 *
 * ─── Architecture (from deep reading of Dimezis BlurView source) ──────────────
 *
 * PreDrawBlurController (Dimezis, API < 31):
 *   onPreDraw() → rootView.draw(BlurViewCanvas) → blurAlgorithm.blur() → done
 *   No worker thread, no Choreographer, fully synchronous in onPreDraw.
 *   BlurViewCanvas is a MARKER CANVAS — draw() skips itself when canvas is this type.
 *
 * RenderNodeBlurController (Dimezis, API 31+):
 *   BlurTarget.dispatchDraw() records children into BlurTarget.renderNode.
 *   BlurView.draw() reads target.renderNode → blurNode → RenderEffect → screen.
 *   RenderThread-safe because target.renderNode is fully recorded BEFORE BlurView draws.
 *   We can't use this pattern (no BlurTarget in RN).
 *
 * ─── Our approach for API 31+ ─────────────────────────────────────────────────
 *
 * Step 1 — Capture (in OnPreDrawListener, synchronous, main thread):
 *   rootView.draw(BlurVibeCanvas) → downsampled bitmap
 *   BlurVibeCanvas marker → BlurVibeView.draw() returns immediately → self excluded
 *
 * Step 2 — RenderEffect in onDraw() (per-frame RenderNode pattern):
 *   Each onDraw() creates a fresh RenderNode for that frame.
 *   beginRecording() → drawBitmap(capturedBitmap) → endRecording()
 *   setRenderEffect(RenderEffect.createBlurEffect(radius * SCALE_FACTOR))
 *   canvas.drawRenderNode(freshNode)
 *
 *   WHY THIS IS THREAD-SAFE (unlike our previous RenderNode approach):
 *   Previous crash: Choreographer callback recorded blurNode (main thread)
 *                   while RenderThread replayed the SAME blurNode from last frame.
 *   This approach: each frame creates a DIFFERENT RenderNode object.
 *   RenderThread replays nodeN (immutable after endRecording).
 *   Main thread creates nodeN+1 (a completely different object).
 *   No shared mutable state between threads. Zero SIGSEGV.
 *
 *   Radius scaling (from Dimezis RenderNodeBlurController.applyBlur()):
 *   realRadius = blurRadius * scaleFactor
 *   Because the bitmap is already downsampled by scaleFactor, the RenderEffect
 *   radius must be scaled up proportionally to produce the correct visual blur.
 *
 * ─── Style props (borderRadius, borderWidth, borderColor) ───────────────────
 *   outlineProvider = BACKGROUND: ReactViewBackgroundDrawable.getOutline() handles
 *   all RN borderRadius variants. clipToOutline only enabled when radius > 0.
 *   background?.draw(canvas) at END of onDraw() redraws border ON TOP of blur.
 */
@RequiresApi(Build.VERSION_CODES.S)
class BlurVibeViewApi31(context: Context) : ReactViewGroup(context) {

  // ── Blur params ────────────────────────────────────────────────────────────

  private var blurAmount     = 10f
  private var overlayColor   = Color.TRANSPARENT
  private var cornerRadiusPx = 0f

  // ── Progressive blur ──────────────────────────────────────────────────────

  private var progressiveDirection      = PROGRESSIVE_NONE
  private var progressiveStartIntensity = 1f
  private var progressiveEndIntensity   = 0f

  // ── Noise ─────────────────────────────────────────────────────────────────

  private var noiseFactor = 0.08f
  private var noiseBitmap: Bitmap? = null
  private val noisePaint  = Paint()

  // ── Capture bitmap ────────────────────────────────────────────────────────
  //
  // Single bitmap, captured and reused each frame.
  // No worker thread needed — RenderEffect does the GPU blur.
  // Thread safety: onDraw() reads capturedBitmap via a per-frame RenderNode
  // (new object each frame). Capture writes capturedBitmap in preDrawListener
  // BEFORE onDraw() is called. Sequential — no concurrent access.

  private var capturedBitmap: Bitmap? = null
  private var captureCanvas: BlurVibeCanvas? = null
  private var initialized = false

  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

  // ── Root ──────────────────────────────────────────────────────────────────

  private var blurRoot: ViewGroup? = null
  private val rootLocation    = IntArray(2)
  private val blurViewLocation = IntArray(2)

  // ── State ─────────────────────────────────────────────────────────────────

  private var blurEnabled = true
  private var autoUpdate  = true

  // ── PreDraw listener — fires BEFORE RenderThread (guaranteed) ─────────────
  //
  // Dimezis PreDrawBlurController does EXACTLY this:
  //   onPreDraw() { updateBlur(); return true; }
  // updateBlur() calls rootView.draw(internalCanvas) synchronously.
  // No Choreographer, no worker thread for capture.
  // We match this exactly.

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (blurEnabled && autoUpdate && initialized) {
      updateCapture()
    }
    true
  }

  // ── Paint ─────────────────────────────────────────────────────────────────

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    outlineProvider = ViewOutlineProvider.BACKGROUND
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    blurRoot = findBlurRoot()
    generateNoiseBitmap()
    if (measuredWidth > 0 && measuredHeight > 0) initBlur()
  }

  override fun onDetachedFromWindow() {
    safeRemovePreDrawListener()
    initialized = false
    blurRoot    = null
    capturedBitmap?.recycle(); capturedBitmap = null
    captureCanvas = null
    noiseBitmap?.recycle(); noiseBitmap = null
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) initBlur()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus && blurEnabled && autoUpdate) safeAddPreDrawListener()
  }

  // ── Init bitmaps ──────────────────────────────────────────────────────────

  private fun initBlur() {
    safeRemovePreDrawListener()
    val w = measuredWidth;  if (w <= 0) return
    val h = measuredHeight; if (h <= 0) return

    // Round to stride alignment (Samsung OEM requirement — Dimezis SizeScaler)
    val scaledW = roundToStride((w / SCALE_FACTOR).toInt().coerceAtLeast(1))
    val roundScale = w.toFloat() / scaledW
    val scaledH = (h / roundScale).toInt().coerceAtLeast(1)

    capturedBitmap?.recycle()
    capturedBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
    captureCanvas  = BlurVibeCanvas(capturedBitmap!!)
    initialized    = true

    safeAddPreDrawListener()
    updateCapture()  // initial capture (Dimezis does this too)
  }

  private fun roundToStride(v: Int): Int {
    if (v % ROUNDING_VALUE == 0) return v
    return v - (v % ROUNDING_VALUE) + ROUNDING_VALUE
  }

  // ── Capture (Dimezis PreDrawBlurController.updateBlur() equivalent) ───────

  private fun updateCapture() {
    val root   = blurRoot       ?: return
    val bitmap = capturedBitmap ?: return
    val canvas = captureCanvas  ?: return
    if (bitmap.isRecycled) return

    // Dimezis setupInternalCanvasMatrix()
    root.getLocationOnScreen(rootLocation)
    getLocationOnScreen(blurViewLocation)

    val left = blurViewLocation[0] - rootLocation[0]
    val top  = blurViewLocation[1] - rootLocation[1]

    val scaleFactorW = width.toFloat()  / bitmap.width.toFloat()
    val scaleFactorH = height.toFloat() / bitmap.height.toFloat()

    val scaledLeft = -left / scaleFactorW
    val scaledTop  = -top  / scaleFactorH

    bitmap.eraseColor(Color.TRANSPARENT)
    canvas.save()
    canvas.translate(scaledLeft, scaledTop)
    canvas.scale(1f / scaleFactorW, 1f / scaleFactorH)

    // rootView.draw(BlurVibeCanvas):
    //   BlurVibeView.draw() detects BlurVibeCanvas → returns immediately (skips self)
    //   All other views draw normally → we capture content BEHIND our view
    //   Exceptions caught silently (Dimezis pattern)
    try {
      root.draw(canvas)
    } catch (e: Exception) {
      Log.e("BlurVibeViewApi31", "Capture failed, skipping frame", e)
      canvas.restore()
      return
    }
    canvas.restore()

    // Request redraw with new captured content
    invalidate()
  }

  // ── draw() — skip self during capture ────────────────────────────────────
  //
  // Dimezis BlurView.draw():
  //   if (canvas instanceof BlurViewCanvas) return false;
  //
  // BlurVibeCanvas is a marker. Real screen draws use the hardware display
  // canvas — never a BlurVibeCanvas. Zero race condition with Reanimated.

  override fun draw(canvas: Canvas) {
    if (canvas is BlurVibeCanvas) return
    super.draw(canvas)
  }

  // ── onDraw ────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    if (!blurEnabled || !initialized) return
    val w = width.toFloat();  if (w <= 0f) return
    val h = height.toFloat(); if (h <= 0f) return
    val bmp = capturedBitmap?.takeIf { !it.isRecycled } ?: return

    if (canvas.isHardwareAccelerated) {
      drawHardwarePath(canvas, bmp, w, h)
    } else {
      drawSoftwarePath(canvas, bmp, w, h)
    }
  }

  // ── Hardware path (API 31+, normal case) ──────────────────────────────────
  //
  // Per-frame RenderNode + RenderEffect.createBlurEffect()
  //
  // Thread safety proof:
  //   All of beginRecording/endRecording/drawRenderNode happen inside
  //   this single onDraw() call during display list recording (main thread).
  //   RenderThread replays blurNode from frame N — a fully-recorded, immutable node.
  //   Main thread creates blurNode for frame N+1 — a DIFFERENT object.
  //   No shared mutable state between threads.
  //
  // Radius scaling (Dimezis RenderNodeBlurController):
  //   realRadius = blurRadius * scaleFactor
  //   Bitmap is at 1/SCALE_FACTOR resolution, so radius must be scaled up.

  private fun drawHardwarePath(canvas: Canvas, bmp: Bitmap, w: Float, h: Float) {
    val radius     = blurRadiusFromAmount(blurAmount)
    val realRadius = (radius * SCALE_FACTOR).coerceAtLeast(1f)

    // Fresh RenderNode per frame — Dimezis's hardware path equivalent
    val blurNode = RenderNode("BlurVibeFrame")
    blurNode.setPosition(0, 0, bmp.width, bmp.height)

    val nodeCanvas = blurNode.beginRecording()
    nodeCanvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
    blurNode.endRecording()

    // Apply RenderEffect — GPU blur (same as Dimezis RenderNodeBlurController.applyBlur())
    blurNode.setRenderEffect(
      RenderEffect.createBlurEffect(realRadius, realRadius, Shader.TileMode.CLAMP)
    )

    // Progressive mask requires saveLayer
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE)
      canvas.saveLayer(0f, 0f, w, h, null) else -1

    // Scale from bitmap resolution to view size, then draw GPU-blurred result
    canvas.save()
    val scaleW = w / bmp.width
    val scaleH = h / bmp.height
    // Clip to BlurView bounds (Dimezis: "Don't draw outside BlurView bounds")
    canvas.clipRect(0f, 0f, w, h)
    canvas.scale(scaleW, scaleH)
    canvas.drawRenderNode(blurNode)
    canvas.restore()

    // Progressive alpha mask
    if (progressiveDirection != PROGRESSIVE_NONE && saveCount >= 0) {
      buildProgressiveShader(w, h)?.let { shader ->
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, maskPaint)
      }
      canvas.restoreToCount(saveCount)
    }

    // Overlay tint
    if (Color.alpha(overlayColor) > 0) {
      overlayPaint.color = overlayColor
      canvas.drawRect(0f, 0f, w, h, overlayPaint)
    }

    // Noise grain
    noiseBitmap?.takeIf { !it.isRecycled && noiseFactor > 0f }?.let { noise ->
      noisePaint.alpha  = (noiseFactor * 255f).toInt().coerceIn(0, 255)
      noisePaint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
      canvas.drawRect(0f, 0f, w, h, noisePaint)
    }

    // Redraw ReactViewBackgroundDrawable ON TOP of blur
    // Borders/borderRadius/borderColor appear above the blur layer
    background?.draw(canvas)
  }

  // ── Software fallback (screenshots, software transitions) ─────────────────

  private fun drawSoftwarePath(canvas: Canvas, bmp: Bitmap, w: Float, h: Float) {
    canvas.save()
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE)
      canvas.saveLayer(0f, 0f, w, h, null) else -1
    canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), bitmapPaint)
    if (progressiveDirection != PROGRESSIVE_NONE && saveCount >= 0) {
      buildProgressiveShader(w, h)?.let { shader ->
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, maskPaint)
      }
      canvas.restoreToCount(saveCount)
    }
    canvas.restore()
    if (Color.alpha(overlayColor) > 0) {
      overlayPaint.color = overlayColor
      canvas.drawRect(0f, 0f, w, h, overlayPaint)
    }
    background?.draw(canvas)
  }

  // ── Progressive shader ────────────────────────────────────────────────────

  private fun buildProgressiveShader(w: Float, h: Float): Shader? {
    val sc = Color.argb((progressiveStartIntensity.coerceIn(0f,1f)*255).toInt(),0,0,0)
    val ec = Color.argb((progressiveEndIntensity.coerceIn(0f,1f)*255).toInt(),0,0,0)
    return when (progressiveDirection) {
      PROGRESSIVE_TOP_TO_BOTTOM -> LinearGradient(0f,0f,0f,h,sc,ec,Shader.TileMode.CLAMP)
      PROGRESSIVE_BOTTOM_TO_TOP -> LinearGradient(0f,h,0f,0f,sc,ec,Shader.TileMode.CLAMP)
      PROGRESSIVE_LEFT_TO_RIGHT -> LinearGradient(0f,0f,w,0f,sc,ec,Shader.TileMode.CLAMP)
      PROGRESSIVE_RIGHT_TO_LEFT -> LinearGradient(w,0f,0f,0f,sc,ec,Shader.TileMode.CLAMP)
      PROGRESSIVE_RADIAL -> RadialGradient(w/2f,h/2f,min(w,h)/2f,sc,ec,Shader.TileMode.CLAMP)
      else -> null
    }
  }

  // ── Noise ─────────────────────────────────────────────────────────────────

  private fun generateNoiseBitmap() {
    if (noiseBitmap?.isRecycled == false) return
    val size = 64
    val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val rng  = Random(42)
    for (x in 0 until size) for (y in 0 until size) {
      val v = rng.nextInt(256); bmp.setPixel(x, y, Color.argb(255, v, v, v))
    }
    noiseBitmap = bmp
  }

  // ── Public setters ─────────────────────────────────────────────────────────

  fun setBlurAmount(amount: Float) {
    blurAmount = amount.coerceIn(0f, 100f); invalidate()
  }

  fun setOverlayColor(colorString: String?) {
    overlayColor = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    invalidate()
  }

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
    )
    clipToOutline = cornerRadiusPx > 0f
    invalidate()
  }

  fun setReducedTransparencyFallbackColor(@Suppress("UNUSED_PARAMETER") c: String?) {}

  fun setProgressiveBlurDirection(d: String?) {
    progressiveDirection = when (d) {
      "topToBottom" -> PROGRESSIVE_TOP_TO_BOTTOM; "bottomToTop" -> PROGRESSIVE_BOTTOM_TO_TOP
      "leftToRight" -> PROGRESSIVE_LEFT_TO_RIGHT; "rightToLeft" -> PROGRESSIVE_RIGHT_TO_LEFT
      "radial" -> PROGRESSIVE_RADIAL; else -> PROGRESSIVE_NONE
    }; invalidate()
  }

  fun setProgressiveStartIntensity(v: Float) { progressiveStartIntensity = v.coerceIn(0f,1f); invalidate() }
  fun setProgressiveEndIntensity(v: Float)   { progressiveEndIntensity   = v.coerceIn(0f,1f); invalidate() }
  fun setNoiseFactor(v: Float)               { noiseFactor = v.coerceIn(0f,1f); invalidate() }

  fun applyBlurEnabled(enabled: Boolean) {
    blurEnabled = enabled
    if (enabled) { safeAddPreDrawListener(); invalidate() }
    else { safeRemovePreDrawListener(); invalidate() }
  }

  fun setAutoUpdate(update: Boolean) {
    autoUpdate = update
    if (update) safeAddPreDrawListener() else safeRemovePreDrawListener()
  }

  // ── PreDrawListener helpers ────────────────────────────────────────────────
  // Dimezis: attach to BOTH root VTO and blurView VTO for Dialog window support

  private fun safeAddPreDrawListener() {
    val root = blurRoot ?: return
    val rootVto = root.viewTreeObserver
    rootVto.removeOnPreDrawListener(preDrawListener)
    if (rootVto.isAlive) rootVto.addOnPreDrawListener(preDrawListener)
    if (root.windowId != windowId) {
      val myVto = viewTreeObserver
      myVto.removeOnPreDrawListener(preDrawListener)
      if (myVto.isAlive) myVto.addOnPreDrawListener(preDrawListener)
    }
  }

  private fun safeRemovePreDrawListener() {
    blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
    viewTreeObserver.removeOnPreDrawListener(preDrawListener)
  }

  // ── Root finder ───────────────────────────────────────────────────────────

  private fun findBlurRoot(): ViewGroup? {
    var p = parent
    while (p != null) {
      if ((p as? View)?.javaClass?.name == "com.swmansion.rnscreens.Screen")
        return p as? ViewGroup
      p = (p as? View)?.parent
    }
    p = parent
    while (p != null) {
      if ((p as? View)?.javaClass?.name == "com.facebook.react.ReactRootView")
        return p as? ViewGroup
      p = (p as? View)?.parent
    }
    return rootView as? ViewGroup
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private fun blurRadiusFromAmount(amount: Float): Float {
    // Linear 0→100 maps to 1→25. Real GPU radius = this * SCALE_FACTOR (Dimezis pattern).
    // blurAmount=10  → radius=3.4  → GPU radius ≈ 20px  (backdrop-blur-sm)
    // blurAmount=50  → radius=13   → GPU radius ≈ 78px  (backdrop-blur-xl)
    // blurAmount=100 → radius=25   → GPU radius ≈ 150px (maximum)
    val t = amount.coerceIn(0f, 100f) / 100f
    return 1f + t * 24f
  }

  private fun parseHexColor(s: String): Int? {
    val t = s.trim()
    if (t.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
    if (!t.startsWith("#")) return try { t.toColorInt() } catch (_: Exception) { null }
    val hex = t.removePrefix("#")
    return try {
      when (hex.length) {
        3 -> Color.argb(255, hex[0].toString().repeat(2).toInt(16),
                             hex[1].toString().repeat(2).toInt(16),
                             hex[2].toString().repeat(2).toInt(16))
        6 -> Color.argb(255, hex.substring(0,2).toInt(16),
                             hex.substring(2,4).toInt(16),
                             hex.substring(4,6).toInt(16))
        8 -> Color.argb(hex.substring(6,8).toInt(16),
                        hex.substring(0,2).toInt(16),
                        hex.substring(2,4).toInt(16),
                        hex.substring(4,6).toInt(16))
        else -> null
      }
    } catch (_: NumberFormatException) { null }
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}

  companion object {
    private const val SCALE_FACTOR   = 6f   // Dimezis default scaleFactor
    private const val ROUNDING_VALUE = 64   // stride alignment (Samsung)
    const val PROGRESSIVE_NONE          = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT = 4
    const val PROGRESSIVE_RADIAL        = 5
  }
}