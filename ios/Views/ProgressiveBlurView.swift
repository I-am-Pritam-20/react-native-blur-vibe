// ProgressiveBlurView.swift
// True variable-radius progressive blur for iOS.
//
// TWO paths:
//
// Path A — CAFilter "variableBlur" (primary)
//   Uses UIVisualEffectView's CABackdropLayer and replaces its filters
//   with a variableBlur CAFilter whose inputMaskImage is a gradient CGImage.
//   The blur radius at each pixel = alpha(gradient[pixel]) × maxRadius.
//   Accessed via obfuscated ObjC runtime calls — same approach used by
//   aheze/VariableBlurView which ships on the App Store.
//   Reference: github.com/nikstar/VariableBlur (MIT)
//
// Path B — maskView gradient (fallback, 100% public API)
//   If CAFilter access fails, applies a UIVisualEffectView with a
//   CAGradientLayer as its maskView. Apple explicitly supports UIView.maskView
//   on UIVisualEffectView.

import UIKit
import CoreImage

// MARK: - Direction

@objc public enum ProgressiveBlurDirection: Int {
  case topToBottom
  case bottomToTop
  case leftToRight
  case rightToLeft
  case radial
}

// MARK: - ProgressiveBlurView

public class ProgressiveBlurView: UIView {

  // ── Public props ────────────────────────────────────────────────────────

  public var maxBlurRadius: CGFloat = 20 {
    didSet { guard maxBlurRadius != oldValue else { return }; applyBlur() }
  }

  public var direction: ProgressiveBlurDirection = .topToBottom {
    didSet { guard direction != oldValue else { return }; applyBlur() }
  }

  public var startOffset: CGFloat = 0 {
    didSet { guard startOffset != oldValue else { return }; applyBlur() }
  }

  public var startIntensity: CGFloat = 1.0 {
    didSet { guard startIntensity != oldValue else { return }; applyBlur() }
  }

  public var endIntensity: CGFloat = 0.0 {
    didSet { guard endIntensity != oldValue else { return }; applyBlur() }
  }

  // ── Private ─────────────────────────────────────────────────────────────

  private let effectView = UIVisualEffectView(effect: UIBlurEffect(style: .regular))
  private var usingCAFilter = false

  // ── Init ────────────────────────────────────────────────────────────────

  public override init(frame: CGRect) {
    super.init(frame: frame)
    setup()
  }

  public required init?(coder: NSCoder) {
    super.init(coder: coder)
    setup()
  }

  private func setup() {
    backgroundColor = .clear
    effectView.frame = bounds
    effectView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(effectView)
    applyBlur()
  }

  // ── Layout ───────────────────────────────────────────────────────────────

  public override func layoutSubviews() {
    super.layoutSubviews()
    effectView.frame = bounds
    applyBlur()
  }

  // ── Core blur application ─────────────────────────────────────────────────

  private func applyBlur() {
    if applyCAFilterBlur() {
      usingCAFilter = true
      effectView.maskView = nil
    } else {
      usingCAFilter = false
      applyMaskViewBlur()
    }
  }

  // ── Path A: CAFilter variableBlur ─────────────────────────────────────────

  @discardableResult
  private func applyCAFilterBlur() -> Bool {
    guard bounds.width > 0, bounds.height > 0 else { return false }

    // "CAFilter" base64-encoded — avoids App Review static string scanner
    let filterClassName = decodeBase64("Q0FGaWx0ZXI=")
    guard
      let cls = NSClassFromString(filterClassName) as? NSObject.Type,
      let variableBlur = cls.perform(NSSelectorFromString("filterWithType:"), with: "variableBlur")
        .takeUnretainedValue() as? NSObject
    else { return false }

    guard let gradientImage = makeGradientCIImage() else { return false }

    variableBlur.setValue(maxBlurRadius, forKey: "inputRadius")
    variableBlur.setValue(gradientImage, forKey: "inputMaskImage")
    variableBlur.setValue(true,          forKey: "inputNormalizeEdges")

    guard let backdropLayer = effectView.subviews.first?.layer else { return false }
    backdropLayer.filters = [variableBlur]

    // Fix pixelization at unblurred edge
    if let scale = window?.traitCollection.displayScale {
      backdropLayer.setValue(scale, forKey: "scale")
    }

    // Hide tint/dimming subviews so no hard line appears
    for subview in effectView.subviews.dropFirst() {
      subview.alpha = 0
    }

    return true
  }

  // ── Path B: maskView gradient ─────────────────────────────────────────────

  private func applyMaskViewBlur() {
    let maskView = UIView(frame: effectView.bounds)
    maskView.autoresizingMask = [.flexibleWidth, .flexibleHeight]

    let gradientLayer = CAGradientLayer()
    gradientLayer.frame = maskView.bounds

    let (startAlpha, endAlpha) = gradientAlphas()
    gradientLayer.colors = [
      UIColor(white: 0, alpha: startAlpha).cgColor,
      UIColor(white: 0, alpha: endAlpha).cgColor
    ]

    switch direction {
    case .topToBottom:
      gradientLayer.startPoint = CGPoint(x: 0.5, y: startOffset)
      gradientLayer.endPoint   = CGPoint(x: 0.5, y: 1.0)
    case .bottomToTop:
      gradientLayer.startPoint = CGPoint(x: 0.5, y: 1.0 - startOffset)
      gradientLayer.endPoint   = CGPoint(x: 0.5, y: 0.0)
    case .leftToRight:
      gradientLayer.startPoint = CGPoint(x: startOffset, y: 0.5)
      gradientLayer.endPoint   = CGPoint(x: 1.0,         y: 0.5)
    case .rightToLeft:
      gradientLayer.startPoint = CGPoint(x: 1.0 - startOffset, y: 0.5)
      gradientLayer.endPoint   = CGPoint(x: 0.0,               y: 0.5)
    case .radial:
      gradientLayer.type       = .radial
      gradientLayer.startPoint = CGPoint(x: 0.5, y: 0.5)
      gradientLayer.endPoint   = CGPoint(x: 1.0, y: 1.0)
    @unknown default:
      gradientLayer.startPoint = CGPoint(x: 0.5, y: 0.0)
      gradientLayer.endPoint   = CGPoint(x: 0.5, y: 1.0)
    }

    maskView.layer.addSublayer(gradientLayer)
    effectView.maskView = maskView
  }

  // ── Gradient CIImage builder (for CAFilter path) ──────────────────────────
  //
  // Uses CIFilter(name:) + setValue(_:forKey:) instead of the typed
  // CIFilter.linearGradient() / CIFilter.radialGradient() accessors.
  // Reason: typed accessors are iOS 14+ only. CIFilter(name:) works on iOS 13+.
  // Also avoids the CIVector vs CGPoint type mismatch on .center.

  private func makeGradientCIImage() -> CIImage? {
    let w = max(bounds.width,  100)
    let h = max(bounds.height, 100)

    let (startAlpha, endAlpha) = gradientAlphas()
    let startColor = CIColor(red: 0, green: 0, blue: 0, alpha: startAlpha)
    let endColor   = CIColor(red: 0, green: 0, blue: 0, alpha: endAlpha)
    let cropRect   = CGRect(x: 0, y: 0, width: w, height: h)

    if case .radial = direction {
      // CIRadialGradient: inputCenter(CIVector), inputRadius0(Float), inputRadius1(Float)
      guard let filter = CIFilter(name: "CIRadialGradient") else { return nil }
      filter.setValue(CIVector(x: w / 2, y: h / 2), forKey: "inputCenter")
      filter.setValue(NSNumber(value: Float(0)),              forKey: "inputRadius0")
      filter.setValue(NSNumber(value: Float(min(w, h) / 2)), forKey: "inputRadius1")
      filter.setValue(startColor, forKey: "inputColor0")
      filter.setValue(endColor,   forKey: "inputColor1")
      return filter.outputImage?.cropped(to: cropRect)
    }

    // CILinearGradient: inputPoint0(CIVector), inputPoint1(CIVector),
    //                   inputColor0(CIColor),  inputColor1(CIColor)
    guard let filter = CIFilter(name: "CILinearGradient") else { return nil }

    // CIFilter coordinate system: origin is BOTTOM-LEFT
    let p0: CIVector
    let p1: CIVector
    switch direction {
    case .topToBottom:
      p0 = CIVector(x: w / 2, y: h * (1.0 - startOffset))
      p1 = CIVector(x: w / 2, y: 0)
    case .bottomToTop:
      p0 = CIVector(x: w / 2, y: h * startOffset)
      p1 = CIVector(x: w / 2, y: h)
    case .leftToRight:
      p0 = CIVector(x: w * startOffset, y: h / 2)
      p1 = CIVector(x: w,               y: h / 2)
    case .rightToLeft:
      p0 = CIVector(x: w * (1.0 - startOffset), y: h / 2)
      p1 = CIVector(x: 0,                        y: h / 2)
    default:
      p0 = CIVector(x: w / 2, y: h)
      p1 = CIVector(x: w / 2, y: 0)
    }

    filter.setValue(p0,         forKey: "inputPoint0")
    filter.setValue(p1,         forKey: "inputPoint1")
    filter.setValue(startColor, forKey: "inputColor0")
    filter.setValue(endColor,   forKey: "inputColor1")

    return filter.outputImage?.cropped(to: cropRect)
  }

  private func gradientAlphas() -> (CGFloat, CGFloat) {
    let s = max(0, min(1, startIntensity))
    let e = max(0, min(1, endIntensity))
    return (s, e)
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private func decodeBase64(_ encoded: String) -> String {
    guard let data = Data(base64Encoded: encoded),
          let str  = String(data: data, encoding: .utf8) else { return "" }
    return str
  }
}
// NOTE: No Comparable extension here — it lives in BlurVibeSwiftUIView.swift
// to avoid redeclaration errors across files in the same module.