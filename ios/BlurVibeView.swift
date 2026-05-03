// BlurVibeView.swift
// UIKit wrapper that hosts the SwiftUI blur view via UIHostingController.
// Approach mirrors sbaiahmed1/react-native-blur's AdvancedBlurView.

import SwiftUI
import UIKit

@objc(BlurVibeView)
class BlurVibeView: UIView {

  // MARK: - Private

  private var hostingController: UIHostingController<BlurVibeSwiftUIView>?

  // MARK: - Props

  @objc var blurAmount: NSNumber = 10 { didSet { updateView() } }
  @objc var blurType: NSString = "light" { didSet { updateView() } }

  /// Hex overlay color on top of blur — works on iOS AND Android.
  /// "#00000000" = transparent (pure blur), "#00000080" = tinted blur
  @objc var overlayColor: NSString = "transparent" { didSet { updateView() } }

  @objc var reducedTransparencyFallbackColor: NSString = "#F2F2F2" { didSet { updateView() } }

  /// Android-only downscale factor — accepted here as no-op to avoid prop warning
  @objc var blurRadius: NSNumber = 4

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
    // Defer hosting controller setup until we have a valid frame
    // Prevents issues with initial render in complex layouts (e.g. FlashList)
    if hostingController == nil && bounds.width > 0 && bounds.height > 0 {
      setupHostingController()
    } else {
      hostingController?.view.frame = bounds
    }
  }

  // MARK: - Hosting Controller

  private func setupHostingController() {
    // Remove existing hosting controller cleanly
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

    // Insert at index 0 — stays behind React children
    if !subviews.isEmpty {
      insertSubview(hosting.view, at: 0)
    } else {
      addSubview(hosting.view)
    }

    hostingController = hosting
  }

  private func updateView() {
    if let hosting = hostingController {
      // Update root view without recreating the controller — avoids jank
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
        ?? UIColor(white: 0.95, alpha: 1)
    )
  }

  // MARK: - Blur Style Map

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

  // MARK: - Color Parser
  // Supports: "transparent", named colors, "#RGB", "#RRGGBB", "#RRGGBBAA"

  private func parseColor(_ colorString: String) -> UIColor? {
    let s = colorString.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

    // Named colors
    let namedColors: [String: UIColor] = [
      "transparent": .clear, "clear": .clear,
      "white": .white, "black": .black,
      "red": .red, "green": .green, "blue": .blue,
      "gray": .gray, "grey": .gray,
    ]
    if let named = namedColors[s] { return named }

    // Hex colors
    var hex = colorString.trimmingCharacters(in: .whitespacesAndNewlines)
    guard hex.hasPrefix("#") else { return nil }
    hex.removeFirst()

    // Validate hex chars
    let validHex = CharacterSet(charactersIn: "0123456789ABCDEFabcdef")
    guard hex.unicodeScalars.allSatisfy({ validHex.contains($0) }) else { return nil }

    var rgbValue: UInt64 = 0
    Scanner(string: hex).scanHexInt64(&rgbValue)

    switch hex.count {
    case 3: // #RGB → expand
      let r = (rgbValue & 0xF00) >> 8; let g = (rgbValue & 0x0F0) >> 4; let b = rgbValue & 0x00F
      return UIColor(red: CGFloat(r | (r << 4)) / 255, green: CGFloat(g | (g << 4)) / 255,
                     blue: CGFloat(b | (b << 4)) / 255, alpha: 1)
    case 4: // #RGBA → expand
      let r = (rgbValue & 0xF000) >> 12; let g = (rgbValue & 0x0F00) >> 8
      let b = (rgbValue & 0x00F0) >> 4; let a = rgbValue & 0x000F
      return UIColor(red: CGFloat(r | (r << 4)) / 255, green: CGFloat(g | (g << 4)) / 255,
                     blue: CGFloat(b | (b << 4)) / 255, alpha: CGFloat(a | (a << 4)) / 255)
    case 6: // #RRGGBB
      return UIColor(red: CGFloat((rgbValue & 0xFF0000) >> 16) / 255,
                     green: CGFloat((rgbValue & 0x00FF00) >> 8) / 255,
                     blue: CGFloat(rgbValue & 0x0000FF) / 255, alpha: 1)
    case 8: // #RRGGBBAA
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