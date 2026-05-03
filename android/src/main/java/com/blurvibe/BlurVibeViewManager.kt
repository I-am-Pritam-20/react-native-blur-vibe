package com.blurvibe

import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * ViewGroupManager because BlurVibeView (→ BlurViewGroup → FrameLayout) hosts
 * React children. needsCustomLayoutForChildren() = false lets Yoga own layout.
 */
class BlurVibeViewManager : ViewGroupManager<BlurVibeView>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext) = BlurVibeView(context)

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: BlurVibeView, amount: Float) = view.setBlurAmount(amount)

  @ReactProp(name = "blurType")
  fun setBlurType(view: BlurVibeView, type: String?) {
    // No-op on Android — blurType is an iOS UIBlurEffectStyle concept only
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColor(view: BlurVibeView, color: String?) = view.setOverlayColor(color)

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: BlurVibeView, color: String?) =
    view.setReducedTransparencyFallbackColor(color)

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadius(view: BlurVibeView, radius: Int) = view.setBlurRadius(radius)

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBorderRadius(view: BlurVibeView, radius: Float) = view.setBorderRadius(radius)

  override fun onDropViewInstance(view: BlurVibeView) {
    super.onDropViewInstance(view)
  }

  // Yoga drives all child layout — return false
  override fun needsCustomLayoutForChildren(): Boolean = false
}
