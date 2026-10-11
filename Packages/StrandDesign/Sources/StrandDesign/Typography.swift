import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// MARK: - Strand Typography (§9.2)
//
// Text is SF Pro, as Health sets it; SF Rounded is kept for figures (`rounded`, `number`, `display`), as
// Fitness sets its metrics (Craft-2). Tabular digits keep live metrics stable, and every named style follows
// Dynamic Type. SF Mono remains reserved for logs.
//
// All numeric styles use `.monospacedDigit()` so live values don't reflow.

public enum StrandFont {

    // MARK: Family

    /// The system text style whose Large-size default is `size` points, so a literal size taken from an
    /// Apple spec keeps its exact look at the default setting and still follows Dynamic Type (CR-1).
    /// Sizes that match no style stay fixed; views scale those with `@ScaledMetric`.
    static func textStyle(for size: CGFloat) -> Font.TextStyle? {
        switch size {
        case 11: .caption2
        case 12: .caption
        case 13: .footnote
        case 15: .subheadline
        case 16: .callout
        case 17: .body
        case 20: .title3
        case 22: .title2
        case 28: .title
        case 34: .largeTitle
        default: nil
        }
    }

    private static func roundedSystem(_ size: CGFloat, weight: Font.Weight) -> Font {
        textStyle(for: size).map { .system($0, design: .rounded, weight: weight) }
            ?? .system(size: size, weight: weight, design: .rounded)
    }

    // MARK: Scale (§9.2)

    /// Display 64–80 / Bold — the gauge score number. Helvetica Neue 700 with tight
    /// tracking (≈ -0.04em), tabular digits so a changing value never reflows.
    public static func display(_ size: CGFloat = 72) -> Font {
        roundedSystem(size, weight: .bold).monospacedDigit()
    }

    /// The tight tracking for big display numbers (≈ -0.04em). Apply alongside
    /// `display(_:)` at the use site, e.g. `.tracking(StrandFont.displayTracking(72))`.
    public static func displayTracking(_ size: CGFloat = 72) -> CGFloat {
        -size * 0.04
    }

    /// A Helvetica-Neue numeric style at an arbitrary size/weight — the house
    /// numeral. Tabular so live values align. Use anywhere a score/number is shown.
    public static func rounded(_ size: CGFloat, weight: Font.Weight = .bold) -> Font {
        roundedSystem(size, weight: weight).monospacedDigit()
    }

    /// Title1 28 / Bold. Scales with Dynamic Type.
    public static let title1 = Font.system(.title, weight: .bold)

    /// Title2 22 / Semibold. Scales with Dynamic Type.
    public static let title2 = Font.system(.title2, weight: .semibold)

    /// Headline 17 / Semibold. Scales with Dynamic Type.
    public static let headline = Font.system(.headline, weight: .semibold)

    /// Body 15 / Regular. Scales with Dynamic Type.
    public static let body = Font.system(.body, weight: .regular)

    /// Subhead 13. Scales with Dynamic Type.
    public static let subhead = Font.system(.subheadline, weight: .regular)

    /// Caption 12. Scales with Dynamic Type.
    public static let caption = Font.system(.caption, weight: .regular)

    /// Footnote 11. Scales with Dynamic Type.
    public static let footnote = Font.system(.footnote, weight: .regular)

    /// Overline 11 / Bold, +1.4 tracking (apply `.tracking(1.4)` at use site;
    /// `overlineText(_:)` does it for you). Sparing ALL-CAPS labels. Scales with Dynamic Type.
    ///
    /// Also the face for compact status copy in constrained chrome (the Today header's sync capsule),
    /// used there WITHOUT the tracking — that is sentence case, not an overline, and the letter-spacing
    /// is what makes an overline read as one.
    public static let overline = Font.system(.caption2, weight: .semibold)

    /// `overline` at a custom point size — same Helvetica face, weight and Dynamic-Type scaling
    /// (relativeTo `.caption2`), just smaller. Passing 11 returns exactly `.overline`. Lets a caller
    /// shrink an ALL-CAPS label to fit a small container without losing accessibility text-scaling.
    public static func overlineScaled(_ size: CGFloat) -> Font {
        #if canImport(UIKit)
        let base = UIFont.systemFont(ofSize: size, weight: .semibold)
        let descriptor = base.fontDescriptor.withDesign(.rounded) ?? base.fontDescriptor
        let rounded = UIFont(descriptor: descriptor, size: size)
        return Font(UIFontMetrics(forTextStyle: .caption2).scaledFont(for: rounded))
        #elseif canImport(AppKit)
        let base = NSFont.systemFont(ofSize: size, weight: .semibold)
        guard let descriptor = base.fontDescriptor.withDesign(.rounded),
              let rounded = NSFont(descriptor: descriptor, size: size) else {
            return Font(base)
        }
        return Font(rounded)
        #else
        return roundedSystem(size, weight: .semibold)
        #endif
    }

    /// Mono 13 (SF Mono) — raw / log views. Tabular by nature.
    public static let mono = Font.system(size: 13, weight: .regular, design: .monospaced)

    // MARK: Numeric variants (tabular digits)

    /// A numeric style at an arbitrary size/weight, for live values — Helvetica
    /// Neue, tabular digits. This is the tile/value numeral.
    public static func number(_ size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        roundedSystem(size, weight: weight).monospacedDigit()
    }

    /// Helvetica-Neue body number — for inline live values that should align. Scales with Dynamic
    /// Type alongside its sibling `body`/`caption` labels so a value and its label stay matched.
    public static let bodyNumber = Font.system(.body, design: .rounded, weight: .medium).monospacedDigit()

    /// Helvetica-Neue caption number — for small live values (sparklines, chips). Scales with Dynamic Type.
    public static let captionNumber = Font.system(.caption, design: .rounded, weight: .medium).monospacedDigit()

    /// SF Pro (the default design, not Rounded) at an arbitrary size/weight — the Health-style pages,
    /// which Apple sets in SF Pro. Digits stay proportional, as Health's figures are.
    public static func pro(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        textStyle(for: size).map { .system($0, weight: weight) } ?? .system(size: size, weight: weight)
    }

    /// SF Compact Rounded at a fixed size: the face Fitness sets its Sharing figures in ("108%",
    /// "923/850KCAL"). The system carries it under a design the SDK gives no name; where that design is
    /// refused the figure is drawn in SF Rounded. Digits stay proportional, as Fitness's are. Callers
    /// scale `size` with `@ScaledMetric`.
    public static func compactRounded(_ size: CGFloat, weight: Font.Weight = .medium) -> Font {
        #if canImport(UIKit)
        let base = UIFont.systemFont(ofSize: size, weight: platformWeight(weight))
        guard let descriptor = base.fontDescriptor.withDesign(.init(rawValue: compactRoundedDesign)) else {
            return .system(size: size, weight: weight, design: .rounded)
        }
        return Font(UIFont(descriptor: descriptor, size: size))
        #elseif canImport(AppKit)
        let base = NSFont.systemFont(ofSize: size, weight: platformWeight(weight))
        guard let descriptor = base.fontDescriptor.withDesign(.init(rawValue: compactRoundedDesign)),
              let font = NSFont(descriptor: descriptor, size: size) else {
            return .system(size: size, weight: weight, design: .rounded)
        }
        return Font(font)
        #else
        return .system(size: size, weight: weight, design: .rounded)
        #endif
    }

    private static let compactRoundedDesign = "NSCTFontUIFontDesignCompactRounded"

    #if canImport(UIKit)
    private static func platformWeight(_ weight: Font.Weight) -> UIFont.Weight {
        switch weight {
        case .regular: .regular
        case .semibold: .semibold
        case .bold: .bold
        default: .medium
        }
    }
    #elseif canImport(AppKit)
    private static func platformWeight(_ weight: Font.Weight) -> NSFont.Weight {
        switch weight {
        case .regular: .regular
        case .semibold: .semibold
        case .bold: .bold
        default: .medium
        }
    }
    #endif

    /// Mono at an arbitrary size.
    public static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }

    /// The recommended tracking for overline text (wide ALL-CAPS labels, ≈ 0.13em).
    public static let overlineTracking: CGFloat = 0.45
}

// MARK: - Text helpers

public extension Text {
    /// Style as an overline label: ALL-CAPS, bold, +1.4 tracking, tertiary text.
    func strandOverline() -> some View {
        self.font(StrandFont.overline)
            .tracking(StrandFont.overlineTracking)
            .textCase(.uppercase)
            .foregroundStyle(StrandPalette.textSecondary)
    }
}

public extension View {
    /// Convenience: an overline-styled label string.
    static func strandOverline(_ string: String) -> some View {
        Text(string).strandOverline()
    }
}

#if DEBUG
#Preview("Typography") {
    ScrollView {
        VStack(alignment: .leading, spacing: 18) {
            Text("88").font(StrandFont.display(72)).tracking(StrandFont.displayTracking(72)).foregroundStyle(StrandPalette.textPrimary)
            Text("Title 1 / Bold 28").font(StrandFont.title1).foregroundStyle(StrandPalette.textPrimary)
            Text("Title 2 / Semibold 22").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
            Text("Headline / Semibold 17").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
            Text("Body / Regular 15 — the thread of you, read in full.")
                .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
            Text("Subhead 13").font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
            Text("Caption 12").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            Text("Footnote 11").font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
            Text("Overline").strandOverline()
            Text("0xAA 41 00 1c crc32=f3a1  mono 13").font(StrandFont.mono).foregroundStyle(StrandPalette.textSecondary)
            HStack(spacing: 4) {
                Text("HRV").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                Text("62").font(StrandFont.bodyNumber).foregroundStyle(StrandPalette.textPrimary)
                Text("ms").font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
    .frame(width: 520, height: 620)
    .background(StrandPalette.surfaceBase)
    .preferredColorScheme(.dark)
}
#endif
