// BlurVibeViewManager.swift
// Old Architecture (Paper) view manager.
// On New Architecture, BlurVibeViewFabric.mm handles component registration.

#if !RCT_NEW_ARCH_ENABLED

import Foundation

@objc(BlurVibeViewManager)
class BlurVibeViewManager: RCTViewManager {
  override func view() -> UIView! {
    return BlurVibeView()
  }
  override static func requiresMainQueueSetup() -> Bool {
    return true
  }
}

#endif