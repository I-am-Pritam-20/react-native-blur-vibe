package com.blurvibe

import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * ViewGroupManager — BlurVibeView (→ BlurView → FrameLayout) hosts React children,
 * so we must use ViewGroupManager. needsCustomLayoutForChildren() = false lets Yoga own layout.
 *
 * Method naming rules to avoid BaseViewManager / BlurView supertype collisions:
 *   - All @ReactProp handler names on the MANAGER are prefixed to avoid hiding supertypes
 *   - All public methods on BlurVibeView (the VIEW) use unique names (apply* prefix)
 *     that don't exist in BlurView, FrameLayout, View, or BaseViewManager
 */
class BlurVibeViewManager : ViewGroupManager<BlurVibeView>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext) = BlurVibeView(context)

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: BlurVibeView, amount: Float) = view.setBlurAmount(amount)

  @ReactProp(name = "blurType")
  fun setBlurType(view: BlurVibeView, type: String?) {
    // No-op on Android — blurType maps to iOS UIBlurEffectStyle only
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColor(view: BlurVibeView, color: String?) = view.applyOverlayColor(color)

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: BlurVibeView, color: String?) =
    view.setReducedTransparencyFallbackColor(color)

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadiusProp(view: BlurVibeView, radius: Int) = view.applyBlurRadius(radius)

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: BlurVibeView, radius: Float) = view.applyBorderRadius(radius)

  override fun needsCustomLayoutForChildren(): Boolean = false
}