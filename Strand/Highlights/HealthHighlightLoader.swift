//  HealthHighlightLoader.swift
//  NOOP · Highlights — reads what each card needs and resolves it into cards ready to draw.
//
//  One loader for every place a highlight shows (the Summary, a metric's page, Sleep, the Highlights
//  pages), so the same card never reads two ways. The arithmetic lives in `HealthHighlightMath`.

import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopProtocol
import WhoopStore

@MainActor
enum HealthHighlightLoader {

    /// A workout or a night counts as "your last" for this long; after it the card would be old news.
    static let recentSeconds = 7 * 86_400

    // MARK: Everything

    /// Every subject that has a highlight, in the order the All Highlights page lists them.
    static func all(repo: Repository, units: MetricHealthStyle.Units, now: Date = Date()) async -> [HighlightGroup] {
        var groups: [HighlightGroup] = []
        for metric in await subjectMetrics(repo: repo) {
            guard let page = await MetricSeriesLoader.load(metric, repo: repo, skinTemp: .absolute,
                                                           fullStepsHistory: true, provenance: false) else { continue }
            if let group = await self.metric(metric, series: page.series, repo: repo, units: units, now: now) {
                groups.append(group)
            }
        }
        let rows = await repo.workoutRows(days: 400)
        if let group = await workouts(rows: rows, repo: repo, now: now) { groups.append(group) }
        if let group = await heartRate(rows: rows, repo: repo, now: now) { groups.append(group) }
        return groups
    }

    /// The metrics that have highlights: steps, calories from whichever source has them, time asleep.
    static func subjectMetrics(repo: Repository) async -> [MetricDescriptor] {
        var out: [MetricDescriptor] = []
        if let steps = MetricCatalog.metric(key: "steps", source: MetricCatalog.combinedStepsSource) { out.append(steps) }
        let imported = await repo.exploreSeries(key: "active_kcal", source: "apple-health")
        if let calories = MetricCatalog.todayCaloriesMetric(hasImportedKcal: !imported.isEmpty, hasOnDeviceKcal: true) {
            out.append(calories)
        }
        if let sleep = MetricCatalog.metric(key: "sleep_total_min", source: Repository.whoopSource) { out.append(sleep) }
        return out
    }

    /// The few the Summary leads with: what is new today first, then the week.
    static func summary(_ groups: [HighlightGroup], limit: Int) -> [HighlightItem] {
        var seen = Set<String>()
        var out: [HighlightItem] = []
        for cadence in [HighlightCadence.daily, .weekly] {
            for group in groups {
                // A workout's heart rate files under both Workouts and Heart Rate; one card is enough.
                for item in group.items(cadence) where seen.insert(item.sentence).inserted {
                    out.append(item)
                }
            }
        }
        return Array(out.prefix(limit))
    }

    static func group(_ subject: HighlightSubject.Kind, repo: Repository, units: MetricHealthStyle.Units,
                      now: Date = Date()) async -> HighlightGroup? {
        switch subject {
        case .metric(let key, let source):
            guard let metric = MetricCatalog.metric(key: key, source: source),
                  let page = await MetricSeriesLoader.load(metric, repo: repo, skinTemp: .absolute,
                                                           fullStepsHistory: true, provenance: false) else { return nil }
            return await self.metric(metric, series: page.series, repo: repo, units: units, now: now)
        case .workouts:
            return await workouts(rows: await repo.workoutRows(days: 400), repo: repo, now: now)
        case .heartRate:
            return await heartRate(rows: await repo.workoutRows(days: 400), repo: repo, now: now)
        }
    }

    // MARK: A metric

    /// Steps, calories and time asleep: today against a usual day (steps), the week, the month, the year.
    static func metric(_ metric: MetricDescriptor, series: [(day: String, value: Double)], repo: Repository,
                       units: MetricHealthStyle.Units, now: Date = Date()) async -> HighlightGroup? {
        guard let kind = HighlightCopy.Kind.of(key: metric.key) else { return nil }
        let calendar = Calendar.current
        let category = AllMetricsCatalog.category(metric)
        let subject = HighlightSubject(kind: .metric(key: metric.key, source: metric.source), title: metric.title,
                                       category: category)
        let tint = MetricHealthStyle.tint(metric)
        let figure: (Double) -> [MetricHealthStyle.Token] = { MetricHealthStyle.tokens(metric, $0, units: units) }
        // A daily mean reads "per day" beside its unit; a night's sleep is already a whole.
        let perDay: (Double) -> [MetricHealthStyle.Token] = { value in
            let tokens = figure(value)
            guard kind != .sleep, let unit = tokens.last, unit.isUnit else { return tokens }
            return tokens.dropLast() + [.init(text: HighlightCopy.text("hl.perDay", ["UNIT": unit.text]), isUnit: true)]
        }
        func item(_ id: String, _ cadence: HighlightCadence, _ sentence: String, _ body: HighlightBody,
                  figure: @escaping (Double) -> [MetricHealthStyle.Token], weekLabel: String = "") -> HighlightItem {
            HighlightItem(id: "\(subject.id).\(id)", subject: subject, cadence: cadence, icon: category.icon,
                          title: metric.title, tint: tint, sentence: sentence, body: body, figure: figure,
                          weekLabel: weekLabel, route: .metricSourced(key: metric.key, source: metric.source))
        }

        var items: [HighlightItem] = []
        if kind == .steps, let hours = await hourlySteps(metric: metric, series: series, repo: repo, now: now),
           let progress = HealthHighlightMath.progress(todayHourly: hours.today, priorHourly: hours.prior, now: now,
                                                       calendar: calendar) {
            items.append(item("today", .daily, HighlightCopy.progress(kind, progress.direction), .progress(progress),
                              figure: figure))
        }
        // A night belongs to the day it ends on, so today's is whole; a day of steps is whole only once over.
        let today = calendar.startOfDay(for: now)
        let lastWhole = kind == .sleep ? today : (calendar.date(byAdding: .day, value: -1, to: today) ?? today)
        if let week = HealthHighlightMath.sevenDay(series: series, lastDay: lastWhole, calendar: calendar) {
            items.append(item("week", .weekly, HighlightCopy.week(kind.rawValue, average: spoken(kind, week.average)),
                              .sevenDay(week), figure: figure, weekLabel: HighlightCopy.weekLabel(kind.rawValue)))
        }
        for (period, cadence, id) in [(HighlightComparison.Period.month, HighlightCadence.monthly, "month"),
                                      (.year, .yearly, "year")] {
            if let comparison = HealthHighlightMath.comparison(series: series, period: period, today: now,
                                                               calendar: calendar) {
                items.append(item(id, cadence, HighlightCopy.comparison(kind, comparison, calendar: calendar),
                                  .comparison(comparison), figure: perDay))
            }
        }
        return items.isEmpty ? nil : HighlightGroup(subject: subject, items: items)
    }

    /// A week's average as its sentence reads it: "8 502 steps", "1 189 kilocalories", "7 hr 30 min".
    static func spoken(_ kind: HighlightCopy.Kind, _ value: Double) -> String {
        let count = Int(value.rounded())
        let number = MetricHealthStyle.number(Double(count), decimals: 0, locale: AppLanguage.activeLocale)
        switch kind {
        case .steps: return "\(number) \(HighlightCopy.unit(String(localized: "hl.unit.steps \(count)")))"
        case .energy: return "\(number) \(HighlightCopy.unit(String(localized: "hl.unit.kcal \(count)")))"
        case .sleep: return SleepFormat.duration(minutes: value)
        }
    }

    // MARK: Workouts

    static func workouts(rows: [WorkoutRow], repo: Repository, now: Date = Date()) async -> HighlightGroup? {
        guard !rows.isEmpty else { return nil }
        let calendar = Calendar.current
        let subject = HighlightSubject(kind: .workouts, title: String(localized: "Workouts"), category: .activity)
        let tint = StrandPalette.activityTitle
        func item(_ id: String, _ cadence: HighlightCadence, _ sentence: String, _ body: HighlightBody,
                  figure: @escaping (Double) -> [MetricHealthStyle.Token] = MetricHealthStyle.durationTokens,
                  weekLabel: String = "") -> HighlightItem {
            HighlightItem(id: "workouts.\(id)", subject: subject, cadence: cadence, icon: HealthCategory.activity.icon,
                          title: subject.title, tint: tint, sentence: sentence, body: body, figure: figure,
                          weekLabel: weekLabel, route: .workoutHistory)
        }

        var items = await lastWorkoutHeartRate(rows: rows, repo: repo, subject: subject, now: now)

        var minutes: [String: Double] = [:]
        var counts: [String: Double] = [:]
        for row in rows {
            let day = HealthHighlightMath.key(Date(timeIntervalSince1970: TimeInterval(row.startTs)), calendar: calendar)
            minutes[day, default: 0] += (row.durationS ?? Double(row.endTs - row.startTs)) / 60
            counts[day, default: 0] += 1
        }
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today) ?? today
        // A day without a workout is a day of no minutes, not a day off the record.
        var week: [(day: String, value: Double)] = []
        for back in stride(from: 6, through: 0, by: -1) {
            guard let day = calendar.date(byAdding: .day, value: -back, to: yesterday) else { continue }
            let key = HealthHighlightMath.key(day, calendar: calendar)
            week.append((day: key, value: minutes[key] ?? 0))
        }
        if week.contains(where: { $0.value > 0 }),
           let seven = HealthHighlightMath.sevenDay(series: week, lastDay: yesterday, calendar: calendar) {
            items.append(item("week", .weekly,
                              HighlightCopy.week("workouts", average: SleepFormat.duration(minutes: seven.average)),
                              .sevenDay(seven), weekLabel: HighlightCopy.weekLabel("workouts")))
        }
        let active = Set(counts.keys)
        if let days = HealthHighlightMath.consistency(activeDays: active, weeks: 1, today: now, calendar: calendar),
           days.count > 0 {
            items.append(item("days.week", .weekly, HighlightCopy.workoutDays(days.count, weeks: 1), .consistency(days)))
        }
        if let days = HealthHighlightMath.consistency(activeDays: active, weeks: 4, today: now, calendar: calendar),
           days.count > 0 {
            items.append(item("days.month", .monthly, HighlightCopy.workoutDays(days.count, weeks: 4), .consistency(days)))
        }
        let series = counts.sorted { $0.key < $1.key }.map { (day: $0.key, value: $0.value) }
        if let comparison = HealthHighlightMath.comparison(series: series, period: .month, today: now,
                                                           calendar: calendar, total: true) {
            items.append(item("month", .monthly, HighlightCopy.workoutsMonth(comparison), .comparison(comparison),
                              figure: { value in
                                  let count = Int(value.rounded())
                                  return [.init(text: "\(count)", isUnit: false),
                                          .init(text: HighlightCopy.unit(String(localized: "hl.unit.workouts \(count)")), isUnit: true)]
                              }))
        }
        return items.isEmpty ? nil : HighlightGroup(subject: subject, items: items)
    }

    // MARK: Heart rate

    static func heartRate(rows: [WorkoutRow], repo: Repository, now: Date = Date()) async -> HighlightGroup? {
        let subject = HighlightSubject(kind: .heartRate, title: String(localized: "Heart Rate"), category: .heart)
        var items = await lastWorkoutHeartRate(rows: rows, repo: repo, subject: subject, now: now)
        if let night = await sleepHeartRate(repo: repo, subject: subject, now: now) { items.append(night) }
        return items.isEmpty ? nil : HighlightGroup(subject: subject, items: items)
    }

    /// The last workout's minutes after it ended, then its range, when it is recent enough to be news.
    private static func lastWorkoutHeartRate(rows: [WorkoutRow], repo: Repository, subject: HighlightSubject,
                                             now: Date) async -> [HighlightItem] {
        let nowTs = Int(now.timeIntervalSince1970)
        guard let last = rows.filter({ $0.endTs <= nowTs }).max(by: { $0.endTs < $1.endTs }),
              nowTs - last.endTs <= recentSeconds else { return [] }
        let tint = StrandPalette.healthHeart
        func item(_ id: String, _ title: String, _ sentence: String, _ body: HighlightBody) -> HighlightItem {
            HighlightItem(id: "\(subject.id).\(id)", subject: subject, cadence: .daily, icon: HealthCategory.heart.icon,
                          title: HighlightCopy.text(title), tint: tint, sentence: sentence, body: body,
                          figure: { [.init(text: "\(Int($0.rounded()))", isUnit: false)] }, route: .workout(last))
        }
        var items: [HighlightItem] = []
        let after = await repo.hrSamples(from: last.endTs - 30, to: last.endTs + 210, limit: 2_000)
        if let recovery = HealthHighlightMath.recovery(samples: after.map { (ts: $0.ts, bpm: Double($0.bpm)) },
                                                       end: last.endTs) {
            items.append(item("recovery", "hl.title.hr.recovery",
                              HighlightCopy.recovery(sport: last.sport, drop: recovery.drop), .recovery(recovery)))
        }
        let buckets = await repo.workoutHrBuckets(from: last.startTs, to: last.endTs, source: last.source)
        if let range = HealthHighlightMath.range(samples: buckets.map { (ts: $0.ts, low: $0.minBpm, high: $0.maxBpm) },
                                                 start: last.startTs, end: last.endTs) {
            items.append(item("workout", "hl.title.hr.workout",
                              HighlightCopy.workoutHeartRate(sport: last.sport, low: Int(range.minimum.rounded()),
                                                             high: Int(range.maximum.rounded())),
                              .range(range)))
        }
        return items
    }

    /// Last night's heart rate, from falling asleep to waking.
    private static func sleepHeartRate(repo: Repository, subject: HighlightSubject, now: Date) async -> HighlightItem? {
        let nights = await SleepNightLoader.navDays(repo: repo).navDays
        guard let blocks = nights.first, let start = blocks.map(\.effectiveStartTs).min(),
              let end = blocks.map(\.endTs).max(), Int(now.timeIntervalSince1970) - end <= 36 * 3600 else { return nil }
        let buckets = await repo.hrBuckets(from: start, to: end, bucketSeconds: 300)
        guard let range = HealthHighlightMath.range(samples: buckets.map { (ts: $0.ts, low: $0.minBpm, high: $0.maxBpm) },
                                                    start: start, end: end) else { return nil }
        return HighlightItem(id: "\(subject.id).sleep", subject: subject, cadence: .daily, icon: HealthCategory.heart.icon,
                             title: HighlightCopy.text("hl.title.hr.sleep"), tint: StrandPalette.healthHeart,
                             sentence: HighlightCopy.sleepHeartRate(low: Int(range.minimum.rounded()),
                                                                    high: Int(range.maximum.rounded())),
                             body: .range(range), figure: { [.init(text: "\(Int($0.rounded()))", isUnit: false)] },
                             route: SleepNightLoader.wakeDayKey(blocks).map { .sleepNight($0) })
    }

    // MARK: Steps by the hour

    /// Settled days' hours by device and day, kept for the life of the process.
    private static var hourCache: [String: [Double]] = [:]

    /// Today's steps hour by hour and the same for the days before, each day scaled to the total the
    /// metric's own series gives it, so the card's "today" is the figure every other screen shows.
    /// The strap's counter is read first, then the phone's hourly steps; a metric that names one source
    /// reads only that one.
    static func hourlySteps(metric: MetricDescriptor, series: [(day: String, value: Double)], repo: Repository,
                            now: Date) async -> (today: [Double], prior: [[Double]])? {
        guard let store = await repo.storeHandle() else { return nil }
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let days: [Date] = (0...HealthHighlightMath.typicalDays).compactMap {
            calendar.date(byAdding: .day, value: -$0, to: today)
        }
        var raw: [[Double]]?
        if metric.source != "apple-health" {
            raw = await strapHours(days: days, store: store, ids: repo.importedReadIds, calendar: calendar)
        }
        if raw == nil, metric.source != Repository.whoopSource {
            raw = await phoneHours(days: days, store: store, now: now, calendar: calendar)
        }
        guard let raw, raw.count == days.count else { return nil }

        let totals = Dictionary(series.map { ($0.day, $0.value) }, uniquingKeysWith: { _, last in last })
        var scales: [Double?] = []
        for (day, hours) in zip(days, raw) {
            let sum = hours.reduce(0, +)
            let total = totals[HealthHighlightMath.key(day, calendar: calendar)]
            scales.append(sum > 0 ? total.map { $0 / sum } : nil)
        }
        let known = scales.compactMap { $0 }.filter { $0 > 0 }
        guard !known.isEmpty else { return nil }
        // A day the series has no total for yet (today, before its first score) borrows the usual scale.
        let usual = known.sorted()[known.count / 2]
        let scaled = zip(raw, scales).map { hours, scale in hours.map { $0 * (scale ?? usual) } }
        return (today: scaled[0], prior: Array(scaled.dropFirst()))
    }

    /// The first strap id whose counter covers today, hour by hour, for each day. nil when none does.
    private static func strapHours(days: [Date], store: WhoopStore, ids: [String],
                                   calendar: Calendar) async -> [[Double]]? {
        guard let today = days.first else { return nil }
        for id in ids {
            let first = await dayHours(today, id: id, store: store, calendar: calendar, cached: false)
            guard first.reduce(0, +) > 0 else { continue }
            var out = [first]
            for (back, day) in days.enumerated().dropFirst() {
                // The strap hands over a day's history after the fact, so the last days are read afresh;
                // an older day is settled and is read once.
                out.append(await dayHours(day, id: id, store: store, calendar: calendar, cached: back > 2))
            }
            return out
        }
        return nil
    }

    private static func dayHours(_ day: Date, id: String, store: WhoopStore, calendar: Calendar,
                                 cached: Bool) async -> [Double] {
        let key = "\(id)|\(HealthHighlightMath.key(day, calendar: calendar))"
        if cached, let hit = hourCache[key] { return hit }
        let start = Int(day.timeIntervalSince1970)
        let end = Int((calendar.date(byAdding: .day, value: 1, to: day) ?? day).timeIntervalSince1970)
        let samples = (try? await store.stepSamples(deviceId: id, from: start, to: end - 1, limit: 200_000)) ?? []
        let hours = await Task.detached { hourlyTicks(samples: samples, dayStart: start, dayEnd: end) }.value
        if cached { hourCache[key] = hours }
        return hours
    }

    /// A day's counter ticks cut into 24 hours. Each hour is counted with the last sample before it, so
    /// the steps taken across the hour's edge are not lost between two windows.
    nonisolated static func hourlyTicks(samples: [StepSample], dayStart: Int, dayEnd: Int) -> [Double] {
        var hours = [Double](repeating: 0, count: 24)
        let sorted = samples.sorted { $0.ts < $1.ts }
        let length = max(1, (dayEnd - dayStart) / 24)
        var index = 0
        var previous: StepSample?
        for hour in 0..<24 {
            let hourEnd = hour == 23 ? dayEnd : dayStart + length * (hour + 1)
            var window: [StepSample] = previous.map { [$0] } ?? []
            while index < sorted.count, sorted[index].ts < hourEnd {
                window.append(sorted[index])
                index += 1
            }
            hours[hour] = Double(StepsCounter.stepsInWindow(window) ?? 0)
            previous = window.last ?? previous
        }
        return hours
    }

    /// The phone's hourly steps for each day. nil when today has none.
    private static func phoneHours(days: [Date], store: WhoopStore, now: Date, calendar: Calendar) async -> [[Double]]? {
        guard let oldest = days.last else { return nil }
        let rows = (try? await store.appleStepHours(deviceId: "apple-health", fromTs: Int(oldest.timeIntervalSince1970),
                                                    toTs: Int(now.timeIntervalSince1970))) ?? []
        guard !rows.isEmpty else { return nil }
        var byDay: [String: [Double]] = [:]
        for row in rows {
            let date = Date(timeIntervalSince1970: TimeInterval(row.ts))
            let key = HealthHighlightMath.key(date, calendar: calendar)
            let hour = calendar.component(.hour, from: date)
            byDay[key, default: [Double](repeating: 0, count: 24)][hour] += Double(row.steps)
        }
        let out = days.map { byDay[HealthHighlightMath.key($0, calendar: calendar)] ?? [Double](repeating: 0, count: 24) }
        return out[0].reduce(0, +) > 0 ? out : nil
    }
}
