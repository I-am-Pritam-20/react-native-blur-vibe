// LiquidGlassView.swift
// UIKit wrapper that hosts the SwiftUI liquid-glass composition via
// UIHostingController — same pattern as BlurVibeView.swift.

import SwiftUI
import UIKit

@objc(LiquidGlassView)
class LiquidGlassView: UIView {

  private var hostingController: UIHostingController<LiquidGlassSwiftUIView>?

  // Props
  //
  // refractionAmount / edgeWidth / curvatureBlend / dispersion are
  // accepted for cross-platform prop-surface parity with Android, but are
  // no-ops on iOS 26+ (Apple's real Glass API doesn't expose this level
  // of control — see LiquidGlassSwiftUIView's doc) and are also unused on
  // the pre-26 fallback (which uses a fixed highlight/rim composition,
  // not a parametric shader). Kept as properties rather than removed
  // entirely so the same JS-side props work unchanged across platforms.

  @objc var refractionAmount: NSNumber = 40   // no-op on iOS — see above
  @objc var blurAmount: NSNumber = 30 { didSet { updateView() } }
  @objc var edgeWidth: NSNumber = 24          // no-op on iOS — see above
  @objc var curvatureBlend: NSNumber = 0.5    // no-op on iOS — see above
  @objc var dispersion: NSNumber = 0.35       // no-op on iOS — see above
  @objc var saturationBoost: NSNumber = 1.1 { didSet { updateView() } }
  @objc var contrastBoost: NSNumber = 1.05 { didSet { updateView() } }
  @objc var brightnessLift: NSNumber = 0.02 { didSet { updateView() } }
  @objc var glassTintColor: NSString = "#FFFFFF14" { didSet { updateView() } }

  // ── Corner radius ──────────────────────────────────────────────────────────
  //
  // Not a JS prop directly — React Native's normal `style.borderRadius`
  // is applied to this UIView itself (same as BlurVibeView), and we read
  // it back here so the SwiftUI Glass shape's corners match the view's
  // own clip shape. Updated from layoutSubviews/layer changes.

  private var cornerRadius: CGFloat {
    layer.cornerRadius
  }

  // Init

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    backgroundColor = .clear
  }

  // Layout

  override func layoutSubviews() {
    super.layoutSubviews()
    if hostingController == nil && bounds.width > 0 && bounds.height > 0 {
      setupHostingController()
    } else {
      hostingController?.view.frame = bounds
      updateView() // corner radius may have changed via style.borderRadius
    }
  }

  // Hosting Controller

  private func setupHostingController() {
    if let old = hostingController {
      old.view.removeFromSuperview()
      old.removeFromParent()
    }
    hostingController = nil

    let swiftUIView = makeSwiftUIView()
    let hosting = UIHostingController(rootView: swiftUIView)
    hosting.view.backgroundColor = .clear
    hosting.view.frame = bounds
    hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]

    if !subviews.isEmpty {
      insertSubview(hosting.view, at: 0)
    } else {
      addSubview(hosting.view)
    }
    hostingController = hosting
  }

  private func updateView() {
    if let hosting = hostingController {
      hosting.rootView = makeSwiftUIView()
    } else if bounds.width > 0 && bounds.height > 0 {
      setupHostingController()
    }
  }

  private func makeSwiftUIView() -> LiquidGlassSwiftUIView {
    return LiquidGlassSwiftUIView(
      blurAmount: Double(truncating: blurAmount),
      tintColor: parseColor(glassTintColor as String) ?? UIColor.white.withAlphaComponent(0.08),
      saturationBoost: Double(truncating: saturationBoost),
      contrastBoost: Double(truncating: contrastBoost),
      brightnessLift: Double(truncating: brightnessLift),
      cornerRadius: cornerRadius
    )
  }

  // Color parser (same format/logic as BlurVibeView.swift)

  private func parseColor(_ colorString: String) -> UIColor? {
    let s = colorString.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    let namedColors: [String: UIColor] = [
      "transparent": .clear, "clear": .clear,
      "white": .white, "black": .black,
      "red": .red, "green": .green, "blue": .blue,
      "gray": .gray, "grey": .gray,
    ]
    if let named = namedColors[s] { return named }

    var hex = colorString.trimmingCharacters(in: .whitespacesAndNewlines)
    guard hex.hasPrefix("#") else { return nil }
    hex.removeFirst()

    let validHex = CharacterSet(charactersIn: "0123456789ABCDEFabcdef")
    guard hex.unicodeScalars.allSatisfy({ validHex.contains($0) }) else { return nil }

    var rgbValue: UInt64 = 0
    Scanner(string: hex).scanHexInt64(&rgbValue)

    switch hex.count {
    case 3:
      let r = (rgbValue & 0xF00) >> 8; let g = (rgbValue & 0x0F0) >> 4; let b = rgbValue & 0x00F
      return UIColor(red: CGFloat(r | (r << 4)) / 255, green: CGFloat(g | (g << 4)) / 255,
                     blue: CGFloat(b | (b << 4)) / 255, alpha: 1)
    case 4:
      let r = (rgbValue & 0xF000) >> 12; let g = (rgbValue & 0x0F00) >> 8
      let b = (rgbValue & 0x00F0) >> 4;  let a = rgbValue & 0x000F
      return UIColor(red: CGFloat(r | (r << 4)) / 255, green: CGFloat(g | (g << 4)) / 255,
                     blue: CGFloat(b | (b << 4)) / 255, alpha: CGFloat(a | (a << 4)) / 255)
    case 6:
      return UIColor(red: CGFloat((rgbValue & 0xFF0000) >> 16) / 255,
                     green: CGFloat((rgbValue & 0x00FF00) >> 8) / 255,
                     blue: CGFloat(rgbValue & 0x0000FF) / 255, alpha: 1)
    case 8:
      return UIColor(red: CGFloat((rgbValue & 0xFF000000) >> 24) / 255,
                     green: CGFloat((rgbValue & 0x00FF0000) >> 16) / 255,
                     blue: CGFloat((rgbValue & 0x0000FF00) >> 8) / 255,
                     alpha: CGFloat(rgbValue & 0x000000FF) / 255)
    default:
      return nil
    }
  }

  // Cleanup

  deinit {
    hostingController?.view.removeFromSuperview()
    hostingController?.removeFromParent()
    hostingController = nil
  }
}
