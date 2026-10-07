import XCTest
@testable import Strand
import StrandAnalytics
import WhoopProtocol

/// The step auto-calibration loop end to end, with the strap replaced by a synthetic walk: 1.5 steps per
/// second on the accelerometer, 1.95 firmware ticks per second in the history (1.30 ticks per step).
@MainActor
final class StepAutoCalibratorTests: XCTestCase {

    private final class Harness {
        let defaults: UserDefaults
        var clock = 1_000
        var history: [StepSample] = []
        var streamOn = 0, streamOff = 0
        var logs: [String] = []
        var pending: [() -> Void] = []
        private(set) var calibrator: StepAutoCalibrator!

        @MainActor
        init(enabled: Bool = true) {
            let name = "StepAutoCalibratorTests-\(UUID().uuidString)"
            defaults = UserDefaults(suiteName: name)!
            defaults.removePersistentDomain(forName: name)
            defaults.set(enabled, forKey: StepCalibrationStore.enabledKey)
            calibrator = StepAutoCalibrator(
                defaults: defaults,
                now: { [unowned self] in self.clock },
                tzOffsetSeconds: { 0 },
                startStream: { [unowned self] in self.streamOn += 1 },
                stopStream: { [unowned self] in self.streamOff += 1 },
                steps: { [unowned self] from, to in self.history.filter { (from...to).contains($0.ts) } },
                log: { [unowned self] in self.logs.append($0) },
                schedule: { [unowned self] _, work in self.pending.append(work) })
        }

        /// One history row a second over `range`, the counter climbing 1.95 ticks a second from `origin`.
        func walk(_ range: ClosedRange<Int>, origin: Int = 900) {
            history += range.map { StepSample(ts: $0, counter: Int(1.95 * Double($0 - origin))) }
        }

        /// The raw IMU packet of second `ts` for a 1.5 steps-per-second gait.
        func packet(_ ts: Int) -> Whoop4RawImu.AccelBuffer {
            var x: [Int16] = [], y: [Int16] = [], z: [Int16] = []
            for i in 0..<100 {
                let t = Double(ts) + Double(i) / 100
                let stride = 2 * Double.pi * 0.75 * t, step = 2 * Double.pi * 1.5 * t
                x.append(Int16(4096 * 0.25 * sin(stride)))
                y.append(Int16(4096 * (1 + 0.2 * sin(step))))
                z.append(Int16(4096 * 0.15 * sin(stride + 1.1)))
            }
            return Whoop4RawImu.AccelBuffer(timestamp: ts, subseconds: 0, x: x, y: y, z: z)
        }

        @MainActor
        func runBurst(from first: Int) {
            for ts in first..<(first + StepAutoCalibrator.burstSeconds) { calibrator.accept(packet(ts)) }
        }
    }

    // MARK: - The loop

    func testAWalkIsMeasuredPairedAndLearned() async {
        let h = Harness()
        h.walk(940...998)
        await h.calibrator.offloadSettled(batteryPct: 80, appOnScreen: false)
        XCTAssertEqual(h.streamOn, 1)
        XCTAssertTrue(h.calibrator.wantsFrames)

        h.runBurst(from: 1_001)
        XCTAssertEqual(h.streamOff, 1, "the stream goes off as soon as the window is full")
        XCTAssertFalse(h.calibrator.wantsFrames)

        // The history of those seconds arrives with a later offload.
        h.clock = 1_100
        h.walk(999...1_045)
        await h.calibrator.offloadSettled(batteryPct: 80, appOnScreen: false)

        let state = StepCalibrationStore.load(h.defaults)
        XCTAssertEqual(state.accepted, 1)
        XCTAssertEqual(StepCalibration.factor(state, day: "1970-01-01", manual: 1.0), 1.30, accuracy: 0.03)
        XCTAssertEqual(h.streamOn, 1, "no second burst inside the spacing limit")
    }

    func testNothingHappensWhenNotOptedIn() async {
        let h = Harness(enabled: false)
        h.walk(940...998)
        await h.calibrator.offloadSettled(batteryPct: 80, appOnScreen: false)
        XCTAssertEqual(h.streamOn, 0)
        XCTAssertFalse(h.calibrator.wantsFrames)
    }

    func testNoBurstWithoutWalkingOrOnALowBattery() async {
        let still = Harness()
        still.history = (940...998).map { StepSample(ts: $0, counter: 500) }
        await still.calibrator.offloadSettled(batteryPct: 80, appOnScreen: false)
        XCTAssertEqual(still.streamOn, 0)

        let low = Harness()
        low.walk(940...998)
        await low.calibrator.offloadSettled(batteryPct: 10, appOnScreen: false)
        XCTAssertEqual(low.streamOn, 0)
        XCTAssertTrue(low.logs.contains { $0.contains("strap battery 10%") }, "a refusal names its reason")

        let stale = Harness()
        stale.walk(880...940)                 // the newest record is a minute old
        await stale.calibrator.offloadSettled(batteryPct: 80, appOnScreen: false)
        XCTAssertEqual(stale.streamOn, 0)
    }

    func testNoBurstWhileTheAppIsOnScreenButWaitingMeasurementsStillPair() async {
        let h = Harness()
        let waiting = [StepAutoCalibrator.Pending(startTs: 1_001, endTs: 1_041, stepHz: 1.5)]
        h.defaults.set(try? JSONEncoder().encode(waiting), forKey: StepAutoCalibrator.pendingKey)
        h.clock = 1_100
        h.walk(940...1_098)
        await h.calibrator.offloadSettled(batteryPct: 80, appOnScreen: true)
        XCTAssertEqual(h.streamOn, 0)
        XCTAssertTrue(h.logs.contains { $0.contains("the app is on screen") })
        XCTAssertEqual(StepCalibrationStore.load(h.defaults).accepted, 1)
    }

    func testNoRawFramesAbandonsTheBurstAndSwitchesTheStreamOff() async {
        let h = Harness()
        h.walk(940...998)
        await h.calibrator.offloadSettled(batteryPct: 80, appOnScreen: false)
        h.pending.forEach { $0() }            // the first-frame timeout
        XCTAssertEqual(h.streamOff, 1)
        XCTAssertFalse(h.calibrator.wantsFrames)
        XCTAssertNil(h.defaults.data(forKey: StepAutoCalibrator.pendingKey).flatMap {
            try? JSONDecoder().decode([StepAutoCalibrator.Pending].self, from: $0)
        }?.first)
    }

    func testBurstsAreLimitedPerDayAndSpacedApart() async {
        let h = Harness()
        for round in 0..<6 {
            h.clock = 1_000 + round * (StepAutoCalibrator.minSpacingSeconds + 60)
            h.history = []
            h.walk((h.clock - 60)...(h.clock - 2), origin: h.clock - 200)
            await h.calibrator.offloadSettled(batteryPct: 80, appOnScreen: false)
            h.pending.forEach { $0() }        // let each burst time out, freeing the calibrator
            h.pending = []
        }
        XCTAssertEqual(h.streamOn, StepAutoCalibrator.maxBurstsPerDay)
    }

    func testAnOctaveWrongGaitIsRefusedNotLearned() async {
        let h = Harness()
        // A waiting measurement that claims twice the real cadence over a window the history covers.
        let wrong = [StepAutoCalibrator.Pending(startTs: 1_001, endTs: 1_041, stepHz: 3.0)]
        h.defaults.set(try? JSONEncoder().encode(wrong), forKey: StepAutoCalibrator.pendingKey)
        h.clock = 1_100
        h.walk(940...1_045)
        await h.calibrator.offloadSettled(batteryPct: 10, appOnScreen: false)
        XCTAssertEqual(StepCalibrationStore.load(h.defaults).accepted, 0)
        XCTAssertTrue(h.logs.contains { $0.contains("refused") })
    }

    // MARK: - Pairing rules

    private func steady(_ range: ClosedRange<Int>) -> [StepSample] {
        range.map { StepSample(ts: $0, counter: 2 * ($0 - 900)) }
    }
    private let window = StepAutoCalibrator.Pending(startTs: 1_000, endTs: 1_040, stepHz: 1.5)

    func testTicksOverACoveredSteadyWindow() {
        XCTAssertEqual(try? StepAutoCalibrator.ticks(for: window, in: steady(990...1_042)).get(), 80)
    }

    func testAWindowWithHistoryGapsIsNotPaired() {
        let gappy = steady(990...1_042).filter { !(1_010...1_020).contains($0.ts) }
        XCTAssertEqual(StepAutoCalibrator.ticks(for: window, in: gappy), .failure(.historyHasGaps))
    }

    func testAWalkThatStartedInsideTheWindowIsNotPaired() {
        // Flat until the window starts: whatever the counter then adds includes a buffered release.
        let flat = (990...1_000).map { StepSample(ts: $0, counter: 200) }
        let after = (1_001...1_042).map { StepSample(ts: $0, counter: 200 + 2 * ($0 - 1_000)) }
        XCTAssertEqual(StepAutoCalibrator.ticks(for: window, in: flat + after), .failure(.walkNotUnderWay))
    }

    func testAReleaseInsideTheWindowIsNotPaired() {
        var samples = steady(990...1_042)
        for index in samples.indices where samples[index].ts >= 1_020 {
            samples[index] = StepSample(ts: samples[index].ts, counter: samples[index].counter + 12)
        }
        XCTAssertEqual(StepAutoCalibrator.ticks(for: window, in: samples), .failure(.releaseInsideWindow))
    }

    func testWalkingNowNeedsFreshClimbingHistory() {
        XCTAssertTrue(StepAutoCalibrator.isWalkingNow(steady(960...995), now: 1_000))
        XCTAssertFalse(StepAutoCalibrator.isWalkingNow(steady(900...950), now: 1_000))
        XCTAssertFalse(StepAutoCalibrator.isWalkingNow(
            (960...995).map { StepSample(ts: $0, counter: 7) }, now: 1_000))
        XCTAssertFalse(StepAutoCalibrator.isWalkingNow([], now: 1_000))
    }

    func testLongestContiguousRunSkipsGapsAndDuplicates() {
        let h = Harness()
        let seconds = [10, 11, 12, 12, 20, 21, 22, 23, 24, 30]
        let run = StepAutoCalibrator.longestContiguousRun(seconds.map(h.packet))
        XCTAssertEqual(run.map(\.timestamp), [20, 21, 22, 23, 24])
    }

    // MARK: - Which divisor a day uses

    func testTheStoreAnswersWithTheManualDivisorUnlessOptedIn() {
        let h = Harness(enabled: false)
        let learned = StepCalibration.recorded(StepCalibration.State(), day: "2026-10-03",
                                               steps: 100, ticks: 130)
        StepCalibrationStore.save(learned, h.defaults)
        XCTAssertEqual(StepCalibrationStore.snapshot(manual: 1.0, today: "2026-10-03", defaults: h.defaults)
            .factor(day: "2026-10-03"), 1.0)

        h.defaults.set(true, forKey: StepCalibrationStore.enabledKey)
        let snapshot = StepCalibrationStore.snapshot(manual: 1.0, today: "2026-10-04", defaults: h.defaults)
        XCTAssertEqual(snapshot.factor(day: "2026-10-03"), 1.30, accuracy: 1e-9)   // frozen on the way
        XCTAssertEqual(snapshot.factor(day: "2026-10-04"), 1.30, accuracy: 1e-9)   // the long-run factor
        XCTAssertEqual(snapshot.factor(day: "2026-09-01"), 1.0)                    // before anything was learned
    }
}
