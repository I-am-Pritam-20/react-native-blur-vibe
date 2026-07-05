package com.blurvibe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.graphics.toColorInt
import com.facebook.react.views.view.ReactViewGroup

/**
 * BlurVibeView — Android API 21–30 backdrop blur.
 */
class BlurVibeView(context: Context) : ReactViewGroup(context) {

  private var blurController: LegacyBlurController? = null
  private var pendingBlurAmount = 10f
  private var pendingOverlay    = Color.TRANSPARENT
  private var cornerRadiusPx    = 0f

  init {
    setWillNotDraw(false)
    outlineProvider = ViewOutlineProvider.BACKGROUND
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    val root = findBlurRoot() ?: return
    blurController = LegacyBlurController(this, root).also {
      it.blurRadius   = mapBlurAmount(pendingBlurAmount)
      it.overlayColor = pendingOverlay
    }
  }

  override fun onDetachedFromWindow() {
    blurController?.destroy()
    blurController = null
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    blurController?.onSizeChanged()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus) blurController?.reAttach()
  }

  // ── KEY: skip self when being captured (BlurVibeCanvas marker) ────────────

  override fun draw(canvas: Canvas) {
    if (canvas is BlurVibeCanvas) return
    super.draw(canvas)
  }

  // ── onDraw ────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    blurController?.draw(canvas, width.toFloat(), height.toFloat())
    // Redraw ReactViewBackgroundDrawable on top so borders/radius show above blur
    background?.draw(canvas)
    super.onDraw(canvas)
  }

  // ── Public setters ─────────────────────────────────────────────────────────

  fun setBlurAmount(amount: Float) {
    pendingBlurAmount = amount
    blurController?.blurRadius = mapBlurAmount(amount)
  }

  fun setOverlayColor(colorString: String?) {
    pendingOverlay = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    blurController?.overlayColor = pendingOverlay
    invalidate()
  }

  fun setBlurRadius(@Suppress("UNUSED_PARAMETER") factor: Int) {}

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
    )
    clipToOutline = cornerRadiusPx > 0f
    invalidate()
  }

  fun setReducedTransparencyFallbackColor(@Suppress("UNUSED_PARAMETER") color: String?) {}

  fun applyBlurEnabled(enabled: Boolean) {
    blurController?.enabled = enabled
    if (!enabled) invalidate()
  }

  fun setAutoUpdate(autoUpdate: Boolean) {
    blurController?.autoUpdate = autoUpdate
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {}

  // ── Helpers ────────────────────────────────────────────────────────────────

  private fun mapBlurAmount(amount: Float): Float {
    // "Felt" radius — desired blur strength in full-resolution-equivalent
    // pixels, range 1–100. MUST stay numerically identical to
    // BlurVibeViewApi31.localBlurRadius()'s felt curve, and both divide by
    // BlurCaptureCoordinator.DOWNSAMPLE_FACTOR (referenced directly, not
    // duplicated as a literal) so blurAmount produces matching visual
    // density on both API < 31 and API 31+, and — now that both view
    // classes crop from the SAME shared bitmap — so a mismatch here can
    // never silently corrupt crop math either.
    //
    // blurAmount=10  → felt≈10.9 → local≈1.8   (backdrop-blur-sm)
    // blurAmount=50  → felt≈50.5 → local≈8.4   (backdrop-blur-xl)
    // blurAmount=100 → felt=100  → local≈16.7  (maximum, within RenderScript's 25 cap)
    val felt = 1f + (amount.coerceIn(0f, 100f) / 100f) * 99f
    return (felt / BlurCaptureCoordinator.DOWNSAMPLE_FACTOR).coerceIn(1f, 25f)
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
}