import XCTest
import StrandAnalytics
@testable import Strand

/// The Friends tab's list: who comes first under each order, and which figure a card shows.
final class FriendsBoardTests: XCTestCase {
    private let today = "2026-10-08"

    private func person(_ nick: String, name: String? = nil, day: String? = "2026-10-08",
                        recovery: Int? = nil, strain: Double? = nil, sleep: Int? = nil,
                        sharesScores: Bool = true) -> FriendProfile {
        let days = day.map { [FriendFeedDay(day: $0, summary: FriendsDay(recovery: recovery, strain: strain, sleepScore: sleep))] }
        return FriendProfile(id: nick, name: name ?? nick.capitalized,
                             share: FriendsShare(scores: sharesScores), days: days)
    }

    private func order(_ rows: [FriendsBoardRow]) -> [String] { rows.map(\.person.id) }

    func testByAScoreTheHighestLeadsAndTheWearerIsAmongFriends() {
        let rows = FriendsBoard.rows(me: person("me", recovery: 58),
                                     friends: [person("anna", recovery: 56), person("max", recovery: 73)],
                                     sort: .recovery, todayKey: today)
        XCTAssertEqual(order(rows), ["max", "me", "anna"])
        XCTAssertEqual(rows.map(\.isMe), [false, true, false])
        XCTAssertEqual(rows.map(\.value), [73, 58, 56])
    }

    func testEachScoreHasItsOwnOrderAndItsOwnFigure() {
        let me = person("me", recovery: 50, strain: 60, sleep: 70)
        let anna = person("anna", recovery: 90, strain: 20, sleep: 80)
        let cases: [(FriendsSort, [String], [Double?])] = [
            (.recovery, ["anna", "me"], [90, 50]), (.strain, ["me", "anna"], [60, 20]),
            (.sleep, ["anna", "me"], [80, 70])
        ]
        for (sort, expected, figures) in cases {
            let rows = FriendsBoard.rows(me: me, friends: [anna], sort: sort, todayKey: today)
            XCTAssertEqual(order(rows), expected, sort.rawValue)
            XCTAssertEqual(rows.map(\.value), figures, sort.rawValue)
        }
    }

    func testByNameTheWearerLeadsWhateverTheFigures() {
        let rows = FriendsBoard.rows(me: person("zzz", recovery: 1),
                                     friends: [person("max", recovery: 99), person("anna", day: nil),
                                               person("boris", day: "2026-10-07", recovery: 70)],
                                     sort: .name, todayKey: today)
        XCTAssertEqual(order(rows), ["zzz", "anna", "boris", "max"])
        XCTAssertEqual(rows.map(\.standing), [.today, .noData, .stale("2026-10-07"), .today])
    }

    func testByNameTheCardsShowRecovery() {
        XCTAssertEqual(FriendsSort.name.metric, .recovery)
        XCTAssertEqual(FriendsSort.allCases.map(\.metric), [.recovery, .recovery, .strain, .sleep])
        let rows = FriendsBoard.rows(me: person("me", recovery: 40, strain: 80), friends: [], sort: .name, todayKey: today)
        XCTAssertEqual(rows.first?.value, 40)
    }

    func testEqualFiguresFallBackToTheName() {
        let rows = FriendsBoard.rows(me: person("me", recovery: 70),
                                     friends: [person("zoe", recovery: 80), person("anna", recovery: 80),
                                               person("max", recovery: 60)],
                                     sort: .recovery, todayKey: today)
        XCTAssertEqual(order(rows), ["anna", "zoe", "me", "max"])
    }

    func testAFigureFromAnEarlierDayComesAfterEveryFigureFromToday() {
        let rows = FriendsBoard.rows(me: person("me", recovery: 40),
                                     friends: [person("pasha", day: "2026-10-07", recovery: 99),
                                               person("ahead", day: "2026-10-09", recovery: 10)],
                                     sort: .recovery, todayKey: today)
        XCTAssertEqual(order(rows), ["me", "ahead", "pasha"])
        XCTAssertEqual(rows.map(\.standing), [.today, .today, .stale("2026-10-07")])
        XCTAssertEqual(rows.last?.value, 99, "yesterday's figure is still shown, said to be yesterday's")
    }

    func testPeopleWithNoFigureFromTodayComeLastByName() {
        let rows = FriendsBoard.rows(
            me: person("me", recovery: 40),
            friends: [person("zed", day: nil), person("kate", recovery: 80, sharesScores: false),
                      person("oleg", strain: 30), person("boris", day: "2026-10-06", recovery: 70)],
            sort: .recovery, todayKey: today)
        XCTAssertEqual(order(rows), ["me", "boris", "kate", "oleg", "zed"])
        XCTAssertEqual(rows.map(\.standing),
                       [.today, .stale("2026-10-06"), .notShared, .noData, .noData])
        XCTAssertNil(rows[2].value, "a figure the person does not share is not carried")
    }

    func testTheWearerAloneIsStillOnTheList() {
        for sort in FriendsSort.allCases {
            XCTAssertEqual(order(FriendsBoard.rows(me: person("me", recovery: 50, strain: 50, sleep: 88), friends: [],
                                                   sort: sort, todayKey: today)), ["me"], sort.rawValue)
            XCTAssertEqual(FriendsBoard.rows(me: person("me", day: nil), friends: [], sort: sort,
                                             todayKey: today).map(\.standing), [.noData], sort.rawValue)
        }
    }

    // MARK: - The second line

    private func day(_ summary: FriendsDay) -> [FriendFeedDay] { [FriendFeedDay(day: today, summary: summary)] }

    private func workout(minutes: Int) -> FriendsDay.Workout {
        FriendsDay.Workout(startTs: 1_791_400_000, sport: "Running", durationS: minutes * 60)
    }

    func testOnlyScoresWithAnAmountBehindThemHaveASecondLine() {
        XCTAssertEqual(FriendsMetric.allCases.map(\.hasDetail), [false, true, true])
    }

    func testSleepIsFollowedByTheTimeAsleepAndRecoveryByNothing() {
        let night = FriendsDay.Sleep(startTs: 0, endTs: 0, asleepMin: 488)
        let me = FriendProfile(id: "me", name: "Me", share: FriendsShare(),
                               days: day(FriendsDay(recovery: 70, sleepScore: 59, sleep: night)))
        XCTAssertEqual(FriendsBoard.rows(me: me, friends: [], sort: .sleep, todayKey: today).first?.detail,
                       .asleep(minutes: 488))
        XCTAssertNil(FriendsBoard.rows(me: me, friends: [], sort: .recovery, todayKey: today).first?.detail)
        XCTAssertNil(FriendsBoard.rows(me: me, friends: [], sort: .name, todayKey: today).first?.detail)
    }

    func testStrainIsFollowedByTheDaysWorkoutMinutes() {
        let cases: [(name: String, workouts: [FriendsDay.Workout]?, minutes: Int)] = [
            ("two workouts add up", [workout(minutes: 25), workout(minutes: 44)], 69),
            ("a day without a workout", nil, 0),
            ("seconds round to the minute", [FriendsDay.Workout(startTs: 0, sport: "Yoga", durationS: 1_530)], 26)
        ]
        for c in cases {
            let me = FriendProfile(id: "me", name: "Me", share: FriendsShare(),
                                   days: day(FriendsDay(strain: 31.9, workouts: c.workouts)))
            XCTAssertEqual(FriendsBoard.rows(me: me, friends: [], sort: .strain, todayKey: today).first?.detail,
                           .workouts(minutes: c.minutes), c.name)
        }
    }

    func testASectionAPersonDoesNotShareIsNotGuessedAt() {
        let night = FriendsDay.Sleep(startTs: 0, endTs: 0, asleepMin: 400)
        let summary = FriendsDay(strain: 40, sleepScore: 80, sleep: night, workouts: [workout(minutes: 30)])
        let quiet = FriendProfile(id: "kate", name: "Kate", share: FriendsShare(sleep: false, workouts: false),
                                  days: day(summary))
        let me = person("me", strain: 10, sleep: 10)
        for sort in [FriendsSort.sleep, .strain] {
            let rows = FriendsBoard.rows(me: me, friends: [quiet], sort: sort, todayKey: today)
            XCTAssertNil(rows.first(where: { !$0.isMe })?.detail, sort.rawValue)
        }
        // Scores without the night behind them: the figure stands alone.
        XCTAssertNil(FriendsBoard.rows(me: me, friends: [], sort: .sleep, todayKey: today).first?.detail)
    }

    func testFiguresReadAsTheSummaryWritesThem() {
        XCTAssertEqual(FriendsMetric.recovery.text(72.6, scale: .hundred), "73%")
        XCTAssertEqual(FriendsMetric.sleep.text(88, scale: .hundred), "88%")
        XCTAssertEqual(FriendsMetric.strain.text(38.6, scale: .hundred),
                       FriendsFormat.strain(38.6, scale: .hundred))
    }
}
