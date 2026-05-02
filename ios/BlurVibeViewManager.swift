import Foundation

@objc(BlurVibeViewManager)
class BlurVibeViewManager: RCTViewManager {

  override func view() -> UIView! {
    return BlurVibeView()
  }

  override static func requiresMainQueueSetup() -> Bool {
    return true
  }

  @objc override func constantsToExport() -> [AnyHashable: Any]! {
    return [:]
  }
}