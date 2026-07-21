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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import com.facebook.react.views.view.ReactViewGroup
import kotlin.math.min
import kotlin.random.Random

/**
 * BlurVibeViewApi31 — Backdrop blur for Android API 31+
 *
 * ─── Shared capture (fixes multi-BlurView breakage) ──────────────────────────
 *
 * Capture (root.draw()) is now owned by ONE BlurCaptureCoordinator per root,
 * shared across every BlurView under it — see that class's doc for the full
 * rationale. Previously, each BlurVibeViewApi31 called root.draw() entirely
 * on its own; N blur views under one root meant N full offscreen re-renders
 * of the whole view tree per frame — cost scaling linearly with the number
 * of mounted blur views, which broke down inside scrolling lists or screens
 * with several blur surfaces.
 *
 * This view's job is now: crop ITS OWN screen-space region out of the
 * coordinator's single shared raw bitmap, then apply its own GPU blur via
 * RenderEffect to that (small) crop — preserving full per-instance
 * blurAmount/overlayColor/progressiveBlur/noise customization while
 * eliminating the redundant per-view root.draw() cost.
 *
 * ─── Self-exclusion for overlapping/stacked BlurViews ────────────────────────
 *
 * draw() checks `if (canvas is BlurVibeCanvas) return` — a typed marker
 * Canvas the coordinator uses ONLY for its shared capture pass. Checking the
 * canvas TYPE (not a per-instance flag) means this correctly excludes THIS
 * view — and every other BlurVibeView/BlurVibeViewApi31 under the same
 * root, including ones nested or stacked on top of each other — from that
 * one shared capture, with zero coordination needed between multiple
 * simultaneously-registered views. A real on-screen draw always uses the
 * hardware display canvas, never this marker.
 *
 * ─── Update trigger ───────────────────────────────────────────────────────────
 *
 * The coordinator invalidates every registered view right after each shared
 * capture completes (within the same frame's pre-draw phase, before the
 * real draw traversal begins). Android then calls THIS view's onDraw()
 * later in that same frame — which is when crop + GPU blur actually happen
 * (see refreshFromSharedCapture() / drawHardwarePath() below), guaranteed
 * to read freshly-captured shared content because Android's pipeline always
 * finishes all pre-draw work before the real draw traversal starts. No
 * separate per-view preDrawListener needed any more.
 *
 * ─── RenderEffect thread safety (per-frame RenderNode pattern) ──────────────
 *
 * Each onDraw() creates a FRESH RenderNode for that frame:
 *   beginRecording() → drawBitmap(capturedBitmap) → endRecording()
 *   setRenderEffect(RenderEffect.createBlurEffect(localRadius)) — UNSCALED
 *   canvas.scale(scaleW, scaleH) → canvas.drawRenderNode(freshNode)
 *
 * RenderThread replays nodeN (immutable after endRecording) while the main
 * thread creates a completely different nodeN+1 object for the next frame —
 * no shared mutable state between threads, so no SIGSEGV risk (unlike an
 * earlier version that reused ONE RenderNode across frames while
 * Reanimated-driven redraws raced its recording).
 *
 * localRadius is applied UNSCALED to the RenderEffect, in the bitmap's own
 * downsampled coordinate space. canvas.scale(scaleW, scaleH) during draw is
 * the ONLY upscale step — it naturally magnifies the blur radius along with
 * everything else when the bitmap is drawn at view size.
 *
 * ─── Style props (borderRadius, borderWidth, borderColor) ───────────────────
 *
 * outlineProvider = BACKGROUND: ReactViewBackgroundDrawable.getOutline()
 * handles all RN borderRadius variants. clipToOutline only enabled when
 * radius > 0. background?.draw(canvas) at END of onDraw() redraws the
 * border ON TOP of blur.
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
  //
  // The underlying noise bitmap/shader are SHARED globally (companion
  // object, lazy) — see companion for rationale. noisePaint stays
  // per-instance since each view's noiseFactor (alpha) differs.

  private var noiseFactor = 0.08f
  private val noisePaint  = Paint()

  // ── Own crop destination bitmap ───────────────────────────────────────────
  //
  // Sized to THIS view's own downsampled, stride-rounded dimensions.
  // Populated by cropping a region out of coordinator.currentBitmap, then
  // GPU-blurred via the per-frame RenderNode in drawHardwarePath().
  // Thread safety: cropped in onDraw() (main thread), the resulting
  // RenderNode is a fresh object per frame — see class doc.

  private var capturedBitmap: Bitmap? = null
  private var cropCanvas: Canvas? = null
  private var initialized = false

  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val cropPaint   = Paint(Paint.FILTER_BITMAP_FLAG)

  // ── Reused per-frame objects (avoid GC pressure from allocating every frame) ─

  private val cropSrcRect = Rect()
  private val cropDstRect = RectF()
  private val drawDstRect = RectF()

  // ── Cached RenderEffect ────────────────────────────────────────────────────
  //
  // RenderEffect is immutable/shareable data (like a Paint or Shader) — safe
  // to reuse across DIFFERENT RenderNode instances as long as its parameters
  // haven't changed. blurAmount typically stays constant for many consecutive
  // frames, so only rebuilding when the radius actually changes avoids an
  // allocation on every single frame. This is independent of (and compatible
  // with) keeping the RenderNode itself fresh every frame for thread safety —
  // see class doc.

  private var cachedBlurEffect: RenderEffect? = null
  private var cachedRadius: Float = -1f

  // ── Root / coordinator ─────────────────────────────────────────────────────

  private var blurRoot: ViewGroup? = null
  private var coordinator: BlurCaptureCoordinator? = null
  private val rootLocation     = IntArray(2)
  private val blurViewLocation = IntArray(2)

  // ── State ─────────────────────────────────────────────────────────────────

  private var blurEnabled = true
  private var autoUpdate  = true

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
    val root = findBlurRoot() ?: return
    blurRoot = root
    coordinator = BlurCaptureCoordinator.forRoot(root)
    acquireSharedNoiseBitmap()
    if (measuredWidth > 0 && measuredHeight > 0) initBlur()
    coordinator?.register(this)
  }

  override fun onDetachedFromWindow() {
    coordinator?.unregister(this)
    coordinator = null
    initialized = false
    blurRoot    = null
    capturedBitmap?.recycle(); capturedBitmap = null
    cropCanvas = null
    releaseSharedNoiseBitmap()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) initBlur()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    // Android can silently kill and replace a ViewTreeObserver during
    // split-screen/PiP/freeform transitions. Ask the coordinator (which
    // owns the actual listener) to re-attach to the current observer —
    // safe/cheap to call redundantly from multiple views under the same root.
    if (hasWindowFocus) coordinator?.reAttachIfNeeded()
  }

  // ── Init own crop-destination bitmap ──────────────────────────────────────

  private fun initBlur() {
    val w = measuredWidth;  if (w <= 0) return
    val h = measuredHeight; if (h <= 0) return

    val factor = BlurCaptureCoordinator.DOWNSAMPLE_FACTOR
    val scaledW = roundToStride((w / factor).toInt().coerceAtLeast(1))
    val roundScale = w.toFloat() / scaledW
    val scaledH = (h / roundScale).toInt().coerceAtLeast(1)

    capturedBitmap?.recycle()
    capturedBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
    cropCanvas     = Canvas(capturedBitmap!!)
    initialized    = true
  }

  private fun roundToStride(v: Int): Int {
    if (v % ROUNDING_VALUE == 0) return v
    return v - (v % ROUNDING_VALUE) + ROUNDING_VALUE
  }

  // ── Crop (replaces the old per-view root.draw() capture step) ────────────

  private fun refreshFromSharedCapture() {
    val root   = blurRoot       ?: return
    val shared = coordinator?.currentBitmap ?: return   // not ready yet — keep prior content
    val bitmap = capturedBitmap ?: return
    val canvas = cropCanvas     ?: return
    if (bitmap.isRecycled) return

    getLocationInWindow(blurViewLocation)
    root.getLocationInWindow(rootLocation)
    val leftPx = (blurViewLocation[0] - rootLocation[0]).toFloat()
    val topPx  = (blurViewLocation[1] - rootLocation[1]).toFloat()

    val factor = BlurCaptureCoordinator.DOWNSAMPLE_FACTOR
    val srcLeft   = (leftPx / factor).toInt().coerceIn(0, shared.width)
    val srcTop    = (topPx  / factor).toInt().coerceIn(0, shared.height)
    val srcRight  = (srcLeft + (width  / factor).toInt()).coerceIn(srcLeft, shared.width)
    val srcBottom = (srcTop  + (height / factor).toInt()).coerceIn(srcTop, shared.height)
    if (srcRight <= srcLeft || srcBottom <= srcTop) return  // off-screen / zero-size this frame

    bitmap.eraseColor(Color.TRANSPARENT)
    cropSrcRect.set(srcLeft, srcTop, srcRight, srcBottom)
    cropDstRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    canvas.drawBitmap(shared, cropSrcRect, cropDstRect, cropPaint)
  }

  // ── draw() — skip self during the coordinator's shared capture ───────────
  //
  // Marker-canvas self-exclusion: if (canvas instanceof BlurVibeCanvas) return false;
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

    if (autoUpdate) refreshFromSharedCapture()

    val bmp = capturedBitmap?.takeIf { !it.isRecycled } ?: return

    if (canvas.isHardwareAccelerated) {
      drawHardwarePath(canvas, bmp, w, h)
    } else {
      drawSoftwarePath(canvas, bmp, w, h)
    }
  }

  // ── Hardware path (API 31+, normal case) ──────────────────────────────────
  //
  // Per-frame RenderNode + RenderEffect.createBlurEffect() — see class doc
  // for the full thread-safety rationale.

  private fun drawHardwarePath(canvas: Canvas, bmp: Bitmap, w: Float, h: Float) {
    val localRadius = localBlurRadius(blurAmount)

    // Fresh RenderNode per frame
    val blurNode = RenderNode("BlurVibeFrame")
    blurNode.setPosition(0, 0, bmp.width, bmp.height)

    val nodeCanvas = blurNode.beginRecording()
    nodeCanvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
    blurNode.endRecording()

    // Apply RenderEffect — UNSCALED. canvas.scale() below is the ONLY
    // upscale step. Cached: RenderEffect is immutable/shareable data, safe
    // to reuse across the FRESH RenderNode created above (the node must be
    // fresh per-frame for thread safety; the effect describing WHAT to do
    // to it does not need to be, and typically stays constant for many
    // consecutive frames since blurAmount rarely changes every frame).
    var effect = cachedBlurEffect
    if (effect == null || cachedRadius != localRadius) {
      effect = RenderEffect.createBlurEffect(localRadius, localRadius, Shader.TileMode.CLAMP)
      cachedBlurEffect = effect
      cachedRadius = localRadius
    }
    blurNode.setRenderEffect(effect)

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

    // Noise grain — sharedNoiseShader is a single static Shader instance
    // (see companion object) reused by every BlurVibeViewApi31 in the app;
    // only alpha (this view's own noiseFactor) is set per-instance.
    if (noiseFactor > 0f) {
      sharedNoiseShader?.let { shader ->
        noisePaint.alpha  = (noiseFactor * 255f).toInt().coerceIn(0, 255)
        noisePaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, noisePaint)
      }
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
    drawDstRect.set(0f, 0f, w, h)
    canvas.drawBitmap(bmp, null, drawDstRect, bitmapPaint)
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

  // ── Shared noise bitmap (resource-usage fix) ──────────────────────────────
  //
  // generateNoiseBitmap() used a fixed seed (Random(42)) — meaning EVERY
  // instance produced the IDENTICAL 64×64 bitmap. Generating it per-instance
  // (via a 4096-call setPixel() loop — a known-slow API, one JNI crossing
  // per pixel) on every single view attach was pure waste: same content,
  // regenerated repeatedly, especially costly with FlatList recycling where
  // attach happens often. Now generated ONCE, shared by every
  // BlurVibeViewApi31 in the app, ref-counted like the coordinator registry.

  private fun acquireSharedNoiseBitmap() {
    if (sharedNoiseBitmap == null) {
      val size = 64
      val pixels = IntArray(size * size)
      val rng = Random(42)
      for (i in pixels.indices) {
        val v = rng.nextInt(256)
        pixels[i] = Color.argb(255, v, v, v)
      }
      val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      bmp.setPixels(pixels, 0, size, 0, 0, size, size)  // batch call — not a setPixel() loop
      sharedNoiseBitmap = bmp
      sharedNoiseShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    noiseRefCount++
  }

  private fun releaseSharedNoiseBitmap() {
    noiseRefCount = (noiseRefCount - 1).coerceAtLeast(0)
    if (noiseRefCount == 0) {
      sharedNoiseBitmap?.recycle()
      sharedNoiseBitmap = null
      sharedNoiseShader = null
    }
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

  // enabled/autoUpdate no longer manage any listener — the coordinator's
  // single shared listener stays active as long as ANY view is registered
  // under that root, regardless of individual enabled/autoUpdate state.
  // These flags now purely gate what onDraw() does each frame (see above).

  fun applyBlurEnabled(enabled: Boolean) {
    blurEnabled = enabled
    invalidate()
  }

  fun setAutoUpdate(update: Boolean) {
    autoUpdate = update
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
    // "Felt" radius — the desired blur strength expressed in full-resolution-
    // equivalent pixels, range 1–100. This curve MUST stay numerically
    // identical to BlurVibeView.mapBlurAmount()'s felt curve, and both
    // divide by BlurCaptureCoordinator.DOWNSAMPLE_FACTOR directly (not a
    // duplicated literal), so blurAmount produces matching visual density
    // on both API < 31 and API 31+, and a mismatch here can never silently
    // corrupt crop math either (both views crop from the SAME shared bitmap).
    //
    // blurAmount=10  → felt≈10.9 → local≈1.8   (backdrop-blur-sm)
    // blurAmount=50  → felt≈50.5 → local≈8.4   (backdrop-blur-xl)
    // blurAmount=100 → felt=100  → local≈16.7  (maximum)
    val felt = 1f + (amount.coerceIn(0f, 100f) / 100f) * 99f
    return (felt / BlurCaptureCoordinator.DOWNSAMPLE_FACTOR).coerceIn(0.5f, 40f)
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
    private const val ROUNDING_VALUE = 64   // stride alignment (Samsung)
    const val PROGRESSIVE_NONE          = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT = 4
    const val PROGRESSIVE_RADIAL        = 5

    // ── Shared noise bitmap — global, ref-counted, main-thread only ─────────
    // Fixed seed means every instance would produce identical content anyway
    // — see acquireSharedNoiseBitmap() above for full rationale.
    private var sharedNoiseBitmap: Bitmap? = null
    private var sharedNoiseShader: Shader? = null
    private var noiseRefCount = 0
  }
}
