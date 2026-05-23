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
 * BlurVibeView — Android API 21–30 backdrop blur (zero external dependencies)
 *
 * Replaces QmBlurView with LegacyBlurController — a direct RenderScript
 * implementation using only the Android SDK. No third-party library needed.
 *
 * Extends ReactViewGroup so it hosts RN children correctly (Yoga layout,
 * touch events, z-ordering all work natively).
 *
 * For API 31+, BlurVibeViewApi31 is used instead (RenderEffect GPU path).
 */
class BlurVibeView(context: Context) : ReactViewGroup(context) {

  // ── State ──────────────────────────────────────────────────────────────────

  private var blurController: LegacyBlurController? = null
  private var pendingBlurAmount  = 10f
  private var pendingOverlay     = Color.TRANSPARENT
  private var cornerRadiusPx     = 0f

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    super.setBackgroundColor(Color.TRANSPARENT)
    clipToOutline = true
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

  // ── Draw ───────────────────────────────────────────────────────────────────

  override fun onDraw(canvas: Canvas) {
    blurController?.draw(canvas, width.toFloat(), height.toFloat())
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

  fun setBlurRadius(factor: Int) {
    // No-op for now — LegacyBlurController uses fixed DOWNSAMPLE_FACTOR = 4
    // Could expose downsampleFactor setter on controller if needed
  }

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
    )
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
      }
    }
    clipToOutline = cornerRadiusPx > 0f
    invalidate()
  }

  fun setReducedTransparencyFallbackColor(@Suppress("UNUSED_PARAMETER") color: String?) { }

  fun applyBlurEnabled(enabled: Boolean) {
    blurController?.enabled = enabled
    if (!enabled) invalidate()
  }

  fun setAutoUpdate(autoUpdate: Boolean) {
    blurController?.autoUpdate = autoUpdate
  }

  // ── Layout passthrough ─────────────────────────────────────────────────────

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    // Yoga handles all layout
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private fun mapBlurAmount(amount: Float): Float {
    val t = amount.coerceIn(0f, 100f) / 100f
    return t * t * 25f  // quadratic curve, max 25 (RenderScript kernel limit)
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