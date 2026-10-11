import XCTest
import StrandAnalytics
@testable import Strand

/// A person's page on the Friends tab: which seven days it covers and how it names a day.
final class FriendPageTests: XCTestCase {
    private func uploaded(_ keys: [String]) -> [FriendFeedDay] {
        keys.enumerated().map { FriendFeedDay(day: $1, summary: FriendsDay(recovery: 50 + $0)) }
    }

    // MARK: - The last seven days

    func testTheWeekIsSevenDaysOldestFirstEndingOnTheAnchor() {
        let days = FriendWeek.days(ending: "2026-10-10", uploaded: [])
        XCTAssertEqual(days.map(\.key), ["2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
                                         "2026-10-09", "2026-10-10"])
        XCTAssertTrue(days.allSatisfy { $0.summary == nil })
    }

    func testTheWeekCrossesAMonthAndAYear() {
        let cases: [(anchor: String, first: String)] = [
            ("2026-10-03", "2026-09-27"), ("2027-01-02", "2026-12-27"), ("2028-03-02", "2028-02-25")
        ]
        for c in cases {
            let days = FriendWeek.days(ending: c.anchor, uploaded: [])
            XCTAssertEqual(days.count, 7, c.anchor)
            XCTAssertEqual(days.first?.key, c.first, c.anchor)
            XCTAssertEqual(days.last?.key, c.anchor, c.anchor)
        }
    }

    func testEachDayCarriesWhatWasUploadedForItAndNothingElse() {
        let days = FriendWeek.days(ending: "2026-10-10",
                                   uploaded: uploaded(["2026-10-10", "2026-10-08", "2026-10-01"]))
        XCTAssertEqual(days.map { $0.summary?.recovery }, [nil, nil, nil, nil, 51, nil, 50],
                       "a day outside the week is not drawn, and a day without an upload stays empty")
    }

    func testADayUploadedTwiceIsReadOnce() {
        let twice = [FriendFeedDay(day: "2026-10-10", summary: FriendsDay(recovery: 80)),
                     FriendFeedDay(day: "2026-10-10", summary: FriendsDay(recovery: 10))]
        XCTAssertEqual(FriendWeek.days(ending: "2026-10-10", uploaded: twice).last?.summary?.recovery, 80)
    }

    func testAKeyThatIsNotADateYieldsNoWeek() {
        for key in ["", "today", "2026-13-40", "10.10.2026"] {
            XCTAssertTrue(FriendWeek.days(ending: key, uploaded: uploaded(["2026-10-10"])).isEmpty, key)
        }
    }

    func testTheWeekEndsOnTheWearersDayUnlessTheFriendsCalendarIsAhead() {
        let cases: [(today: String, newest: String?, anchor: String)] = [
            ("2026-10-10", nil, "2026-10-10"), ("2026-10-10", "2026-10-10", "2026-10-10"),
            ("2026-10-10", "2026-10-08", "2026-10-10"), ("2026-10-10", "2026-10-11", "2026-10-11")
        ]
        for c in cases {
            XCTAssertEqual(FriendWeek.anchor(todayKey: c.today, newest: c.newest), c.anchor,
                           "\(c.today) / \(c.newest ?? "nothing uploaded")")
        }
    }

    // MARK: - Naming a day

    /// Noon on the day `key` names, by the calendar the keys are counted in.
    private func noon(_ key: String) -> Date {
        FriendsFormat.date(key)!.addingTimeInterval(12 * 3_600)
    }

    func testADaysKeyReadsBackAsItself() {
        for key in ["2026-10-10", "2026-01-01", "2028-02-29"] {
            XCTAssertEqual(FriendsFormat.date(key).map(FriendsFormat.key), key)
        }
    }

    func testAWeekdayGoesByOneLetterInTheActiveLanguage() {
        // 10 October 2026 is a Saturday.
        let english = Locale(identifier: "en_GB"), russian = Locale(identifier: "ru_RU")
        XCTAssertEqual((4...10).map { FriendsFormat.weekdayLetter(String(format: "2026-10-%02d", $0), locale: english) },
                       ["S", "M", "T", "W", "T", "F", "S"])
        XCTAssertEqual((4...10).map { FriendsFormat.weekdayLetter(String(format: "2026-10-%02d", $0), locale: russian) },
                       ["В", "П", "В", "С", "Ч", "П", "С"])
        XCTAssertEqual(FriendsFormat.weekdayLetter("nonsense", locale: english), "")
    }

    func testAWorkoutOfThePastWeekIsDatedByItsWeekday() {
        let now = noon("2026-10-10")
        let english = Locale(identifier: "en_GB"), russian = Locale(identifier: "ru_RU")
        let cases: [(key: String, en: String, ru: String)] = [
            ("2026-10-08", "Thursday", "Четверг"), ("2026-10-05", "Monday", "Понедельник"),
            ("2026-10-04", "Sunday", "Воскресенье")
        ]
        for c in cases {
            XCTAssertEqual(FriendsFormat.weekdayLabel(c.key, now: now, locale: english), c.en, c.key)
            XCTAssertEqual(FriendsFormat.weekdayLabel(c.key, now: now, locale: russian), c.ru, c.key)
        }
    }

    func testTodayYesterdayAndAnythingOlderThanAWeekAreNamedAsEverywhereElse() {
        let now = noon("2026-10-10")
        // Today and yesterday by their words, a week back and beyond (and a day ahead) by the date: the
        // same answer `dayLabel` gives, so the page and the list never name one day two ways.
        for key in ["2026-10-10", "2026-10-09", "2026-10-03", "2026-09-01", "2026-10-11"] {
            XCTAssertEqual(FriendsFormat.weekdayLabel(key, now: now), FriendsFormat.dayLabel(key, now: now), key)
        }
        XCTAssertEqual(FriendsFormat.weekdayLabel("nonsense", now: now), "nonsense")
    }
}
