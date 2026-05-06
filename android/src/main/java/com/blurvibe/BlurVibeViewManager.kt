package com.blurvibe

import android.os.Build
import android.view.ViewGroup
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * Extends ViewGroupManager<ViewGroup> so that both BlurVibeView (which extends
 * BlurViewGroup, not ReactViewGroup) and BlurVibeViewApi31 (which extends
 * ReactViewGroup) satisfy the type bound.
 *
 * @ReactProp handlers receive ViewGroup and smart-cast via `when`.
 *
 * Naming rules to avoid supertype collisions on the VIEW classes:
 *   Manager method       → View method called
 *   setBlurBorderRadius  → applyBorderRadius   (ReactViewGroup has setBorderRadius)
 *   setBlurRadiusProp    → setBlurRadius        (unique name on BlurVibeView)
 *   setOverlayColorProp  → setOverlayColor      (unique — not in ReactViewGroup)
 *   setBlurTypeProp      → no-op
 */
class BlurVibeViewManager : ViewGroupManager<ViewGroup>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext): ViewGroup =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurVibeViewApi31(context)
    else BlurVibeView(context)

  // ── Core props ─────────────────────────────────────────────────────────────

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: ViewGroup, amount: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.setBlurAmount(amount)
      is BlurVibeView      -> view.setBlurAmount(amount)
    }
  }

  @ReactProp(name = "blurType")
  fun setBlurTypeProp(view: ViewGroup, @Suppress("UNUSED_PARAMETER") type: String?) {
    // iOS UIBlurEffectStyle only — no-op on Android
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColorProp(view: ViewGroup, color: String?) {
    when (view) {
      is BlurVibeViewApi31 -> view.setOverlayColor(color)
      is BlurVibeView      -> view.setOverlayColor(color)
    }
  }

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: ViewGroup, color: String?) {
    when (view) {
      is BlurVibeViewApi31 -> view.setReducedTransparencyFallbackColor(color)
      is BlurVibeView      -> view.setReducedTransparencyFallbackColor(color)
    }
  }

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadiusProp(view: ViewGroup, radius: Int) {
    // API < 31 only — QmBlurView downsample factor
    // API 31+ uses full-res RenderNode, downsample irrelevant
    if (view is BlurVibeView) view.setBlurRadius(radius)
  }

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: ViewGroup, radius: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.applyBorderRadius(radius)   // renamed — avoids ReactViewGroup.setBorderRadius
      is BlurVibeView      -> view.setBorderRadius(radius)
    }
  }

  // ── Progressive blur props (API 31+ only) ──────────────────────────────────

  @ReactProp(name = "progressiveBlurDirection")
  fun setProgressiveBlurDirection(view: ViewGroup, direction: String?) {
    if (view is BlurVibeViewApi31) view.setProgressiveBlurDirection(direction)
  }

  @ReactProp(name = "progressiveStartIntensity", defaultFloat = 1f)
  fun setProgressiveStartIntensity(view: ViewGroup, intensity: Float) {
    if (view is BlurVibeViewApi31) view.setProgressiveStartIntensity(intensity)
  }

  @ReactProp(name = "progressiveEndIntensity", defaultFloat = 0f)
  fun setProgressiveEndIntensity(view: ViewGroup, intensity: Float) {
    if (view is BlurVibeViewApi31) view.setProgressiveEndIntensity(intensity)
  }

  // ── Noise prop (API 31+ only) ──────────────────────────────────────────────

  @ReactProp(name = "noiseFactor", defaultFloat = 0.08f)
  fun setNoiseFactorProp(view: ViewGroup, factor: Float) {
    if (view is BlurVibeViewApi31) view.setNoiseFactor(factor)
  }

  override fun needsCustomLayoutForChildren(): Boolean = false
}