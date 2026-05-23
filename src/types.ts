import type { ViewProps } from 'react-native';

// ─────────────────────────────────────────────────────────────────────────────
// BlurType
// ─────────────────────────────────────────────────────────────────────────────

/**
 * iOS blur material style — maps directly to `UIBlurEffect.Style`.
 *
 * **iOS only.** Ignored on Android (Android uses `blurAmount` + `overlayColor`
 * to control blur appearance).
 *
 * ### Adaptive styles (recommended)
 * These automatically adapt to light/dark mode:
 * - `"light"` — light frosted glass (default)
 * - `"dark"` — dark frosted glass
 * - `"extraLight"` — brighter than light
 * - `"regular"` — system default material
 * - `"prominent"` — higher contrast than regular
 *
 * ### Material styles (iOS 13+, adaptive)
 * - `"systemUltraThinMaterial"` — thinnest, most transparent
 * - `"systemThinMaterial"` — thin
 * - `"systemMaterial"` — medium (equivalent to iOS sheet backgrounds)
 * - `"systemThickMaterial"` — thick
 * - `"systemChromeMaterial"` — for toolbars and navigation bars
 *
 * ### Light variants (iOS 13+, always light)
 * - `"systemUltraThinMaterialLight"`
 * - `"systemThinMaterialLight"`
 * - `"systemMaterialLight"`
 * - `"systemThickMaterialLight"`
 * - `"systemChromeMaterialLight"`
 *
 * ### Dark variants (iOS 13+, always dark)
 * - `"systemUltraThinMaterialDark"`
 * - `"systemThinMaterialDark"`
 * - `"systemMaterialDark"`
 * - `"systemThickMaterialDark"`
 * - `"systemChromeMaterialDark"`
 */
export type BlurType =
  | 'light'
  | 'dark'
  | 'extraLight'
  | 'regular'
  | 'prominent'
  | 'systemUltraThinMaterial'
  | 'systemThinMaterial'
  | 'systemMaterial'
  | 'systemThickMaterial'
  | 'systemChromeMaterial'
  | 'systemUltraThinMaterialLight'
  | 'systemThinMaterialLight'
  | 'systemMaterialLight'
  | 'systemThickMaterialLight'
  | 'systemChromeMaterialLight'
  | 'systemUltraThinMaterialDark'
  | 'systemThinMaterialDark'
  | 'systemMaterialDark'
  | 'systemThickMaterialDark'
  | 'systemChromeMaterialDark';

// ─────────────────────────────────────────────────────────────────────────────
// ProgressiveBlurDirection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Direction for progressive (gradient) blur.
 *
 * Controls which axis the blur fades across, and which end starts at full
 * intensity vs. transparent. Use with `progressiveStartIntensity` and
 * `progressiveEndIntensity` for fine control.
 *
 * **iOS**: Uses `CAFilter variableBlur` — true per-pixel variable radius,
 * same technique as Apple's Home Screen and Control Center. Falls back to
 * `maskView` opacity gradient if CAFilter is unavailable.
 *
 * **Android API 31+**: Uses `LinearGradient`/`RadialGradient` as an alpha
 * mask over the GPU `RenderEffect` blur layer.
 *
 * **Android API < 31**: Silently ignored (uniform blur is shown).
 *
 * | Value            | Blur starts at | Fades towards  |
 * |------------------|----------------|----------------|
 * | `"topToBottom"`  | Top edge       | Bottom edge    |
 * | `"bottomToTop"`  | Bottom edge    | Top edge       |
 * | `"leftToRight"`  | Left edge      | Right edge     |
 * | `"rightToLeft"`  | Right edge     | Left edge      |
 * | `"radial"`       | Center         | Outer edges    |
 * | `"none"`         | — uniform blur — no gradient   |
 *
 * @example
 * // Sticky header: full blur at top, invisible at bottom
 * progressiveBlurDirection="topToBottom"
 * progressiveStartIntensity={1}
 * progressiveEndIntensity={0}
 *
 * @example
 * // Bottom sheet scrim: invisible at top, full blur at bottom
 * progressiveBlurDirection="bottomToTop"
 * progressiveStartIntensity={1}
 * progressiveEndIntensity={0}
 */
export type ProgressiveBlurDirection =
  | 'topToBottom'
  | 'bottomToTop'
  | 'leftToRight'
  | 'rightToLeft'
  | 'radial'
  | 'none';

// ─────────────────────────────────────────────────────────────────────────────
// BlurViewProps
// ─────────────────────────────────────────────────────────────────────────────

export interface BlurViewProps extends ViewProps {
  // ─── Core props (iOS + Android) ───────────────────────────────────────────

  /**
   * Blur intensity. Range: `0` (no blur) to `100` (maximum blur).
   *
   * Approximate CSS `backdrop-filter` equivalents:
   *
   * | `blurAmount` | CSS equivalent          | Visual feel         |
   * |-------------|-------------------------|---------------------|
   * | `5`         | `backdrop-blur-sm` (4px)  | Subtle hint of blur |
   * | `15`        | `backdrop-blur` (8px)     | Light frosted glass |
   * | `25`        | `backdrop-blur-md` (12px) | Standard card blur  |
   * | `50`        | `backdrop-blur-xl` (24px) | Heavy frosted glass |
   * | `75`        | `backdrop-blur-2xl`       | Dense blur          |
   * | `100`       | `backdrop-blur-3xl`       | Maximum blur        |
   *
   * **iOS**: Controls `UIViewPropertyAnimator` fraction on `UIBlurEffect`.
   * **Android API 31+**: Maps quadratically to `RenderEffect.createBlurEffect` radius (0–25px).
   * **Android API < 31**: Maps to `RenderScript` Gaussian radius via QmBlurView.
   *
   * @default 10
   */
  blurAmount?: number;

  /**
   * Overlay color composited **on top of** the blur layer.
   *
   * Equivalent to CSS:
   * ```css
   * backdrop-filter: blur(Xpx);
   * background-color: <overlayColor>;
   * ```
   *
   * The alpha channel controls how much of the blur is visible:
   * - `"#00000000"` — fully transparent, pure blur (no tint)
   * - `"#00000040"` — 25% black tint over blur (dark frosted glass)
   * - `"#FFFFFF30"` — 19% white tint over blur (light frosted glass)
   * - `"#000000FF"` — fully opaque black, blur is hidden
   *
   * Supported color formats: `"transparent"`, `"#RGB"`, `"#RRGGBB"`, `"#RRGGBBAA"`
   *
   * **Works on both iOS and Android.**
   *
   * @default `"transparent"` on iOS, `"#00000030"` on Android
   */
  overlayColor?: string;

  /**
   * Fallback solid color shown when blur effects are unavailable.
   *
   * Shown when:
   * - **iOS**: User has enabled *Reduce Transparency* in Accessibility settings
   * - **Android**: Device API level < 21
   *
   * Should be a color that provides sufficient contrast for your UI without
   * the blur effect. Commonly a semi-opaque version of your background color.
   *
   * Supported formats: `"transparent"`, `"#RGB"`, `"#RRGGBB"`, `"#RRGGBBAA"`
   *
   * **Works on both iOS and Android.**
   *
   * @default `"#F2F2F2"`
   */
  reducedTransparencyFallbackColor?: string;

  // ─── iOS-only props ───────────────────────────────────────────────────────

  /**
   * iOS blur material style.
   *
   * Maps to `UIBlurEffect.Style`. Controls the visual character of the blur —
   * thickness, color tint, and how content shows through.
   *
   * Use adaptive styles (`"systemMaterial"`, `"light"`, `"dark"`) for apps
   * that support both light and dark mode.
   *
   * **iOS only.** Ignored on Android — use `overlayColor` to tint the blur
   * on Android.
   *
   * @default `"light"`
   * @platform ios
   */
  blurType?: BlurType;

  // ─── Android-only props ───────────────────────────────────────────────────

  /**
   * Android API < 31 only — RenderScript capture downsample factor.
   *
   * Controls how aggressively the screen is downsampled before the blur
   * kernel is applied. Higher values are faster but produce a softer,
   * less detailed blur.
   *
   * | Value | Resolution captured | Quality  | Performance |
   * |-------|---------------------|----------|-------------|
   * | `1`   | Full resolution     | Sharpest | Slowest     |
   * | `4`   | 1/16 pixels (default) | Good   | Fast        |
   * | `8`   | 1/64 pixels         | Softer   | Fastest     |
   *
   * On **Android API 31+** this prop is ignored — blur runs at full
   * resolution on the GPU via `RenderEffect`.
   *
   * On **iOS** this prop is ignored entirely.
   *
   * @default 4
   * @platform android
   */
  blurRadius?: number;

  // ─── Progressive blur props (iOS + Android API 31+) ──────────────────────

  /**
   * Direction the blur fades across the view.
   *
   * Creates a gradient blur effect — full blur intensity at one edge,
   * fading to no blur (or a different intensity) at the other.
   * Commonly used for:
   * - Sticky/floating headers (blur fades downward)
   * - Bottom sheet scrims (blur fades upward)
   * - Side drawers (blur fades horizontally)
   * - Spotlight effects (radial, full blur at center)
   *
   * Use `progressiveStartIntensity` and `progressiveEndIntensity` to
   * control the intensity at each end of the gradient.
   *
   * **iOS**: True per-pixel variable-radius blur via `CAFilter variableBlur`
   * (same as Apple's Home Screen / Control Center). Falls back to opacity
   * masking if CAFilter is unavailable.
   *
   * **Android API 31+**: Alpha mask gradient over GPU `RenderEffect` blur.
   *
   * **Android API < 31**: Silently ignored — uniform blur is shown.
   *
   * @default `"none"` (uniform blur)
   * @platform ios, android (API 31+)
   */
  progressiveBlurDirection?: ProgressiveBlurDirection;

  /**
   * Blur intensity at the **start** of the gradient direction. Range: `0.0`–`1.0`.
   *
   * - `1.0` = full blur (at `blurAmount` intensity)
   * - `0.0` = completely unblurred / transparent
   *
   * What "start" means per direction:
   * - `"topToBottom"` → intensity at the **top** edge
   * - `"bottomToTop"` → intensity at the **bottom** edge
   * - `"leftToRight"` → intensity at the **left** edge
   * - `"rightToLeft"` → intensity at the **right** edge
   * - `"radial"` → intensity at the **center**
   *
   * @default 1.0
   * @platform ios, android (API 31+)
   */
  progressiveStartIntensity?: number;

  /**
   * Blur intensity at the **end** of the gradient direction. Range: `0.0`–`1.0`.
   *
   * - `1.0` = full blur (at `blurAmount` intensity)
   * - `0.0` = completely unblurred / transparent
   *
   * What "end" means per direction:
   * - `"topToBottom"` → intensity at the **bottom** edge
   * - `"bottomToTop"` → intensity at the **top** edge
   * - `"leftToRight"` → intensity at the **right** edge
   * - `"rightToLeft"` → intensity at the **left** edge
   * - `"radial"` → intensity at the **outer edges**
   *
   * @default 0.0
   * @platform ios, android (API 31+)
   */
  progressiveEndIntensity?: number;

  /**
   * Noise grain overlay strength — adds tactile frosted-glass texture.
   *
   * Overlays a subtle static noise pattern on top of the blur layer.
   * This mimics the micro-texture of real ground glass, making digital
   * blur feel more physical and premium.
   *
   * | Value  | Effect                                          |
   * |--------|-------------------------------------------------|
   * | `0`    | No noise — clean digital blur                   |
   * | `0.08` | Subtle grain, barely perceptible (default)      |
   * | `0.15` | Noticeable grain (matches Haze library default) |
   * | `0.30` | Heavy grain — strong tactile texture            |
   *
   * **iOS**: Drawn as a tiled `CGImage` noise layer with `.overlay` blend mode.
   * **Android API 31+**: Tiled `BitmapShader` drawn over the blur layer.
   * **Android API < 31**: Silently ignored.
   *
   * @default 0.08
   * @platform ios, android (API 31+)
   */
  noiseFactor?: number;

  // ─── Blur control ──────────────────────────────────────────────────────────

  /**
   * Enable or disable the blur effect.
   *
   * When `false`, the view renders as transparent (showing
   * `reducedTransparencyFallbackColor` if set). Useful for toggling blur
   * based on scroll position, performance mode, or user preference.
   *
   * **Works on both iOS and Android.**
   *
   * @default true
   */
  enabled?: boolean;

  /**
   * Automatically re-capture and re-blur when the content behind changes.
   *
   * When `false`, the blur is captured once at mount and never updated.
   * Use this for completely static backgrounds (e.g. a blurred album art
   * card where the image never changes) — eliminates all per-frame cost
   * on Android API < 31.
   *
   * **Works on both iOS and Android.**
   *
   * @default true
   */
  autoUpdate?: boolean;
}