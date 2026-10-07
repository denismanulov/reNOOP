import Foundation
import StrandAnalytics
import WhoopProtocol

/// Takes the short raw-accelerometer measurements `StepCalibration` learns from, on a WHOOP 4.0.
///
/// One measurement: right after a history offload shows the wearer walking, the raw stream is switched
/// on for `burstSeconds`, `GaitCadence` reads the step frequency from it, and the stream is switched
/// off. The firmware ticks for those same seconds only exist once a later offload brings their history
/// records, so the measurement waits as `Pending` until then.
///
/// WHAT KEEPS IT CHEAP. At most `maxBurstsPerDay` bursts in any 24 hours, at least `minSpacingSeconds`
/// apart, never below `minBatteryPct`, and only when the newest history already shows steady walking. A
/// burst that finds no clear gait costs its seconds and records nothing.
///
/// WHAT KEEPS IT HONEST. A measurement is used only when the history covers its whole window, the walk
/// was already under way before the window (so no buffered release falls inside it), and the resulting
/// ratio passes `StepCalibration.accepts`. Everything else is dropped with one log line saying why.
///
@MainActor
final class StepAutoCalibrator {
    static let pendingKey = "stepAutoCalibration.pending"
    static let burstTimesKey = "stepAutoCalibration.burstTimes"

    static let burstSeconds = 40
    /// A window with fewer contiguous seconds than this is not analysed.
    static let minimumContiguousSeconds = 30
    static let firstFrameTimeout: TimeInterval = 12
    static let maxBurstsPerDay = 4
    static let minSpacingSeconds = 3_600
    /// No burst below this strap charge. A burst is 40 seconds of streaming, so the guard is there for
    /// a strap about to run out, not to save charge in general: a higher floor (30 was tried) kept the
    /// calibration idle for the last third of every charge cycle.
    static let minBatteryPct = 15.0
    /// The newest history record must be this recent for "walking now" to mean now.
    static let freshnessSeconds = 30
    /// Ticks the counter must have added over the last `walkingWindowSeconds` to call it walking.
    static let walkingWindowSeconds = 12
    static let walkingMinimumTicks = 10
    /// A pending measurement whose history never arrives is dropped after this long.
    static let pendingMaxAgeSeconds = 6 * 3_600
    /// The largest one-second increment a steady walk produces; anything above is a buffered release.
    static let maxSteadyTicksPerSecond = 6

    struct Pending: Codable, Equatable {
        let startTs: Int
        let endTs: Int
        let stepHz: Double
    }

    private enum Phase { case idle, awaitingFirstFrame, collecting }

    private let defaults: UserDefaults
    private let now: () -> Int
    private let tzOffsetSeconds: () -> Int
    private let startStream: () -> Void
    private let stopStream: () -> Void
    private let steps: (_ from: Int, _ to: Int) async -> [StepSample]
    private let log: (String) -> Void
    private let schedule: (TimeInterval, @escaping () -> Void) -> Void

    private var phase = Phase.idle
    private var buffers: [Whoop4RawImu.AccelBuffer] = []
    /// Bumped whenever a burst starts or ends, so a timer armed for an earlier one does nothing.
    private var generation = 0
    /// True while `offloadSettled` is between its awaits, so offloads that end back to back cannot pair
    /// the same waiting measurement twice.
    private var settling = false

    init(defaults: UserDefaults = .standard,
         now: @escaping () -> Int = { Int(Date().timeIntervalSince1970) },
         tzOffsetSeconds: @escaping () -> Int = { TimeZone.current.secondsFromGMT() },
         startStream: @escaping () -> Void,
         stopStream: @escaping () -> Void,
         steps: @escaping (_ from: Int, _ to: Int) async -> [StepSample],
         log: @escaping (String) -> Void,
         schedule: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
             DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
         }) {
        self.defaults = defaults
        self.now = now
        self.tzOffsetSeconds = tzOffsetSeconds
        self.startStream = startStream
        self.stopStream = stopStream
        self.steps = steps
        self.log = log
        self.schedule = schedule
    }

    /// True while a burst wants raw frames; the BLE manager decodes them only then.
    var wantsFrames: Bool { phase != .idle }

    // MARK: - Driven by the BLE manager

    /// A history offload finished and none followed it. Pairs waiting measurements with the history
    /// that just arrived, then decides whether to measure now.
    ///
    /// `appOnScreen` is true when the wearer is looking at this app on the phone. No burst is taken
    /// then: that arm is held still, and the factor measured so (1.10 to 1.14 on 2026-10-03, against
    /// 1.23 to 1.27 with the arm free) is not the one the day's other steps were walked with.
    func offloadSettled(batteryPct: Double?, appOnScreen: Bool) async {
        guard !settling else { return }
        settling = true
        defer { settling = false }
        await resolvePending()
        guard StepCalibrationStore.isEnabled(defaults), phase == .idle else { return }
        // Every way out below says why. With the opt-in on, a calibration that never measures has to
        // be explainable from the strap log alone.
        guard !appOnScreen else {
            log("Step calibration: no burst, the app is on screen")
            return
        }
        if let batteryPct, batteryPct < Self.minBatteryPct {
            log("Step calibration: no burst, strap battery \(String(format: "%.0f", batteryPct))% is"
                + " under \(Int(Self.minBatteryPct))%")
            return
        }
        let at = now()
        let recent = burstTimes().filter { at - $0 < 86_400 }
        guard recent.count < Self.maxBurstsPerDay else {
            log("Step calibration: no burst, \(recent.count) already taken in the last 24 h")
            return
        }
        if let last = recent.max(), at - last < Self.minSpacingSeconds {
            log("Step calibration: no burst, the last one was \((at - last) / 60) min ago")
            return
        }
        let history = await steps(at - Self.freshnessSeconds - Self.walkingWindowSeconds - 5, at + 5)
        guard phase == .idle else { return }
        guard Self.isWalkingNow(history, now: at) else {
            log("Step calibration: no burst, the offload does not show walking right now")
            return
        }

        generation += 1
        let started = generation
        phase = .awaitingFirstFrame
        buffers = []
        defaults.set(recent + [at], forKey: Self.burstTimesKey)
        log("Step calibration: walking seen in the offload; raw stream on for \(Self.burstSeconds) s"
            + " (\(recent.count + 1)/\(Self.maxBurstsPerDay) in 24 h)")
        startStream()
        schedule(Self.firstFrameTimeout) { [weak self] in
            guard let self, self.generation == started, self.phase == .awaitingFirstFrame else { return }
            self.end(reason: "no raw frame within \(Int(Self.firstFrameTimeout)) s")
        }
    }

    /// One decoded raw IMU packet. Ignored unless a burst is running.
    func accept(_ buffer: Whoop4RawImu.AccelBuffer) {
        guard phase != .idle else { return }
        if phase == .awaitingFirstFrame {
            phase = .collecting
            let started = generation
            // A backstop: the strap sends one packet a second, so the count below normally ends the
            // burst first. This fires only when packets stop arriving.
            schedule(TimeInterval(Self.burstSeconds + 10)) { [weak self] in
                guard let self, self.generation == started, self.phase == .collecting else { return }
                self.end(reason: nil)
            }
        }
        buffers.append(buffer)
        if Set(buffers.map(\.timestamp)).count >= Self.burstSeconds { end(reason: nil) }
    }

    /// The link dropped mid-burst: the stream is gone with it and the samples are not a full window.
    func disconnected() {
        guard phase != .idle else { return }
        generation += 1
        phase = .idle
        buffers = []
        log("Step calibration: burst abandoned, link dropped")
    }

    // MARK: - Burst end

    private func end(reason: String?) {
        generation += 1
        phase = .idle
        stopStream()
        let collected = buffers
        buffers = []
        if let reason {
            log("Step calibration: burst abandoned, \(reason)")
            return
        }
        let window = Self.longestContiguousRun(collected)
        guard window.count >= Self.minimumContiguousSeconds, let first = window.first,
              let last = window.last else {
            log("Step calibration: burst unusable, only \(window.count) contiguous second(s) of raw data")
            return
        }
        func g(_ keyPath: KeyPath<Whoop4RawImu.AccelBuffer, [Int16]>) -> [Double] {
            window.flatMap { $0[keyPath: keyPath].map { Double($0) * Whoop4RawImu.gPerLSB } }
        }
        let rate = Double(Whoop4RawImu.samplesPerPacket)
        guard let estimate = GaitCadence.estimate(x: g(\.x), y: g(\.y), z: g(\.z), sampleRate: rate) else {
            log("Step calibration: burst had no clear gait over \(window.count) s; nothing recorded")
            return
        }
        let pending = Pending(startTs: first.timestamp, endTs: last.timestamp + 1, stepHz: estimate.stepHz)
        savePending(loadPending() + [pending])
        log("Step calibration: gait \(String(format: "%.2f", estimate.stepHz)) steps/s over"
            + " \(window.count) s (step line \(String(format: "%.2f", estimate.stepStrength)),"
            + " stride line \(String(format: "%.2f", estimate.strideStrength)));"
            + " waiting for the history of those seconds")
    }

    // MARK: - Pairing with history

    private func resolvePending() async {
        let waiting = loadPending()
        guard !waiting.isEmpty else { return }
        var still: [Pending] = []
        for pending in waiting {
            let at = now()
            if at - pending.endTs > Self.pendingMaxAgeSeconds {
                log("Step calibration: measurement dropped, its history never arrived")
                continue
            }
            let history = await steps(pending.startTs - 10, pending.endTs + 2)
            guard let newest = history.map(\.ts).max(), newest >= pending.endTs else {
                still.append(pending)
                continue
            }
            let seconds = pending.endTs - pending.startTs
            let stepCount = pending.stepHz * Double(seconds)
            switch Self.ticks(for: pending, in: history) {
            case .failure(let reason):
                log("Step calibration: measurement dropped, \(reason.rawValue)")
            case .success(let ticks):
                let ratio = Double(ticks) / stepCount
                guard StepCalibration.accepts(steps: stepCount, ticks: Double(ticks)) else {
                    log("Step calibration: measurement refused, \(ticks) ticks for"
                        + " \(String(format: "%.1f", stepCount)) steps is"
                        + " \(String(format: "%.2f", ratio)) per step, outside"
                        + " \(StepCalibration.ratioBand.lowerBound)-\(StepCalibration.ratioBand.upperBound)")
                    continue
                }
                let day = AnalyticsEngine.dayString(pending.startTs, offsetSec: tzOffsetSeconds())
                let state = StepCalibration.recorded(StepCalibrationStore.load(defaults), day: day,
                                                     steps: stepCount, ticks: Double(ticks))
                StepCalibrationStore.save(state, defaults)
                let dayFactor = state.frozen[day] ?? StepCalibration.factor(state, day: day, manual: ratio)
                log("Step calibration: accepted \(ticks) ticks for"
                    + " \(String(format: "%.1f", stepCount)) steps over \(seconds) s"
                    + " (\(String(format: "%.3f", ratio)) per step); \(day) now divides by"
                    + " \(String(format: "%.3f", dayFactor)), \(state.accepted) accepted in all")
            }
        }
        savePending(still)
    }

    enum PairingFailure: String, Error {
        case historyHasGaps = "the history has gaps inside the window"
        case walkNotUnderWay = "the walk was not already under way before the window"
        case releaseInsideWindow = "a buffered release fell inside the window"
    }

    /// Firmware ticks over a pending measurement's window, or why the window cannot be trusted.
    static func ticks(for pending: Pending, in history: [StepSample]) -> Result<Int, PairingFailure> {
        var counter: [Int: Int] = [:]
        for sample in history { counter[sample.ts] = sample.counter }
        let seconds = pending.endTs - pending.startTs
        let inside = (pending.startTs...pending.endTs).compactMap { counter[$0] == nil ? nil : $0 }
        guard let start = counter[pending.startTs], let end = counter[pending.endTs],
              inside.count * 10 >= (seconds + 1) * 9 else { return .failure(.historyHasGaps) }
        guard let before = counter[pending.startTs - 8], (start - before) & 0xFFFF >= 6 else {
            return .failure(.walkNotUnderWay)
        }
        for (earlier, later) in zip(inside, inside.dropFirst()) {
            let perSecond = Double((counter[later]! - counter[earlier]!) & 0xFFFF) / Double(later - earlier)
            if perSecond > Double(maxSteadyTicksPerSecond) { return .failure(.releaseInsideWindow) }
        }
        return .success((end - start) & 0xFFFF)
    }

    /// True when the newest history is fresh and the counter has been climbing through its last seconds.
    static func isWalkingNow(_ history: [StepSample], now: Int) -> Bool {
        let sorted = history.sorted { $0.ts < $1.ts }
        guard let newest = sorted.last, now - newest.ts <= freshnessSeconds,
              let earlier = sorted.last(where: { $0.ts <= newest.ts - walkingWindowSeconds }),
              newest.ts - earlier.ts <= walkingWindowSeconds + 3 else { return false }
        return (newest.counter - earlier.counter) & 0xFFFF >= walkingMinimumTicks
    }

    /// The longest stretch of packets whose timestamps run second by second, one packet per second.
    static func longestContiguousRun(_ buffers: [Whoop4RawImu.AccelBuffer]) -> [Whoop4RawImu.AccelBuffer] {
        var bySecond: [Int: Whoop4RawImu.AccelBuffer] = [:]
        for buffer in buffers where bySecond[buffer.timestamp] == nil { bySecond[buffer.timestamp] = buffer }
        var best: [Whoop4RawImu.AccelBuffer] = [], run: [Whoop4RawImu.AccelBuffer] = []
        for second in bySecond.keys.sorted() {
            if let last = run.last, second != last.timestamp + 1 { run = [] }
            run.append(bySecond[second]!)
            if run.count > best.count { best = run }
        }
        return best
    }

    // MARK: - Persistence

    private func burstTimes() -> [Int] { defaults.array(forKey: Self.burstTimesKey) as? [Int] ?? [] }

    private func loadPending() -> [Pending] {
        guard let data = defaults.data(forKey: Self.pendingKey) else { return [] }
        return (try? JSONDecoder().decode([Pending].self, from: data)) ?? []
    }

    private func savePending(_ pending: [Pending]) {
        defaults.set(try? JSONEncoder().encode(pending), forKey: Self.pendingKey)
    }
}
