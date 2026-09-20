# React Native Blur-Vibe

<a href="https://www.npmjs.com/package/react-native-blur-vibe"><img width="100%" height="35%" alt="github-banner" src="https://github.com/user-attachments/assets/78b2e5ec-5b57-48c0-b984-69cb57cbcf26" /></a>
<br></br>

A modern, actively maintained blur view and liquid glass vibe for React Native. Works on **iOS** and **Android** with both Old (Paper) and New (Fabric) Architecture support.

> The key difference from other blur libraries: `overlayColor` works on **both iOS and Android** — letting you control blur visibility the same way CSS `backdrop-filter` + `background-color` works on the web. <br></br>
 Multiple `BlurView`s on the same screen — including inside `FlatList`/`ScrollView`, and even overlapping/stacked ones — share ONE capture pass per screen, so adding more blur surfaces doesn't cost more. On Android, blur runs on a native, multi-threaded engine for speed. A second component, `LiquidGlassView`, adds real-time optical refraction on Android API 33+, with a blur-based fallback everywhere else.

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

## Components

| Component | What it does |
|---|---|
| [`BlurView`](#blurview) | CSS-style backdrop blur, `overlayColor` tint, progressive blur, noise grain |
| [`LiquidGlassView`](#liquidglassview) | Real-time optical refraction ("liquid glass") on Android API 33+, blur + highlight fallback elsewhere |

---

## Platform matrix

| Feature | iOS 13+ | Android API 31+ | Android API 21–30 |
|---|---|---|---|
| Backdrop blur | ✅ | ✅ | ✅ |
| Overlay tint | ✅ | ✅ | ✅ |
| Progressive blur | ✅ | ✅ | ❌ |
| Noise texture | ✅ | ✅ | ❌ |
| Full RN style props | ✅ | ✅ | ✅ |
| `blurType` | ✅ | ❌ | ❌ |
| `enabled` | ✅ | ✅ | ✅ |
| Split-screen / PiP / Freeform | ✅ | ✅ | ✅ |
| Old Architecture (Paper) | ✅ | ✅ | ✅ |
| New Architecture (Fabric) | ✅ | ✅ | ✅ |
| Many `BlurView`s per screen (FlatList, stacked cards) | ✅ (native) | ✅ shared capture | ✅ shared capture |
| Overlapping / stacked `BlurView`s | ✅ (native) | ✅ | ✅ |
| Native multi-threaded blur engine | — (uses `UIVisualEffectView`) | — (uses `RenderEffect`, GPU) | ✅ |
| `LiquidGlassView` real refraction | ❌ (blur fallback) | ✅ (API 33+ only) | ❌ (blur fallback) |

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

### Installation Requirement:

Minimum iOS version : **iOS 13.0**

Minimum Android SDK version: **API 21** (Android 5.0)


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

# BlurView


## Props

### `blurAmount`

| | |
|---|---|
| Type | `number` |
| Default | `10` |
| Platform | iOS + Android |

Blur intensity from `0` (no blur) to `100` (maximum blur).

| `blurAmount` | CSS equivalent | Visual feel |
|---|---|---|
| `5` | `backdrop-blur-sm` (4px) | Subtle hint |
| `15` | `backdrop-blur` (8px) | Light glass |
| `25` | `backdrop-blur-md` (12px) | Standard card |
| `50` | `backdrop-blur-xl` (24px) | Heavy glass |
| `75` | `backdrop-blur-2xl` | Dense blur |
| `100` | `backdrop-blur-3xl` | Maximum — nearly opaque frosted panel |

Each `BlurView` on screen can use a different `blurAmount`, independent of any others.

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

| Value | Effect |
|---|---|
| `"#00000000"` | Transparent — pure blur, no tint |
| `"#00000040"` | 25% black tint — dark frosted glass |
| `"#FFFFFF30"` | 19% white tint — light frosted glass |
| `"#FF000080"` | 50% red tint |
| `"#000000FF"` | Fully opaque — blur hidden |

Supported formats: `"transparent"`, `"#RGB"`, `"#RRGGBB"`, `"#RRGGBBAA"`

---

### `blurType`

| | |
|---|---|
| Type | `BlurType` |
| Default | `"light"` |
| Platform | **iOS only** — ignored on Android |

Maps to `UIBlurEffect.Style`. Use `overlayColor` to tint on Android.

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

Also available: `Light` and `Dark` suffixed variants (e.g. `"systemMaterialDark"`). See `BlurType` in types.

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

Solid color shown when blur is unavailable (iOS Reduce Transparency enabled, or Android API < 21).

---

### `blurRadius`

| | |
|---|---|
| Type | `number` |
| Default | `4` |
| Platform | Accepted on all platforms, currently a **no-op** |

> **Note:** This prop is kept for backward compatibility but no longer has an effect. Earlier versions used it as a per-view capture-downsample factor; since capture is now shared across every `BlurView` on a screen (see [How blur capture works](#how-blur-capture-works-on-android)), the downsample level is fixed internally to a value tuned for quality and performance, and is no longer configurable per view. Use `blurAmount` to control blur strength.

---

### `enabled`

| | |
|---|---|
| Type | `boolean` |
| Default | `true` |
| Platform | iOS + Android |

Enable or disable the blur effect. When `false`, the view renders transparently. Useful for toggling blur based on scroll position or performance mode. Disabling one `BlurView` has no effect on any others on the same screen — each is independent.

```tsx
<BlurView blurAmount={30} enabled={isScrolling ? false : true} style={StyleSheet.absoluteFill} />
```

---

### `progressiveBlurDirection`

| | |
|---|---|
| Type | `ProgressiveBlurDirection` |
| Default | `"none"` |
| Platform | **iOS + Android API 31+** |

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

Blur intensity at the start of the gradient direction. `1.0` = full blur, `0.0` = no blur.

---

### `progressiveEndIntensity`

| | |
|---|---|
| Type | `number` (0.0–1.0) |
| Default | `0.0` |
| Platform | **iOS + Android API 31+** |

Blur intensity at the end of the gradient direction.

---

### `noiseFactor`

| | |
|---|---|
| Type | `number` (0.0–1.0) |
| Default | `0.08` |
| Platform | **iOS + Android API 31+** |

Noise grain overlay for tactile frosted-glass texture.

| Value | Effect |
|---|---|
| `0` | No noise — clean digital blur |
| `0.08` | Subtle grain (default) |
| `0.15` | Noticeable grain |
| `0.30` | Heavy grain |

Each `BlurView` controls its own grain strength independently, even though the underlying grain texture is shared internally for efficiency.

---

## Style props

`BlurView` accepts **all standard React Native View style props** via `StyleSheet` — including `borderRadius`, `borderColor`, `borderWidth`, `opacity`, `backgroundColor`, `elevation`, `shadowColor`, and all others.

### `borderRadius` via StyleSheet

Use `borderRadius` directly inside `style` — it works exactly like any other RN view:

```tsx
// ✅ Via StyleSheet (recommended)
<BlurView
  blurAmount={30}
  overlayColor="#FFFFFF20"
  style={{
    borderRadius: 20,
    overflow: 'hidden',   // required on iOS for clipping
    ...StyleSheet.absoluteFillObject,
  }}
/>

// ✅ Via StyleSheet.create
const styles = StyleSheet.create({
  blur: {
    borderRadius: 16,
    overflow: 'hidden',
    position: 'absolute',
    top: 0, left: 0, right: 0, bottom: 0,
  },
});

<BlurView blurAmount={25} overlayColor="#00000040" style={styles.blur} />
```

> **Note:** Add `overflow: 'hidden'` when using `borderRadius` on iOS to ensure child content is clipped correctly. On Android this is handled automatically.

### Rounded frosted card

```tsx
import { BlurView } from 'react-native-blur-vibe';
import { StyleSheet, View, Text, ImageBackground } from 'react-native';

function FrostedCard() {
  return (
    <ImageBackground source={require('./bg.jpg')} style={styles.bg}>
      <View style={styles.card}>
        <BlurView
          blurAmount={35}
          overlayColor="#FFFFFF18"
          noiseFactor={0.1}
          style={[StyleSheet.absoluteFill, styles.blur]}
        />
        <Text style={styles.title}>Now Playing</Text>
      </View>
    </ImageBackground>
  );
}

const styles = StyleSheet.create({
  bg: { flex: 1 },
  card: {
    margin: 20,
    borderRadius: 24,
    overflow: 'hidden',   // clips blur to card shape on iOS
    padding: 20,
  },
  blur: {
    borderRadius: 24,     // matches card borderRadius
  },
  title: { color: '#fff', fontSize: 18, fontWeight: '600' },
});
```

### Individual corner radii

```tsx
<BlurView
  blurAmount={25}
  style={{
    borderTopLeftRadius: 0,
    borderTopRightRadius: 0,
    borderBottomLeftRadius: 20,
    borderBottomRightRadius: 20,
    overflow: 'hidden',
  }}
/>
```

### Border with blur

```tsx
<BlurView
  blurAmount={30}
  overlayColor="#FFFFFF10"
  style={{
    borderRadius: 16,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.3)',
    overflow: 'hidden',
  }}
/>
```

---

## Usage examples

### Basic frosted glass card

```tsx
<ImageBackground source={require('./bg.jpg')} style={styles.bg}>
  <View style={styles.card}>
    <BlurView
      blurAmount={30}
      overlayColor="#FFFFFF20"
      noiseFactor={0.1}
      style={[StyleSheet.absoluteFill, { borderRadius: 20 }]}
    />
    <Text style={styles.title}>Hello</Text>
  </View>
</ImageBackground>
```

### Sticky header with progressive blur

```tsx
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
<BlurView
  blurAmount={50}
  overlayColor="#00000040"
  progressiveBlurDirection="bottomToTop"
  progressiveStartIntensity={1}
  progressiveEndIntensity={0}
  style={StyleSheet.absoluteFill}
/>
```

### Music player card — dark frosted glass

```tsx
<BlurView
  blurAmount={60}
  blurType="systemMaterial"
  overlayColor="#00000050"
  noiseFactor={0.12}
  style={[StyleSheet.absoluteFill, { borderRadius: 16 }]}
/>
```

### Toggle blur on scroll

```tsx
const [isScrolling, setIsScrolling] = React.useState(false);

<BlurView
  blurAmount={30}
  enabled={!isScrolling}
  style={StyleSheet.absoluteFill}
/>
```

### Static background blur (best performance)

```tsx
// Capture once, never update — great for album art, splash screens
<BlurView
  blurAmount={50}
  overlayColor="#00000030"
  style={StyleSheet.absoluteFill}
/>
```

### Inside a Modal

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

### Inside FlatList / FlashList

Every card's `BlurView` shares one capture pass per frame — adding more blurred cards to the list does not multiply the cost.

```tsx
<FlatList
  data={items}
  renderItem={({ item }) => (
    <ImageBackground source={{ uri: item.image }} style={styles.card}>
      <BlurView
        blurAmount={20}
        overlayColor="#FFFFFF15"
        style={[StyleSheet.absoluteFill, { borderRadius: 12 }]}
      />
      <Text>{item.title}</Text>
    </ImageBackground>
  )}
/>
```

### Multiple blur surfaces on one screen

```tsx
<View style={{ flex: 1 }}>
  <ScrollView>
    {items.map((item) => (
      <View key={item.id} style={styles.row}>
        <ImageBackground source={{ uri: item.image }} style={styles.rowImage} />
        <BlurView
          blurAmount={20}
          overlayColor="#00000030"
          style={StyleSheet.absoluteFill}
        />
      </View>
    ))}
  </ScrollView>

  {/* Blurred tab bar — a separate BlurView, fully independent settings */}
  <BlurView
    blurAmount={50}
    overlayColor="#0000004D"
    style={styles.tabBar}
  />
</View>
```

### Overlapping / stacked blur surfaces

```tsx
<Modal visible={visible} transparent>
  <BlurView blurAmount={30} overlayColor="#00000060" style={StyleSheet.absoluteFill} />

  <View style={styles.sheet}>
    <BlurView
      blurAmount={20}
      overlayColor="#FFFFFF15"
      style={[StyleSheet.absoluteFill, { borderRadius: 20 }]}
    />
    <Text style={styles.sheetTitle}>Details</Text>
  </View>
</Modal>
```

---

# LiquidGlassView

Real-time optical refraction — a lens-like bulge with chromatic dispersion at a panel's edges and a clear pass-through center, the same visual family as iOS/visionOS "liquid glass." This is a **separate component from `BlurView`**, not a prop on it, because the two techniques are fundamentally different and have different platform support.

| | |
|---|---|
| Android API 33+ | ✅ Real refraction via `RuntimeShader` |
| Android API < 33 | ⚠️ Fallback: blur + a static diagonal highlight + rim stroke |
| iOS | ⚠️ Fallback: blur + a static diagonal highlight + rim stroke |

The fallback is a **visual approximation**, not real refraction — true optical bending requires a runtime shader, which only exists on Android 33+ today. This is an intentional, visible difference by design, not a bug.

## Quick start

```tsx
import { LiquidGlassView } from 'react-native-blur-vibe';
import { StyleSheet, ImageBackground } from 'react-native';

<ImageBackground source={require('./bg.jpg')} style={{ flex: 1 }}>
  <LiquidGlassView
    refractionAmount={45}
    blurAmount={10}
    style={[StyleSheet.absoluteFill, { borderRadius: 24 }]}
  />
</ImageBackground>
```

## Props

### `refractionAmount`

| | |
|---|---|
| Type | `number` (0–100) |
| Default | `40` |
| Platform | **Android API 33+ only** |

How strongly content displaces near the panel's edges. `0` = flat, no bulge. Ignored on API < 33 / iOS, where this component falls back to blur + highlight instead.

### `blurAmount`

| | |
|---|---|
| Type | `number` (0–100) |
| Default | `30` |
| Platform | All |

On API 33+: `0` gives pure clear refraction (no frosting); higher values chain a blur pass before the refraction shader, for a "frosted liquid glass" look. On the fallback path (API < 33 / iOS): this is the **only** effect applied — no refraction happens there at all.

### `edgeWidth`

| | |
|---|---|
| Type | `number` (dp) |
| Default | `24` |
| Platform | **Android API 33+ only** |

How far from the panel's edge refraction begins. Content further than this from any edge is shown clear/undistorted.

### `curvatureBlend`

| | |
|---|---|
| Type | `number` (0.0–1.0) |
| Default | `0.5` |
| Platform | **Android API 33+ only** |

`0` = displacement follows the rounded-rect's own edge normal (a flatter "pane of glass with curved edges" look). `1` = displacement is radial from the panel's center (a more "spherical lens" look).

### `dispersion`

| | |
|---|---|
| Type | `number` (0.0–1.0) |
| Default | `0.35` |
| Platform | **Android API 33+ only** |

Chromatic dispersion strength at the edges — how far apart red/green/blue sample offsets spread, simulating the rainbow fringing real glass edges show. `0` disables dispersion (edges still refract, but stay color-neutral).

### `saturationBoost` / `contrastBoost` / `brightnessLift`

| | | | |
|---|---|---|---|
| Type | `number` | `number` | `number` |
| Default | `1.1` | `1.05` | `0.02` |
| Platform | All (applied in the color-grading step of both the shader and fallback paths) |

Global color-grading multipliers/offset applied across the whole panel.

### `tintColor`

| | |
|---|---|
| Type | `string` |
| Default | `"#FFFFFF14"` |
| Platform | All |

RGBA tint blended over the panel — same format as `BlurView`'s `overlayColor`: `"transparent"`, `"#RGB"`, `"#RRGGBB"`, `"#RRGGBBAA"`.

## Style props

Same as `BlurView` — all standard RN View style props work, including `borderRadius` (drives both the visual clip **and** the refraction shape's corner radii on API 33+, so the "glass" bulges follow the same rounded corners you set in `style`).

```tsx
<LiquidGlassView
  refractionAmount={50}
  blurAmount={15}
  dispersion={0.4}
  style={{
    borderRadius: 28,
    overflow: 'hidden',
    ...StyleSheet.absoluteFillObject,
  }}
/>
```

## Usage examples

### Subtle glass panel, minimal dispersion

```tsx
<LiquidGlassView
  refractionAmount={25}
  blurAmount={0}
  dispersion={0.1}
  style={[StyleSheet.absoluteFill, { borderRadius: 16 }]}
/>
```

### Strong frosted liquid glass card

```tsx
<LiquidGlassView
  refractionAmount={60}
  blurAmount={35}
  dispersion={0.5}
  tintColor="#FFFFFF20"
  style={[StyleSheet.absoluteFill, { borderRadius: 24 }]}
/>
```

### Spherical lens look

```tsx
<LiquidGlassView
  refractionAmount={70}
  curvatureBlend={1}
  edgeWidth={40}
  style={[StyleSheet.absoluteFill, { borderRadius: 32 }]}
/>
```

---

## TypeScript

Full TypeScript support with detailed JSDoc on every prop.

```ts
import type {
  BlurViewProps,
  BlurType,
  ProgressiveBlurDirection,
  LiquidGlassViewProps,
} from 'react-native-blur-vibe';
```

---

## License

MIT © [Pritam Nanda](https://github.com/I-am-Pritam-20)