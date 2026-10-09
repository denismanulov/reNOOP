import SwiftUI

/// The data-visualisation colour style: the brand "Titanium & Gold" data ramps, or a "Classic"
/// throwback — the recognizable red → amber → green readiness scale (cool→hot zones, green→red stress,
/// purple REM) that health apps have always used. Works in BOTH light and dark. It only re-colours the
/// DATA encodings (gauge rings, charts, sparklines, scales, stage bands) — never the chrome/surfaces.
///
/// Read globally via `StrandPalette.chartStyle` (set from `@AppStorage(ChartStyle.storageKey)` at the
/// app root); the data-ramp accessors in `StrandPalette` branch on it. The app root keys its content on
/// the raw value so a flip re-renders the visible charts live.
public enum ChartStyle: String, CaseIterable, Identifiable, Sendable {
    case titanium   // brand: gold recovery, amber strain, blue rest
    case classic    // throwback: red→green recovery, cool→hot zones, green→red stress

    public var id: String { rawValue }
    public static let storageKey = "chart.style"

    public var label: String {
        switch self {
        case .titanium: return String(localized: "Default", bundle: .module)
        case .classic:  return String(localized: "Classic", bundle: .module)
        }
    }

    public static func resolve(_ raw: String) -> ChartStyle { ChartStyle(rawValue: raw) ?? .titanium }
}

/// Which stage-colour ramp a sleep chart draws with: NOOP's own tokens, Oura's ramp, or Garmin's. Apple
/// only: Android dropped its in-app sleep-chart styles with the Material 3 port.
public enum SleepStagePalette: String, Sendable { case noop, oura, garmin }

/// Applies the chart style: sets the global `StrandPalette.chartStyle` (read by the data-ramp
/// accessors) AND keys the content on the raw value so a flip re-renders the visible charts. The
/// global is set during body evaluation, before the keyed content renders, so the new ramps are live
/// on the rebuild. Apply at each app root: `.chartStyle(chartStyleRaw)`.
public extension View {
    func chartStyle(_ raw: String) -> some View {
        StrandPalette.chartStyle = ChartStyle.resolve(raw)
        return self.id("noop.chartStyle.\(raw)")
    }
}

/// The user's CHROME accent colour (buttons, links, focus rings, selected states). Only the chrome
/// accent — never the recovery/strain/sleep DATA colour worlds (those follow `ChartStyle`). Read globally
/// via `StrandPalette.accentChoice` (+ `StrandPalette.customAccentHex` for `.custom`), set from
/// `@AppStorage(AccentColor.storageKey)` / `@AppStorage(AccentColor.customHexKey)` at the app root; the
/// `accent`/`accentHover`/`accentMuted`/`focusRing` accessors in `StrandPalette` branch on it. Mirror in
/// Kotlin via `Palette.accentChoice` + `NoopPrefs.accentColor`/`accentCustomHex`.
public enum AccentColor: String, CaseIterable, Identifiable, Sendable {
    case mint        // the brand default (#1068 NoopVisualStyle.mint world)
    case whoopBlue   // the classic WHOOP link blue
    case custom      // a user-picked colour (hex stored separately)
    case system      // the system blue, resolved by the OS so Increase Contrast darkens it (CR-2)

    public var id: String { rawValue }
    public static let storageKey = "accent.color"
    /// The custom colour's hex, kept separate so switching away from `.custom` and back keeps the choice.
    public static let customHexKey = "accent.customHex"
    /// Seeds the custom picker (mint) so a fresh `.custom` selection is not black.
    public static let defaultCustomHex = "#149A78"

    public var label: String {
        switch self {
        case .mint:      return String(localized: "Mint", bundle: .module)
        case .whoopBlue: return String(localized: "WHOOP Blue", bundle: .module)
        case .custom:    return String(localized: "Custom", bundle: .module)
        case .system:    return String(localized: "Blue", bundle: .module)
        }
    }

    public static func resolve(_ raw: String) -> AccentColor { AccentColor(rawValue: raw) ?? .mint }

    /// The chrome accent. `.custom` resolves the stored hex at read time.
    public var accent: Color {
        switch self {
        case .mint:      return NoopVisualStyle.mint
        case .whoopBlue: return Color(light: "#234F9E", dark: "#60A0E0")
        case .custom:    return Color(hex: StrandPalette.customAccentHex)
        case .system:    return .blue
        }
    }

    /// The brighter hover/pressed accent. For `.custom` it is the chosen colour lightened toward white.
    public var accentHover: Color {
        switch self {
        case .mint:      return NoopVisualStyle.mintGlow
        case .whoopBlue: return Color(light: "#3A6FC0", dark: "#8FBEEC")
        case .custom:    return AccentColor.lighten(StrandPalette.customAccentHex)
        case .system:    return .blue
        }
    }

    /// A low-opacity tint of the accent for muted fills (chips, selected rows). Translucent so it
    /// composites over whatever surface is behind it — the same 0.18 the mint world uses.
    public var accentMuted: Color {
        switch self {
        case .mint:      return NoopVisualStyle.mintDeep.opacity(0.18)
        case .whoopBlue: return Color(light: "#234F9E", dark: "#60A0E0").opacity(0.18)
        case .custom:    return Color(hex: StrandPalette.customAccentHex).opacity(0.18)
        case .system:    return Color.blue.opacity(0.18)
        }
    }

    public var focusRing: Color { accent }

    /// Blend an sRGB hex toward white by `amount` (0…1) — the deterministic "hover" derivation for a
    /// custom accent, so a single picked colour still gets a sensible brighter pressed state.
    static func lighten(_ hex: String, by amount: Double = 0.24) -> Color {
        let c = Color.sRGBComponents(hex: hex)
        func up(_ x: Double) -> Double { min(1, x + (1 - x) * amount) }
        return Color(.sRGB, red: up(c.r), green: up(c.g), blue: up(c.b), opacity: 1)
    }
}

/// Applies the chrome accent: sets `StrandPalette.accentChoice` + `customAccentHex` (read by the accent
/// accessors) and keys the content so a change re-renders live. Apply at each app root:
/// `.noopAccent(accentRaw, customHex: accentCustomHex)`.
public extension View {
    func noopAccent(_ raw: String, customHex: String) -> some View {
        StrandPalette.accentChoice = AccentColor.resolve(raw)
        StrandPalette.customAccentHex = customHex
        return self.id("noop.accent.\(raw).\(customHex)")
    }
}

/// The user's appearance preference for the whole app. Persisted via
/// `@AppStorage(AppearanceMode.storageKey)`. `.system` follows the OS (the default);
/// `.light` / `.dark` force a scheme regardless of the system setting.
///
/// Applied once at each app root via `.preferredColorScheme(mode.colorScheme)`. Because every
/// `StrandPalette` token is a dynamic `Color(light:dark:)`, flipping this re-resolves the entire
/// UI automatically — no per-view plumbing.
public enum AppearanceMode: String, CaseIterable, Identifiable, Sendable {
    case system
    case light
    case dark

    public var id: String { rawValue }

    /// The @AppStorage key shared by the app roots and the Settings picker.
    public static let storageKey = "theme.appearance"

    /// Human label for the Settings control.
    ///
    /// `.light` carries an EXPLICIT key rather than using its English text as the key, because
    /// "Light" is also the name of a SLEEP STAGE (`Palette.swift`, Awake / Light / Deep / REM) and a
    /// catalogue key IS its English string, so both shared one entry and one translation. Every
    /// locale then had to pick a meaning, and each picked the sleep one: French offered "Léger"
    /// (lightweight) as an appearance, Portuguese and Polish offered "Luz" and "Światło"
    /// (illumination), and Chinese offered 浅睡, "shallow sleep", as a theme. No translation could
    /// fix that while one key served both. `.dark` and `.system` are unambiguous and keep theirs.
    public var label: String {
        switch self {
        case .system: return String(localized: "System", bundle: .module)
        case .light:  return String(localized: "appearance.light", defaultValue: "Light", bundle: .module)
        case .dark:   return String(localized: "Dark", bundle: .module)
        }
    }

    /// SF Symbol for the Settings control.
    public var symbol: String {
        switch self {
        case .system: return "circle.lefthalf.filled"
        case .light:  return "sun.max"
        case .dark:   return "moon.stars"
        }
    }

    /// The `ColorScheme` to force, or `nil` to follow the system (the `.system` case).
    public var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light:  return .light
        case .dark:   return .dark
        }
    }

    /// Resolve a stored raw value (tolerant of an unknown/missing value → `.system`).
    public static func resolve(_ raw: String) -> AppearanceMode {
        AppearanceMode(rawValue: raw) ?? .system
    }
}

/// The day-cycle sky backdrop behind the scaffolded screens. Default ON. Some people find it distracting
/// and want a plain canvas (#698), so the app's `LiquidScaffoldSky` reads this via
/// `@AppStorage(SceneBackgroundPrefs.enabledKey)` and renders nothing when it is OFF, leaving the opaque
/// `surfaceBase`. Mirror in Kotlin via `NoopPrefs.showDayCycleBackground`.
public enum SceneBackgroundPrefs {
    /// The @AppStorage key. Default value is `true`.
    public static let enabledKey = "noop.showDayCycleBackground"
}

/// Card-surface opacity as a PERCENT (0 = fully see-through, 100 = solid; default 100). `FrostedCardSurface`
/// reads it via `@AppStorage(CardAppearancePrefs.opacityKey)` and fades the whole glass by it, so cards
/// (Heart Rate, Key Metrics, Recovery Vitals, …) can be made see-through from Settings → Appearance; the
/// card content stays fully readable. Mirror in Kotlin via `NoopPrefs.cardOpacityPercent`.
public enum CardAppearancePrefs {
    public static let opacityKey = "noop.cardOpacityPercent"
    public static let defaultPercent = 100
}

/// "Sky behind cards": extend the day-cycle sky behind the WHOLE scaffold scroll (not just the top band)
/// so the Card-transparency setting reveals it under every card. Read by the app's `LiquidScaffoldSky`
/// via `@AppStorage(SkyBehindCardsPrefs.enabledKey)`. Mirror in
/// Kotlin via `NoopPrefs.skyBehindCards`.
public enum SkyBehindCardsPrefs {
    public static let enabledKey = "noop.skyBehindCards"
}

/// Custom background image (#custom-background): a user-picked photo drawn full-bleed behind every screen,
/// REPLACING the day-cycle sky when enabled (precedence: image > sky > plain canvas). The image itself is
/// a device-local file (Application Support on Apple, `filesDir` on Android) — like the avatar it is
/// deliberately kept OUT of the `.noopbak` whitelist. Read in the scaffold sky provider + Today's inline
/// sky. Mirror in Kotlin via `NoopPrefs.backgroundImageEnabled` / `.backgroundFillMode` /
/// `.backgroundImagePresent` — the three key strings are byte-identical across platforms.
public enum BackgroundImagePrefs {
    /// Master gate — when true AND an image is present, the custom image overrides the sky. Default false.
    public static let enabledKey = "noop.backgroundImageEnabled"
    /// The `BackgroundFillMode` rawValue. Default `"fill"`.
    public static let fillModeKey = "noop.backgroundFillMode"
    /// Whether a background image file has been stored (so the UI can offer Remove and the provider can
    /// skip a decode when absent). Default false.
    public static let presentKey = "noop.backgroundImagePresent"
    /// The recent-images list (MRU, up to 3), serialized as `"<file>,<fillMode>;<file>,<fillMode>;…"`.
    /// Device-local like the image files — the filenames differ per device, so only the KEY is shared,
    /// not the value. Default `""`.
    public static let recentsKey = "noop.backgroundRecents"
}

/// How a custom background image is scaled to the screen. RawValues are byte-identical to the Kotlin
/// `BackgroundFillMode` twin so a backup/restore (if ever whitelisted) would read the same, and the two
/// platforms map them onto the same intent: fill → aspect-fill/crop, fit → aspect-fit, stretch →
/// no-aspect fill, tile → repeat.
public enum BackgroundFillMode: String, CaseIterable, Identifiable, Sendable {
    case fill, fit, stretch, tile

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .fill:    return String(localized: "Fill", bundle: .module)
        case .fit:     return String(localized: "Fit", bundle: .module)
        case .stretch: return String(localized: "Stretch", bundle: .module)
        case .tile:    return String(localized: "Tile", bundle: .module)
        }
    }

    /// Tolerant parse — an unknown/legacy rawValue falls back to `.fill` (the default).
    public static func resolve(_ raw: String) -> BackgroundFillMode { BackgroundFillMode(rawValue: raw) ?? .fill }
}
