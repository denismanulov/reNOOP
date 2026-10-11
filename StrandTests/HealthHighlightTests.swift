import XCTest
import WhoopProtocol
@testable import Strand

/// The figures behind the Highlights cards (`HealthHighlightMath`), the slots their sentences carry, and
/// the hourly cut of the strap's step counter.
final class HealthHighlightTests: XCTestCase {

    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        c.firstWeekday = 2
        return c
    }()

    private func date(_ y: Int, _ m: Int, _ d: Int, hour: Int = 0, minute: Int = 0) -> Date {
        cal.date(from: DateComponents(year: y, month: m, day: d, hour: hour, minute: minute))!
    }

    private func key(_ date: Date) -> String { HealthHighlightMath.key(date, calendar: cal) }

    /// `count` days ending on `last`, each with `value(daysBack)`.
    private func series(ending last: Date, count: Int, _ value: (Int) -> Double) -> [(day: String, value: Double)] {
        (0..<count).reversed().map { back in
            (day: key(cal.date(byAdding: .day, value: -back, to: last)!), value: value(back))
        }
    }

    // MARK: Direction

    func testDirectionReadsFivePercentAsTheSame() {
        XCTAssertEqual(HighlightDirection.of(104, against: 100), .same)
        XCTAssertEqual(HighlightDirection.of(106, against: 100), .more)
        XCTAssertEqual(HighlightDirection.of(94, against: 100), .less)
        XCTAssertEqual(HighlightDirection.of(0, against: 0), .same)
    }

    // MARK: Today against a usual day

    func testProgressComparesTodayWithTheUsualTotalByNow() {
        let flat = [Double](repeating: 100, count: 24)
        var today = [Double](repeating: 0, count: 24)
        for hour in 0..<12 { today[hour] = 200 }
        let p = HealthHighlightMath.progress(todayHourly: today, priorHourly: [flat, flat, flat],
                                             now: date(2026, 10, 11, hour: 12), calendar: cal)
        XCTAssertEqual(p?.todayTotal ?? 0, 2400, accuracy: 0.001)
        XCTAssertEqual(p?.typicalTotal ?? 0, 1200, accuracy: 0.001)
        XCTAssertEqual(p?.nowFraction ?? 0, 0.5, accuracy: 0.0001)
        XCTAssertEqual(p?.direction, .more)
        XCTAssertEqual(p?.typical.last?.total ?? 0, 2400, accuracy: 0.001)
        XCTAssertEqual(p?.today.last?.fraction ?? 0, 0.5, accuracy: 0.0001)
    }

    func testProgressCountsTheHourInProgressAndReadsTheUsualBetweenHours() {
        let flat = [Double](repeating: 60, count: 24)
        var today = [Double](repeating: 0, count: 24)
        today[0] = 10
        today[1] = 30
        let p = HealthHighlightMath.progress(todayHourly: today, priorHourly: [flat, flat, flat],
                                             now: date(2026, 10, 11, hour: 1, minute: 30), calendar: cal)
        XCTAssertEqual(p?.todayTotal ?? 0, 40, accuracy: 0.001)
        XCTAssertEqual(p?.typicalTotal ?? 0, 90, accuracy: 0.001)
        XCTAssertEqual(p?.direction, .less)
    }

    func testProgressLeavesOutDaysOffTheWristAndNeedsThree() {
        let flat = [Double](repeating: 100, count: 24)
        let empty = [Double](repeating: 0, count: 24)
        let now = date(2026, 10, 11, hour: 12)
        XCTAssertNil(HealthHighlightMath.progress(todayHourly: flat, priorHourly: [flat, flat, empty], now: now, calendar: cal))
        let p = HealthHighlightMath.progress(todayHourly: flat, priorHourly: [flat, flat, flat, empty], now: now, calendar: cal)
        XCTAssertEqual(p?.typical.last?.total ?? 0, 2400, accuracy: 0.001)
        XCTAssertEqual(p?.direction, .same)
    }

    // MARK: Seven days

    func testSevenDayAveragesTheDaysOnRecord() {
        let last = date(2026, 10, 10)
        var rows = series(ending: last, count: 7) { _ in 1000 }
        rows.remove(at: 2)
        rows[0].value = 4000
        guard let week = HealthHighlightMath.sevenDay(series: rows, lastDay: last, calendar: cal) else {
            return XCTFail("no week")
        }
        XCTAssertEqual(week.values.count, 7)
        XCTAssertEqual(week.values.map { $0 == nil }, [false, false, true, false, false, false, false])
        XCTAssertEqual(week.average, 9000.0 / 6, accuracy: 0.001)
        XCTAssertEqual(week.days.last, last)
        XCTAssertEqual(week.days.first, date(2026, 10, 4))
    }

    func testSevenDayIgnoresDaysOutsideTheWeekAndNeedsThree() {
        let last = date(2026, 10, 10)
        let old = series(ending: date(2026, 9, 30), count: 10) { _ in 500 }
        XCTAssertNil(HealthHighlightMath.sevenDay(series: old, lastDay: last, calendar: cal))
        let two = series(ending: last, count: 2) { _ in 500 }
        XCTAssertNil(HealthHighlightMath.sevenDay(series: old + two, lastDay: last, calendar: cal))
    }

    // MARK: This period against the last

    func testMonthComparesDailyMeansUpToToday() {
        let today = date(2026, 10, 11)
        let rows = series(ending: date(2026, 10, 20), count: 50) { back in back < 20 ? 1200 : 800 }
        let c = HealthHighlightMath.comparison(series: rows, period: .month, today: today, calendar: cal)
        XCTAssertEqual(c?.current ?? 0, 1200, accuracy: 0.001)
        XCTAssertEqual(c?.previous ?? 0, 800, accuracy: 0.001)
        XCTAssertEqual(c?.direction, .more)
        XCTAssertEqual(c?.currentStart, date(2026, 10, 1))
        XCTAssertEqual(c?.previousStart, date(2026, 9, 1))
    }

    func testMonthNeedsThreeDaysOfEach() {
        let rows = series(ending: date(2026, 10, 2), count: 20) { _ in 100 }
        XCTAssertNil(HealthHighlightMath.comparison(series: rows, period: .month, today: date(2026, 10, 2), calendar: cal))
    }

    func testYearComparesAgainstTheYearBeforeAndTotalsAddUp() {
        let rows = series(ending: date(2026, 3, 1), count: 200) { back in back < 60 ? 50 : 100 }
        let year = HealthHighlightMath.comparison(series: rows, period: .year, today: date(2026, 3, 1), calendar: cal)
        XCTAssertEqual(year?.current ?? 0, 50, accuracy: 0.001)
        XCTAssertEqual(year?.previous ?? 0, 100, accuracy: 0.001)
        XCTAssertEqual(year?.direction, .less)
        XCTAssertEqual(year?.previousStart, date(2025, 1, 1))

        let counts = [(day: "2026-10-03", value: 2.0), (day: "2026-10-09", value: 1.0), (day: "2026-09-12", value: 3.0)]
        let total = HealthHighlightMath.comparison(series: counts, period: .month, today: date(2026, 10, 11),
                                                   calendar: cal, total: true)
        XCTAssertEqual(total?.current ?? 0, 3, accuracy: 0.001)
        XCTAssertEqual(total?.previous ?? 0, 3, accuracy: 0.001)
        XCTAssertEqual(total?.direction, .same)
    }

    // MARK: A range over time

    func testRangeCutsAWorkoutIntoRoundSlices() {
        // 54 minutes at one reading a minute: three-minute slices.
        let samples: [(ts: Int, low: Double, high: Double)] = (0..<54).map { minute in
            let bpm = Double(100 + minute)
            return (ts: 1000 + minute * 60, low: bpm, high: bpm)
        }
        let r = HealthHighlightMath.range(samples: samples, start: 1000, end: 1000 + 54 * 60)
        XCTAssertEqual(HealthHighlightMath.sliceLength(span: 54 * 60), 180)
        XCTAssertEqual(r?.slices.count, 18)
        XCTAssertEqual(r?.minimum ?? 0, 100, accuracy: 0.001)
        XCTAssertEqual(r?.maximum ?? 0, 153, accuracy: 0.001)
        XCTAssertEqual(r?.minimumIndex, 0)
        XCTAssertEqual(r?.maximumIndex, 17)
        XCTAssertEqual(r?.slices[0], HighlightRange.Slice(low: 100, high: 102))
    }

    func testRangeKeepsGapsAndNeedsFourSlices() {
        XCTAssertEqual(HealthHighlightMath.sliceLength(span: 8 * 3600), 1800)
        var night: [(ts: Int, low: Double, high: Double)] = []
        for slice in [0, 1, 5, 9] {
            let offset = Double(slice)
            night.append((ts: slice * 1800 + 10, low: 50 + offset, high: 60 + offset))
        }
        let r = HealthHighlightMath.range(samples: night, start: 0, end: 8 * 3600)
        XCTAssertEqual(r?.slices.count, 16)
        XCTAssertEqual(r?.slices.compactMap { $0 }.count, 4)
        XCTAssertEqual(r?.slices[2], HighlightRange.Slice?.none)
        XCTAssertEqual(r?.maximumIndex, 9)
        XCTAssertNil(HealthHighlightMath.range(samples: Array(night.prefix(3)), start: 0, end: 8 * 3600))
        XCTAssertNil(HealthHighlightMath.range(samples: night, start: 100, end: 100))
    }

    // MARK: After a workout

    func testRecoveryReadsEachMinuteAndTheDrop() {
        let end = 10_000
        let samples: [(ts: Int, bpm: Double)] = stride(from: -20, through: 200, by: 5).map { second in
            (ts: end + second, bpm: 150 - Double(second) / 5)
        }
        let r = HealthHighlightMath.recovery(samples: samples, end: end)
        XCTAssertEqual(r?.atEnd, 150)
        XCTAssertEqual(r?.afterOneMinute, 138)
        XCTAssertEqual(r?.afterTwoMinutes, 126)
        XCTAssertEqual(r?.afterThreeMinutes, 114)
        XCTAssertEqual(r?.drop, 36)
        // One dot per fifteen seconds, from the end to three minutes on.
        XCTAssertEqual(r?.readings.count, 13)
        XCTAssertEqual(r?.readings.first?.seconds, 0)
        XCTAssertEqual(r?.readings.last?.seconds, 180)
    }

    func testRecoveryNeedsEveryMinuteAndAFall() {
        let end = 10_000
        let seconds = Array(stride(from: 0, through: 200, by: 5))
        let gap: [(ts: Int, bpm: Double)] = seconds.filter { !(90...150).contains($0) }.map { second in
            (ts: end + second, bpm: 150 - Double(second) / 5)
        }
        XCTAssertNil(HealthHighlightMath.recovery(samples: gap, end: end))
        let rising: [(ts: Int, bpm: Double)] = seconds.map { second in (ts: end + second, bpm: 100 + Double(second) / 5) }
        XCTAssertNil(HealthHighlightMath.recovery(samples: rising, end: end))
    }

    // MARK: Days that had one

    func testConsistencyLaysOutWholeWeeksAndLeavesTheDaysAhead() {
        // Wednesday 7 October 2026, weeks starting on Monday.
        let today = date(2026, 10, 7)
        let active: Set<String> = ["2026-10-05", "2026-10-07", "2026-09-30", "2026-10-09", "2026-09-01"]
        let month = HealthHighlightMath.consistency(activeDays: active, weeks: 4, today: today, calendar: cal)
        XCTAssertEqual(month?.days.count, 28)
        XCTAssertEqual(month?.days.first?.date, date(2026, 9, 14))
        XCTAssertEqual(month?.days.last?.date, date(2026, 10, 11))
        XCTAssertEqual(month?.count, 3)
        XCTAssertEqual(month?.days.last?.mark, .ahead)
        XCTAssertEqual(month?.days.first?.mark, .missed)

        let week = HealthHighlightMath.consistency(activeDays: active, weeks: 1, today: today, calendar: cal)
        XCTAssertEqual(week?.days.map(\.mark), [.done, .missed, .done, .ahead, .ahead, .ahead, .ahead])
        XCTAssertEqual(week?.count, 2)
    }

    // MARK: Steps by the hour

    func testHourlyTicksKeepTheStepsAcrossAnHourEdge() {
        // A counter rising two ticks a minute through the first three hours of the day.
        let samples = (0...180).map { StepSample(ts: $0 * 60, counter: $0 * 2, activityClass: nil) }
        let hours = HealthHighlightLoader.hourlyTicks(samples: samples, dayStart: 0, dayEnd: 86_400)
        XCTAssertEqual(hours.count, 24)
        XCTAssertEqual(hours[0], 118)
        XCTAssertEqual(hours[1], 120)
        XCTAssertEqual(hours[2], 120)
        XCTAssertEqual(hours[3], 2)
        XCTAssertEqual(hours.reduce(0, +), 360)
        XCTAssertEqual(hours[4...].reduce(0, +), 0)
    }

    // MARK: Copy

    func testFillReplacesEverySlotItIsGiven() {
        XCTAssertEqual(HighlightCopy.fill("@A@ and @B@, @A@", ["A": "1", "B": "2"]), "1 and 2, 1")
    }

    func testUnitWordIsWhatFollowsTheBar() {
        XCTAssertEqual(HighlightCopy.unit("8502|steps"), "steps")
        XCTAssertEqual(HighlightCopy.unit("steps"), "steps")
    }

    func testSportsWithoutASentenceReadAsAWorkout() {
        XCTAssertEqual(HighlightCopy.sportKey("Running"), "run")
        XCTAssertEqual(HighlightCopy.sportKey("HIIT"), "hiit")
        XCTAssertEqual(HighlightCopy.sportKey("Strength"), "other")
        XCTAssertEqual(HighlightCopy.sportKey("detected"), "other")
    }

    /// Every sentence in the catalogue, in every language, carries only slots the app fills: a slot nobody
    /// fills would be printed raw.
    func testCatalogueSentencesCarryOnlyKnownSlots() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("Strand/Resources/Localizable.xcstrings"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let strings = try XCTUnwrap(json["strings"] as? [String: Any])
        let slot = try NSRegularExpression(pattern: "@([A-Z_0-9]+)@")
        var checked = 0
        func values(_ node: Any) -> [String] {
            guard let dict = node as? [String: Any] else { return [] }
            if let unit = dict["stringUnit"] as? [String: Any], let value = unit["value"] as? String { return [value] }
            return dict.values.flatMap(values)
        }
        for (key, entry) in strings where key.hasPrefix("hl.") {
            for value in values(entry) {
                checked += 1
                let range = NSRange(value.startIndex..., in: value)
                for match in slot.matches(in: value, range: range) {
                    let name = (value as NSString).substring(with: match.range(at: 1))
                    XCTAssertTrue(HighlightCopy.tokens.contains(name), "\(key): @\(name)@ is not filled by the app")
                }
            }
        }
        XCTAssertGreaterThan(checked, 500)
    }
}
