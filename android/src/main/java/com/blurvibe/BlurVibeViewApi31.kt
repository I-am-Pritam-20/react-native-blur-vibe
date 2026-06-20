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
  private var lastCaptureNs = 0L

  // ── PreDraw listener — fires BEFORE RenderThread ─────────────

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (blurEnabled && autoUpdate && initialized) {
      val now = System.nanoTime()
      if (now - lastCaptureNs >= FRAME_INTERVAL_NS) {
        lastCaptureNs = now
        updateCapture()
      }
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

    // Round to stride alignment (Samsung OEM requirement)
    val scaledW = roundToStride((w / SCALE_FACTOR).toInt().coerceAtLeast(1))
    val roundScale = w.toFloat() / scaledW
    val scaledH = (h / roundScale).toInt().coerceAtLeast(1)

    capturedBitmap?.recycle()
    capturedBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
    captureCanvas  = BlurVibeCanvas(capturedBitmap!!)
    initialized    = true

    safeAddPreDrawListener()
    updateCapture()  // initial capture
  }

  private fun roundToStride(v: Int): Int {
    if (v % ROUNDING_VALUE == 0) return v
    return v - (v % ROUNDING_VALUE) + ROUNDING_VALUE
  }

  // ── Capture  ───────

  private fun updateCapture() {
    val root   = blurRoot       ?: return
    val bitmap = capturedBitmap ?: return
    val canvas = captureCanvas  ?: return
    if (bitmap.isRecycled) return
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

  private fun drawHardwarePath(canvas: Canvas, bmp: Bitmap, w: Float, h: Float) {
    val localRadius = localBlurRadius(blurAmount)

    // Fresh RenderNode per frame
    val blurNode = RenderNode("BlurVibeFrame")
    blurNode.setPosition(0, 0, bmp.width, bmp.height)

    val nodeCanvas = blurNode.beginRecording()
    nodeCanvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
    blurNode.endRecording()
    blurNode.setRenderEffect(
      RenderEffect.createBlurEffect(localRadius, localRadius, Shader.TileMode.CLAMP)
    )

    // Progressive mask requires saveLayer
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE)
      canvas.saveLayer(0f, 0f, w, h, null) else -1

    // Scale from bitmap resolution to view size, then draw GPU-blurred result
    canvas.save()
    val scaleW = w / bmp.width
    val scaleH = h / bmp.height
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

  private fun localBlurRadius(amount: Float): Float {
    // blurAmount=10  → felt≈10.9 → local≈1.8   (backdrop-blur-sm)
    // blurAmount=50  → felt≈50.5 → local≈8.4   (backdrop-blur-xl)
    // blurAmount=100 → felt=100  → local≈16.7  (maximum)
    val felt = 1f + (amount.coerceIn(0f, 100f) / 100f) * 99f
    return (felt / SCALE_FACTOR).coerceIn(0.5f, 40f)
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
    private const val SCALE_FACTOR   = 6f   // Default scaleFactor
    private const val ROUNDING_VALUE = 64   // stride alignment (Samsung)
    private const val FRAME_INTERVAL_NS = 33_333_333L  // ~30fps cap
    const val PROGRESSIVE_NONE          = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT = 4
    const val PROGRESSIVE_RADIAL        = 5
  }
}