import XCTest
@testable import Strand

/// Pins the Sleep page's pure pieces: the night clock, the range windows and the highlight thresholds.
final class SleepHistoryTests: XCTestCase {
    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    private func day(_ offset: Int) -> Date {
        cal.date(byAdding: .day, value: offset, to: cal.date(from: DateComponents(year: 2026, month: 9, day: 26))!)!
    }

    /// A night ending on `day(offset)`: bed at `bedMin` minutes after 18:00 the evening before, asleep `asleep`.
    private func night(_ offset: Int, bedMin: Double = 300, asleep: Double = 420) -> SleepNightEntry {
        let origin = SleepNightEntry.nightOrigin(day(offset))
        let onset = origin.addingTimeInterval(bedMin * 60)
        return SleepNightEntry(day: day(offset), onset: onset, wake: onset.addingTimeInterval((asleep + 20) * 60),
                               asleepMin: asleep)
    }

    func testNightClockRunsThroughMidnight() {
        let e = night(0, bedMin: 330)   // 23:30
        XCTAssertEqual(e.onsetOfNightMin, 330, accuracy: 0.001)
        XCTAssertEqual(e.wakeOfNightMin, 330 + 440, accuracy: 0.001)
    }

    func testWeekWindowHasSevenSlotsAndOnlyNightsWithData() {
        let entries = [night(-8), night(-6), night(-3), night(0)]
        let w = SleepHistory.window(.week, entries: entries, anchor: day(0), calendar: cal)
        XCTAssertEqual(w.slotStarts.count, 7)
        XCTAssertEqual(w.slotStarts.last, day(0))
        XCTAssertEqual(w.bars.map(\.slot), [0, 3, 6])
        XCTAssertEqual(w.averageAsleepMin ?? 0, 420, accuracy: 0.001)
    }

    func testMonthWindowHasThirtySlots() {
        let w = SleepHistory.window(.month, entries: [night(-29), night(-30)], anchor: day(0), calendar: cal)
        XCTAssertEqual(w.slotStarts.count, 30)
        XCTAssertEqual(w.bars.map(\.slot), [0])
    }

    func testSixMonthsAveragesEachWeek() {
        let entries = [night(0, asleep: 400), night(-1, asleep: 440)]
        let w = SleepHistory.window(.sixMonths, entries: entries, anchor: day(0), calendar: cal)
        XCTAssertEqual(w.slotStarts.count, 26)
        let weekOf = { (d: Date) in self.cal.dateInterval(of: .weekOfYear, for: d)!.start }
        let expected = Set([weekOf(day(0)), weekOf(day(-1))]).count
        XCTAssertEqual(w.bars.count, expected)
        if expected == 1 { XCTAssertEqual(w.bars.first?.asleepMin ?? 0, 420, accuracy: 0.001) }
    }

    func testEmptyWindowHasNoAverage() {
        XCTAssertNil(SleepHistory.window(.week, entries: [], anchor: day(0), calendar: cal).averageAsleepMin)
    }

    func testBedtimeHighlightThresholds() {
        let usual = (1...7).map { night(-$0, bedMin: 300) }.reversed()
        let cases: [(Double, String?)] = [
            (310, String(localized: "Last night you went to bed around your usual time.")),
            (340, String(localized: "Last night you went to bed \(40) min later than usual.")),
            (270, String(localized: "Last night you went to bed \(30) min earlier than usual.")),
        ]
        for (bed, expected) in cases {
            let h = SleepHighlights.bedtime(Array(usual) + [night(0, bedMin: bed)])
            XCTAssertEqual(h?.sentence, expected, "bed at \(bed)")
        }
    }

    func testBedtimeHighlightCarriesTheFiguresItCompares() {
        let usual = (1...7).map { night(-$0, bedMin: 300) }.reversed()
        let h = SleepHighlights.bedtime(Array(usual) + [night(0, bedMin: 340)])
        guard case .bedtime(let usualMin, let lastMin, let nights, let first, let last)? = h?.detail else {
            return XCTFail("no bedtime detail")
        }
        XCTAssertEqual(usualMin, 300, accuracy: 0.001)
        XCTAssertEqual(lastMin, 340, accuracy: 0.001)
        XCTAssertEqual(nights.count, 8)
        XCTAssertEqual(nights.last ?? 0, 340, accuracy: 0.001)
        XCTAssertEqual(first, day(-7))
        XCTAssertEqual(last, day(0))
    }

    func testBedtimeHighlightReadsAFortnight() {
        let nights = (1...20).map { night(-$0, bedMin: $0 <= 13 ? 300 : 600) }.reversed()
        guard case .bedtime(let usualMin, _, let shown, _, _)? =
                SleepHighlights.bedtime(Array(nights) + [night(0, bedMin: 300)])?.detail else {
            return XCTFail("no bedtime detail")
        }
        XCTAssertEqual(usualMin, 300, accuracy: 0.001)
        XCTAssertEqual(shown.count, 14)
    }

    func testNightsAsADailySeries() {
        let series = SleepHighlights.series([night(-1, asleep: 400), night(0, asleep: 460)], calendar: cal)
        XCTAssertEqual(series.map(\.value), [400, 460])
        XCTAssertEqual(series.last?.day, HealthHighlightMath.key(day(0), calendar: cal))
    }

    func testHighlightsNeedHistory() {
        XCTAssertNil(SleepHighlights.bedtime([night(-1), night(0)]))
    }
}
