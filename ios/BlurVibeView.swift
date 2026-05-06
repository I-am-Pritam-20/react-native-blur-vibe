// BlurVibeView.swift
// UIKit wrapper that hosts the SwiftUI blur view via UIHostingController.

import SwiftUI
import UIKit

@objc(BlurVibeView)
class BlurVibeView: UIView {

  // MARK: - Private

  private var hostingController: UIHostingController<BlurVibeSwiftUIView>?

  // MARK: - Props

  @objc var blurAmount: NSNumber = 10 { didSet { updateView() } }
  @objc var blurType: NSString = "light" { didSet { updateView() } }
  @objc var overlayColor: NSString = "transparent" { didSet { updateView() } }
  @objc var reducedTransparencyFallbackColor: NSString = "#F2F2F2" { didSet { updateView() } }
  @objc var blurRadius: NSNumber = 4  // Android-only — no-op on iOS

  // Progressive blur props
  @objc var progressiveBlurDirection: NSString = "none" { didSet { updateView() } }
  @objc var progressiveStartIntensity: NSNumber = 1.0   { didSet { updateView() } }
  @objc var progressiveEndIntensity: NSNumber   = 0.0   { didSet { updateView() } }
  @objc var noiseFactor: NSNumber               = 0.08  { didSet { updateView() } }

  // MARK: - Init

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    backgroundColor = .clear
  }

  // MARK: - Layout

  override func layoutSubviews() {
    super.layoutSubviews()
    if hostingController == nil && bounds.width > 0 && bounds.height > 0 {
      setupHostingController()
    } else {
      hostingController?.view.frame = bounds
    }
  }

  // MARK: - Hosting Controller

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

  private func makeSwiftUIView() -> BlurVibeSwiftUIView {
    return BlurVibeSwiftUIView(
      blurAmount: Double(truncating: blurAmount),
      blurStyle: blurStyleFromString(blurType as String),
      overlayColor: parseColor(overlayColor as String) ?? .clear,
      reducedTransparencyFallbackColor: parseColor(reducedTransparencyFallbackColor as String)
        ?? UIColor(white: 0.95, alpha: 1),
      progressiveDirection: progressiveDirectionFromString(progressiveBlurDirection as String),
      progressiveStartIntensity: CGFloat(truncating: progressiveStartIntensity),
      progressiveEndIntensity: CGFloat(truncating: progressiveEndIntensity),
      noiseFactor: CGFloat(truncating: noiseFactor)
    )
  }

  // MARK: - Prop parsers

  private func progressiveDirectionFromString(_ s: String) -> ProgressiveBlurDirection? {
    switch s {
    case "topToBottom":  return .topToBottom
    case "bottomToTop":  return .bottomToTop
    case "leftToRight":  return .leftToRight
    case "rightToLeft":  return .rightToLeft
    case "radial":       return .radial
    default:             return nil  // nil = no progressive blur, use uniform
    }
  }

  private func blurStyleFromString(_ type: String) -> UIBlurEffect.Style {
    switch type {
    case "xlight":                         return .extraLight
    case "dark":                           return .dark
    case "extraLight":                     return .extraLight
    case "regular":                        return .regular
    case "prominent":                      return .prominent
    case "systemUltraThinMaterial":        return .systemUltraThinMaterial
    case "systemThinMaterial":             return .systemThinMaterial
    case "systemThickMaterial":            return .systemThickMaterial
    case "systemChromeMaterial":           return .systemChromeMaterial
    case "systemUltraThinMaterialLight":   return .systemUltraThinMaterialLight
    case "systemThinMaterialLight":        return .systemThinMaterialLight
    case "systemMaterialLight":            return .systemMaterialLight
    case "systemThickMaterialLight":       return .systemThickMaterialLight
    case "systemChromeMaterialLight":      return .systemChromeMaterialLight
    case "systemUltraThinMaterialDark":    return .systemUltraThinMaterialDark
    case "systemThinMaterialDark":         return .systemThinMaterialDark
    case "systemMaterialDark":             return .systemMaterialDark
    case "systemThickMaterialDark":        return .systemThickMaterialDark
    case "systemChromeMaterialDark":       return .systemChromeMaterialDark
    case "systemMaterial":                 return .systemMaterial
    default:                               return .light
    }
  }

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

  // MARK: - Cleanup

  deinit {
    hostingController?.view.removeFromSuperview()
    hostingController?.removeFromParent()
    hostingController = nil
  }
}