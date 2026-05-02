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
 * BlurVibeView — Android implementation
 *
 * API 31+  : RenderEffect (hardware accelerated)
 * API 24-30: RenderScript (built-in SDK, no extra dep)
 *
 * Color props (overlayColor, reducedTransparencyFallbackColor) are
 * received as hex strings from JS and parsed manually here.
 * This gives full control over alpha channel handling.
 *
 * Supports: "#RGB" "#RRGGBB" "#RRGGBBAA" "transparent"
 */
@SuppressLint("NewApi")
class BlurVibeView(context: Context) : FrameLayout(context) {

  private val overlayView = View(context)
  private var blurAmountValue: Float = 10f
  private var overlayColorValue: Int = Color.TRANSPARENT
  private var fallbackColorValue: Int = Color.parseColor("#F2F2F2")
  private var blurRadiusDownscale: Int = 4

  init {
    setWillNotDraw(false)
    overlayView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    overlayView.isClickable = false
    overlayView.isFocusable = false
    addView(overlayView)
    applyBlur()
  }

  // MARK: - Public setters (called by ViewManager)

  fun setBlurAmount(amount: Float) {
    blurAmountValue = amount.coerceIn(0f, 100f)
    applyBlur()
  }

  fun setOverlayColor(colorString: String) {
    overlayColorValue = parseHexColor(colorString) ?: Color.TRANSPARENT
    updateOverlay()
  }

  fun setReducedTransparencyFallbackColor(colorString: String) {
    fallbackColorValue = parseHexColor(colorString) ?: Color.parseColor("#F2F2F2")
  }

  fun setBlurRadius(radius: Int) {
    blurRadiusDownscale = radius.coerceIn(1, 8)
    applyBlur()
  }

  // MARK: - Blur

  private fun applyBlur() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      applyRenderEffect()
    } else {
      post { renderScriptBlur() }
    }
    updateOverlay()
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

  // MARK: - Overlay

  private fun updateOverlay() {
    overlayView.setBackgroundColor(overlayColorValue)
    bringChildToFront(overlayView)
    for (i in 0 until childCount) {
      val child = getChildAt(i)
      if (child !== overlayView) bringChildToFront(child)
    }
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    super.onLayout(changed, l, t, r, b)
    if (changed && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      post { renderScriptBlur() }
    }
  }

  // MARK: - Color parser
  // Supports: "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA"
  // Returns null if unparseable (caller uses fallback)
  private fun parseHexColor(colorString: String): Int? {
    val s = colorString.trim()
    if (s.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
    if (!s.startsWith("#")) return null

    val hex = s.removePrefix("#")
    return try {
      when (hex.length) {
        3 -> { // #RGB → #RRGGBB
          val r = hex[0].toString().repeat(2).toInt(16)
          val g = hex[1].toString().repeat(2).toInt(16)
          val b = hex[2].toString().repeat(2).toInt(16)
          Color.argb(255, r, g, b)
        }
        6 -> { // #RRGGBB
          val r = hex.substring(0, 2).toInt(16)
          val g = hex.substring(2, 4).toInt(16)
          val b = hex.substring(4, 6).toInt(16)
          Color.argb(255, r, g, b)
        }
        8 -> { // #RRGGBBAA — note: AA is alpha, last two digits
          val r = hex.substring(0, 2).toInt(16)
          val g = hex.substring(2, 4).toInt(16)
          val b = hex.substring(4, 6).toInt(16)
          val a = hex.substring(6, 8).toInt(16)
          Color.argb(a, r, g, b)
        }
        else -> null
      }
    } catch (e: NumberFormatException) {
      null
    }
  }
}