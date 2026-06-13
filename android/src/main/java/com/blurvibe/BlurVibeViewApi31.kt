package com.blurvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
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
import android.util.TypedValue
import android.view.Choreographer
import android.view.PixelCopy
import android.view.Surface
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

  // ── Bitmap double-buffer ──────────────────────────────────────────────────
  //
  // pixelCopyBitmap: written by PixelCopy (on its own callback thread)
  // scaledBitmap:    written by workerThread after downsampling
  // readyBitmap:     @Volatile — RenderThread reads this in onDraw()

  private var pixelCopyBitmap: Bitmap? = null
  private var scaledBitmap:    Bitmap? = null
  @Volatile private var readyBitmap:   Bitmap? = null

  private val capturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val bitmapPaint  = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

  // ── Worker thread ─────────────────────────────────────────────────────────

  private val workerThread  = HandlerThread("BlurVibeWorker31-${hashCode()}")
    .also { it.start() }
  private val workerHandler = Handler(workerThread.looper)
  private val mainHandler   = Handler(Looper.getMainLooper())

  // ── Root / window ─────────────────────────────────────────────────────────

  private var blurRoot:  ViewGroup? = null
  private val myLoc    = IntArray(2)
  private val rootLoc  = IntArray(2)

  // ── State ─────────────────────────────────────────────────────────────────

  var isCapturing    = false
    private set
  private var blurEnabled    = true
  private var autoUpdate     = true
  private var frameScheduled = false
  private var pixelCopyInFlight = false

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
    // outlineProvider = BACKGROUND: ReactViewBackgroundDrawable implements
    // getOutline() correctly for all RN borderRadius variants automatically.
    outlineProvider = ViewOutlineProvider.BACKGROUND
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    blurRoot = findBlurRoot()
    safeAddPreDrawListener()
    generateNoiseBitmap()
    scheduleFrame()
  }

  override fun onDetachedFromWindow() {
    safeRemovePreDrawListener()
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    frameScheduled     = false
    isCapturing        = false
    pixelCopyInFlight  = false
    blurRoot           = null
    readyBitmap        = null
    noiseBitmap?.recycle(); noiseBitmap = null
    workerHandler.post { releaseBitmapPool() }
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
      // No blur ready yet — show overlay color as placeholder
      if (Color.alpha(overlayColor) > 0) {
        overlayPaint.color = overlayColor
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
      }
      // Redraw border on top even when no blur ready
      background?.draw(canvas)
      return
    }

    // Step 1: progressive mask layer
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE) {
      canvas.saveLayer(0f, 0f, w, h, null)
    } else -1

    // Step 2: blurred bitmap
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
    background?.draw(canvas)
  }

  // ── Capture pipeline — PixelCopy (API 31+) ────────────────────────────────

  private fun captureAndBlur() {
    if (isCapturing || pixelCopyInFlight) return
    val root  = blurRoot  ?: return
    val vw    = width;      if (vw <= 0) return
    val vh    = height;     if (vh <= 0) return

    // Compute this view's screen rect for PixelCopy
    getLocationInWindow(myLoc)
    root.getLocationInWindow(rootLoc)

    // Screen-space rect of the CONTENT BEHIND this view (use root location
    // as origin since PixelCopy works in window coordinates)
    val srcRect = Rect(
      myLoc[0], myLoc[1],
      myLoc[0] + vw, myLoc[1] + vh
    )

    val sw = (vw / DOWNSAMPLE).toInt().coerceAtLeast(1)
    val sh = (vh / DOWNSAMPLE).toInt().coerceAtLeast(1)

    val destBitmap = reuseBitmap(pixelCopyBitmap, vw, vh)
      .also { pixelCopyBitmap = it }

    // Hide ourselves during PixelCopy so we capture ONLY content behind us
    isCapturing       = true
    pixelCopyInFlight = true

    val window = (context as? android.app.Activity)?.window
      ?: run {
        // Fallback to root.draw() if window not available
        isCapturing       = false
        pixelCopyInFlight = false
        captureWithRootDraw()
        return
      }

    PixelCopy.request(
      window,
      srcRect,
      destBitmap,
      { result ->
        isCapturing       = false
        pixelCopyInFlight = false

        if (result != PixelCopy.SUCCESS) {
          // PixelCopy failed — fall back to root.draw()
          mainHandler.post { captureWithRootDraw() }
          return@request
        }

        // Blur on worker thread
        val captureRef = destBitmap
        workerHandler.post {
          val scaled = reuseBitmap(scaledBitmap, sw, sh).also { scaledBitmap = it }

          // Downsample
          Canvas(scaled).drawBitmap(
            captureRef,
            Rect(0, 0, captureRef.width, captureRef.height),
            Rect(0, 0, scaled.width, scaled.height),
            capturePaint
          )

          // Multi-pass software Gaussian blur
          val radius = blurRadiusFromAmount(blurAmount)
          repeat(BLUR_ROUNDS) { stackBlur(scaled, radius.toInt().coerceAtLeast(1)) }

          readyBitmap = scaled
          mainHandler.post { invalidate() }
        }
      },
      mainHandler
    )
  }

  // ── Fallback: root.draw() when PixelCopy unavailable ─────────────────────

  private fun captureWithRootDraw() {
    if (isCapturing) return
    val root = blurRoot ?: return
    val vw   = width;  if (vw <= 0) return
    val vh   = height; if (vh <= 0) return
    val sw   = (vw / DOWNSAMPLE).toInt().coerceAtLeast(1)
    val sh   = (vh / DOWNSAMPLE).toInt().coerceAtLeast(1)

    root.getLocationInWindow(rootLoc)
    getLocationInWindow(myLoc)
    val offsetX = (myLoc[0] - rootLoc[0]).toFloat()
    val offsetY = (myLoc[1] - rootLoc[1]).toFloat()

    val capture = reuseBitmap(pixelCopyBitmap, vw, vh).also { pixelCopyBitmap = it }
    val scaled  = reuseBitmap(scaledBitmap,    sw, sh).also { scaledBitmap    = it }

    isCapturing = true
    val c = Canvas(capture)
    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    c.translate(-offsetX, -offsetY)
    try { root.draw(c) } catch (_: Exception) { isCapturing = false; return }
    isCapturing = false

    val captureRef = capture
    workerHandler.post {
      Canvas(scaled).drawBitmap(
        captureRef,
        Rect(0, 0, captureRef.width, captureRef.height),
        Rect(0, 0, scaled.width, scaled.height),
        capturePaint
      )
      val radius = blurRadiusFromAmount(blurAmount)
      repeat(BLUR_ROUNDS) { stackBlur(scaled, radius.toInt().coerceAtLeast(1)) }
      readyBitmap = scaled
      mainHandler.post { invalidate() }
    }
  }

  // ── Stack blur (pure Kotlin, no deprecated APIs) ──────────────────────────
  //
  // Mario Klingemann's StackBlur — O(w×h) regardless of radius.
  // Fast, no RenderScript, works on all API levels, zero deprecation warnings.
  // Used by many production apps including Facebook's Fresco library.
  // radius clamped 1–254 (algorithm limit).

  private fun stackBlur(bmp: Bitmap, radius: Int) {
    val r = radius.coerceIn(1, 254)
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)

    val div = r + r + 1
    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val divSum = (div + 1) shr 1
    val divSumSq = divSum * divSum
    val dv = IntArray(256 * divSumSq) { it / divSumSq }

    var yi = 0
    val vmin = IntArray(maxOf(w, h))
    val vmax = IntArray(maxOf(w, h))

    val rStack = IntArray(div)
    val gStack = IntArray(div)
    val bStack = IntArray(div)

    for (y in 0 until h) {
      var rSum = 0; var gSum = 0; var bSum = 0
      var rOut = 0; var gOut = 0; var bOut = 0

      var p = pixels[yi]
      var pr = (p shr 16) and 0xFF
      var pg = (p shr 8)  and 0xFF
      var pb = p and 0xFF

      for (i in 0 until divSum) {
        rStack[i] = pr; gStack[i] = pg; bStack[i] = pb
        rSum += pr * (i + 1); gSum += pg * (i + 1); bSum += pb * (i + 1)
        rOut += pr; gOut += pg; bOut += pb
      }
      for (i in 1 until divSum) {
        val ii = if (i <= wm) i else wm
        p  = pixels[yi + ii]
        pr = (p shr 16) and 0xFF; pg = (p shr 8) and 0xFF; pb = p and 0xFF
        rStack[i + r] = pr; gStack[i + r] = pg; bStack[i + r] = pb
        rSum += pr * (divSum - i)
        gSum += pg * (divSum - i)
        bSum += pb * (divSum - i)
      }

      var si = r
      for (x in 0 until w) {
        pixels[yi + x] = (-0x1000000 or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum])
        rSum -= rOut; gSum -= gOut; bSum -= bOut
        rOut -= rStack[si]; gOut -= gStack[si]; bOut -= bStack[si]
        var sip = si + divSum
        if (sip >= div) sip -= div
        pr = rStack[sip]; pg = gStack[sip]; pb = bStack[sip]
        rOut += pr; gOut += pg; bOut += pb
        rSum += rOut; gSum += gOut; bSum += bOut
        if (x < r) vmin[x] = x + r + 1 else if (x + r < wm) vmin[x] = x + r + 1 else vmin[x] = wm
        if (x > r) vmax[x] = x - r else vmax[x] = 0
        val sp = pixels[yi + vmin[x]]
        val vp = pixels[yi + vmax[x]]
        rStack[sip] = (sp shr 16) and 0xFF
        gStack[sip] = (sp shr 8)  and 0xFF
        bStack[sip] = sp and 0xFF
        rOut += rStack[sip] - ((vp shr 16) and 0xFF)
        gOut += gStack[sip] - ((vp shr 8) and 0xFF)
        bOut += bStack[sip] - (vp and 0xFF)
        if (++si >= div) si = 0
      }
      yi += w
    }

    var xi = 0
    for (x in 0 until w) {
      var rSum = 0; var gSum = 0; var bSum = 0
      var rOut = 0; var gOut = 0; var bOut = 0
      var yp = -r * w
      var p  = pixels[xi]
      var pr = (p shr 16) and 0xFF
      var pg = (p shr 8)  and 0xFF
      var pb = p and 0xFF
      for (i in 0 until divSum) {
        rStack[i] = pr; gStack[i] = pg; bStack[i] = pb
        rSum += pr * (i + 1); gSum += pg * (i + 1); bSum += pb * (i + 1)
        rOut += pr; gOut += pg; bOut += pb
      }
      for (i in 1..r) {
        if (i <= hm) yp += w
        p  = pixels[xi + yp]
        pr = (p shr 16) and 0xFF; pg = (p shr 8) and 0xFF; pb = p and 0xFF
        rStack[i + r] = pr; gStack[i + r] = pg; bStack[i + r] = pb
        rSum += pr * (divSum - i); gSum += pg * (divSum - i); bSum += pb * (divSum - i)
      }
      var si = r
      for (y in 0 until h) {
        pixels[xi + y * w] = (-0x1000000 or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum])
        rSum -= rOut; gSum -= gOut; bSum -= bOut
        rOut -= rStack[si]; gOut -= gStack[si]; bOut -= bStack[si]
        var sip = si + divSum; if (sip >= div) sip -= div
        pr = rStack[sip]; pg = gStack[sip]; pb = bStack[sip]
        rOut += pr; gOut += pg; bOut += pb
        rSum += rOut; gSum += gOut; bSum += bOut
        vmin[y] = if (y + r < hm) (y + r + 1) * w else hm * w
        vmax[y] = if (y > r) (y - r) * w else 0
        val sp = pixels[xi + vmin[y]]
        val vp = pixels[xi + vmax[y]]
        rStack[sip] = (sp shr 16) and 0xFF
        gStack[sip] = (sp shr 8)  and 0xFF
        bStack[sip] = sp and 0xFF
        rOut += rStack[sip] - ((vp shr 16) and 0xFF)
        gOut += gStack[sip] - ((vp shr 8) and 0xFF)
        bOut += bStack[sip] - (vp and 0xFF)
        if (++si >= div) si = 0
      }
      xi++
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
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
      frameScheduled = false; readyBitmap = null; invalidate()
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
    pixelCopyBitmap?.recycle(); pixelCopyBitmap = null
    scaledBitmap?.recycle();    scaledBitmap    = null
  }

  private fun reuseBitmap(existing: Bitmap?, w: Int, h: Int): Bitmap {
    if (existing != null && !existing.isRecycled
        && existing.width == w && existing.height == h) return existing
    existing?.recycle()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  }

  private fun blurRadiusFromAmount(amount: Float): Float {
    val t = amount.coerceIn(0f, 100f) / 100f
    return (2f + t * 22f)   // 2–24, StackBlur works well in this range per pass
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
    private const val DOWNSAMPLE  = 2f
    private const val BLUR_ROUNDS = 3
    const val PROGRESSIVE_NONE          = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT = 4
    const val PROGRESSIVE_RADIAL        = 5
  }
}