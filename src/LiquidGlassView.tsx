import { StyleSheet } from 'react-native';
import type { LiquidGlassViewProps } from './LiquidGlassTypes';
import NativeLiquidGlassView from './LiquidGlassViewNativeComponent';

/**
 * LiquidGlassView — real-time optical refraction ("liquid glass"),
 * separate from BlurView.
 *
 * Android API 33+: true refraction via RuntimeShader — a lens-like bulge
 * with chromatic dispersion at the panel's edges, clear pass-through in
 * the center, same visual family as iOS/visionOS "liquid glass."
 *
 * Android API < 33 and iOS: RuntimeShader isn't available, so this
 * renders as blur + a static diagonal highlight + a subtle rim stroke —
 * a visual approximation, not real refraction. This is an intentional,
 * visible difference between platforms/API levels, not a bug.
 */
const LiquidGlassView = ({
  refractionAmount = 40,
  blurAmount = 30,
  edgeWidth = 24,
  curvatureBlend = 0.5,
  dispersion = 0.35,
  saturationBoost = 1.1,
  contrastBoost = 1.05,
  brightnessLift = 0.02,
  tintColor = '#FFFFFF14',
  style,
  children,
  ...rest
}: LiquidGlassViewProps) => {
  return (
    <NativeLiquidGlassView
      refractionAmount={refractionAmount}
      blurAmount={blurAmount}
      edgeWidth={edgeWidth}
      curvatureBlend={curvatureBlend}
      dispersion={dispersion}
      saturationBoost={saturationBoost}
      contrastBoost={contrastBoost}
      brightnessLift={brightnessLift}
      tintColor={tintColor}
      style={[styles.transparent, style]}
      {...rest}
    >
      {children}
    </NativeLiquidGlassView>
  );
};

LiquidGlassView.displayName = 'LiquidGlassView';

const styles = StyleSheet.create({
  transparent: { backgroundColor: 'transparent' },
});

export default LiquidGlassView;
