// LiquidGlassViewManager.m
// Old Architecture (Paper) prop exports.
// On New Architecture, props are handled by LiquidGlassViewFabric.mm via codegen.

#ifndef RCT_NEW_ARCH_ENABLED

#import <React/RCTViewManager.h>
#import <React/RCTUIManager.h>

RCT_EXTERN_MODULE(LiquidGlassViewManager, RCTViewManager)

// ── Props that map 1:1 to a same-named Swift property ─────────────────────
RCT_EXPORT_VIEW_PROPERTY(refractionAmount, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(blurAmount, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(edgeWidth, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(curvatureBlend, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(dispersion, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(saturationBoost, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(contrastBoost, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(brightnessLift, NSNumber)

// ── tintColor — custom mapping ──────────────────────────────────────────────
//
// The JS-facing prop is "tintColor", but the Swift property is named
// "glassTintColor" (UIView already declares its own `tintColor: UIColor!`,
// of a different type — RCT_EXPORT_VIEW_PROPERTY's automatic KVC binding
// requires an exact name+compatible-type match, so a same-named property
// here would collide with UIView's own). RCT_CUSTOM_VIEW_PROPERTY lets us
// bridge the JS name to the differently-named Swift property explicitly.
RCT_CUSTOM_VIEW_PROPERTY(tintColor, NSString, LiquidGlassView)
{
  view.glassTintColor = json ?: @"#FFFFFF14";
}

#endif // RCT_NEW_ARCH_ENABLED
