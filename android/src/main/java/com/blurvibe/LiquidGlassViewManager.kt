package com.blurvibe

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * LiquidGlassViewManager
 *
 * Unlike BlurVibeViewManager, this manager always creates the SAME
 * LiquidGlassView class — that class handles the API 33+ (real
 * refraction) vs API < 33 (blur + highlight approximation) gating
 * internally by choosing its own child view. See LiquidGlassView's doc.
 *
 * Naming rules (prevent supertype method hiding, same pattern used
 * throughout this codebase):
 *   setBlurAmountProp   → not setBlurAmount (safety, matches BlurView's convention)
 *   setTintColorProp     → not setTintColor
 *   setBlurBorderRadius  → not setBorderRadius (BaseViewManager has it)
 */
@ReactModule(name = LiquidGlassViewManager.NAME)
class LiquidGlassViewManager : SimpleViewManager<LiquidGlassView>() {

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext): LiquidGlassView =
    LiquidGlassView(context)

  @ReactProp(name = "refractionAmount", defaultFloat = 40f)
  fun setRefractionAmount(view: LiquidGlassView, amount: Float) {
    view.setRefractionAmount(amount)
  }

  @ReactProp(name = "blurAmount", defaultFloat = 30f)
  fun setBlurAmountProp(view: LiquidGlassView, amount: Float) {
    view.setBlurAmountProp(amount)
  }

  @ReactProp(name = "edgeWidth", defaultFloat = 24f)
  fun setEdgeWidth(view: LiquidGlassView, dp: Float) {
    view.setEdgeWidth(dp)
  }

  @ReactProp(name = "curvatureBlend", defaultFloat = 0.5f)
  fun setCurvatureBlend(view: LiquidGlassView, v: Float) {
    view.setCurvatureBlend(v)
  }

  @ReactProp(name = "dispersion", defaultFloat = 0.35f)
  fun setDispersion(view: LiquidGlassView, v: Float) {
    view.setDispersion(v)
  }

  @ReactProp(name = "saturationBoost", defaultFloat = 1.1f)
  fun setSaturationBoost(view: LiquidGlassView, v: Float) {
    view.setSaturationBoost(v)
  }

  @ReactProp(name = "contrastBoost", defaultFloat = 1.05f)
  fun setContrastBoost(view: LiquidGlassView, v: Float) {
    view.setContrastBoost(v)
  }

  @ReactProp(name = "brightnessLift", defaultFloat = 0.02f)
  fun setBrightnessLift(view: LiquidGlassView, v: Float) {
    view.setBrightnessLift(v)
  }

  @ReactProp(name = "tintColor")
  fun setTintColorProp(view: LiquidGlassView, color: String?) {
    view.setTintColorProp(color)
  }

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: LiquidGlassView, radius: Float) {
    view.applyBorderRadius(radius)
  }

  companion object {
    const val NAME = "LiquidGlassView"
  }
}
