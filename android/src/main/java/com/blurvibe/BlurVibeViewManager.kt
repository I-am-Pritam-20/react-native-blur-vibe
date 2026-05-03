package com.blurvibe

import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * All @ReactProp handler names are intentionally distinct from BaseViewManager /
 * ReactViewGroup / View supertype methods to avoid "hides member" compile errors:
 *
 *   setBlurAmount              — unique, not in any supertype
 *   setBlurTypeProp            — avoids any "setBlurType" conflict
 *   setOverlayColorProp        — avoids BaseViewManager.setBackgroundColor etc.
 *   setReducedTransparencyFallbackColor — unique
 *   setBlurRadiusProp          — avoids View.setRadius / BlurView.setBlurRadius
 *   setBlurBorderRadius        — avoids BaseViewManager.setBorderRadius
 */
class BlurVibeViewManager : ViewGroupManager<BlurVibeView>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext) = BlurVibeView(context)

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: BlurVibeView, amount: Float) =
    view.setBlurAmount(amount)

  @ReactProp(name = "blurType")
  fun setBlurTypeProp(view: BlurVibeView, @Suppress("UNUSED_PARAMETER") type: String?) {
    // iOS UIBlurEffectStyle — no-op on Android
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColorProp(view: BlurVibeView, color: String?) =
    view.applyOverlayColor(color)

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: BlurVibeView, color: String?) =
    view.setReducedTransparencyFallbackColor(color)

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadiusProp(view: BlurVibeView, radius: Int) =
    view.applyBlurRadius(radius)

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: BlurVibeView, radius: Float) =
    view.applyBorderRadius(radius)

  override fun needsCustomLayoutForChildren(): Boolean = false
}