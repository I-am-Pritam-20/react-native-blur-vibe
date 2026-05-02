import { Platform, StyleSheet } from 'react-native';
import type { BlurViewProps } from './types';
import NativeBlurVibeView from './BlurVibeViewNativeComponent';

/**
 * BlurView — react-native-blur-vibe
 *
 * Cross-platform blur view for React Native.
 * iOS: UIVisualEffectView | Android: RenderEffect (API 31+) / RenderScript fallback
 *
 * overlayColor works on BOTH platforms — composites a color on top of the blur,
 * exactly like CSS: backdrop-filter: blur(Xpx) + background-color: overlayColor
 *
 * @example
 * <BlurView
 *   blurAmount={15}
 *   blurType="systemMaterial"
 *   overlayColor="#00000040"
 *   style={StyleSheet.absoluteFill}
 * />
 */
const BlurView = ({
  blurAmount = 10,
  blurType = 'light',
  overlayColor,
  reducedTransparencyFallbackColor = '#F2F2F2',
  blurRadius = 4,
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