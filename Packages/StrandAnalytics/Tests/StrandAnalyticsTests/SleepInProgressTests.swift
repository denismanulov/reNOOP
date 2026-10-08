import XCTest
@testable import StrandAnalytics

/// When the Summary may say a night is still being counted. The times follow one real morning: bed at
/// 00:05, up at 08:15, and a scoring pass at 08:15:50 that still stored the night as running to the edge
/// of the data. Same cases as Kotlin `SleepInProgressTest`; the expected values were checked against a
/// standalone compile of `SleepInProgress.swift`.
final class SleepInProgressTests: XCTestCase {

    private let bed = 1_000_000
    private func at(_ hours: Double) -> Int { bed + Int(hours * 3600) }

    func testConstantsMatchTheKotlinTwin() {
        XCTAssertEqual(SleepInProgress.edgeSlackSeconds, 600)
        XCTAssertEqual(SleepInProgress.freshDataSeconds, 5400)
    }

    func testANightRunningToTheEdgeOfFreshDataIsInProgress() {
        XCTAssertTrue(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(8.15), newestDataTs: at(8.17),
                                                   nowTs: at(8.18), pendingWakeTs: nil))
    }

    /// The pass after the wearer got up: the night ends at 08:15 and the data now runs well past it.
    func testANightThatEndedBeforeTheEdgeIsNot() {
        XCTAssertFalse(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(8.17), newestDataTs: at(8.6),
                                                    nowTs: at(8.6), pendingWakeTs: nil))
    }

    func testTheSlackIsInclusive() {
        let edge = at(8.0)
        let slack = SleepInProgress.edgeSlackSeconds
        XCTAssertTrue(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: edge - slack, newestDataTs: edge,
                                                   nowTs: edge, pendingWakeTs: nil))
        XCTAssertFalse(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: edge - slack - 1,
                                                    newestDataTs: edge, nowTs: edge, pendingWakeTs: nil))
    }

    /// Tapping "I'm awake" ends the in-progress state at once, before any sync has shown the wake.
    func testAWakeMarkTappedDuringThisNightEndsIt() {
        XCTAssertFalse(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(8.15), newestDataTs: at(8.17),
                                                    nowTs: at(8.18), pendingWakeTs: at(8.18)))
    }

    /// A mark left over from the morning before says nothing about tonight.
    func testAWakeMarkFromBeforeThisNightIsIgnored() {
        XCTAssertTrue(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(8.15), newestDataTs: at(8.17),
                                                   nowTs: at(8.18), pendingWakeTs: bed - 3600))
        // The comparison is strict: a mark at the very second the night began is not during it.
        XCTAssertTrue(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(8.15), newestDataTs: at(8.17),
                                                   nowTs: at(8.18), pendingWakeTs: bed))
        XCTAssertFalse(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(8.15), newestDataTs: at(8.17),
                                                    nowTs: at(8.18), pendingWakeTs: bed + 1))
    }

    /// The strap out of reach since 03:00 is "not synced", not "asleep until now".
    func testStaleDataIsNotInProgress() {
        XCTAssertFalse(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(3.0), newestDataTs: at(3.0),
                                                    nowTs: at(9.0), pendingWakeTs: nil))
        let fresh = SleepInProgress.freshDataSeconds
        XCTAssertTrue(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(3.0), newestDataTs: at(3.0),
                                                   nowTs: at(3.0) + fresh, pendingWakeTs: nil))
        XCTAssertFalse(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(3.0), newestDataTs: at(3.0),
                                                    nowTs: at(3.0) + fresh + 1, pendingWakeTs: nil))
    }

    func testNoDataAtAllIsNotInProgress() {
        XCTAssertFalse(SleepInProgress.isInProgress(bedTs: bed, lastAsleepTs: at(8.0), newestDataTs: nil,
                                                    nowTs: at(8.0), pendingWakeTs: nil))
    }
}
