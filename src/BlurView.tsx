import { Platform, StyleSheet } from 'react-native';
import type { BlurViewProps } from './types';
import NativeBlurVibeView from './BlurVibeViewNativeComponent';

/**
 * BlurView — react-native-blur-vibe
 *
 * Cross-platform backdrop-filter: blur() for React Native.
 *
 * iOS:               UIVisualEffectView with true custom radius via effectSettings KVC
 * Android API 31+:   Dual-RenderNode + RenderEffect GPU pipeline
 * Android API < 31:  Direct RenderScript Gaussian (zero external dependencies)
 *
 * Works on both Old Architecture (Paper) and New Architecture (Fabric).
 */
const BlurView = ({
  blurAmount = 10,
  blurType = 'light',
  overlayColor,
  reducedTransparencyFallbackColor = '#F2F2F2',
  blurRadius = 4,
  enabled = true,
  autoUpdate = true,
  progressiveBlurDirection = 'none',
  progressiveStartIntensity = 1.0,
  progressiveEndIntensity = 0.0,
  noiseFactor = 0.08,
  style,
  children,
  ...rest
}: BlurViewProps) => {
  const resolvedOverlayColor =
    overlayColor ?? (Platform.OS === 'android' ? '#00000030' : 'transparent');

  return (
    <NativeBlurVibeView
      blurAmount={blurAmount}
      blurType={blurType}
      overlayColor={resolvedOverlayColor}
      reducedTransparencyFallbackColor={reducedTransparencyFallbackColor}
      blurRadius={blurRadius}
      enabled={enabled}
      autoUpdate={autoUpdate}
      progressiveBlurDirection={progressiveBlurDirection}
      progressiveStartIntensity={progressiveStartIntensity}
      progressiveEndIntensity={progressiveEndIntensity}
      noiseFactor={noiseFactor}
      style={[styles.transparent, style]}
      {...rest}
    >
      {children}
    </NativeBlurVibeView>
  );
};

BlurView.displayName = 'BlurView';

const styles = StyleSheet.create({
  transparent: { backgroundColor: 'transparent' },
});

export default BlurView;