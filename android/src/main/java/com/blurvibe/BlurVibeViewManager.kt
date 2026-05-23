package com.blurvibe

import android.os.Build
import android.view.ViewGroup
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.BlurVibeViewManagerDelegate
import com.facebook.react.viewmanagers.BlurVibeViewManagerInterface

/**
 * BlurVibeViewManager
 *
 * Supports BOTH Old Architecture (Paper) and New Architecture (Fabric):
 *
 * Old Arch: React Native calls @ReactProp methods directly via reflection.
 * New Arch: React Native calls the generated BlurVibeViewManagerDelegate,
 *           which forwards to our BlurVibeViewManagerInterface implementation.
 *
 * The dual-arch pattern (SimpleViewManager + ViewManagerDelegate + Interface)
 * is the official React Native recommended approach for library interop.
 * Reference: https://reactnative.dev/docs/the-new-architecture/backward-compatibility-with-libraries
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
class BlurVibeViewManager : SimpleViewManager<ViewGroup>(),
  BlurVibeViewManagerInterface<ViewGroup> {

  private val delegate = BlurVibeViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<ViewGroup> = delegate

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext): ViewGroup =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurVibeViewApi31(context)
    else BlurVibeView(context)

  // ── Core props ─────────────────────────────────────────────────────────────

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  override fun setBlurAmount(view: ViewGroup, amount: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.setBlurAmount(amount)
      is BlurVibeView      -> view.setBlurAmount(amount)
    }
  }

  @ReactProp(name = "blurType")
  fun setBlurTypeProp(view: ViewGroup, type: String?) {
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
  override fun setReducedTransparencyFallbackColor(view: ViewGroup, color: String?) {
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
  override fun setEnabled(view: ViewGroup, enabled: Boolean) {
    when (view) {
      is BlurVibeViewApi31 -> view.applyBlurEnabled(enabled)
      is BlurVibeView      -> view.applyBlurEnabled(enabled)
    }
  }

  @ReactProp(name = "autoUpdate", defaultBoolean = true)
  override fun setAutoUpdate(view: ViewGroup, autoUpdate: Boolean) {
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
  override fun setProgressiveBlurDirection(view: ViewGroup, direction: String?) {
    if (view is BlurVibeViewApi31) view.setProgressiveBlurDirection(direction)
  }

  @ReactProp(name = "progressiveStartIntensity", defaultFloat = 1f)
  override fun setProgressiveStartIntensity(view: ViewGroup, intensity: Float) {
    if (view is BlurVibeViewApi31) view.setProgressiveStartIntensity(intensity)
  }

  @ReactProp(name = "progressiveEndIntensity", defaultFloat = 0f)
  override fun setProgressiveEndIntensity(view: ViewGroup, intensity: Float) {
    if (view is BlurVibeViewApi31) view.setProgressiveEndIntensity(intensity)
  }

  // ── Noise (API 31+ only) ──────────────────────────────────────────────────

  @ReactProp(name = "noiseFactor", defaultFloat = 0.08f)
  override fun setNoiseFactor(view: ViewGroup, factor: Float) {
    if (view is BlurVibeViewApi31) view.setNoiseFactor(factor)
  }

  // ── Interface methods not mapped to @ReactProp (required by codegen) ───────

  override fun setBlurRadius(view: ViewGroup, radius: Int) {
    if (view is BlurVibeView) view.setBlurRadius(radius)
  }

  override fun setBlurType(view: ViewGroup, type: String?) {
    // iOS only — no-op
  }

  override fun setOverlayColor(view: ViewGroup, color: String?) {
    setOverlayColorProp(view, color)
  }


  companion object {
    const val NAME = "BlurVibeView"
  }
}