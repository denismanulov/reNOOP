import XCTest
@testable import StrandAnalytics

/// What a tapped "I'm awake" mark may do to a night: end it earlier, at the mark, and nothing else.
///
/// The two real cases are one wearer's nights of 2026-10-07 and 2026-10-08, where the record shows him
/// up at 08:02 and 08:15 and the detector closed the night at 08:21:27 and 08:18:06. Same cases as
/// Kotlin `WakeMarkTrimTest`; the expected values were checked against a standalone compile of
/// `WakeMarkTrim.swift`.
final class WakeMarkTrimTests: XCTestCase {

    private let h = 3600
    private var start: Int { 100 * h }            // an arbitrary bedtime
    private var end: Int { start + 8 * h }        // the detected end, eight hours later

    func testConstantsMatchTheKotlinTwin() {
        XCTAssertEqual(WakeMarkTrim.maxTrimSeconds, 3600)
        XCTAssertEqual(WakeMarkTrim.maxPendingSeconds, 43200)
    }

    func testAMarkShortlyBeforeTheDetectedEndBecomesTheEnd() {
        // 3 min 6 s late, then 19 min late.
        XCTAssertEqual(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: end - 186), end - 186)
        XCTAssertEqual(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: end - 19 * 60),
                       end - 19 * 60)
    }

    /// A night is never made longer: a mark after the detected end leaves it alone.
    func testAMarkAtOrAfterTheDetectedEndChangesNothing() {
        XCTAssertNil(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: end))
        XCTAssertNil(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: end + 600))
    }

    /// A mark deep inside the night is a stray tap, not a wake the detector missed by hours.
    func testAMarkMoreThanAnHourBeforeTheEndChangesNothing() {
        XCTAssertEqual(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: end - h), end - h)
        XCTAssertNil(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: end - h - 1))
        XCTAssertNil(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: start + 600))
    }

    func testAMarkBeforeTheNightBeganChangesNothing() {
        XCTAssertNil(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: start))
        XCTAssertNil(WakeMarkTrim.trimmedEnd(startTs: start, endTs: end, wakeTs: start - 60))
    }

    func testDecidePicksTheNightTheMarkFallsIn() {
        let earlier = (start: start - 30 * h, end: start - 22 * h)
        let tonight = (start: start, end: end)
        let d = WakeMarkTrim.decide(nights: [earlier, tonight], wakeTs: end - 186, nowTs: end + 60)
        XCTAssertEqual(d, .trim(index: 1, newEndTs: end - 186))
    }

    /// The usual order of events: the mark is tapped before the night has synced, so the newest night on
    /// record still ends before it. That is not "nothing to do"; the same night grows past the mark on
    /// the next pass, and the mark has to still be there.
    func testDecideWaitsWhileTheNightHasNotReachedTheMarkYet() {
        let partial = (start: start, end: end - 30 * 60)
        let wake = end - 186
        XCTAssertEqual(WakeMarkTrim.decide(nights: [partial], wakeTs: wake, nowTs: wake + 30), .wait)
        XCTAssertEqual(WakeMarkTrim.decide(nights: [(start: start, end: end)], wakeTs: wake, nowTs: wake + 900),
                       .trim(index: 0, newEndTs: wake))
    }

    func testDecideWaitsWithNoNightsAtAll() {
        XCTAssertEqual(WakeMarkTrim.decide(nights: [], wakeTs: end, nowTs: end + 60), .wait)
    }

    func testDecideExpiresAnOldMarkEvenIfANightWouldFit() {
        let wake = end - 186
        let late = wake + WakeMarkTrim.maxPendingSeconds + 1
        XCTAssertEqual(WakeMarkTrim.decide(nights: [(start: start, end: end)], wakeTs: wake, nowTs: late),
                       .expired)
        XCTAssertEqual(WakeMarkTrim.decide(nights: [(start: start, end: end)], wakeTs: wake,
                                           nowTs: wake + WakeMarkTrim.maxPendingSeconds),
                       .trim(index: 0, newEndTs: wake))
    }
}
