//  SummaryLoader.swift
//  NOOP · Summary home — one async read of everything the screen shows for a selected day.
//
//  Every read and every precedence rule here is the one the earlier Today home used, so the Summary shows
//  the same Charge / Effort / Rest and the same metric values; only the presentation changed. The result is a plain value the view renders without touching the Repository again.

import Foundation
import StrandAnalytics
import WhoopStore

struct SummarySnapshot {
    var charge: ChargeDisplay = .noData
    /// Effort on the stored 0–100 axis (live in-progress score for today when it beats the row).
    var effort: Double?
    /// Sleep performance, 0–100.
    var rest: Double?
    var metrics: SummaryMetricInputs?
    /// Trailing 7-day values (oldest → newest) keyed by `SummaryMetricReading.seriesKey`.
    var series: [String: [Double]] = [:]
    /// The same week one slot per day (nil where a day has no value), for the Fitness tiles' day columns.
    var dailySeries: [String: [Double?]] = [:]
    /// The week's day keys, oldest → newest, the slots of `dailySeries`.
    var weekKeys: [String] = []
    var highlights: [SummaryHighlight] = []
}

/// Day arithmetic for the selector: offset 0 is today's logical day (rolls at 04:00), 1 is yesterday, …
@MainActor
enum SummaryDay {
    static func logicalDay(offset: Int, now: Date = Date()) -> Date {
        let base = Repository.logicalDay(now)
        return Calendar.current.date(byAdding: .day, value: -offset, to: base) ?? base
    }

    /// The key the day-scoped reads use; at offset 0 it follows the live `repo.today` row.
    static func key(offset: Int, repo: Repository) -> String {
        if offset == 0, let todayKey = repo.today?.day { return todayKey }
        return Repository.localDayKey(logicalDay(offset: offset))
    }

    /// How far back the selector may go.
    static func maxOffset(repo: Repository) -> Int {
        maxDayOffset(earliestDayKey: repo.freshness.earliestDay, todayKey: Repository.logicalDayKey(Date()))
    }

    /// Whole days from today's logical day back to `earliestDayKey` (the oldest banked day across all
    /// sources). nil/unparseable earliest, or a key on/after today, both yield 0 - today is then the only
    /// navigable day. Both keys are "yyyy-MM-dd". Pure + unit-testable.
    nonisolated static func maxDayOffset(earliestDayKey: String?, todayKey: String) -> Int {
        guard let earliestKey = earliestDayKey,
              let earliest = dayKeyParser.date(from: earliestKey),
              let today = dayKeyParser.date(from: todayKey) else { return 0 }
        let gap = Calendar.current.dateComponents([.day],
                                                  from: Calendar.current.startOfDay(for: earliest),
                                                  to: Calendar.current.startOfDay(for: today)).day ?? 0
        return max(0, gap)
    }

    /// #16 - whole days-back offset for a date chosen in the day picker, measured from the LOGICAL day
    /// (not raw Date()). Pure + unit-testable so the 00:00-04:00 rollover case is locked: in that window the
    /// logical day is the PREVIOUS calendar day, so anchoring the offset here (rather than on raw Date())
    /// keeps the picked day in step with the visible date and the a11y label. Clamped at 0 so a future-
    /// relative pick collapses to today. Both dates are reduced to their start-of-day before counting.
    nonisolated static func pickedDayOffset(pickedDate: Date, anchorLogicalDay: Date) -> Int {
        let cal = Calendar.current
        let days = cal.dateComponents([.day],
                                      from: cal.startOfDay(for: pickedDate),
                                      to: cal.startOfDay(for: anchorLogicalDay)).day ?? 0
        return max(0, days)
    }

    /// Parses a stored `yyyy-MM-dd` day key in the device-local zone (matching how DailyMetric.day is
    /// written), so a key never shifts a day under timezone conversion.
    private nonisolated static let dayKeyParser: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()
}

@MainActor
enum SummaryLoader {
    struct Prefs {
        var dayCycleMode: DayCycleMode
        var unitSystem: UnitSystem
        var fahrenheit: Bool
        var skinTempKind: SkinTempDisplay.Kind
    }

    static let seriesDays = 7

    /// The picked day's night and the newest sample on record, read together so the Sleep card and the
    /// in-progress card are drawn from one result.
    static func sleep(repo: Repository, wakeDayKey: String) async -> SummarySleepLoad {
        async let night = SleepNightLoader.night(repo: repo, wakeDayKey: wakeDayKey)
        async let newest = repo.newestHRSampleTs()
        return SummarySleepLoad(night: await night, newestDataTs: await newest)
    }

    static func load(repo: Repository, profile: ProfileStore, offset: Int, prefs: Prefs) async -> SummarySnapshot {
        var snap = SummarySnapshot()
        let isToday = offset == 0
        let dayKey = SummaryDay.key(offset: offset, repo: repo)
        let logicalDay = SummaryDay.logicalDay(offset: offset)
        let days = repo.days

        let day: DailyMetric? = isToday
            ? (repo.today ?? days.last(where: { $0.day == dayKey }))
            : days.last(where: { $0.day == dayKey })
        let tkey = day?.day ?? dayKey

        // Charge: scored → calibrating → a real prior night's carry → nothing.
        let calNights = isToday
            ? RecoveryScorer.calibrationNights(nightlyHrv: days.map(\.avgHrv), dayKeys: days.map(\.day),
                                               hasRecovery: day?.recovery != nil)
            : nil
        let priorScored = DayScoreReadings.lastScoredRecoveryDay(days: days, selectedDayKey: tkey, isToday: isToday,
                                                                 todayScored: day?.recovery != nil,
                                                                 isCalibrating: calNights != nil)
        snap.charge = .resolve(todayRecovery: day?.recovery, priorScored: priorScored,
                               calibrationNights: calNights, todayKey: tkey)

        // Effort: today scores the in-progress window live; the stored row is the floor.
        let liveStrain = isToday
            ? await liveTodayStrain(repo: repo, profile: profile, day: day, dayKey: dayKey,
                                    logicalDay: logicalDay, mode: prefs.dayCycleMode)
            : nil
        snap.effort = StrainScorer.effectiveEffort(live: liveStrain, stored: day?.strain)

        async let restA = repo.exploreSeries(key: "sleep_performance", source: "my-whoop")
        async let stepsA = repo.exploreSeries(key: "steps_est", source: "my-whoop")
        async let spo2CandA = repo.exploreSeries(key: "spo2_candidate", source: "my-whoop")
        async let weightA = repo.series(key: "weight", source: "apple-health", days: 91)
        async let appleA = repo.appleDailyRows()

        let restSeries = await restA
        let restByDay = Dictionary(restSeries.map { ($0.day, $0.value) }, uniquingKeysWith: { _, last in last })
        snap.rest = DayScoreReadings.freshRestScore(todayValue: restByDay[dayKey], lastDay: restSeries.last?.day,
                                                    lastValue: restSeries.last?.value, isTodaySelected: isToday,
                                                    todayKey: dayKey)

        let stepsSeries = await stepsA
        let stepsEstByDay = Dictionary(stepsSeries.map { ($0.day, $0.value) }, uniquingKeysWith: { _, last in last })
        let spo2CandSeries = await spo2CandA
        let spo2CandByDay = Dictionary(spo2CandSeries.map { ($0.day, $0.value) }, uniquingKeysWith: { _, last in last })
        let weightSeries = await weightA
        let appleRows = await appleA

        // Today-only carries so the vitals don't blank between the 04:00 rollover and tonight's sleep.
        let vitalsDay = isToday ? Repository.lastVitalsDay(days: days, todayKey: tkey) : nil
        let skinCarry = isToday ? Repository.lastSkinTempReadingDay(days: days, todayKey: tkey) : nil
        let skinRow = [day, vitalsDay, skinCarry].compactMap { $0 }
            .first { $0.skinTempC != nil || $0.skinTempDevC != nil }

        let appleToday = appleRows.filter { $0.day == dayKey }
        let appleWeightToday = appleToday.compactMap { $0.weightKg }.max()
        let lastWeight = weightSeries.last(where: { $0.day <= dayKey })
        snap.metrics = SummaryMetricInputs(
            day: day,
            vitalsDay: vitalsDay,
            respDay: isToday ? Repository.lastRespDay(days: days, todayKey: tkey) : nil,
            hrvDay: isToday ? Repository.lastHrvDay(days: days, todayKey: tkey) : nil,
            restingHrDay: isToday ? Repository.lastRestingHrDay(days: days, todayKey: tkey) : nil,
            skinTempReading: SkinTempDisplay.leadReading(absC: skinRow?.skinTempC, devC: skinRow?.skinTempDevC,
                                                         prefer: prefs.skinTempKind),
            spo2Candidate: PuffinExperiment.spo2CandidateDisplayEnabled ? spo2CandByDay[tkey] : nil,
            importedSteps: appleToday.compactMap { $0.steps }.max(),
            stepsEstimate: stepsEstByDay[dayKey] ?? (isToday ? stepsSeries.last?.value : nil),
            importedActiveKcal: appleToday.compactMap { $0.activeKcal }.max(),
            healthWeightKg: appleWeightToday ?? lastWeight?.value,
            profileWeightKg: profile.weightKg,
            unitSystem: prefs.unitSystem,
            fahrenheit: prefs.fahrenheit,
            dayKey: tkey,
            healthWeightDay: appleWeightToday != nil ? dayKey : lastWeight?.day,
            skinTempDay: skinRow?.day
        )

        // The trailing week each pinned card draws, ending on the selected day.
        let cal = Calendar.current
        let windowKeys: [String] = (0..<seriesDays).reversed().map { back in
            Repository.localDayKey(cal.date(byAdding: .day, value: -back, to: logicalDay) ?? logicalDay)
        }
        let rowByDay = Dictionary(days.map { ($0.day, $0) }, uniquingKeysWith: { _, last in last })
        var appleByDay: [String: (steps: Int?, kcal: Double?)] = [:]
        for r in appleRows where windowKeys.contains(r.day) {
            let prev = appleByDay[r.day]
            appleByDay[r.day] = ([prev?.steps, r.steps].compactMap { $0 }.max(),
                                 [prev?.kcal, r.activeKcal].compactMap { $0 }.max())
        }
        let weightByDay = Dictionary(weightSeries.map { ($0.day, $0.value) }, uniquingKeysWith: { _, last in last })
        func week(_ value: (String) -> Double?) -> [Double?] { windowKeys.map(value) }
        snap.weekKeys = windowKeys
        snap.dailySeries = [
            "hrv": week { rowByDay[$0]?.avgHrv },
            "rhr": week { rowByDay[$0]?.restingHr.map(Double.init) },
            "spo2": week { rowByDay[$0]?.spo2Pct },
            "spo2_candidate": week { spo2CandByDay[$0] },
            "resp_rate": week { rowByDay[$0]?.respRateBpm },
            "skin_temp": week { rowByDay[$0]?.skinTempDevC },
            "steps": week { d in
                rowByDay[d]?.steps.map(Double.init) ?? appleByDay[d]?.steps.map(Double.init) ?? stepsEstByDay[d]
            },
            "energy_kcal": week { appleByDay[$0]?.kcal ?? rowByDay[$0]?.activeKcalEst },
            "weight": week { weightByDay[$0] },
            // Today's bar is the live Effort the rings show, not the row the daily pass last stored.
            "effort": week { $0 == tkey ? snap.effort : rowByDay[$0]?.strain },
        ]
        snap.series = snap.dailySeries.mapValues { $0.compactMap { $0 } }

        snap.highlights = SummaryHighlight.from(ReadinessEngine.evaluate(days: days, today: day?.day))
        return snap
    }

    /// Today's in-progress Effort over the same window the daily pass scores (the day-cycle onset when
    /// that mode is on, else calendar midnight → now), with the same HR-max resolution. nil below the
    /// scorer's minimum readings, so the stored row stands rather than a fabricated value.
    private static func liveTodayStrain(repo: Repository, profile: ProfileStore, day: DailyMetric?,
                                        dayKey: String, logicalDay: Date, mode: DayCycleMode) async -> Double? {
        let cal = Calendar.current
        let dayStart = cal.startOfDay(for: logicalDay)
        let calendarFrom = Int(dayStart.timeIntervalSince1970)
        let calendarTo = Int(Date().timeIntervalSince1970)
        let nextDayKey = Repository.localDayKey(cal.date(byAdding: .day, value: 1, to: dayStart) ?? dayStart)
        let markers = mode == .sleepOnset
            ? await repo.exploreSeries(key: DayCycleIntelligenceIntegration.onsetKey, source: "my-whoop") : []
        let from = markers.last(where: { $0.day == dayKey }).map { Int($0.value) } ?? calendarFrom
        let toExclusive = markers.last(where: { $0.day == nextDayKey }).map { Int($0.value) } ?? calendarTo
        let to = max(from, toExclusive - 1)
        // Whole-window read: the chart-sized default limit would drop the newest samples.
        let samples = await repo.hrSamples(from: from, to: to, limit: 200_000)
        let restHR = day?.restingHr.map(Double.init) ?? StrainScorer.defaultRestingHR
        return StrainScorer.strain(samples, maxHR: profile.effortHRmax, restingHR: restHR,
                                   method: PuffinExperiment.effortMethod, sex: profile.sex)
    }
}
