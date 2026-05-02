import type { ViewProps } from 'react-native';

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

export interface BlurViewProps extends ViewProps {
  /**
   * Blur intensity from 0 to 100.
   * @default 10
   */
  blurAmount?: number;

  /**
   * iOS only — maps to UIBlurEffectStyle.
   * @default 'light'
   */
  blurType?: BlurType;

  /**
   * Overlay color composited ON TOP of the blur layer. Works on iOS AND Android.
   *
   * Alpha channel controls how much blur is visible — like CSS:
   *   backdrop-filter: blur(Xpx) + background-color: overlayColor
   *
   * "#00000000" → transparent overlay = pure blur
   * "#00000080" → semi-transparent black tint over blur
   * "#FFFFFFFF" → fully opaque = blur hidden
   *
   * @default "transparent" on iOS | "#00000030" on Android
   */
  overlayColor?: string;

  /**
   * Fallback color when blur is unavailable.
   * (Reduce Transparency on iOS, API < 21 on Android)
   * @default "#F2F2F2"
   */
  reducedTransparencyFallbackColor?: string;

  /**
   * Android only — downscale factor for RenderScript blur path (API 21-30).
   * Higher = faster performance, slightly softer blur. Range: 1–8.
   * @default 4
   */
  blurRadius?: number;
}