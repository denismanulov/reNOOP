import XCTest
@testable import StrandAnalytics

/// `SleepStageContext` puts a stretch of the night into one row. What these pin is that each column is
/// the window its name says, that a window stops at the night's edge instead of inventing a value, and
/// that a missing measurement is left out of a mean and never read as a number.
final class SleepStageContextTests: XCTestCase {

    private typealias C = SleepStageContext

    /// A night of feature rows where every feature is `value(epoch, column)`.
    private func night(_ n: Int, _ value: (Int, Int) -> Double?) -> [[Double?]] {
        (0..<n).map { i in SleepStageFeatures.names.indices.map { value(i, $0) } }
    }

    private func column(_ name: String) -> Int { C.names.firstIndex(of: name)! }

    /// The mean of the present values at `lo...hi`, clipped to the column, the slow way.
    private func mean(_ x: [Double?], _ lo: Int, _ hi: Int) -> Double? {
        let p = (max(0, lo)...min(x.count - 1, hi)).compactMap { x[$0] }
        return p.isEmpty ? nil : p.reduce(0, +) / Double(p.count)
    }

    private func assertClose(_ a: Double?, _ b: Double?, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        switch (a, b) {
        case (nil, nil): return
        case let (a?, b?): XCTAssertEqual(a, b, accuracy: 1e-9, what, file: file, line: line)
        default: XCTFail("\(what): \(String(describing: a)) against \(String(describing: b))", file: file, line: line)
        }
    }

    func testOneValuePerNameAndNoNameTwice() {
        let out = C.surroundings(values: night(50) { i, c in Double(i + c) })
        XCTAssertEqual(out.count, 50)
        XCTAssertTrue(out.allSatisfy { $0.count == C.names.count })
        XCTAssertEqual(C.names.count, C.sources.count * 10)
        XCTAssertEqual(Set(C.secondNames).count, C.secondNames.count)
        XCTAssertEqual(C.firstNames.count, SleepStageFeatures.names.count + C.names.count)
        XCTAssertEqual(C.secondNames.count, C.firstNames.count + 4 * 15)
        XCTAssertTrue(C.sources.allSatisfy { SleepStageFeatures.names.contains($0) })
    }

    func testAnEmptyNightHasNoSurroundings() {
        XCTAssertTrue(C.surroundings(values: []).isEmpty)
        XCTAssertTrue(C.neighbours([]).isEmpty)
    }

    /// A feature that never changes is its own mean over any window, and stands nowhere from it.
    func testAFlatNightIsItsOwnSurroundings() {
        let out = C.surroundings(values: night(400) { _, c in Double(c) + 0.25 })
        let src = SleepStageFeatures.names.firstIndex(of: "hr_z")!
        for i in [0, 1, 7, 200, 398, 399] {
            for name in ["hr_z_around_21", "hr_z_around_61", "hr_z_around_181"] {
                assertClose(out[i][column(name)], Double(src) + 0.25, "\(name) at \(i)")
            }
            assertClose(out[i][column("hr_z_vs_181")], 0, "vs at \(i)")
        }
    }

    /// Every column against the window its name says, on a night with holes in it.
    func testEachColumnIsTheWindowItsNameSays() {
        var rng = SplitMix(seed: 3)
        let src = SleepStageFeatures.names.firstIndex(of: "move_share")!
        let x: [Double?] = (0..<500).map { i in (i % 17 == 3 || (120..<150).contains(i)) ? nil : rng.unit() }
        let out = C.surroundings(values: night(500) { i, c in c == src ? x[i] : 0 })
        for i in [0, 1, 2, 5, 6, 10, 30, 90, 119, 135, 149, 250, 409, 480, 493, 494, 498, 499] {
            assertClose(out[i][column("move_share_around_21")], mean(x, i - 10, i + 10), "around 21 at \(i)")
            assertClose(out[i][column("move_share_around_61")], mean(x, i - 30, i + 30), "around 61 at \(i)")
            assertClose(out[i][column("move_share_around_181")], mean(x, i - 90, i + 90), "around 181 at \(i)")
            assertClose(out[i][column("move_share_before_20")], i == 0 ? nil : mean(x, i - 20, i - 1), "before at \(i)")
            assertClose(out[i][column("move_share_after_20")], i == 499 ? nil : mean(x, i + 1, i + 20), "after at \(i)")
            assertClose(out[i][column("move_share_back_2")], i >= 2 ? x[i - 2] : nil, "back 2 at \(i)")
            assertClose(out[i][column("move_share_ahead_2")], i + 2 < 500 ? x[i + 2] : nil, "ahead 2 at \(i)")
            assertClose(out[i][column("move_share_back_6")], i >= 6 ? x[i - 6] : nil, "back 6 at \(i)")
            assertClose(out[i][column("move_share_ahead_6")], i + 6 < 500 ? x[i + 6] : nil, "ahead 6 at \(i)")
            let wide = mean(x, i - 90, i + 90)
            assertClose(out[i][column("move_share_vs_181")], x[i].flatMap { v in wide.map { v - $0 } }, "vs at \(i)")
        }
    }

    /// A source without the measurement (a wrist with no beat intervals) has no surroundings either,
    /// and takes nothing away from the other sources.
    func testAMissingSourceIsMissingEverywhereAndAloneInIt() {
        let rr = SleepStageFeatures.names.indices.filter { SleepStageFeatures.names[$0].hasPrefix("rr_") }
        let full = C.surroundings(values: night(300) { i, c in sin(Double(i * (c + 1)) / 40) })
        let bare = C.surroundings(values: night(300) { i, c in rr.contains(c) ? nil : sin(Double(i * (c + 1)) / 40) })
        for (k, name) in C.names.enumerated() {
            if name.hasPrefix("rr_") {
                XCTAssertTrue(bare.allSatisfy { $0[k] == nil }, name)
            } else {
                XCTAssertEqual(bare.map { $0[k] }, full.map { $0[k] }, name)
            }
        }
    }

    /// The window is cut at the night's edge: the first epoch has nothing before it, the last nothing after.
    func testNothingIsReadFromOutsideTheNight() {
        let out = C.surroundings(values: night(100) { i, _ in Double(i) })
        XCTAssertNil(out[0][column("hr_z_before_20")])
        XCTAssertNil(out[99][column("hr_z_after_20")])
        assertClose(out[0][column("hr_z_around_21")], 5, "the first epoch's centred window holds epochs 0...10")
        assertClose(out[99][column("hr_z_around_21")], 94, "the last one's holds 89...99")
    }

    /// The two models' inputs are the named columns in the named order, with the stand-in for a missing value.
    func testModelInputsAreTheNamedColumnsInOrder() {
        let values = night(40) { i, c in c == 0 ? nil : Double(i) + Double(c) / 100 }
        let first = C.firstInput(values: values)
        XCTAssertTrue(first.allSatisfy { $0.count == C.firstNames.count })
        XCTAssertEqual(first[7][0], SleepStageFeatures.missing)
        XCTAssertEqual(first[7][3], 7.03)
        let around = C.firstNames.firstIndex(of: "hr_z_around_21")!
        let hr = SleepStageFeatures.names.firstIndex(of: "hr_z")!
        XCTAssertEqual(first[20][around], 20 + Double(hr) / 100, accuracy: 1e-9)
        XCTAssertEqual(first[0][C.firstNames.firstIndex(of: "hr_z_before_20")!], SleepStageFeatures.missing)

        let p = (0..<40).map { i in i < 20 ? [0.7, 0.1, 0.1, 0.1] : [0.1, 0.1, 0.1, 0.7] }
        let second = C.secondInput(first: first, probabilities: p)
        XCTAssertTrue(second.allSatisfy { $0.count == C.secondNames.count })
        XCTAssertEqual(Array(second[5].prefix(first[5].count)), first[5])
        XCTAssertEqual(second[20][C.secondNames.firstIndex(of: "p_wake_back_1")!], 0.7)
        XCTAssertEqual(second[20][C.secondNames.firstIndex(of: "p_rem_ahead_1")!], 0.7)
        XCTAssertEqual(second[0][C.secondNames.firstIndex(of: "p_wake_back_1")!], SleepStageFeatures.missing)
    }

    // MARK: - neighbours

    func testNeighboursAreTheEpochsTheirNamesSay() {
        var rng = SplitMix(seed: 9)
        let p: [[Double]] = (0..<300).map { _ in
            let v = (0..<4).map { _ in 0.01 + rng.unit() }
            let total = v.reduce(0, +)
            return v.map { $0 / total }
        }
        let out = C.neighbours(p)
        XCTAssertTrue(out.allSatisfy { $0.count == C.neighbourNames.count })
        func col(_ name: String) -> Int { C.neighbourNames.firstIndex(of: name)! }
        for (c, stage) in SleepStageDecoder.stages.enumerated() {
            let x = p.map { Optional($0[c]) }
            for i in [0, 1, 3, 4, 7, 8, 150, 291, 292, 298, 299] {
                for d in [1, 2, 4, 8] {
                    assertClose(out[i][col("p_\(stage)_back_\(d)")], i >= d ? x[i - d] : nil, "\(stage) back \(d) at \(i)")
                    assertClose(out[i][col("p_\(stage)_ahead_\(d)")], i + d < 300 ? x[i + d] : nil, "\(stage) ahead \(d) at \(i)")
                }
                for w in [5, 11, 21, 41, 81] {
                    assertClose(out[i][col("p_\(stage)_around_\(w)")], mean(x, i - w / 2, i + w / 2), "\(stage) around \(w) at \(i)")
                }
                assertClose(out[i][col("p_\(stage)_so_far")], i == 0 ? nil : mean(x, 0, i - 1), "\(stage) so far at \(i)")
                assertClose(out[i][col("p_\(stage)_to_come")], i == 299 ? nil : mean(x, i + 1, 299), "\(stage) to come at \(i)")
            }
        }
    }

    /// Probabilities sum to one in every row, so their window means do too.
    func testNeighbourMeansStillSumToOne() {
        let p = (0..<120).map { i in i % 2 == 0 ? [0.1, 0.2, 0.3, 0.4] : [0.7, 0.1, 0.15, 0.05] }
        let out = C.neighbours(p)
        let cols = SleepStageDecoder.stages.map { C.neighbourNames.firstIndex(of: "p_\($0)_around_21")! }
        for i in [0, 60, 119] {
            XCTAssertEqual(cols.reduce(0.0) { $0 + (out[i][$1] ?? 0) }, 1, accuracy: 1e-12)
        }
    }
}

/// A fixed sequence, so a failure names the same epoch on every run.
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
