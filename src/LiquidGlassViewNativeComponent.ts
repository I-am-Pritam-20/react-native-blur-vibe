// @ts-ignore
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent, ViewProps } from 'react-native';
// @ts-ignore
import type { WithDefault, Float, Int32 } from 'react-native/Libraries/Types/CodegenTypes';

interface NativeProps extends ViewProps {
  refractionAmount?: Float;
  blurAmount?: number;
  edgeWidth?: number;
  curvatureBlend?: number;
  dispersion?: number;
  saturationBoost?: number;
  contrastBoost?: number;
  brightnessLift?: number;
  tintColor?: string;
}

export default codegenNativeComponent<NativeProps>(
  'LiquidGlassView'
) as HostComponent<NativeProps>;
