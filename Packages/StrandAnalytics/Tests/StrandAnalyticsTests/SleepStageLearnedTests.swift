import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// `SleepStageLearned` hands a night to the installed model and tiles its answer the way the recipe tiles
/// its own. What these pin is the contract with the caller: a hypnogram that covers the window exactly,
/// or nil — never a guess — whenever the model is absent, starved of data, or does not answer.
final class SleepStageLearnedTests: XCTestCase {

    private let start = 1_700_000_017            // off the 30 s grid, as a detected onset is
    private let duration = 3 * 3600
    private var end: Int { start + duration }

    override func tearDown() {
        SleepStageLearned.model = nil
        super.tearDown()
    }

    private func gravity(every: Int = 1, from: Int? = nil, to: Int? = nil) -> [GravitySample] {
        stride(from: from ?? (start - 2400), to: to ?? (end + 2400), by: every).map { t in
            GravitySample(ts: t, x: 0.3 + 0.001 * sin(Double(t)), y: 0.1, z: 0.94)
        }
    }

    private func heartRate(every: Int = 1) -> [HRSample] {
        stride(from: start - 2400, to: end + 2400, by: every).map { t in
            HRSample(ts: t, bpm: 55 + Int(4 * sin(Double(t - start) / 600)))
        }
    }

    /// A model whose second stage is sure of `stage(epoch index)`, and whose first stage says nothing.
    private func model(version: String = "test", _ stage: @escaping @Sendable (Int) -> String) -> SleepStageModel {
        SleepStageModel(version: version, prior: nil, weight: 1, smoothing: 1,
                        first: { rows in rows.map { _ in [0.25, 0.25, 0.25, 0.25] } },
                        second: { rows in
                            rows.indices.map { i in SleepStageDecoder.stages.map { $0 == stage(i) ? 0.97 : 0.01 } }
                        })
    }

    func testWithoutAModelThereIsNoAnswer() {
        XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(), hr: heartRate(), rr: []))
        XCTAssertEqual(SleepStageLearned.version, "")
    }

    func testTheHypnogramTilesTheWindowExactly() throws {
        SleepStageLearned.model = model { i in i < 100 ? "light" : (i < 220 ? "deep" : (i < 300 ? "rem" : "wake")) }
        let segs = try XCTUnwrap(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(),
                                                                hr: heartRate(), rr: []))
        XCTAssertEqual(segs.map { $0.stage }, ["light", "deep", "rem", "wake"])
        XCTAssertEqual(segs.first?.start, start)
        XCTAssertEqual(segs.last?.end, end)
        for (a, b) in zip(segs, segs.dropFirst()) { XCTAssertEqual(a.end, b.start) }
        let grid = ((start + 29) / 30) * 30
        XCTAssertEqual(segs[1].start, grid + 100 * 30)
        XCTAssertEqual(segs[2].start, grid + 220 * 30)
        XCTAssertEqual(segs[3].start, grid + 300 * 30)
    }

    func testEachModelIsGivenItsOwnColumns() {
        let seen = Seen()
        SleepStageLearned.model = SleepStageModel(
            version: "widths", prior: nil, weight: 1, smoothing: 1,
            first: { rows in seen.first(rows); return rows.map { _ in [0.1, 0.7, 0.1, 0.1] } },
            second: { rows in seen.second(rows); return rows.map { _ in [0.1, 0.7, 0.1, 0.1] } })
        XCTAssertNotNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(), hr: heartRate(), rr: []))
        XCTAssertEqual(seen.widths.0, SleepStageContext.firstNames.count)
        XCTAssertEqual(seen.widths.1, SleepStageContext.secondNames.count)
        XCTAssertEqual(seen.rows.0, seen.rows.1)
        // the second model reads the first one's probabilities, not a constant of its own
        XCTAssertEqual(seen.backOne, 0.7)
    }

    /// A strap that banks motion coarsely is the recipe's to stage: the model was never shown such a night.
    func testThinMotionIsNotStaged() {
        SleepStageLearned.model = model { _ in "light" }
        XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(every: 4),
                                                    hr: heartRate(), rr: []))
        XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end, grav: [], hr: heartRate(), rr: []))
        // motion for the first half of the night only
        XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end,
                                                    grav: gravity(to: start + duration / 2), hr: heartRate(), rr: []))
    }

    func testThinHeartRateIsNotStagedButAFiveSecondCadenceIs() {
        SleepStageLearned.model = model { _ in "light" }
        XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(), hr: heartRate(every: 60), rr: []))
        XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(), hr: [], rr: []))
        XCTAssertNotNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(), hr: heartRate(every: 5), rr: []))
    }

    func testAModelThatDoesNotAnswerLeavesTheNightToTheRecipe() {
        let ok: SleepStageModel.Predict = { rows in rows.map { _ in [0.1, 0.7, 0.1, 0.1] } }
        let bad: [(String, SleepStageModel.Predict)] = [
            ("nil", { _ in nil }),
            ("short", { rows in Array(rows.map { _ in [0.1, 0.7, 0.1, 0.1] }.dropLast()) }),
            ("narrow", { rows in rows.map { _ in [0.5, 0.5] } }),
            ("nan", { rows in rows.map { _ in [0.1, .nan, 0.1, 0.1] } }),
        ]
        for (name, predict) in bad {
            SleepStageLearned.model = SleepStageModel(version: "first-" + name, prior: nil, weight: 1, smoothing: 1,
                                                      first: predict, second: ok)
            XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(), hr: heartRate(), rr: []), name)
            SleepStageLearned.model = SleepStageModel(version: "second-" + name, prior: nil, weight: 1, smoothing: 1,
                                                      first: ok, second: predict)
            XCTAssertNil(SleepStageLearned.stageSession(start: start, end: end, grav: gravity(), hr: heartRate(), rr: []), name)
        }
    }

    /// A night staged under one version is not served to another from the cache.
    func testAnotherVersionStagesTheNightAgain() {
        let grav = gravity(), hr = heartRate()
        SleepStageLearned.model = model(version: "a") { _ in "light" }
        XCTAssertEqual(SleepStageLearned.stageSession(start: start, end: end, grav: grav, hr: hr, rr: [])?.map { $0.stage }, ["light"])
        SleepStageLearned.model = model(version: "b") { _ in "deep" }
        XCTAssertEqual(SleepStageLearned.version, "b")
        XCTAssertEqual(SleepStageLearned.stageSession(start: start, end: end, grav: grav, hr: hr, rr: [])?.map { $0.stage }, ["deep"])
    }

    /// The detector stages an accepted night with the installed model, and with the recipe when there is
    /// none. The same streams are asked for twice, so this also pins that the detector's memo does not
    /// hand the recipe's hypnogram to the model, nor the model's back to the recipe.
    func testTheDetectorStagesWithTheModelOnceInstalled() throws {
        let night = 1_749_513_600 + 2 * 3600                       // 02:00 UTC, an overnight window
        let dur = 90 * 60
        let grav = (0..<dur).map { GravitySample(ts: night + $0, x: 0, y: 0, z: 1.0) }
        let hr = (0..<dur).map { HRSample(ts: night + $0, bpm: 50) }

        let recipe = SleepStager.detectSleep(hr: hr, gravity: grav, useSleepStagerV2: true)
        XCTAssertEqual(recipe.count, 1)

        SleepStageLearned.model = model(version: "detector") { $0 < 60 ? "rem" : "deep" }
        let learned = SleepStager.detectSleep(hr: hr, gravity: grav, useSleepStagerV2: true)
        XCTAssertEqual(learned.count, 1)
        XCTAssertEqual(learned[0].start, recipe[0].start)
        XCTAssertEqual(learned[0].end, recipe[0].end)
        XCTAssertEqual(learned[0].stages.map { $0.stage }, ["rem", "deep"])
        XCTAssertNotEqual(learned[0].stages, recipe[0].stages)
        XCTAssertEqual(learned[0].efficiency, 1, accuracy: 1e-9)

        SleepStageLearned.model = nil
        XCTAssertEqual(SleepStager.detectSleep(hr: hr, gravity: grav, useSleepStagerV2: true), recipe)
    }

    func testAnEmptyOrInvertedWindowIsNotStaged() {
        SleepStageLearned.model = model { _ in "light" }
        XCTAssertNil(SleepStageLearned.stageSession(start: end, end: start, grav: gravity(), hr: heartRate(), rr: []))
        XCTAssertNil(SleepStageLearned.stageSession(start: start, end: start, grav: gravity(), hr: heartRate(), rr: []))
    }
}

/// What the two fake models were handed, collected across the `@Sendable` boundary.
private final class Seen: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var widths = (0, 0)
    private(set) var rows = (0, 0)
    private(set) var backOne = 0.0

    func first(_ r: [[Double]]) { lock.lock(); widths.0 = r.first?.count ?? 0; rows.0 = r.count; lock.unlock() }
    func second(_ r: [[Double]]) {
        lock.lock()
        widths.1 = r.first?.count ?? 0; rows.1 = r.count
        let col = SleepStageContext.secondNames.firstIndex(of: "p_light_back_1")!
        backOne = r.count > 5 ? r[5][col] : 0
        lock.unlock()
    }
}
