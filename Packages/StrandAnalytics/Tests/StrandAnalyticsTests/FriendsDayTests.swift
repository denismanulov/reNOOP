import XCTest
@testable import StrandAnalytics

/// The one summary a phone uploads per day for the Friends tab: what it holds, how each figure is
/// rounded and capped, and above all that a section whose sharing switch is off is not built at all.
final class FriendsDayTests: XCTestCase {
    private typealias Builder = FriendsDayBuilder

    private let t0 = 1_791_500_000

    private func fullInput() -> Builder.Input {
        Builder.Input(
            recovery: 80.6, strain: 38.64, sleepScore: 87.5,
            sleep: .init(startTs: t0, endTs: t0 + 28_920, awakeMin: 18.2, remMin: 103.6, lightMin: 252.4,
                         deepMin: 99.5, needMin: 495.4),
            workouts: [.init(startTs: t0 + 30_400, endTs: t0 + 32_260, sport: "Running", durationS: 1860,
                             strain: 35.24, avgHr: 139, maxHr: 162, kcal: 310.4)],
            heartRate: [(t0 + 39_880, 64), (t0 + 40_000, 62)],
            restingBpm: 51)
    }

    // MARK: - Sharing switches

    func testEverySectionIsBuiltWhenItsSwitchIsOn() {
        let day = Builder.day(fullInput(), share: FriendsShare(scores: true, sleep: true, workouts: true, hr: true))
        XCTAssertEqual(day.recovery, 81)
        XCTAssertEqual(day.strain, 38.6)
        XCTAssertEqual(day.sleepScore, 88)
        XCTAssertNotNil(day.sleep)
        XCTAssertEqual(day.workouts?.count, 1)
        XCTAssertNotNil(day.hr)
        XCTAssertFalse(day.isEmpty)
    }

    /// What is not shared is not sent: the phone does not lean on the server to drop it.
    func testASectionWhoseSwitchIsOffIsNotBuilt() {
        let input = fullInput()
        let noScores = Builder.day(input, share: FriendsShare(scores: false, sleep: true, workouts: true, hr: true))
        XCTAssertNil(noScores.recovery); XCTAssertNil(noScores.strain); XCTAssertNil(noScores.sleepScore)
        XCTAssertNotNil(noScores.sleep)

        XCTAssertNil(Builder.day(input, share: FriendsShare(scores: true, sleep: false, workouts: true, hr: true)).sleep)
        XCTAssertNil(Builder.day(input, share: FriendsShare(scores: true, sleep: true, workouts: false, hr: true)).workouts)
        XCTAssertNil(Builder.day(input, share: FriendsShare(scores: true, sleep: true, workouts: true, hr: false)).hr)

        let nothing = Builder.day(input, share: FriendsShare(scores: false, sleep: false, workouts: false, hr: false))
        XCTAssertTrue(nothing.isEmpty)
        XCTAssertEqual(nothing, FriendsDay())
    }

    func testHeartRateIsOffByDefault() {
        XCTAssertEqual(FriendsShare(), FriendsShare(scores: true, sleep: true, workouts: true, hr: false))
        XCTAssertNil(Builder.day(fullInput(), share: FriendsShare()).hr)
    }

    // MARK: - Scores

    func testScoresAreRoundedAndAnythingOutOfRangeIsDropped() {
        XCTAssertEqual(Builder.percent(80.5), 81)
        XCTAssertEqual(Builder.percent(0), 0)
        XCTAssertEqual(Builder.percent(100), 100)
        XCTAssertNil(Builder.percent(100.4))
        XCTAssertNil(Builder.percent(-1))
        XCTAssertNil(Builder.percent(.nan))
        XCTAssertEqual(Builder.tenth(38.64, in: 0...100), 38.6)
        XCTAssertEqual(Builder.tenth(38.65, in: 0...100), 38.7)
        XCTAssertNil(Builder.tenth(120, in: 0...100))
    }

    // MARK: - Sleep

    /// Time asleep is the sum of the rounded stages, so the parts a friend sees add up to the total.
    func testTimeAsleepIsTheSumOfItsRoundedStages() throws {
        let sleep = try XCTUnwrap(Builder.day(fullInput(), share: FriendsShare()).sleep)
        XCTAssertEqual(sleep, FriendsDay.Sleep(startTs: t0, endTs: t0 + 28_920, asleepMin: 104 + 252 + 100,
                                               awakeMin: 18, remMin: 104, lightMin: 252, deepMin: 100,
                                               needMin: 495))
    }

    func testANightWithNoSpanOrNoTimeAsleepIsNotBuilt() {
        XCTAssertNil(Builder.sleep(.init(startTs: t0, endTs: t0, awakeMin: 0, remMin: 60, lightMin: 60, deepMin: 60)))
        XCTAssertNil(Builder.sleep(.init(startTs: t0, endTs: t0 + 3_600, awakeMin: 60, remMin: 0, lightMin: 0, deepMin: 0.2)))
    }

    // MARK: - Workouts

    func testWorkoutsAreOrderedCappedAndTidied() {
        var rows: [Builder.WorkoutInput] = (0..<23).map { i in
            .init(startTs: t0 + i * 600, endTs: t0 + i * 600 + 300, sport: "Run \(i)")
        }
        rows.shuffle()
        let built = Builder.workouts(rows)
        XCTAssertEqual(built.count, 20)
        XCTAssertEqual(built.first?.sport, "Run 3", "the three oldest are the ones dropped")
        XCTAssertEqual(built.last?.sport, "Run 22")
        XCTAssertEqual(built.map(\.startTs), built.map(\.startTs).sorted())
        XCTAssertEqual(built.first?.durationS, 300, "a missing duration falls back to the span")

        let tidy = Builder.workouts([
            .init(startTs: t0, endTs: t0 + 60, sport: "  " + String(repeating: "x", count: 60) + " ",
                  durationS: 59.5, strain: 12.34, avgHr: 10, maxHr: 300, kcal: -5),
            .init(startTs: t0 + 100, endTs: t0 + 160, sport: "   "),
        ])
        XCTAssertEqual(tidy.count, 1, "a workout with no sport name is not sent")
        XCTAssertEqual(tidy[0].sport.count, 40)
        XCTAssertEqual(tidy[0].durationS, 60)
        XCTAssertEqual(tidy[0].strain, 12.3)
        XCTAssertNil(tidy[0].avgHr); XCTAssertNil(tidy[0].maxHr); XCTAssertNil(tidy[0].kcal)
    }

    // MARK: - Heart rate

    func testTheLineIsTheMeanOfFiveMinuteBinsOnTheUnixClock() throws {
        let bin = 1_791_500_100          // a multiple of 300
        XCTAssertEqual(bin % 300, 0)
        let hr = try XCTUnwrap(Builder.heartRate(
            [(bin + 10, 60), (bin + 20, 61), (bin + 299, 61),        // mean 60.67 → 61
             (bin + 300, 70), (bin + 301, 71),                        // mean 70.5 → 71 (half up)
             (bin + 900, 55),
             (bin + 950, 19), (bin + 960, 251)],                      // not readings
            restingBpm: 51))
        XCTAssertEqual(hr.series, [[bin, 61], [bin + 300, 71], [bin + 900, 55]])
        XCTAssertEqual(hr.lastBpm, 55)
        XCTAssertEqual(hr.lastTs, bin + 900)
        XCTAssertEqual(hr.restingBpm, 51)
    }

    func testNoUsableSampleMeansNoHeartRateAtAll() {
        XCTAssertNil(Builder.heartRate([], restingBpm: 51))
        XCTAssertNil(Builder.heartRate([(t0, 0), (t0 + 1, 400)], restingBpm: 51))
    }

    func testTheLineNeverExceedsTheServersCap() throws {
        // Two days of one sample a minute: 576 bins, of which the newest 300 are kept.
        let samples = (0..<2880).map { (ts: t0 + $0 * 60, bpm: 60 + $0 % 5) }
        let hr = try XCTUnwrap(Builder.heartRate(samples, restingBpm: nil))
        XCTAssertEqual(hr.series?.count, 300)
        XCTAssertEqual(hr.series?.last?[0], (t0 + 2879 * 60) / 300 * 300)
        XCTAssertEqual(hr.lastTs, t0 + 2879 * 60)
    }

    // MARK: - The wire form

    /// Members that are absent are omitted, not sent as null: the server reads a missing member as
    /// "not shown" and refuses an unknown one.
    func testADayEncodesToTheContractsJSON() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let scoresOnly = FriendsDay(recovery: 81, strain: 38.6, sleepScore: 88)
        XCTAssertEqual(String(decoding: try encoder.encode(scoresOnly), as: UTF8.self),
                       #"{"recovery":81,"sleepScore":88,"strain":38.6}"#)
        XCTAssertEqual(String(decoding: try encoder.encode(FriendsDay()), as: UTF8.self), "{}")

        let hr = FriendsDay(hr: .init(lastBpm: 62, lastTs: 1_791_540_000, series: [[1_791_539_880, 64]]))
        XCTAssertEqual(String(decoding: try encoder.encode(hr), as: UTF8.self),
                       #"{"hr":{"lastBpm":62,"lastTs":1791540000,"series":[[1791539880,64]]}}"#)
    }

    /// The example in `friends-server/README.md`, as a friend's day comes back.
    func testTheContractsExampleDecodes() throws {
        let json = #"""
        {"recovery": 81, "strain": 38.6, "sleepScore": 88,
         "sleep": {"startTs": 1791500000, "endTs": 1791528920, "asleepMin": 474,
                   "awakeMin": 18, "remMin": 104, "lightMin": 252, "deepMin": 100, "needMin": 495},
         "workouts": [{"startTs": 1791530400, "sport": "Running", "durationS": 1860,
                       "strain": 35.2, "avgHr": 139, "maxHr": 162, "kcal": 310}],
         "hr": {"lastBpm": 62, "lastTs": 1791540000, "restingBpm": 51,
                "series": [[1791539880, 64], [1791540000, 62]]}}
        """#
        let day = try JSONDecoder().decode(FriendsDay.self, from: Data(json.utf8))
        XCTAssertEqual(day.recovery, 81)
        XCTAssertEqual(day.sleep?.asleepMin, 474)
        XCTAssertEqual(day.workouts?.first?.sport, "Running")
        XCTAssertEqual(day.hr?.series?.count, 2)
        // A feed day leaves the line out and sends unknown optionals as null.
        let feed = #"{"hr": {"lastBpm": 62, "lastTs": 1791540000, "restingBpm": null}, "recovery": null}"#
        let light = try JSONDecoder().decode(FriendsDay.self, from: Data(feed.utf8))
        XCTAssertNil(light.hr?.series)
        XCTAssertNil(light.recovery)
    }
}
