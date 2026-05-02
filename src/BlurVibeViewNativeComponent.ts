// @ts-ignore - internal RN path, exists at runtime
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent, ViewProps } from 'react-native';
// @ts-ignore - internal RN path, exists at runtime
import type { Float, Int32 } from 'react-native/Libraries/Types/CodegenTypes';

/**
 * NativeComponent codegen spec for BlurVibeView.
 *
 * Type mapping (JS → Native):
 *   Float   → NSNumber (iOS) / Float (Android)
 *   string  → NSString (iOS) / String (Android)
 *   Int32   → NSNumber (iOS) / Int   (Android)
 *
 * Color props (overlayColor, reducedTransparencyFallbackColor) use
 * plain `string` — NOT the RN `ColorValue` type — because we parse
 * hex manually on both platforms for full alpha channel control.
 * Using ColorValue would trigger RN's color normalization which
 * reorders alpha bytes and breaks #RRGGBBAA format.
 */
export interface NativeBlurVibeViewProps extends ViewProps {
  // 0–100 blur intensity
  blurAmount?: Float;

  // iOS UIBlurEffectStyle name — no-op on Android
  blurType?: string;

  // Hex color string with alpha — "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA"
  overlayColor?: string;

  // Fallback when blur unavailable (Reduce Transparency / old API)
  reducedTransparencyFallbackColor?: string;

  // Android downscale factor 1–8 — no-op on iOS
  blurRadius?: Int32;
}

export default codegenNativeComponent<NativeBlurVibeViewProps>(
  'BlurVibeView'
) as HostComponent<NativeBlurVibeViewProps>;