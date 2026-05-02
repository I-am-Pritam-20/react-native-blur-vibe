// @ts-ignore - codegenNativeComponent is available at runtime in RN 0.71+
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent, ViewProps } from 'react-native';
// @ts-ignore - CodegenTypes available at runtime
import type { Float, Int32 } from 'react-native/Libraries/Types/CodegenTypes';

export interface NativeBlurVibeViewProps extends ViewProps {
  blurAmount?: Float;
  blurType?: string;
  overlayColor?: string;
  reducedTransparencyFallbackColor?: string;
  blurRadius?: Int32;
}

export default codegenNativeComponent<NativeBlurVibeViewProps>(
  'BlurVibeView'
) as HostComponent<NativeBlurVibeViewProps>;