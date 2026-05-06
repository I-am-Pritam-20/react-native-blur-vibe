import { Platform, StyleSheet } from 'react-native';
import type { BlurViewProps } from './types';
import NativeBlurVibeView from './BlurVibeViewNativeComponent';

/**
 * BlurView — react-native-blur-vibe
 *
 * Cross-platform backdrop blur.
 *
 * iOS:               UIVisualEffectView — compositor-level, always smooth
 * Android API 31+:   Dual-RenderNode + RenderEffect — GPU, no pixelation,
 *                    supports progressive blur + noise
 * Android API < 31:  QmBlurView RenderScript — CPU, smooth at downsample=4
 *
 * @example Basic frosted glass
 * <BlurView
 *   blurAmount={30}
 *   overlayColor="#FFFFFF20"
 *   style={StyleSheet.absoluteFill}
 * />
 *
 * @example Progressive blur (fades from full blur at top to transparent at bottom)
 * <BlurView
 *   blurAmount={40}
 *   overlayColor="#00000040"
 *   progressiveBlurDirection="topToBottom"
 *   progressiveStartIntensity={1}
 *   progressiveEndIntensity={0}
 *   style={StyleSheet.absoluteFill}
 * />
 *
 * @example Music card frosted glass with noise
 * <BlurView
 *   blurAmount={60}
 *   overlayColor="#FFFFFF15"
 *   noiseFactor={0.12}
 *   borderRadius={16}
 *   style={StyleSheet.absoluteFill}
 * />
 */
const BlurView = ({
  blurAmount = 10,
  blurType = 'light',
  overlayColor,
  reducedTransparencyFallbackColor = '#F2F2F2',
  blurRadius = 4,
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
  transparent: {
    backgroundColor: 'transparent',
  },
});

export default BlurView;