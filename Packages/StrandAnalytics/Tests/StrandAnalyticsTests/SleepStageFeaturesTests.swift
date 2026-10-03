import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// `SleepStageFeatures` feeds a model trained on other hardware, so what these pin is PORTABILITY: the
/// features must not move when the things that differ between a dataset's device and a strap move — the
/// accelerometer's scale, the heart-rate cadence, the wearer's resting pulse, the clock, the order rows
/// arrive in. They say nothing about whether a feature separates sleep stages; `Tools/SleepML` measures that.
final class SleepStageFeaturesTests: XCTestCase {

    // MARK: - fixtures

    private let start = 1_700_000_017          // deliberately off the 30 s grid
    private let duration = 2 * 3600

    /// Gravity at 1 Hz from 40 min before the window to 40 min after: a still wrist with sensor noise, a
    /// turn every 23 minutes and a short burst of movement every 7.
    private func gravity(scale: Double = 1) -> [GravitySample] {
        var rng = SplitMix(seed: 7)
        return ((start - 2400)..<(start + duration + 2400)).map { t in
            let k = t - start
            let side = (Double(k) / 1380).rounded(.down).truncatingRemainder(dividingBy: 2) == 0 ? 1.0 : -1.0
            let burst = (k % 420) < 6 ? 0.2 : 0.0
            func n() -> Double { (rng.unit() - 0.5) * (0.002 + burst) }
            return GravitySample(ts: t, x: scale * (0.3 * side + n()), y: scale * (0.1 + n()),
                                 z: scale * (0.94 + n()))
        }
    }

    /// Heart rate at `every`-second cadence: a slow overnight fall, a ten-minute wave and a slower one.
    private func heartRate(every: Int = 1, offset: Int = 0) -> [HRSample] {
        stride(from: start - 2400, to: start + duration + 2400, by: every).map { t in
            let k = Double(t - start)
            let bpm = 58 - 6 * k / Double(duration) + 4 * sin(k / 95) + 2 * sin(k / 1700)
            return HRSample(ts: t, bpm: Int(bpm.rounded()) + offset)
        }
    }

    private func intervals() -> [RRInterval] {
        ((start - 2400)..<(start + duration + 2400)).map { t in
            RRInterval(ts: t, rrMs: 1000 + Int(45 * sin(2 * Double.pi * Double(t) / 4.2)) + (t % 7) * 3)
        }
    }

    private func column(_ rows: [SleepStageFeatures.Row], _ name: String) -> [Double?] {
        let i = SleepStageFeatures.names.firstIndex(of: name)!
        return rows.map { $0.values[i] }
    }

    private func assertSame(_ a: [SleepStageFeatures.Row], _ b: [SleepStageFeatures.Row],
                            accuracy: Double, skip: Set<String> = [], file: StaticString = #filePath,
                            line: UInt = #line) {
        XCTAssertEqual(a.count, b.count, file: file, line: line)
        for (ra, rb) in zip(a, b) {
            for (i, name) in SleepStageFeatures.names.enumerated() where !skip.contains(name) {
                switch (ra.values[i], rb.values[i]) {
                case (nil, nil): continue
                case let (x?, y?): XCTAssertEqual(x, y, accuracy: accuracy, "\(name) at \(ra.start)", file: file, line: line)
                default: XCTFail("\(name) present in one and missing in the other at \(ra.start)", file: file, line: line)
                }
            }
        }
    }

    // MARK: - shape

    func testOneRowPerGridEpochAndOneValuePerName() {
        let rows = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(),
                                           hr: heartRate(), rr: intervals())
        let firstEpoch = ((start + 29) / 30) * 30
        XCTAssertEqual(rows.first?.start, firstEpoch)
        XCTAssertEqual(rows.count, (start + duration - firstEpoch + 29) / 30)
        for (i, r) in rows.enumerated() {
            XCTAssertEqual(r.start, firstEpoch + 30 * i)
            XCTAssertEqual(r.values.count, SleepStageFeatures.names.count)
            for v in r.values { if let v = v { XCTAssertTrue(v.isFinite) } }
        }
        XCTAssertEqual(Set(SleepStageFeatures.names).count, SleepStageFeatures.names.count)
    }

    func testEmptyAndInvertedWindowsYieldNothing() {
        XCTAssertTrue(SleepStageFeatures.rows(start: start, end: start, grav: gravity(), hr: heartRate(), rr: []).isEmpty)
        XCTAssertTrue(SleepStageFeatures.rows(start: start, end: start - 60, grav: [], hr: [], rr: []).isEmpty)
    }

    func testNoStreamsStillGiveARowPerEpochWithOnlyTheClock() {
        let rows = SleepStageFeatures.rows(start: start, end: start + 3600, grav: [], hr: [], rr: [])
        XCTAssertFalse(rows.isEmpty)
        for r in rows {
            for (i, name) in SleepStageFeatures.names.enumerated() {
                if ["minutes", "fraction", "minutes_left"].contains(name) { XCTAssertNotNil(r.values[i]) }
                else { XCTAssertNil(r.values[i], name) }
            }
        }
    }

    // MARK: - beat intervals are optional

    func testWithoutIntervalsOnlyTheIntervalFeaturesAreMissing() {
        let with = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(),
                                           hr: heartRate(), rr: intervals())
        let without = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(),
                                              hr: heartRate(), rr: [])
        let rrNames: Set<String> = ["rr_rmssd_5m_z", "rr_sdnn_5m_z", "rr_resp_reg_z"]
        assertSame(with, without, accuracy: 0, skip: rrNames)
        for name in rrNames {
            XCTAssertTrue(column(without, name).allSatisfy { $0 == nil }, name)
            XCTAssertTrue(column(with, name).contains { $0 != nil }, name)
        }
    }

    // MARK: - portability

    func testMotionFeaturesIgnoreTheAccelerometerScale() {
        let a = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(), hr: heartRate(), rr: [])
        let b = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(scale: 3.7),
                                        hr: heartRate(), rr: [])
        assertSame(a, b, accuracy: 1e-6)
    }

    func testHeartRateFeaturesIgnoreTheRestingLevel() {
        let a = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(), hr: heartRate(), rr: [])
        let b = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(),
                                        hr: heartRate(offset: 17), rr: [])
        // A rank moves by a place or two where two bin means that tied exactly no longer do in floating
        // point; everything else is the same number.
        let ranks: Set<String> = ["hr_rank", "hr_sd_11m_rank"]
        assertSame(a, b, accuracy: 1e-9, skip: ranks)
        for name in ranks {
            for (x, y) in zip(column(a, name), column(b, name)) {
                XCTAssertEqual(x ?? -1, y ?? -1, accuracy: 0.01, name)
            }
        }
    }

    /// A source reporting every 5 s and one reporting every second describe the same night. The bins are
    /// 10 s, so the two agree up to how a 10 s mean of whole-bpm samples differs between 2 and 10 of them.
    func testHeartRateFeaturesBarelyMoveWithTheSourceCadence() {
        let a = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(), hr: heartRate(), rr: [])
        let b = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(),
                                        hr: heartRate(every: 5), rr: [])
        for name in ["hr_z", "hr_z_30m", "hr_sd_5m_z", "hr_step_prev", "hr_step_next"] {
            let x = column(a, name).compactMap { $0 }, y = column(b, name).compactMap { $0 }
            XCTAssertEqual(x.count, y.count, name)
            let gap = zip(x, y).map { abs($0 - $1) }.reduce(0, +) / Double(x.count)
            XCTAssertLessThan(gap, 0.25, "\(name): mean absolute difference between cadences, in night units")
        }
    }

    func testAShiftOfTheClockByWholeEpochsChangesNothing() {
        let a = SleepStageFeatures.rows(start: start, end: start + duration, grav: gravity(),
                                        hr: heartRate(), rr: intervals())
        let by = 30 * 4321
        let b = SleepStageFeatures.rows(
            start: start + by, end: start + duration + by,
            grav: gravity().map { GravitySample(ts: $0.ts + by, x: $0.x, y: $0.y, z: $0.z) },
            hr: heartRate().map { HRSample(ts: $0.ts + by, bpm: $0.bpm) },
            rr: intervals().map { RRInterval(ts: $0.ts + by, rrMs: $0.rrMs) })
        XCTAssertEqual(a.map { $0.values }, b.map { $0.values })
        XCTAssertEqual(a.map { $0.start + by }, b.map { $0.start })
    }

    func testRowsOutsideTheReachAndTheirOrderDoNotMatter() {
        let g = gravity(), h = heartRate(), r = intervals()
        let a = SleepStageFeatures.rows(start: start, end: start + duration, grav: g, hr: h, rr: r)
        let far = start - 86_400
        let b = SleepStageFeatures.rows(
            start: start, end: start + duration,
            grav: (g + [GravitySample(ts: far, x: 9, y: 9, z: 9)]).reversed(),
            hr: (h + [HRSample(ts: far, bpm: 190)]).reversed(),
            rr: r + [RRInterval(ts: far, rrMs: 300)])
        assertSame(a, b, accuracy: 1e-9)
    }

    // MARK: - what the context features mean

    func testStillMinutesCountsFromTheLastMovementIncludingBeforeTheWindow() {
        // Movement only in the minutes before the window opens, ending half a minute short of it (the
        // jerk between the last moving second and the first still one belongs to the later second).
        var rng = SplitMix(seed: 3)
        let g = ((start - 1800)..<(start + 3600)).map { t -> GravitySample in
            let moving = t >= start - 300 && t < start - 30
            let n = (rng.unit() - 0.5) * (moving ? 0.5 : 0.0005)
            return GravitySample(ts: t, x: 0.2 + n, y: 0.1 - n, z: 0.95 + n)
        }
        let rows = SleepStageFeatures.rows(start: start, end: start + 3600, grav: g, hr: [], rr: [])
        let still = column(rows, "still_minutes").compactMap { $0 }
        XCTAssertEqual(still.count, rows.count)
        XCTAssertLessThanOrEqual(still[0], 1.5, "the window opens right after the movement")
        XCTAssertGreaterThan(still[0], 0)
        for i in 1..<still.count { XCTAssertEqual(still[i] - still[i - 1], 0.5, accuracy: 1e-9) }
        let moved = column(rows, "move_frac").enumerated().filter { $0.element != 0 }
        XCTAssertTrue(moved.isEmpty, "epochs with movement: \(moved.prefix(5))")
    }
}

/// A fixed-sequence generator, so a fixture is the same on every platform and run.
private struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func unit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return Double((z ^ (z >> 31)) >> 11) / Double(1 << 53)
    }
}
