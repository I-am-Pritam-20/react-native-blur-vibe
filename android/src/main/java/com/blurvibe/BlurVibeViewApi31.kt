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

@RequiresApi(Build.VERSION_CODES.S)
class BlurVibeViewApi31(context: Context) : ReactViewGroup(context) {

  // ── Blur params ────────────────────────────────────────────────────────────

  private var blurRadiusX    = DEFAULT_BLUR_RADIUS
  private var blurRadiusY    = DEFAULT_BLUR_RADIUS
  private var overlayColor   = Color.TRANSPARENT
  private var cornerRadiusPx = 0f

  // ── Progressive blur params ────────────────────────────────────────────────

  private var progressiveDirection      = PROGRESSIVE_NONE
  private var progressiveStartIntensity = 1f
  private var progressiveEndIntensity   = 0f

  // ── Noise params ──────────────────────────────────────────────────────────

  private var noiseFactor = 0.08f
  private var noiseBitmap: Bitmap? = null
  private val noisePaint  = Paint()

  // ── RenderNodes ───────────────────────────────────────────────────────────
  //
  // contentNode: records root-view draw commands ("what's behind me")
  // blurNode:    crops + translates contentNode to this view's position,
  //              with RenderEffect blur applied
  //
  // IMPORTANT — NO setUseCompositingLayer(true) on either node.
  // Compositing layer on a re-recorded RenderNode causes GPU memory
  // thrashing and SIGSEGV on some API 31 drivers.
  //
  // IMPORTANT — NO LAYER_TYPE_HARDWARE on the view itself.
  // canvas.drawRenderNode() is only valid on a hardware-accelerated canvas
  // that is NOT itself a hardware layer — mixing them causes SIGSEGV
  // in RenderThread (the exact crash we saw in logcat).

  private val contentNode = RenderNode("BlurVibeContent")
  private val blurNode    = RenderNode("BlurVibeBlur")

  // ── Recording guard — prevents double-beginRecording crashes ─────────────
  //
  // If captureRootIntoNode fires twice in the same frame (e.g. during
  // layout + invalidate), a second beginRecording() on an active recording
  // crashes the RenderThread. This flag gates it.

  private var isCapturing = false

  // ── Paint objects ──────────────────────────────────────────────────────────

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
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
    // DO NOT call setLayerType(LAYER_TYPE_HARDWARE) here —
    // it conflicts with canvas.drawRenderNode() and causes SIGSEGV in RenderThread.
    // The view uses the default layer type (LAYER_TYPE_NONE) so its canvas is
    // the hardware-accelerated display list canvas — which supports drawRenderNode.
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
    isCapturing    = false
    blurRoot       = null
    noiseBitmap?.recycle()
    noiseBitmap = null
    // Discard RenderNode display lists to free GPU memory
    contentNode.discardDisplayList()
    blurNode.discardDisplayList()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    // Update blurNode bounds — contentNode bounds are set in captureRootIntoNode
    if (w > 0 && h > 0) {
      blurNode.setPosition(0, 0, w, h)
      applyBlurRenderEffect()
    }
  }

  // ── Capture pipeline ───────────────────────────────────────────────────────

  private fun captureRootIntoNode() {
    if (isCapturing) return  // guard against re-entrant / double recording crash
    val root = blurRoot ?: return
    val rw = root.width;  if (rw <= 0) return
    val rh = root.height; if (rh <= 0) return
    val vw = width;       if (vw <= 0) return
    val vh = height;      if (vh <= 0) return

    isCapturing = true
    try {
      // Step 1: record root-view draw into contentNode
      contentNode.setPosition(0, 0, rw, rh)
      val contentCanvas = contentNode.beginRecording()
      try {
        contentCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        root.draw(contentCanvas)
      } finally {
        contentNode.endRecording()  // always end — even if draw() throws
      }

      // Step 2: contentNode recording is FINISHED before we reference it
      // in blurNode. This is critical — drawing an actively-recording
      // RenderNode into another canvas is undefined behaviour.
      val myLoc   = IntArray(2); getLocationInWindow(myLoc)
      val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
      val offsetX = (myLoc[0] - rootLoc[0]).toFloat()
      val offsetY = (myLoc[1] - rootLoc[1]).toFloat()

      blurNode.setPosition(0, 0, vw, vh)
      applyBlurRenderEffect()

      val blurCanvas = blurNode.beginRecording()
      try {
        blurCanvas.translate(-offsetX, -offsetY)
        blurCanvas.drawRenderNode(contentNode)  // safe: contentNode recording is done
      } finally {
        blurNode.endRecording()
      }

    } finally {
      isCapturing = false
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
    val w = width.toFloat();  if (w <= 0f) return
    val h = height.toFloat(); if (h <= 0f) return

    // Guard: only draw if blurNode has a valid recorded display list
    if (!blurNode.hasDisplayList()) return

    // Step 1: save layer for progressive mask compositing
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE) {
      canvas.saveLayer(0f, 0f, w, h, null)
    } else -1

    // Step 2: draw the blurred backdrop
    canvas.drawRenderNode(blurNode)

    // Step 3: progressive alpha mask
    if (progressiveDirection != PROGRESSIVE_NONE && saveCount >= 0) {
      buildProgressiveShader(w, h)?.let { shader ->
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, maskPaint)
      }
      canvas.restoreToCount(saveCount)
    }

    // Step 4: overlay tint
    if (Color.alpha(overlayColor) > 0) {
      overlayPaint.color = overlayColor
      if (cornerRadiusPx > 0f) {
        canvas.drawRoundRect(RectF(0f, 0f, w, h), cornerRadiusPx, cornerRadiusPx, overlayPaint)
      } else {
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
      }
    }

    // Step 5: noise grain
    noiseBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
      if (noiseFactor > 0f) {
        noisePaint.alpha  = (noiseFactor * 255f).toInt().coerceIn(0, 255)
        noisePaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        canvas.drawRect(0f, 0f, w, h, noisePaint)
      }
    }
  }

  // ── Progressive shader ────────────────────────────────────────────────────

  private fun buildProgressiveShader(w: Float, h: Float): Shader? {
    val startColor = Color.argb((progressiveStartIntensity.coerceIn(0f,1f) * 255).toInt(), 0,0,0)
    val endColor   = Color.argb((progressiveEndIntensity.coerceIn(0f,1f)   * 255).toInt(), 0,0,0)
    return when (progressiveDirection) {
      PROGRESSIVE_TOP_TO_BOTTOM -> LinearGradient(0f,0f,0f,h, startColor,endColor, Shader.TileMode.CLAMP)
      PROGRESSIVE_BOTTOM_TO_TOP -> LinearGradient(0f,h,0f,0f, startColor,endColor, Shader.TileMode.CLAMP)
      PROGRESSIVE_LEFT_TO_RIGHT -> LinearGradient(0f,0f,w,0f, startColor,endColor, Shader.TileMode.CLAMP)
      PROGRESSIVE_RIGHT_TO_LEFT -> LinearGradient(w,0f,0f,0f, startColor,endColor, Shader.TileMode.CLAMP)
      PROGRESSIVE_RADIAL        -> RadialGradient(w/2f,h/2f, min(w,h)/2f, startColor,endColor, Shader.TileMode.CLAMP)
      else                      -> null
    }
  }

  // ── Noise bitmap ──────────────────────────────────────────────────────────

  private fun generateNoiseBitmap() {
    if (noiseBitmap?.isRecycled == false) return
    val size = 64
    val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val rng  = Random(42)
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
    val t = amount.coerceIn(0f, 100f) / 100f
    blurRadiusX = t * t * MAX_BLUR_RADIUS
    blurRadiusY = blurRadiusX
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

  fun setReducedTransparencyFallbackColor(@Suppress("UNUSED_PARAMETER") color: String?) { }

  fun setProgressiveBlurDirection(direction: String?) {
    progressiveDirection = when (direction) {
      "topToBottom" -> PROGRESSIVE_TOP_TO_BOTTOM
      "bottomToTop" -> PROGRESSIVE_BOTTOM_TO_TOP
      "leftToRight" -> PROGRESSIVE_LEFT_TO_RIGHT
      "rightToLeft" -> PROGRESSIVE_RIGHT_TO_LEFT
      "radial"      -> PROGRESSIVE_RADIAL
      else          -> PROGRESSIVE_NONE
    }
    invalidate()
  }

  fun setProgressiveStartIntensity(intensity: Float) {
    progressiveStartIntensity = intensity.coerceIn(0f, 1f); invalidate()
  }

  fun setProgressiveEndIntensity(intensity: Float) {
    progressiveEndIntensity = intensity.coerceIn(0f, 1f); invalidate()
  }

  fun setNoiseFactor(factor: Float) {
    noiseFactor = factor.coerceIn(0f, 1f); invalidate()
  }

  fun setEnabled(enabled: Boolean) {
    if (!enabled) {
      blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
      Choreographer.getInstance().removeFrameCallback(frameCallback)
      frameScheduled = false
      blurNode.discardDisplayList()
      contentNode.discardDisplayList()
      invalidate()
    } else {
      blurRoot?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
      scheduleFrame()
    }
  }

  fun setAutoUpdate(autoUpdate: Boolean) {
    if (autoUpdate) {
      blurRoot?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
    } else {
      blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
      Choreographer.getInstance().removeFrameCallback(frameCallback)
      frameScheduled = false
    }
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

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { }

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