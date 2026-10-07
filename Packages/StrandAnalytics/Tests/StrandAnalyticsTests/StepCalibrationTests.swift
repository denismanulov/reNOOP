import XCTest
@testable import StrandAnalytics

final class StepCalibrationTests: XCTestCase {

    private typealias C = StepCalibration

    func testAcceptsOnlyRatiosInsideTheBand() {
        XCTAssertTrue(C.accepts(steps: 60, ticks: 74))        // 1.23, arm swinging
        XCTAssertTrue(C.accepts(steps: 63, ticks: 90))        // 1.43, hand in pocket
        XCTAssertFalse(C.accepts(steps: 60, ticks: 59))       // the counter never undercounts
        XCTAssertFalse(C.accepts(steps: 60, ticks: 110))      // 1.83
        XCTAssertFalse(C.accepts(steps: 20, ticks: 25))       // too few steps
        XCTAssertFalse(C.accepts(steps: 60, ticks: 0))
    }

    func testAnOctaveErrorIsRefusedOverTheWholeObservedRange() {
        // The detector's one consequential failure doubles or halves the step count. Every ratio seen on
        // the strap (1.14 to 1.49) then lands outside the band.
        for trueRatio in stride(from: 1.14, through: 1.49, by: 0.05) {
            let steps = 60.0, ticks = steps * trueRatio
            XCTAssertTrue(C.accepts(steps: steps, ticks: ticks))
            XCTAssertFalse(C.accepts(steps: steps * 2, ticks: ticks), "octave high at \(trueRatio)")
            XCTAssertFalse(C.accepts(steps: steps / 2, ticks: ticks), "octave low at \(trueRatio)")
        }
    }

    func testManualDivisorAppliesUntilSomethingIsMeasured() {
        let state = C.advanced(C.State(), to: "2026-10-03")
        XCTAssertEqual(C.factor(state, day: "2026-10-03", manual: 1.26), 1.26)
        XCTAssertEqual(C.factor(state, day: "2026-09-30", manual: 1.26), 1.26)
        XCTAssertNil(C.longRunFactor(state))
    }

    func testFirstDayUsesItsOwnMeasurementsAsARatioOfSums() {
        var state = C.State()
        state = C.recorded(state, day: "2026-10-03", steps: 60, ticks: 72)     // 1.20
        state = C.recorded(state, day: "2026-10-03", steps: 180, ticks: 252)   // 1.40, three times longer
        // (72 + 252) / (60 + 180), not the mean of 1.20 and 1.40.
        XCTAssertEqual(C.factor(state, day: "2026-10-03", manual: 1.0), 1.35, accuracy: 1e-9)
        XCTAssertEqual(state.accepted, 2)
    }

    func testARefusedMeasurementChangesNothing() {
        let start = C.recorded(C.State(), day: "2026-10-03", steps: 60, ticks: 75)
        XCTAssertEqual(C.recorded(start, day: "2026-10-03", steps: 120, ticks: 75), start)
    }

    func testLeavingADayFreezesItAndSeedsTheLongRunFactor() {
        var state = C.recorded(C.State(), day: "2026-10-03", steps: 100, ticks: 125)
        state = C.advanced(state, to: "2026-10-04")
        XCTAssertEqual(state.frozen["2026-10-03"] ?? 0, 1.25, accuracy: 1e-9)
        // Zero-seeded sums: after one measured day the ratio is that day's, with no pull toward a seed.
        XCTAssertEqual(C.longRunFactor(state) ?? 0, 1.25, accuracy: 1e-9)
        // A day with no measurement of its own runs on the long-run factor.
        XCTAssertEqual(C.factor(state, day: "2026-10-04", manual: 1.0), 1.25, accuracy: 1e-9)
    }

    func testADaysOwnEvidenceOutweighsTheLongRunFactor() {
        var state = C.recorded(C.State(), day: "2026-10-03", steps: 200, ticks: 250)      // 1.25
        // Next day: hands in pockets, four measurements of 55 steps at 1.49.
        for _ in 0..<4 { state = C.recorded(state, day: "2026-10-04", steps: 55, ticks: 55 * 1.49) }
        let today = C.factor(state, day: "2026-10-04", manual: 1.0)
        // (220 * 1.49 + 120 * 1.25) / (220 + 120)
        XCTAssertEqual(today, (220 * 1.49 + 120 * 1.25) / 340, accuracy: 1e-9)
        XCTAssertGreaterThan(today, 1.40)
        // History is not rewritten by it.
        XCTAssertEqual(C.factor(state, day: "2026-10-03", manual: 1.0), 1.25, accuracy: 1e-9)
    }

    func testLongRunFactorIsAnExponentialAverageOfDaySums() {
        var state = C.State()
        state = C.recorded(state, day: "2026-10-01", steps: 100, ticks: 120)
        state = C.recorded(state, day: "2026-10-02", steps: 100, ticks: 140)
        state = C.advanced(state, to: "2026-10-03")
        // ticks: 0.8 * (0.2 * 120) + 0.2 * 140 ; steps: 0.8 * (0.2 * 100) + 0.2 * 100
        XCTAssertEqual(C.longRunFactor(state) ?? 0, (0.8 * 24 + 28) / (0.8 * 20 + 20), accuracy: 1e-9)
    }

    func testAnUnmeasuredDayStillFreezesAtTheLongRunFactor() {
        var state = C.recorded(C.State(), day: "2026-10-01", steps: 100, ticks: 130)
        state = C.advanced(state, to: "2026-10-02")
        state = C.advanced(state, to: "2026-10-03")
        state = C.recorded(state, day: "2026-10-03", steps: 100, ticks: 110)
        XCTAssertEqual(state.frozen["2026-10-02"] ?? 0, 1.30, accuracy: 1e-9)
        XCTAssertEqual(C.factor(state, day: "2026-10-02", manual: 1.0), 1.30, accuracy: 1e-9)
    }

    func testAMeasurementForADayAlreadyLeftIsDropped() {
        var state = C.recorded(C.State(), day: "2026-10-03", steps: 100, ticks: 125)
        state = C.advanced(state, to: "2026-10-04")
        XCTAssertEqual(C.recorded(state, day: "2026-10-03", steps: 100, ticks: 150), state)
    }

    func testFrozenDaysAreBounded() {
        var state = C.State()
        for index in 0..<(C.frozenDayLimit + 30) {
            let day = String(format: "2026-%04d", index)       // any strictly increasing key
            state = C.recorded(state, day: day, steps: 100, ticks: 125)
        }
        state = C.advanced(state, to: "2027-0000")
        XCTAssertEqual(state.frozen.count, C.frozenDayLimit)
        XCTAssertNil(state.frozen["2026-0000"])
    }

    func testStateSurvivesACodableRoundTrip() throws {
        var state = C.recorded(C.State(), day: "2026-10-03", steps: 100, ticks: 125)
        state = C.recorded(state, day: "2026-10-04", steps: 55, ticks: 80)
        let decoded = try JSONDecoder().decode(C.State.self, from: JSONEncoder().encode(state))
        XCTAssertEqual(decoded, state)
    }
}
