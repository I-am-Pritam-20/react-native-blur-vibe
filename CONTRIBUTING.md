# Contributing to react-native-blur-vibe

First off, thanks for taking the time to contribute! 🎉

This document explains how to get the project running locally, how to submit changes, and what the review process looks like.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [iOS Testers & Contributors Wanted](#ios-testers--contributors-wanted)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [Development Workflow](#development-workflow)
- [Making Changes](#making-changes)
- [Testing](#testing)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Reporting Bugs](#reporting-bugs)
- [Requesting Features](#requesting-features)
- [Commit Convention](#commit-convention)

---

## Code of Conduct

This project follows a standard code of conduct — be respectful, be constructive, and assume good intent. Harassment of any kind will not be tolerated. See [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md) for details.

---

## iOS Testers & Contributors Wanted

**We are actively looking for iOS developers and testers to help validate and improve the iOS implementation.**

The iOS layer is architecturally the most involved part of this library. It uses a UIKit → SwiftUI bridge (`UIHostingController`), a custom `CAFilter variableBlur` path for progressive blur, and a `UIViewPropertyAnimator` technique for correct custom blur intensity — none of which are straightforward to test without a real Mac + Xcode environment.

If you have access to a macOS machine with Xcode 16.1+ and can test on a physical iOS device or simulator, your contributions are especially valuable. Areas where help is most needed:

- **Regression testing** — verifying blur rendering across iOS 16, 17, and 18
- **Progressive blur** — validating `ProgressiveBlurView.swift` on different device sizes and orientations
- **Accessibility** — confirming the Reduce Transparency fallback works correctly across OS versions
- **SwiftUI interop** — catching edge cases in `BlurVibeSwiftUIView.swift` when used inside SwiftUI-hosted RN screens
- **Performance profiling** — identifying overdraw or frame drops in blur-heavy UIs

To get involved, open an issue tagged `ios` or `help wanted`, or comment on an existing one. You are also welcome to open a PR directly — even a partial fix or a test result posted as a comment is a meaningful contribution.

---

## Getting Started

### Prerequisites

Make sure you have the following installed:

- **Node.js** >= 18.0.0
- **Yarn** 4.x (this project uses Yarn workspaces with Yarn Berry)
- **Android Studio** with Android SDK (for Android development)
- **Xcode** >= 16.1 (for iOS development, macOS only)
- **CocoaPods** (for iOS development)
- **Ruby / Bundler** (the example app uses a `Gemfile` to pin the CocoaPods version)

### Setup

```sh
# 1. Fork the repo on GitHub, then clone your fork
git clone https://github.com/I-am-Pritam-20/react-native-blur-vibe.git
cd react-native-blur-vibe

# 2. Install dependencies
yarn install

# 3. Install iOS pods (macOS only)
cd example/ios && bundle exec pod install && cd ../..
```

> **Note:** This project uses Yarn Berry (4.x) with the release committed to `.yarn/releases/`. You do not need to install Yarn globally — just run `yarn` and the pinned version will be used automatically.

---

## Project Structure

```
react-native-blur-vibe/
│
├── src/                              # TypeScript source — edit this
│   ├── index.ts                      # Public exports
│   ├── types.ts                      # Props and type definitions
│   ├── BlurView.tsx                  # Main React component
│   ├── BlurVibeViewNativeComponent.ts # Codegen spec (New Architecture)
│   └── __tests__/
│       └── index.test.tsx
│
├── lib/                              # Compiled output — do not edit
│   ├── commonjs/                     # CJS build
│   ├── module/                       # ESM build
│   └── typescript/                   # Type declarations (.d.ts)
│
├── ios/                              # iOS native code (Swift + ObjC bridge)
│   ├── Views/                        # SwiftUI / UIKit view layer
│   │   ├── BlurEffectView.swift      # UIVisualEffectView + UIViewPropertyAnimator
│   │   ├── BlurVibeSwiftUIView.swift # SwiftUI composition: blur + progressive + overlay + noise
│   │   └── ProgressiveBlurView.swift # Variable-radius progressive blur (CAFilter path)
│   ├── BlurVibeView.swift            # UIKit host — wraps SwiftUI via UIHostingController
│   ├── BlurVibeView.m                # ObjC category — exposes Swift class to RN bridge
│   ├── BlurVibeViewManager.swift     # RCTViewManager subclass
│   ├── BlurVibeViewManager.m         # ObjC bridge for the manager
│   └── react-native-blur-vibe.podspec
│
├── android/                          # Android native code (Kotlin)
│   └── src/main/java/com/blurvibe/
│       ├── BlurVibeView.kt           # API 24–30: QmBlurView (RenderScript-based)
│       ├── BlurVibeViewApi31.kt      # API 31+: RenderEffect + RenderNode
│       ├── BlurVibeViewManager.kt    # ViewGroupManager — dispatches to correct impl
│       └── BlurVibePackage.kt        # ReactPackage registration
│   └── build.gradle
│
├── example/                          # Example app for manual testing
│   ├── src/App.tsx                   # Demo screens
│   ├── ios/                          # iOS example project (Xcode)
│   │   ├── BlurVibeExample/          # App target source
│   │   ├── BlurVibeExample.xcodeproj/
│   │   └── Podfile
│   └── android/                      # Android example project (Gradle)
│
├── .github/
│   ├── actions/setup/action.yml      # Reusable setup action
│   ├── ISSUE_TEMPLATE/               # Bug report and config templates
│   └── workflows/
│       ├── ci.yml                    # Lint + typecheck on every push
│       ├── build-android.yml         # Gradle debug build
│       ├── build-ios.yml             # Xcode build (macos-15 runner)
│       └── publish.yml               # npm publish on git tag
│
├── tsconfig.json                     # Base TypeScript config
├── tsconfig.build.json               # Build-specific overrides
├── babel.config.js
├── turbo.json                        # Turborepo task pipeline
└── package.json
```

---

## Development Workflow

### Running the example app

The `example/` app is pre-linked to the local library via `react-native.config.js`. Any changes you make in `src/`, `ios/`, or `android/` are immediately reflected after the steps below.

```sh
# Build the TypeScript source first (always do this before running the app)
yarn prepare

# Run on Android
yarn example android

# Run on iOS (macOS only)
yarn example ios
```

### Rebuilding after TypeScript changes

```sh
yarn prepare
```

This compiles `src/` into `lib/` (CJS, ESM, and type declarations). Run this whenever you change TypeScript files.

### Rebuilding after native changes

**Android** — the example app rebuilds native code automatically when you run `yarn example android`.

**iOS** — if you changed the podspec or added new files, re-run pod install first:

```sh
cd example/ios && bundle exec pod install && cd ../..
yarn example ios
```

For Swift-only changes (no new files, no podspec change), a regular `yarn example ios` build is sufficient.

---

## Making Changes

### TypeScript / React

- Edit files in `src/`
- Run `yarn prepare` to rebuild
- Run `yarn typecheck` to verify types
- Run `yarn lint` to check code style

### iOS (Swift)

The iOS implementation is split across two layers:

- **`ios/Views/`** — the visual layer. `BlurVibeSwiftUIView.swift` composes blur, progressive blur, overlay tint, and noise. `BlurEffectView.swift` handles `UIVisualEffectView` intensity via `UIViewPropertyAnimator`. `ProgressiveBlurView.swift` implements variable-radius blur using a private `CAFilter`.
- **`ios/BlurVibeView.swift`** — the UIKit host. Bridges the SwiftUI view into the RN view hierarchy via `UIHostingController`.
- **`ios/BlurVibeViewManager.swift` + `.m`** — the React Native bridge. Exposes props to JS.

Test via `yarn example ios` on macOS. See [iOS Testers & Contributors Wanted](#ios-testers--contributors-wanted) if you want to help with iOS coverage.

### Android (Kotlin)

The Android implementation uses two separate backends selected at runtime:

- **`BlurVibeViewApi31.kt`** — API 31+ path using `RenderEffect` and `RenderNode` for full-resolution GPU blur.
- **`BlurVibeView.kt`** — API 24–30 path using the QmBlurView library (`RenderScript`-based).
- **`BlurVibeViewManager.kt`** — `ViewGroupManager<ViewGroup>` that instantiates the correct backend and dispatches `@ReactProp` calls to it.

Test via `yarn example android`.

---

## Testing

### Automated (GitHub Actions)

Every push and pull request automatically runs:

- **Lint** — ESLint checks (`ci.yml`)
- **Typecheck** — TypeScript compilation (`ci.yml`)
- **Android build** — full Gradle debug build on `ubuntu-latest` (`build-android.yml`)
- **iOS build** — full Xcode build on `macos-15` (`build-ios.yml`)

You do not need to run these locally — push your branch and check the **Actions** tab on GitHub.

### Manual testing checklist

When submitting a PR that touches native code, please test and confirm the following if you have the environment available:

**Android:**
- [ ] Blur renders correctly on API 31+ device/emulator (`RenderEffect` path)
- [ ] Blur renders correctly on API 24–30 emulator (`RenderScript` / QmBlurView path)
- [ ] `overlayColor` with alpha shows a tint over the blur
- [ ] `overlayColor: "#00000000"` shows pure blur with no tint
- [ ] Children render on top of the blur layer
- [ ] `progressiveBlurDirection` variants work on API 31+

**iOS:**
- [ ] Blur renders correctly on device and simulator
- [ ] `blurType` variants all work (`light`, `dark`, `systemMaterial`, etc.)
- [ ] `blurAmount` produces a visible intensity difference
- [ ] `overlayColor` with alpha shows a tint over the blur
- [ ] `progressiveBlurDirection` variants render the correct gradient mask
- [ ] Reduce Transparency accessibility setting triggers the fallback color
- [ ] Children render on top of the blur layer

If you don't have access to both platforms, note in your PR which platforms you tested on. Other contributors or maintainers will cover the rest — partial testing is still a meaningful contribution.

---

## Submitting a Pull Request

1. **Fork** the repo and create a branch from `main`:
   ```sh
   git checkout -b fix/android-blur-api-28
   ```

2. **Make your changes** following the guidelines above.

3. **Run checks locally:**
   ```sh
   yarn lint
   yarn typecheck
   yarn prepare
   ```

4. **Commit** using the convention below.

5. **Push** to your fork:
   ```sh
   git push origin fix/android-blur-api-28
   ```

6. **Open a Pull Request** against `main` on this repo.

7. **Fill in the PR template** — describe what changed and why, and note which platforms you tested on.

8. **Wait for CI** — all three workflow checks must pass before merge.

9. **Address review feedback** if any.

---

## Reporting Bugs

Open an issue using the **Bug Report** template (`.github/ISSUE_TEMPLATE/bug_report.yml`). Please include:

- React Native version
- Platform (iOS / Android) and OS version
- Device or emulator details
- Minimal reproducible code
- What you expected vs. what happened
- Logs or screenshots if available

The more detail you provide, the faster it gets resolved.

---

## Requesting Features

Open an issue using the **Feature Request** template. Describe:

- What you want to achieve
- Why the current API does not cover it
- Any API design ideas you have in mind

---

## Commit Convention

This project uses [Conventional Commits](https://www.conventionalcommits.org/):

```
feat:      new feature
fix:       bug fix
docs:      documentation only
style:     formatting, no logic change
refactor:  code change that is neither a fix nor a feature
perf:      performance improvement
test:      adding or fixing tests
chore:     build process, tooling, dependencies
```

Examples:

```sh
git commit -m "feat: add vibrancy support for iOS"
git commit -m "fix: android blur not updating on layout change"
git commit -m "docs: update project structure in CONTRIBUTING"
git commit -m "chore: bump react-native peer dep to 0.76"
```

---

## Questions?

Open an issue or start a GitHub Discussion. We're happy to help. 🫡