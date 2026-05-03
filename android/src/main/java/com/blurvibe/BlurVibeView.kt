package com.blurvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.graphics.toColorInt
import com.facebook.react.views.view.ReactViewGroup

/**
 * BlurVibeView — CSS backdrop-filter: blur() for React Native / Android
 *
 * Extends ReactViewGroup so it can host React Native children correctly
 * (Yoga layout, touch events, z-ordering all work out of the box).
 *
 * Blur is produced by BlurCaptureCoordinator — a singleton per root view that
 * captures + blurs the root ONCE per vsync and shares the result to every
 * registered BlurVibeView. N blur views on screen = same cost as 1.
 *
 * Each view clips the shared blurred bitmap to its own screen-space rect in
 * onDraw(), then draws the overlay color on top.
 */
class BlurVibeView(context: Context) : ReactViewGroup(context) {

  // ── State ──────────────────────────────────────────────────────────────────

  private var blurRadius     = DEFAULT_BLUR_RADIUS
  private var overlayColor   = Color.TRANSPARENT
  private var cornerRadiusPx = 0f

  // ── Coordinator ───────────────────────────────────────────────────────────

  private var coordinator: BlurCaptureCoordinator? = null

  // ── Draw state (main thread only) ─────────────────────────────────────────

  @Volatile private var latestBitmap: Bitmap? = null
  private val bitmapPaint  = Paint(Paint.FILTER_BITMAP_FLAG)
  private val overlayPaint = Paint()
  private val srcRect      = Rect()
  private val dstRect      = RectF()

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    super.setBackgroundColor(Color.TRANSPARENT)
    clipToOutline = true
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    attachToCoordinator()
  }

  override fun onDetachedFromWindow() {
    coordinator?.unregister(this)
    coordinator = null
    super.onDetachedFromWindow()
  }

  // ── Coordinator attachment ─────────────────────────────────────────────────

  private fun attachToCoordinator() {
    coordinator?.unregister(this)
    val root = findBlurRoot() ?: return
    val coord = BlurCaptureCoordinator.forRoot(root).also {
      it.blurRadius = blurRadius
      coordinator   = it
    }
    coord.register(this)
  }

  /** Called by coordinator on main thread when a new blurred bitmap is ready. */
  fun onBlurReady(bitmap: Bitmap) {
    latestBitmap = bitmap
    invalidate()
  }

  // ── Drawing ────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    val bitmap = latestBitmap?.takeIf { !it.isRecycled } ?: return
    val root   = findBlurRoot() ?: return

    // compute this view's offset within the blur root
    val myLoc   = IntArray(2);   getLocationInWindow(myLoc)
    val rootLoc = IntArray(2);   root.getLocationInWindow(rootLoc)

    val l = myLoc[0] - rootLoc[0]
    val t = myLoc[1] - rootLoc[1]

    // the blurred bitmap is at 1/DOWNSAMPLE_FACTOR resolution — scale coords
    val f = BlurCaptureCoordinator.DOWNSAMPLE_FACTOR
    srcRect.set(
      (l / f).toInt().coerceAtLeast(0),
      (t / f).toInt().coerceAtLeast(0),
      ((l + width)  / f).toInt().coerceAtMost(bitmap.width),
      ((t + height) / f).toInt().coerceAtMost(bitmap.height)
    )
    dstRect.set(0f, 0f, width.toFloat(), height.toFloat())

    if (!srcRect.isEmpty) canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)

    if (Color.alpha(overlayColor) > 0) {
      overlayPaint.color = overlayColor
      canvas.drawRect(dstRect, overlayPaint)
    }
  }

  // ── Public setters (ViewManager → UI thread) ──────────────────────────────

  fun setBlurAmount(amount: Float) {
    blurRadius = (amount.coerceIn(0f, 100f) / 100f) * 25f
    coordinator?.blurRadius = blurRadius
  }

  fun applyOverlayColor(colorString: String?) {
    overlayColor = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    invalidate()
  }

  /**
   * blurRadius prop: Android downsample factor (1–8).
   * Higher = faster + softer. Sets the global factor on the coordinator.
   */
  fun applyBlurRadius(factor: Int) {
    BlurCaptureCoordinator.DOWNSAMPLE_FACTOR = factor.coerceIn(2, 16).toFloat()
    // re-attach so coordinator picks up new factor
    attachToCoordinator()
  }

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
    )
    updateOutline()
  }

  fun setReducedTransparencyFallbackColor(@Suppress("UNUSED_PARAMETER") color: String?) {
    // iOS-only concept — no-op on Android
  }

  // ── Corner radius / outline ────────────────────────────────────────────────

  private fun updateOutline() {
    if (cornerRadiusPx > 0f) {
      outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
          outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
        }
      }
      clipToOutline = true
    } else {
      outlineProvider = ViewOutlineProvider.BACKGROUND
      clipToOutline   = false
    }
    invalidate()
  }

  // ── React Native layout passthrough ───────────────────────────────────────

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    // Yoga owns layout — calling super would run ReactViewGroup's layout which is correct,
    // but we must NOT call FrameLayout super here (ReactViewGroup handles it internally).
  }

  // ── Blur root finder ──────────────────────────────────────────────────────
  //
  // Priority: react-native-screens Screen → ReactRootView → window root
  // The root is what gets captured — use the narrowest stable ancestor.

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

  // ── Color parser ──────────────────────────────────────────────────────────
  // Handles: "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA", named colors

  private fun parseHexColor(s: String): Int? {
    val t = s.trim()
    if (t.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
    if (!t.startsWith("#")) return try { t.toColorInt() } catch (_: Exception) { null }
    val hex = t.removePrefix("#")
    return try {
      when (hex.length) {
        3    -> Color.argb(255,
                  hex[0].toString().repeat(2).toInt(16),
                  hex[1].toString().repeat(2).toInt(16),
                  hex[2].toString().repeat(2).toInt(16))
        6    -> Color.argb(255,
                  hex.substring(0, 2).toInt(16),
                  hex.substring(2, 4).toInt(16),
                  hex.substring(4, 6).toInt(16))
        8    -> Color.argb(
                  hex.substring(6, 8).toInt(16),   // alpha LAST in #RRGGBBAA
                  hex.substring(0, 2).toInt(16),
                  hex.substring(2, 4).toInt(16),
                  hex.substring(4, 6).toInt(16))
        else -> null
      }
    } catch (_: NumberFormatException) { null }
  }

  companion object {
    private const val DEFAULT_BLUR_RADIUS = 2.5f   // blurAmount=10 → 2.5
  }
}