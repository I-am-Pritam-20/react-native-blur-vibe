package com.blurvibe

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class BlurVibeViewManager : SimpleViewManager<BlurVibeView>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext) = BlurVibeView(context)

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: BlurVibeView, amount: Float) {
    view.setBlurAmount(amount)
  }

  @ReactProp(name = "blurType")
  fun setBlurType(view: BlurVibeView, type: String?) {
    // No-op on Android — blurType is iOS UIBlurEffectStyle only
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColor(view: BlurVibeView, color: Int) {
    view.setOverlayColor(color)
  }

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: BlurVibeView, color: Int) {
    view.setReducedTransparencyFallbackColor(color)
  }

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadius(view: BlurVibeView, radius: Int) {
    view.setBlurRadius(radius)
  }
}