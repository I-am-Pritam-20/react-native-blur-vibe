package com.blurvibe

import android.os.Build
import android.view.ViewGroup
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * Plain SimpleViewManager + @ReactProp — works correctly under BOTH Old
 * Architecture (Paper) and New Architecture (Fabric) on Android.
 *
 * Fabric on Android calls ViewManager.updateProperties() which dispatches
 * to @ReactProp-annotated setter methods via reflection — the SAME
 * mechanism Old Architecture uses. There is no functional requirement to
 * implement the codegen interface/delegate pattern for a ViewManager to
 * work correctly under Fabric; it's an optional type-safety layer.
 *
 * Type param is ViewGroup — lowest common supertype of:
 *   BlurVibeViewApi31 (extends ReactViewGroup extends ViewGroup)
 *   BlurVibeView      (extends ReactViewGroup extends ViewGroup)
 *
 * Naming rules (prevent supertype method hiding):
 *   setBlurBorderRadius → not setBorderRadius (BaseViewManager has it)
 *   setBlurRadiusProp   → not setBlurRadius   (safety)
 *   setBlurTypeProp     → not setBlurType      (safety)
 *   setOverlayColorProp → not setOverlayColor  (safety)
 */
@ReactModule(name = BlurVibeViewManager.NAME)
class BlurVibeViewManager : SimpleViewManager<ViewGroup>() {

  override fun getName(): String = NAME

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
    // iOS UIBlurEffectStyle — no-op on Android
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
    if (view is BlurVibeView) view.setBlurRadius(radius)
  }

  @ReactProp(name = "enabled", defaultBoolean = true)
  fun setEnabled(view: ViewGroup, enabled: Boolean) {
    when (view) {
      is BlurVibeViewApi31 -> view.applyBlurEnabled(enabled)
      is BlurVibeView      -> view.applyBlurEnabled(enabled)
    }
  }

  @ReactProp(name = "autoUpdate", defaultBoolean = true)
  fun setAutoUpdate(view: ViewGroup, autoUpdate: Boolean) {
    when (view) {
      is BlurVibeViewApi31 -> view.setAutoUpdate(autoUpdate)
      is BlurVibeView      -> view.setAutoUpdate(autoUpdate)
    }
  }

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: ViewGroup, radius: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.applyBorderRadius(radius)
      is BlurVibeView      -> view.applyBorderRadius(radius)
    }
  }

  // ── Progressive blur (API 31+ only) ───────────────────────────────────────

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

  // ── Noise (API 31+ only) ──────────────────────────────────────────────────

  @ReactProp(name = "noiseFactor", defaultFloat = 0.08f)
  fun setNoiseFactor(view: ViewGroup, factor: Float) {
    if (view is BlurVibeViewApi31) view.setNoiseFactor(factor)
  }

  companion object {
    const val NAME = "BlurVibeView"
  }
}