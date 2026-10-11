//  MetricHealthStyle.swift
//  NOOP · Metric page — how one catalog metric looks on its page: its hue, its chart, its figure split
//  into number and unit, and the plain-language "About" text behind its ⓘ.

import SwiftUI
import StrandDesign
import StrandAnalytics

enum MetricHealthStyle {

    enum Mark: Equatable {
        /// One bar per day (or per week / month average), from zero.
        case bars
        /// One hollow ring per reading, unjoined — nightly vitals, as Health's Vitals draws them.
        case dots
        /// Rings joined by a line — slow-moving measures read as a trend.
        case line
        /// Bars up or down from zero — a signed difference from the reader's baseline.
        case diverging
    }

    /// How one metric's chart reads best: its mark, the scale it is read on, whether the period's average
    /// is drawn across it, and what colours a single bar.
    struct Chart {
        var mark: Mark
        /// A fixed y scale (0–100 for scores, 90–100 % for blood oxygen), widened only if a reading falls
        /// outside it; nil fits the scale to the readings.
        var domain: ClosedRange<Double>? = nil
        var showsAverage = false
        /// A bar's own hue by its value (Charge by its state, skin temperature by its sign); nil = the metric's hue.
        var barTint: ((Double) -> Color)? = nil
        /// Dashed hairlines where the bars change hue, each named, so the state is not told by colour alone.
        var thresholds: [Threshold] = []
        /// The state word a reading carries (Charge's), read out with a picked mark; nil = none.
        var stateWord: ((Double) -> String)? = nil
    }

    /// A dashed hairline across the chart where a band ends; the axis carries its value.
    struct Threshold {
        var value: Double
    }

    // MARK: Identity

    /// The Summary card a metric appears as, when it can be pinned there.
    static func keyMetric(for key: String) -> KeyMetric? {
        switch key {
        case "recovery": return .charge
        case "strain": return .effort
        case "sleep_performance": return .rest
        case "hrv": return .hrv
        case "rhr": return .restingHr
        case "spo2": return .bloodOxygen
        case "resp_rate": return .respiratory
        case "steps", "steps_est": return .steps
        case "weight": return .weight
        case "energy_kcal", "active_kcal": return .calories
        case "skin_temp": return .skinTemp
        default: return nil
        }
    }

    /// The Health app's category hue: Heart pink-red, Respiratory teal / blue, Activity orange, Sleep
    /// indigo, Body Measurements purple, Nutrition green, Mind teal. Charge, Effort and Rest keep their
    /// Summary rings' hues on every screen: Move red, Exercise green, Stand cyan.
    static func tint(_ metric: MetricDescriptor) -> Color {
        switch metric.key {
        case "recovery": return StrandPalette.activityMoveStart
        case "strain": return StrandPalette.activityExerciseStart
        case "sleep_performance": return StrandPalette.activityStandStart
        case "resp_rate": return StrandPalette.healthRespiratory
        case "spo2": return StrandPalette.healthOxygen
        case "skin_temp": return StrandPalette.healthTemperature
        case "weight", "body_fat", "lean_mass", "bmi": return StrandPalette.healthBody
        case "stress", "mood": return StrandPalette.healthMind
        default: break
        }
        switch metric.category {
        case "Heart", "Charge": return StrandPalette.healthHeart
        case "Rest": return StrandPalette.sleepSchedule
        case "Effort": return StrandPalette.activityTitle
        case "Nutrition": return StrandPalette.healthNutrition
        case "Mind": return StrandPalette.healthMind
        default: return StrandPalette.healthBody
        }
    }

    static func chart(_ metric: MetricDescriptor, series: [(day: String, value: Double)]) -> Chart {
        switch metric.key {
        case "recovery":
            // Banded on the Charge state words' own thresholds (`StrandPalette.recoveryState`), each
            // threshold drawn and named on the chart and the state word read out with a picked bar.
            return Chart(mark: .bars, domain: 0...100, barTint: { v in
                v < 50 ? StrandPalette.healthZoneLow : v < 70 ? StrandPalette.healthZoneMid : StrandPalette.healthZoneHigh
            }, thresholds: [
                Threshold(value: 50),
                Threshold(value: 70),
            ], stateWord: { StrandPalette.recoveryState($0) })
        case "strain":
            return Chart(mark: .bars, domain: 0...100)
        case "sleep_performance", "sleep_score", "hours_vs_needed_pct", "sleep_consistency", "restorative_pct",
             "sleep_efficiency":
            return Chart(mark: .bars, domain: 0...100)
        case "spo2":
            return Chart(mark: .dots, domain: 90...100)
        case "hrv", "resp_rate":
            return Chart(mark: .dots, showsAverage: true)
        case "rhr", "avg_hr", "max_hr":
            return Chart(mark: .line, showsAverage: true)
        case "skin_temp":
            // A deviation series reads as warmer / cooler than baseline; an absolute one as a trend.
            let deviation = !series.isEmpty && series.allSatisfy { !VitalBands.isAbsoluteSkinTemp($0.value) }
            return deviation
                ? Chart(mark: .diverging, barTint: { $0 >= 0 ? StrandPalette.healthTemperature : StrandPalette.healthOxygen })
                : Chart(mark: .line)
        case "weight", "body_fat", "lean_mass", "bmi", "vo2max", "vo2max_est", "fitness_age", "body_age", "vitality":
            return Chart(mark: .line)
        case "mood":
            return Chart(mark: .dots, domain: 1...5)
        case "stress":
            return Chart(mark: .bars, domain: 0...(metric.unit == "/3" ? 3 : 100))
        case "steps", "steps_est", "energy_kcal", "active_kcal", "calories_in", "protein_g", "carbs_g", "fat_g":
            return Chart(mark: .bars, showsAverage: true)
        default:
            return metric.unit == "min" ? Chart(mark: .bars, showsAverage: true) : Chart(mark: .dots)
        }
    }

    static func isSteps(_ metric: MetricDescriptor) -> Bool { metric.key == "steps" || metric.key == "steps_est" }

    // MARK: Figures

    /// One run of a figure: a number (large) or a unit (small, secondary) — "7 hr 12 min" is four.
    struct Token: Equatable {
        let text: String
        let isUnit: Bool
    }

    struct Units {
        var system: UnitSystem
        var temperature: TemperatureUnit
        var effortScale: EffortScale
    }

    static func tokens(_ metric: MetricDescriptor, _ value: Double, units: Units) -> [Token] {
        let locale = AppLanguage.activeLocale
        if metric.unit == "min" {
            let total = Int(value.rounded())
            let h = total / 60, m = total % 60
            var out: [Token] = []
            if h > 0 {
                out += [Token(text: "\(h)", isUnit: false),
                        Token(text: String(localized: "sleep.unit.hr", defaultValue: "hr"), isUnit: true)]
            }
            if m > 0 || h == 0 {
                out += [Token(text: "\(m)", isUnit: false),
                        Token(text: String(localized: "sleep.unit.min", defaultValue: "min"), isUnit: true)]
            }
            return out
        }
        if metric.key == "strain" {
            return [Token(text: number(UnitFormatter.effortValue(value, scale: units.effortScale), decimals: 1,
                                       locale: locale), isUnit: false),
                    Token(text: metric.displayUnit(effortScale: units.effortScale), isUnit: true)]
        }
        if metric.unit == "°C" {
            // Skin temperature can be an absolute or a signed deviation; its formatter owns both (the sign,
            // the °F conversion), and only the decimal separator follows the reader's locale.
            let kind = SkinTempDisplay.kind(of: value)
            let fahrenheit = units.temperature == .fahrenheit
            let n = SkinTempDisplay.numberString(value, kind: kind, fahrenheit: fahrenheit, decimals: metric.decimals)
            // A deviation reads as Health writes one, "+0.4 °C": the sign marks it as a move from baseline
            // (the page's header names it "Deviation"), so the unit is plain degrees rather than "Δ°C".
            return [Token(text: n.replacingOccurrences(of: ".", with: locale.decimalSeparator ?? "."), isUnit: false),
                    Token(text: SkinTempDisplay.unitSymbol(kind: .absolute, fahrenheit: fahrenheit), isUnit: true)]
        }
        if isSteps(metric) {
            return [Token(text: number(value, decimals: 0, locale: locale), isUnit: false),
                    Token(text: String(localized: "steps"), isUnit: true)]
        }
        var shown = value
        var unit = metric.unit
        if unit == "kg" {
            if units.system == .imperial { shown = UnitFormatter.kgToPounds(value) }
            unit = UnitFormatter.massUnit(units.system)
        }
        var out = [Token(text: number(shown, decimals: metric.decimals, locale: locale), isUnit: false)]
        if !unit.isEmpty {
            out.append(Token(text: String(localized: String.LocalizationValue(unit)), isUnit: true))
        }
        return out
    }

    /// The figure as one string, for lists and VoiceOver.
    static func text(_ metric: MetricDescriptor, _ value: Double, units: Units) -> String {
        tokens(metric, value, units: units).map(\.text).joined(separator: " ")
    }

    /// A y-axis tick: the number alone (whole numbers without a decimal), durations in hours once they
    /// pass two.
    static func axisLabel(_ metric: MetricDescriptor, _ value: Double, units: Units, span: Double) -> String {
        let locale = AppLanguage.activeLocale
        if metric.unit == "min" && span >= 120 {
            return "\(number(value / 60, decimals: 0, locale: locale)) \(String(localized: "sleep.unit.hr", defaultValue: "hr"))"
        }
        if metric.key == "strain", units.effortScale != .hundred {
            return UnitFormatter.effortDisplay(value, scale: units.effortScale)
        }
        var shown = value
        if metric.unit == "kg", units.system == .imperial { shown = UnitFormatter.kgToPounds(value) }
        if metric.unit == "°C", units.temperature == .fahrenheit {
            shown = VitalBands.isAbsoluteSkinTemp(value) ? value * 9 / 5 + 32 : value * 9 / 5
        }
        return number(shown, decimals: abs(shown - shown.rounded()) < 1e-6 ? 0 : 1, locale: locale)
    }

    static func number(_ value: Double, decimals: Int, locale: Locale) -> String {
        value.formatted(.number.precision(.fractionLength(decimals)).grouping(.automatic).locale(locale))
    }

    // MARK: About

    /// The page's "About" text: what the number is and what moves it. nil hides the section.
    static func about(_ metric: MetricDescriptor) -> String? {
        switch metric.key {
        case "hrv":
            return String(localized: "metric.about.hrv", defaultValue: "Heart rate variability is the variation in time between your heartbeats, measured here while you sleep. It is personal: compare it with your own normal rather than with other people. It tends to rise with rest and fitness, and to fall with stress, alcohol, illness and hard training.")
        case "rhr":
            return String(localized: "metric.about.rhr", defaultValue: "Resting heart rate is your lowest steady heart rate, measured during sleep. A lower value usually reflects better cardiovascular fitness. A rise of a few beats above your normal can come with stress, alcohol, late meals, heat or the start of an illness.")
        case "resp_rate":
            return String(localized: "metric.about.resp", defaultValue: "Respiratory rate is the number of breaths you take per minute while you sleep. It is very stable from night to night, so a clear change from your normal can be an early sign that your body is under strain.")
        case "spo2":
            return String(localized: "metric.about.spo2", defaultValue: "Blood oxygen is the percentage of oxygen your red blood cells carry, measured during sleep. Most people sit between 95 and 100 percent. These readings are not intended for medical use.")
        case "skin_temp":
            return String(localized: "metric.about.skinTemp", defaultValue: "Skin temperature is measured at your wrist while you sleep and shown as a temperature or as the difference from your baseline. It moves with your cycle, the room, alcohol and illness.")
        case "steps", "steps_est":
            return String(localized: "metric.about.steps", defaultValue: "Step count is the number of steps you take throughout the day. Regular walking is one of the simplest ways to stay active.")
        case "vo2max", "vo2max_est":
            return String(localized: "metric.about.vo2max", defaultValue: "VO₂ max is the maximum amount of oxygen your body can use during exercise, in millilitres per kilogram per minute. Higher values mean better cardio fitness.")
        case "weight":
            return String(localized: "metric.about.weight", defaultValue: "Weight changes from day to day with food, water and the time you weigh yourself. The trend over weeks says more than any single reading.")
        case "energy_kcal", "active_kcal":
            return String(localized: "metric.about.energy", defaultValue: "Energy is the calories you burn through the day, estimated from your heart rate and movement.")
        case "sleep_total_min":
            return String(localized: "metric.about.asleep", defaultValue: "Time asleep is the time you actually spent asleep, not counting the time you lay awake in bed.")
        default:
            return metric.description
        }
    }
}

extension KeyMetric {
    /// The hue a pinned Summary card shares with the metric page it opens (`MetricHealthStyle.tint`).
    var healthTint: Color {
        switch self {
        case .charge: return StrandPalette.activityMoveStart
        case .effort: return StrandPalette.activityExerciseStart
        case .rest: return StrandPalette.activityStandStart
        case .hrv, .restingHr: return StrandPalette.healthHeart
        case .bloodOxygen: return StrandPalette.healthOxygen
        case .respiratory: return StrandPalette.healthRespiratory
        case .steps, .calories: return StrandPalette.activityTitle
        case .weight: return StrandPalette.healthBody
        case .skinTemp: return StrandPalette.healthTemperature
        }
    }

    /// The Health category a pinned Summary card files under, as `AllMetricsCatalog.category` files the
    /// metric page it opens.
    var healthCategory: HealthCategory {
        switch self {
        case .charge, .hrv, .restingHr: return .heart
        case .effort, .steps, .calories: return .activity
        case .rest: return .sleep
        case .bloodOxygen, .respiratory: return .respiratory
        case .weight, .skinTemp: return .bodyMeasurements
        }
    }

    /// The glyph a pinned Summary card carries: its category's, so a metric reads the same on Summary,
    /// in All Metrics and in Trends.
    var healthIcon: String { healthCategory.icon }
}
