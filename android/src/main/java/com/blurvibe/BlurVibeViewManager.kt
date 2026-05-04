package com.blurvibe

import android.os.Build
import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * BlurVibeViewManager
 *
 * Routes to the correct blur implementation based on API level:
 *
 *   API 31+ → BlurVibeViewApi31  (dual-RenderNode + RenderEffect GPU blur)
 *   API < 31 → BlurVibeView      (QmBlurView RenderScript blur)
 *
 * Both implement the same public setter names so @ReactProp handlers
 * dispatch identically regardless of which class is instantiated.
 *
 * Naming rules to avoid BaseViewManager supertype collisions:
 *   setBlurBorderRadius  (not setBorderRadius — clashes with BaseViewManager)
 *   setBlurRadiusProp    (not setBlurRadius  — could clash with view supertypes)
 *   setOverlayColorProp  (not setOverlayColor — safety margin)
 *   setBlurTypeProp      (not setBlurType)
 */
class BlurVibeViewManager : ViewGroupManager<View>() {

  override fun getName() = "BlurVibeView"

  override fun createViewInstance(context: ThemedReactContext): View {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      BlurVibeViewApi31(context)
    } else {
      BlurVibeView(context)
    }
  }

  @ReactProp(name = "blurAmount", defaultFloat = 10f)
  fun setBlurAmount(view: View, amount: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.setBlurAmount(amount)
      is BlurVibeView      -> view.setBlurAmount(amount)
    }
  }

  @ReactProp(name = "blurType")
  fun setBlurTypeProp(view: View, @Suppress("UNUSED_PARAMETER") type: String?) {
    // iOS UIBlurEffectStyle only — no-op on Android
  }

  @ReactProp(name = "overlayColor")
  fun setOverlayColorProp(view: View, color: String?) {
    when (view) {
      is BlurVibeViewApi31 -> view.setOverlayColor(color)
      is BlurVibeView      -> view.setOverlayColor(color)
    }
  }

  @ReactProp(name = "reducedTransparencyFallbackColor")
  fun setReducedTransparencyFallbackColor(view: View, color: String?) {
    when (view) {
      is BlurVibeViewApi31 -> view.setReducedTransparencyFallbackColor(color)
      is BlurVibeView      -> view.setReducedTransparencyFallbackColor(color)
    }
  }

  @ReactProp(name = "blurRadius", defaultInt = 4)
  fun setBlurRadiusProp(view: View, radius: Int) {
    // blurRadius prop = Android downsample hint for API < 31 only
    // API 31+ uses full-res RenderNode so downsample is irrelevant
    if (view is BlurVibeView) view.setBlurRadius(radius)
  }

  @ReactProp(name = "borderRadius", defaultFloat = 0f)
  fun setBlurBorderRadius(view: View, radius: Float) {
    when (view) {
      is BlurVibeViewApi31 -> view.setBorderRadius(radius)
      is BlurVibeView      -> view.setBorderRadius(radius)
    }
  }

  override fun needsCustomLayoutForChildren(): Boolean = false
}