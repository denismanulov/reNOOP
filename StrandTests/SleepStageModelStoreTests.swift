import XCTest
import Foundation
import WhoopProtocol
import WhoopStore
import StrandAnalytics
@testable import Strand

/// The bundled stage models, loaded the way the app loads them. What these pin is that the models in
/// the bundle are the ones this build's features feed: the column names line up, a night goes in and a
/// hypnogram comes out, and the pair is named by its bytes so a refit re-stages the history once.
@MainActor
final class SleepStageModelStoreTests: XCTestCase {

    override func tearDown() {
        // The host app installs the pair at launch; a test that replaced it puts it back.
        SleepStageLearned.model = SleepStageModelStore.load(from: .main)
        super.tearDown()
    }

    /// Three hours of a still wrist that turns over every 23 minutes, under a slowly falling pulse.
    private func night(start: Int, seconds: Int) -> (grav: [GravitySample], hr: [HRSample]) {
        let grav = ((start - 2400)..<(start + seconds + 2400)).map { t -> GravitySample in
            let k = t - start
            let side = ((k + 2400) / 1380) % 2 == 0 ? 1.0 : -1.0
            let burst = (k % 1380) < 8 ? 0.15 * sin(Double(k)) : 0
            return GravitySample(ts: t, x: 0.3 * side + burst, y: 0.1 + 0.001 * sin(Double(t) / 7), z: 0.94)
        }
        let hr = ((start - 2400)..<(start + seconds + 2400)).map { t -> HRSample in
            let k = Double(t - start)
            return HRSample(ts: t, bpm: Int((58 - 6 * k / Double(seconds) + 4 * sin(k / 95) + 2 * sin(k / 1700)).rounded()))
        }
        return (grav, hr)
    }

    func testTheBundledPairLoadsAndIsNamedByItsBytes() throws {
        let a = try XCTUnwrap(SleepStageModelStore.load(from: .main))
        let b = try XCTUnwrap(SleepStageModelStore.load(from: .main))
        XCTAssertFalse(a.version.isEmpty)
        XCTAssertEqual(a.version, b.version)
        XCTAssertEqual(a.prior?.count, SleepStageDecoder.stages.count)
        XCTAssertEqual(a.prior?.reduce(0, +) ?? 0, 1, accuracy: 1e-3)
        XCTAssertTrue((0...1).contains(a.weight))
        XCTAssertTrue((0...1).contains(a.smoothing))
    }

    func testABundleWithoutTheModelsLoadsNothing() {
        XCTAssertNil(SleepStageModelStore.load(from: Bundle(for: SleepStageModelStoreTests.self)))
    }

    /// Each model answers one row of four probabilities per epoch for rows of this build's columns.
    func testTheModelsTakeThisBuildsColumns() throws {
        let model = try XCTUnwrap(SleepStageModelStore.load(from: .main))
        let start = 1_749_513_600 + 2 * 3600 + 17
        let n = night(start: start, seconds: 3 * 3600)
        let rows = SleepStageFeatures.rows(start: start, end: start + 3 * 3600, grav: n.grav, hr: n.hr, rr: [])
        let xs = SleepStageContext.firstInput(rows)
        let p1 = try XCTUnwrap(model.first(xs))
        XCTAssertEqual(p1.count, rows.count)
        let p2 = try XCTUnwrap(model.second(SleepStageContext.secondInput(first: xs, probabilities: p1)))
        XCTAssertEqual(p2.count, rows.count)
        for p in p1 + p2 {
            XCTAssertEqual(p.count, 4)
            XCTAssertEqual(p.reduce(0, +), 1, accuracy: 1e-3)
            XCTAssertTrue(p.allSatisfy { $0 >= 0 && $0 <= 1 })
        }
        // A row of the wrong width is refused, not padded.
        XCTAssertNil(model.first(xs.map { Array($0.dropLast()) }))
        XCTAssertNil(model.second(xs))
    }

    func testADenseNightIsStagedByTheModelAndTilesItsWindow() throws {
        SleepStageLearned.model = try XCTUnwrap(SleepStageModelStore.load(from: .main))
        let start = 1_749_513_600 + 2 * 3600 + 17, end = start + 3 * 3600
        let n = night(start: start, seconds: 3 * 3600)
        let segs = try XCTUnwrap(SleepStageLearned.stageSession(start: start, end: end, grav: n.grav, hr: n.hr, rr: []))
        XCTAssertEqual(segs.first?.start, start)
        XCTAssertEqual(segs.last?.end, end)
        for (a, b) in zip(segs, segs.dropFirst()) {
            XCTAssertEqual(a.end, b.start)
            XCTAssertNotEqual(a.stage, b.stage)
        }
        XCTAssertTrue(segs.allSatisfy { SleepStageDecoder.stages.contains($0.stage) && $0.end > $0.start })
        // The same night twice is the same hypnogram.
        XCTAssertEqual(SleepStageLearned.stageSession(start: start, end: end, grav: n.grav, hr: n.hr, rr: []), segs)
    }

    func testTheHistoryIsRestagedOncePerModel() {
        XCTAssertFalse(IntelligenceEngine.sleepStageRestageIsPending(installed: "", restaged: nil))
        XCTAssertFalse(IntelligenceEngine.sleepStageRestageIsPending(installed: "", restaged: "abc"))
        XCTAssertTrue(IntelligenceEngine.sleepStageRestageIsPending(installed: "abc", restaged: nil))
        XCTAssertTrue(IntelligenceEngine.sleepStageRestageIsPending(installed: "abc", restaged: "old"))
        XCTAssertFalse(IntelligenceEngine.sleepStageRestageIsPending(installed: "abc", restaged: "abc"))
    }
}

/// Opt-in: the whole stored history of a REAL store copy staged again by the bundled models, through the
/// app's own scoring path. Skipped unless the runner is given a store:
///
///     TEST_RUNNER_NOOP_BENCH_DB=/path/to/whoop.sqlite xcodebuild test … -only-testing:StrandTests/SleepStageModelRealStoreTests
///
/// The file it is given is never touched: the store is copied to a temporary directory first. It prints
/// `STAGES` lines (each stored night before and after, and the pass's wall time). The numbers are the
/// output; the assertions are only that the pass ran, recorded itself, and does not run twice.
@MainActor
final class SleepStageModelRealStoreTests: XCTestCase {
    private let deviceId = "my-whoop"

    private func shares(_ json: String?) -> (minutes: Double, share: [String: Double])? {
        let segs = AnalyticsEngine.decodeStages(json)
        let total = segs.reduce(0.0) { $0 + Double($1.end - $1.start) }
        if total <= 0 { return nil }
        var by: [String: Double] = [:]
        for s in segs { by[s.stage, default: 0] += Double(s.end - s.start) }
        return (total / 60, by.mapValues { 100 * $0 / total })
    }

    func testRealStoreHistoryRestage() async throws {
        guard let src = ProcessInfo.processInfo.environment["NOOP_BENCH_DB"], !src.isEmpty else {
            throw XCTSkip("NOOP_BENCH_DB not set")
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-stages-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dst = dir.appendingPathComponent("whoop.sqlite")
        try FileManager.default.copyItem(atPath: src, toPath: dst.path)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }

        let defaults = UserDefaults.standard
        let keys = [
            "noop.analyzeWatermark", "analyzeRecent.stepsMotionCache.v1",
            RescoreBackgroundScheduler.owedKey, RescoreBackgroundScheduler.owedTokenKey,
            RescoreBackgroundScheduler.lastPassSecondsKey, IntelligenceEngine.sleepStageModelRestagedKey,
        ]
        let saved = keys.map { ($0, defaults.object(forKey: $0)) }
        defer {
            for (key, value) in saved {
                if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
            }
        }
        for key in keys { defaults.removeObject(forKey: key) }

        let store = try await WhoopStore(path: dst.path)
        let repo = Repository(deviceId: deviceId)
        repo.setStoreForTesting(store)
        let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: deviceId)
        let computed = deviceId + "-noop"
        func sessions() async throws -> [CachedSleepSession] {
            try await store.sleepSessions(deviceId: computed, from: 0, to: Int.max / 2, limit: 100_000)
        }
        let before = try await sessions()
        SleepStageModelStore.ensureInstalled()
        let version = SleepStageLearned.version
        XCTAssertFalse(version.isEmpty)

        let t0 = Date()
        await engine.runSleepStageModelRestageIfNeeded()
        let wall = Date().timeIntervalSince(t0)
        let after = try await sessions()
        XCTAssertEqual(defaults.string(forKey: IntelligenceEngine.sleepStageModelRestagedKey), version)

        print("STAGES model \(version) restage wall=\(Int(wall))s sessions before=\(before.count) after=\(after.count)")
        let fmt = ISO8601DateFormatter()
        var pooled: [String: [String: Double]] = ["before": [:], "after": [:]]
        var sameBounds = 0, changedStages = 0
        let afterByStart = Dictionary(after.map { ($0.startTs, $0) }, uniquingKeysWith: { a, _ in a })
        for b in before {
            let a = afterByStart[b.startTs]
            let sb = shares(b.stagesJSON), sa = shares(a?.stagesJSON)
            if let a, a.endTs == b.endTs { sameBounds += 1 }
            if a?.stagesJSON != b.stagesJSON { changedStages += 1 }
            func line(_ s: (minutes: Double, share: [String: Double])?) -> String {
                guard let s else { return "none" }
                return ["wake", "light", "deep", "rem"].map { String(format: "%@ %2.0f", $0, s.share[$0] ?? 0) }
                    .joined(separator: " ") + String(format: " (%.0f min)", s.minutes)
            }
            for (k, s) in [("before", sb), ("after", sa)] {
                if let s { for (st, v) in s.share { pooled[k]![st, default: 0] += v * s.minutes / 100 } }
            }
            print("STAGES \(fmt.string(from: Date(timeIntervalSince1970: TimeInterval(b.startTs))))"
                  + "\(b.userEdited ? " edited" : "") | before \(line(sb)) | after \(a == nil ? "GONE" : line(sa))")
        }
        let added = after.filter { s in !before.contains { $0.startTs == s.startTs } }
        for s in added {
            print("STAGES new session \(fmt.string(from: Date(timeIntervalSince1970: TimeInterval(s.startTs)))) \(shares(s.stagesJSON)?.minutes ?? 0) min")
        }
        for k in ["before", "after"] {
            let total = pooled[k]!.values.reduce(0, +)
            print("STAGES pooled \(k): " + ["wake", "light", "deep", "rem"].map {
                String(format: "%@ %.1f %%", $0, 100 * (pooled[k]![$0] ?? 0) / max(1, total))
            }.joined(separator: ", "))
        }
        print("STAGES of \(before.count) stored sessions: \(sameBounds) kept their bounds, \(changedStages) have new stages, \(added.count) sessions are new")

        // A second call has nothing to do.
        let t1 = Date()
        await engine.runSleepStageModelRestageIfNeeded()
        XCTAssertLessThan(Date().timeIntervalSince(t1), 2)
        let again = try await sessions()
        XCTAssertEqual(again, after)
    }
}
