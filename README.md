# React Native Blur-Vibe

<img width="1500" height="500" alt="github-banner" src="https://github.com/user-attachments/assets/78b2e5ec-5b57-48c0-b984-69cb57cbcf26" />
<br></br>
A modern, actively maintained blur view for React Native. Works on <b>iOS</b> and <b>Android</b> with full New Architecture (Fabric) support.
 
> The key difference from other blur libraries: `overlayColor` works on **both iOS and Android** — letting you control blur visibility the same way CSS `backdrop-filter` + `background-color` works on the web.
 <br></br>


[![npm version](https://img.shields.io/npm/v/react-native-blur-vibe)](https://www.npmjs.com/package/react-native-blur-vibe)
[![Build iOS](https://github.com/I-am-Pritam-20/react-native-blur-vibe/actions/workflows/build-ios.yml/badge.svg)](https://github.com/I-am-Pritam-20/react-native-blur-vibe/actions/workflows/build-ios.yml)
[![Build Android](https://github.com/I-am-Pritam-20/react-native-blur-vibe/actions/workflows/build-android.yml/badge.svg)](https://github.com/I-am-Pritam-20/react-native-blur-vibe/actions/workflows/build-android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
 <div align="left">
  <p>
    <img src="https://img.shields.io/badge/iOS-13%2B-blue?style=flat-square" alt="iOS 13+" />
    <img src="https://img.shields.io/badge/Android-API%2021%2B-green?style=flat-square" alt="Android API 21+" />
  </p>
</div>

---


---

## Platform matrix

| Feature | iOS 13+ | Android API 31+ | Android API 21–30 |
|---|---|---|---|
| Backdrop blur | ✅ | ✅ | ✅ |
| Overlay tint | ✅ | ✅ | ✅ |
| Progressive blur | ✅ | ✅ | ❌ |
| Noise texture | ✅ | ✅ | ❌ |
| `blurType` | ✅ | ❌ | ❌ |
| `blurRadius` downsample | ❌ | ❌ | ✅ |

---

## Installation

```sh
npm install react-native-blur-vibe
# or
yarn add react-native-blur-vibe
```

### iOS

```sh
cd ios && pod install
```

Minimum deployment target: **iOS 13.0**

### Android

Minimum SDK: **API 21** (Android 5.0)

No extra configuration required. The package automatically picks the best blur engine for the running API level.

---

## Quick start

```tsx
import { BlurView } from 'react-native-blur-vibe';
import { StyleSheet, ImageBackground } from 'react-native';

export default function Card() {
  return (
    <ImageBackground source={require('./bg.jpg')} style={styles.container}>
      <BlurView
        blurAmount={25}
        overlayColor="#FFFFFF20"
        style={StyleSheet.absoluteFill}
      />
    </ImageBackground>
  );
}
```

---

## Props

### `blurAmount`

| | |
|---|---|
| Type | `number` |
| Default | `10` |
| Platform | iOS + Android |

Blur intensity from `0` (no blur) to `100` (maximum blur).

Approximate CSS `backdrop-filter` equivalents:

| `blurAmount` | CSS equivalent | Visual feel |
|---|---|---|
| `5` | `backdrop-blur-sm` (4px) | Subtle hint |
| `15` | `backdrop-blur` (8px) | Light glass |
| `25` | `backdrop-blur-md` (12px) | Standard card |
| `50` | `backdrop-blur-xl` (24px) | Heavy glass |
| `75` | `backdrop-blur-2xl` | Dense blur |
| `100` | `backdrop-blur-3xl` | Maximum |

```tsx
<BlurView blurAmount={30} style={StyleSheet.absoluteFill} />
```

---

### `overlayColor`

| | |
|---|---|
| Type | `string` |
| Default | `"transparent"` (iOS) · `"#00000030"` (Android) |
| Platform | iOS + Android |

RGBA color composited **on top of** the blur. Equivalent to:

```css
backdrop-filter: blur(Xpx);
background-color: <overlayColor>;
```

The alpha channel controls how much blur shows through:

| Value | Effect |
|---|---|
| `"#00000000"` | Transparent — pure blur, no tint |
| `"#00000040"` | 25% black tint — dark frosted glass |
| `"#FFFFFF30"` | 19% white tint — light frosted glass |
| `"#FF000080"` | 50% red tint |
| `"#000000FF"` | Fully opaque — blur hidden |

Supported formats: `"transparent"`, `"#RGB"`, `"#RRGGBB"`, `"#RRGGBBAA"`

```tsx
<BlurView blurAmount={20} overlayColor="#FFFFFF25" style={StyleSheet.absoluteFill} />
```

---

### `blurType`

| | |
|---|---|
| Type | `BlurType` |
| Default | `"light"` |
| Platform | **iOS only** — ignored on Android |


**Adaptive styles** (change with light/dark mode — recommended):

| Value | Description |
|---|---|
| `"light"` | Light frosted glass |
| `"dark"` | Dark frosted glass |
| `"extraLight"` | Brighter than light |
| `"regular"` | System default |
| `"prominent"` | Higher contrast |
| `"systemUltraThinMaterial"` | Thinnest, most transparent |
| `"systemThinMaterial"` | Thin material |
| `"systemMaterial"` | Medium — iOS sheet background |
| `"systemThickMaterial"` | Thick material |
| `"systemChromeMaterial"` | For toolbars / nav bars |

Also available: `Light` and `Dark` suffixed variants (e.g. `"systemMaterialDark"`) for explicit mode control. See `BlurType` in types for the full list.

 ❗On Android, use `overlayColor` to control the tint instead.

```tsx
<BlurView blurType="systemMaterial" blurAmount={100} style={StyleSheet.absoluteFill} />
```

---

### `reducedTransparencyFallbackColor`

| | |
|---|---|
| Type | `string` |
| Default | `"#F2F2F2"` |
| Platform | iOS + Android |

Solid color shown when blur is unavailable:
- **iOS**: User enabled *Settings → Accessibility → Display & Text Size → Reduce Transparency*
- **Android**: Device API level < 21

Should provide sufficient contrast without the blur. Use a semi-opaque version of your background color.

```tsx
<BlurView
  blurAmount={20}
  reducedTransparencyFallbackColor="#1C1C1ECC"
  style={StyleSheet.absoluteFill}
/>
```

---

### `blurRadius`

| | |
|---|---|
| Type | `number` (integer 1–8) |
| Default | `4` |
| Platform | **Android API < 31 only** — ignored on API 31+ and iOS |


| Value | Pixels captured | Quality | Speed |
|---|---|---|---|
| `1` | Full res | Sharpest | Slowest |
| `4` | 1/16 (default) | Good | Fast |
| `8` | 1/64 | Softer | Fastest |

On **Android API 31+** the blur runs at full resolution on the GPU — this prop has no effect.

```tsx
<BlurView blurAmount={20} blurRadius={4} style={StyleSheet.absoluteFill} />
```

---

### `progressiveBlurDirection`

| | |
|---|---|
| Type | `ProgressiveBlurDirection` |
| Default | `"none"` |
| Platform | **iOS + Android API 31+** — Android API < 31 shows uniform blur |

Direction the blur intensity fades across the view.

| Value | Blur starts at | Fades towards |
|---|---|---|
| `"none"` | — uniform blur — | — |
| `"topToBottom"` | Top edge | Bottom edge |
| `"bottomToTop"` | Bottom edge | Top edge |
| `"leftToRight"` | Left edge | Right edge |
| `"rightToLeft"` | Right edge | Left edge |
| `"radial"` | Center | Outer edges |


```tsx
// Sticky header — full blur at top, fades to nothing at bottom
<BlurView
  blurAmount={40}
  progressiveBlurDirection="topToBottom"
  progressiveStartIntensity={1}
  progressiveEndIntensity={0}
  style={StyleSheet.absoluteFill}
/>
```

---

### `progressiveStartIntensity`

| | |
|---|---|
| Type | `number` (0.0–1.0) |
| Default | `1.0` |
| Platform | **iOS + Android API 31+** |

Blur intensity at the **start** of the gradient direction.

- `1.0` — full blur at `blurAmount` intensity
- `0.0` — completely transparent / no blur

"Start" depends on `progressiveBlurDirection`:
- `"topToBottom"` → top edge
- `"bottomToTop"` → bottom edge
- `"leftToRight"` → left edge
- `"rightToLeft"` → right edge
- `"radial"` → center

---

### `progressiveEndIntensity`

| | |
|---|---|
| Type | `number` (0.0–1.0) |
| Default | `0.0` |
| Platform | **iOS + Android API 31+** |

Blur intensity at the **end** of the gradient direction.

"End" is the opposite edge/point from `progressiveStartIntensity`.

---

### `noiseFactor`

| | |
|---|---|
| Type | `number` (0.0–1.0) |
| Default | `0.08` |
| Platform | **iOS + Android API 31+** — Android API < 31 silently ignores |

Noise grain overlay strength. Adds a subtle static grain texture on top of the blur, mimicking the micro-texture of real ground glass and making the blur feel more physical and premium.

| Value | Effect |
|---|---|
| `0` | No noise — clean digital blur |
| `0.08` | Subtle, barely perceptible (default) |
| `0.15` | Noticeable grain (Haze library default) |
| `0.30` | Heavy grain |

```tsx
<BlurView blurAmount={50} noiseFactor={0.12} style={StyleSheet.absoluteFill} />
```

---

## Usage examples

### Basic frosted glass card

```tsx
import { BlurView } from 'react-native-blur-vibe';
import { StyleSheet, View, Text, ImageBackground } from 'react-native';

function FrostedCard() {
  return (
    <ImageBackground source={require('./bg.jpg')} style={styles.bg}>
      <View style={styles.card}>
        <BlurView
          blurAmount={30}
          overlayColor="#FFFFFF20"
          noiseFactor={0.1}
          style={StyleSheet.absoluteFill}
        />
        <Text style={styles.title}>Hello</Text>
      </View>
    </ImageBackground>
  );
}
```

### Sticky header with progressive blur

```tsx
// Full blur at top, fades to nothing — used by iOS App Library, Spotlight
<BlurView
  blurAmount={40}
  overlayColor="#00000020"
  progressiveBlurDirection="topToBottom"
  progressiveStartIntensity={1}
  progressiveEndIntensity={0}
  style={[StyleSheet.absoluteFill, { height: 120 }]}
/>
```

### Bottom sheet scrim

```tsx
// No blur at top, full blur at bottom — bottom sheet background
<BlurView
  blurAmount={50}
  overlayColor="#00000040"
  progressiveBlurDirection="bottomToTop"
  progressiveStartIntensity={1}
  progressiveEndIntensity={0}
  style={StyleSheet.absoluteFill}
/>
```

### Music player card (dark frosted glass)

```tsx
<BlurView
  blurAmount={60}
  blurType="systemMaterial"        // iOS: system material
  overlayColor="#00000050"         // Android: dark tint
  noiseFactor={0.12}
  style={StyleSheet.absoluteFill}
/>
```

### Pure blur, no tint

```tsx
// Maximum blur, fully transparent overlay
<BlurView
  blurAmount={50}
  overlayColor="#00000000"
  style={StyleSheet.absoluteFill}
/>
```

### Inside a Modal

Works out of the box — the blur root is automatically scoped to the nearest `Screen` or `ReactRootView`.

```tsx
<Modal visible={visible} transparent>
  <BlurView
    blurAmount={20}
    overlayColor="#00000060"
    style={StyleSheet.absoluteFill}
  />
  <View style={styles.content}>{/* content */}</View>
</Modal>
```

### Inside FlatList / FlashList cards

```tsx
<FlatList
  data={items}
  renderItem={({ item }) => (
    <ImageBackground source={{ uri: item.image }} style={styles.card}>
      <BlurView
        blurAmount={20}
        overlayColor="#FFFFFF15"
        style={StyleSheet.absoluteFill}
      />
      <Text>{item.title}</Text>
    </ImageBackground>
  )}
/>
```

## TypeScript

Full TypeScript support with detailed JSDoc on every prop.

```ts
import type { BlurViewProps, BlurType, ProgressiveBlurDirection } from 'react-native-blur-vibe';
```

---

## License

MIT ©[Pritam Nanda](https://github.com/I-am-Pritam-20)