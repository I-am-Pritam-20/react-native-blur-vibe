package com.blurvibe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * BlurVibeView
 *
 * Extends FrameLayout — required because:
 *   1. We host children (overlay view + React children)
 *   2. ViewGroupManager (used in manager) requires a ViewGroup subclass
 *   3. SimpleViewManager cast to IViewGroupManager would crash
 *
 * Blur strategy:
 *   API 31+  → RenderEffect (hardware accelerated, no bitmap)
 *   API 24-30 → RenderScript (bitmap-based, built into Android SDK)
 *
 * Color props received as hex strings from JS, parsed manually.
 * Supports: "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA"
 */
@SuppressLint("NewApi")
class BlurVibeView(context: Context) : FrameLayout(context) {

  private val overlayView = View(context)
  private var blurAmountValue: Float = 10f
  private var overlayColorValue: Int = Color.TRANSPARENT
  private var fallbackColorValue: Int = Color.parseColor("#F2F2F2")
  private var blurRadiusDownscale: Int = 4

  init {
    // overlayView fills entire frame, sits above blur, below React children
    overlayView.layoutParams = LayoutParams(
      LayoutParams.MATCH_PARENT,
      LayoutParams.MATCH_PARENT
    )
    overlayView.isClickable = false
    overlayView.isFocusable = false
    // Add overlay as first child — React children added later will be on top
    super.addView(overlayView, 0)
  }

  // MARK: - React child management
  // Must override to ensure React children go ABOVE our overlay view

  override fun addView(child: View, index: Int) {
    if (child === overlayView) {
      super.addView(child, index)
      return
    }
    // React children always go on top of overlay
    super.addView(child, childCount)
  }

  override fun addView(child: View) {
    if (child === overlayView) {
      super.addView(child)
      return
    }
    super.addView(child, childCount)
  }

  // MARK: - Setters (called by BlurVibeViewManager)

  fun setBlurAmount(amount: Float) {
    blurAmountValue = amount.coerceIn(0f, 100f)
    applyBlur()
  }

  fun setOverlayColor(colorString: String?) {
    overlayColorValue = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    overlayView.setBackgroundColor(overlayColorValue)
  }

  fun setReducedTransparencyFallbackColor(colorString: String?) {
    fallbackColorValue = parseHexColor(colorString ?: "#F2F2F2") ?: Color.parseColor("#F2F2F2")
  }

  fun setBlurRadius(radius: Int) {
    blurRadiusDownscale = radius.coerceIn(1, 8)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      post { renderScriptBlur() }
    }
  }

  // MARK: - Blur

  private fun applyBlur() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      applyRenderEffect()
    } else {
      post { renderScriptBlur() }
    }
  }

  private fun applyRenderEffect() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val sigma = (blurAmountValue * 0.5f).coerceAtLeast(0.01f)
      setRenderEffect(
        RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.MIRROR)
      )
    }
  }

  @Suppress("DEPRECATION")
  private fun renderScriptBlur() {
    val parentView = parent as? ViewGroup ?: return
    if (width <= 0 || height <= 0) return
    try {
      val scaledW = (width / blurRadiusDownscale).coerceAtLeast(1)
      val scaledH = (height / blurRadiusDownscale).coerceAtLeast(1)
      val bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.scale(1f / blurRadiusDownscale, 1f / blurRadiusDownscale)
      canvas.translate(-left.toFloat(), -top.toFloat())
      parentView.draw(canvas)

      val rs = RenderScript.create(context)
      val input = Allocation.createFromBitmap(rs, bitmap)
      val output = Allocation.createTyped(rs, input.type)
      val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
      val sigma = (blurAmountValue / 100f * 25f).coerceIn(1f, 25f)
      script.setRadius(sigma)
      script.setInput(input)
      script.forEach(output)
      output.copyTo(bitmap)
      rs.destroy()

      background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
    } catch (e: Exception) {
      setBackgroundColor(fallbackColorValue)
    }
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.onLayout(changed, l, t, r, b)
    if (changed && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      post { renderScriptBlur() }
    }
  }

  // MARK: - Hex color parser
  // Supports: "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA"
  private fun parseHexColor(colorString: String): Int? {
    val s = colorString.trim()
    if (s.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
    if (!s.startsWith("#")) return null
    val hex = s.removePrefix("#")
    return try {
      when (hex.length) {
        3 -> Color.argb(
          255,
          hex[0].toString().repeat(2).toInt(16),
          hex[1].toString().repeat(2).toInt(16),
          hex[2].toString().repeat(2).toInt(16)
        )
        6 -> Color.argb(
          255,
          hex.substring(0, 2).toInt(16),
          hex.substring(2, 4).toInt(16),
          hex.substring(4, 6).toInt(16)
        )
        8 -> Color.argb(
          hex.substring(6, 8).toInt(16), // alpha last in #RRGGBBAA
          hex.substring(0, 2).toInt(16),
          hex.substring(2, 4).toInt(16),
          hex.substring(4, 6).toInt(16)
        )
        else -> null
      }
    } catch (e: NumberFormatException) {
      null
    }
  }
}