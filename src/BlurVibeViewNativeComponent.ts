import type { ColorValue, ViewProps } from 'react-native';
import type {
  WithDefault,
  Float,
  Int32,
} from 'react-native/Libraries/Types/CodegenTypes';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent } from 'react-native';

/**
 * Codegen spec for BlurVibeView.
 *
 * type: "all" in codegenConfig (package.json) makes this work on BOTH:
 *   - Old Architecture (Paper / RCTViewManager)
 *   - New Architecture (Fabric / RCTViewComponentView)
 *
 * Prop types must use WithDefault<T, default> for scalars — codegen requires this.
 * Colors use ColorValue so Fabric passes a SharedColor (C++ type) on New Arch,
 * and an NSNumber/Int on Old Arch.
 */
interface NativeProps extends ViewProps {
  // ── Core props ────────────────────────────────────────────────────────────

  /** Blur intensity 0–100 */
  blurAmount?: WithDefault<Float, 10>;

  /** iOS UIBlurEffectStyle — no-op on Android */
  blurType?: string;

  /** Hex RGBA overlay color — "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA" */
  overlayColor?: string;

  /** Fallback when blur unavailable (Reduce Transparency / API < 21) */
  reducedTransparencyFallbackColor?: string;

  /** Android API < 31 downsample factor 1–8 */
  blurRadius?: WithDefault<Int32, 4>;

  /** Disable blur entirely */
  enabled?: WithDefault<boolean, true>;

  /** Auto-update blur on content change */
  autoUpdate?: WithDefault<boolean, true>;

  // ── Progressive blur ──────────────────────────────────────────────────────

  /** "none"|"topToBottom"|"bottomToTop"|"leftToRight"|"rightToLeft"|"radial" */
  progressiveBlurDirection?: WithDefault<string, 'none'>;

  /** Intensity at gradient start (0.0–1.0) */
  progressiveStartIntensity?: WithDefault<Float, 1>;

  /** Intensity at gradient end (0.0–1.0) */
  progressiveEndIntensity?: WithDefault<Float, 0>;

  // ── Noise ─────────────────────────────────────────────────────────────────

  /** Noise grain strength (0.0–1.0). API 31+ and iOS only. */
  noiseFactor?: WithDefault<Float, 0.08>;
}

export default codegenNativeComponent<NativeProps>(
  'BlurVibeView'
) as HostComponent<NativeProps>;