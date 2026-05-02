package com.blurvibe

import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * Extends ViewGroupManager — NOT SimpleViewManager.
 * Reason: BlurVibeView hosts children (overlay + React children).
 * SimpleViewManager cast to IViewGroupManager crashes at runtime.
 * ViewGroupManager correctly implements IViewGroupManager interface.
 */
class BlurVibeViewManager : ViewGroupManager<BlurVibeView>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext) = BlurVibeView(context)

  // Float — matches TS NativeComponent Float 
  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: BlurVibeView, amount: Float) {
    view.setBlurAmount(amount)
  }

  // String — matches TS NativeComponent string  (no-op on Android)
  @ReactProp(name = "blurType")
  fun setBlurType(view: BlurVibeView, type: String?) {
    // No-op — blurType maps to iOS UIBlurEffectStyle only
  }

  // String — matches TS NativeComponent string 
  // Parsed as hex in BlurVibeView — no customType="Color" needed
  @ReactProp(name = "overlayColor")
  fun setOverlayColor(view: BlurVibeView, color: String?) {
    view.setOverlayColor(color)
  }

  // String — matches TS NativeComponent string 
  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: BlurVibeView, color: String?) {
    view.setReducedTransparencyFallbackColor(color)
  }

  // Int — matches TS NativeComponent Int32 
  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadius(view: BlurVibeView, radius: Int) {
    view.setBlurRadius(radius)
  }
}