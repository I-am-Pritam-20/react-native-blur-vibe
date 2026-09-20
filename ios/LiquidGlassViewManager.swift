// LiquidGlassViewManager.swift
// Old Architecture (Paper) view manager.
// On New Architecture, LiquidGlassViewFabric.mm handles component registration.

#if !RCT_NEW_ARCH_ENABLED

import Foundation

@objc(LiquidGlassViewManager)
class LiquidGlassViewManager: RCTViewManager {
  override func view() -> UIView! {
    return LiquidGlassView()
  }
  override static func requiresMainQueueSetup() -> Bool {
    return true
  }
}

#endif
