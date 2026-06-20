package com.blurvibe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * LegacyBlurController — Backdrop blur for Android API 21–30.
 *
 * Adapted from Dimezis BlurView PreDrawBlurController.
 *
 * KEY CHANGE: Uses BlurVibeCanvas (typed canvas) instead of isCapturing boolean.
 * BlurVibeView.draw() checks: if (canvas is BlurVibeCanvas) return
 * This eliminates the race condition where Reanimated-triggered draws
 * hit isCapturing=true and cause the view to disappear for a frame.
 *
 * Scale matrix: matches Dimezis setupInternalCanvasMatrix() with
 * scaleFactorH/W to correctly handle the downsampled bitmap size.
 *
 * Stride alignment: bitmap width rounded to 64 (Samsung requirement).
 */
internal class LegacyBlurController(
  private val view: BlurVibeView,
  private val rootView: ViewGroup
) {

  companion object {
    private const val SCALE_FACTOR   = 6f
    private const val ROUNDING_VALUE = 64
    private const val BLUR_ROUNDS    = 3
    private const val FRAME_INTERVAL_NS = 16_666_666L
  }

  // ── Bitmap pool ───────────────────────────────────────────────────────────

  private var internalBitmap: Bitmap? = null
  private var internalCanvas: BlurVibeCanvas? = null
  @Volatile private var readyBitmap: Bitmap? = null

  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val overlayPaint = Paint()

  // ── Worker thread ─────────────────────────────────────────────────────────

  private val workerThread  = HandlerThread("BlurVibeWorkerLegacy-${hashCode()}")
    .also { it.start() }
  private val workerHandler = Handler(workerThread.looper)
  private val mainHandler   = Handler(Looper.getMainLooper())

  // ── State ─────────────────────────────────────────────────────────────────

  var overlayColor: Int  = Color.TRANSPARENT
  var blurRadius:   Float = 8f
  var enabled:      Boolean = true
  var autoUpdate:   Boolean = true

  private var workerBusy   = false
  private var initialized  = false
  private var lastCaptureNs = 0L

  private val rootLocation    = IntArray(2)
  private val viewLocation    = IntArray(2)

  // ── PreDraw listener ──────────────────────────────────────────────────────

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (enabled && autoUpdate && initialized && !workerBusy) {
      val now = System.nanoTime()
      if (now - lastCaptureNs >= FRAME_INTERVAL_NS) {
        lastCaptureNs = now
        captureAndBlur()
      }
    }
    true
  }

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    val w = view.measuredWidth
    val h = view.measuredHeight
    if (w > 0 && h > 0) initBitmaps(w, h)
  }

  private fun initBitmaps(w: Int, h: Int) {
    val scaledW = roundToStride((w / SCALE_FACTOR).toInt().coerceAtLeast(1))
    val roundingScale = w.toFloat() / scaledW
    val scaledH = (h / roundingScale).toInt().coerceAtLeast(1)

    internalBitmap?.recycle()
    internalBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
    internalCanvas = BlurVibeCanvas(internalBitmap!!)
    initialized = true
    safeAddPreDrawListener()
    captureAndBlur()
  }

  private fun roundToStride(value: Int): Int {
    if (value % ROUNDING_VALUE == 0) return value
    return value - (value % ROUNDING_VALUE) + ROUNDING_VALUE
  }

  // ── Capture ────────────────────────────────────────────────────────────────

  private fun captureAndBlur() {
    if (workerBusy || !initialized) return
    val bitmap = internalBitmap ?: return
    val canvas = internalCanvas ?: return
    if (bitmap.isRecycled) return

    // Dimezis setupInternalCanvasMatrix() approach
    rootView.getLocationOnScreen(rootLocation)
    view.getLocationOnScreen(viewLocation)

    val left = viewLocation[0] - rootLocation[0]
    val top  = viewLocation[1] - rootLocation[1]

    val scaleW = view.width.toFloat()  / bitmap.width.toFloat()
    val scaleH = view.height.toFloat() / bitmap.height.toFloat()

    bitmap.eraseColor(Color.TRANSPARENT)
    canvas.save()
    canvas.translate(-left / scaleW, -top / scaleH)
    canvas.scale(1f / scaleW, 1f / scaleH)

    // BlurVibeCanvas: view.draw() sees BlurVibeCanvas → skips itself
    // All other views draw normally → captures content behind our view
    try {
      rootView.draw(canvas)
    } catch (e: Exception) {
      Log.e("LegacyBlurController", "Snapshot failed, skipping frame", e)
      canvas.restore()
      return
    }
    canvas.restore()

    val bitmapRef = bitmap
    val radius    = blurRadius.coerceIn(1f, 25f)
    workerBusy    = true

    workerHandler.post {
      val blurred = Bitmap.createBitmap(
        bitmapRef.width, bitmapRef.height, Bitmap.Config.ARGB_8888
      )
      Canvas(blurred).drawBitmap(bitmapRef, 0f, 0f, null)
      repeat(BLUR_ROUNDS) { stackBlur(blurred, radius.toInt().coerceAtLeast(1)) }

      readyBitmap = blurred
      workerBusy  = false
      mainHandler.post { view.invalidate() }
    }
  }

  // ── Draw — called from BlurVibeView.onDraw() ──────────────────────────────

  fun draw(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
    val bmp = readyBitmap?.takeIf { !it.isRecycled } ?: return
    // Scale blurred bitmap (at 1/SCALE_FACTOR resolution) to view size
    canvas.drawBitmap(bmp, null, RectF(0f, 0f, viewWidth, viewHeight), bitmapPaint)
    if (Color.alpha(overlayColor) > 0) {
      overlayPaint.color = overlayColor
      canvas.drawRect(0f, 0f, viewWidth, viewHeight, overlayPaint)
    }
  }

  // ── StackBlur ─────────────────────────────────────────────────────────────

  private fun stackBlur(bmp: Bitmap, radius: Int) {
    val r = radius.coerceIn(1, 254)
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val div = r + r + 1; val wm = w - 1; val hm = h - 1
    val ds = (div + 1) shr 1; val dsSq = ds * ds
    val dv = IntArray(256 * dsSq) { it / dsSq }
    val vmin = IntArray(maxOf(w, h))
    val rSt = IntArray(div); val gSt = IntArray(div); val bSt = IntArray(div)
    var yi = 0
    for (y in 0 until h) {
      var rS=0; var gS=0; var bS=0; var rO=0; var gO=0; var bO=0
      var p=px[yi]; var pr=(p shr 16) and 0xFF; var pg=(p shr 8) and 0xFF; var pb=p and 0xFF
      for (i in 0 until ds) { rSt[i]=pr; gSt[i]=pg; bSt[i]=pb; rS+=pr*(i+1); gS+=pg*(i+1); bS+=pb*(i+1); rO+=pr; gO+=pg; bO+=pb }
      for (i in 1 until ds) { val xi=if(i<=wm) i else wm; p=px[yi+xi]; pr=(p shr 16) and 0xFF; pg=(p shr 8) and 0xFF; pb=p and 0xFF; rSt[i+r]=pr; gSt[i+r]=pg; bSt[i+r]=pb; rS+=pr*(ds-i); gS+=pg*(ds-i); bS+=pb*(ds-i) }
      var si=r
      for (x in 0 until w) {
        px[yi+x]=-0x1000000 or (dv[rS] shl 16) or (dv[gS] shl 8) or dv[bS]
        rS-=rO; gS-=gO; bS-=bO; rO-=rSt[si]; gO-=gSt[si]; bO-=bSt[si]
        var sip=si+ds; if(sip>=div) sip-=div
        pr=rSt[sip]; pg=gSt[sip]; pb=bSt[sip]; rO+=pr; gO+=pg; bO+=pb; rS+=rO; gS+=gO; bS+=bO
        vmin[x]=if(x+r<wm) x+r+1 else wm
        val sp=px[yi+vmin[x]]; val vp=px[yi+if(x>r) x-r else 0]
        rSt[sip]=(sp shr 16) and 0xFF; gSt[sip]=(sp shr 8) and 0xFF; bSt[sip]=sp and 0xFF
        rO+=rSt[sip]-((vp shr 16) and 0xFF); gO+=gSt[sip]-((vp shr 8) and 0xFF); bO+=bSt[sip]-(vp and 0xFF)
        if(++si>=div) si=0
      }; yi+=w
    }
    for (x in 0 until w) {
      var rS=0; var gS=0; var bS=0; var rO=0; var gO=0; var bO=0
      var p=px[x]; var pr=(p shr 16) and 0xFF; var pg=(p shr 8) and 0xFF; var pb=p and 0xFF
      for (i in 0 until ds) { rSt[i]=pr; gSt[i]=pg; bSt[i]=pb; rS+=pr*(i+1); gS+=pg*(i+1); bS+=pb*(i+1); rO+=pr; gO+=pg; bO+=pb }
      for (i in 1 until ds) { val yi2=if(i<=hm) i*w else hm*w; p=px[x+yi2]; pr=(p shr 16) and 0xFF; pg=(p shr 8) and 0xFF; pb=p and 0xFF; rSt[i+r]=pr; gSt[i+r]=pg; bSt[i+r]=pb; rS+=pr*(ds-i); gS+=pg*(ds-i); bS+=pb*(ds-i) }
      var si=r
      for (y in 0 until h) {
        px[x+y*w]=-0x1000000 or (dv[rS] shl 16) or (dv[gS] shl 8) or dv[bS]
        rS-=rO; gS-=gO; bS-=bO; rO-=rSt[si]; gO-=gSt[si]; bO-=bSt[si]
        var sip=si+ds; if(sip>=div) sip-=div
        pr=rSt[sip]; pg=gSt[sip]; pb=bSt[sip]; rO+=pr; gO+=pg; bO+=pb; rS+=rO; gS+=gO; bS+=bO
        vmin[y]=if(y+r<hm) (y+r+1)*w else hm*w
        val sp=px[x+vmin[y]]; val vp=px[x+if(y>r) (y-r)*w else 0]
        rSt[sip]=(sp shr 16) and 0xFF; gSt[sip]=(sp shr 8) and 0xFF; bSt[sip]=sp and 0xFF
        rO+=rSt[sip]-((vp shr 16) and 0xFF); gO+=gSt[sip]-((vp shr 8) and 0xFF); bO+=bSt[sip]-(vp and 0xFF)
        if(++si>=div) si=0
      }
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  fun onSizeChanged(w: Int, h: Int) {
    if (w > 0 && h > 0) {
      safeRemovePreDrawListener()
      workerHandler.post {
        internalBitmap?.recycle(); internalBitmap = null
        internalCanvas = null; readyBitmap = null
        mainHandler.post { initBitmaps(w, h) }
      }
    }
  }

  fun reAttach() {
    if (enabled && autoUpdate) safeAddPreDrawListener()
  }

  fun destroy() {
    safeRemovePreDrawListener()
    workerHandler.post {
      internalBitmap?.recycle(); internalBitmap = null
      internalCanvas = null
      workerThread.quitSafely()
    }
  }

  private fun safeAddPreDrawListener() {
    val vto = rootView.viewTreeObserver
    vto.removeOnPreDrawListener(preDrawListener)
    if (vto.isAlive) vto.addOnPreDrawListener(preDrawListener)
    // Also listen on our own window if different (Dialog support)
    if (rootView.windowId != view.windowId) {
      val myVto = view.viewTreeObserver
      myVto.removeOnPreDrawListener(preDrawListener)
      if (myVto.isAlive) myVto.addOnPreDrawListener(preDrawListener)
    }
  }

  private fun safeRemovePreDrawListener() {
    rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    view.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
  }
}