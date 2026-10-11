//  HealthHighlight.swift
//  NOOP · Highlights — the figures behind each kind of Highlights card the Health app draws: today against
//  a usual day, a week's average, this month or year against the last, a workout's or a night's range,
//  the minutes after a workout, and the days that had one.
//
//  Pure: series and samples in, the card's numbers out. No store, no copy, no views.

import Foundation

/// How often a highlight is renewed, which is the section it is filed under on a metric's Highlights page.
enum HighlightCadence: Int, CaseIterable, Hashable {
    case daily, weekly, monthly, yearly
}

/// Which way a figure moved against the one it is read beside. Within 5 % reads as the same.
enum HighlightDirection: Equatable {
    case more, less, same

    static func of(_ value: Double, against reference: Double, tolerance: Double = 0.05) -> HighlightDirection {
        guard abs(value - reference) > tolerance * max(abs(reference), 1e-9) else { return .same }
        return value > reference ? .more : .less
    }
}

/// A point of a day's running total: how far through the day, and the total by then.
struct HighlightDayPoint: Equatable {
    /// 0 at midnight, 1 at the next.
    let fraction: Double
    let total: Double
}

/// Today's running total against the usual one by this time of day.
struct HighlightProgress: Equatable {
    /// Midnight to now.
    let today: [HighlightDayPoint]
    /// Midnight to midnight, the mean of the days compared.
    let typical: [HighlightDayPoint]
    let nowFraction: Double
    let todayTotal: Double
    /// The usual total by now.
    let typicalTotal: Double
    let direction: HighlightDirection
}

/// Seven days side by side with their mean drawn across.
struct HighlightSevenDay: Equatable {
    /// Oldest first; nil is a day with no reading.
    let values: [Double?]
    let days: [Date]
    /// The mean of the days on record.
    let average: Double
}

/// One period's figure against the period before it.
struct HighlightComparison: Equatable {
    enum Period: Equatable { case month, year }
    let period: Period
    let current: Double
    let previous: Double
    /// The first day of each period, for its name ("October", "2026").
    let currentStart: Date
    let previousStart: Date
    let direction: HighlightDirection
}

/// A stretch of time cut into even slices, each with the lowest and highest reading inside it.
struct HighlightRange: Equatable {
    struct Slice: Equatable {
        let low: Double
        let high: Double
    }
    /// nil is a slice with no reading.
    let slices: [Slice?]
    let start: Date
    let end: Date
    let minimum: Double
    let maximum: Double
    /// The slices holding the lowest and the highest reading.
    let minimumIndex: Int
    let maximumIndex: Int
}

/// Heart rate over the three minutes after a workout ended.
struct HighlightRecovery: Equatable {
    struct Reading: Equatable {
        /// Seconds after the workout ended.
        let seconds: Double
        let bpm: Double
    }
    let end: Date
    let readings: [Reading]
    let atEnd: Int
    let afterOneMinute: Int
    let afterTwoMinutes: Int
    let afterThreeMinutes: Int

    var drop: Int { atEnd - afterThreeMinutes }
}

/// Whole weeks of days, each marked as having the thing or not.
struct HighlightConsistency: Equatable {
    enum Mark: Equatable { case done, missed, ahead }
    struct Day: Equatable {
        let date: Date
        let mark: Mark
    }
    /// Whole weeks, oldest first, each starting on the calendar's first weekday.
    let days: [Day]
    let count: Int
    let today: Date
}

enum HighlightBody: Equatable {
    case progress(HighlightProgress)
    case sevenDay(HighlightSevenDay)
    case comparison(HighlightComparison)
    case range(HighlightRange)
    case recovery(HighlightRecovery)
    case consistency(HighlightConsistency)
}

enum HealthHighlightMath {

    /// Days compared for a "usual day"; a usual day needs this many of them on record.
    static let typicalDays = 14
    static let minimumTypicalDays = 3

    // MARK: Today against a usual day

    /// `todayHourly` and each row of `priorHourly` are 24 hourly amounts (not running totals). Hours that
    /// have not begun are ignored. A prior day with nothing in it is a day off the wrist, not a day of
    /// zero, so it is left out of the mean.
    static func progress(todayHourly: [Double], priorHourly: [[Double]], now: Date,
                         calendar: Calendar) -> HighlightProgress? {
        guard todayHourly.count == 24 else { return nil }
        let days = priorHourly.filter { $0.count == 24 && $0.reduce(0, +) > 0 }
        guard days.count >= minimumTypicalDays else { return nil }

        let midnight = calendar.startOfDay(for: now)
        let nowHours = min(24, max(0, now.timeIntervalSince(midnight) / 3600))
        let nowFraction = nowHours / 24

        var typical = [HighlightDayPoint(fraction: 0, total: 0)]
        var running = 0.0
        for hour in 0..<24 {
            running += days.reduce(0) { $0 + $1[hour] } / Double(days.count)
            typical.append(HighlightDayPoint(fraction: Double(hour + 1) / 24, total: running))
        }
        guard running > 0 else { return nil }

        var today = [HighlightDayPoint(fraction: 0, total: 0)]
        var todayTotal = 0.0
        let whole = Int(nowHours)
        for hour in 0..<min(whole, 24) {
            todayTotal += todayHourly[hour]
            today.append(HighlightDayPoint(fraction: Double(hour + 1) / 24, total: todayTotal))
        }
        if whole < 24, nowHours > Double(whole) {
            // The hour in progress holds everything counted in it so far.
            todayTotal += todayHourly[whole]
            today.append(HighlightDayPoint(fraction: nowFraction, total: todayTotal))
        }

        let typicalTotal = total(of: typical, at: nowFraction)
        return HighlightProgress(today: today, typical: typical, nowFraction: nowFraction, todayTotal: todayTotal,
                                 typicalTotal: typicalTotal,
                                 direction: .of(todayTotal, against: typicalTotal, tolerance: 0.1))
    }

    /// The running total at a point of the day, read off the line between the two points around it.
    static func total(of points: [HighlightDayPoint], at fraction: Double) -> Double {
        guard let first = points.first else { return 0 }
        var before = first
        for point in points {
            if point.fraction >= fraction {
                let span = point.fraction - before.fraction
                guard span > 0 else { return point.total }
                return before.total + (point.total - before.total) * (fraction - before.fraction) / span
            }
            before = point
        }
        return before.total
    }

    // MARK: Seven days

    /// The seven days ending on `lastDay`. Needs three of them on record.
    static func sevenDay(series: [(day: String, value: Double)], lastDay: Date,
                         calendar: Calendar) -> HighlightSevenDay? {
        let byDay = Dictionary(series.map { ($0.day, $0.value) }, uniquingKeysWith: { _, last in last })
        let end = calendar.startOfDay(for: lastDay)
        var days: [Date] = []
        var values: [Double?] = []
        for back in stride(from: 6, through: 0, by: -1) {
            guard let day = calendar.date(byAdding: .day, value: -back, to: end) else { return nil }
            days.append(day)
            values.append(byDay[key(day, calendar: calendar)])
        }
        let recorded = values.compactMap { $0 }
        guard recorded.count >= 3 else { return nil }
        return HighlightSevenDay(values: values, days: days, average: recorded.reduce(0, +) / Double(recorded.count))
    }

    // MARK: This period against the last

    /// The daily mean of the month (or year) `today` is in against the one before it. `total` compares
    /// the sums instead (a count of workouts). Each period needs enough days on record to stand for it:
    /// three of a month, fourteen of a year.
    static func comparison(series: [(day: String, value: Double)], period: HighlightComparison.Period,
                           today: Date, calendar: Calendar, total: Bool = false) -> HighlightComparison? {
        let unit: Calendar.Component = period == .month ? .month : .year
        guard let current = calendar.dateInterval(of: unit, for: today),
              let before = calendar.date(byAdding: unit, value: -1, to: current.start),
              let previous = calendar.dateInterval(of: unit, for: before) else { return nil }
        let dayEnd = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: today)) ?? today
        var now: [Double] = []
        var then: [Double] = []
        for row in series {
            guard let date = date(row.day, calendar: calendar) else { continue }
            if date >= current.start && date < dayEnd {
                now.append(row.value)
            } else if date >= previous.start && date < previous.end {
                then.append(row.value)
            }
        }
        let needed = total ? 1 : (period == .month ? 3 : 14)
        guard now.count >= needed, then.count >= needed else { return nil }
        let a = total ? now.reduce(0, +) : now.reduce(0, +) / Double(now.count)
        let b = total ? then.reduce(0, +) : then.reduce(0, +) / Double(then.count)
        return HighlightComparison(period: period, current: a, previous: b, currentStart: current.start,
                                   previousStart: previous.start, direction: .of(a, against: b))
    }

    // MARK: A range over time

    /// The slices a stretch is cut into: about three minutes each for a workout, half an hour for a night.
    static let targetSlices = 18

    /// `samples` are readings with their own low and high (a raw sample has both equal). The stretch is
    /// cut into slices of a round length; needs readings in four of them.
    static func range(samples: [(ts: Int, low: Double, high: Double)], start: Int, end: Int) -> HighlightRange? {
        guard end > start else { return nil }
        let span = end - start
        let length = sliceLength(span: span)
        let count = max(1, Int((Double(span) / Double(length)).rounded(.up)))
        var slices = [HighlightRange.Slice?](repeating: nil, count: count)
        for sample in samples where sample.ts >= start && sample.ts < end && sample.high > 0 {
            let index = min(count - 1, (sample.ts - start) / length)
            if let slice = slices[index] {
                slices[index] = .init(low: min(slice.low, sample.low), high: max(slice.high, sample.high))
            } else {
                slices[index] = .init(low: sample.low, high: sample.high)
            }
        }
        let filled = slices.enumerated().compactMap { index, slice in slice.map { (index: index, slice: $0) } }
        guard filled.count >= 4,
              let lowest = filled.min(by: { $0.slice.low < $1.slice.low }),
              let highest = filled.max(by: { $0.slice.high < $1.slice.high }) else { return nil }
        return HighlightRange(slices: slices, start: Date(timeIntervalSince1970: TimeInterval(start)),
                              end: Date(timeIntervalSince1970: TimeInterval(end)),
                              minimum: lowest.slice.low, maximum: highest.slice.high,
                              minimumIndex: lowest.index, maximumIndex: highest.index)
    }

    /// The round slice length, in seconds, that cuts `span` into the fewest slices above the target.
    static func sliceLength(span: Int) -> Int {
        let steps = [60, 120, 180, 300, 600, 900, 1200, 1800, 3600, 7200]
        return steps.first { span / $0 <= targetSlices } ?? 7200
    }

    // MARK: After a workout

    /// The three minutes after `end`. Each minute's figure is the reading nearest it, within twenty
    /// seconds; a minute with none means the strap was off, and there is no card.
    static func recovery(samples: [(ts: Int, bpm: Double)], end: Int) -> HighlightRecovery? {
        let window = samples.filter { $0.ts >= end - 20 && $0.ts <= end + 200 && $0.bpm > 0 }
        func nearest(_ target: Int) -> Int? {
            window.filter { abs($0.ts - target) <= 20 }
                .min { abs($0.ts - target) < abs($1.ts - target) }
                .map { Int($0.bpm.rounded()) }
        }
        guard let atEnd = nearest(end), let one = nearest(end + 60), let two = nearest(end + 120),
              let three = nearest(end + 180), atEnd > three else { return nil }
        let readings = window.filter { $0.ts >= end && $0.ts <= end + 180 }
            .map { HighlightRecovery.Reading(seconds: Double($0.ts - end), bpm: $0.bpm) }
        return HighlightRecovery(end: Date(timeIntervalSince1970: TimeInterval(end)), readings: thinned(readings),
                                 atEnd: atEnd, afterOneMinute: one, afterTwoMinutes: two, afterThreeMinutes: three)
    }

    /// One reading per fifteen seconds, which is as many dots as the card has room for.
    static func thinned(_ readings: [HighlightRecovery.Reading]) -> [HighlightRecovery.Reading] {
        var out: [HighlightRecovery.Reading] = []
        var slot = -1
        for reading in readings {
            let index = Int(reading.seconds / 15)
            if index != slot {
                out.append(reading)
                slot = index
            }
        }
        return out
    }

    // MARK: Days that had one

    /// `weeks` whole weeks ending with the week `today` is in. Days after today are still ahead.
    static func consistency(activeDays: Set<String>, weeks: Int, today: Date,
                            calendar: Calendar) -> HighlightConsistency? {
        let day = calendar.startOfDay(for: today)
        guard weeks > 0, let week = calendar.dateInterval(of: .weekOfYear, for: day),
              let first = calendar.date(byAdding: .day, value: -7 * (weeks - 1), to: week.start) else { return nil }
        var days: [HighlightConsistency.Day] = []
        var count = 0
        for offset in 0..<(7 * weeks) {
            guard let date = calendar.date(byAdding: .day, value: offset, to: first) else { return nil }
            let mark: HighlightConsistency.Mark
            if date > day {
                mark = .ahead
            } else if activeDays.contains(key(date, calendar: calendar)) {
                mark = .done
                count += 1
            } else {
                mark = .missed
            }
            days.append(.init(date: date, mark: mark))
        }
        return HighlightConsistency(days: days, count: count, today: day)
    }

    // MARK: Day keys

    /// Local midnight → "yyyy-MM-dd".
    static func key(_ date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// "yyyy-MM-dd" → local midnight.
    static func date(_ key: String, calendar: Calendar) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}
