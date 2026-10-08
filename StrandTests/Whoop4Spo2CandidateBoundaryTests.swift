import XCTest
import Foundation
import WhoopProtocol
import WhoopStore
import StrandAnalytics
@testable import Strand

/// GUARD for the WHOOP 4.0 strap-computed blood-oxygen byte (v24 `aux_byte_86`).
///
/// The byte is an unvalidated candidate: nothing has been compared with a reference oximeter or with
/// the WHOOP app's nightly figure. Under the rule in AGENTS.md for a signal derived from sensor data it
/// may be stored and shown behind a default-off switch, and must never become the default `spo2Pct` or
/// feed a downstream gate. These score one night twice, with and without the banked bytes, and require
/// that the only thing the bytes change is the `spo2_candidate` series.
@MainActor
final class Whoop4Spo2CandidateBoundaryTests: XCTestCase {
    private let strap = "my-whoop"

    private func withPreferences(candidateOn: Bool, _ body: () async throws -> Void) async throws {
        let defaults = UserDefaults.standard
        let keys = [
            "profile.dateOfBirth", "profile.age", "profile.sex", "profile.weightKg",
            "profile.heightCm", "profile.waistCm", "profile.hrMaxOverride", "profile.stepTicksPerStep",
            "profile.stepsCalibrationCoefficient", "profile.stepsCalibrationSampleDays",
            "profile.stepsCalibrationConfidence", "profile.stepsCalibrationManual",
            "profile.stepsManualCoefficient", "profile.stepsHasBankedMotion",
            "noop.analyzeWatermark", "analyzeRecent.stepsMotionCache.v1",
            "noop.hrvBaselineEpoch", "noop.recoveryBaselineEpoch", UnitPrefs.hrvWindowKey,
            RescoreBackgroundScheduler.owedKey, RescoreBackgroundScheduler.owedTokenKey,
            RescoreBackgroundScheduler.lastPassSecondsKey, DayCycleMode.storageKey,
            PuffinExperiment.experimentalSleepV2Key, PuffinExperiment.motionAwareWakeKey,
            PuffinExperiment.spo2CandidateDisplayKey,
        ]
        let saved = keys.map { ($0, defaults.object(forKey: $0)) }
        defer {
            for (key, value) in saved {
                if let value { defaults.set(value, forKey: key) }
                else { defaults.removeObject(forKey: key) }
            }
        }
        for key in keys { defaults.removeObject(forKey: key) }
        defaults.set(DayCycleMode.midnight.rawValue, forKey: DayCycleMode.storageKey)
        defaults.set(true, forKey: PuffinExperiment.experimentalSleepV2Key)
        defaults.set(false, forKey: PuffinExperiment.motionAwareWakeKey)
        defaults.set(candidateOn, forKey: PuffinExperiment.spo2CandidateDisplayKey)
        try await body()
    }

    /// Yesterday: awake for sixteen hours, then asleep from local midnight for eight.
    private func night() -> (day: String, hr: [HRSample], rr: [RRInterval]) {
        let start = Int(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970) - 86_400
        let day = Repository.localDayKey(Date(timeIntervalSince1970: Double(start)))
        var hr: [HRSample] = []
        var rr: [RRInterval] = []
        for i in 0..<(24 * 3_600) {
            let asleep = i >= 16 * 3_600
            let phase = asleep ? i - 16 * 3_600 : i
            let bpm = asleep ? 64 + Int(sin(Double(phase) / 900) * 5)
                             : 74 + Int(sin(Double(phase) / 500) * 11)
            let ts = start - 16 * 3_600 + i
            hr.append(HRSample(ts: ts, bpm: bpm))
            rr.append(RRInterval(ts: ts, rrMs: 900 + (i.isMultiple(of: 2) ? 16 : -16),
                                 srcChannel: .whoop4Historical))
        }
        return (day, hr, rr)
    }

    /// Two measurement windows inside the detected night, 19 minutes apart: status codes, then
    /// percentages 94 and 96. A third window an hour before the night began must not count.
    private func bytes(inside session: CachedSleepSession) -> [WhoopEvent] {
        var out: [WhoopEvent] = [V24AuxByte86Mapping.event(ts: session.effectiveStartTs - 3_600, byte: 80)]
        for window in 0..<2 {
            let base = session.effectiveStartTs + 600 + window * 1_140
            for (offset, code) in [8, 16, 40, 128, 168].enumerated() {
                out.append(V24AuxByte86Mapping.event(ts: base + offset, byte: code))
            }
            for second in 5..<25 {
                out.append(V24AuxByte86Mapping.event(ts: base + second, byte: window == 0 ? 94 : 96))
            }
        }
        return out
    }

    private struct Scored {
        let daily: DailyMetric
        let series: [String: [MetricPoint]]
        /// The night as the engine stored it, so the bytes can be placed inside the detected span.
        let session: CachedSleepSession
    }

    private func score(bytesInside placed: CachedSleepSession? = nil) async throws -> Scored {
        let store = try await WhoopStore.inMemory()
        try DeviceRegistryStore(dbQueue: store.registryWriter).add(PairedDevice(
            id: strap, brand: "WHOOP", model: "4.0", sourceKind: .liveBLE,
            capabilities: [.hr, .hrv], status: .active, addedAt: 1, lastSeenAt: 1))
        let input = night()
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        let date = try XCTUnwrap(formatter.date(from: input.day))
        let history = (1...8).map { offset in
            DailyMetric(day: formatter.string(from: date.addingTimeInterval(-Double(offset) * 86_400)),
                totalSleepMin: 480, efficiency: 0.9, deepMin: 90, remMin: 90, lightMin: 300,
                disturbances: 0, restingHr: 60, avgHrv: 32 + Double(offset % 3), recovery: 60,
                strain: nil, exerciseCount: nil)
        }
        _ = try await store.upsertDailyMetrics(history, deviceId: strap)
        _ = try await store.insert(
            Streams(hr: input.hr, rr: input.rr, events: placed.map(bytes(inside:)) ?? []),
            deviceId: strap)
        let repo = Repository(deviceId: strap)
        repo.setStoreForTesting(store)
        let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: strap)
        await engine.analyzeRecent(maxDays: 2, force: true)

        let computed = strap + "-noop"
        let rows = try await store.dailyMetrics(deviceId: computed, from: input.day, to: input.day)
        var series: [String: [MetricPoint]] = [:]
        for key in try await store.metricKeys(deviceId: computed) {
            series[key] = try await store.metricSeries(deviceId: computed, key: key,
                                                       from: "0000-00-00", to: "9999-99-99")
        }
        let sessions = try await store.sleepSessions(deviceId: computed, from: 0, to: Int.max / 2, limit: 50)
        return Scored(daily: try XCTUnwrap(rows.first), series: series,
                      session: try XCTUnwrap(sessions.last))
    }

    func testTheBytesWriteOnlyTheCandidateSeries() async throws {
        try await withPreferences(candidateOn: true) {
            let without = try await score()
            let with = try await score(bytesInside: without.session)
            XCTAssertGreaterThan(without.session.endTs - without.session.effectiveStartTs, 2_400,
                                 "the fixture's night must be long enough to hold both windows")

            XCTAssertGreaterThan(with.daily.totalSleepMin ?? 0, 0, "the fixture must actually score a night")
            XCTAssertNotNil(with.daily.recovery, "the fixture must produce a Charge for the guard to mean anything")

            // The whole daily row: `spo2Pct`, recovery, sleep, HRV, strain and every other column.
            XCTAssertNil(with.daily.spo2Pct, "the candidate must never be written as the calibrated SpO2")
            XCTAssertNil(with.daily.spo2Red)
            XCTAssertNil(with.daily.spo2Ir)
            XCTAssertEqual(with.daily, without.daily, "the bytes must not move any stored daily value")

            // Every derived series except the candidate's own, which is where illness and the other
            // downstream readers take their inputs from.
            XCTAssertNil(without.series["spo2_candidate"], "no bytes, no candidate")
            var rest = with.series
            let candidate = rest.removeValue(forKey: "spo2_candidate")
            XCTAssertEqual(rest, without.series, "the bytes must add one series and change no other")

            // 20 readings of 94 and 20 of 96 in band and inside the night. The ten status codes are
            // not percentages, and the 80 an hour before the night is not this night's.
            XCTAssertEqual(candidate?.map(\.value), [95])
            XCTAssertEqual(candidate?.map(\.day), [with.daily.day])
        }
    }

    /// The switch is default-off, and with it off the banked bytes reach no series at all.
    func testWithTheSwitchOffTheBytesReachNothing() async throws {
        try await withPreferences(candidateOn: false) {
            let without = try await score()
            let with = try await score(bytesInside: without.session)
            XCTAssertEqual(with.daily, without.daily)
            XCTAssertEqual(with.series, without.series)
            XCTAssertNil(with.series["spo2_candidate"])
        }
    }
}
