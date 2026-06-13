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
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
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
 * BlurVibeViewApi31 — Backdrop blur for Android API 31+
**/
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

  // ── Double-buffer bitmap pool ─────────────────────────────────────────────

  private var captureBitmap: Bitmap? = null
  private var scaledBitmap:  Bitmap? = null
  @Volatile private var readyBitmap:  Bitmap? = null

  private val capturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val bitmapPaint  = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

  // ── Worker thread — blur runs here, main thread never blocks ──────────────

  private val workerThread  = HandlerThread("BlurVibeWorker31-${hashCode()}")
    .also { it.start() }
  private val workerHandler = Handler(workerThread.looper)
  private val mainHandler   = Handler(Looper.getMainLooper())

  // ── RenderScript (deprecated API 31 but still functional through API 35) ──

  @Suppress("DEPRECATION")
  private var rs:         RenderScript?        = null
  @Suppress("DEPRECATION")
  private var blurScript: ScriptIntrinsicBlur? = null
  @Suppress("DEPRECATION")
  private var inAlloc:    Allocation?          = null
  @Suppress("DEPRECATION")
  private var outAlloc:   Allocation?          = null

  // ── Root view ─────────────────────────────────────────────────────────────

  private var blurRoot: ViewGroup? = null
  private val myLoc   = IntArray(2)
  private val rootLoc = IntArray(2)

  // ── State ─────────────────────────────────────────────────────────────────

  // isCapturing: suppresses our own draw() during root.draw() capture
  // so we don't paint stale blur into the capture bitmap (static blur bug)
  var isCapturing    = false
    private set
  private var blurEnabled    = true
  private var autoUpdate     = true
  private var frameScheduled = false

  // ── Choreographer gate ────────────────────────────────────────────────────

  private val frameCallback = Choreographer.FrameCallback {
    frameScheduled = false
    if (isAttachedToWindow && blurEnabled) captureAndBlur()
  }

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (!frameScheduled && blurEnabled && autoUpdate) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
    true
  }

  // ── Paint objects ─────────────────────────────────────────────────────────

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
    safeAddPreDrawListener()
    generateNoiseBitmap()
    workerHandler.post { initRenderScript() }
    scheduleFrame()
  }

  override fun onDetachedFromWindow() {
    safeRemovePreDrawListener()
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    frameScheduled = false
    isCapturing    = false
    blurRoot       = null
    readyBitmap    = null
    noiseBitmap?.recycle(); noiseBitmap = null
    workerHandler.post {
      releaseBitmapPool()
      releaseRenderScript()
    }
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) {
      readyBitmap = null
      workerHandler.post { releaseBitmapPool() }
      scheduleFrame()
    }
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus && blurEnabled && autoUpdate) {
      safeAddPreDrawListener()
      scheduleFrame()
    }
  }

  // ── draw() — no-op during root capture ────────────────────────────────────
  //
  // Prevents stale blur output from being captured into the background bitmap.
  // When isCapturing=true, root.draw() is in progress — we skip ourselves
  // so only the content BEHIND us is captured.

  override fun draw(canvas: Canvas) {
    if (isCapturing) return
    super.draw(canvas)
  }

  // ── onDraw ────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    if (!blurEnabled) return
    val w = width.toFloat();  if (w <= 0f) return
    val h = height.toFloat(); if (h <= 0f) return

    val bmp = readyBitmap?.takeIf { !it.isRecycled } ?: run {
      // No blur ready yet — draw overlay only so view isn't invisible
      if (Color.alpha(overlayColor) > 0) {
        overlayPaint.color = overlayColor
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
      }
      super.onDraw(canvas)
      return
    }

    // Step 1: progressive mask layer
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE) {
      canvas.saveLayer(0f, 0f, w, h, null)
    } else -1

    // Step 2: draw blurred bitmap
    canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), bitmapPaint)

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
      canvas.drawRect(0f, 0f, w, h, overlayPaint)
    }

    // Step 5: noise grain
    noiseBitmap?.takeIf { !it.isRecycled && noiseFactor > 0f }?.let { noise ->
      noisePaint.alpha  = (noiseFactor * 255f).toInt().coerceIn(0, 255)
      noisePaint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
      canvas.drawRect(0f, 0f, w, h, noisePaint)
    }

    // Step 6: let ReactViewGroup draw borders/radius on top
    super.onDraw(canvas)
  }

  // ── Capture + blur pipeline ───────────────────────────────────────────────

  private fun captureAndBlur() {
    if (isCapturing) return
    val root = blurRoot ?: return
    val rw   = root.width;  if (rw <= 0) return
    val rh   = root.height; if (rh <= 0) return
    val vw   = width;       if (vw <= 0) return
    val vh   = height;      if (vh <= 0) return

    val sw = (vw / DOWNSAMPLE).toInt().coerceAtLeast(1)
    val sh = (vh / DOWNSAMPLE).toInt().coerceAtLeast(1)

    // Compute offset — window coords, correct for split-screen/freeform/PiP
    root.getLocationInWindow(rootLoc)
    getLocationInWindow(myLoc)
    val offsetX = (myLoc[0] - rootLoc[0]).toFloat()
    val offsetY = (myLoc[1] - rootLoc[1]).toFloat()

    val capture = reuseBitmap(captureBitmap, vw, vh).also { captureBitmap = it }
    val scaled  = reuseBitmap(scaledBitmap,  sw, sh).also { scaledBitmap  = it }

    // Capture — isCapturing suppresses our own draw() so root.draw() skips us
    isCapturing = true
    val c = Canvas(capture)
    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    c.translate(-offsetX, -offsetY)
    try {
      root.draw(c)
    } catch (_: Exception) {
      isCapturing = false
      return
    }
    isCapturing = false

    // Downsample + blur on worker thread (never blocks main/RenderThread)
    val captureRef = capture
    val scaledRef  = scaled
    val radius     = blurRadiusFromAmount(blurAmount)

    workerHandler.post {
      // Downsample
      Canvas(scaledRef).drawBitmap(
        captureRef,
        Rect(0, 0, captureRef.width, captureRef.height),
        Rect(0, 0, scaledRef.width,  scaledRef.height),
        capturePaint
      )
      // Multi-pass blur for wide frosted-glass spread
      repeat(BLUR_ROUNDS) { blurBitmap(scaledRef, radius) }

      // Atomic swap: readyBitmap is @Volatile — RenderThread sees new value immediately
      // We never mutate scaledRef after this point until the next capture starts
      readyBitmap = scaledRef

      mainHandler.post { invalidate() }
    }
  }

  // ── RenderScript blur ─────────────────────────────────────────────────────

  @Suppress("DEPRECATION")
  private fun initRenderScript() {
    try {
      rs = RenderScript.create(context)
      blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    } catch (_: Exception) {}
  }

  @Suppress("DEPRECATION")
  private fun blurBitmap(bmp: Bitmap, radius: Float) {
    val r  = rs         ?: return softwareBlur(bmp, radius)
    val sc = blurScript ?: return softwareBlur(bmp, radius)
    try {
      val iA = reuseAlloc(inAlloc,  bmp, r).also { inAlloc  = it }
      val oA = reuseAlloc(outAlloc, bmp, r).also { outAlloc = it }
      iA.copyFrom(bmp)
      sc.setRadius(radius.coerceIn(1f, 25f))
      sc.setInput(iA)
      sc.forEach(oA)
      oA.copyTo(bmp)
    } catch (_: Exception) { softwareBlur(bmp, radius) }
  }

  private fun softwareBlur(bmp: Bitmap, radius: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    Canvas(bmp).drawBitmap(bmp, 0f, 0f, p)
  }

  @Suppress("DEPRECATION")
  private fun releaseRenderScript() {
    inAlloc?.destroy();    inAlloc    = null
    outAlloc?.destroy();   outAlloc   = null
    blurScript?.destroy(); blurScript = null
    rs?.destroy();         rs         = null
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
      val v = rng.nextInt(256); bmp.setPixel(x, y, Color.argb(255, v, v, v))
    }
    noiseBitmap = bmp
  }

  // ── Public setters ─────────────────────────────────────────────────────────

  fun setBlurAmount(amount: Float) {
    blurAmount = amount.coerceIn(0f, 100f); scheduleFrame()
  }

  fun setOverlayColor(colorString: String?) {
    overlayColor = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    invalidate()
  }

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
    )
    // Only enable clipToOutline when radius > 0.
    // Keeping it false when not needed avoids GPU clip stack issues
    // when overflow:hidden is set on parent + Reanimated is animating.
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
    if (enabled) { safeAddPreDrawListener(); scheduleFrame() }
    else {
      safeRemovePreDrawListener()
      Choreographer.getInstance().removeFrameCallback(frameCallback)
      frameScheduled = false
      readyBitmap = null
      invalidate()
    }
  }

  fun setAutoUpdate(update: Boolean) {
    autoUpdate = update
    if (update) safeAddPreDrawListener() else safeRemovePreDrawListener()
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private fun scheduleFrame() {
    if (!frameScheduled && blurEnabled) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
  }

  private fun safeAddPreDrawListener() {
    val root = blurRoot ?: return
    val vto  = root.viewTreeObserver
    vto.removeOnPreDrawListener(preDrawListener)
    if (vto.isAlive) vto.addOnPreDrawListener(preDrawListener)
  }

  private fun safeRemovePreDrawListener() {
    blurRoot?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
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

  private fun releaseBitmapPool() {
    captureBitmap?.recycle(); captureBitmap = null
    scaledBitmap?.recycle();  scaledBitmap  = null
  }

  @Suppress("DEPRECATION")
  private fun reuseBitmap(existing: Bitmap?, w: Int, h: Int): Bitmap {
    if (existing != null && !existing.isRecycled
        && existing.width == w && existing.height == h) return existing
    existing?.recycle()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  }

  @Suppress("DEPRECATION")
  private fun reuseAlloc(existing: Allocation?, src: Bitmap, rs: RenderScript): Allocation {
    if (existing != null && existing.type.x == src.width && existing.type.y == src.height)
      return existing
    existing?.destroy()
    return Allocation.createFromBitmap(rs, src,
      Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
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
    private const val DOWNSAMPLE  = 2f   // 1/4 pixels — higher quality than legacy
    private const val BLUR_ROUNDS = 4    // 4 passes — wider Gaussian spread for API 31+
    const val PROGRESSIVE_NONE          = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT = 4
    const val PROGRESSIVE_RADIAL        = 5
  }
}