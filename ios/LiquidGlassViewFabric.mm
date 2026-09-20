// LiquidGlassViewFabric.mm
// Fabric (New Architecture) component view for LiquidGlassView.
//
// Same codegen namespace as BlurVibeViewFabric.mm (BlurVibeSpec) — both
// components are exported from this same package, so they share one
// generated spec rather than each having their own.
//
// Architecture routing:
//   New Arch (Fabric) → LiquidGlassViewFabric (this file)
//   Old Arch (Paper)  → LiquidGlassViewManager.m + LiquidGlassView.swift
//
// Both render the same SwiftUI layer (LiquidGlassSwiftUIView) so visual
// output is identical on both architectures.

#ifdef RCT_NEW_ARCH_ENABLED

#import <React/RCTViewComponentView.h>
#import <UIKit/UIKit.h>
#import <objc/runtime.h>

// Generated codegen headers (produced by `pod install` / yarn codegen)
#import <react/renderer/components/BlurVibeSpec/ComponentDescriptors.h>
#import <react/renderer/components/BlurVibeSpec/EventEmitters.h>
#import <react/renderer/components/BlurVibeSpec/Props.h>
#import <react/renderer/components/BlurVibeSpec/RCTComponentViewHelpers.h>
#import "RCTFabricComponentsPlugins.h"

// Import Swift types via the generated module header
#import "react_native_blur_vibe-Swift.h"

using namespace facebook::react;

@interface LiquidGlassViewFabric () <RCTLiquidGlassViewViewProtocol>
@end

@implementation LiquidGlassViewFabric {
  // The UIKit view that hosts the SwiftUI liquid-glass layer.
  // LiquidGlassView is defined in LiquidGlassView.swift — reused across
  // both architectures.
  LiquidGlassView *_glassView;
}

// ── Fabric component descriptor ────────────────────────────────────────────

+ (ComponentDescriptorProvider)componentDescriptorProvider {
  return concreteComponentDescriptorProvider<LiquidGlassViewComponentDescriptor>();
}

// ── Init ───────────────────────────────────────────────────────────────────

- (instancetype)initWithFrame:(CGRect)frame {
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const LiquidGlassViewProps>();
    _props = defaultProps;

    _glassView = [[LiquidGlassView alloc] initWithFrame:CGRectZero];
    _glassView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;

    self.contentView = _glassView;
    self.backgroundColor = UIColor.clearColor;
  }
  return self;
}

// ── Layout ─────────────────────────────────────────────────────────────────

- (void)layoutSubviews {
  [super layoutSubviews];
  _glassView.frame = self.bounds;
}

// ── Props update (called by Fabric on prop changes) ────────────────────────

- (void)updateProps:(Props::Shared const &)props
          oldProps:(Props::Shared const &)oldProps {

  const auto &p = *std::static_pointer_cast<const LiquidGlassViewProps>(props);

  _glassView.refractionAmount = @(p.refractionAmount);
  _glassView.blurAmount       = @(p.blurAmount);
  _glassView.edgeWidth        = @(p.edgeWidth);
  _glassView.curvatureBlend   = @(p.curvatureBlend);
  _glassView.dispersion       = @(p.dispersion);
  _glassView.saturationBoost  = @(p.saturationBoost);
  _glassView.contrastBoost    = @(p.contrastBoost);
  _glassView.brightnessLift   = @(p.brightnessLift);

  // JS prop "tintColor" → Swift property "glassTintColor" (UIView already
  // declares its own tintColor of a different type — see LiquidGlassView.swift).
  _glassView.glassTintColor = [NSString stringWithUTF8String:p.tintColor.c_str()];

  [super updateProps:props oldProps:oldProps];
}

// ── Fabric component registration ──────────────────────────────────────────

Class<RCTComponentViewProtocol> LiquidGlassViewFabricCls(void) {
  return LiquidGlassViewFabric.class;
}

@end

#endif // RCT_NEW_ARCH_ENABLED
