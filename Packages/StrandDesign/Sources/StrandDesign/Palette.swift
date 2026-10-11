import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Hex Color Helper

public extension Color {
    /// Parse a hex string ("#0B0D12" / "0B0D12" RGB, or "#AARRGGBB"/"RRGGBBAA" RGBA) to sRGB
    /// components in 0...1. Shared by `Color(hex:)` and the dynamic `Color(light:dark:)` provider.
    static func sRGBComponents(hex: String) -> (r: Double, g: Double, b: Double, a: Double) {
        let raw = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: raw).scanHexInt64(&int)
        switch raw.count {
        case 8: // RRGGBBAA
            return (Double((int >> 24) & 0xFF) / 255.0, Double((int >> 16) & 0xFF) / 255.0,
                    Double((int >> 8) & 0xFF) / 255.0, Double(int & 0xFF) / 255.0)
        default: // RRGGBB (6) and any fallback
            return (Double((int >> 16) & 0xFF) / 255.0, Double((int >> 8) & 0xFF) / 255.0,
                    Double(int & 0xFF) / 255.0, 1.0)
        }
    }

    /// Create a Color from a hex string like "#0B0D12" or "0B0D12" (RGB) or "#AARRGGBB" / "RRGGBBAA".
    /// Supported lengths: 6 (RGB), 8 (RGBA).
    init(hex: String) {
        let c = Color.sRGBComponents(hex: hex)
        self.init(.sRGB, red: c.r, green: c.g, blue: c.b, opacity: c.a)
    }

    /// A colour that resolves to `light` or `dark` (both hex strings) per the active appearance.
    /// Backed by a `UIColor`/`NSColor` dynamic provider, so a single token automatically re-resolves
    /// at every one of its call sites when the colour scheme flips — no per-view environment plumbing.
    /// This is the whole light-theme strategy: only the token definitions change, never the call sites.
    ///
    /// `lightHC` / `darkHC` are the Increase Contrast variants (`accessibilityContrast == .high`); a token
    /// that omits them keeps its normal hex there (CR-2).
    init(light: String, dark: String, lightHC: String? = nil, darkHC: String? = nil) {
        // #2393: parse BOTH hexes ONCE, here, and let the provider pick between two ready tuples.
        //
        // The provider closure is not called once per token — it is called once per RESOLUTION, and the
        // liquid layer resolves on every frame: `Color.liquidComponents()` asks for
        // `NSColor(self).usingColorSpace(.sRGB)` (`LiquidCore.swift`), which re-invokes this closure,
        // which used to run `trimmingCharacters` + `Scanner.scanHexInt64` over a string. A reporter
        // profiling NOOP at a third to half a CPU core on macOS found `Color.sRGBComponents(hex:)` and
        // `closure #1 in Color.init(light:dark:)` among the hot leaves (#2393).
        //
        // A cache would also have removed the cost, and a cache is the wrong shape for it: it needs a
        // key, a lock (a dynamic provider can resolve off the main thread) and an eviction story, to
        // re-derive per frame a value that cannot change after this line. The tokens are `static let`
        // (`NoopVisualStyle`, `StrandPalette`), so this runs once per token for the life of the process.
        // One declaration rather than two `let`s: watchOS resolves straight to the dark hex, so a separate
        // `lightComponents` would be unused on that platform and warn. A pair that is always read as a
        // whole has no such half.
        let components = (light: Color.sRGBComponents(hex: light), dark: Color.sRGBComponents(hex: dark),
                          lightHC: Color.sRGBComponents(hex: lightHC ?? light),
                          darkHC: Color.sRGBComponents(hex: darkHC ?? dark))
        #if os(watchOS)
        // watchOS has no UITraitCollection / dynamic-provider UIColor, and our watch app is effectively
        // always dark, so a token resolves straight to its dark hex. No per-scheme plumbing on the wrist.
        self.init(.sRGB, red: components.dark.r, green: components.dark.g,
                  blue: components.dark.b, opacity: components.dark.a)
        #elseif canImport(UIKit)
        self.init(UIColor { trait in
            let high = trait.accessibilityContrast == .high
            let c = trait.userInterfaceStyle == .dark
                ? (high ? components.darkHC : components.dark)
                : (high ? components.lightHC : components.light)
            return UIColor(red: CGFloat(c.r), green: CGFloat(c.g), blue: CGFloat(c.b), alpha: CGFloat(c.a))
        })
        #elseif canImport(AppKit)
        self.init(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            let high = NSWorkspace.shared.accessibilityDisplayShouldIncreaseContrast
            let c = isDark ? (high ? components.darkHC : components.dark)
                           : (high ? components.lightHC : components.light)
            return NSColor(srgbRed: CGFloat(c.r), green: CGFloat(c.g), blue: CGFloat(c.b), alpha: CGFloat(c.a))
        })
        #else
        self.init(.sRGB, red: components.dark.r, green: components.dark.g,
                  blue: components.dark.b, opacity: components.dark.a)
        #endif
    }
}

// MARK: - Strand Palette
//
// The "Titanium & Gold" re-skin: a premium dark theme built on a deep navy canvas with
// per-domain accent "colour worlds" (Charge = gold, Effort = amber, Rest = blue,
// Stress = blue→gold→orange). GOLD is the dominant brand anchor; titanium drives the
// neutral chrome (tiles, avatars, icons).
//
// PUBLIC API IS FROZEN: every property name below is depended on by screens across
// macOS / iOS, so the names never change — only the VALUES were re-themed. New
// Titanium & Gold tokens (gold ramp, titanium ramp, gradients) are ADDED at the end
// of the type; nothing existing was removed or renamed.

public enum StrandPalette {

    // MARK: Surfaces — deep navy canvas, tinted frosted cards
    // Background is a near-black navy (NOT pure black); cards float just above it.
    public static let surfaceBase    = NoopVisualStyle.canvas
    public static let surfaceRaised  = NoopVisualStyle.surface
    public static let surfaceOverlay = NoopVisualStyle.surfaceTop
    public static let surfaceInset   = NoopVisualStyle.inset
    public static let hairline       = NoopVisualStyle.border
    public static let hairlineStrong = NoopVisualStyle.borderHighlight

    // MARK: Text — deep navy-ink on paper / cool off-white on navy
    public static let textPrimary    = NoopVisualStyle.primaryText
    public static let textSecondary  = NoopVisualStyle.secondaryText
    public static let textTertiary   = NoopVisualStyle.tertiaryText

    // MARK: Text ON a permanently-dark surface (scheme-invariant)
    // Use these — NOT textPrimary/Secondary/Tertiary — for labels/pills drawn over a fill that is pinned
    // dark in BOTH themes (e.g. the over-sky ScreenScaffold title, on the time-of-day sky backdrop). The
    // regular text tokens FLIP to dark ink in Light mode, so on a fixed-dark surface they render
    // dark-on-near-black and vanish (#1013). These hold the light-on-dark values in BOTH schemes, so a
    // label always reads.
    public static let onDarkPrimary   = Color(hex: "#F4F6F8")

    // MARK: Glow — ambient bloom behind heroes / charts (additive on dark; faint warm on light)

    // MARK: Accent — chrome anchor (links, selection, focus, generic accent). USER-SELECTABLE (mint /
    // WHOOP blue / custom) via `accentChoice` below, default mint (#1068). Only the chrome accent is
    // user-themed — the recovery/strain/sleep DATA worlds follow `chartStyle`, never this.
    /// The user's chrome-accent choice. Set from `@AppStorage(AccentColor.storageKey)` at the app root
    /// via `.noopAccent(...)`; the four accessors below branch on it. Default mint.
    public static var accentChoice: AccentColor = .mint
    /// The custom accent's hex, used only when `accentChoice == .custom`. Set alongside `accentChoice`.
    public static var customAccentHex: String = AccentColor.defaultCustomHex
    public static var accent: Color { accentChoice.accent }
    public static var accentHover: Color { accentChoice.accentHover }
    public static var accentMuted: Color { accentChoice.accentMuted }
    /// Focus ring color — the same accent, on both schemes.
    public static var focusRing: Color { accentChoice.focusRing }

    // MARK: - Chart style (data-viz colour mode) — Titanium (brand) or Classic (throwback)
    //
    // Set from `@AppStorage(ChartStyle.storageKey)` at the app root. The DATA-RAMP accessors below
    // (recoveryStops, strainStops, hrZones, sleepStageColor, stress gradient, status, metric, and the
    // DomainTheme worlds) branch on this — so flipping it re-colours every gauge/chart/scale to the
    // classic red→green readiness scale, in BOTH light and dark, with NO call-site changes. Chrome
    // (surfaces, text, accent) is never touched.
    public static var chartStyle: ChartStyle = .titanium
    @inline(__always) static var isClassic: Bool { chartStyle == .classic }

    // MARK: Classic (throwback) data ramps — the recognizable health-app scale. Light/dark tuned.
    // Recovery: red → orange → amber → lime → green.
    static let cRecovery000 = Color(light: "#CB3A2F", dark: "#E5483B")
    static let cRecovery030 = Color(light: "#D87328", dark: "#EE8B3C")
    static let cRecovery055 = Color(light: "#CFA528", dark: "#F2C53D")
    static let cRecovery078 = Color(light: "#74A53A", dark: "#A6D04E")
    static let cRecovery100 = Color(light: "#2E9E4F", dark: "#46B45A")
    static let cRecoveryStops: [Gradient.Stop] = [
        .init(color: cRecovery000, location: 0.00), .init(color: cRecovery030, location: 0.30),
        .init(color: cRecovery055, location: 0.55), .init(color: cRecovery078, location: 0.78),
        .init(color: cRecovery100, location: 1.00),
    ]
    // Strain: the classic light→deep blue cardiovascular ramp.
    static let cStrain000 = Color(light: "#5E92D6", dark: "#7FB2E8")
    static let cStrain033 = Color(light: "#3A74C4", dark: "#4A90E2")
    static let cStrain066 = Color(light: "#284F9C", dark: "#2F6FCB")
    static let cStrain100 = Color(light: "#1C3E80", dark: "#1E4FA0")
    static let cStrainStops: [Gradient.Stop] = [
        .init(color: cStrain000, location: 0.00), .init(color: cStrain033, location: 0.33),
        .init(color: cStrain066, location: 0.66), .init(color: cStrain100, location: 1.00),
    ]
    // Sleep: grey awake, blue light, deep indigo, purple REM.
    static let cSleepAwake = Color(light: "#8C95A3", dark: "#C9CCD6")
    static let cSleepLight = Color(light: "#3A80D6", dark: "#6FA8E8")
    static let cSleepDeep  = Color(light: "#203E73", dark: "#2A4C8F")
    static let cSleepREM   = Color(light: "#6A4FC0", dark: "#8E6FD6")
    // HR zones: grey → green → yellow → orange → red.
    static let cZone1 = Color(light: "#828D9B", dark: "#9AA7B5")
    static let cZone2 = Color(light: "#2E9E4F", dark: "#46B45A")
    static let cZone3 = Color(light: "#CFA528", dark: "#F2C53D")
    static let cZone4 = Color(light: "#D87328", dark: "#EE8B3C")
    static let cZone5 = Color(light: "#CB3A2F", dark: "#E5483B")
    // Stress: calm green → amber → red.
    static let cStressStops: [Gradient.Stop] = [
        .init(color: Color(light: "#2E9E4F", dark: "#46B45A"), location: 0.0),
        .init(color: Color(light: "#CFA528", dark: "#F2C53D"), location: 0.5),
        .init(color: Color(light: "#CB3A2F", dark: "#E5483B"), location: 1.0),
    ]

    // MARK: Recovery / Charge gradient — the gold "Charge" colour world.
    // A single warm metal ramp: a deep bronze floor climbs through brand gold into a
    // bright champagne peak — no green anywhere; depleted reads as dim gold, not coral.
    // 0.00 bronze → 0.30 antique gold → 0.55 brand gold → 0.78 soft gold → 1.00 champagne.
    public static let recovery000 = Color(light: "#C0392B", dark: "#E0463C") // depleted — WHOOP red
    public static let recovery030 = Color(light: "#D9682A", dark: "#E8743C") // low — red-orange
    public static let recovery055 = Color(light: "#C99A00", dark: "#F9DF4A") // moderate — WHOOP yellow
    public static let recovery078 = Color(light: "#6FB23A", dark: "#8FD86A") // primed — yellow-green
    public static let recovery100 = Color(light: "#0F9D62", dark: "#03E095") // peak — WHOOP green

    /// Ordered gradient stops for the recovery scale (Titanium gold ramp, or the Classic red→green).
    public static var recoveryStops: [Gradient.Stop] {
        isClassic ? cRecoveryStops : [
            .init(color: recovery000, location: 0.00),
            .init(color: recovery030, location: 0.30),
            .init(color: recovery055, location: 0.55),
            .init(color: recovery078, location: 0.78),
            .init(color: recovery100, location: 1.00),
        ]
    }

    /// The signature recovery gradient (bronze → champagne, or Classic red→green).
    public static var recoveryGradient: Gradient { Gradient(stops: recoveryStops) }

    // MARK: Strain / Effort ramp — the amber "Effort" colour world.
    // Deep ember → warm amber → bright amber → soft amber peak: heat/output, all in the
    // Effort accent family rather than veering into magenta.
    public static let strain000 = Color(light: "#7E460E", dark: "#9C5A14") // deep ember
    public static let strain033 = Color(light: "#A4621B", dark: "#C2762A") // warm amber
    public static let strain066 = Color(light: "#C2792E", dark: "#D98A3D") // bright amber
    public static let strain100 = Color(light: "#D89240", dark: "#F0A85A") // soft amber peak

    public static var strainStops: [Gradient.Stop] {
        isClassic ? cStrainStops : [
            .init(color: strain000, location: 0.00),
            .init(color: strain033, location: 0.33),
            .init(color: strain066, location: 0.66),
            .init(color: strain100, location: 1.00),
        ]
    }

    /// The strain gradient (output / heat, or the Classic blue ramp).
    public static var strainGradient: Gradient { Gradient(stops: strainStops) }

    // MARK: Sleep stages — the blue "Rest" colour world (Titanium); Classic adds a purple REM.
    // WHOOP sleep-stage palette (adopted from ryanAtriumAi #988): four distinct hues per stage —
    // Awake white-grey #CAC8CB, Light periwinkle #A7A4F4, SWS/Deep orchid-pink #FD96FD, REM purple
    // #AE5BEF — because the previous three near-identical blues made a fragmented on-device
    // hypnogram unreadable. Light-mode variants are the same hues darkened for contrast on white.
    public static var sleepAwake: Color { isClassic ? cSleepAwake : Color(light: "#8E949E", dark: "#CAC8CB") }
    public static var sleepLight: Color { isClassic ? cSleepLight : Color(light: "#7B78E0", dark: "#A7A4F4") }
    public static var sleepDeep:  Color { isClassic ? cSleepDeep  : Color(light: "#C13EC1", dark: "#FD96FD") }
    public static var sleepREM:   Color { isClassic ? cSleepREM   : Color(light: "#8E3BD6", dark: "#AE5BEF") }

    // MARK: HR zones — Titanium cool→warm (no green), or the Classic grey→green→yellow→orange→red.
    public static var zone1: Color { isClassic ? cZone1 : Color(light: "#3A80D6", dark: "#4A90E2") }
    public static var zone2: Color { isClassic ? cZone2 : Color(light: "#2E92B4", dark: "#3FA9C9") }
    public static var zone3: Color { isClassic ? cZone3 : Color(light: "#C28E26", dark: "#E8B84B") }
    public static var zone4: Color { isClassic ? cZone4 : Color(light: "#C2792E", dark: "#D98A3D") }
    public static var zone5: Color { isClassic ? cZone5 : Color(light: "#C84E1E", dark: "#E0662F") }

    /// HR zones indexed 1...5; index 0 mirrors zone1 for convenience.
    public static var hrZones: [Color] { [zone1, zone1, zone2, zone3, zone4, zone5] }

    // MARK: Status — Titanium gold/amber/orange, or the Classic green/amber/red.
    public static var statusPositive: Color { isClassic ? Color(light: "#2E9E4F", dark: "#46B45A") : Color(light: "#1F8A5B", dark: "#03E095") }
    public static var statusWarning:  Color { isClassic ? Color(light: "#CFA528", dark: "#F2C53D") : Color(light: "#9C6125", dark: "#F0A020", lightHC: "#74481C", darkHC: "#F7BF63") }
    public static var statusCritical: Color { isClassic ? Color(light: "#CB3A2F", dark: "#E5483B") : Color(light: "#C84E1E", dark: "#E0662F") }

    // MARK: Per-metric accents — HRV / SpO₂ / energy / risk. Classic leans the traditional hues (purple HRV, red risk).
    public static var metricCyan:   Color { isClassic ? Color(light: "#2E92B4", dark: "#3FA9C9") : Color(light: "#2E92B4", dark: "#3FA9C9") }
    public static var metricPurple: Color { isClassic ? Color(light: "#6A4FC0", dark: "#8E6FD6") : Color(light: "#3A80D6", dark: "#4A90E2") }
    public static var metricAmber:  Color { isClassic ? Color(light: "#CFA528", dark: "#F2C53D") : Color(light: "#C2792E", dark: "#D98A3D") }
    public static var metricRose:   Color { isClassic ? Color(light: "#CB3A2F", dark: "#E5483B") : Color(light: "#C84E1E", dark: "#E0662F") }

    // MARK: - Titanium & Gold domain "colour worlds" (NEW)
    //
    // Each daily score owns a two-stop accent gradient (deep → bright) plus a glow.
    // These drive the layered gauges, frosted-card tints and scenic heroes. Charge
    // owns the brand gold; Effort the amber ramp; Rest the blue scale.

    // Each domain's accent / glow follows the chart style: Titanium (gold/amber/blue) or Classic
    // (Charge=green, Effort=blue, Rest=indigo, Stress=amber) so card tints + gauge tips + glows match
    // the data scale. The gauge ARC itself samples the recovery/strain/stress STOPS above, so it goes
    // full red→green / blue / green→red in Classic regardless of these.

    /// Charge (recovery) — gold world / Classic green.
    public static var chargeColor: Color  { isClassic ? Color(light: "#2E9E4F", dark: "#46B45A") : Color(light: "#0F9D62", dark: "#03E095") }

    /// Effort (strain) — amber world / Classic blue.
    public static var effortColor: Color   { isClassic ? Color(light: "#3A74C4", dark: "#4A90E2") : Color(light: "#2A78C8", dark: "#4090E0") }

    /// Rest (sleep) — blue world / Classic indigo.
    public static var restColor: Color     { isClassic ? Color(light: "#3A80D6", dark: "#6FA8E8") : Color(light: "#5E7896", dark: "#83A0B8") }
    public static var restDeep: Color      { isClassic ? Color(light: "#203E73", dark: "#2A4C8F") : Color(light: "#234F9E", dark: "#2F6FCB") }
    public static var restBright: Color    { isClassic ? Color(light: "#6A4FC0", dark: "#8E6FD6") : Color(light: "#5790DA", dark: "#6FA8E8") }
    /// The Rest family's most legible LINE colour — for strokes that must read on a busy or translucent
    /// surface, such as the body-clock dial's arcs over a custom background image.
    ///
    /// Introduces no new value: it selects the existing token that is the bright blue in each palette.
    /// The families are not parallel — classic's `restBright` is a PURPLE accent while modern's is the
    /// blue, and classic's `restColor` is the blue where modern's is a muted steel — so a card naming
    /// either token directly gets the right colour in one palette and the wrong one in the other. Both
    /// resolve to #6FA8E8 in dark.
    public static var restLine: Color { isClassic ? restColor : restBright }


    // MARK: Summary home (Apple-Health-style) — rings, canvas, card, top wash.
    // The three concentric rings need three clearly separate hues at a glance, which the Titanium score
    // family (green / blue / steel-blue) cannot give, so the Summary owns its own trio.
    /// Charge, Effort and Rest in one triad on every screen: the Summary rings' Move red, Exercise green
    /// and Stand cyan (the `activity*Start` hues below). Text in these hues goes through `text(for:)`.
    public static let summaryChargeRing       = activityMoveStart
    public static let summaryEffortRing       = activityExerciseStart
    public static let summaryRestRing         = activityStandStart
    /// The Activity rings as Apple Watch draws them (sampled from Apple's own ring artwork): each ring
    /// runs from a deeper start hue to a brighter end hue. The rings always sit on a black disc, as they
    /// do in Health, so one set of values serves both appearances.
    public static let activityMoveStart     = Color(hex: "#E8182F")
    public static let activityMoveEnd       = Color(hex: "#FA2E6C")
    public static let activityExerciseStart = Color(hex: "#3BDC00")
    public static let activityExerciseEnd   = Color(hex: "#B4FF00")
    public static let activityStandStart    = Color(hex: "#00BDEA")
    public static let activityStandEnd      = Color(hex: "#00F2F0")
    /// The same three hues as text on a card, darkened in light mode to 4.5:1 on #F2F2F7 (7:1 under
    /// Increase Contrast) (CR-2).
    public static let activityMoveText     = Color(light: "#DC093F", dark: "#FF2D6C", lightHC: "#A5072F", darkHC: "#FF7CA3")
    public static let activityExerciseText = Color(light: "#1D7C00", dark: "#A6FF00", lightHC: "#165F00", darkHC: "#C4FF5C")
    public static let activityStandText    = Color(light: "#007992", dark: "#00E5F0", lightHC: "#005A6D", darkHC: "#7AF4F8")
    /// The ring Fitness draws round a workout measured by its time (sampled from its Sharing tab): amber
    /// deepening to yellow, with the yellow its duration is set in. Drawn on black like the three above.
    public static let activityTimeStart    = Color(hex: "#FFC500")
    public static let activityTimeEnd      = Color(hex: "#FFE620")
    public static let activityTimeText     = Color(light: "#8C6A00", dark: "#FFE620", lightHC: "#684E00", darkHC: "#FFEE66")
    /// Health's Activity category tint (the flame in the card's title row).
    public static let activityTitle        = Color(light: "#FA3C1E", dark: "#FF5A36", lightHC: "#D92205", darkHC: "#FF8267")
    /// Fitness's Workout-tab card: the Exercise green washed into the page — deep olive on black in dark
    /// mode, a pale green on the light canvas.
    public static let fitnessCard          = Color(light: "#E3F5D6", dark: "#1B2610")
    /// Glyph on an Exercise-green button (Fitness's black play triangle; white on the light variant).
    public static let fitnessOnAccent      = Color(light: "#FFFFFF", dark: "#000000")
    /// Fitness's workout-page hues: durations in yellow, Effort in blue.
    public static let fitnessTime          = Color(light: "#8C6A00", dark: "#FFD60A", lightHC: "#684E00", darkHC: "#FFE566")
    public static let fitnessEffort        = Color(light: "#007AFF", dark: "#0A84FF")
    /// Fitness's workout-goal card hues (purple, teal, blue, pink, orange), cycled per card.
    public static func fitnessGoal(_ index: Int) -> Color { fitnessGoalHues[index % 5] }
    private static let fitnessGoalHues: [Color] = [
        Color(light: "#A34BD6", dark: "#BF5AF2", lightHC: "#9E41D4", darkHC: "#D28AF6"),
        Color(light: "#0F9BB0", dark: "#40C8E0", lightHC: "#0C7A8B", darkHC: "#86DDEC"),
        .blue,
        Color(light: "#E0284F", dark: "#FF375F", lightHC: "#D71F46", darkHC: "#FF7E98"),
        Color(light: "#E07F00", dark: "#FF9F0A", lightHC: "#A55D00", darkHC: "#FFC466"),
    ]
    /// The goal hues as text on their pale card (CR-2).
    private static let fitnessGoalTexts: [Color] = [
        Color(light: "#9E41D4", dark: "#BF5AF2", lightHC: "#7927A9", darkHC: "#D28AF6"),
        Color(light: "#0C7A8B", dark: "#40C8E0", lightHC: "#095A67", darkHC: "#86DDEC"),
        .blue,
        Color(light: "#D71F46", dark: "#FF375F", lightHC: "#A11734", darkHC: "#FF7E98"),
        Color(light: "#A55D00", dark: "#FF9F0A", lightHC: "#7A4500", darkHC: "#FFC466"),
    ]
    /// Fitness's five heart-rate zone hues (blue, teal, lime, orange, pink), Zone 1 first.
    public static func fitnessZone(_ zone: Int) -> Color {
        switch zone {
        case 1:  return Color(light: "#1E7FE0", dark: "#3A9BFF", lightHC: "#1B70C6", darkHC: "#7DBDFF")
        case 2:  return Color(light: "#0FA596", dark: "#37D6C4", lightHC: "#0B7C71", darkHC: "#37D6C4")
        case 3:  return Color(light: "#5E9E00", dark: "#B7F23A", lightHC: "#4A7C00", darkHC: "#B7F23A")
        case 4:  return Color(light: "#E07F00", dark: "#FF9F0A", lightHC: "#A55D00", darkHC: "#FFC466")
        default: return Color(light: "#E0284F", dark: "#FF3B6B", lightHC: "#D71F46", darkHC: "#FF7E98")
        }
    }
    /// The zone hues as text ("Zone 2"): 4.5:1 on #F2F2F7, 7:1 under Increase Contrast (CR-2).
    public static func fitnessZoneText(_ zone: Int) -> Color {
        switch zone {
        case 1:  return Color(light: "#1B70C6", dark: "#3A9BFF", lightHC: "#145393", darkHC: "#7DBDFF")
        case 2:  return Color(light: "#0B7C71", dark: "#37D6C4", lightHC: "#085C54", darkHC: "#37D6C4")
        case 3:  return Color(light: "#4A7C00", dark: "#B7F23A", lightHC: "#365B00", darkHC: "#B7F23A")
        case 4:  return Color(light: "#A55D00", dark: "#FF9F0A", lightHC: "#7A4500", darkHC: "#FFC466")
        default: return Color(light: "#D71F46", dark: "#FF3B6B", lightHC: "#A11734", darkHC: "#FF7E98")
        }
    }
    /// Grouped-list canvas behind the Summary cards, and the solid card on it: the system grouped
    /// backgrounds on iOS, so sheets get the elevated dark variant and Increase Contrast its own (CR-2).
    #if canImport(UIKit) && !os(watchOS)
    public static let summaryCanvas = Color(uiColor: .systemGroupedBackground)
    public static let summaryCard   = Color(uiColor: .secondarySystemGroupedBackground)
    #else
    public static let summaryCanvas = Color(light: "#F2F2F7", dark: "#000000")
    public static let summaryCard   = Color(light: "#FFFFFF", dark: "#1C1C1E")
    #endif
    /// The default (no photo) profile circle: Contacts-style grey gradient behind a white silhouette.
    public static let summaryAvatarTop    = Color(light: "#A9AEBB", dark: "#8E929E")
    public static let summaryAvatarBottom = Color(light: "#868A96", dark: "#6B6F7A")
    /// Apple Fitness's Summary tiles (measured on iOS 26.5): the chart's rules and day letters, and the grey
    /// disc behind a tile's chevron. Fitness is dark-only; the light values are the system greys it would take.
    /// The pixel-thin rules on Fitness's page for a friend (sampled from it): under each of the day's
    /// figures, and the fainter one under each workout in the list. The light values are the system
    /// separator's, since Fitness has no light page to sample.
    public static let fitnessFigureRule = Color(light: "#C6C6C8", dark: "#484848")
    public static let fitnessListRule   = Color(light: "#C6C6C8", dark: "#2A2A2C")
    public static let fitnessTileRule = Color(light: "#D1D1D6", dark: "#5D5D60", lightHC: "#AEAEB2", darkHC: "#8E8E93")
    public static let fitnessTileDisc = Color(light: "#C7C7CC", dark: "#727275", lightHC: "#8E8E93", darkHC: "#AEAEB2")
    /// The outline of Health's "Show All Health Data" glyph.
    public static let healthDataGlyph = Color(light: "#CFCFD1", dark: "#5A5A5E", lightHC: "#8E8E93", darkHC: "#8E8E93")
    /// Sleep screen (Apple Health idiom): the four stage hues, and the plain page colour the chart sits on.
    /// Health's own values (iOS 26): Awake and Deep are HealthUI's `sleep_awake` / `sleep_deep_color`
    /// (Awake converted from Display P3), REM and Core the system cyan and blue.
    public static let healthSleepAwake = Color(light: "#FF836C", dark: "#FF694E", lightHC: "#DF3317", darkHC: "#FF836C")
    #if canImport(UIKit) && !os(watchOS)
    public static let healthSleepRem   = Color(uiColor: .systemCyan)
    public static let healthSleepCore  = Color(uiColor: .systemBlue)
    #else
    public static let healthSleepRem   = Color(light: "#00C0E8", dark: "#3CD3FE")
    public static let healthSleepCore  = Color(light: "#0088FF", dark: "#0091FF")
    #endif
    public static let healthSleepDeep  = Color(light: "#3634A3", dark: "#3634A3", darkHC: "#7E7CFB")
    public static let healthSleepPage  = Color(light: "#FFFFFF", dark: "#000000")
    /// Health's stages chart: the stage names and row rules (secondary label), the hour grid (that grey at
    /// 35 %), and the clock under the plot (tertiary label).
    #if canImport(UIKit) && !os(watchOS)
    public static let healthChartLabel     = Color(uiColor: .secondaryLabel)
    public static let healthChartAxisLabel = Color(uiColor: .tertiaryLabel)
    #else
    public static let healthChartLabel     = Color(light: "#3C3C4399", dark: "#EBEBF599")
    public static let healthChartAxisLabel = Color(light: "#3C3C434D", dark: "#EBEBF54D")
    #endif
    public static let healthChartGrid      = healthChartLabel.opacity(0.35)
    /// The plain white (dark: black) page Messages and Health's Medications draw on, and the grey card
    /// that sits on it.
    public static let plainPage           = Color(light: "#FFFFFF", dark: "#000000")
    public static let plainPageCard       = Color(light: "#F2F2F7", dark: "#1C1C1E")
    /// Coach as a Messages conversation (iOS 26). Values are the Messages app's own: the blue bubble is
    /// a gradient pinned to the screen, light at the top and deep at the bottom, so a bubble's shade
    /// follows where it sits; the grey reply bubble and its text and links; the typing dots (drawn at
    /// 20–45 % opacity); the grey caption text for times and delivery; the field placeholder and mic; the send
    /// button; the failure red; the contact circle behind the Coach glyph; and the Apple Intelligence
    /// wash on suggestion text.
    public static let messageOutgoingTop    = Color(light: "#5AC8FA", dark: "#409CFF")
    public static let messageOutgoingBottom = Color(light: "#0088FF", dark: "#0091FF")
    public static let messageOutgoingText   = Color(light: "#FFFFFF", dark: "#FFFFFF")
    public static let messageIncoming       = Color(light: "#E9E9EB", dark: "#262629")
    public static let messageIncomingText   = Color(light: "#000000", dark: "#FFFFFF")
    public static let messageLink           = Color(light: "#007AFF", dark: "#0984FF")
    public static let messageTypingDot      = Color(light: "#000000", dark: "#FFFFFF")
    #if canImport(UIKit) && !os(watchOS)
    public static let messageMeta           = Color(uiColor: .secondaryLabel)
    #else
    public static let messageMeta           = Color(light: "#3C3C4399", dark: "#EBEBF599")
    #endif
    public static let messagePlaceholder    = Color(light: "#3C3C434D", dark: "#EBEBF54D")
    public static let messageFieldGlyph     = Color(light: "#858E9980", dark: "#EBEBF56B")
    public static let messageSend           = Color(light: "#0088FF", dark: "#0091FF")
    public static let messageFailure        = Color(light: "#FF383C", dark: "#FF383C")
    public static let messageAvatarTop      = Color(light: "#A9C1E0", dark: "#565468")
    public static let messageAvatarBottom   = Color(light: "#7481BA", dark: "#2E2147")
    public static let messageSuggestionStart = Color(light: "#5AA6D4", dark: "#6FB8E6")
    public static let messageSuggestionEnd   = Color(light: "#E8607E", dark: "#F0708C")
    /// Sleep schedule (Health's Full Schedule): the schedule's purple, and the bedtime/wake dial — the
    /// card it sits on, the track ring, the clock face, the bedtime→wake arc, its ticks and end glyphs.
    public static let sleepSchedule       = Color(light: "#5E5CE6", dark: "#7D7AFF", lightHC: "#3532E0", darkHC: "#9E9CFF")
    public static let sleepDialCard       = Color(light: "#F2F2F7", dark: "#2C2C2E")
    public static let sleepDialTrack      = Color(light: "#E3E3E8", dark: "#000000")
    public static let sleepDialFace       = Color(light: "#FFFFFF", dark: "#2C2C2E")
    public static let sleepDialArc        = Color(light: "#FFFFFF", dark: "#3A3A3C")
    public static let sleepDialArcTick    = Color(light: "#E5E5EA", dark: "#232325")
    public static let sleepDialKnobGlyph  = Color(light: "#8E8E93", dark: "#98989D")
    public static let sleepDialSun        = Color(light: "#FFCC00", dark: "#FFD60A")
    /// "Show More Sleep Data" → Comparisons: the Health category hue each overlaid vital is drawn in.
    public static let healthHeart       = Color(light: "#FF2D55", dark: "#FF375F", lightHC: "#DF002A", darkHC: "#FF7E98")
    public static let healthRespiratory = Color(light: "#00C7BE", dark: "#63E6E2", lightHC: "#007C76", darkHC: "#9AF0ED")
    public static let healthOxygen      = Color(light: "#32ADE6", dark: "#64D2FF", lightHC: "#1476A4", darkHC: "#9ADFFF")
    public static let healthTemperature = Color(light: "#FF9500", dark: "#FF9F0A", lightHC: "#A25F00", darkHC: "#FFC466")
    /// Health category hues for the metric pages and their Summary cards (Heart / Respiratory above).
    public static let healthBody      = Color(light: "#AF52DE", dark: "#BF5AF2", lightHC: "#A439D9", darkHC: "#D28AF6")
    public static let healthMind      = Color(light: "#30B0C7", dark: "#40C8E0", lightHC: "#217989", darkHC: "#86DDEC")
    public static let healthNutrition = Color(light: "#34C759", dark: "#30D158", lightHC: "#217F39", darkHC: "#6FE08C")
    /// Category hues as TEXT on a card or canvas (CR-2): the same hue darkened in light mode to 4.5:1 on
    /// #F2F2F7, and to 7:1 under Increase Contrast. The hue tokens above stay on glyphs, rings and charts.
    public static let healthHeartText       = Color(light: "#DF002A", dark: "#FF375F", lightHC: "#A80020", darkHC: "#FF7E98")
    public static let healthRespiratoryText = Color(light: "#007C76", dark: "#63E6E2", lightHC: "#005C58", darkHC: "#9AF0ED")
    public static let healthOxygenText      = Color(light: "#1476A4", dark: "#64D2FF", lightHC: "#0F5779", darkHC: "#9ADFFF")
    public static let healthTemperatureText = Color(light: "#A25F00", dark: "#FF9F0A", lightHC: "#784600", darkHC: "#FFC466")
    public static let healthBodyText        = Color(light: "#A439D9", dark: "#BF5AF2", lightHC: "#7D21AC", darkHC: "#D28AF6")
    public static let healthMindText        = Color(light: "#217989", dark: "#40C8E0", lightHC: "#185965", darkHC: "#86DDEC")
    public static let healthNutritionText   = Color(light: "#217F39", dark: "#30D158", lightHC: "#195E2A", darkHC: "#6FE08C")
    public static let healthSleepText       = Color(light: "#5E5CE6", dark: "#7D7AFF", lightHC: "#3532E0", darkHC: "#9E9CFF")
    public static let activityTitleText     = Color(light: "#D92205", dark: "#FF5A36", lightHC: "#A21904", darkHC: "#FF8267")
    /// The text token for a hue token, for a label drawn in its category's hue (CR-2); a hue with no
    /// text twin is returned unchanged. The lookup compares the static tokens themselves.
    public static func text(for hue: Color) -> Color {
        let pairs: [(Color, Color)] = [
            (healthHeart, healthHeartText), (healthRespiratory, healthRespiratoryText),
            (healthOxygen, healthOxygenText), (healthTemperature, healthTemperatureText),
            (healthBody, healthBodyText), (healthMind, healthMindText),
            (healthNutrition, healthNutritionText), (activityTitle, activityTitleText),
            (sleepSchedule, healthSleepText),
            (activityMoveStart, activityMoveText), (activityMoveEnd, activityMoveText),
            (activityExerciseStart, activityExerciseText), (activityExerciseEnd, activityExerciseText),
            (activityStandStart, activityStandText), (activityStandEnd, activityStandText),
            (healthZoneHigh, healthNutritionText), (fitnessTime, fitnessTime),
            (activityTimeStart, activityTimeText), (activityTimeEnd, activityTimeText),
        ]
        return (pairs + Array(zip(fitnessGoalHues, fitnessGoalTexts))).first { $0.0 == hue }?.1 ?? hue
    }
    /// A daily score's state on its chart, in Apple's system red / yellow / green.
    public static let healthZoneLow   = Color(light: "#FF3B30", dark: "#FF453A", lightHC: "#D70015", darkHC: "#FF6961")
    public static let healthZoneMid   = Color(light: "#FFCC00", dark: "#FFD60A", lightHC: "#A17B00", darkHC: "#FFE566")
    public static let healthZoneHigh  = Color(light: "#34C759", dark: "#30D158", lightHC: "#217F39", darkHC: "#6FE08C")
    /// Sleep score ring: one hue per part of the score. Duration and Interruptions are Health's own; its
    /// Bedtime teal marks Regularity, the part that reads bedtime; Deep & REM, which Health has no part
    /// for, takes a violet beside them.
    public static let sleepScoreDuration     = Color(light: "#3E62FF", dark: "#4265FF")
    public static let sleepScoreInterruption = Color(light: "#FF826C", dark: "#FF694E")
    public static let sleepScoreRestorative  = Color(light: "#A86CF0", dark: "#BE8CFF")
    public static let sleepScoreRegularity   = Color(light: "#00C8B3", dark: "#00DAC3")
    /// Health's Sleep Score page: the Sleep title hue, the page and its cards (a step lighter than the
    /// Summary's in dark mode, as Health draws this page), and the chevron on a card's title.
    public static let sleepScoreTitle  = Color(light: "#6155F5", dark: "#6D7CFF")
    public static let sleepScoreCanvas = Color(light: "#F2F2F7", dark: "#1C1C1E")
    public static let sleepScoreCard   = Color(light: "#FFFFFF", dark: "#2C2C2E")
    #if canImport(UIKit) && !os(watchOS)
    public static let healthChevron    = Color(uiColor: .tertiaryLabel)
    #else
    public static let healthChevron    = Color(light: "#3C3C434D", dark: "#EBEBF54D")
    #endif
    /// Health's Vitals: the typical-range hue and its band, the outlier hue and its band, the grey of a
    /// night still to come, and the grey high and low zones either side of the band.
    public static let vitalsTypical     = Color(light: "#3E97F8", dark: "#55AEFF", lightHC: "#186DC9", darkHC: "#55AEFF")
    public static let vitalsTypicalBand = Color(light: "#9FCBFC", dark: "#2D648E", lightHC: "#9BC1EB", darkHC: "#417093")
    public static let vitalsOutlier     = Color(light: "#FF6CE5", dark: "#FF6CE5", lightHC: "#CC21B1", darkHC: "#FF6CE5")
    public static let vitalsOutlierBand = Color(light: "#FFA9E5", dark: "#8E4679", lightHC: "#F09CD7", darkHC: "#915380")
    public static let vitalsPending     = Color(light: "#AEAEB2", dark: "#636366")
    public static let vitalsZone        = Color(light: "#E5E5EA", dark: "#3A3A3C")
    /// Settings rows (iOS Settings idiom): the solid rounded squares behind each row's white glyph, in
    /// Apple's system hues.
    public static let settingsGray   = Color(light: "#8E8E93", dark: "#8E8E93")
    public static let settingsBlue   = Color(light: "#007AFF", dark: "#0A84FF")
    public static let settingsGreen  = Color(light: "#34C759", dark: "#30D158")
    public static let settingsRed    = Color(light: "#FF3B30", dark: "#FF453A")
    public static let settingsOrange = Color(light: "#FF9500", dark: "#FF9F0A")
    public static let settingsPink   = Color(light: "#FF2D55", dark: "#FF375F")
    public static let settingsPurple = Color(light: "#AF52DE", dark: "#BF5AF2")
    public static let settingsIndigo = Color(light: "#5856D6", dark: "#5E5CE6")
    public static let settingsTeal   = Color(light: "#30B0C7", dark: "#40C8E0")
    public static let settingsCyan   = Color(light: "#32ADE6", dark: "#64D2FF")
    /// Settings row icon in dark appearance, as iOS 26 draws a Dark icon: a near-black tile (lighter at
    /// the top) under the row's coloured glyph, with a faint glass rim.
    public static let settingsIconDarkTop    = Color(hex: "#48484A")
    public static let settingsIconDarkBottom = Color(hex: "#2C2C2E")
    public static let settingsIconRim        = Color.white.opacity(0.35)
    public static let settingsIconDarkRim    = Color.white.opacity(0.14)
    /// The name field on a pairing card: iOS's tertiary system fill.
    public static let deviceField       = Color(light: "#767680", dark: "#767680").opacity(0.12)
    /// Top wash, leading → trailing: warm → violet → cool (sampled from Health's iOS 26 Summary), faded
    /// into `summaryCanvas` by the view.
    public static let summaryWashWarm   = Color(light: "#FFBBA3", dark: "#4E2A24")
    public static let summaryWashViolet = Color(light: "#EDC4D8", dark: "#3E2A48")
    public static let summaryWashCool   = Color(light: "#BACDFF", dark: "#1F2C52")

    /// Stress — blue→gold→orange world / Classic green→amber→red.
    public static var stressColor: Color   { isClassic ? Color(light: "#CFA528", dark: "#F2C53D") : Color(light: "#C7891A", dark: "#F0A020") }

    // MARK: Scenic background (NEW) — detail-screen hero gradient + starfield.


    // MARK: - Titanium & Gold core tokens (NEW)
    //
    // The brand gold ramp (buttons, ring fills, FAB, active chrome) and the neutral
    // titanium ramp (tiles, avatars, icon plates). Same names + hexes on Android so
    // Apple and Android match byte-for-byte.

    /// Brand gold — primary accent. Gold FILLS stay bright (dark text on them is legible in both schemes);
    /// only a hair deeper on light so the fill doesn't wash out against white.
    public static let gold          = Color(light: "#3A78C8", dark: "#60A0E0") // repointed to WHOOP blue (gold killed 2026-06-22)
    /// Bright blue — accent highlight / hover (was champagne).
    public static let goldLight     = Color(light: "#6FA8E0", dark: "#9FC8F0")
    /// Deep blue — accent low stop (was bronze).
    public static let goldDeep      = Color(light: "#2A5C9E", dark: "#3A78C8")
    /// Near-black brown — text / icons placed ON gold surfaces (scheme-invariant; gold fills stay gold).
    public static let goldDeepText  = Color(hex: "#FFFFFF") // white text/icons on accent fills (WHOOP, gold killed)
    /// The bright core dot at a gauge arc tip / sparkline head. White reads as a highlight on the dark
    /// canvas; on light it would vanish into the white card, so it flips to a deep ink that reads as a
    /// crisp centre on the (deepened) coloured tip bead.
    public static let tipCore       = Color(light: "#241B06", dark: "#FFFFFF")
    /// High-vis signal yellow — sparing emphasis (badges / alerts); deepened on light to stay visible.
    public static let signalYellow  = Color(light: "#E8A800", dark: "#FFD63D")
    /// 135–155° gold ramp for buttons, ring fills, FAB (light → gold → deep).
    public static let goldGradient  = Gradient(colors: [goldLight, gold, goldDeep])

    /// Brushed-titanium ramp (top highlight → mid body → low → deep) for tiles, avatars and icon plates.
    /// Shifted to a MID-grey ramp on light so brushed-metal tiles stay visible against white cards.
    public static let titaniumTop   = Color(light: "#DDE1E6", dark: "#F1F3F5")
    public static let titaniumMid   = Color(light: "#BBC2C9", dark: "#C9CFD4")
    public static let titaniumLow   = Color(light: "#98A0A8", dark: "#969DA4")
    public static let titaniumDeep  = Color(hex: "#6B737B")

    // MARK: - Sampling helpers

    /// Sample the recovery gradient (bronze → champagne) at a recovery score 0...100.
    /// Returns the exact interpolated color used everywhere recovery is tinted.
    public static func recoveryColor(_ score: Double) -> Color {
        sample(stops: recoveryStops, at: score / 100.0)
    }

    /// Sample the strain ("Effort") gradient at a value on NOOP's 0...100 Effort scale.
    public static func strainColor(_ strain: Double) -> Color {
        sample(stops: strainStops, at: strain / 100.0)
    }

    /// Effort tint sampled by a 0...1 fraction (e.g. value/scaleMax), spreading the full ember→amber
    /// ramp. Prefer this for gauge tips / value-tinted accents so a high Effort reads as bright amber
    /// rather than ember. `strainColor(_:)` stays for callers holding a 0...100 value.
    public static func effortTint(fraction: Double) -> Color {
        sample(stops: strainStops, at: min(max(fraction, 0), 1))
    }

    /// The state word for a recovery score, per spec §9.3.
    /// DEPLETED · LOW · MODERATE · PRIMED · PEAK
    public static func recoveryState(_ score: Double) -> String {
        switch score {
        case ..<25:  return String(localized: "DEPLETED", bundle: .module)
        case ..<50:  return String(localized: "LOW", bundle: .module)
        case ..<70:  return String(localized: "MODERATE", bundle: .module)
        case ..<88:  return String(localized: "PRIMED", bundle: .module)
        default:     return String(localized: "PEAK", bundle: .module)
        }
    }

    /// HR-zone color for a 0...5 zone index (clamped).
    public static func hrZoneColor(_ zone: Int) -> Color {
        let z = max(1, min(5, zone))
        return hrZones[z]
    }

    /// Color for a sleep stage by canonical name (awake/light/deep/rem).
    public static func sleepStageColor(_ stage: SleepStage) -> Color {
        switch stage {
        case .awake: return sleepAwake
        case .light: return sleepLight
        case .deep:  return sleepDeep
        case .rem:   return sleepREM
        }
    }

    // Brand sleep ramps for the two stepped-hypnogram styles, so the chart reads like the app it's modelled
    // on. Ribbon = Oura's ramp (cream awake + blues, sampled from the ring's app); Filled = Garmin's
    // (blue light/deep + magenta REM). Opt-in only (Sleep-tab stepped chart) — every other Hypnogram caller
    // keeps `sleepStageColor`. (#sleep-chart-style)
    //
    // WHY THERE IS A LIGHT VARIANT. Both source apps are dark-tuned, so the ramps shipped flat — the same
    // hex in both schemes. On the light card those bands are drawn on near-white (`NoopVisualStyle.surface`
    // = #FFFFFF; a real Sleep-screen capture samples #FEFEFF), and measured there the Oura ramp collapses:
    // three of its four bands fall under the 3:1 non-text minimum and `awake` #EAE3D3 sits at **1.28:1**,
    // i.e. not drawn. That is not a rare band — on one real ring night awake was 64% of the chart.
    //
    // HOW THE LIGHT VALUES WERE DERIVED (not eyeballed, and not invented hues). Clamping each band on its
    // own to 3:1 is the obvious fix and it BREAKS THE RAMP: Oura `rem` → #1E9EDD and `light` → #239FD5 land
    // 1.00:1 apart, two adjacent stages the same colour. Instead ONE uniform HLS-lightness scale is applied
    // per ramp, pinned so the ramp's lightest band reaches 3:1 on white — Oura ×0.575, Garmin ×0.912. Hue
    // and saturation are untouched, so it is still recognisably the brand ramp; stage ORDERING survives;
    // and the intra-ramp separation is no worse than the shipped dark ramp's (pinned in
    // `BrandSleepRampTests`, twin `BrandSleepRampTest` on Android).
    static let oSleepAwake = Color(light: BrandSleepRamp.ouraAwake.light, dark: BrandSleepRamp.ouraAwake.dark)
    static let oSleepREM   = Color(light: BrandSleepRamp.ouraREM.light,   dark: BrandSleepRamp.ouraREM.dark)
    static let oSleepLight = Color(light: BrandSleepRamp.ouraLight.light, dark: BrandSleepRamp.ouraLight.dark)
    static let oSleepDeep  = Color(light: BrandSleepRamp.ouraDeep.light,  dark: BrandSleepRamp.ouraDeep.dark)
    static let gSleepAwake = Color(light: BrandSleepRamp.garminAwake.light, dark: BrandSleepRamp.garminAwake.dark)
    static let gSleepREM   = Color(light: BrandSleepRamp.garminREM.light,   dark: BrandSleepRamp.garminREM.dark)
    static let gSleepLight = Color(light: BrandSleepRamp.garminLight.light, dark: BrandSleepRamp.garminLight.dark)
    static let gSleepDeep  = Color(light: BrandSleepRamp.garminDeep.light,  dark: BrandSleepRamp.garminDeep.dark)

    /// The brand ramps as hex PAIRS, so the properties above can be asserted in a test — a `SwiftUI.Color`
    /// built from a dynamic provider cannot be read back, and these are also the values the Kotlin twin
    /// must match byte for byte (`stageColorForBrand` in `SleepStageBreakdownUi.kt`). `.dark` is the
    /// shipped ramp, unchanged.
    enum BrandSleepRamp {
        static let ouraAwake   = (light: "#AD9153", dark: "#EAE3D3")
        static let ouraREM     = (light: "#1A8AC2", dark: "#90D0F0")
        static let ouraLight   = (light: "#176B8E", dark: "#40B0E0")
        static let ouraDeep    = (light: "#12374A", dark: "#206080")
        static let garminAwake = (light: "#EF52E3", dark: "#F26FE8")
        static let garminREM   = (light: "#D91EC7", dark: "#E22DD0")
        static let garminLight = (light: "#3099F0", dark: "#4AA6F2")
        static let garminDeep  = (light: "#2168C5", dark: "#2472D8")

        /// Oura's ramp in STAGE order (awake → rem → light → deep), which is not the same as luminance
        /// order — Garmin's `light` is lighter than its `rem`. What the light pass must preserve is each
        /// ramp's OWN luminance order, whatever it is; that is what the tests assert.
        static let oura   = [ouraAwake, ouraREM, ouraLight, ouraDeep]
        /// Garmin's ramp in stage order.
        static let garmin = [garminAwake, garminREM, garminLight, garminDeep]
    }

    /// A sleep-stage colour in a chosen ramp: NOOP's own tokens, Oura's (Ribbon), or Garmin's (Garmin Fill).
    public static func sleepStageColor(_ stage: SleepStage, palette: SleepStagePalette) -> Color {
        switch palette {
        case .noop: return sleepStageColor(stage)
        case .oura:
            switch stage {
            case .awake: return oSleepAwake
            case .light: return oSleepLight
            case .deep:  return oSleepDeep
            case .rem:   return oSleepREM
            }
        case .garmin:
            switch stage {
            case .awake: return gSleepAwake
            case .light: return gSleepLight
            case .deep:  return gSleepDeep
            case .rem:   return gSleepREM
            }
        }
    }

    // MARK: - Linear gradient stop interpolation

    /// Interpolate a set of gradient stops at a normalized position 0...1.
    /// Clamps out-of-range positions to the end stops.
    public static func sample(stops: [Gradient.Stop], at position: Double) -> Color {
        guard let first = stops.first else { return .clear }
        guard stops.count > 1 else { return first.color }
        let t = min(max(position, 0.0), 1.0)

        // Find the bracketing pair.
        var lower = stops[0]
        var upper = stops[stops.count - 1]
        for i in 0..<(stops.count - 1) {
            let a = stops[i]
            let b = stops[i + 1]
            if t >= a.location && t <= b.location {
                lower = a
                upper = b
                break
            }
        }
        let span = upper.location - lower.location
        let localT = span > 0 ? (t - lower.location) / span : 0
        return interpolate(lower.color, upper.color, localT)
    }

    /// Linear-interpolate two colors in sRGB space.
    static func interpolate(_ a: Color, _ b: Color, _ t: Double) -> Color {
        let ca = ColorComponentCache.components(of: a)
        let cb = ColorComponentCache.components(of: b)
        let tt = min(max(t, 0.0), 1.0)
        return Color(
            .sRGB,
            red:   ca.r + (cb.r - ca.r) * tt,
            green: ca.g + (cb.g - ca.g) * tt,
            blue:  ca.b + (cb.b - ca.b) * tt,
            opacity: ca.a + (cb.a - ca.a) * tt
        )
    }
}

// MARK: - Resolved-component memo cache
//
// PERF: `interpolate(_:_:_:)` is the leaf of ALL gradient sampling — every sparkline point, every pip
// segment, every gauge tip, every heat-strip cell calls `sample(stops:at:)` → `interpolate`, which used
// to build a fresh UIColor/NSColor and run `getRed()` on BOTH endpoints on every single call. The stop
// colours are a tiny fixed set of static `let`s, so resolving them over and over dominated the draw.
//
// This memoizes the resolved sRGB components per Color. Crucially the cache is keyed on the CURRENT
// resolved appearance as well as the Color, because the palette tokens are dynamic `Color(light:dark:)`
// providers that resolve to DIFFERENT components per light/dark — so a bare Color key would return a
// stale, wrong-scheme value after an appearance flip. Including the appearance token in the key makes
// the cache miss (and re-resolve) exactly when the scheme changes, so the output stays byte-identical to
// calling `rgbaComponents` directly. Bounded so a pathological caller can't grow it without limit.
enum ColorComponentCache {
    private static var store: [Key: (r: Double, g: Double, b: Double, a: Double)] = [:]
    private static let lock = NSLock()

    private struct Key: Hashable {
        let color: Color
        let appearance: Int
    }

    /// A small integer identifying the current resolved appearance (light vs dark), matching the trait
    /// that `UIColor(color)` / `NSColor(color)` resolves against at this call site.
    private static var appearanceToken: Int {
        #if os(watchOS)
        // No UITraitCollection on watchOS; the watch app is always dark, so the cache key is constant.
        return 1
        #elseif canImport(UIKit)
        return UITraitCollection.current.userInterfaceStyle == .dark ? 1 : 0
        #elseif canImport(AppKit)
        let match = NSAppearance.currentDrawing().bestMatch(from: [.aqua, .darkAqua])
        return match == .darkAqua ? 1 : 0
        #else
        return 0
        #endif
    }

    static func components(of color: Color) -> (r: Double, g: Double, b: Double, a: Double) {
        let key = Key(color: color, appearance: appearanceToken)
        lock.lock()
        if let hit = store[key] {
            lock.unlock()
            return hit
        }
        lock.unlock()
        let resolved = color.rgbaComponents
        lock.lock()
        // Cap the cache so an adversarial stream of unique colours can't grow it unboundedly; the real
        // working set is the handful of static palette stops, so this ceiling is never hit in practice.
        if store.count > 512 { store.removeAll(keepingCapacity: true) }
        store[key] = resolved
        lock.unlock()
        return resolved
    }
}

// MARK: - Sleep stage enum (shared with Hypnogram)

public enum SleepStage: String, CaseIterable, Sendable {
    case awake
    case light
    case deep
    case rem

    /// Display label, in Health's words: light sleep is "Core", REM is translated like the other stages.
    public var label: String {
        switch self {
        case .awake: return String(localized: "Awake", bundle: .module)
        case .light: return String(localized: "Core", bundle: .module)
        case .deep:  return String(localized: "Deep", bundle: .module)
        case .rem:   return String(localized: "REM", bundle: .module)
        }
    }

    /// The row name on the stages chart: Health's own somnogram labels, shorter than `label` in some
    /// languages ("Быстрый" beside the list's "Быстрый сон").
    public var chartLabel: String {
        switch self {
        case .awake: return String(localized: "sleep.chart.awake", defaultValue: "Awake", bundle: .module)
        case .light: return String(localized: "sleep.chart.core", defaultValue: "Core", bundle: .module)
        case .deep:  return String(localized: "sleep.chart.deep", defaultValue: "Deep", bundle: .module)
        case .rem:   return String(localized: "sleep.chart.rem", defaultValue: "REM", bundle: .module)
        }
    }

    /// Vertical band order (top = awake, bottom = deep) for hypnogram layout.
    public var bandRank: Int {
        switch self {
        case .awake: return 0
        case .rem:   return 1
        case .light: return 2
        case .deep:  return 3
        }
    }
}

// MARK: - Color component extraction

extension Color {
    /// Resolve to sRGB RGBA components in 0...1. Works on macOS 13+ via platform color bridge.
    var rgbaComponents: (r: Double, g: Double, b: Double, a: Double) {
        #if canImport(AppKit)
        let ns = NSColor(self).usingColorSpace(.sRGB) ?? NSColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ns.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
        #elseif canImport(UIKit)
        let ui = UIColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
        #else
        return (0, 0, 0, 1)
        #endif
    }
}

#if DEBUG
#Preview("Palette") {
    ScrollView {
        VStack(alignment: .leading, spacing: 24) {
            swatchRow("Surfaces", [
                ("base", StrandPalette.surfaceBase),
                ("raised", StrandPalette.surfaceRaised),
                ("overlay", StrandPalette.surfaceOverlay),
                ("inset", StrandPalette.surfaceInset),
                ("hairline", StrandPalette.hairline),
                ("hairline.strong", StrandPalette.hairlineStrong),
            ])
            swatchRow("Text", [
                ("primary", StrandPalette.textPrimary),
                ("secondary", StrandPalette.textSecondary),
                ("tertiary", StrandPalette.textTertiary),
            ])
            swatchRow("Accent", [
                ("accent", StrandPalette.accent),
                ("hover", StrandPalette.accentHover),
                ("muted", StrandPalette.accentMuted),
            ])
            swatchRow("Gold", [
                ("gold", StrandPalette.gold),
                ("light", StrandPalette.goldLight),
                ("deep", StrandPalette.goldDeep),
                ("deepText", StrandPalette.goldDeepText),
                ("signal", StrandPalette.signalYellow),
            ])
            swatchRow("Titanium", [
                ("top", StrandPalette.titaniumTop),
                ("mid", StrandPalette.titaniumMid),
                ("low", StrandPalette.titaniumLow),
                ("deep", StrandPalette.titaniumDeep),
            ])
            VStack(alignment: .leading, spacing: 8) {
                Text("RECOVERY GRADIENT").font(.caption).foregroundStyle(StrandPalette.textTertiary)
                LinearGradient(gradient: StrandPalette.recoveryGradient, startPoint: .leading, endPoint: .trailing)
                    .frame(height: 36).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("STRAIN RAMP").font(.caption).foregroundStyle(StrandPalette.textTertiary)
                LinearGradient(gradient: StrandPalette.strainGradient, startPoint: .leading, endPoint: .trailing)
                    .frame(height: 36).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            swatchRow("Sleep stages", [
                ("awake", StrandPalette.sleepAwake),
                ("light", StrandPalette.sleepLight),
                ("deep", StrandPalette.sleepDeep),
                ("REM", StrandPalette.sleepREM),
            ])
            swatchRow("HR zones", [
                ("Z1", StrandPalette.zone1), ("Z2", StrandPalette.zone2),
                ("Z3", StrandPalette.zone3), ("Z4", StrandPalette.zone4),
                ("Z5", StrandPalette.zone5),
            ])
        }
        .padding(24)
    }
    .frame(width: 520, height: 760)
    .background(StrandPalette.surfaceBase)
    .preferredColorScheme(.dark)
}

@ViewBuilder
private func swatchRow(_ title: String, _ items: [(String, Color)]) -> some View {
    VStack(alignment: .leading, spacing: 8) {
        Text(title.uppercased())
            .font(.caption)
            .foregroundStyle(StrandPalette.textTertiary)
        HStack(spacing: 10) {
            ForEach(items, id: \.0) { name, color in
                VStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(color)
                        .frame(width: 64, height: 48)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(StrandPalette.hairline, lineWidth: 1))
                    Text(name).font(.system(size: 9)).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }
}
#endif
