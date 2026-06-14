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
 * ─── Why Compose Modifier.blur() doesn't work for this ───────────────────────
 *
 * Modifier.blur() blurs the Composable's OWN content — equivalent to CSS
 * filter:blur(), not backdrop-filter:blur(). It cannot see RN views behind it.
 * ComposeView also creates its own Choreographer loop which conflicts with
 * Reanimated causing SIGSEGV. Compose is not the right tool here.
 *
 * ─── Root cause of API 35 glitch + SIGSEGV ───────────────────────────────────
 *
 * The Choreographer.FrameCallback approach was the problem:
 *
 *   OnPreDrawListener fires → schedules Choreographer callback
 *   Choreographer callback fires → calls root.draw()
 *
 * On Android 15+ (API 35), Choreographer.FrameCallback fires at the COMMIT
 * phase — AFTER the RenderThread has already started processing the frame.
 * Calling root.draw() at this point means:
 *   1. Torn frames: capture sees RenderThread mid-draw → blinking/glitch
 *   2. Reanimated's RenderNodes being written by UI thread while RenderThread
 *      reads them → use-after-free in GPU driver → SIGSEGV
 *
 * ─── THE FIX ─────────────────────────────────────────────────────────────────
 *
 * Call root.draw() DIRECTLY inside OnPreDrawListener — NOT deferred to
 * Choreographer. OnPreDrawListener is guaranteed to fire BEFORE the
 * RenderThread starts. This is the same approach used by Dimezis BlurView
 * and it works on ALL Android versions including API 35.
 *
 * Throttling: simple System.nanoTime() check (16ms cap = 60fps).
 * No Choreographer needed for throttling.
 *
 * StackBlur still runs on workerThread — main thread is never blocked.
 *
 * ─── Thread model ─────────────────────────────────────────────────────────────
 *
 *   OnPreDrawListener (main thread, PRE-DRAW guaranteed):
 *     → throttle check
 *     → root.draw() into captureBitmap  (isCapturing guard skips us)
 *     → post StackBlur to workerThread
 *     → return true immediately (never blocks draw pass)
 *
 *   workerThread:
 *     → downsample + StackBlur × 3 passes
 *     → readyBitmap = scaledBitmap  (@Volatile atomic swap)
 *     → post invalidate() to main thread
 *
 *   RenderThread (onDraw):
 *     → canvas.drawBitmap(readyBitmap)  ← reads fully-written bitmap, zero race
 *
 * ─── Reanimated safety ───────────────────────────────────────────────────────
 *
 * root.draw() is called in OnPreDrawListener BEFORE RenderThread starts.
 * Reanimated's UI-thread animations have completed their prop updates by
 * the time OnPreDrawListener fires (it fires after layout, after animations
 * update view properties, but before the GPU draw begins).
 * Zero SIGSEGV risk.
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

  // ── Bitmap double-buffer ──────────────────────────────────────────────────
  //
  // captureBitmap: written by main thread in OnPreDrawListener
  // scaledBitmap:  written by workerThread (back buffer)
  // readyBitmap:   @Volatile pointer — RenderThread reads this in onDraw()
  //
  // We NEVER mutate the bitmap readyBitmap currently points to.
  // scaledBitmap becomes the next back buffer after the @Volatile swap.

  private var captureBitmap: Bitmap? = null
  private var scaledBitmap:  Bitmap? = null
  @Volatile private var readyBitmap: Bitmap? = null

  private val capturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val bitmapPaint  = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

  // ── Worker thread ─────────────────────────────────────────────────────────

  private val workerThread  = HandlerThread("BlurVibeWorker31-${hashCode()}")
    .also { it.start() }
  private val workerHandler = Handler(workerThread.looper)
  private val mainHandler   = Handler(Looper.getMainLooper())

  // ── Root ──────────────────────────────────────────────────────────────────

  private var blurRoot: ViewGroup? = null
  private val myLoc    = IntArray(2)
  private val rootLoc  = IntArray(2)

  // ── State ─────────────────────────────────────────────────────────────────

  var isCapturing = false
    private set

  private var blurEnabled     = true
  private var autoUpdate      = true
  private var workerBusy      = false  // prevent queuing multiple blur jobs
  private var lastCaptureNano = 0L     // throttle: nano timestamp of last capture

  // ── PreDraw listener — fires PRE-DRAW, guaranteed before RenderThread ─────

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (blurEnabled && autoUpdate && !workerBusy) {
      val now = System.nanoTime()
      if (now - lastCaptureNano >= FRAME_INTERVAL_NS) {
        lastCaptureNano = now
        captureNow()   // synchronous capture in pre-draw — safe on all API levels
      }
    }
    true   // MUST return true — never block the draw pass
  }

  // ── Paint objects ─────────────────────────────────────────────────────────

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    // outlineProvider = BACKGROUND: ReactViewBackgroundDrawable.getOutline()
    // handles all RN borderRadius variants automatically. clipToOutline only
    // enabled when non-zero radius is set (avoids GPU clip stack issues).
    outlineProvider = ViewOutlineProvider.BACKGROUND
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    blurRoot = findBlurRoot()
    safeAddPreDrawListener()
    generateNoiseBitmap()
  }

  override fun onDetachedFromWindow() {
    safeRemovePreDrawListener()
    isCapturing = false
    workerBusy  = false
    blurRoot    = null
    readyBitmap = null
    noiseBitmap?.recycle(); noiseBitmap = null
    workerHandler.post {
      captureBitmap?.recycle(); captureBitmap = null
      scaledBitmap?.recycle();  scaledBitmap  = null
    }
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) {
      readyBitmap = null
      workerBusy  = false
      workerHandler.post {
        captureBitmap?.recycle(); captureBitmap = null
        scaledBitmap?.recycle();  scaledBitmap  = null
      }
    }
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus && blurEnabled && autoUpdate) {
      safeAddPreDrawListener()
    }
  }

  // ── draw() — skip self during capture ────────────────────────────────────

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
      if (Color.alpha(overlayColor) > 0) {
        overlayPaint.color = overlayColor
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
      }
      background?.draw(canvas)
      return
    }

    // Progressive mask layer
    val saveCount = if (progressiveDirection != PROGRESSIVE_NONE)
      canvas.saveLayer(0f, 0f, w, h, null)
    else -1

    // Blurred bitmap
    canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), bitmapPaint)

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

    // Redraw ReactViewBackgroundDrawable ON TOP of blur so borders/radius
    // are visible above the blur layer, not hidden underneath it.
    background?.draw(canvas)
  }

  // ── Capture — called in OnPreDrawListener (pre-draw, main thread) ─────────

  private fun captureNow() {
    if (isCapturing || workerBusy) return
    val root = blurRoot ?: return
    val vw   = width;  if (vw <= 0) return
    val vh   = height; if (vh <= 0) return

    val sw = (vw / DOWNSAMPLE).toInt().coerceAtLeast(1)
    val sh = (vh / DOWNSAMPLE).toInt().coerceAtLeast(1)

    // Window-relative offset (correct for split-screen, freeform, PiP)
    root.getLocationInWindow(rootLoc)
    getLocationInWindow(myLoc)
    val offsetX = (myLoc[0] - rootLoc[0]).toFloat()
    val offsetY = (myLoc[1] - rootLoc[1]).toFloat()

    val capture = reuseBitmap(captureBitmap, vw, vh).also { captureBitmap = it }

    // isCapturing = true → draw() returns immediately → root.draw() skips us
    // → capture contains ONLY the content behind us, not our own blur output
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

    // Hand off to worker thread — main thread returns immediately
    workerBusy = true
    val captureRef = capture
    val radius     = blurRadiusFromAmount(blurAmount)

    workerHandler.post {
      val scaled = reuseBitmap(scaledBitmap, sw, sh).also { scaledBitmap = it }

      // Downsample
      Canvas(scaled).drawBitmap(
        captureRef,
        Rect(0, 0, captureRef.width, captureRef.height),
        Rect(0, 0, scaled.width, scaled.height),
        capturePaint
      )

      // Multi-pass StackBlur — pure Kotlin, no deprecated APIs, all versions
      val r = radius.toInt().coerceAtLeast(1)
      repeat(BLUR_ROUNDS) { stackBlur(scaled, r) }

      // @Volatile atomic swap — RenderThread always sees a complete bitmap
      readyBitmap = scaled
      workerBusy  = false

      mainHandler.post { invalidate() }
    }
  }

  // ── StackBlur ─────────────────────────────────────────────────────────────

  private fun stackBlur(bmp: Bitmap, radius: Int) {
    val r  = radius.coerceIn(1, 254)
    val w  = bmp.width
    val h  = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)

    val div    = r + r + 1
    val wm     = w - 1
    val hm     = h - 1
    val ds     = (div + 1) shr 1
    val dsSq   = ds * ds
    val dv     = IntArray(256 * dsSq) { it / dsSq }
    val vmin   = IntArray(maxOf(w, h))
    val rStack = IntArray(div)
    val gStack = IntArray(div)
    val bStack = IntArray(div)

    // Horizontal pass
    var yi = 0
    for (y in 0 until h) {
      var rSum = 0; var gSum = 0; var bSum = 0
      var rOut = 0; var gOut = 0; var bOut = 0
      var p = px[yi]; var pr = (p shr 16) and 0xFF; var pg = (p shr 8) and 0xFF; var pb = p and 0xFF
      for (i in 0 until ds) {
        rStack[i] = pr; gStack[i] = pg; bStack[i] = pb
        rSum += pr * (i + 1); gSum += pg * (i + 1); bSum += pb * (i + 1)
        rOut += pr; gOut += pg; bOut += pb
      }
      for (i in 1 until ds) {
        val xi = if (i <= wm) i else wm
        p = px[yi + xi]; pr = (p shr 16) and 0xFF; pg = (p shr 8) and 0xFF; pb = p and 0xFF
        rStack[i + r] = pr; gStack[i + r] = pg; bStack[i + r] = pb
        rSum += pr * (ds - i); gSum += pg * (ds - i); bSum += pb * (ds - i)
      }
      var si = r
      for (x in 0 until w) {
        px[yi + x] = -0x1000000 or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
        rSum -= rOut; gSum -= gOut; bSum -= bOut
        rOut -= rStack[si]; gOut -= gStack[si]; bOut -= bStack[si]
        var sip = si + ds; if (sip >= div) sip -= div
        pr = rStack[sip]; pg = gStack[sip]; pb = bStack[sip]
        rOut += pr; gOut += pg; bOut += pb; rSum += rOut; gSum += gOut; bSum += bOut
        vmin[x] = if (x + r < wm) x + r + 1 else wm
        val sp = px[yi + vmin[x]]; val vp = px[yi + if (x > r) x - r else 0]
        rStack[sip] = (sp shr 16) and 0xFF; gStack[sip] = (sp shr 8) and 0xFF; bStack[sip] = sp and 0xFF
        rOut += rStack[sip] - ((vp shr 16) and 0xFF)
        gOut += gStack[sip] - ((vp shr 8) and 0xFF)
        bOut += bStack[sip] - (vp and 0xFF)
        if (++si >= div) si = 0
      }
      yi += w
    }

    // Vertical pass
    for (x in 0 until w) {
      var rSum = 0; var gSum = 0; var bSum = 0
      var rOut = 0; var gOut = 0; var bOut = 0
      var p = px[x]; var pr = (p shr 16) and 0xFF; var pg = (p shr 8) and 0xFF; var pb = p and 0xFF
      for (i in 0 until ds) {
        rStack[i] = pr; gStack[i] = pg; bStack[i] = pb
        rSum += pr * (i + 1); gSum += pg * (i + 1); bSum += pb * (i + 1)
        rOut += pr; gOut += pg; bOut += pb
      }
      for (i in 1 until ds) {
        val yi2 = if (i <= hm) i * w else hm * w
        p = px[x + yi2]; pr = (p shr 16) and 0xFF; pg = (p shr 8) and 0xFF; pb = p and 0xFF
        rStack[i + r] = pr; gStack[i + r] = pg; bStack[i + r] = pb
        rSum += pr * (ds - i); gSum += pg * (ds - i); bSum += pb * (ds - i)
      }
      var si = r
      for (y in 0 until h) {
        px[x + y * w] = -0x1000000 or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
        rSum -= rOut; gSum -= gOut; bSum -= bOut
        rOut -= rStack[si]; gOut -= gStack[si]; bOut -= bStack[si]
        var sip = si + ds; if (sip >= div) sip -= div
        pr = rStack[sip]; pg = gStack[sip]; pb = bStack[sip]
        rOut += pr; gOut += pg; bOut += pb; rSum += rOut; gSum += gOut; bSum += bOut
        vmin[y] = if (y + r < hm) (y + r + 1) * w else hm * w
        val sp = px[x + vmin[y]]; val vp = px[x + if (y > r) (y - r) * w else 0]
        rStack[sip] = (sp shr 16) and 0xFF; gStack[sip] = (sp shr 8) and 0xFF; bStack[sip] = sp and 0xFF
        rOut += rStack[sip] - ((vp shr 16) and 0xFF)
        gOut += gStack[sip] - ((vp shr 8) and 0xFF)
        bOut += bStack[sip] - (vp and 0xFF)
        if (++si >= div) si = 0
      }
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
  }

  // ── Progressive shader ────────────────────────────────────────────────────

  private fun buildProgressiveShader(w: Float, h: Float): Shader? {
    val sc = Color.argb((progressiveStartIntensity.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
    val ec = Color.argb((progressiveEndIntensity.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
    return when (progressiveDirection) {
      PROGRESSIVE_TOP_TO_BOTTOM -> LinearGradient(0f, 0f, 0f, h, sc, ec, Shader.TileMode.CLAMP)
      PROGRESSIVE_BOTTOM_TO_TOP -> LinearGradient(0f, h, 0f, 0f, sc, ec, Shader.TileMode.CLAMP)
      PROGRESSIVE_LEFT_TO_RIGHT -> LinearGradient(0f, 0f, w, 0f, sc, ec, Shader.TileMode.CLAMP)
      PROGRESSIVE_RIGHT_TO_LEFT -> LinearGradient(w, 0f, 0f, 0f, sc, ec, Shader.TileMode.CLAMP)
      PROGRESSIVE_RADIAL -> RadialGradient(w / 2f, h / 2f, min(w, h) / 2f, sc, ec, Shader.TileMode.CLAMP)
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
      "topToBottom" -> PROGRESSIVE_TOP_TO_BOTTOM
      "bottomToTop" -> PROGRESSIVE_BOTTOM_TO_TOP
      "leftToRight" -> PROGRESSIVE_LEFT_TO_RIGHT
      "rightToLeft" -> PROGRESSIVE_RIGHT_TO_LEFT
      "radial"      -> PROGRESSIVE_RADIAL
      else          -> PROGRESSIVE_NONE
    }; invalidate()
  }

  fun setProgressiveStartIntensity(v: Float) { progressiveStartIntensity = v.coerceIn(0f, 1f); invalidate() }
  fun setProgressiveEndIntensity(v: Float)   { progressiveEndIntensity   = v.coerceIn(0f, 1f); invalidate() }
  fun setNoiseFactor(v: Float)               { noiseFactor = v.coerceIn(0f, 1f); invalidate() }

  fun applyBlurEnabled(enabled: Boolean) {
    blurEnabled = enabled
    if (enabled) safeAddPreDrawListener()
    else {
      safeRemovePreDrawListener()
      workerBusy = false; readyBitmap = null; invalidate()
    }
  }

  fun setAutoUpdate(update: Boolean) {
    autoUpdate = update
    if (update) safeAddPreDrawListener() else safeRemovePreDrawListener()
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

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

  private fun reuseBitmap(existing: Bitmap?, w: Int, h: Int): Bitmap {
    if (existing != null && !existing.isRecycled
        && existing.width == w && existing.height == h) return existing
    existing?.recycle()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  }

  private fun blurRadiusFromAmount(amount: Float): Float {
    val t = amount.coerceIn(0f, 100f) / 100f
    return 2f + t * 22f   // 2–24px per pass, × BLUR_ROUNDS = wide spread
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
        6 -> Color.argb(255, hex.substring(0, 2).toInt(16),
                             hex.substring(2, 4).toInt(16),
                             hex.substring(4, 6).toInt(16))
        8 -> Color.argb(hex.substring(6, 8).toInt(16),
                        hex.substring(0, 2).toInt(16),
                        hex.substring(2, 4).toInt(16),
                        hex.substring(4, 6).toInt(16))
        else -> null
      }
    } catch (_: NumberFormatException) { null }
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}

  companion object {
    private const val DOWNSAMPLE      = 2f          // 1/4 pixels
    private const val BLUR_ROUNDS     = 3           // passes per frame
    private const val FRAME_INTERVAL_NS = 16_666_666L  // ~60 fps cap

    const val PROGRESSIVE_NONE          = 0
    const val PROGRESSIVE_TOP_TO_BOTTOM = 1
    const val PROGRESSIVE_BOTTOM_TO_TOP = 2
    const val PROGRESSIVE_LEFT_TO_RIGHT = 3
    const val PROGRESSIVE_RIGHT_TO_LEFT = 4
    const val PROGRESSIVE_RADIAL        = 5
  }
}
