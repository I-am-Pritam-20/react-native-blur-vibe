// @ts-ignore - internal RN path, exists at runtime
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent, ViewProps } from 'react-native';
// @ts-ignore - internal RN path, exists at runtime
import type { Float, Int32 } from 'react-native/Libraries/Types/CodegenTypes';

export interface NativeBlurVibeViewProps extends ViewProps {
  // 0–100 blur intensity
  blurAmount?: Float;

  // iOS UIBlurEffectStyle name — no-op on Android
  blurType?: string;

  // Hex color string with alpha — "transparent", "#RGB", "#RRGGBB", "#RRGGBBAA"
  overlayColor?: string;

  // Fallback when blur unavailable
  reducedTransparencyFallbackColor?: string;

  // Android API < 31 only: downsample factor 1–8
  blurRadius?: Int32;

  // Progressive blur — Android API 31+ only
  progressiveBlurDirection?: string;
  progressiveStartIntensity?: Float;
  progressiveEndIntensity?: Float;

  // Noise grain overlay — Android API 31+ only
  noiseFactor?: Float;
}

export default codegenNativeComponent<NativeBlurVibeViewProps>(
  'BlurVibeView'
) as HostComponent<NativeBlurVibeViewProps>;