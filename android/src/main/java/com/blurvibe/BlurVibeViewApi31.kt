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
import android.graphics.Rect
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
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * BlurVibeViewApi31 — Backdrop blur for Android API 31+
 *
 * Pipeline (adapted from ModernBlurView's RenderEffectBlur approach):
 *
 *   1. rootView.draw(canvas) → internalBitmap   (bitmap capture, main thread)
 *   2. renderNode.beginRecording()
 *        canvas.drawBitmap(internalBitmap)       (bitmap → RenderNode — safe on all OEMs)
 *      renderNode.endRecording()
 *   3. renderNode.setRenderEffect(
 *        createChainEffect(tintEffect, blurEffect)  (GPU blur + tint in one pass)
 *      )
 *   4. onDraw: canvas.drawRenderNode(renderNode)  (draws GPU result to screen)
 *      + progressive mask + noise
 *
 * KEY INSIGHT from ModernBlurView:
 *   Drawing a flat BITMAP into a RenderNode, then drawRenderNode() is stable
 *   on all OEM devices (Oppo/OnePlus/Xiaomi/Samsung).
 *   Drawing a RenderNode INSIDE another RenderNode's recording crashes
 *   on OEM-patched GPU drivers. We don't do that here.
 *
 * Choreographer gate: max 1 capture per vsync.
 * Bitmap pool + RenderNode reuse: zero GC per frame.
 */
@RequiresApi(Build.VERSION_CODES.S)
class BlurVibeViewApi31(context: Context) : ReactViewGroup(context) {

  // ── Blur params ────────────────────────────────────────────────────────────

  private var blurAmount   = 10f
  private var overlayColor = Color.TRANSPARENT
  private var cornerRadiusPx = 0f

  // ── Progressive blur ──────────────────────────────────────────────────────

  private var progressiveDirection      = PROGRESSIVE_NONE
  private var progressiveStartIntensity = 1f
  private var progressiveEndIntensity   = 0f

  // ── Noise ─────────────────────────────────────────────────────────────────

  private var noiseFactor = 0.08f
  private var noiseBitmap: Bitmap? = null
  private val noisePaint  = Paint()

  // ── Bitmap + RenderNode (ModernBlurView pattern) ───────────────────────────
  //
  // internalBitmap: captured root pixels at view resolution
  // renderNode:     holds bitmap + RenderEffect (GPU blur + tint chain)
  //
  // The renderNode is reused every frame — only its content (bitmap) and
  // effect (radius/tint) are updated, not recreated.

  private var internalBitmap: Bitmap? = null
  private val renderNode = RenderNode("BlurVibeNode")

  // ── Draw paint ────────────────────────────────────────────────────────────

  private val bitmapPaint  = Paint(Paint.FILTER_BITMAP_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }
  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  // ── Root view ─────────────────────────────────────────────────────────────

  private var blurRoot: ViewGroup? = null
  private val rootLocation   = IntArray(2)
  private val blurViewLocation = IntArray(2)

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
    super.setBackgroundColor(Color.TRANSPARENT)
    clipToOutline = true
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
    blurRoot       = null
    noiseBitmap?.recycle(); noiseBitmap = null
    internalBitmap?.recycle(); internalBitmap = null
    renderNode.discardDisplayList()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) {
      internalBitmap?.recycle(); internalBitmap = null
      initBlur()
    }
  }

  // ── Multi-window / split-screen / PiP safety ──────────────────────────────
  //
  // Android can "kill" a ViewTreeObserver when the window enters/exits
  // split-screen, PiP, or freeform mode — creating a new one silently.
  // If we hold a reference to the old (dead) observer our preDrawListener
  // stops firing and blur freezes. We fix this by:
  //   1. Always re-attaching via the CURRENT observer (not a cached one)
  //   2. Checking isAlive() before adding — safe even if called redundantly
  //   3. Re-attaching on window focus gain (fires after every mode transition)

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus && blurEnabled && autoUpdate) {
      // Re-attach listener to the current (possibly new) ViewTreeObserver
      safeAddPreDrawListener()
      scheduleFrame()
    }
  }

  /**
   * Add preDrawListener to rootView's CURRENT ViewTreeObserver.
   * Removes from any stale observer first, then attaches to the live one.
   * Safe to call multiple times — isAlive() prevents double-attachment.
   */
  private fun safeAddPreDrawListener() {
    val root = blurRoot ?: return
    val vto  = root.viewTreeObserver
    // Remove first (no-op if not attached) then re-add to current observer
    vto.removeOnPreDrawListener(preDrawListener)
    if (vto.isAlive) {
      vto.addOnPreDrawListener(preDrawListener)
    }
  }

  // ── Blur init ─────────────────────────────────────────────────────────────

  private fun initBlur() {
    val w = measuredWidth;  if (w <= 0) return
    val h = measuredHeight; if (h <= 0) return

    internalBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    renderNode.setPosition(0, 0, w, h)
    initialized = true
    setWillNotDraw(false)
    updateBlur()
  }

  // ── Core blur update (ModernBlurView pattern) ─────────────────────────────

  private fun updateBlur() {
    if (!blurEnabled || !initialized) return
    val root   = blurRoot          ?: return
    val bitmap = internalBitmap    ?: return

    // ① Capture root into internalBitmap (same as ModernBlurView's approach)
    //   Translate canvas so we capture exactly the region behind this view
    // getLocationInWindow — correct for ALL Android versions and ALL window modes
    // (split-screen, freeform, PiP, DeX).
    // rootView.draw() uses window-relative coordinates, so we must also use
    // window-relative positions for the offset — not screen-absolute.
    // getLocationOnScreen is WRONG in split-screen (Android 7+) because the
    // app window doesn't start at screen (0,0) in that mode.
    root.getLocationInWindow(rootLocation)
    getLocationInWindow(blurViewLocation)

    val scaleW = width.toFloat()  / bitmap.width.toFloat()
    val scaleH = height.toFloat() / bitmap.height.toFloat()
    val left   = (blurViewLocation[0] - rootLocation[0])
    val top    = (blurViewLocation[1] - rootLocation[1])

    val captureCanvas = Canvas(bitmap)
    captureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    captureCanvas.translate(-left / scaleW, -top / scaleH)
    captureCanvas.scale(1f / scaleW, 1f / scaleH)
    try {
      root.draw(captureCanvas)
    } catch (_: Exception) { return }

    // ② Record bitmap into RenderNode (ModernBlurView key insight:
    //    bitmap → RenderNode is stable; RenderNode → RenderNode is not)
    if (renderNode.width != bitmap.width || renderNode.height != bitmap.height) {
      renderNode.setPosition(0, 0, bitmap.width, bitmap.height)
    }
    val nodeCanvas = renderNode.beginRecording()
    nodeCanvas.drawBitmap(bitmap, 0f, 0f, null)
    renderNode.endRecording()

    // ③ Build chained RenderEffect: blur first, then tint on top (one GPU pass)
    val radius = blurRadiusFromAmount(blurAmount)
    val blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)

    val finalEffect = if (Color.alpha(overlayColor) > 0) {
      // Chain: blur → tint in single GPU pass (ModernBlurView's chained approach)
      val tintEffect = RenderEffect.createColorFilterEffect(
        BlendModeColorFilter(overlayColor, BlendMode.SRC_ATOP)
      )
      RenderEffect.createChainEffect(tintEffect, blurEffect)
    } else {
      blurEffect
    }

    renderNode.setRenderEffect(finalEffect)

    // ④ Trigger redraw — onDraw will drawRenderNode (GPU-rendered result)
    invalidate()
  }

  // ── Draw ───────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    if (!blurEnabled || !initialized) return
    val w = width.toFloat();  if (w <= 0f) return
    val h = height.toFloat(); if (h <= 0f) return
    if (!renderNode.hasDisplayList()) return

    // Step 1: save layer for progressive mask compositing
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE) {
      canvas.saveLayer(0f, 0f, w, h, null)
    } else -1

    // Step 2: draw GPU-blurred + tinted result
    // Scale from bitmap resolution back to view resolution
    val bitmapW = internalBitmap?.width?.toFloat()  ?: w
    val bitmapH = internalBitmap?.height?.toFloat() ?: h
    val scaleX = w / bitmapW
    val scaleY = h / bitmapH
    canvas.save()
    canvas.scale(scaleX, scaleY)
    canvas.drawRenderNode(renderNode)
    canvas.restore()

    // Step 3: progressive alpha mask fades the blur
    if (progressiveDirection != PROGRESSIVE_NONE && saveCount >= 0) {
      buildProgressiveShader(w, h)?.let { shader ->
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, maskPaint)
      }
      canvas.restoreToCount(saveCount)
    }

    // Step 4: noise grain overlay
    noiseBitmap?.takeIf { !it.isRecycled && noiseFactor > 0f }?.let { noise ->
      noisePaint.alpha  = (noiseFactor * 255f).toInt().coerceIn(0, 255)
      noisePaint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
      canvas.drawRect(0f, 0f, w, h, noisePaint)
    }
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

  // ── Noise bitmap ──────────────────────────────────────────────────────────

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
    blurAmount = amount.coerceIn(0f, 100f)
    scheduleFrame()
  }

  fun setOverlayColor(colorString: String?) {
    overlayColor = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    scheduleFrame()
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
    if (enabled) {
      safeAddPreDrawListener()
      scheduleFrame()
    } else {
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
    val t = amount / 100f
    return (t * t * 25f).coerceIn(1f, 25f)
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
        3 -> Color.argb(255,hex[0].toString().repeat(2).toInt(16),
                            hex[1].toString().repeat(2).toInt(16),
                            hex[2].toString().repeat(2).toInt(16))
        6 -> Color.argb(255,hex.substring(0,2).toInt(16),
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