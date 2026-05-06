// BlurVibeSwiftUIView.swift
// SwiftUI view that composes blur + progressive blur + overlay + noise.

import SwiftUI
import UIKit

struct BlurVibeSwiftUIView: View {
  let blurAmount:                     Double
  let blurStyle:                      UIBlurEffect.Style
  let overlayColor:                   UIColor
  let reducedTransparencyFallbackColor: UIColor
  let progressiveDirection:           ProgressiveBlurDirection?
  let progressiveStartIntensity:      CGFloat
  let progressiveEndIntensity:        CGFloat
  let noiseFactor:                    CGFloat

  private let isReducedTransparency = UIAccessibility.isReduceTransparencyEnabled

  private var blurIntensity: Double {
    (blurAmount / 100.0).clamped(to: 0.0...1.0)
  }

  var body: some View {
    if isReducedTransparency {
      Rectangle()
        .fill(Color(reducedTransparencyFallbackColor))
    } else {
      ZStack {
        // ── Layer 1: Blur ──────────────────────────────────────────────────
        if let direction = progressiveDirection {
          // Progressive blur — CAFilter variableBlur with gradient mask
          ProgressiveBlurRepresentable(
            maxBlurRadius: CGFloat(blurAmount / 100.0 * 20.0), // map 0-100 → 0-20pt radius
            direction: direction,
            startIntensity: progressiveStartIntensity,
            endIntensity: progressiveEndIntensity
          )
        } else {
          // Uniform blur — UIVisualEffectView with custom intensity
          BlurVibeEffect(style: blurStyle, intensity: blurIntensity)
        }

        // ── Layer 2: Overlay tint ──────────────────────────────────────────
        Rectangle()
          .fill(Color(overlayColor))

        // ── Layer 3: Noise grain (frosted-glass tactility) ─────────────────
        if noiseFactor > 0 {
          NoiseView()
            .opacity(Double(noiseFactor))
            .blendMode(.overlay)
        }
      }
    }
  }
}

// MARK: - ProgressiveBlurRepresentable

/// UIViewRepresentable that wraps ProgressiveBlurView for use in SwiftUI
struct ProgressiveBlurRepresentable: UIViewRepresentable {
  let maxBlurRadius:   CGFloat
  let direction:       ProgressiveBlurDirection
  let startIntensity:  CGFloat
  let endIntensity:    CGFloat

  func makeUIView(context: Context) -> ProgressiveBlurView {
    let view = ProgressiveBlurView()
    configure(view)
    return view
  }

  func updateUIView(_ view: ProgressiveBlurView, context: Context) {
    configure(view)
  }

  private func configure(_ view: ProgressiveBlurView) {
    view.maxBlurRadius    = maxBlurRadius
    view.direction        = direction
    view.startIntensity   = startIntensity
    view.endIntensity     = endIntensity
    view.backgroundColor  = .clear
  }
}

// MARK: - NoiseView

/// Renders a subtle static noise texture for tactile frosted-glass feel.
/// Uses a 64×64 CGImage generated once and tiled via CALayer contentsGravity.
/// Equivalent to Haze's noiseFactor — adds grain that makes blur feel like
/// real ground glass rather than a soft digital filter.
struct NoiseView: UIViewRepresentable {
  func makeUIView(context: Context) -> UIView {
    let view = UIView()
    view.backgroundColor = .clear
    view.layer.contents  = NoiseTextureCache.shared.texture
    view.layer.contentsGravity = .resize
    return view
  }
  func updateUIView(_ view: UIView, context: Context) {}
}

/// Generates and caches the noise texture as a CGImage (once per process lifetime)
private class NoiseTextureCache {
  static let shared = NoiseTextureCache()
  let texture: CGImage?

  private init() {
    let size = 128
    var pixels = [UInt8](repeating: 0, count: size * size * 4)
    // Fixed seed via deterministic sequence — no shimmer on re-render
    var seed: UInt64 = 0xDEADBEEF
    for i in stride(from: 0, to: pixels.count, by: 4) {
      // xorshift64 PRNG — fast, no imports needed
      seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17
      let v = UInt8(seed & 0xFF)
      pixels[i]   = v  // R
      pixels[i+1] = v  // G
      pixels[i+2] = v  // B
      pixels[i+3] = 255
    }
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let ctx = CGContext(
      data: &pixels,
      width: size, height: size,
      bitsPerComponent: 8, bytesPerRow: size * 4,
      space: colorSpace,
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    )
    texture = ctx?.makeImage()
  }
}

// MARK: - Comparable clamp (internal — shared across this module, defined once here)
extension Comparable {
  func clamped(to range: ClosedRange<Self>) -> Self {
    min(max(self, range.lowerBound), range.upperBound)
  }
}