package com.blurvibe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.renderscript.Allocation
import androidx.renderscript.Element
import androidx.renderscript.RenderScript
import androidx.renderscript.ScriptIntrinsicBlur

/**
 * BlurVibeView — Android implementation
 *
 * API 31+ : RenderEffect (hardware accelerated, most performant)
 * API 24-30: RenderScript via androidx (bitmap-based fallback)
 *
 * overlayColor is composited ON TOP of blur — same concept as CSS:
 *   backdrop-filter: blur(Xpx) + background-color: overlayColor
 * Alpha of overlayColor controls how much blur is visible.
 */
class BlurVibeView(context: Context) : FrameLayout(context) {

  private val overlayView = View(context)
  private var blurAmountValue: Float = 10f
  private var overlayColorValue: Int = Color.TRANSPARENT
  private var reducedTransparencyFallbackColorValue: Int = Color.parseColor("#F2F2F2")
  private var blurRadiusDownscale: Int = 4

  init {
    setWillNotDraw(false)
    val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    overlayView.layoutParams = lp
    overlayView.isClickable = false
    overlayView.isFocusable = false
    addView(overlayView)
    applyBlur()
  }

  fun setBlurAmount(amount: Float) {
    blurAmountValue = amount.coerceIn(0f, 100f)
    applyBlur()
  }

  fun setOverlayColor(color: Int) {
    overlayColorValue = color
    updateOverlay()
  }

  fun setReducedTransparencyFallbackColor(color: Int) {
    reducedTransparencyFallbackColorValue = color
  }

  fun setBlurRadius(radius: Int) {
    blurRadiusDownscale = radius.coerceIn(1, 8)
    applyBlur()
  }

  private fun applyBlur() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      applyRenderEffect()
    } else {
      post { renderScriptBlur() }
    }
    updateOverlay()
  }

  /** Android 12+ — hardware accelerated, zero bitmap copy */
  private fun applyRenderEffect() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val sigma = (blurAmountValue * 0.5f).coerceAtLeast(0.01f)
      val effect = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.MIRROR)
      setRenderEffect(effect)
    }
  }

  /** API 24-30 — androidx RenderScript bitmap blur */
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
      val blurSigma = (blurAmountValue / 100f * 25f).coerceIn(1f, 25f)
      script.setRadius(blurSigma)
      script.setInput(input)
      script.forEach(output)
      output.copyTo(bitmap)
      rs.destroy()

      background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
    } catch (e: Exception) {
      setBackgroundColor(reducedTransparencyFallbackColorValue)
    }
  }

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
}