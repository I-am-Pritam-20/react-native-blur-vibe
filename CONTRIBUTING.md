# Contributing to react-native-blur-vibe

First off, thanks for taking the time to contribute! 🎉

This document explains how to get the project running locally, how to submit changes, and what the review process looks like.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
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

This project follows a standard code of conduct — be respectful, be constructive, and assume good intent. Harassment of any kind will not be tolerated.

---

## Getting Started

### Prerequisites

Make sure you have the following installed:

- **Node.js** >= 18.0.0
- **Yarn** (this project uses Yarn workspaces)
- **Android Studio** with Android SDK (for Android development)
- **Xcode** >= 16.1 (for iOS development, macOS only)
- **CocoaPods** (for iOS development)

### Setup

```sh
# 1. Fork the repo on GitHub, then clone your fork
git clone https://github.com/I-am-Pritam-20/react-native-blur-vibe.git
cd react-native-blur-vibe

# 2. Install dependencies
yarn install

# 3. Install iOS pods (macOS only)
cd example/ios && pod install && cd ../..
```

---

## Project Structure

```
react-native-blur-vibe/
├── src/                        # TypeScript source — edit this
│   ├── index.ts                # Public exports
│   ├── types.ts                # Props and type definitions
│   ├── BlurView.tsx            # Main React component
│   └── BlurVibeViewNativeComponent.ts  # Codegen spec (New Architecture)
│
├── ios/                        # iOS native code (Swift)
│   ├── BlurVibeView.swift      # UIVisualEffectView + overlayColor logic
│   ├── BlurVibeViewManager.swift
│   ├── BlurVibeViewManager.m   # ObjC bridge
│   └── BlurVibeView.m
│
├── android/                    # Android native code (Kotlin)
│   └── src/main/java/com/blurvibe/
│       ├── BlurVibeView.kt     # RenderEffect + RenderScript logic
│       ├── BlurVibeViewManager.kt
│       └── BlurVibePackage.kt
│
├── example/                    # Example app for testing
│   ├── src/App.tsx
│   ├── ios/
│   └── android/
│
└── .github/workflows/          # CI/CD — runs on every push
    ├── ci.yml                  # Lint + typecheck + build
    ├── build-android.yml       # Gradle build
    ├── build-ios.yml           # Xcode build
    └── publish.yml             # npm publish on git tag
```

---

## Development Workflow

### Running the example app

The `example/` app is pre-linked to the local library. Any changes you make in `src/`, `ios/`, or `android/` are immediately reflected.

```sh
# Build the TypeScript source first
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

This compiles `src/` into `lib/`. You need to run this whenever you change TypeScript files for the changes to reflect in the example app.

### Rebuilding after native changes

**Android** — the example app rebuilds native code automatically when you run `yarn example android`.

**iOS** — run `pod install` again if you changed the podspec, otherwise a regular build picks up Swift changes:

```sh
cd example/ios && pod install && cd ../..
yarn example ios
```

---

## Making Changes

### TypeScript / React

- Edit files in `src/`
- Run `yarn prepare` to rebuild
- Run `yarn typecheck` to verify types
- Run `yarn lint` to check code style

### iOS (Swift)

- Edit files in `ios/`
- The key file is `BlurVibeView.swift` — this contains all blur and overlay logic
- Test via `yarn example ios` on macOS

### Android (Kotlin)

- Edit files in `android/src/main/java/com/blurvibe/`
- The key file is `BlurVibeView.kt` — handles both `RenderEffect` (API 31+) and `RenderScript` (API 24-30)
- Test via `yarn example android`

---

## Testing

### Automated (GitHub Actions)

Every push and pull request automatically runs:

- **Lint** — ESLint checks
- **Typecheck** — TypeScript compilation
- **Android build** — full Gradle debug build on `ubuntu-latest`
- **iOS build** — full Xcode build on `macos-15`

You do not need to run these locally — just push and check the Actions tab on GitHub.

### Manual testing checklist

When submitting a PR that touches native code, please test and confirm the following if you have the environment available:

**Android:**
- [ ] Blur renders visually on API 31+ device/emulator (RenderEffect path)
- [ ] Blur renders visually on API 24-30 emulator (RenderScript path)
- [ ] `overlayColor` with alpha shows tint over blur
- [ ] `overlayColor: "#00000000"` shows pure blur with no tint
- [ ] Children render on top of the blur layer

**iOS:**
- [ ] Blur renders visually on device/simulator
- [ ] `blurType` variants all work (light, dark, systemMaterial, etc.)
- [ ] `overlayColor` with alpha shows tint over blur
- [ ] Reduce Transparency accessibility setting triggers fallback color
- [ ] Children render on top of the blur layer

If you don't have both environments, just note in your PR which platforms you tested on. Other contributors or maintainers will cover the rest.

---

## Submitting a Pull Request

1. **Fork** the repo and create a branch from `main`:
   ```sh
   git checkout -b fix/android-blur-api-28
   ```

2. **Make your changes** following the guidelines above

3. **Run checks locally:**
   ```sh
   yarn lint
   yarn typecheck
   yarn prepare
   ```

4. **Commit** using the convention below

5. **Push** to your fork:
   ```sh
   git push origin fix/android-blur-api-28
   ```

6. **Open a Pull Request** against `main` on this repo

7. **Fill in the PR template** — describe what changed and why, and note which platforms you tested on

8. **Wait for CI** — all 3 workflows must be green before merge

9. **Address review feedback** if any

---

## Reporting Bugs

Open an issue using the **Bug Report** template. Please include:

- React Native version
- Platform (iOS / Android) and OS version
- Device or emulator details
- Minimal code to reproduce
- What you expected vs what happened
- Logs or screenshots if available

The more detail you provide, the faster it gets fixed.

---

## Requesting Features

Open an issue using the **Feature Request** template. Describe:

- What you want to achieve
- Why the current API doesn't cover it
- Any API design ideas you have in mind

---

## Commit Convention

This project uses conventional commits:

```
feat:     new feature
fix:      bug fix
docs:     documentation only
style:    formatting, no logic change
refactor: code change that is not a fix or feature
perf:     performance improvement
test:     adding or fixing tests
chore:    build process, tooling, dependencies
```

Examples:

```sh
git commit -m "feat: add vibrancy support for iOS"
git commit -m "fix: android blur not updating on layout change"
git commit -m "docs: add overlayColor examples to README"
git commit -m "chore: bump react-native peer dep to 0.76"
```

---

## Questions?

Open an issue or start a GitHub Discussion. We're happy to help. 🫡