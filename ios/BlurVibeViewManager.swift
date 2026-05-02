import Foundation

/**
 * BlurVibeViewManager
 *
 * RCTViewManager subclass — registers BlurVibeView with React Native.
 * requiresMainQueueSetup = true because we create UIKit views.
 */
@objc(BlurVibeViewManager)
class BlurVibeViewManager: RCTViewManager {

  override func view() -> UIView! {
    return BlurVibeView()
  }

  override static func requiresMainQueueSetup() -> Bool {
    return true
  }
}