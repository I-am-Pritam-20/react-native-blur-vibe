import type { ViewProps } from 'react-native';

export interface LiquidGlassViewProps extends ViewProps {
  /**
   * Strength of the edge refraction — how strongly content displaces near
   * the panel's edges. Range: 0–100.
   *
   * **Android API 33+ only.** Ignored on API < 33 and iOS, where this
   * component falls back to blur + a static highlight instead of real
   * refraction — see the component doc.
   *
   * @default 40
   */
  refractionAmount?: number;

  /**
   * Blur strength applied to the captured backdrop. Range: 0–100.
   *
   * On API 33+: `0` gives pure clear refraction (no frosting); higher
   * values chain a blur pass before the refraction shader, for a
   * "frosted liquid glass" look.
   *
   * On API < 33 / iOS (fallback path): this is the ONLY effect applied —
   * there is no refraction on this path, just blur + highlight.
   *
   * @default 30
   */
  blurAmount?: number;

  /**
   * How far from the panel's edge (in dp) the refraction zone extends.
   * Content further than this from any edge is shown clear/undistorted.
   *
   * **Android API 33+ only.**
   *
   * @default 24
   */
  edgeWidth?: number;

  /**
   * Blends between two displacement directions at the edge: `0` follows
   * the rounded-rect's own edge normal (a more "flat pane of glass with
   * curved edges" look), `1` displaces radially away from the panel's
   * center (a more "spherical lens" look). Range: 0.0–1.0.
   *
   * **Android API 33+ only.**
   *
   * @default 0.5
   */
  curvatureBlend?: number;

  /**
   * Chromatic dispersion strength at the edges — how far apart red/green/
   * blue sample offsets spread, simulating the rainbow fringing real
   * glass edges show. `0` disables dispersion (edges refract but stay
   * neutral in color). Range: 0.0–1.0.
   *
   * **Android API 33+ only.**
   *
   * @default 0.35
   */
  dispersion?: number;

  /** Color saturation multiplier applied to the whole panel. `1.0` = no change. @default 1.1 */
  saturationBoost?: number;

  /** Contrast multiplier applied to the whole panel. `1.0` = no change. @default 1.05 */
  contrastBoost?: number;

  /** Flat brightness offset applied to the whole panel. @default 0.02 */
  brightnessLift?: number;

  /**
   * RGBA tint color blended over the panel — same format as BlurView's
   * `overlayColor`: `"transparent"`, `"#RGB"`, `"#RRGGBB"`, `"#RRGGBBAA"`.
   *
   * @default "#FFFFFF14"
   */
  tintColor?: string;
}
