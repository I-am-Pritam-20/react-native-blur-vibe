package com.blurvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import android.view.Choreographer
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
 * BlurVibeViewApi31 — GPU backdrop blur for Android API 31+
 *
 * Features:
 *  • Dual-RenderNode blur (backdrop-filter CSS semantics)
 *  • Progressive / gradient blur (vertical, horizontal, radial)
 *  • Noise texture overlay (tactile frosted-glass feel, like Haze)
 *  • Overlay tint with full RGBA support
 *  • Corner radius with hardware clipping
 *  • Choreographer-gated updates (max 1 capture per vsync)
 *
 * Progressive blur technique (from Haze docs):
 *   Uses a mask approach — a LinearGradient/RadialGradient is drawn as an
 *   alpha mask over the blur output using PorterDuff.DST_IN.
 *   This fades the blur from full-strength to zero across the view.
 *   Per Haze docs: "masks are much faster with negligible performance cost"
 *   vs true per-pixel radius variation which costs ~25% more on API 33+.
 *
 * Noise texture:
 *   Haze uses noise at 15% opacity by default for tactility.
 *   We generate a small tileable noise bitmap once and draw it with low alpha.
 */
@RequiresApi(Build.VERSION_CODES.S)
class BlurVibeViewApi31(context: Context) : ReactViewGroup(context) {

  // ── Blur params ────────────────────────────────────────────────────────────

  private var blurRadiusX    = DEFAULT_BLUR_RADIUS
  private var blurRadiusY    = DEFAULT_BLUR_RADIUS
  private var overlayColor   = Color.TRANSPARENT
  private var cornerRadiusPx = 0f

  // ── Progressive blur params ────────────────────────────────────────────────

  private var progressiveDirection = PROGRESSIVE_NONE
  private var progressiveStartIntensity = 1f   // 0.0–1.0, full blur at start
  private var progressiveEndIntensity   = 0f   // 0.0–1.0, no blur at end

  // ── Noise params ──────────────────────────────────────────────────────────

  private var noiseFactor = 0.08f   // Haze default is 0.15 — we use 0.08 as default (subtler)
  private var noiseBitmap: Bitmap? = null
  private val noisePaint  = Paint().apply { alpha = (noiseFactor * 255).toInt() }

  // ── RenderNodes ───────────────────────────────────────────────────────────

  /** Records the root-view content — "what's behind me" */
  private val contentNode = RenderNode("BlurVibeContent").apply {
    setUseCompositingLayer(true, null)  // caches as GPU texture — repeated reads are free
  }

  /** Holds contentNode cropped to this view's position, with RenderEffect blur applied */
  private val blurNode = RenderNode("BlurVibeBlur")

  // ── Paint objects (reused, no per-frame allocation) ───────────────────────

  private val overlayPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
  private val maskPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }
  private val clearPaint      = Paint().apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
  }

  // ── Root view ─────────────────────────────────────────────────────────────

  private var blurRoot: ViewGroup? = null

  // ── Choreographer gate ────────────────────────────────────────────────────

  private var frameScheduled = false
  private val frameCallback = Choreographer.FrameCallback {
    frameScheduled = false
    if (isAttachedToWindow) {
      captureRootIntoNode()
      invalidate()
    }
  }
  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (!frameScheduled) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
    true
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    super.setBackgroundColor(Color.TRANSPARENT)
    clipToOutline = true
    // Enable hardware layer so onDraw() runs on GPU
    setLayerType(LAYER_TYPE_HARDWARE, null)
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    blurRoot = findBlurRoot()
    blurRoot?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
    generateNoiseBitmap()
  }

  override fun onDetachedFromWindow() {
    blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    frameScheduled = false
    blurRoot = null
    noiseBitmap?.recycle()
    noiseBitmap = null
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    blurNode.setPosition(0, 0, w, h)
    applyBlurRenderEffect()
  }

  // ── Capture ────────────────────────────────────────────────────────────────

  private fun captureRootIntoNode() {
    val root = blurRoot ?: return
    if (root.width <= 0 || root.height <= 0) return

    contentNode.setPosition(0, 0, root.width, root.height)

    val canvas = contentNode.beginRecording()
    try {
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
      root.draw(canvas)
    } finally {
      contentNode.endRecording()
    }

    rebuildBlurNode()
  }

  private fun rebuildBlurNode() {
    val root = blurRoot ?: return
    if (width <= 0 || height <= 0) return

    val myLoc   = IntArray(2); getLocationInWindow(myLoc)
    val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
    val offsetX = (myLoc[0] - rootLoc[0]).toFloat()
    val offsetY = (myLoc[1] - rootLoc[1]).toFloat()

    blurNode.setPosition(0, 0, width, height)
    applyBlurRenderEffect()

    val canvas = blurNode.beginRecording()
    try {
      canvas.translate(-offsetX, -offsetY)
      canvas.drawRenderNode(contentNode)
    } finally {
      blurNode.endRecording()
    }
  }

  private fun applyBlurRenderEffect() {
    if (blurRadiusX < 0.5f && blurRadiusY < 0.5f) {
      blurNode.setRenderEffect(null)
      return
    }
    blurNode.setRenderEffect(
      RenderEffect.createBlurEffect(blurRadiusX, blurRadiusY, Shader.TileMode.CLAMP)
    )
  }

  // ── Draw ───────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return

    if (!blurNode.hasDisplayList()) return

    // ── Step 1: Save layer so we can apply mask on top of blur ────────────────
    // saveLayer lets us composite blur + progressive mask as a unit
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE) {
      canvas.saveLayer(0f, 0f, w, h, null)
    } else {
      -1
    }

    // ── Step 2: Draw blurred backdrop ─────────────────────────────────────────
    canvas.drawRenderNode(blurNode)

    // ── Step 3: Progressive mask (alpha gradient fades the blur) ──────────────
    if (progressiveDirection != PROGRESSIVE_NONE && saveCount >= 0) {
      val shader = buildProgressiveShader(w, h)
      if (shader != null) {
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, maskPaint)
      }
      canvas.restoreToCount(saveCount)
    }

    // ── Step 4: Overlay tint ──────────────────────────────────────────────────
    if (Color.alpha(overlayColor) > 0) {
      overlayPaint.color = overlayColor
      if (cornerRadiusPx > 0f) {
        canvas.drawRoundRect(RectF(0f, 0f, w, h), cornerRadiusPx, cornerRadiusPx, overlayPaint)
      } else {
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
      }
    }

    // ── Step 5: Noise texture (tactile frosted-glass feel) ────────────────────
    if (noiseFactor > 0f && noiseBitmap != null && !noiseBitmap!!.isRecycled) {
      noisePaint.alpha = (noiseFactor * 255f).toInt().coerceIn(0, 255)
      val noiseShader = BitmapShader(noiseBitmap!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
      noisePaint.shader = noiseShader
      canvas.drawRect(0f, 0f, w, h, noisePaint)
    }
  }

  // ── Progressive shader builder ────────────────────────────────────────────

  private fun buildProgressiveShader(w: Float, h: Float): Shader? {
    // Map intensity values to alpha: 1.0 = fully opaque (full blur), 0.0 = fully transparent (no blur)
    val startAlpha = progressiveStartIntensity.coerceIn(0f, 1f)
    val endAlpha   = progressiveEndIntensity.coerceIn(0f, 1f)
    val startColor = Color.argb((startAlpha * 255).toInt(), 0, 0, 0)
    val endColor   = Color.argb((endAlpha   * 255).toInt(), 0, 0, 0)

    return when (progressiveDirection) {
      PROGRESSIVE_TOP_TO_BOTTOM -> LinearGradient(
        0f, 0f, 0f, h, startColor, endColor, Shader.TileMode.CLAMP
      )
      PROGRESSIVE_BOTTOM_TO_TOP -> LinearGradient(
        0f, h, 0f, 0f, startColor, endColor, Shader.TileMode.CLAMP
      )
      PROGRESSIVE_LEFT_TO_RIGHT -> LinearGradient(
        0f, 0f, w, 0f, startColor, endColor, Shader.TileMode.CLAMP
      )
      PROGRESSIVE_RIGHT_TO_LEFT -> LinearGradient(
        w, 0f, 0f, 0f, startColor, endColor, Shader.TileMode.CLAMP
      )
      PROGRESSIVE_RADIAL -> RadialGradient(
        w / 2f, h / 2f,
        min(w, h) / 2f,
        startColor, endColor,
        Shader.TileMode.CLAMP
      )
      else -> null
    }
  }

  // ── Noise generation ─────────────────────────────────────────────────────
  //
  // Generates a small (64×64) tileable noise bitmap once.
  // Haze uses noise at 15% opacity for tactility — the fine grain
  // breaks up the uniform blur and makes it look more like real frosted glass.

  private fun generateNoiseBitmap() {
    if (noiseBitmap != null && !noiseBitmap!!.isRecycled) return
    val size = 64
    val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val rng  = Random(42)  // fixed seed = deterministic noise, no shimmer on re-render
    for (x in 0 until size) {
      for (y in 0 until size) {
        val v = rng.nextInt(256)
        bmp.setPixel(x, y, Color.argb(255, v, v, v))
      }
    }
    noiseBitmap = bmp
  }

  // ── Public setters ─────────────────────────────────────────────────────────

  fun setBlurAmount(amount: Float) {
    val t      = amount.coerceIn(0f, 100f) / 100f
    val radius = t * t * MAX_BLUR_RADIUS  // quadratic — matches CSS backdrop-blur feel
    blurRadiusX = radius
    blurRadiusY = radius
    applyBlurRenderEffect()
    scheduleFrame()
  }

  fun setOverlayColor(colorString: String?) {
    overlayColor = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    invalidate()
  }

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
    )
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
      }
    }
    clipToOutline = cornerRadiusPx > 0f
    invalidate()
  }

  fun setReducedTransparencyFallbackColor(@Suppress("UNUSED_PARAMETER") color: String?) {
    // iOS-only — no-op on Android
  }

  /**
   * Progressive blur direction.
   * @param direction one of: "none", "topToBottom", "bottomToTop",
   *                          "leftToRight", "rightToLeft", "radial"
   */
  fun setProgressiveBlurDirection(direction: String?) {
    progressiveDirection = when (direction) {
      "topToBottom"  -> PROGRESSIVE_TOP_TO_BOTTOM
      "bottomToTop"  -> PROGRESSIVE_BOTTOM_TO_TOP
      "leftToRight"  -> PROGRESSIVE_LEFT_TO_RIGHT
      "rightToLeft"  -> PROGRESSIVE_RIGHT_TO_LEFT
      "radial"       -> PROGRESSIVE_RADIAL
      else           -> PROGRESSIVE_NONE
    }
    invalidate()
  }

  /**
   * Progressive blur start intensity (0.0 = no blur, 1.0 = full blur).
   * This is the intensity at the START of the gradient direction.
   * Default 1.0 — full blur at top/left/center.
   */
  fun setProgressiveStartIntensity(intensity: Float) {
    progressiveStartIntensity = intensity.coerceIn(0f, 1f)
    invalidate()
  }

  /**
   * Progressive blur end intensity (0.0 = no blur, 1.0 = full blur).
   * This is the intensity at the END of the gradient direction.
   * Default 0.0 — fades to no blur at bottom/right/edge.
   */
  fun setProgressiveEndIntensity(intensity: Float) {
    progressiveEndIntensity = intensity.coerceIn(0f, 1f)
    invalidate()
  }

  /**
   * Noise factor — grain overlay strength for frosted-glass tactility.
   * 0.0 = no noise, 1.0 = full noise. Default 0.08 (8%).
   * Haze's default is 0.15. Set 0 to disable.
   */
  fun setNoiseFactor(factor: Float) {
    noiseFactor = factor.coerceIn(0f, 1f)
    invalidate()
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private fun scheduleFrame() {
    if (!frameScheduled) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
  }

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

  private fun parseHexColor(s: String): Int? {
    val t = s.trim()
    if (t.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
    if (!t.startsWith("#")) return try { t.toColorInt() } catch (_: Exception) { null }
    val hex = t.removePrefix("#")
    return try {
      when (hex.length) {
        3 -> Color.argb(255,
          hex[0].toString().repeat(2).toInt(16),
          hex[1].toString().repeat(2).toInt(16),
          hex[2].toString().repeat(2).toInt(16))
        6 -> Color.argb(255,
          hex.substring(0, 2).toInt(16),
          hex.substring(2, 4).toInt(16),
          hex.substring(4, 6).toInt(16))
        8 -> Color.argb(
          hex.substring(6, 8).toInt(16),
          hex.substring(0, 2).toInt(16),
          hex.substring(2, 4).toInt(16),
          hex.substring(4, 6).toInt(16))
        else -> null
      }
    } catch (_: NumberFormatException) { null }
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    // Yoga handles all layout
  }

  companion object {
    private const val MAX_BLUR_RADIUS     = 25f
    private const val DEFAULT_BLUR_RADIUS = 2.5f

    const val PROGRESSIVE_NONE           = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM  = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP  = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT  = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT  = 4
    const val PROGRESSIVE_RADIAL         = 5
  }
}