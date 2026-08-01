import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent, ViewProps } from 'react-native';

interface NativeProps extends ViewProps {
  refractionAmount?: number;
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
