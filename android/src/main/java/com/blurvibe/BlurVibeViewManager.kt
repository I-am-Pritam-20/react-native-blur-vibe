package com.blurvibe

import android.os.Build
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.views.view.ReactViewGroup

/**
 * BlurVibeViewManager
 *
 * Must extend ViewGroupManager<ReactViewGroup> — the type param must be a
 * subtype of ViewGroup. Both BlurVibeView and BlurVibeViewApi31 extend
 * ReactViewGroup so this satisfies the bound.
 *
 * @ReactProp handlers receive ReactViewGroup and smart-cast via `when`.
 *
 * Naming rules to avoid supertype collisions on the VIEW classes:
 *   Manager method       → View method called
 *   setBlurBorderRadius  → applyBorderRadius   (ReactViewGroup has setBorderRadius)
 *   setBlurRadiusProp    → setBlurRadius        (unique name on BlurVibeView)
 *   setOverlayColorProp  → setOverlayColor      (unique — not in ReactViewGroup)
 *   setBlurTypeProp      → no-op
 */
class BlurVibeViewManager : ViewGroupManager<ReactViewGroup>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext): ReactViewGroup =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurVibeViewApi31(context)
    else BlurVibeView(context)

  // ── Core props ─────────────────────────────────────────────────────────────

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: ReactViewGroup, amount: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.setBlurAmount(amount)
      is BlurVibeView      -> view.setBlurAmount(amount)
    }
  }

  @ReactProp(name = "blurType")
  fun setBlurTypeProp(view: ReactViewGroup, @Suppress("UNUSED_PARAMETER") type: String?) {
    // iOS UIBlurEffectStyle only — no-op on Android
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColorProp(view: ReactViewGroup, color: String?) {
    when (view) {
      is BlurVibeViewApi31 -> view.setOverlayColor(color)
      is BlurVibeView      -> view.setOverlayColor(color)
    }
  }

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: ReactViewGroup, color: String?) {
    when (view) {
      is BlurVibeViewApi31 -> view.setReducedTransparencyFallbackColor(color)
      is BlurVibeView      -> view.setReducedTransparencyFallbackColor(color)
    }
  }

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadiusProp(view: ReactViewGroup, radius: Int) {
    // API < 31 only — QmBlurView downsample factor
    // API 31+ uses full-res RenderNode, downsample irrelevant
    if (view is BlurVibeView) view.setBlurRadius(radius)
  }

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: ReactViewGroup, radius: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.applyBorderRadius(radius)   // renamed — avoids ReactViewGroup.setBorderRadius
      is BlurVibeView      -> view.setBorderRadius(radius)
    }
  }

  // ── Progressive blur props (API 31+ only) ──────────────────────────────────

  @ReactProp(name = "progressiveBlurDirection")
  fun setProgressiveBlurDirection(view: ReactViewGroup, direction: String?) {
    if (view is BlurVibeViewApi31) view.setProgressiveBlurDirection(direction)
  }

  @ReactProp(name = "progressiveStartIntensity", defaultFloat = 1f)
  fun setProgressiveStartIntensity(view: ReactViewGroup, intensity: Float) {
    if (view is BlurVibeViewApi31) view.setProgressiveStartIntensity(intensity)
  }

  @ReactProp(name = "progressiveEndIntensity", defaultFloat = 0f)
  fun setProgressiveEndIntensity(view: ReactViewGroup, intensity: Float) {
    if (view is BlurVibeViewApi31) view.setProgressiveEndIntensity(intensity)
  }

  // ── Noise prop (API 31+ only) ──────────────────────────────────────────────

  @ReactProp(name = "noiseFactor", defaultFloat = 0.08f)
  fun setNoiseFactorProp(view: ReactViewGroup, factor: Float) {
    if (view is BlurVibeViewApi31) view.setNoiseFactor(factor)
  }

  override fun needsCustomLayoutForChildren(): Boolean = false
}