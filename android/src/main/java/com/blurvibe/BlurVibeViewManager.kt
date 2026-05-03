package com.blurvibe

import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * ViewGroupManager — BlurVibeView (which extends BlurViewGroup/FrameLayout)
 * hosts React children, so we must use ViewGroupManager, not SimpleViewManager.
 */
class BlurVibeViewManager : ViewGroupManager<BlurVibeView>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext) = BlurVibeView(context)

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: BlurVibeView, amount: Float) {
    view.setBlurAmount(amount)
  }

  @ReactProp(name = "blurType")
  fun setBlurType(view: BlurVibeView, type: String?) {
    // No-op on Android — blurType maps to iOS UIBlurEffectStyle only
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColor(view: BlurVibeView, color: String?) {
    view.setOverlayColor(color)
  }

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: BlurVibeView, color: String?) {
    view.setReducedTransparencyFallbackColor(color)
  }

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadius(view: BlurVibeView, radius: Int) {
    view.setBlurRadius(radius)
  }

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: BlurVibeView, radius: Float) {
    view.setBorderRadius(radius)
  }

  // React Native's Yoga handles child layout — return false
  override fun needsCustomLayoutForChildren(): Boolean = false
}