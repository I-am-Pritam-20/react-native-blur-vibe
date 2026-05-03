// BlurEffectView.swift
// UIVisualEffectView with UIViewPropertyAnimator for custom blur intensity.
// This is the ONLY correct way to achieve custom blur radius on iOS.
// UIBlurEffect itself ignores any custom intensity — the animator interpolates it.

import SwiftUI
import UIKit

// MARK: - UIKit blur with custom intensity

class BlurEffectView: UIVisualEffectView {
  private var animator: UIViewPropertyAnimator?
  private var blurStyle: UIBlurEffect.Style = .systemMaterial
  private var intensity: Double = 1.0

  override init(effect: UIVisualEffect?) {
    super.init(effect: effect)
    setupBlur()
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    setupBlur()
  }

  func updateBlur(style: UIBlurEffect.Style, intensity: Double) {
    // Skip expensive animator recreation when nothing changed
    guard style != self.blurStyle || intensity != self.intensity else { return }
    self.blurStyle = style
    self.intensity = intensity
    setupBlur()
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    guard window != nil else { return }
    // UIKit resumes paused CAAnimations when view re-joins a window.
    // Re-pause and re-lock the fraction to prevent blur drifting to full intensity.
    animator?.pauseAnimation()
    animator?.fractionComplete = intensity
  }

  private func setupBlur() {
    if let existing = animator, existing.state == .active {
      existing.stopAnimation(true)
    }
    animator = nil
    effect = nil

    let newAnimator = UIViewPropertyAnimator(duration: 1, curve: .linear)
    newAnimator.addAnimations { [weak self] in
      self?.effect = UIBlurEffect(style: self?.blurStyle ?? .systemMaterial)
    }
    // pausesOnCompletion: keeps animator .active even at fraction 1.0
    // so didMoveToWindow can always safely call pauseAnimation()
    newAnimator.pausesOnCompletion = true
    newAnimator.startAnimation()
    newAnimator.pauseAnimation()
    newAnimator.fractionComplete = intensity
    animator = newAnimator
  }

  deinit {
    if let animator = animator, animator.state == .active {
      animator.stopAnimation(true)
    }
  }
}

// MARK: - SwiftUI wrapper for BlurEffectView

struct BlurVibeEffect: UIViewRepresentable {
  var style: UIBlurEffect.Style = .systemMaterial
  var intensity: Double = 1.0

  func makeUIView(context: Context) -> BlurEffectView {
    let view = BlurEffectView(effect: nil)
    view.updateBlur(style: style, intensity: intensity)
    return view
  }

  func updateUIView(_ uiView: BlurEffectView, context: Context) {
    uiView.updateBlur(style: style, intensity: intensity)
  }
}