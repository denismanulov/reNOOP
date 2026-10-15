//  SleepHistory.swift
//  NOOP · Sleep — nights as (bedtime, wake, time asleep), and the week / month / 6-month views of them.
//
//  Pure. Each night is the day's MAIN sleep group, picked by the same `SleepNightDecoding.mainNightGroup` rule
//  the Sleep tab and the analytics use, so the range charts and the day chart never disagree about
//  which block was "the night".

import Foundation
import WhoopStore

struct SleepNightEntry: Equatable {
    /// Start of the calendar day the night ended on.
    let day: Date
    let onset: Date
    let wake: Date
    let asleepMin: Double

    /// Minutes after 18:00 on the evening before `day` — a clock that runs through the night without
    /// wrapping at midnight, so bedtimes either side of it compare directly.
    var onsetOfNightMin: Double { onset.timeIntervalSince(Self.nightOrigin(day)) / 60 }
    var wakeOfNightMin: Double { wake.timeIntervalSince(Self.nightOrigin(day)) / 60 }

    static func nightOrigin(_ day: Date) -> Date { day.addingTimeInterval(-6 * 3600) }
}

enum SleepRange: String, CaseIterable, Identifiable {
    case day, week, month, sixMonths
    var id: String { rawValue }

    var label: String {
        switch self {
        case .day: return String(localized: "sleep.range.day", defaultValue: "D")
        case .week: return String(localized: "sleep.range.week", defaultValue: "W")
        case .month: return String(localized: "sleep.range.month", defaultValue: "M")
        case .sixMonths: return String(localized: "sleep.range.sixMonths", defaultValue: "6M")
        }
    }

    /// The segment as VoiceOver reads it: the whole word, not the letter.
    var spokenName: String {
        switch self {
        case .day: return String(localized: "Day")
        case .week: return String(localized: "Week")
        case .month: return String(localized: "Month")
        case .sixMonths: return String(localized: "6 Months")
        }
    }
}

/// One bar of a range chart: a night (week / month) or a week's average (6 months).
struct SleepRangeBar: Identifiable, Equatable {
    let slot: Int
    let start: Date
    let onsetMin: Double
    let wakeMin: Double
    let asleepMin: Double
    var id: Int { slot }
}

struct SleepRangeWindow: Equatable {
    let range: SleepRange
    /// First day of each slot, oldest → newest.
    let slotStarts: [Date]
    let bars: [SleepRangeBar]

    /// Mean time asleep over the slots that have data; nil when none do.
    var averageAsleepMin: Double? {
        bars.isEmpty ? nil : bars.map(\.asleepMin).reduce(0, +) / Double(bars.count)
    }
}

enum SleepHistory {
    /// Every night with sleep, oldest → newest. `navDays` is `SleepModel.navDays` (newest day first).
    static func entries(navDays: [[CachedSleepSession]], habitualMidsleepSec: Int?,
                        calendar: Calendar = .current) -> [SleepNightEntry] {
        navDays.compactMap { day -> SleepNightEntry? in
            let group = SleepNightDecoding.mainNightGroup(day, habitualMidsleepSec: habitualMidsleepSec)
            guard let last = group.last else { return nil }
            let onsetTs = SleepModel.nightOnsetTs(group)
            let asleep = group.filter { $0.effectiveStartTs >= onsetTs }
                .map { SleepNightDecoding.decodedAsleepMinutes($0.stagesJSON, effectiveStartTs: $0.effectiveStartTs) }
                .reduce(0, +)
            guard asleep > 0 else { return nil }
            let wake = Date(timeIntervalSince1970: TimeInterval(last.endTs))
            return SleepNightEntry(day: calendar.startOfDay(for: wake),
                                   onset: Date(timeIntervalSince1970: TimeInterval(onsetTs)),
                                   wake: wake, asleepMin: asleep)
        }
        .sorted { $0.day < $1.day }
    }

    /// The window ending on `anchor`'s day: 7 nights, 30 nights, or 26 weekly averages.
    static func window(_ range: SleepRange, entries: [SleepNightEntry], anchor: Date,
                       calendar: Calendar = .current) -> SleepRangeWindow {
        let today = calendar.startOfDay(for: anchor)
        let byDay = Dictionary(entries.map { ($0.day, $0) }, uniquingKeysWith: { _, newer in newer })
        switch range {
        case .day, .week, .month:
            let count = range == .month ? 30 : 7
            let starts = (0..<count).reversed().compactMap { calendar.date(byAdding: .day, value: -$0, to: today) }
            let bars = starts.enumerated().compactMap { slot, day -> SleepRangeBar? in
                guard let e = byDay[day] else { return nil }
                return SleepRangeBar(slot: slot, start: day, onsetMin: e.onsetOfNightMin,
                                     wakeMin: e.wakeOfNightMin, asleepMin: e.asleepMin)
            }
            return SleepRangeWindow(range: range, slotStarts: starts, bars: bars)
        case .sixMonths:
            guard let thisWeek = calendar.dateInterval(of: .weekOfYear, for: today)?.start else {
                return SleepRangeWindow(range: range, slotStarts: [], bars: [])
            }
            let starts = (0..<26).reversed().compactMap {
                calendar.date(byAdding: .weekOfYear, value: -$0, to: thisWeek)
            }
            let bars = starts.enumerated().compactMap { slot, weekStart -> SleepRangeBar? in
                let weekEnd = calendar.date(byAdding: .day, value: 7, to: weekStart) ?? weekStart
                let nights = entries.filter { $0.day >= weekStart && $0.day < weekEnd }
                guard !nights.isEmpty else { return nil }
                let n = Double(nights.count)
                return SleepRangeBar(slot: slot, start: weekStart,
                                     onsetMin: nights.map(\.onsetOfNightMin).reduce(0, +) / n,
                                     wakeMin: nights.map(\.wakeOfNightMin).reduce(0, +) / n,
                                     asleepMin: nights.map(\.asleepMin).reduce(0, +) / n)
            }
            return SleepRangeWindow(range: range, slotStarts: starts, bars: bars)
        }
    }
}

// MARK: - Highlights

struct SleepHighlight: Identifiable, Equatable {
    /// The two figures a Health highlight sets under its sentence, and the nights behind them.
    enum Detail: Equatable {
        /// Usual bedtime vs last night's, on the night clock (`SleepNightEntry.onsetOfNightMin`); `nights`
        /// are the compared nights then last night, oldest first, from `first` to `last`.
        case bedtime(usualMin: Double, lastMin: Double, nights: [Double], first: Date, last: Date)
    }

    let id: String
    let sentence: String
    var detail: Detail? = nil
}

enum SleepHighlights {
    /// The nights a usual bedtime is read from: a fortnight with last night, as Health draws it.
    static let bedtimeNights = 13

    /// Plain-language reads of the recent nights, Health-style. Each needs enough history to compare
    /// against; with too little it is simply left out.
    static func make(entries: [SleepNightEntry]) -> [SleepHighlight] {
        [bedtime(entries)].compactMap { $0 }
    }

    /// Last night's bedtime against the mean of up to thirteen nights before it (needs three).
    static func bedtime(_ entries: [SleepNightEntry]) -> SleepHighlight? {
        guard let last = entries.last else { return nil }
        let before = entries.dropLast().suffix(bedtimeNights)
        guard before.count >= 3, let first = before.first else { return nil }
        let usual = before.map(\.onsetOfNightMin).reduce(0, +) / Double(before.count)
        let diff = Int(((last.onsetOfNightMin - usual) / 5).rounded()) * 5
        let sentence: String
        if abs(diff) < 15 {
            sentence = String(localized: "Last night you went to bed around your usual time.")
        } else if diff > 0 {
            sentence = String(localized: "Last night you went to bed \(diff) min later than usual.")
        } else {
            sentence = String(localized: "Last night you went to bed \(-diff) min earlier than usual.")
        }
        return SleepHighlight(id: "bedtime", sentence: sentence,
                              detail: .bedtime(usualMin: usual, lastMin: last.onsetOfNightMin,
                                               nights: before.map(\.onsetOfNightMin) + [last.onsetOfNightMin],
                                               first: first.day, last: last.day))
    }

    /// The nights as the daily series the week, month and year highlights read ("yyyy-MM-dd" → minutes asleep).
    static func series(_ entries: [SleepNightEntry], calendar: Calendar = .current) -> [(day: String, value: Double)] {
        entries.map { (day: HealthHighlightMath.key($0.day, calendar: calendar), value: $0.asleepMin) }
    }
}
