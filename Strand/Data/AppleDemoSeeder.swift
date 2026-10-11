#if DEBUG
import Foundation
import WhoopStore
import WhoopProtocol

// MARK: - DEBUG-only demo seed (Apple parity with Android's DemoSeeder)
// Seeds a comprehensive, self-contained synthetic dataset so a DEBUG build can walk every screen —
// Today, Sleep, Trends, Workouts, Health, Stress, Insights, Explore — with no strap and no import.
// This is the Apple twin of `android/.../data/DemoSeeder.kt` (same RNG seed, same physiology, same
// 120-day window) and exists so we can render iOS + macOS for verification and marketing screenshots.
//
// Gating: the whole file is `#if DEBUG`, so it is stripped from every Release build (the shipped
// app). At runtime it only seeds when launched with `--demo-seed` AND the store has no daily rows,
// so it runs at most once and never clobbers real data. Everything here is SYNTHETIC and
// DETERMINISTIC (fixed seed) — nothing is real biometric data. Values are physiologically plausible
// and internally correlated (recovery ↔ HRV ↔ resting-HR ↔ sleep; strain ↔ workouts; a slow fitness
// drift) so the charts, trends and insights all read like a real account.
enum AppleDemoSeeder {

    static let whoop = "my-whoop"
    static let apple = "apple-health"
    private static let DAYS = 120
    /// Effort rescale factor: the old 0–21 strain scale → the new 0–100 Effort scale.
    private static let STRAIN_SCALE = 100.0 / 21.0

    private static let SPORTS = [
        "Running", "Cycling", "Strength", "HIIT", "Swimming", "Yoga", "Walking", "Rowing",
    ]
    private static let DISTANCE_SPORTS: Set<String> = ["Running", "Cycling", "Walking", "Swimming", "Rowing"]

    /// True when the process was launched asking for the demo seed (Xcode scheme arg or `simctl
    /// launch … --demo-seed`).
    static var requested: Bool { CommandLine.arguments.contains("--demo-seed") }

    /// Seed only if requested AND the store is empty. Safe to call on every launch.
    static func seedIfRequested(into store: WhoopStore) async {
        guard requested else { return }
        seedDemoDeviceIfNeeded(into: store)
        await seedLabMarkersIfNeeded(into: store)
        let existing = (try? await store.dailyMetrics(deviceId: whoop, from: "0000-00-00", to: "9999-99-99")) ?? []
        if existing.isEmpty {
            do { try await seed(into: store) }
            catch { NSLog("AppleDemoSeeder: seed failed — \(error)") }
        }
        // After the daily seed: both read the workouts it wrote.
        await seedTodayRawIfNeeded(into: store)
        await seedHighlightsRawIfNeeded(into: store)
    }

    /// What the Highlights cards read beyond the daily rows: the phone's steps hour by hour for the last
    /// fortnight, and heart rate through the last workout (with the minutes after it) and last night.
    /// Each part only when its window is still empty, so it re-runs as the days move on.
    private static func seedHighlightsRawIfNeeded(into store: WhoopStore) async {
        let cal = Calendar.current
        let now = Int(Date().timeIntervalSince1970)
        let today = cal.startOfDay(for: Date())
        var rng = SplitMix64(seed: UInt64(today.timeIntervalSince1970))

        // Steps by the hour, each day's hours adding up to that day's total; today's run to now.
        let todayTs = Int(today.timeIntervalSince1970)
        if ((try? await store.appleStepHours(deviceId: apple, fromTs: todayTs, toTs: now)) ?? []).isEmpty {
            let shape: [Double] = [0, 0, 0, 0, 0, 0, 1, 4, 9, 6, 4, 5, 9, 7, 4, 4, 5, 8, 12, 10, 7, 4, 2, 1]
            let weight = shape.reduce(0, +)
            var hours: [(ts: Int, steps: Int)] = []
            for back in 0...15 {
                guard let day = cal.date(byAdding: .day, value: -back, to: today) else { continue }
                let key = HealthHighlightMath.key(day, calendar: cal)
                let total = ((try? await store.appleDaily(deviceId: apple, from: key, to: key)) ?? []).first?.steps ?? 8_000
                for (hour, share) in shape.enumerated() where share > 0 {
                    let ts = Int(day.timeIntervalSince1970) + hour * 3_600
                    guard ts <= now else { break }
                    // The hour in progress holds only the part of it that has passed.
                    let elapsed = min(1, Double(now - ts) / 3_600)
                    let steps = Double(total) * share / weight * (0.75 + rng.nextDouble() * 0.5) * elapsed
                    hours.append((ts: ts, steps: Int(steps.rounded())))
                }
            }
            _ = try? await store.upsertAppleStepHours(hours, deviceId: apple)
        }

        // The daily totals as the importers write them, which is the series the Steps and Calories pages read.
        if ((try? await store.metricSeries(deviceId: apple, key: "steps", from: "0000-00-00", to: "9999-99-99")) ?? []).isEmpty {
            let rows = (try? await store.appleDaily(deviceId: apple, from: "0000-00-00", to: "9999-99-99")) ?? []
            var points: [MetricPoint] = []
            for row in rows {
                if let steps = row.steps { points.append(MetricPoint(day: row.day, key: "steps", value: Double(steps))) }
                if let kcal = row.activeKcal { points.append(MetricPoint(day: row.day, key: "active_kcal", value: kcal)) }
            }
            _ = try? await store.upsertMetricSeries(points, deviceId: apple)
        }

        // The last workout: up to a peak and back, then three and a half minutes of coming down.
        let recent = (try? await store.workouts(deviceId: whoop, from: now - 7 * 86_400, to: now, limit: 50)) ?? []
        if let last = recent.filter({ $0.endTs + 240 <= now }).max(by: { $0.endTs < $1.endTs }),
           ((try? await store.hrSamples(deviceId: whoop, from: last.startTs, to: last.endTs, limit: 1)) ?? []).isEmpty {
            var hr: [HRSample] = []
            let span = Double(max(1, last.endTs - last.startTs))
            for ts in stride(from: last.startTs, to: last.endTs, by: 5) {
                let phase = Double(ts - last.startTs) / span
                hr.append(HRSample(ts: ts, bpm: Int(gauss(&rng, 118 + sin(phase * .pi) * 42, 5).clamped(70, 185))))
            }
            for ts in stride(from: last.endTs, through: last.endTs + 210, by: 5) {
                let minutes = Double(ts - last.endTs) / 60
                hr.append(HRSample(ts: ts, bpm: Int(gauss(&rng, 146 - minutes * 14.5, 1).clamped(60, 185))))
            }
            _ = try? await store.insert(Streams(hr: hr), deviceId: whoop)
        }

        // Last night: settling in the first hour, then low and even until morning.
        let nights = (try? await store.sleepSessions(deviceId: whoop, from: now - 36 * 3_600, to: now, limit: 8)) ?? []
        if let night = nights.max(by: { $0.endTs < $1.endTs }),
           ((try? await store.hrSamples(deviceId: whoop, from: night.startTs, to: min(night.endTs, todayTs + 8 * 3_600 - 1),
                                        limit: 1)) ?? []).isEmpty {
            var hr: [HRSample] = []
            for ts in stride(from: night.startTs, to: night.endTs, by: 30) {
                let hoursIn = Double(ts - night.startTs) / 3_600
                let level = hoursIn < 1 ? 66 - hoursIn * 12 : 54 + sin(hoursIn * 1.3) * 2.5
                hr.append(HRSample(ts: ts, bpm: Int(gauss(&rng, level, 2.5).clamped(42, 100))))
            }
            _ = try? await store.insert(Streams(hr: hr), deviceId: whoop)
        }
    }

    /// DEBUG/demo-only: so the Devices screen renders with content under `--demo-seed`, pair a second
    /// (non-WHOOP) strap alongside the seeded WHOOP. If the registry only holds the WHOOP, add a
    /// `.paired` "Polar H10" — the screenshot then shows the WHOOP (Active) plus a paired strap. Status
    /// `.paired` (not `.active`) keeps the WHOOP active, so the SourceCoordinator stays dormant and the
    /// existing WHOOP path is untouched. No-op once a second device already exists.
    private static func seedDemoDeviceIfNeeded(into store: WhoopStore) {
        let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
        guard let devices = try? registry.all() else { return }
        guard devices.allSatisfy({ $0.id == whoop }) else { return }  // only the seeded WHOOP present
        let now = Int(Date().timeIntervalSince1970)
        let polar = PairedDevice(
            id: "polar-h10-demo", brand: "Polar", model: "H10", nickname: nil,
            sourceKind: .liveBLE, capabilities: [.hr, .hrv], status: .paired,
            addedAt: now - 86_400, lastSeenAt: now - 3_600)
        try? registry.add(polar)
    }

    /// Today's raw heart rate and R-R from 08:00 to now, so the Stress page's hour-by-hour card has a real
    /// day to score. The daily rows above carry no raw streams. Calm mornings, a tense early afternoon:
    /// heart rate up and beat-to-beat variability down across 13:00–15:00. Re-runs on a new day.
    private static func seedTodayRawIfNeeded(into store: WhoopStore) async {
        let cal = Calendar.current
        let start = Int(cal.startOfDay(for: Date()).timeIntervalSince1970) + 8 * 3_600
        let end = Int(Date().timeIntervalSince1970)
        guard end > start,
              ((try? await store.hrSamples(deviceId: whoop, from: start, to: end, limit: 1)) ?? []).isEmpty
        else { return }
        // Today's workouts get a heart rate of their own (`seedHighlightsRawIfNeeded`), so a resting one
        // is not laid under them or under the minutes after.
        let workouts = ((try? await store.workouts(deviceId: whoop, from: start - 7_200, to: end, limit: 20)) ?? [])
            .map { $0.startTs...($0.endTs + 240) }
        var rng = SplitMix64(seed: UInt64(start))
        var hr: [HRSample] = []
        var rr: [RRInterval] = []
        var t = Double(start)
        var nextHR = start
        while Int(t) < end {
            let hour = cal.component(.hour, from: Date(timeIntervalSince1970: t))
            let tense = (13...15).contains(hour) ? 1.0 : (hour == 18 ? 0.5 : 0.0)
            let bpm = gauss(&rng, 62 + tense * 22, 2)
            let jitter = 45 * (1 - 0.8 * tense)
            let beat = (60_000 / bpm + gauss(&rng, 0, jitter)).clamped(400, 1_400)
            rr.append(RRInterval(ts: Int(t), rrMs: Int(beat)))
            if Int(t) >= nextHR {
                if !workouts.contains(where: { $0.contains(Int(t)) }) {
                    hr.append(HRSample(ts: Int(t), bpm: Int(bpm.rounded())))
                }
                nextHR = Int(t) + 5
            }
            t += beat / 1_000
        }
        _ = try? await store.insert(Streams(hr: hr, rr: rr), deviceId: whoop)
    }

    /// A few blood tests and blood-pressure readings over the last nine months, so Lab Book has a logbook
    /// to show. Only when the logbook is empty.
    private static func seedLabMarkersIfNeeded(into store: WhoopStore) async {
        // Under the device Lab Book reads (the registry's active one), which a simulator that has been
        // through the Devices screens may have moved off the seeded WHOOP.
        let owner = (try? DeviceRegistryStore(dbQueue: store.registryWriter).activeDeviceId()) ?? whoop
        let existing = (try? await store.labMarkers(deviceId: owner, category: "bloodPanel")) ?? []
        guard existing.isEmpty else { return }
        // Readings reference a `device` row; the paired demo strap has none of its own.
        if owner != whoop { try? await store.upsertDevice(id: owner, mac: nil, name: owner) }
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        let draws: [(days: Int, values: [(key: String, category: String, value: Double, unit: String, ref: String?)])] = [
            (270, [("ldl", "bloodPanel", 3.6, "mmol/L", "< 3.0"), ("hdl", "bloodPanel", 1.2, "mmol/L", "> 1.0"),
                   ("fasting_glucose", "bloodPanel", 5.4, "mmol/L", "3.9–5.5"), ("ferritin", "bloodPanel", 48, "µg/L", "30–400"),
                   ("vitamin_d", "bloodPanel", 42, "nmol/L", "50–125")]),
            (180, [("ldl", "bloodPanel", 3.3, "mmol/L", "< 3.0"), ("hdl", "bloodPanel", 1.3, "mmol/L", "> 1.0"),
                   ("fasting_glucose", "bloodPanel", 5.2, "mmol/L", "3.9–5.5"), ("vitamin_d", "bloodPanel", 58, "nmol/L", "50–125")]),
            (90, [("ldl", "bloodPanel", 3.0, "mmol/L", "< 3.0"), ("hdl", "bloodPanel", 1.4, "mmol/L", "> 1.0"),
                  ("ferritin", "bloodPanel", 71, "µg/L", "30–400"), ("vitamin_d", "bloodPanel", 74, "nmol/L", "50–125")]),
            (21, [("ldl", "bloodPanel", 2.8, "mmol/L", "< 3.0"), ("hdl", "bloodPanel", 1.4, "mmol/L", "> 1.0"),
                  ("fasting_glucose", "bloodPanel", 5.0, "mmol/L", "3.9–5.5")]),
        ]
        var rows: [LabMarkerRow] = []
        func add(_ key: String, _ category: String, _ value: Double, _ unit: String, _ ref: String?, daysAgo: Int, hour: Int) {
            let date = cal.date(byAdding: .day, value: -daysAgo, to: today)!.addingTimeInterval(TimeInterval(hour * 3_600))
            let epoch = Int(date.timeIntervalSince1970)
            rows.append(LabMarkerRow(id: "\(owner)-\(key)-\(epoch)", deviceId: owner, markerKey: key, category: category,
                                     day: LabBookFormat.dayKey(date), takenAt: epoch, value: value, valueText: nil,
                                     unit: unit, source: "manual", note: nil, referenceText: ref))
        }
        for draw in draws {
            for v in draw.values { add(v.key, v.category, v.value, v.unit, v.ref, daysAgo: draw.days, hour: 8) }
        }
        for (i, bp) in [(128.0, 84.0), (124, 82), (121, 80), (118, 78), (119, 77)].enumerated() {
            let daysAgo = 150 - i * 35
            add("bp_systolic", "bloodPressure", bp.0, "mmHg", nil, daysAgo: daysAgo, hour: 9)
            add("bp_diastolic", "bloodPressure", bp.1, "mmHg", nil, daysAgo: daysAgo, hour: 9)
        }
        do { _ = try await store.upsertLabMarkers(rows) }
        catch { NSLog("AppleDemoSeeder: lab markers failed — \(error)") }
    }

    private static func seed(into store: WhoopStore) async throws {
        var rng = SplitMix64(seed: 0xC0FFEE)
        let cal = Calendar.current
        let zone = TimeZone.current
        let startDay = cal.date(byAdding: .day, value: -(DAYS - 1), to: cal.startOfDay(for: Date()))!

        try? await store.upsertDevice(id: whoop, mac: nil, name: "WHOOP (demo)")

        var daily: [DailyMetric] = []
        var sleeps: [CachedSleepSession] = []
        var series: [MetricPoint] = []
        var appleRows: [AppleDaily] = []
        var workouts: [WorkoutRow] = []
        var journal: [JournalEntry] = []

        var weight = 79.5
        var fitness = 0.0  // slow upward drift: HRV rises, resting-HR falls, VO2max climbs

        let isoFmt = DateFormatter()
        isoFmt.locale = Locale(identifier: "en_US_POSIX")
        isoFmt.timeZone = zone
        isoFmt.dateFormat = "yyyy-MM-dd"

        for i in 0..<DAYS {
            let date = cal.date(byAdding: .day, value: i, to: startDay)!
            let day = isoFmt.string(from: date)
            let weekday = cal.component(.weekday, from: date)  // 1=Sun … 7=Sat
            let weekend = (weekday == 1 || weekday == 7)
            fitness += 0.012

            // --- training load for the day ---
            let trains = weekend ? rng.nextDouble() < 0.40 : rng.nextDouble() < 0.62
            let nWorkouts = !trains ? 0 : (rng.nextDouble() < 0.22 ? 2 : 1)

            // --- sleep architecture ---
            let totalSleep = gauss(&rng, 430.0, 35.0).clamped(300.0, 540.0)
            let efficiency = gauss(&rng, 89.0, 4.0).clamped(72.0, 98.0)
            let deep = (totalSleep * gauss(&rng, 0.20, 0.03)).clamped(35.0, 130.0)
            let rem = (totalSleep * gauss(&rng, 0.23, 0.03)).clamped(45.0, 150.0)
            let light = (totalSleep - deep - rem).atLeast(60.0)
            let disturbances = Int(gauss(&rng, 6.0, 3.0).clamped(0.0, 18.0))

            // --- autonomic markers ---
            let hrv = (gauss(&rng, 78.0 + fitness * 1.5, 12.0) + (weekend ? 6 : 0) - Double(nWorkouts) * 4)
                .clamped(28.0, 150.0)
            let rhr = Int((gauss(&rng, 56.0 - fitness * 0.4, 3.0) + Double(nWorkouts) * 1.2).clamped(42.0, 70.0))
            let spo2 = gauss(&rng, 96.5, 0.8).clamped(93.0, 100.0)
            let skinTempDev = gauss(&rng, 0.0, 0.25).clamped(-1.2, 1.4)
            let resp = gauss(&rng, 14.6, 0.9).clamped(11.0, 19.0)

            // --- recovery: a function of HRV, sleep quality and resting-HR ---
            let recovery = (
                40 + (hrv - 70) * 0.55 + (efficiency - 85) * 0.6 + (totalSleep - 420) * 0.03 -
                    (Double(rhr) - 55) * 1.4 - Double(disturbances) * 0.8 + gauss(&rng, 0.0, 5.0)
            ).clamped(8.0, 99.0)

            // --- strain (Effort): workout-driven, rescaled 0–21 → 0–100 ---
            let strain = (
                (nWorkouts == 0 ? gauss(&rng, 7.5, 1.8)
                 : gauss(&rng, 13.5, 2.4) + Double(nWorkouts - 1) * 2.5) * STRAIN_SCALE
            ).clamped(3.0 * STRAIN_SCALE, 100.0)

            daily.append(DailyMetric(
                day: day, totalSleepMin: round1(totalSleep), efficiency: round1(efficiency),
                deepMin: round1(deep), remMin: round1(rem), lightMin: round1(light),
                disturbances: disturbances, restingHr: rhr, avgHrv: round1(hrv),
                recovery: round1(recovery), strain: round1(strain), exerciseCount: nWorkouts,
                spo2Pct: round1(spo2), skinTempDevC: round2(skinTempDev), respRateBpm: round1(resp)))

            // --- sleep session: previous night ~23:10 → wake, with a REAL stage timeline so the
            //     hypnogram renders the computed segment path (not just the proportional bar). ---
            let onsetBase = cal.date(byAdding: .day, value: -1, to: date)!
            let onsetDay = cal.startOfDay(for: onsetBase)
            let onset = Int(onsetDay.timeIntervalSince1970) + 23 * 3600 + 10 * 60 + rng.nextInt(-1800, 1800)
            let inBedSec = Int((totalSleep + totalSleep * (100 - efficiency) / 100) * 60)
            sleeps.append(CachedSleepSession(
                startTs: onset, endTs: onset + inBedSec,
                efficiency: round1(efficiency), restingHr: rhr, avgHrv: round1(hrv),
                stagesJSON: segmentsJSON(onset: onset, deep: deep, rem: rem, light: light,
                                         awakeMin: Double(disturbances) * 1.6)))

            // --- long-format extras (body composition) under my-whoop ---
            weight += gauss(&rng, -0.02, 0.18)
            series.append(MetricPoint(day: day, key: "weightKg", value: round2(weight)))
            series.append(MetricPoint(day: day, key: "bodyFatPct",
                value: round1((18.0 - fitness * 0.2 + gauss(&rng, 0.0, 0.4)).clamped(10.0, 24.0))))
            // Export-verbatim sleep figures (same metricSeries keys the importers write), so the demo
            // Sleep tiles exercise the prefer-imported path.
            let demoNeedMin = (totalSleep + gauss(&rng, 25.0, 20.0)).clamped(420.0, 560.0)
            series.append(MetricPoint(day: day, key: "sleep_performance",
                value: round1(min(totalSleep / demoNeedMin * 100.0, 100.0))))
            series.append(MetricPoint(day: day, key: "sleep_consistency",
                value: round1(gauss(&rng, 80.0, 8.0).clamped(40.0, 100.0))))
            series.append(MetricPoint(day: day, key: "sleep_need_min", value: round1(demoNeedMin)))
            series.append(MetricPoint(day: day, key: "sleep_debt_min",
                value: round1((demoNeedMin - totalSleep).atLeast(0.0))))

            // --- Apple Health daily aggregate ---
            let steps = Int(gauss(&rng, 8500.0, 2600.0).clamped(1200.0, 19000.0))
            appleRows.append(AppleDaily(
                day: day, steps: steps,
                activeKcal: round1((Double(steps) * 0.045 + Double(nWorkouts) * 220).clamped(120.0, 1400.0)),
                basalKcal: round1(gauss(&rng, 1650.0, 40.0)),
                vo2max: round1((46 + fitness * 0.3 + gauss(&rng, 0.0, 0.5)).clamped(38.0, 56.0)),
                avgHr: Int(gauss(&rng, 72.0, 5.0)), maxHr: Int(gauss(&rng, 150.0, 12.0)),
                walkingHr: Int(gauss(&rng, 108.0, 6.0)), weightKg: round2(weight)))

            // --- workouts on training days ---
            for k in 0..<nWorkouts {
                let sport = SPORTS[rng.nextInt(0, SPORTS.count)]
                let durSec = gauss(&rng, 48.0, 16.0).clamped(18.0, 110.0) * 60
                let hour = weekend ? 9 : 18
                let dayStart = cal.startOfDay(for: date)
                let start = Int(dayStart.timeIntervalSince1970) + hour * 3600 + rng.nextInt(0, 50) * 60 + k * 3600
                let avg = Int(gauss(&rng, 138.0, 12.0))
                let src = rng.nextDouble() < 0.7 ? whoop : apple
                let zonesJSON: String? = src == whoop ? {
                    let z = [gauss(&rng, 15.0, 5.0), gauss(&rng, 30.0, 8.0), gauss(&rng, 28.0, 8.0),
                             gauss(&rng, 15.0, 6.0), gauss(&rng, 6.0, 3.0)].map { $0.clamped(0.0, 100.0) }
                    return "{\"zone1\":\(round1(z[0])),\"zone2\":\(round1(z[1])),\"zone3\":\(round1(z[2])),\"zone4\":\(round1(z[3])),\"zone5\":\(round1(z[4]))}"
                }() : nil
                workouts.append(WorkoutRow(
                    startTs: start, endTs: start + Int(durSec), sport: sport, source: src,
                    durationS: round1(durSec),
                    energyKcal: round1((durSec / 60) * gauss(&rng, 9.0, 2.0)),
                    avgHr: avg, maxHr: avg + Int(gauss(&rng, 22.0, 6.0)),
                    strain: round1((strain * gauss(&rng, 0.6, 0.1)).clamped(4.0 * STRAIN_SCALE, 100.0)),
                    distanceM: DISTANCE_SPORTS.contains(sport) ? round1(gauss(&rng, 6500.0, 2500.0).atLeast(500.0)) : nil,
                    zonesJSON: zonesJSON, notes: nil, steps: nil))
            }

            // --- journal answers for the recent 40 days (real catalog strings → Insights light up) ---
            if i >= DAYS - 40 {
                journal.append(JournalEntry(day: day, question: "Did you drink any alcohol?", answeredYes: rng.nextDouble() < 0.18, notes: nil))
                journal.append(JournalEntry(day: day, question: "Did you have caffeine late in the day?", answeredYes: rng.nextDouble() < 0.30, notes: nil))
                journal.append(JournalEntry(day: day, question: "Did you feel stressed?", answeredYes: rng.nextDouble() < 0.28, notes: nil))
            }
        }

        // --- weekly Fitness Age + VO2max estimate (the engine stamps these on each week's
        //     Saturday; mirror that here so the Fitness Age screen renders under --demo-seed).
        //     Trends from ~42 → ~36 (younger) as the demo "fitness" drift climbs; vo2max ~44 → ~50.
        var fitnessAge = 42.0
        var vo2 = 44.0
        var vitality = 55.0      // weekly Vitality (0–100) trending up as the demo habits improve
        var bodyAgeDemo = 40.0   // Body Age (years) trending down (younger)
        for i in 0..<DAYS {
            let date = cal.date(byAdding: .day, value: i, to: startDay)!
            guard cal.component(.weekday, from: date) == 7 else { continue }  // 7 = Saturday
            let day = isoFmt.string(from: date)
            series.append(MetricPoint(day: day, key: "fitness_age",
                value: round1((fitnessAge + gauss(&rng, 0.0, 0.3)).clamped(34.0, 44.0))))
            series.append(MetricPoint(day: day, key: "vo2max_est",
                value: round1((vo2 + gauss(&rng, 0.0, 0.4)).clamped(42.0, 52.0))))
            series.append(MetricPoint(day: day, key: "vitality",
                value: round1((vitality + gauss(&rng, 0.0, 1.0)).clamped(40.0, 80.0))))
            series.append(MetricPoint(day: day, key: "body_age",
                value: round1((bodyAgeDemo + gauss(&rng, 0.0, 0.3)).clamped(30.0, 45.0))))
            fitnessAge -= 0.75  // ~6 yr younger across the 8 seeded Saturdays
            vo2 += 0.75
            vitality += 2.0
            bodyAgeDemo -= 0.6
        }

        _ = try await store.upsertDailyMetrics(daily, deviceId: whoop)
        _ = try await store.upsertSleepSessions(sleeps, deviceId: whoop)
        _ = try await store.upsertMetricSeries(series, deviceId: whoop)
        _ = try await store.upsertAppleDaily(appleRows, deviceId: apple)
        if !workouts.isEmpty { _ = try await store.upsertWorkouts(workouts, deviceId: whoop) }
        if !journal.isEmpty { _ = try await store.upsertJournal(journal, deviceId: whoop) }
        NSLog("AppleDemoSeeder: seeded \(daily.count) days, \(workouts.count) workouts.")
    }

    // MARK: - helpers

    private static func round1(_ x: Double) -> Double { (x * 10).rounded() / 10 }
    private static func round2(_ x: Double) -> Double { (x * 100).rounded() / 100 }

    /// Box–Muller normal sample, matching DemoSeeder.gauss exactly.
    private static func gauss(_ rng: inout SplitMix64, _ mean: Double, _ sd: Double) -> Double {
        let u1 = rng.nextDouble().clamped(1e-9, 1.0)
        let u2 = rng.nextDouble()
        return mean + sd * (Foundation.sqrt(-2.0 * Foundation.log(u1)) * Foundation.cos(2.0 * Double.pi * u2))
    }

    /// A plausible light→deep→rem cycle as the COMPUTED segment array
    /// [{"start":epoch,"end":epoch,"stage":"light"|"deep"|"rem"|"wake"}] that SleepNightDecoding.decodeSegments
    /// reads, laid end-to-end from `onset`.
    private static func segmentsJSON(onset: Int, deep: Double, rem: Double, light: Double, awakeMin: Double) -> String {
        var t = onset
        var parts: [String] = []
        func seg(_ stage: String, _ minutes: Double) {
            let secs = Int(minutes * 60)
            guard secs > 0 else { return }
            parts.append("{\"start\":\(t),\"end\":\(t + secs),\"stage\":\"\(stage)\"}")
            t += secs
        }
        seg("light", light * 0.35); seg("deep", deep * 0.6); seg("light", light * 0.30)
        seg("rem", rem * 0.6); seg("deep", deep * 0.4); seg("light", light * 0.35)
        seg("rem", rem * 0.4); seg("wake", awakeMin)
        return "[" + parts.joined(separator: ",") + "]"
    }
}

/// Deterministic SplitMix64 PRNG — gives a fixed, reproducible demo dataset across runs (the Apple
/// counterpart of Kotlin's `Random(0xC0FFEE)`). Not for any security use.
struct SplitMix64 {
    private var state: UInt64
    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state = state &+ 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }

    /// Uniform in [0, 1).
    mutating func nextDouble() -> Double {
        Double(next() >> 11) * (1.0 / 9007199254740992.0)  // 2^53
    }

    /// Uniform Int in [lower, upper).
    mutating func nextInt(_ lower: Int, _ upper: Int) -> Int {
        guard upper > lower else { return lower }
        let span = UInt64(upper - lower)
        return lower + Int(next() % span)
    }
}

private extension Double {
    func clamped(_ lo: Double, _ hi: Double) -> Double { Swift.min(Swift.max(self, lo), hi) }
    func atLeast(_ lo: Double) -> Double { Swift.max(self, lo) }
}
#endif
