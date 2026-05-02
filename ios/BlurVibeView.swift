import UIKit

@objc(BlurVibeView)
class BlurVibeView: UIView {

  // MARK: - Private Views
  private var blurEffectView: UIVisualEffectView?
  private let overlayView = UIView()

  // MARK: - Properties

  @objc var blurAmount: NSNumber = 10 { didSet { updateBlur() } }
  @objc var blurType: NSString = "light" { didSet { updateBlur() } }

  /// Overlay color ON TOP of blur layer — works on iOS AND Android.
  /// Alpha channel controls visibility, just like CSS:
  ///   backdrop-filter: blur(Xpx) + background-color: overlayColor
  /// "#00000000" = pure blur, "#00000080" = tinted blur, "#000000FF" = hidden blur
  @objc var overlayColor: NSString = "transparent" { didSet { updateOverlay() } }
  @objc var reducedTransparencyFallbackColor: NSString = "#F2F2F2" { didSet { updateBlur() } }

  // MARK: - Init
  override init(frame: CGRect) { super.init(frame: frame); commonInit() }
  required init?(coder: NSCoder) { super.init(coder: coder); commonInit() }

  private func commonInit() {
    backgroundColor = .clear
    clipsToBounds = true
    overlayView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    overlayView.isUserInteractionEnabled = false
    updateBlur()
  }

  // MARK: - Layout
  override func layoutSubviews() {
    super.layoutSubviews()
    blurEffectView?.frame = bounds
    overlayView.frame = bounds
    bringSubviewToFront(overlayView)
    for subview in subviews where subview !== blurEffectView && subview !== overlayView {
      bringSubviewToFront(subview)
    }
  }

  // MARK: - Blur
  private func updateBlur() {
    if UIAccessibility.isReduceTransparencyEnabled {
      blurEffectView?.removeFromSuperview()
      blurEffectView = nil
      backgroundColor = parseColor(reducedTransparencyFallbackColor as String) ?? UIColor(white: 0.95, alpha: 1)
      return
    }
    backgroundColor = .clear
    let effect = UIBlurEffect(style: blurEffectStyle(for: blurType as String))
    if let existing = blurEffectView {
      existing.effect = effect
    } else {
      let newBlurView = UIVisualEffectView(effect: effect)
      newBlurView.frame = bounds
      newBlurView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      blurEffectView = newBlurView
      insertSubview(newBlurView, at: 0)
    }
    if overlayView.superview == nil {
      insertSubview(overlayView, aboveSubview: blurEffectView!)
    }
    updateOverlay()
  }

  private func updateOverlay() {
    let colorString = overlayColor as String
    if colorString.lowercased() == "transparent" {
      overlayView.backgroundColor = .clear
    } else {
      overlayView.backgroundColor = parseColor(colorString) ?? .clear
    }
  }

  // MARK: - Blur Style Map
  private func blurEffectStyle(for type: String) -> UIBlurEffect.Style {
    switch type {
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

  // MARK: - Color Parser — supports #RGB, #RRGGBB, #RRGGBBAA
  private func parseColor(_ colorString: String) -> UIColor? {
    var hex = colorString.trimmingCharacters(in: .whitespacesAndNewlines)
    guard hex.hasPrefix("#") else { return nil }
    hex.removeFirst()
    var rgbValue: UInt64 = 0
    Scanner(string: hex).scanHexInt64(&rgbValue)
    switch hex.count {
    case 3:
      return UIColor(
        red: CGFloat((rgbValue & 0xF00) >> 8) / 15,
        green: CGFloat((rgbValue & 0x0F0) >> 4) / 15,
        blue: CGFloat(rgbValue & 0x00F) / 15, alpha: 1)
    case 6:
      return UIColor(
        red: CGFloat((rgbValue & 0xFF0000) >> 16) / 255,
        green: CGFloat((rgbValue & 0x00FF00) >> 8) / 255,
        blue: CGFloat(rgbValue & 0x0000FF) / 255, alpha: 1)
    case 8: // #RRGGBBAA
      return UIColor(
        red: CGFloat((rgbValue & 0xFF000000) >> 24) / 255,
        green: CGFloat((rgbValue & 0x00FF0000) >> 16) / 255,
        blue: CGFloat((rgbValue & 0x0000FF00) >> 8) / 255,
        alpha: CGFloat(rgbValue & 0x000000FF) / 255)
    default: return nil
    }
  }
}