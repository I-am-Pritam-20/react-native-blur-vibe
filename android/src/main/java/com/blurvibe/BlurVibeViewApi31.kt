package com.blurvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
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

  // ── Bitmap + RenderNode ────────────────────────────────────────────────────

  private var internalBitmap: Bitmap? = null
  private val renderNode = RenderNode("BlurVibeNode")

  // ── Capture exclusion flag ────────────────────────────────────────────────
  //
  // THE FIX FOR STATIC BLUR:
  //
  // root.draw(canvas) walks the entire view tree including THIS BlurView.
  // When it reaches us during capture, our onDraw draws the PREVIOUS frame's
  // blurred bitmap — so the capture contains our own stale output, not just
  // the content behind us. This makes the blur appear static because each
  // frame captures the previous frame's blur output, not the live content.
  //
  // Fix: set isCapturing = true before root.draw(), override draw() to be
  // a no-op when isCapturing = true. root.draw() then skips us completely,
  // capturing ONLY the content behind us. This is exactly how Dimezis
  // BlurView solves the same problem.
  //
  // This does NOT cause a flash because we are not changing visibility —
  // we are only suppressing our own draw() during the off-screen capture.
  // The view remains visible on screen; we just skip drawing into the
  // off-screen capture canvas.

  private var isCapturing = false

  // ── Draw paints ───────────────────────────────────────────────────────────

  private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }
  private val noisePaintFinal = Paint()

  // ── Root view ─────────────────────────────────────────────────────────────

  private var blurRoot: ViewGroup? = null
  private val myLocation   = IntArray(2)
  private val rootLocation = IntArray(2)

  // ── State ─────────────────────────────────────────────────────────────────

  private var blurEnabled    = true
  private var autoUpdate     = true
  private var frameScheduled = false
  private var initialized    = false

  // ── Choreographer gate ────────────────────────────────────────────────────

  private val frameCallback = Choreographer.FrameCallback {
    frameScheduled = false
    if (isAttachedToWindow && blurEnabled) updateBlur()
  }

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (!frameScheduled && blurEnabled && autoUpdate) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
    true
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    // DO NOT call setBackgroundColor here.
    // ReactViewGroup manages its own ReactViewBackgroundDrawable which handles
    // all RN style props: borderRadius, borderColor, borderWidth, opacity,
    // backgroundColor, shadow, elevation etc.
    // Calling super.setBackgroundColor() replaces that drawable with a plain
    // ColorDrawable — destroying all style prop handling.
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    blurRoot = findBlurRoot()
    safeAddPreDrawListener()
    generateNoiseBitmap()
    if (measuredWidth > 0 && measuredHeight > 0) initBlur()
  }

  override fun onDetachedFromWindow() {
    blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    frameScheduled = false
    initialized    = false
    isCapturing    = false
    blurRoot       = null
    noiseBitmap?.recycle();    noiseBitmap    = null
    internalBitmap?.recycle(); internalBitmap = null
    renderNode.discardDisplayList()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) {
      internalBitmap?.recycle(); internalBitmap = null
      renderNode.discardDisplayList()
      initialized = false
      initBlur()
    }
  }

  // ── Multi-window safety ───────────────────────────────────────────────────

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus && blurEnabled && autoUpdate) {
      safeAddPreDrawListener()
      scheduleFrame()
    }
  }

  private fun safeAddPreDrawListener() {
    val root = blurRoot ?: return
    val vto  = root.viewTreeObserver
    vto.removeOnPreDrawListener(preDrawListener)
    if (vto.isAlive) vto.addOnPreDrawListener(preDrawListener)
  }

  // ── Init blur ─────────────────────────────────────────────────────────────

  private fun initBlur() {
    val w = measuredWidth;  if (w <= 0) return
    val h = measuredHeight; if (h <= 0) return
    internalBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    renderNode.setPosition(0, 0, w, h)
    initialized = true
    updateBlur()
  }

  // ── Core: capture + blur + render ─────────────────────────────────────────

  private fun updateBlur() {
    if (!blurEnabled || !initialized) return
    val root   = blurRoot       ?: return
    val bitmap = internalBitmap ?: return
    if (bitmap.isRecycled) return

    // ① Compute this view's offset within the root (window coords — correct
    //   for all window modes: split-screen, freeform, PiP, DeX)
    root.getLocationInWindow(rootLocation)
    getLocationInWindow(myLocation)
    val offsetX = (myLocation[0] - rootLocation[0]).toFloat()
    val offsetY = (myLocation[1] - rootLocation[1]).toFloat()

    // ② Capture root content EXCLUDING this view.
    //   isCapturing = true causes our draw() to be a no-op, so root.draw()
    //   skips us and captures only the content behind us.
    isCapturing = true
    val captureCanvas = Canvas(bitmap)
    captureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    captureCanvas.translate(-offsetX, -offsetY)
    try {
      root.draw(captureCanvas)
    } catch (_: Exception) {
      isCapturing = false
      return
    }
    isCapturing = false

    // ③ Record bitmap into RenderNode.
    //   Drawing a BITMAP into RenderNode is stable on all OEM drivers.
    //   Drawing a RenderNode into another RenderNode's recording is NOT.
    renderNode.setPosition(0, 0, bitmap.width, bitmap.height)
    val nodeCanvas = renderNode.beginRecording()
    nodeCanvas.drawBitmap(bitmap, 0f, 0f, null)
    renderNode.endRecording()

    // ④ Apply GPU blur + tint as chained RenderEffects
    // Double-pass blur: two Gaussian passes = wider spread kernel
    // Equivalent to sqrt(2) wider sigma — gives frosted-glass light diffusion
    // CLAMP tile mode: no edge reflection artifacts
    val radius = blurRadiusFromAmount(blurAmount)
    val pass1  = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
    val pass2  = RenderEffect.createBlurEffect(radius * 0.5f, radius * 0.5f, Shader.TileMode.CLAMP)
    val doubleBlur = RenderEffect.createChainEffect(pass2, pass1)  // pass1 first, then pass2

    renderNode.setRenderEffect(
      if (Color.alpha(overlayColor) > 0) {
        RenderEffect.createChainEffect(
          RenderEffect.createColorFilterEffect(
            BlendModeColorFilter(overlayColor, BlendMode.SRC_ATOP)
          ),
          doubleBlur
        )
      } else doubleBlur
    )

    invalidate()
  }

  // ── draw() override — no-op during capture ────────────────────────────────
  //
  // When isCapturing = true (root.draw() is in progress capturing background),
  // suppress our own draw so we don't paint stale blur into the capture bitmap.
  // This makes us invisible to root.draw() during capture only —
  // NOT to the actual screen renderer.

  override fun draw(canvas: Canvas) {
    if (isCapturing) return   // skip self during root capture
    super.draw(canvas)
  }

  // ── onDraw ────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    if (!blurEnabled || !initialized) return
    val w = width.toFloat();  if (w <= 0f) return
    val h = height.toFloat(); if (h <= 0f) return
    if (!renderNode.hasDisplayList()) return

    // Progressive mask requires a saved layer so DST_IN mask composites correctly
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE) {
      canvas.saveLayer(0f, 0f, w, h, null)
    } else -1

    // Draw GPU-blurred + tinted result from RenderNode
    canvas.drawRenderNode(renderNode)

    // Progressive alpha mask — fades blur across the view
    if (progressiveDirection != PROGRESSIVE_NONE && saveCount >= 0) {
      buildProgressiveShader(w, h)?.let { shader ->
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, maskPaint)
      }
      canvas.restoreToCount(saveCount)
    }

    // Noise grain overlay
    noiseBitmap?.takeIf { !it.isRecycled && noiseFactor > 0f }?.let { noise ->
      noisePaintFinal.alpha  = (noiseFactor * 255f).toInt().coerceIn(0, 255)
      noisePaintFinal.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
      canvas.drawRect(0f, 0f, w, h, noisePaintFinal)
    }

    // Let ReactViewGroup draw borders/background on top (handles borderRadius
    // and all other RN style props natively — no conflict with our blur)
    super.onDraw(canvas)
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
      PROGRESSIVE_RADIAL        -> RadialGradient(w/2f,h/2f,min(w,h)/2f,sc,ec,Shader.TileMode.CLAMP)
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
      val v = rng.nextInt(256)
      bmp.setPixel(x, y, Color.argb(255, v, v, v))
    }
    noiseBitmap = bmp
  }

  // ── Public setters ─────────────────────────────────────────────────────────

  fun setBlurAmount(amount: Float) {
    blurAmount = amount.coerceIn(0f, 100f); scheduleFrame()
  }

  fun setOverlayColor(colorString: String?) {
    overlayColor = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    scheduleFrame()
  }

  // borderRadius from JS style prop — handled natively by ReactViewGroup.
  // applyBorderRadius is called by our @ReactProp "borderRadius" binding.
  // We additionally set clipToOutline so the blur content is clipped correctly.
  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
    )
    if (cornerRadiusPx > 0f) {
      outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
          outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
        }
      }
      clipToOutline = true
    } else {
      outlineProvider = ViewOutlineProvider.BACKGROUND
      clipToOutline   = false
    }
    invalidate()
  }

  fun setReducedTransparencyFallbackColor(@Suppress("UNUSED_PARAMETER") c: String?) {}

  fun setProgressiveBlurDirection(d: String?) {
    progressiveDirection = when (d) {
      "topToBottom" -> PROGRESSIVE_TOP_TO_BOTTOM
      "bottomToTop" -> PROGRESSIVE_BOTTOM_TO_TOP
      "leftToRight" -> PROGRESSIVE_LEFT_TO_RIGHT
      "rightToLeft" -> PROGRESSIVE_RIGHT_TO_LEFT
      "radial"      -> PROGRESSIVE_RADIAL
      else          -> PROGRESSIVE_NONE
    }; invalidate()
  }

  fun setProgressiveStartIntensity(v: Float) { progressiveStartIntensity = v.coerceIn(0f,1f); invalidate() }
  fun setProgressiveEndIntensity(v: Float)   { progressiveEndIntensity   = v.coerceIn(0f,1f); invalidate() }
  fun setNoiseFactor(v: Float)               { noiseFactor = v.coerceIn(0f,1f); invalidate() }

  fun applyBlurEnabled(enabled: Boolean) {
    blurEnabled = enabled
    if (enabled) { safeAddPreDrawListener(); scheduleFrame() }
    else {
      blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
      Choreographer.getInstance().removeFrameCallback(frameCallback)
      frameScheduled = false
      renderNode.discardDisplayList()
      invalidate()
    }
  }

  fun setAutoUpdate(update: Boolean) {
    autoUpdate = update
    if (update) safeAddPreDrawListener()
    else {
      blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
      Choreographer.getInstance().removeFrameCallback(frameCallback)
      frameScheduled = false
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private fun scheduleFrame() {
    if (!frameScheduled && blurEnabled) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
  }

  private fun blurRadiusFromAmount(amount: Float): Float {
    // Linear mapping: 0→1px, 10→13px, 25→31px, 50→61px, 75→91px, 100→120px
    // These values match CSS backdrop-filter feel:
    //   blurAmount=10  ≈ backdrop-blur-sm  (4px CSS = ~13px GPU after downsample)
    //   blurAmount=25  ≈ backdrop-blur-md  (12px CSS ≈ 31px GPU)
    //   blurAmount=50  ≈ backdrop-blur-xl  (24px CSS ≈ 61px GPU)
    //   blurAmount=100 ≈ backdrop-blur-3xl (64px CSS = fully frosted glass)
    val t = amount.coerceIn(0f, 100f) / 100f
    return (1f + t * 119f)  // 1–120 linear
  }

  private fun findBlurRoot(): ViewGroup? {
    var p = parent
    while (p != null) {
      if ((p as? View)?.javaClass?.name == "com.swmansion.rnscreens.Screen") return p as? ViewGroup
      p = (p as? View)?.parent
    }
    p = parent
    while (p != null) {
      if ((p as? View)?.javaClass?.name == "com.facebook.react.ReactRootView") return p as? ViewGroup
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
    const val PROGRESSIVE_NONE          = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT = 4
    const val PROGRESSIVE_RADIAL        = 5
  }
}