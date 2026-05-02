#import <React/RCTViewManager.h>
#import <React/RCTUIManager.h>

RCT_EXTERN_MODULE(BlurVibeViewManager, RCTViewManager)

// Float  → NSNumber  matches TS Float 
RCT_EXPORT_VIEW_PROPERTY(blurAmount, NSNumber)

// String → NSString  matches TS string 
RCT_EXPORT_VIEW_PROPERTY(blurType, NSString)

// String → NSString  matches TS string 
RCT_EXPORT_VIEW_PROPERTY(overlayColor, NSString)

// String → NSString  matches TS string 
RCT_EXPORT_VIEW_PROPERTY(reducedTransparencyFallbackColor, NSString)

// Int32  → NSNumber  matches TS Int32  (no-op in Swift)
RCT_EXPORT_VIEW_PROPERTY(blurRadius, NSNumber)