package com.blurvibe

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class BlurVibeViewManager : SimpleViewManager<BlurVibeView>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext) = BlurVibeView(context)

  // Float — matches TS codegen Float type ✅
  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: BlurVibeView, amount: Float) {
    view.setBlurAmount(amount)
  }

  // String — matches TS codegen string type ✅ (no-op on Android)
  @ReactProp(name = "blurType")
  fun setBlurType(view: BlurVibeView, type: String?) {
    // No-op on Android — blurType is iOS UIBlurEffectStyle only
  }

  // String — matches TS codegen string type ✅
  // We parse hex manually in BlurVibeView for full alpha control
  // Do NOT use Int with customType="Color" — RN reorders alpha bytes unexpectedly
  @ReactProp(name = "overlayColor")
  fun setOverlayColor(view: BlurVibeView, color: String?) {
    view.setOverlayColor(color ?: "transparent")
  }

  // String — matches TS codegen string type ✅
  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: BlurVibeView, color: String?) {
    view.setReducedTransparencyFallbackColor(color ?: "#F2F2F2")
  }

  // Int32 — matches TS codegen Int32 type ✅
  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadius(view: BlurVibeView, radius: Int) {
    view.setBlurRadius(radius)
  }
}