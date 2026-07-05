package com.blurvibe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/*
 * BlurCaptureCoordinator
 */
internal class BlurCaptureCoordinator private constructor(
  private val rootView: ViewGroup
) {

  // ── Registered views ─────

  private val clients = java.util.concurrent.CopyOnWriteArraySet<View>()

  // ── Shared raw capture ────

  private var sharedBitmap: Bitmap? = null
  private var sharedCanvas: BlurVibeCanvas? = null

  /** Raw (unblurred) shared capture, downsampled by [DOWNSAMPLE_FACTOR]. Null until the first frame. */
  val currentBitmap: Bitmap? get() = sharedBitmap?.takeIf { !it.isRecycled }

  // ── PreDraw listener — ONE per root, fires before RenderThread ────────────

  private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    captureShared()
    if (clients.isNotEmpty()) {
      clients.forEach { it.invalidate() }
    }
    true
  }

  // ── Capture ────

  private fun captureShared() {
    if (clients.isEmpty()) return
    val rw = rootView.width;  if (rw <= 0) return
    val rh = rootView.height; if (rh <= 0) return

    val scaledW = roundToStride((rw / DOWNSAMPLE_FACTOR).toInt().coerceAtLeast(1))
    val roundingScale = rw.toFloat() / scaledW
    val scaledH = (rh / roundingScale).toInt().coerceAtLeast(1)

    var bitmap = sharedBitmap
    if (bitmap == null || bitmap.isRecycled || bitmap.width != scaledW || bitmap.height != scaledH) {
      bitmap?.recycle()
      bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
      sharedBitmap = bitmap
      sharedCanvas = BlurVibeCanvas(bitmap)
    }
    val canvas = sharedCanvas ?: return

    bitmap.eraseColor(Color.TRANSPARENT)
    canvas.save()
    canvas.scale(1f / roundingScale, 1f / roundingScale)
    try {
      rootView.draw(canvas)
    } catch (_: Exception) {
    }
    canvas.restore()
  }

  private fun roundToStride(v: Int): Int {
    if (v % ROUNDING_VALUE == 0) return v
    return v - (v % ROUNDING_VALUE) + ROUNDING_VALUE
  }

  // ── Registration ───

  fun register(view: View) {
    val wasEmpty = clients.isEmpty()
    clients.add(view)
    if (wasEmpty) safeAddPreDrawListener()
  }

  fun unregister(view: View) {
    clients.remove(view)
    if (clients.isEmpty()) destroy()
  }

  fun reAttachIfNeeded() {
    if (clients.isNotEmpty()) safeAddPreDrawListener()
  }

  private fun safeAddPreDrawListener() {
    val vto = rootView.viewTreeObserver
    vto.removeOnPreDrawListener(preDrawListener)
    if (vto.isAlive) vto.addOnPreDrawListener(preDrawListener)
  }

  private fun destroy() {
    rootView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
    sharedBitmap?.recycle()
    sharedBitmap = null
    sharedCanvas = null
    registry.remove(rootView)
  }

  companion object {
    const val DOWNSAMPLE_FACTOR = 6f
    private const val ROUNDING_VALUE = 64  // stride alignment (Samsung OEM requirement)

    private val registry = HashMap<ViewGroup, BlurCaptureCoordinator>()

    /** Main-thread only — see class doc. */
    fun forRoot(root: ViewGroup): BlurCaptureCoordinator =
      registry.getOrPut(root) { BlurCaptureCoordinator(root) }
  }
}