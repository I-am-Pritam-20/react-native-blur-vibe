# react-native-blur-vibe
 
A modern, actively maintained blur view for React Native. Works on **iOS** and **Android** with full New Architecture (Fabric) support.
 
> The key difference from other blur libraries: `overlayColor` works on **both iOS and Android** — letting you control blur visibility the same way CSS `backdrop-filter` + `background-color` works on the web.
 
[![npm version](https://img.shields.io/npm/v/react-native-blur-vibe)](https://www.npmjs.com/package/react-native-blur-vibe)
[![Build iOS](https://github.com/I-am-Pritam-20/react-native-blur-vibe/actions/workflows/build-ios.yml/badge.svg)](https://github.com/your-username/react-native-blur-vibe/actions/workflows/build-ios.yml)
[![Build Android](https://github.com/I-am-Pritam-20/react-native-blur-vibe/actions/workflows/build-android.yml/badge.svg)](https://github.com/your-username/react-native-blur-vibe/actions/workflows/build-android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
 
---
 
## Why another blur library?
 
Existing libraries like `@react-native-community/blur` and `react-native-blurview` suffer from:
- Build failures with React Native 0.73+ and the New Architecture
- Android 16KB page size build errors
- `overlayColor` only working on Android, not iOS
- Unmaintained or slow to update
`react-native-blur-vibe` is built from the ground up for modern React Native, with CI builds on every commit.
 
---
 
## The overlayColor concept
 
Think of it exactly like CSS:
 
```css
/* Web equivalent of what this library does */
.blur-view {
  backdrop-filter: blur(10px);        /* ← blurAmount */
  background-color: #00000050;        /* ← overlayColor */
}
```
 
The `overlayColor` alpha channel is what controls blur visibility:
 
| `overlayColor`  | Result                                      |
|-----------------|---------------------------------------------|
| `#00000000`     | Fully transparent → pure blur shows through |
| `#00000080`     | Semi-transparent black tint over blur       |
| `#FFFFFFFF`     | Fully opaque white → blur completely hidden |
| `#FFFFFF30`     | Frosted glass / white tint effect           |
 
**This works on both iOS and Android.** Not just Android like other libraries.
 
---
 
## Installation
 
```sh
yarn add react-native-blur-vibe
# or
npm install react-native-blur-vibe
```
 
### iOS
 
```sh
cd ios && pod install
```
 
Minimum iOS version: **13.0**
 
### Android
 
No extra steps needed. Minimum SDK: **21**
 
- API 31+ (Android 12): Uses `RenderEffect` — hardware accelerated
- API 21-30: Uses `RenderScript` — bitmap-based fallback
---
 
## Usage
 
```tsx
import { BlurView } from 'react-native-blur-vibe';
import { StyleSheet, ImageBackground, Text } from 'react-native';
 
export default function App() {
  return (
    <ImageBackground source={require('./background.jpg')} style={styles.image}>
      {/* Pure blur — transparent overlay */}
      <BlurView
        blurAmount={15}
        blurType="systemMaterial"
        overlayColor="#00000000"
        style={styles.blur}
      />
 
      {/* Dark tinted blur — like a modal backdrop */}
      <BlurView
        blurAmount={20}
        overlayColor="#00000060"
        style={styles.blur}
      />
 
      {/* Frosted glass card */}
      <BlurView
        blurAmount={10}
        blurType="systemUltraThinMaterialLight"
        overlayColor="#FFFFFF25"
        style={styles.card}
      >
        <Text style={styles.text}>Frosted Glass Card</Text>
      </BlurView>
    </ImageBackground>
  );
}
 
const styles = StyleSheet.create({
  image: { flex: 1 },
  blur: { ...StyleSheet.absoluteFillObject },
  card: {
    margin: 20,
    padding: 20,
    borderRadius: 16,
  },
  text: { color: 'white', fontSize: 18 },
});
```
 
---
 
## Props
 
| Prop | Type | Default | Platform | Description |
|------|------|---------|----------|-------------|
| `blurAmount` | `number` (0–100) | `10` | iOS, Android | Blur intensity |
| `blurType` | `BlurType` | `'light'` | iOS | Maps to `UIBlurEffectStyle` |
| `overlayColor` | `string` (hex) | `'transparent'` on iOS, `'#00000030'` on Android | **iOS & Android** | Color composited on top of blur. Alpha controls blur visibility |
| `reducedTransparencyFallbackColor` | `string` (hex) | `'#F2F2F2'` | iOS, Android | Shown when blur is unavailable (Reduce Transparency enabled, API < 18) |
| `blurRadius` | `number` (1–8) | `4` | Android | Downscale factor for RenderScript path. Higher = faster, slightly softer |
 
All standard `ViewProps` (style, children, onLayout, etc.) are also supported.
 
---
 
## BlurType values (iOS)
 
```
light · dark · extraLight · regular · prominent
systemUltraThinMaterial · systemThinMaterial · systemMaterial
systemThickMaterial · systemChromeMaterial
systemUltraThinMaterialLight · systemThinMaterialLight · systemMaterialLight
systemThickMaterialLight · systemChromeMaterialLight
systemUltraThinMaterialDark · systemThinMaterialDark · systemMaterialDark
systemThickMaterialDark · systemChromeMaterialDark
```
 
---
 
## Recipes
 
### Modal backdrop (dark blur)
```tsx
<BlurView
  blurAmount={25}
  overlayColor="#00000070"
  style={StyleSheet.absoluteFill}
/>
```
 
### Frosted glass navbar
```tsx
<BlurView
  blurAmount={12}
  blurType="systemChromeMaterial"
  overlayColor="#FFFFFF20"
  style={styles.navbar}
/>
```
 
### Light frosted card
```tsx
<BlurView
  blurAmount={8}
  blurType="systemUltraThinMaterialLight"
  overlayColor="#FFFFFF30"
  style={{ borderRadius: 12, padding: 16 }}
>
  {children}
</BlurView>
```
 
### Completely invisible overlay (just blur, no tint)
```tsx
<BlurView
  blurAmount={15}
  overlayColor="#00000000"
  style={StyleSheet.absoluteFill}
/>
```
 
---
 
## Architecture support
 
| Architecture | iOS | Android |
|---|---|---|
| Old Architecture (JSC/Bridge) | ✅ | ✅ |
| New Architecture (Fabric/JSI) | ✅ | ✅ |
| Expo (bare workflow) | ✅ | ✅ |
| Expo Go | ❌ (native module) | ❌ |
 
---
 
## Android API compatibility
 
| Android API | Blur Method | Notes |
|---|---|---|
| 31+ (Android 12) | `RenderEffect` | Hardware accelerated, best quality |
| 21–30 | `RenderScript` | Bitmap-based, `blurRadius` tunable |
| < 21 | `reducedTransparencyFallbackColor` | Solid color only |
 
---

## Contributing

**See [CONTRIBUTING.md](./CONTRIBUTING.md).**

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT © [Pritam Nanda](https://github.com/I-am-Pritam-20)

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
