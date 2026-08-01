// @ts-ignore
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent, ViewProps } from 'react-native';
// @ts-ignore
import type { WithDefault, Float } from 'react-native/Libraries/Types/CodegenTypes';

interface NativeProps extends ViewProps {
  refractionAmount?: WithDefault<Float, 40>;
  blurAmount?: WithDefault<Float, 30>;
  edgeWidth?: WithDefault<Float, 24>;
  curvatureBlend?: WithDefault<Float, 0.5>;
  dispersion?: WithDefault<Float, 0.35>;
  saturationBoost?: WithDefault<Float, 1.1>;
  contrastBoost?: WithDefault<Float, 1.05>;
  brightnessLift?: WithDefault<Float, 0.02>;
  tintColor?: string;
}

export default codegenNativeComponent<NativeProps>(
  'LiquidGlassView'
) as HostComponent<NativeProps>;
