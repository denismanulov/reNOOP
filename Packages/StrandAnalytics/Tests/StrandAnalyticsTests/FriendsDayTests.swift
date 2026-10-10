import XCTest
@testable import StrandAnalytics

/// The day a phone uploads for the Friends tab (`friends-server/README.md`, "A day"): every member, what
/// is left out when the app has no figure, each sharing switch, the heart-rate line's 300-point bound,
/// the Strain axis and the wire text.
///
/// The cases and their expected literals are those of Android's `FriendsDayPayloadTest`, which is the
/// reference for this builder: two friends on different platforms read each other's figures side by
/// side. The oracle runs one way only (Kotlin's literals, checked here), so a change to either side has
/// to be carried to the other by hand.
final class FriendsDayTests: XCTestCase {
    private typealias Builder = FriendsDayBuilder

    private let now = 1_791_540_000
    private let all = FriendsShare(scores: true, sleep: true, workouts: true, hr: true)
    private let none = FriendsShare(scores: false, sleep: false, workouts: false, hr: false)

    private func full() -> Builder.Input {
        Builder.Input(
            recovery: 80.6, strain: 38.604, sleepScore: 87.5,
            sleep: .init(startTs: now - 30_000, endTs: now - 1_080, asleepMin: 474.4, awakeMin: 18.2,
                         remMin: 104.0, lightMin: 252.49, deepMin: 99.6, needMin: 480.0),
            workouts: [.init(startTs: now - 9_600, sport: "Running", durationS: 1_860.4, strain: 35.2,
                             avgHr: 139, maxHr: 162, kcal: 310.3)],
            heartRate: [(now - 120, 64), (now - 60, 62)],
            restingBpm: 51)
    }

    private func build(_ input: Builder.Input, _ share: FriendsShare? = nil) -> FriendsDay {
        Builder.day(input, share: share ?? all, nowTs: now)
    }

    private func json(_ input: Builder.Input, _ share: FriendsShare? = nil) -> String {
        Builder.json(build(input, share))
    }

    // MARK: - Every member

    func testEveryMemberIsBuiltAndRoundedToTheWire() {
        let day = build(full())
        XCTAssertEqual(day.recovery, 81)
        XCTAssertEqual(day.strain, 38.6)
        XCTAssertEqual(day.sleepScore, 88)
        XCTAssertEqual(day.sleep, FriendsDay.Sleep(startTs: now - 30_000, endTs: now - 1_080, asleepMin: 474,
                                                   awakeMin: 18, remMin: 104, lightMin: 252, deepMin: 100,
                                                   needMin: 480))
        XCTAssertEqual(day.workouts, [FriendsDay.Workout(startTs: now - 9_600, sport: "Running", durationS: 1_860,
                                                         strain: 35.2, avgHr: 139, maxHr: 162, kcal: 310)])
        XCTAssertEqual(day.hr, FriendsDay.HeartRate(lastBpm: 62, lastTs: now - 60, restingBpm: 51,
                                                    series: [[now - 120, 64], [now - 60, 62]]))
    }

    func testTheWireTextIsTheReadmesObjectInItsOrder() throws {
        let text = json(full())
        XCTAssertEqual(text,
            "{\"recovery\":81,\"strain\":38.6,\"sleepScore\":88,"
            + "\"sleep\":{\"startTs\":\(now - 30_000),\"endTs\":\(now - 1_080),\"asleepMin\":474,\"awakeMin\":18,"
            + "\"remMin\":104,\"lightMin\":252,\"deepMin\":100,\"needMin\":480},"
            + "\"workouts\":[{\"startTs\":\(now - 9_600),\"sport\":\"Running\",\"durationS\":1860,\"strain\":35.2,"
            + "\"avgHr\":139,\"maxHr\":162,\"kcal\":310}],"
            + "\"hr\":{\"lastBpm\":62,\"lastTs\":\(now - 60),\"restingBpm\":51,\"series\":[[\(now - 120),64],[\(now - 60),62]]}}")
        // It is JSON a strict parser reads back as the same day, with no member the server would refuse.
        XCTAssertEqual(try JSONDecoder().decode(FriendsDay.self, from: Data(text.utf8)), build(full()))
        let parsed = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
        XCTAssertEqual(Set(parsed.keys), ["recovery", "strain", "sleepScore", "sleep", "workouts", "hr"])
    }

    // MARK: - Omission

    func testADayWithNothingIsAnEmptyObjectNeverZeros() {
        let day = build(Builder.Input())
        XCTAssertEqual(day, FriendsDay())
        XCTAssertEqual(Builder.json(day), "{}")
    }

    func testAMissingFigureIsLeftOutWhileItsNeighboursStay() throws {
        var input = full()
        input.recovery = nil
        input.restingBpm = nil
        let day = build(input)
        XCTAssertNil(day.recovery)
        XCTAssertEqual(day.strain, 38.6)
        XCTAssertNil(try XCTUnwrap(day.hr).restingBpm)
        let text = Builder.json(day)
        XCTAssertFalse(text.contains("recovery"))
        XCTAssertFalse(text.contains("restingBpm"))
        XCTAssertTrue(text.contains("\"sleepScore\":88"))
    }

    func testOptionalSleepAndWorkoutMembersAreLeftOutNotNulled() {
        let input = Builder.Input(
            sleep: .init(startTs: now - 30_000, endTs: now - 1_000, asleepMin: 400.0),
            workouts: [.init(startTs: now - 900, sport: "Strength", durationS: 600.0)])
        XCTAssertEqual(json(input),
            "{\"sleep\":{\"startTs\":\(now - 30_000),\"endTs\":\(now - 1_000),\"asleepMin\":400},"
            + "\"workouts\":[{\"startTs\":\(now - 900),\"sport\":\"Strength\",\"durationS\":600}]}")
    }

    func testADayWithNoWorkoutSaysSoAndAnUnreadOneSaysNothing() {
        XCTAssertEqual(json(Builder.Input(workouts: [])), "{\"workouts\":[]}")
        XCTAssertEqual(json(Builder.Input(workouts: nil)), "{}")
    }

    func testNoHeartRateSampleMeansNoHeartRateSectionEvenWithARestingFigure() {
        XCTAssertNil(build(Builder.Input(restingBpm: 51)).hr)
    }

    func testFiguresTheServerWouldRefuseAreLeftOutSoTheDayStillGoesUp() {
        var input = full()
        input.recovery = 140.0
        input.strain = -3.0
        input.sleepScore = .nan
        input.workouts = [
            .init(startTs: now - 900, sport: "Running", durationS: 600.0, strain: 250.0, avgHr: 300, maxHr: 10, kcal: 99_999.0),
            .init(startTs: now - 800, sport: "   ", durationS: 600.0),
            .init(startTs: 100, sport: "Cycling", durationS: 600.0),
            .init(startTs: now - 700, sport: "Rowing", durationS: 200_000.0),
        ]
        input.heartRate = [(now - 60, 62), (now - 30, 400), (now + 10 * 86_400, 70)]
        input.restingBpm = 5
        let day = build(input)
        XCTAssertNil(day.recovery)
        XCTAssertNil(day.strain)
        XCTAssertNil(day.sleepScore)
        XCTAssertEqual(day.workouts, [FriendsDay.Workout(startTs: now - 900, sport: "Running", durationS: 600)])
        XCTAssertEqual(day.hr, FriendsDay.HeartRate(lastBpm: 62, lastTs: now - 60, restingBpm: nil,
                                                    series: [[now - 60, 62]]))
    }

    func testASleepThatEndsBeforeItStartsIsNotSent() {
        XCTAssertNil(build(Builder.Input(sleep: .init(startTs: now - 100, endTs: now - 200, asleepMin: 60.0))).sleep)
        XCTAssertNil(build(Builder.Input(sleep: .init(startTs: now - 200, endTs: now - 100, asleepMin: .nan))).sleep)
    }

    // MARK: - Sharing switches

    func testScoresOffDropsTheThreeScoresOnly() {
        var expected = build(full())
        expected.recovery = nil
        expected.strain = nil
        expected.sleepScore = nil
        XCTAssertEqual(build(full(), FriendsShare(scores: false, sleep: true, workouts: true, hr: true)), expected)
    }

    func testSleepOffDropsTheNightOnly() {
        var expected = build(full())
        expected.sleep = nil
        XCTAssertEqual(build(full(), FriendsShare(scores: true, sleep: false, workouts: true, hr: true)), expected)
    }

    func testWorkoutsOffDropsTheWorkoutsOnly() {
        var expected = build(full())
        expected.workouts = nil
        XCTAssertEqual(build(full(), FriendsShare(scores: true, sleep: true, workouts: false, hr: true)), expected)
    }

    func testHeartRateOffDropsLatestLineAndRestingTogether() {
        var expected = build(full())
        expected.hr = nil
        let day = build(full(), FriendsShare(scores: true, sleep: true, workouts: true, hr: false))
        XCTAssertEqual(day, expected)
        let text = Builder.json(day)
        XCTAssertFalse(text.contains("hr"))
        XCTAssertFalse(text.contains("restingBpm"))
    }

    func testEverySwitchOffSendsNothingAtAll() {
        XCTAssertEqual(json(full(), none), "{}")
    }

    func testHeartRateIsOffByDefault() {
        XCTAssertEqual(FriendsShare(), FriendsShare(scores: true, sleep: true, workouts: true, hr: false))
        XCTAssertNil(build(full(), FriendsShare()).hr)
    }

    // MARK: - Strain axis

    func testStrainStaysOnTheStored0To100AxisForTheDayAndEachWorkout() {
        // 59.05 stored is 12.4 on the 21 scale; the wire carries the stored value whatever the display scale.
        let day = build(Builder.Input(strain: 59.05,
                                      workouts: [.init(startTs: now - 900, sport: "Running", durationS: 600.0, strain: 100.0)]))
        XCTAssertEqual(day.strain, 59.05)
        XCTAssertEqual(day.workouts?.first?.strain, 100.0)
        let text = Builder.json(day)
        XCTAssertTrue(text.hasPrefix("{\"strain\":59.05,"), text)
        XCTAssertTrue(text.contains("\"strain\":100}"), text)
    }

    func testStrainIsWrittenWithoutExponentOrTrailingZeros() {
        func text(_ strain: Double) -> String { json(Builder.Input(strain: strain)) }
        XCTAssertEqual(text(0.0), "{\"strain\":0}")
        XCTAssertEqual(text(0.005), "{\"strain\":0.01}")
        XCTAssertEqual(text(7.0), "{\"strain\":7}")
        XCTAssertEqual(text(38.605), "{\"strain\":38.61}")
        XCTAssertEqual(text(99.999), "{\"strain\":100}")
        XCTAssertEqual(text(100.01), "{}")
        // Not in the Kotlin test: figures a double prints with an exponent, and ones past any range.
        XCTAssertEqual(text(0.00004), "{\"strain\":0}")
        XCTAssertEqual(text(1e300), "{}")
        XCTAssertEqual(text(.infinity), "{}")
    }

    // MARK: - Whole numbers

    /// Halves go toward positive infinity, as Kotlin's `roundToInt` sends them, a negative one included.
    func testWholeNumbersRoundHalfUpAndStayInRange() {
        XCTAssertEqual(Builder.whole(80.5, in: 0...100), 81)
        XCTAssertEqual(Builder.whole(80.49, in: 0...100), 80)
        XCTAssertEqual(Builder.whole(-0.5, in: 0...100), 0)
        XCTAssertNil(Builder.whole(-0.51, in: 0...100))
        XCTAssertEqual(Builder.whole(100.49, in: 0...100), 100)
        XCTAssertNil(Builder.whole(100.5, in: 0...100))
        XCTAssertNil(Builder.whole(.nan, in: 0...100))
        XCTAssertNil(Builder.whole(1e300, in: 0...100))
        XCTAssertNil(Builder.whole(nil, in: 0...100))
    }

    // MARK: - The heart-rate line

    func testADayAtOneSampleASecondIsThinnedToAtMost300Points() throws {
        let start = now - 86_399
        let samples = (0..<86_400).map { (ts: start + $0, bpm: 50 + ($0 / 600) % 90) }
        let hr = try XCTUnwrap(build(Builder.Input(heartRate: samples)).hr)
        let series = try XCTUnwrap(hr.series)
        XCTAssertLessThanOrEqual(series.count, Builder.maxSeriesPoints)
        XCTAssertGreaterThanOrEqual(series.count, 290)
        // In order, inside the day, and ending on the newest sample itself.
        XCTAssertEqual(series.map { $0[0] }, series.map { $0[0] }.sorted())
        XCTAssertEqual(Set(series.map { $0[0] }).count, series.count)
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(series.first)[0], start)
        XCTAssertEqual(series.last, [samples[86_399].ts, samples[86_399].bpm])
        XCTAssertEqual(hr.lastBpm, samples[86_399].bpm)
        XCTAssertEqual(hr.lastTs, samples[86_399].ts)
        XCTAssertTrue(series.allSatisfy { (20...250).contains($0[1]) })
    }

    func testTheBoundHoldsForEverySizeAroundIt() {
        for n in [1, 2, 299, 300, 301, 302, 599, 600, 601, 5_000] {
            let samples = (0..<n).map { (ts: now - n + $0, bpm: 60 + $0 % 40) }
            let out = Builder.downsample(samples)
            XCTAssertLessThanOrEqual(out.count, 300, "\(n)")
            XCTAssertEqual(out.last?.ts, samples.last?.ts, "\(n)")
            XCTAssertEqual(out.last?.bpm, samples.last?.bpm, "\(n)")
            if n <= 300 {
                XCTAssertEqual(out.map(\.ts), samples.map(\.ts), "\(n)")
                XCTAssertEqual(out.map(\.bpm), samples.map(\.bpm), "\(n)")
            }
        }
    }

    func testAThinnedStretchIsTheMeanOfItsSamples() {
        // 600 samples in two flat halves, thinned to 3 points: two stretches and the newest sample.
        let samples = (0..<300).map { (ts: now - 600 + $0, bpm: 60) } + (300..<600).map { (ts: now - 600 + $0, bpm: 120) }
        let out = Builder.downsample(samples, maxPoints: 3)
        XCTAssertEqual(out.count, 3)
        XCTAssertEqual(out[0].bpm, 60)
        XCTAssertEqual(out[1].bpm, 120)
        XCTAssertEqual(out[2].ts, samples[599].ts)
        XCTAssertEqual(out[2].bpm, samples[599].bpm)
    }

    func testSamplesArriveInAnyOrderAndOnePerSecondIsKept() throws {
        let hr = try XCTUnwrap(build(Builder.Input(heartRate: [(now - 10, 70), (now - 30, 60), (now - 10, 99)])).hr)
        XCTAssertEqual(hr.series, [[now - 30, 60], [now - 10, 70]])
        XCTAssertEqual(hr.lastBpm, 70)
    }

    // MARK: - Workouts

    func testAtMostTwentyWorkoutsGoUpAndTheNewestStay() throws {
        let many = (0..<25).map { Builder.WorkoutInput(startTs: now - 50_000 + $0 * 1_000, sport: "Walking", durationS: 600.0) }
        let sent = try XCTUnwrap(build(Builder.Input(workouts: many.shuffled())).workouts)
        XCTAssertEqual(sent.count, Builder.maxWorkouts)
        XCTAssertEqual(sent.map(\.startTs), many.dropFirst(5).map(\.startTs))
    }

    func testASportLabelIsTrimmedCutTo40AndStrippedOfControlCharacters() {
        XCTAssertEqual(Builder.sportLabel("  Open-water swim \n"), "Open-water swim")
        XCTAssertEqual(Builder.sportLabel(String(repeating: "a", count: 55)), String(repeating: "a", count: 40))
        XCTAssertEqual(Builder.sportLabel("Trail\u{0007} run\u{200B}"), "Trail run")
        XCTAssertEqual(Builder.sportLabel("Бег"), "Бег")
        XCTAssertNil(Builder.sportLabel(" \t\n"))
    }

    func testASportLabelIsQuotedAsJson() throws {
        let text = json(Builder.Input(workouts: [.init(startTs: now - 900, sport: "Push \"n\" pull\\", durationS: 60.0)]))
        let day = try JSONDecoder().decode(FriendsDay.self, from: Data(text.utf8))
        XCTAssertEqual(day.workouts?.first?.sport, "Push \"n\" pull\\")
    }

    /// The recorded duration when it is a usable figure, else the span, never a negative one.
    func testActiveTimeIsTheRecordedDurationOrTheSpan() {
        XCTAssertEqual(Builder.activeSeconds(durationS: 1_860.4, startTs: 0, endTs: 3_600), 1_860.4)
        XCTAssertEqual(Builder.activeSeconds(durationS: nil, startTs: 100, endTs: 400), 300)
        XCTAssertEqual(Builder.activeSeconds(durationS: -5, startTs: 100, endTs: 400), 300)
        XCTAssertEqual(Builder.activeSeconds(durationS: .nan, startTs: 400, endTs: 100), 0)
    }

    // MARK: - Reading a day back

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
