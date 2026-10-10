import XCTest
import StrandAnalytics
@testable import Strand

/// "Highlights" on the Friends tab is read out of the days the feed already carries: which workouts
/// count as news, and in what order.
final class FriendsHighlightsTests: XCTestCase {
    private let now = 1_791_500_000

    private func person(_ nick: String, _ workouts: [[FriendsDay.Workout]]) -> FriendProfile {
        let days = workouts.enumerated().map { index, list in
            FriendFeedDay(day: "2026-10-0\(8 - index)", summary: FriendsDay(workouts: list))
        }
        return FriendProfile(id: nick, name: nick.capitalized, days: days)
    }

    /// A workout that ended `ago` seconds before `now` and ran for half an hour.
    private func workout(endedAgo ago: Int, sport: String = "Running") -> FriendsDay.Workout {
        FriendsDay.Workout(startTs: now - ago - 1800, sport: sport, durationS: 1800, strain: 30)
    }

    func testWhichWorkoutsAreNews() {
        let cases: [(name: String, endedAgo: Int, shown: Bool)] = [
            ("just ended", 0, true),
            ("an hour ago", 3600, true),
            ("at the window's edge", FriendsHighlights.windowSeconds, true),
            ("a second past the window", FriendsHighlights.windowSeconds + 1, false),
            ("a week ago", 7 * 86_400, false),
            ("a friend's clock runs ahead, within the slack", -FriendsHighlights.clockSlackSeconds, true),
            ("still running by this phone's clock", -FriendsHighlights.clockSlackSeconds - 1, false)
        ]
        for c in cases {
            let found = FriendsHighlights.recent(friends: [person("anna", [[workout(endedAgo: c.endedAgo)]])], now: now)
            XCTAssertEqual(found.count, c.shown ? 1 : 0, c.name)
        }
    }

    func testNewestFirstAcrossFriendsAndDays() {
        let anna = person("anna", [[workout(endedAgo: 7200)], [workout(endedAgo: 90_000, sport: "Yoga")]])
        let max = person("max", [[workout(endedAgo: 600, sport: "Cycling"), workout(endedAgo: 30_000, sport: "Walking")]])
        let found = FriendsHighlights.recent(friends: [anna, max], now: now)
        XCTAssertEqual(found.map(\.workout.sport), ["Cycling", "Running", "Walking", "Yoga"])
        XCTAssertEqual(found.map(\.personID), ["max", "anna", "max", "anna"])
    }

    func testTwoFriendsEndingTogetherKeepAStableOrder() {
        let found = FriendsHighlights.recent(
            friends: [person("zoe", [[workout(endedAgo: 100)]]), person("anna", [[workout(endedAgo: 100)]])], now: now)
        XCTAssertEqual(found.map(\.personID), ["anna", "zoe"])
    }

    func testAWorkoutListedTwiceIsShownOnce() {
        let twice = workout(endedAgo: 100)
        let found = FriendsHighlights.recent(friends: [person("anna", [[twice], [twice]])], now: now)
        XCTAssertEqual(found.count, 1)
    }

    func testNoMoreThanTheLimit() {
        let many = (0..<(FriendsHighlights.limit + 5)).map { workout(endedAgo: 60 * ($0 + 1)) }
        let found = FriendsHighlights.recent(friends: [person("anna", [many])], now: now)
        XCTAssertEqual(found.count, FriendsHighlights.limit)
        XCTAssertEqual(found.first?.endTs, now - 60)
    }

    func testFriendsWithNothingToShow() {
        let silent = FriendProfile(id: "kate", name: "Kate", days: nil)
        let rested = FriendProfile(id: "oleg", name: "Oleg",
                                   days: [FriendFeedDay(day: "2026-10-08", summary: FriendsDay(recovery: 80))])
        XCTAssertTrue(FriendsHighlights.recent(friends: [silent, rested], now: now).isEmpty)
        XCTAssertTrue(FriendsHighlights.recent(friends: [], now: now).isEmpty)
    }
}
