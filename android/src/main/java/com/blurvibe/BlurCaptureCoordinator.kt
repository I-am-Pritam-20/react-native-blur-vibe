package com.blurvibe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.Choreographer
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.util.concurrent.CopyOnWriteArraySet

/**
 * BlurCaptureCoordinator
 *
 * Singleton per root-view. Owns ONE preDrawListener, ONE bitmap capture, ONE blur pass
 * per vsync — shared across ALL BlurVibeViews that point at the same root.
 *
 * Cost: O(1) per frame regardless of how many BlurVibeViews are mounted.
 * Compare to naive per-view: O(N) per frame.
 *
 * Thread model:
 *   rootView.draw()   → main thread (Android requires this)
 *   RenderScript blur → workerThread (non-blocking)
 *   onBlurReady()     → main thread via mainHandler.post()
 */
internal class BlurCaptureCoordinator private constructor(
  private val rootView: ViewGroup
) {

  // registered BlurVibeViews — thread-safe, iterated on main thread
  private val clients = CopyOnWriteArraySet<BlurVibeView>()

  // bitmap pool — allocated once, reused every frame (zero GC)
  private var captureBitmap: Bitmap? = null
  private var scaledBitmap:  Bitmap? = null

  private val capturePaint = Paint(Paint.FILTER_BITMAP_FLAG)

  // worker thread for blur (keeps main thread free)
  private val workerThread = HandlerThread("BlurVibeWorker-${System.identityHashCode(rootView)}")
    .also { it.start() }
  private val workerHandler  = Handler(workerThread.looper)
  private val mainHandler    = Handler(Looper.getMainLooper())

  // RenderScript state (API < 31 only, created lazily on workerThread)
  private var rs:          RenderScript?        = null
  private var blurScript:  ScriptIntrinsicBlur? = null
  private var inputAlloc:  Allocation?          = null
  private var outputAlloc: Allocation?          = null

  // blur params
  var blurRadius: Float = 8f
    set(value) { field = value.coerceIn(1f, 25f) }
  var downsampleFactor: Float = DOWNSAMPLE_FACTOR

  // frame gate — at most one capture queued at a time
  private var frameScheduled = false
  private val frameCallback = Choreographer.FrameCallback {
    frameScheduled = false
    captureAndBlur()
  }

  // ONE preDrawListener for the entire coordinator
  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (!frameScheduled) {
      frameScheduled = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
    true  // never block the draw pass
  }

  // ── Init / destroy ────────────────────────────────────────────────────────

  init {
    rootView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      workerHandler.post { initRenderScript() }
    }
  }

  private fun destroy() {
    rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    Choreographer.getInstance().removeFrameCallback(frameCallback)
    cache.remove(rootView)
    workerHandler.post {
      inputAlloc?.destroy();  inputAlloc  = null
      outputAlloc?.destroy(); outputAlloc = null
      blurScript?.destroy();  blurScript  = null
      rs?.destroy();          rs          = null
      captureBitmap?.recycle(); captureBitmap = null
      scaledBitmap?.recycle();  scaledBitmap  = null
      workerThread.quitSafely()
    }
  }

  // ── Registration ──────────────────────────────────────────────────────────

  fun register(view: BlurVibeView) {
    clients.add(view)
    // deliver cached result immediately so view doesn't flash blank
    scaledBitmap?.takeIf { !it.isRecycled }?.let { view.onBlurReady(it) }
  }

  fun unregister(view: BlurVibeView) {
    clients.remove(view)
    if (clients.isEmpty()) destroy()
  }

  // ── Capture + blur pipeline ───────────────────────────────────────────────

  private fun captureAndBlur() {
    if (clients.isEmpty()) return
    val w = rootView.width;  if (w <= 0) return
    val h = rootView.height; if (h <= 0) return

    val factor  = downsampleFactor
    val scaledW = (w / factor).toInt().coerceAtLeast(1)
    val scaledH = (h / factor).toInt().coerceAtLeast(1)

    // allocate / reuse bitmaps
    val capture = reuseBitmap(captureBitmap, w, h).also      { captureBitmap = it }
    val scaled  = reuseBitmap(scaledBitmap, scaledW, scaledH).also { scaledBitmap  = it }

    // ① capture on main thread (required by Android)
    try {
      val c = Canvas(capture)
      c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
      rootView.draw(c)
    } catch (_: Exception) { return }

    val radius        = blurRadius
    val captureRef    = capture
    val scaledRef     = scaled

    // ② blur on worker thread
    workerHandler.post {
      // downsample
      Canvas(scaledRef).drawBitmap(
        captureRef,
        android.graphics.Rect(0, 0, captureRef.width, captureRef.height),
        android.graphics.Rect(0, 0, scaledRef.width,  scaledRef.height),
        capturePaint
      )

      // blur
      val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        blurSoftware(scaledRef, radius)   // BlurMaskFilter — fast enough at small size
      } else {
        blurRenderScript(scaledRef, radius) ?: blurSoftware(scaledRef, radius)
      }

      // ③ deliver to all clients on main thread
      mainHandler.post {
        clients.forEach { it.onBlurReady(blurred) }
      }
    }
  }

  // ── Blur implementations ──────────────────────────────────────────────────

  private fun initRenderScript() {
    try {
      rs         = RenderScript.create(rootView.context)
      blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    } catch (_: Exception) {}
  }

  private fun blurRenderScript(src: Bitmap, radius: Float): Bitmap? {
    val r  = this.rs          ?: return null
    val sc = this.blurScript  ?: return null
    return try {
      val inA  = reuseAlloc(inputAlloc,  src, r).also { inputAlloc  = it }
      val outA = reuseAlloc(outputAlloc, src, r).also { outputAlloc = it }
      inA.copyFrom(src)
      sc.setRadius(radius)
      sc.setInput(inA)
      sc.forEach(outA)
      val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
      outA.copyTo(out)
      out
    } catch (_: Exception) { null }
  }

  private fun blurSoftware(src: Bitmap, radius: Float): Bitmap {
    val out   = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    Canvas(out).drawBitmap(src, 0f, 0f, paint)
    return out
  }

  // ── Bitmap / Allocation helpers ───────────────────────────────────────────

  private fun reuseBitmap(existing: Bitmap?, w: Int, h: Int): Bitmap {
    if (existing != null && !existing.isRecycled
        && existing.width == w && existing.height == h) return existing
    existing?.recycle()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  }

  private fun reuseAlloc(existing: Allocation?, src: Bitmap, rs: RenderScript): Allocation {
    if (existing != null
        && existing.type.x == src.width
        && existing.type.y == src.height) return existing
    existing?.destroy()
    return Allocation.createFromBitmap(rs, src,
      Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
  }

  // ── Singleton cache ───────────────────────────────────────────────────────

  companion object {
    /** Global downsample factor. Higher = faster + softer. Range 2–16. */
    var DOWNSAMPLE_FACTOR: Float = 8f

    private val cache = HashMap<ViewGroup, BlurCaptureCoordinator>()

    fun forRoot(rootView: ViewGroup): BlurCaptureCoordinator =
      cache.getOrPut(rootView) { BlurCaptureCoordinator(rootView) }
  }
}