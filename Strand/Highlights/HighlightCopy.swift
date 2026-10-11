//  HighlightCopy.swift
//  NOOP · Highlights — the sentence over each card and the words inside it.
//
//  The catalogue holds each sentence with `@TOKEN@` slots, since which figures a sentence names differs
//  by language (one tongue says "than last year", another "than in 2025"). Every slot a sentence may
//  carry is filled here; `tokens` is the full list, and a test holds the catalogue to it.

import Foundation

enum HighlightCopy {

    /// Every slot a catalogue sentence may carry.
    static let tokens: Set<String> = [
        "SEVEN_DAY_AVERAGE_UNIT", "THIS_YEAR", "LAST_YEAR", "WORKOUT_HR_RANGE_UNIT", "RECOVERY_HR_DELTA_UNIT",
        "HEART_RATE_RANGE_PLUS_UNIT", "MOST_RECENT_VALUE_PLUS_UNIT", "DELTA_TOTAL", "DATA_TYPE_UNIT",
        "DISPLAY_NAME", "LOW_RANGE_VALUE", "HIGH_RANGE_VALUE", "UNIT"
    ]

    /// The catalogue sentence under `key`, its slots filled.
    static func text(_ key: String, _ values: [String: String] = [:]) -> String {
        fill(String(localized: String.LocalizationValue(key)), values)
    }

    static func fill(_ sentence: String, _ values: [String: String]) -> String {
        var out = sentence
        for (name, value) in values {
            out = out.replacingOccurrences(of: "@\(name)@", with: value)
        }
        return out
    }

    /// The metrics whose highlights have sentences of their own.
    enum Kind: String {
        case steps, energy, sleep

        static func of(key: String) -> Kind? {
            switch key {
            case "steps", "steps_est": return .steps
            case "energy_kcal", "active_kcal": return .energy
            case "sleep_total_min": return .sleep
            default: return nil
            }
        }
    }

    static func word(_ direction: HighlightDirection) -> String {
        switch direction {
        case .more: return "more"
        case .less: return "less"
        case .same: return "same"
        }
    }

    // MARK: Sentences

    static func progress(_ kind: Kind, _ direction: HighlightDirection) -> String {
        text("hl.progress.\(word(direction)).\(kind.rawValue)")
    }

    /// `average` is the figure with its unit, as the sentence reads it ("8 502 steps").
    static func week(_ subject: String, average: String) -> String {
        text("hl.week.\(subject)", ["SEVEN_DAY_AVERAGE_UNIT": average])
    }

    static func weekLabel(_ subject: String) -> String { text("hl.week.label.\(subject)") }

    static func comparison(_ kind: Kind, _ comparison: HighlightComparison, calendar: Calendar) -> String {
        let period = comparison.period == .month ? "month" : "year"
        return text("hl.\(period).\(word(comparison.direction)).\(kind.rawValue)",
                    ["THIS_YEAR": year(comparison.currentStart, calendar: calendar),
                     "LAST_YEAR": year(comparison.previousStart, calendar: calendar)])
    }

    static func workoutsMonth(_ comparison: HighlightComparison) -> String {
        let now = Int(comparison.current.rounded())
        let delta = abs(now - Int(comparison.previous.rounded()))
        return text("hl.month.\(word(comparison.direction)).workouts",
                    ["MOST_RECENT_VALUE_PLUS_UNIT": "\(now) \(unit(String(localized: "hl.unit.workouts \(now)")))",
                     "DELTA_TOTAL": "\(delta)",
                     "DATA_TYPE_UNIT": unit(String(localized: "hl.unit.workouts \(delta)"))])
    }

    static func workoutHeartRate(sport: String, low: Int, high: Int) -> String {
        text("hl.hr.workout.\(sportKey(sport))", ["WORKOUT_HR_RANGE_UNIT": beatsRange(low: low, high: high)])
    }

    static func recovery(sport: String, drop: Int) -> String {
        text("hl.hr.recovery.\(sportKey(sport))",
             ["RECOVERY_HR_DELTA_UNIT": "\(drop) \(unit(String(localized: "hl.unit.bpm \(drop)")))"])
    }

    static func sleepHeartRate(low: Int, high: Int) -> String {
        text("hl.hr.sleep", ["HEART_RATE_RANGE_PLUS_UNIT": beatsRange(low: low, high: high)])
    }

    static func workoutDays(_ count: Int, weeks: Int) -> String {
        weeks == 1 ? String(localized: "hl.days.week.workouts \(count)")
                   : String(localized: "hl.days.month.workouts \(count)")
    }

    static func title(for name: String) -> String { text("hl.title.for", ["DISPLAY_NAME": name]) }

    // MARK: Pieces

    /// The unit word out of a counted unit. A catalogue plural has to name its number, so each form is
    /// stored as "%lld|steps"; the word in the right form for that number is what follows the bar.
    static func unit(_ counted: String) -> String {
        counted.split(separator: "|", maxSplits: 1).last.map(String.init) ?? counted
    }

    /// "72–166 beats per minute".
    static func beatsRange(low: Int, high: Int) -> String {
        let range = text("hl.range", ["LOW_RANGE_VALUE": "\(low)", "HIGH_RANGE_VALUE": "\(high)"])
        return "\(range) \(unit(String(localized: "hl.unit.bpm \(high)")))"
    }

    static func year(_ date: Date, calendar: Calendar) -> String {
        "\(calendar.component(.year, from: date))"
    }

    /// The sports with a sentence of their own; anything else reads "your last workout".
    static func sportKey(_ sport: String) -> String {
        switch sport.lowercased().replacingOccurrences(of: " ", with: "") {
        case "running", "run", "outdoorrun", "trailrunning": return "run"
        case "treadmill", "indoorrun", "indoorrunning": return "indoorRun"
        case "walking", "walk", "outdoorwalk": return "walk"
        case "cycling", "outdoorcycle", "biking", "mountainbiking": return "cycle"
        case "indoorcycling", "indoorcycle", "spin", "spinning": return "indoorCycle"
        case "hiking", "hike": return "hike"
        case "yoga": return "yoga"
        case "hiit", "highintensityintervaltraining": return "hiit"
        case "rowing", "rower": return "row"
        case "elliptical": return "elliptical"
        case "stairstepper", "stairs", "stairclimbing": return "stairs"
        default: return "other"
        }
    }
}
