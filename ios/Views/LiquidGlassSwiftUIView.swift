// LiquidGlassSwiftUIView.swift
//
// iOS 26+: uses Apple's real Liquid Glass material (`.glassEffect()`,
// introduced at WWDC 2025) — genuine system-level optical glass, not an
// approximation. Apple's Glass API is deliberately high-level and
// system-controlled: it exposes `.regular`/`.clear`/`.identity` variants,
// `.tint(Color)`, and `.interactive()` — NOT granular physical parameters.
// This means our Android AGSL shader's fine-grained controls
// (refractionAmount, edgeWidth, curvatureBlend, dispersion) have no
// equivalent here and are intentional no-ops on this path — Apple's
// system decides the actual refraction/distortion internally. Only
// `tintColor` and corner radius map directly onto real Glass's exposed
// surface. This mirrors how `blurRadius` is already a documented no-op
// on Android's newer paths elsewhere in this library.
//
// iOS < 26: no Glass API exists yet, so this falls back to the SAME
// composition as LiquidGlassView's Android fallback (API < 33): a blur
// layer + diagonal highlight gradient + rim stroke + tint. On THIS path,
// blurAmount/tintColor/saturationBoost/contrastBoost/brightnessLift all
// meaningfully apply, since it's our own composition, not a system
// material.

import SwiftUI
import UIKit

struct LiquidGlassSwiftUIView: View {
  let blurAmount:      Double     // 0-100, JS-facing
  let tintColor:       UIColor
  let saturationBoost: Double
  let contrastBoost:   Double
  let brightnessLift:  Double
  let cornerRadius:    CGFloat

  private var blurIntensity: Double {
    (blurAmount / 100.0).clamped(to: 0.0...1.0)
  }

  private var swiftUITint: Color {
    Color(tintColor)
  }

  var body: some View {
    if #available(iOS 26.0, *) {
      realGlass
    } else {
      fallbackComposition
    }
  }

  // ── iOS 26+: real system Glass material ───────────────────────────────────

  @available(iOS 26.0, *)
  private var realGlass: some View {
    GlassEffectContainer {
      Color.clear
        .glassEffect(
          .regular.tint(swiftUITint),
          in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        )
    }
  }

  // ── iOS < 26: blur + highlight + rim approximation ────────────────────────
  //
  // Same technique as LiquidGlassView's Android fallback — see that
  // class's doc for the full rationale.

  private var fallbackComposition: some View {
    ZStack {
      BlurVibeEffect(style: .systemMaterial, intensity: blurIntensity)

      LinearGradient(
        gradient: Gradient(colors: [
          Color.white.opacity(0.35),
          Color.white.opacity(0.0)
        ]),
        startPoint: .topLeading,
        endPoint: UnitPoint(x: 0.6, y: 0.6)
      )

      swiftUITint
    }
    .saturation(saturationBoost)
    .contrast(contrastBoost)
    .brightness(brightnessLift)
    .overlay(
      RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        .strokeBorder(Color.white.opacity(0.28), lineWidth: 1)
    )
  }
}

private extension Comparable {
  func clamped(to range: ClosedRange<Self>) -> Self {
    min(max(self, range.lowerBound), range.upperBound)
  }
}
