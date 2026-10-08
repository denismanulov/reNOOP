import XCTest
import WhoopProtocol
import WhoopStore
@testable import StrandAnalytics

/// The nightly gated mean of the WHOOP 4.0 strap-computed blood-oxygen byte (v24 `aux_byte_86`).
///
/// WHOOP 4.0 twin of `Spo2CandidateNightlyTests`, with the same cases: what the number counts, and what
/// it refuses to count. Same inputs and expectations as Kotlin `V24Spo2CandidateNightlyTest`; the
/// expected values were checked against a standalone compile of this function.
final class V24Spo2CandidateNightlyTests: XCTestCase {

    private func session(_ start: Int, _ durSec: Int) -> SleepSession {
        SleepSession(start: start, end: start + durSec, efficiency: 0.9,
                     stages: [], restingHR: 50, avgHRV: 60)
    }

    private func s(_ ts: Int, _ byte: Int) -> V24AuxByte86Sample { V24AuxByte86Sample(ts: ts, byte: byte) }

    func testMeanAndSampleCountOverTheSession() {
        let r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            [session(1000, 600)], samples: [s(1100, 94), s(1200, 96), s(1300, 95)])
        XCTAssertEqual(r?.mean, 95)
        XCTAssertEqual(r?.samples, 3)
    }

    /// The codes seen on real straps (8, 16, 40, 128, 168) and the out-of-range results (1...3) are not
    /// low blood oxygen. Averaging them in would give a number that is not a percentage of anything.
    func testStatusCodesAreExcluded() {
        let r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            [session(1000, 600)],
            samples: [s(1100, 94), s(1110, 8), s(1120, 16), s(1130, 40), s(1140, 128), s(1150, 168),
                      s(1160, 3), s(1300, 96)])
        XCTAssertEqual(r?.mean, 95)
        XCTAssertEqual(r?.samples, 2)
    }

    func testReadingsOutsideTheSessionAreExcluded() {
        let r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            [session(1000, 600)], samples: [s(500, 99), s(1100, 94), s(5000, 88)])
        XCTAssertEqual(r?.mean, 94)
        XCTAssertEqual(r?.samples, 1)
    }

    func testNilWhenNothingInBandFallsInsideASession() {
        XCTAssertNil(AnalyticsEngine.nightlyV24Spo2CandidateMean(
            [session(1000, 600)], samples: [s(1100, 16), s(1200, 168), s(9000, 95)]))
        XCTAssertNil(AnalyticsEngine.nightlyV24Spo2CandidateMean([], samples: [s(1100, 95)]))
        XCTAssertNil(AnalyticsEngine.nightlyV24Spo2CandidateMean([session(1000, 600)], samples: []))
    }

    func testMeanRoundsRatherThanFloors() {
        let r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            [session(1000, 600)], samples: [s(1100, 95), s(1200, 96)])
        XCTAssertEqual(r?.mean, 96)
    }

    func testBandBoundariesMatchTheDecoderGate() {
        let r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            [session(1000, 600)], samples: [s(1100, 69), s(1200, 70), s(1300, 100), s(1400, 101)])
        XCTAssertEqual(r?.mean, 85)
        XCTAssertEqual(r?.samples, 2)
    }

    /// The session span is inclusive at both ends, as the v18 function's is.
    func testTheSessionSpanIsInclusiveAtBothEnds() {
        let r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            [session(1000, 600)], samples: [s(999, 80), s(1000, 94), s(1600, 96), s(1601, 80)])
        XCTAssertEqual(r?.mean, 95)
        XCTAssertEqual(r?.samples, 2)
    }
}
