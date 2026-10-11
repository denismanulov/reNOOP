import Foundation
import WhoopProtocol

// SleepStageLearned.swift — the learned stage model, staging a night in `SleepStagerV2`'s place.
//
// `SleepStagerV2` finds nothing here: when the wearer slept is still the detector's answer. What changes is
// the hypnogram inside an accepted window. The recipe's coefficients were set a priori; the model's were
// fit to human-scored polysomnography (`Tools/SleepML`: 122 people, every one staged by a model trained
// without them — kappa 0.56 against 0.36 for the recipe, stage shares within 1.1 points of the PSG).
//
// The model itself is two boosted-tree classifiers, and running them is the app's business: this package
// links no model runtime and builds where there is none. The app installs the two as plain functions
// from rows to probabilities; everything around them — the features, their surroundings, the decoder and
// the tiling into `StageSegment`s — is here, the same code the training tool ran.
//
// A night the model cannot be trusted with is not staged by it. It was trained on wrists that report
// motion and heart rate nearly every second, so a window either of them covers thinly, and any night
// with no model installed, comes back nil and the caller falls back to the recipe.

/// The two models and the decoder settings fit with them.
public struct SleepStageModel: Sendable {
    /// Class probabilities for each row, in `SleepStageDecoder.stages` order; nil when the model could
    /// not answer. Rows are one night's, in order.
    public typealias Predict = @Sendable (_ rows: [[Double]]) -> [[Double]]?

    /// Names the fit. A night staged under one version is staged again under another, so this goes
    /// into every key that caches a hypnogram.
    public let version: String
    /// Each stage's share of the training epochs, and how much of it the decoder divides out.
    public let prior: [Double]?
    public let weight: Double
    public let smoothing: Double
    /// Takes rows of `SleepStageContext.firstNames`.
    public let first: Predict
    /// Takes rows of `SleepStageContext.secondNames`.
    public let second: Predict

    public init(version: String, prior: [Double]?, weight: Double, smoothing: Double,
                first: @escaping Predict, second: @escaping Predict) {
        self.version = version
        self.prior = prior
        self.weight = weight
        self.smoothing = smoothing
        self.first = first
        self.second = second
    }
}

public enum SleepStageLearned {

    private static let lock = NSLock()
    private static var installed: SleepStageModel?

    /// The model in use, nil until the app installs one. Installed once at launch, before any night is
    /// scored; a later change re-keys every cached hypnogram through `version`.
    public static var model: SleepStageModel? {
        get { lock.lock(); defer { lock.unlock() }; return installed }
        set { lock.lock(); installed = newValue; lock.unlock() }
    }

    /// The installed model's version, empty when there is none: what a cache key carries.
    public static var version: String { model?.version ?? "" }

    /// The least share of a window's seconds that must carry a gravity sample, and of its 10 s bins a
    /// heart-rate sample, before the model stages it. The training wrists covered both nearly in full;
    /// below this the model has not been measured, and a sparse-motion night (a strap that banks motion
    /// coarsely) is the recipe's to stage.
    static let minCoverage = 0.8

    private struct Key: Hashable {
        let version: String
        let start: Int; let end: Int
        let grav: StreamFingerprint; let hr: StreamFingerprint; let rr: StreamFingerprint
    }
    private struct Staged { let segments: [StageSegment]? }
    /// As `SleepStagerV2.stageCache`: the scoring loop and the edited-night self-heal ask for the same
    /// window with the same streams pass after pass.
    private static let cache = AnalyticsMemoCache<Key, Staged>(capacity: 24)

    /// The hypnogram of `[start, end]` by the installed model, tiled as `SleepStagerV2.stageSession`
    /// tiles its own: the first segment begins at `start`, the last ends at `end`. nil when no model is
    /// installed, when motion or heart rate cover the window too thinly, or when the model does not
    /// answer; the caller then stages with the recipe.
    public static func stageSession(start: Int, end: Int, grav: [GravitySample],
                                    hr: [HRSample], rr: [RRInterval]) -> [StageSegment]? {
        guard let model, end > start else { return nil }
        // Only what the features read: the window and `reach` either side.
        let lo = start - SleepStageFeatures.reach - 30, hi = end + SleepStageFeatures.reach + 30
        let gravW = grav.filter { $0.ts >= lo && $0.ts < hi }
        let hrW = hr.filter { $0.ts >= lo && $0.ts < hi }
        let rrW = rr.filter { $0.ts >= lo && $0.ts < hi }
        let key = Key(version: model.version, start: start, end: end,
                      grav: StreamFingerprint.of(gravW, ts: { $0.ts }, quant: {
                          StreamFingerprint.gravityQuant(x: $0.x, y: $0.y, z: $0.z)
                      }),
                      hr: StreamFingerprint.of(hrW, ts: { $0.ts }, quant: { Int($0.bpm) }),
                      rr: StreamFingerprint.of(rrW, ts: { $0.ts }, quant: { Int($0.rrMs) }))
        return cache.value(key) {
            Staged(segments: stageUncached(model, start: start, end: end, grav: gravW, hr: hrW, rr: rrW))
        }.segments
    }

    /// Whether motion and heart rate cover `[start, end)` densely enough for the model.
    static func covers(start: Int, end: Int, grav: [GravitySample], hr: [HRSample]) -> Bool {
        let seconds = end - start
        if seconds <= 0 { return false }
        var moved = [Bool](repeating: false, count: seconds)
        for g in grav where g.ts >= start && g.ts < end { moved[g.ts - start] = true }
        let bins = (seconds + SleepStageFeatures.hrBin - 1) / SleepStageFeatures.hrBin
        var beat = [Bool](repeating: false, count: bins)
        for h in hr where h.ts >= start && h.ts < end { beat[(h.ts - start) / SleepStageFeatures.hrBin] = true }
        return Double(moved.filter { $0 }.count) >= minCoverage * Double(seconds)
            && Double(beat.filter { $0 }.count) >= minCoverage * Double(bins)
    }

    private static func stageUncached(_ model: SleepStageModel, start: Int, end: Int, grav: [GravitySample],
                                      hr: [HRSample], rr: [RRInterval]) -> [StageSegment]? {
        guard covers(start: start, end: end, grav: grav, hr: hr) else { return nil }
        let rows = SleepStageFeatures.rows(start: start, end: end, grav: grav, hr: hr, rr: rr)
        if rows.isEmpty { return nil }
        let xs = SleepStageContext.firstInput(rows)
        guard let p1 = model.first(xs), valid(p1, rows.count),
              let p = model.second(SleepStageContext.secondInput(first: xs, probabilities: p1)),
              valid(p, rows.count) else { return nil }
        let labels = SleepStageDecoder.decode(p, prior: model.prior, weight: model.weight,
                                              smoothing: model.smoothing)
        var segments: [StageSegment] = []
        for (i, row) in rows.enumerated() {
            let segStart = i == 0 ? start : row.start
            let segEnd = i == rows.count - 1 ? end : rows[i + 1].start
            if let last = segments.last, last.stage == labels[i] {
                segments[segments.count - 1].end = segEnd
            } else {
                segments.append(StageSegment(start: segStart, end: segEnd, stage: labels[i]))
            }
        }
        return segments
    }

    /// A model's answer is usable when it is one finite row of four per epoch.
    private static func valid(_ p: [[Double]], _ epochs: Int) -> Bool {
        p.count == epochs && p.allSatisfy { $0.count == SleepStageDecoder.stages.count && $0.allSatisfy { $0.isFinite } }
    }
}
