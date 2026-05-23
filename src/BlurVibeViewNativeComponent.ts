// @ts-ignore
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type { HostComponent, ViewProps } from 'react-native';
// @ts-ignore
import type { WithDefault, Float, Int32} from 'react-native/Libraries/Types/CodegenTypes';

interface NativeProps extends ViewProps {
  blurAmount?: WithDefault<Float, 10>;
  blurType?: string;
  overlayColor?: string;
  reducedTransparencyFallbackColor?: string;
  blurRadius?: WithDefault<Int32, 4>;
  enabled?: WithDefault<boolean, true>;
  autoUpdate?: WithDefault<boolean, true>;
  progressiveBlurDirection?: WithDefault<string, 'none'>;
  progressiveStartIntensity?: WithDefault<Float, 1>;
  progressiveEndIntensity?: WithDefault<Float, 0>;
  noiseFactor?: WithDefault<Float, 0.08>;
}

export default codegenNativeComponent<NativeProps>(
  'BlurVibeView'
) as HostComponent<NativeProps>;