//  SummaryGlass.swift
//  NOOP · Summary home — the ONE place that decides between native Liquid Glass and its fallback.
//
//  Native glass needs both the Xcode 26 toolchain (Swift 6.2, whose SDK declares `glassEffect`) and an
//  iOS 26 / macOS 26 runtime. The compiler guard keeps an Xcode 16 build compiling the fallback, so the
//  deployment targets (iOS 17 / macOS 13) are unchanged. Views never call `glassEffect` directly.
//  The screen's backdrop (canvas and wash) lives here too, since it is what the glass sits over.

import SwiftUI
import StrandDesign

extension View {
    /// Interactive circular glass behind a header control; `.ultraThinMaterial` circle otherwise. Every
    /// header control takes the same glass inset (`NoopMetrics.syncIndicatorGlassPadding`) so the row is even.
    @ViewBuilder
    func summaryGlassCircle() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, macOS 26.0, *) {
            self
                .padding(NoopMetrics.syncIndicatorGlassPadding)
                .glassEffect(.regular.interactive(), in: Circle())
        } else {
            self.summaryMaterialCircle()
        }
        #else
        self.summaryMaterialCircle()
        #endif
    }

    /// Groups sibling glass shapes so they blend and morph as one surface on iOS 26 / macOS 26.
    @ViewBuilder
    func summaryGlassGroup(spacing: CGFloat) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, macOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { self }
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// The Summary's backdrop: the grouped canvas with Health's warm → cool wash across the top. It sits
    /// behind the whole screen and runs under the status and navigation bars rather than inside the scroll
    /// content, so the wash reaches the top edge in every appearance (Reduce Transparency included) and no
    /// toolbar background of our own cuts it off.
    func summaryBackdrop() -> some View {
        background {
            ZStack(alignment: .top) {
                StrandPalette.summaryCanvas
                SummaryWash()
            }
            .ignoresSafeArea()
        }
    }

    private func summaryMaterialCircle() -> some View {
        self
            .background(.ultraThinMaterial, in: Circle())
            .overlay(Circle().strokeBorder(StrandPalette.hairline, lineWidth: NoopMetrics.hairlineWidth))
    }
}

// MARK: - The scrolling large title

/// What a page needs to draw its own title in its content and hand over to the bar's small title: the
/// Summary does with Health's title row, a friend's page with the person's name.
extension View {
    /// iOS 26's soft scroll edge under the bar, so the page blurs away beneath the small title as Health's
    /// does. A no-op before iOS 26.
    @ViewBuilder
    func softTopEdge() -> some View {
        #if compiler(>=6.2) && os(iOS)
        if #available(iOS 26.0, *) {
            self.scrollEdgeEffectStyle(.soft, for: .top)
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// Reports whether the scroll view has moved more than `offset` points from its top. iOS 18 API; on
    /// iOS 17 nothing is reported, so the bar keeps no title (the page's own title row still shows).
    @ViewBuilder
    func onScrolledPast(_ offset: CGFloat, action: @escaping (Bool) -> Void) -> some View {
        if #available(iOS 18.0, macOS 15.0, *) {
            self.onScrollGeometryChange(for: Bool.self) { geo in
                geo.contentOffset.y + geo.contentInsets.top > offset
            } action: { _, away in
                action(away)
            }
        } else {
            self
        }
    }
}

/// Health's Summary top: warm on the leading side, violet in the middle, cool on the trailing side, solid
/// under the bars and the title, fading into the canvas by the first cards.
private struct SummaryWash: View {
    var body: some View {
        LinearGradient(
            colors: [StrandPalette.summaryWashWarm, StrandPalette.summaryWashViolet, StrandPalette.summaryWashCool],
            startPoint: .leading, endPoint: .trailing
        )
        .mask {
            LinearGradient(stops: [.init(color: .black, location: 0),
                                   .init(color: .black, location: Self.solidFraction),
                                   .init(color: .clear, location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
        .frame(height: Self.height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    // From the screen's top edge: iPhone's status bar, bar and large title stay solid; the Mac has neither
    // bar nor large title above its content.
    #if os(iOS)
    private static let height: CGFloat = 390
    private static let solidFraction = 0.4
    #else
    private static let height: CGFloat = 240
    private static let solidFraction = 0.0
    #endif
}
