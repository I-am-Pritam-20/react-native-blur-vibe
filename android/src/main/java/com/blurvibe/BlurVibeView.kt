package com.blurvibe

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.core.graphics.toColorInt
import com.qmdeve.blurview.base.BaseBlurViewGroup
import com.qmdeve.blurview.widget.BlurViewGroup

/**
 * BlurVibeView — Android backdrop blur implementation
 *
 * Extends QmBlurView's BlurViewGroup — a high-performance blur library
 * that correctly implements CSS backdrop-filter: blur() semantics:
 *   - Blurs content BEHIND the view, not the view itself
 *   - Hardware accelerated via native blur algorithms
 *   - Handles scroll, animation, zIndex, absolute positioning correctly
 *   - Never causes draw loops or bitmap capture on the JS thread
 *
 * Uses reflection to redirect the blur capture root from the activity
 * decor view to the nearest ReactRootView or react-native-screens Screen,
 * preventing full-screen blur and navigation transition artifacts.
 *
 * Credit: approach adapted from sbaiahmed1/react-native-blur
 */
class BlurVibeView(context: Context) : BlurViewGroup(context, null) {

  private var currentBlurRadius = DEFAULT_BLUR_RADIUS
  private var currentOverlayColor = Color.TRANSPARENT
  private var currentCornerRadius = 0f
  private var isBlurInitialized = false

  companion object {
    private const val DEFAULT_BLUR_RADIUS = 10f
    private const val MIN_BLUR_AMOUNT = 0f
    private const val MAX_BLUR_AMOUNT = 100f
    private const val MAX_BLUR_RADIUS = 25f  // QmBlurView Gaussian kernel designed for 0-25

    // Maps 0–100 blurAmount to 0–25 QmBlurView radius range
    private fun mapBlurAmountToRadius(amount: Float): Float {
      val clamped = amount.coerceIn(MIN_BLUR_AMOUNT, MAX_BLUR_AMOUNT)
      return (clamped / MAX_BLUR_AMOUNT) * MAX_BLUR_RADIUS
    }
  }

  init {
    super.setBackgroundColor(currentOverlayColor)
    clipChildren = true
    clipToOutline = true
    blurRounds = 1          // was 5 — single pass is visually identical, 5x cheaper
    super.setDownsampleFactor(8.0f)  // was 6 — 1/64 pixel count, blur hides the difference
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (isBlurInitialized) return
    swapBlurRootToOptimalAncestor()
    initializeBlur()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    isBlurInitialized = false
  }

  private var frameScheduled = false

  private val frameCallback = android.view.Choreographer.FrameCallback {
    frameScheduled = false
    try { invalidate() } catch (_: Exception) {}
  }

  /**
   * Redirects QmBlurView's internal preDrawListener from the old root to [newRoot].
   * Also wraps it in a Choreographer gate so blur work fires at most ONCE per vsync,
   * even when many views invalidate simultaneously (scroll, animation, etc).
   */
   */
  private fun swapBlurRootToOptimalAncestor() {
    val newRoot = findNearestScreenAncestor() ?: findNearestReactRootView() ?: return

    try {
      val blurViewGroupClass = BlurViewGroup::class.java
      val baseField = blurViewGroupClass.getDeclaredField("mBaseBlurViewGroup")
      baseField.isAccessible = true
      val baseBlurViewGroup = baseField.get(this) ?: return

      val baseClass = BaseBlurViewGroup::class.java

      val decorViewField = baseClass.getDeclaredField("mDecorView")
      decorViewField.isAccessible = true
      val oldDecorView = decorViewField.get(baseBlurViewGroup) as? View

      val preDrawListenerField = baseClass.getDeclaredField("preDrawListener")
      preDrawListenerField.isAccessible = true
      val preDrawListener = preDrawListenerField.get(baseBlurViewGroup)
        as? ViewTreeObserver.OnPreDrawListener

      if (oldDecorView != null && preDrawListener != null) {
        // Remove listener from old root
        oldDecorView.viewTreeObserver.removeOnPreDrawListener(preDrawListener)

        // Set new root
        decorViewField.set(baseBlurViewGroup, newRoot)

        // Wrap in Choreographer gate: fires at most once per vsync regardless of
        // how many child invalidations happen in the same frame
        val gatedListener = ViewTreeObserver.OnPreDrawListener {
          if (!frameScheduled) {
            frameScheduled = true
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
          }
          true  // never block the draw pass
        }

        // Add gated listener to new root (NOT the original raw listener)
        newRoot.viewTreeObserver.addOnPreDrawListener(gatedListener)

        // Update mDifferentRoot flag
        val differentRootField = baseClass.getDeclaredField("mDifferentRoot")
        differentRootField.isAccessible = true
        differentRootField.setBoolean(baseBlurViewGroup, newRoot.rootView != this.rootView)

        // Force redraw
        val forceRedrawField = baseClass.getDeclaredField("mForceRedraw")
        forceRedrawField.isAccessible = true
        forceRedrawField.setBoolean(baseBlurViewGroup, true)
      }
    } catch (e: Exception) {
      // Reflection failed — QmBlurView internals changed
      // Fall back gracefully to default decor view blur root
    }
  }

  private fun findNearestScreenAncestor(): ViewGroup? {
    var current = parent
    while (current != null) {
      if (current.javaClass.name == "com.swmansion.rnscreens.Screen") {
        return current as? ViewGroup
      }
      current = current.parent
    }
    return null
  }

  private fun findNearestReactRootView(): ViewGroup? {
    var current = parent
    while (current != null) {
      if (current.javaClass.name == "com.facebook.react.ReactRootView") {
        return current as? ViewGroup
      }
      current = current.parent
    }
    return null
  }

  private fun initializeBlur() {
    if (isBlurInitialized) return
    try {
      super.setBlurRadius(currentBlurRadius)
      super.setOverlayColor(currentOverlayColor)
      updateCornerRadius()
      isBlurInitialized = true
    } catch (e: Exception) {
      // Ignore — view may not be fully attached yet
    }
  }

  // MARK: - Public setters

  fun setBlurAmount(amount: Float) {
    currentBlurRadius = mapBlurAmountToRadius(amount)
    try { super.setBlurRadius(currentBlurRadius) } catch (e: Exception) {}
  }

  fun setOverlayColor(colorString: String?) {
    currentOverlayColor = parseHexColor(colorString ?: "transparent") ?: Color.TRANSPARENT
    try {
      super.setBackgroundColor(currentOverlayColor)
      super.setOverlayColor(currentOverlayColor)
    } catch (e: Exception) {}
  }

  fun setReducedTransparencyFallbackColor(colorString: String?) {
    // Stored for future use — QmBlurView handles accessibility fallback internally
  }

  fun setBlurRadius(radius: Int) {
    // blurRadius is the Android downscale factor — map to QmBlurView's downsample factor
    val downsample = radius.coerceIn(1, 8).toFloat()
    try { super.setDownsampleFactor(downsample) } catch (e: Exception) {}
  }

  fun setBorderRadius(radius: Float) {
    currentCornerRadius = radius
    updateCornerRadius()
  }

  private fun updateCornerRadius() {
    val radiusPx = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      currentCornerRadius,
      context.resources.displayMetrics
    )
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
      }
    }
    clipToOutline = true
    try { super.setCornerRadius(radiusPx) } catch (e: Exception) {}
  }

  // React Native handles layout — prevent superclass from interfering
  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    // No-op: layout handled by React Native's Yoga engine
  }

  // MARK: - Color parser
  // Supports: "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA"
  private fun parseHexColor(colorString: String): Int? {
    val s = colorString.trim()
    if (s.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
    if (!s.startsWith("#")) {
      return try { s.toColorInt() } catch (e: Exception) { null }
    }
    val hex = s.removePrefix("#")
    return try {
      when (hex.length) {
        3 -> Color.argb(
          255,
          hex[0].toString().repeat(2).toInt(16),
          hex[1].toString().repeat(2).toInt(16),
          hex[2].toString().repeat(2).toInt(16)
        )
        6 -> Color.argb(
          255,
          hex.substring(0, 2).toInt(16),
          hex.substring(2, 4).toInt(16),
          hex.substring(4, 6).toInt(16)
        )
        8 -> Color.argb(
          hex.substring(6, 8).toInt(16), // AA is last in #RRGGBBAA
          hex.substring(0, 2).toInt(16),
          hex.substring(2, 4).toInt(16),
          hex.substring(4, 6).toInt(16)
        )
        else -> null
      }
    } catch (e: NumberFormatException) { null }
  }
}