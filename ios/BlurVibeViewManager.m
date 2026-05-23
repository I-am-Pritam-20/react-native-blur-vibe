// BlurVibeViewManager.m
// Old Architecture (Paper) prop exports.
// On New Architecture, props are handled by BlurVibeViewFabric.mm via codegen.

#ifndef RCT_NEW_ARCH_ENABLED

#import <React/RCTViewManager.h>
#import <React/RCTUIManager.h>

RCT_EXTERN_MODULE(BlurVibeViewManager, RCTViewManager)

// ── Core props ─────────────────────────────────────────────────────────────
RCT_EXPORT_VIEW_PROPERTY(blurAmount, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(blurType, NSString)
RCT_EXPORT_VIEW_PROPERTY(overlayColor, NSString)
RCT_EXPORT_VIEW_PROPERTY(reducedTransparencyFallbackColor, NSString)
RCT_EXPORT_VIEW_PROPERTY(blurRadius, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(enabled, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(autoUpdate, NSNumber)

// ── Progressive blur props ──────────────────────────────────────────────────
RCT_EXPORT_VIEW_PROPERTY(progressiveBlurDirection, NSString)
RCT_EXPORT_VIEW_PROPERTY(progressiveStartIntensity, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(progressiveEndIntensity, NSNumber)

// ── Noise prop ──────────────────────────────────────────────────────────────
RCT_EXPORT_VIEW_PROPERTY(noiseFactor, NSNumber)

#endif // RCT_NEW_ARCH_ENABLED