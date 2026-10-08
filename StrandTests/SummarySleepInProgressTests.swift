import XCTest
import Foundation
import WhoopStore
import StrandAnalytics
@testable import Strand

/// When the Summary shows the "Sleep still in progress" card, and what figure it carries. The rule is
/// `SleepInProgress` (tested in StrandAnalytics); these pin the conditions the Summary adds around it
/// and that the card's figure is the loaded night's own.
@MainActor
final class SummarySleepInProgressTests: XCTestCase {
    /// 03:00 today, local: a wake time whose local day is today whatever the zone.
    private var wake: Int { Int(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970) + 3 * 3_600 }
    private var bed: Int { wake - 5 * 3_600 }
    private var todayKey: String { Repository.localDayKey(Date(timeIntervalSince1970: TimeInterval(wake))) }

    private func night(asleep: Double = 289, endTs: Int? = nil) -> Night {
        Night(session: CachedSleepSession(startTs: bed, endTs: endTs ?? wake, efficiency: 0.9, restingHr: 54,
                                          avgHrv: 60, stagesJSON: nil),
              stages: Stages(awake: 11, light: asleep, deep: 0, rem: 0))
    }

    private func resolve(_ load: SummarySleepLoad, isToday: Bool = true, key: String? = nil,
                         now: Int? = nil, pending: Int? = nil) -> SummarySleepInProgress? {
        SummarySleepInProgress.resolve(load, isToday: isToday, selectedDayKey: key ?? todayKey,
                                       nowTs: now ?? wake + 180, pendingWakeTs: pending)
    }

    /// A night running to the edge of fresh data: the card shows, with that night's own time asleep.
    func testTodaysNightAtTheEdgeOfFreshDataShowsWithItsOwnFigure() {
        let load = SummarySleepLoad(night: night(asleep: 337), newestDataTs: wake + 120)
        XCTAssertEqual(resolve(load), SummarySleepInProgress(asleepMinutes: 337))
        XCTAssertEqual(resolve(load)?.asleepMinutes, load.night?.stages.asleep)
    }

    func testAnotherDayNeverShowsIt() {
        let load = SummarySleepLoad(night: night(), newestDataTs: wake + 120)
        XCTAssertNil(resolve(load, isToday: false))
    }

    /// The night on screen must be the one whose wake day is the picked day.
    func testANightThatEndedOnAnotherDayDoesNotShow() {
        let yesterday = wake - 86_400
        let load = SummarySleepLoad(night: night(endTs: yesterday), newestDataTs: yesterday + 120)
        XCTAssertNil(resolve(load, now: yesterday + 180))
        let fresh = SummarySleepLoad(night: night(), newestDataTs: wake + 120)
        XCTAssertNil(resolve(fresh, key: "1999-01-01"))
    }

    func testANightWithNoTimeAsleepDoesNotShow() {
        XCTAssertNil(resolve(SummarySleepLoad(night: night(asleep: 0), newestDataTs: wake + 120)))
        XCTAssertNil(resolve(SummarySleepLoad(night: nil, newestDataTs: wake + 120)))
    }

    /// The card goes away on the tap; a mark left from before this night began says nothing about it.
    func testAPendingWakeMarkDuringThisNightHidesIt() {
        let load = SummarySleepLoad(night: night(), newestDataTs: wake + 120)
        XCTAssertNil(resolve(load, pending: wake + 150))
        XCTAssertNotNil(resolve(load, pending: bed - 3_600))
    }

    func testANightThatEndedBeforeTheEdgeOrOnStaleDataDoesNotShow() {
        // The data now runs well past the night's end.
        XCTAssertNil(resolve(SummarySleepLoad(night: night(), newestDataTs: wake + 1_800), now: wake + 1_800))
        // The strap has been out of reach for two hours.
        XCTAssertNil(resolve(SummarySleepLoad(night: night(), newestDataTs: wake), now: wake + 7_200))
        // Nothing on record at all.
        XCTAssertNil(resolve(SummarySleepLoad(night: night(), newestDataTs: nil)))
    }
}
