package com.blurvibe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.graphics.toColorInt
import com.facebook.react.views.view.ReactViewGroup

/**
 * LiquidGlassView — the public component.
 *
 * API 33+: hosts a LiquidGlassShaderView child (real-time optical
 * refraction via RuntimeShader — see that class).
 *
 * API < 33: RuntimeShader doesn't exist on these API levels, so true
 * refraction isn't possible. Instead this hosts a plain blur child
 * (BlurVibeViewApi31 on API 31-32, BlurVibeView below that — reusing ALL
 * existing, already-optimized blur infrastructure with zero duplication),
 * and draws a diagonal highlight gradient + a subtle rim stroke on top in
 * its own onDraw() — a static approximation of a glass look, not a real
 * optical effect. This is an intentional, visible difference from the
 * API 33+ path, not an attempt to replicate refraction without a shader.
 */
class LiquidGlassView(context: Context) : ReactViewGroup(context) {

  // ── Props (shared surface across both tiers where meaningful) ────────────

  private var refractionAmount = 40f
  private var blurAmount        = 30f
  private var tintColor         = Color.argb(20, 255, 255, 255)
  private var cornerRadiusDp    = 20f

  // ── Child views (exactly one of these exists at a time) ──────────────────

  private var shaderChild: LiquidGlassShaderView? = null
  private var blurChild: View? = null

  // ── Fallback highlight paint (API < 33 only) ──────────────────────────────

  private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.5f
  }
  private var highlightShader: Shader? = null
  private var highlightShaderW = -1
  private var highlightShaderH = -1

  // ── Init ───────────────────────────────────────────────────────────────────

  init {
    setWillNotDraw(false)
    outlineProvider = ViewOutlineProvider.BACKGROUND

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val child = LiquidGlassShaderView(context)
      child.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
      addView(child)
      shaderChild = child
    } else {
      val child = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BlurVibeViewApi31(context)
      } else {
        BlurVibeView(context)
      }
      child.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
      addView(child)
      blurChild = child
    }
  }

  // ── onDraw — only used to add the highlight/rim on the fallback path ──────
  //
  // On API 33+, shaderChild does 100% of the rendering; this view draws
  // nothing extra. On API < 33, blurChild fills the bounds first (drawn as
  // an ordinary child during dispatchDraw), then onDrawForeground runs
  // AFTER all children — the correct Android hook for "draw on top of my
  // own children."

  override fun onDrawForeground(canvas: Canvas) {
    super.onDrawForeground(canvas)
    if (blurChild == null) return // API 33+ path — nothing to add

    val w = width.toFloat();  if (w <= 0f) return
    val h = height.toFloat(); if (h <= 0f) return

    if (highlightShader == null || highlightShaderW != width || highlightShaderH != height) {
      highlightShader = LinearGradient(
        0f, 0f, w * 0.6f, h * 0.6f,
        Color.argb((90 * (refractionAmount.coerceIn(0f, 100f) / 100f)).toInt(), 255, 255, 255),
        Color.TRANSPARENT,
        Shader.TileMode.CLAMP
      )
      highlightShaderW = width
      highlightShaderH = height
    }
    highlightPaint.shader = highlightShader
    canvas.drawRect(0f, 0f, w, h, highlightPaint)

    if (Color.alpha(tintColor) > 0) {
      val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
      canvas.drawRect(0f, 0f, w, h, tintPaint)
    }

    rimPaint.color = Color.argb(70, 255, 255, 255)
    val inset = rimPaint.strokeWidth / 2f
    canvas.drawRect(inset, inset, w - inset, h - inset, rimPaint)
  }

  // ── Public setters — forwarded to whichever child is active ──────────────

  fun setRefractionAmount(v: Float) {
    refractionAmount = v
    shaderChild?.setRefractionAmount(v)
    invalidate()
  }

  fun setBlurAmountProp(v: Float) {
    blurAmount = v
    shaderChild?.setBlurAmount(v)
    when (val child = blurChild) {
      is BlurVibeViewApi31 -> child.setBlurAmount(v)
      is BlurVibeView       -> child.setBlurAmount(v)
    }
  }

  fun setEdgeWidth(dp: Float)     { shaderChild?.setEdgeWidth(dp) }
  fun setCurvatureBlend(v: Float) { shaderChild?.setCurvatureBlend(v) }
  fun setDispersion(v: Float)     { shaderChild?.setDispersion(v) }
  fun setSaturationBoost(v: Float){ shaderChild?.setSaturationBoost(v) }
  fun setContrastBoost(v: Float)  { shaderChild?.setContrastBoost(v) }
  fun setBrightnessLift(v: Float) { shaderChild?.setBrightnessLift(v) }

  fun setTintColorProp(colorString: String?) {
    tintColor = parseHexColor(colorString ?: "transparent") ?: Color.argb(20, 255, 255, 255)
    shaderChild?.setTintColor(colorString)
    when (val child = blurChild) {
      is BlurVibeViewApi31 -> child.setOverlayColor(colorString)
      is BlurVibeView       -> child.setOverlayColor(colorString)
    }
    invalidate()
  }

  fun applyBorderRadius(radiusDp: Float) {
    cornerRadiusDp = radiusDp
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        val px = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
        )
        outline.setRoundRect(0, 0, view.width, view.height, px)
      }
    }
    clipToOutline = radiusDp > 0f
    shaderChild?.applyBorderRadius(radiusDp)
    when (val child = blurChild) {
      is BlurVibeViewApi31 -> child.applyBorderRadius(radiusDp)
      is BlurVibeView       -> child.applyBorderRadius(radiusDp)
    }
    invalidate()
  }

  // ── Layout: single child fills bounds ─────────────────────────────────────

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    val w = right - left
    val h = bottom - top
    (shaderChild ?: blurChild)?.layout(0, 0, w, h)
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

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
