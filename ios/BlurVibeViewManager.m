#import <React/RCTViewManager.h>
#import <React/RCTUIManager.h>

RCT_EXTERN_MODULE(BlurVibeViewManager, RCTViewManager)

RCT_EXPORT_VIEW_PROPERTY(blurAmount, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(blurType, NSString)
RCT_EXPORT_VIEW_PROPERTY(overlayColor, NSString)
RCT_EXPORT_VIEW_PROPERTY(reducedTransparencyFallbackColor, NSString)