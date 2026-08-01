package com.blurvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import com.facebook.react.views.view.ReactViewGroup

/**
 * LiquidGlassShaderView — real-time optical refraction ("liquid glass"),
 * Android API 33+ only (RuntimeShader requires TIRAMISU).
 *
 * ─── Why this uses a FRESH RenderNode every frame ────────────────────────────
 *
 * A natural design is to persist ONE RenderNode field, re-recording it each
 * frame (record in onPreDraw, draw later in the same frame's real draw
 * pass). That pattern is exactly what caused a confirmed SIGSEGV in this
 * project's own earlier blur implementation: Reanimated can trigger an
 * independent invalidate()/redraw cycle for a DIFFERENT frame while a
 * capture+record is in flight, so by the time RenderThread replays a
 * QUEUED reference to that RenderNode from an earlier frame, the main
 * thread may have already started re-recording the SAME object for a
 * newer one — a cross-frame race on shared, persistent RenderNode state.
 *
 * The fix that resolved that crash (see BlurVibeViewApi31) was allocating
 * a brand new RenderNode object every single onDraw() call — RenderThread
 * only ever replays a fully-recorded, immutable, frame-specific object;
 * the main thread's next recording always targets a DIFFERENT object. That
 * same discipline is used here.
 *
 * ─── Backdrop capture ────────────────────────────────────────────────────────
 *
 * Reuses BlurCaptureCoordinator — the same shared, once-per-root capture
 * BlurVibeViewApi31 uses — rather than doing an independent per-instance
 * capture. Adding more LiquidGlassViews (or mixing them with BlurViews on
 * the same screen) doesn't add capture cost.
 *
 * ─── Pipeline ─────────────────────────────────────────────────────────────────
 *
 * cropped backdrop bitmap → [optional blur RenderEffect] → refraction
 * RuntimeShader (reads the previous stage's output via its "content"
 * child-shader input) → drawn via a fresh RenderNode.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LiquidGlassShaderView(context: Context) : ReactViewGroup(context) {

  // ── Props ──────────────────────────────────────────────────────────────────

  private var refractionAmount = 40f      // 0-100, JS-facing
  private var edgeWidthDp       = 24f
  private var curvatureBlend    = 0.5f
  private var dispersion        = 0.35f
  private var saturationBoost   = 1.1f
  private var contrastBoost     = 1.05f
  private var brightnessLift    = 0.02f
  private var tintColor         = Color.argb(20, 255, 255, 255)
  private var blurAmount        = 0f      // 0 = pure clear refraction, >0 = frosted
  private var cornerRadiusDp    = 20f

  // ── Capture / crop state ──────────────────────────────────────────────────

  private var coordinator: BlurCaptureCoordinator? = null
  private var blurRoot: ViewGroup? = null
  private var croppedBitmap: Bitmap? = null
  private var cropCanvas: Canvas? = null
  private var initialized = false

  private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val cropSrcRect = Rect()
  private val cropDstRect = RectF()

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    outlineProvider = ViewOutlineProvider.BACKGROUND
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    val root = findBlurRoot() ?: return
    blurRoot = root
    coordinator = BlurCaptureCoordinator.forRoot(root)
    if (measuredWidth > 0 && measuredHeight > 0) initBitmap()
    coordinator?.register(this)
  }

  override fun onDetachedFromWindow() {
    coordinator?.unregister(this)
    coordinator = null
    blurRoot = null
    initialized = false
    croppedBitmap?.recycle()
    croppedBitmap = null
    cropCanvas = null
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) initBitmap()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus) coordinator?.reAttachIfNeeded()
  }

  private fun initBitmap() {
    val w = measuredWidth;  if (w <= 0) return
    val h = measuredHeight; if (h <= 0) return
    croppedBitmap?.recycle()
    croppedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    cropCanvas = Canvas(croppedBitmap!!)
    initialized = true
  }

  // ── draw() — skip self during the coordinator's shared capture ───────────

  override fun draw(canvas: Canvas) {
    if (canvas is BlurVibeCanvas) return
    super.draw(canvas)
  }

  // ── Crop this view's region out of the shared capture ────────────────────

  private fun refreshCrop() {
    val root = blurRoot ?: return
    val shared = coordinator?.currentBitmap ?: return
    val bitmap = croppedBitmap ?: return
    val canvas = cropCanvas ?: return
    if (bitmap.isRecycled) return

    val myLoc = IntArray(2);   getLocationInWindow(myLoc)
    val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
    val leftPx = (myLoc[0] - rootLoc[0]).toFloat()
    val topPx  = (myLoc[1] - rootLoc[1]).toFloat()

    val factor = BlurCaptureCoordinator.DOWNSAMPLE_FACTOR
    val srcLeft   = (leftPx / factor).toInt().coerceIn(0, shared.width)
    val srcTop    = (topPx  / factor).toInt().coerceIn(0, shared.height)
    val srcRight  = (srcLeft + (width  / factor).toInt()).coerceIn(srcLeft, shared.width)
    val srcBottom = (srcTop  + (height / factor).toInt()).coerceIn(srcTop, shared.height)
    if (srcRight <= srcLeft || srcBottom <= srcTop) return

    bitmap.eraseColor(Color.TRANSPARENT)
    cropSrcRect.set(srcLeft, srcTop, srcRight, srcBottom)
    cropDstRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    canvas.drawBitmap(shared, cropSrcRect, cropDstRect, cropPaint)
  }

  // ── onDraw — build the shader pipeline fresh, every frame ─────────────────

  override fun onDraw(canvas: Canvas) {
    if (!initialized) return
    val w = width.toFloat();  if (w <= 0f) return
    val h = height.toFloat(); if (h <= 0f) return

    refreshCrop()
    val bmp = croppedBitmap?.takeIf { !it.isRecycled } ?: return

    // Fresh RenderNode every frame — see class doc for why.
    val node = RenderNode("LiquidGlassFrame")
    node.setPosition(0, 0, bmp.width, bmp.height)
    val nodeCanvas = node.beginRecording()
    nodeCanvas.drawBitmap(bmp, 0f, 0f, null)
    node.endRecording()

    val radiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp, context.resources.displayMetrics
    )
    val edgeWidthPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, edgeWidthDp, context.resources.displayMetrics
    )

    val shader = RuntimeShader(shaderSource)
    shader.setFloatUniform("panelSize", bmp.width.toFloat(), bmp.height.toFloat())
    shader.setFloatUniform("panelOffset", 0f, 0f)
    shader.setFloatUniform("cornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
    shader.setFloatUniform("edgeZoneWidth", edgeWidthPx)
    shader.setFloatUniform("bulgeStrength", refractionAmount.coerceIn(0f, 100f) / 100f * edgeWidthPx)
    shader.setFloatUniform("curvatureBlend", curvatureBlend.coerceIn(0f, 1f))
    shader.setFloatUniform("dispersion", dispersion.coerceIn(0f, 1f))
    shader.setFloatUniform("saturationBoost", saturationBoost)
    shader.setFloatUniform("contrastBoost", contrastBoost)
    shader.setFloatUniform("brightnessLift", brightnessLift)
    shader.setFloatUniform(
      "tintColor",
      Color.red(tintColor) / 255f, Color.green(tintColor) / 255f,
      Color.blue(tintColor) / 255f, Color.alpha(tintColor) / 255f
    )
    shader.setFloatUniform("tintStrength", 1f)

    val refractEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")

    val finalEffect = if (blurAmount > 0f) {
      val blurPx = blurAmount.coerceIn(0f, 100f) / 100f * 25f
      val blurEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
      RenderEffect.createChainEffect(refractEffect, blurEffect)
    } else {
      refractEffect
    }

    node.setRenderEffect(finalEffect)

    canvas.save()
    canvas.clipRect(0f, 0f, w, h)
    canvas.drawRenderNode(node)
    canvas.restore()

    background?.draw(canvas)
  }

  // ── Public setters ─────────────────────────────────────────────────────────

  fun setRefractionAmount(v: Float) { refractionAmount = v; invalidate() }
  fun setEdgeWidth(dp: Float)        { edgeWidthDp = dp; invalidate() }
  fun setCurvatureBlend(v: Float)    { curvatureBlend = v; invalidate() }
  fun setDispersion(v: Float)        { dispersion = v; invalidate() }
  fun setSaturationBoost(v: Float)   { saturationBoost = v; invalidate() }
  fun setContrastBoost(v: Float)     { contrastBoost = v; invalidate() }
  fun setBrightnessLift(v: Float)    { brightnessLift = v; invalidate() }
  fun setBlurAmount(v: Float)        { blurAmount = v; invalidate() }

  fun setTintColor(colorString: String?) {
    tintColor = parseHexColor(colorString ?: "transparent") ?: Color.argb(20, 255, 255, 255)
    invalidate()
  }

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusDp = radiusDp
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        val px = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
        )
        outline.setRoundRect(0, 0, view.width, view.height, px)
      }
    }
    clipToOutline = radiusDp > 0f
    invalidate()
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

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
    // Cached once, shared by every LiquidGlassShaderView instance in the
    // app — the shader source text never changes at runtime.
    @Volatile private var cachedShaderSourceValue: String? = null

    private fun loadShaderSource(context: Context): String {
      val resId = context.resources.getIdentifier(
        "liquid_glass_refraction", "raw", context.packageName
      )
      return context.resources.openRawResource(resId)
        .bufferedReader()
        .use { it.readText() }
    }
  }

  private val shaderSource: String
    get() = cachedShaderSourceValue ?: loadShaderSource(context).also { cachedShaderSourceValue = it }
}
