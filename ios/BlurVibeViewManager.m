#import <React/RCTViewManager.h>
#import <React/RCTUIManager.h>

RCT_EXTERN_MODULE(BlurVibeViewManager, RCTViewManager)

// ── Core props ─────────────────────────────────────────────────────────────
RCT_EXPORT_VIEW_PROPERTY(blurAmount, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(blurType, NSString)
RCT_EXPORT_VIEW_PROPERTY(overlayColor, NSString)
RCT_EXPORT_VIEW_PROPERTY(reducedTransparencyFallbackColor, NSString)
RCT_EXPORT_VIEW_PROPERTY(blurRadius, NSNumber)       // Android-only, no-op on iOS

// ── Progressive blur props ──────────────────────────────────────────────────
RCT_EXPORT_VIEW_PROPERTY(progressiveBlurDirection, NSString)
RCT_EXPORT_VIEW_PROPERTY(progressiveStartIntensity, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(progressiveEndIntensity, NSNumber)

// ── Noise prop ──────────────────────────────────────────────────────────────
RCT_EXPORT_VIEW_PROPERTY(noiseFactor, NSNumber)