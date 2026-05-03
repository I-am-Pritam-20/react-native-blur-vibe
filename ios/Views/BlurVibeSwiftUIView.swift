// BlurVibeSwiftUIView.swift
// SwiftUI view that composes blur effect + overlay color.

import SwiftUI
import UIKit

struct BlurVibeSwiftUIView: View {
  let blurAmount: Double
  let blurStyle: UIBlurEffect.Style
  let overlayColor: UIColor
  let reducedTransparencyFallbackColor: UIColor

  private let isReducedTransparencyEnabled = UIAccessibility.isReduceTransparencyEnabled

  // Map 0–100 blurAmount to 0.0–1.0 animator fraction
  private var blurIntensity: Double {
    (blurAmount / 100.0).clamped(to: 0.0...1.0)
  }

  var body: some View {
    if isReducedTransparencyEnabled {
      // Accessibility: Reduce Transparency is ON — show solid fallback color
      Rectangle()
        .fill(Color(reducedTransparencyFallbackColor))
    } else {
      ZStack {
        // Layer 1: backdrop blur (what's behind this view gets blurred)
        BlurVibeEffect(style: blurStyle, intensity: blurIntensity)

        // Layer 2: overlay color with alpha on top of blur
        // This is our overlayColor prop — same as CSS background-color with alpha
        // "#00000000" = transparent = pure blur shows through
        // "#00000080" = 50% black tint over blur
        // "#000000FF" = fully opaque = blur hidden
        Rectangle()
          .fill(Color(overlayColor))
      }
    }
  }
}

extension Comparable {
  func clamped(to range: ClosedRange<Self>) -> Self {
    min(max(self, range.lowerBound), range.upperBound)
  }
}