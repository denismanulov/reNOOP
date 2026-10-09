package com.noop.ui.sleep

import com.noop.analytics.RestScorer
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Pins the Sleep tab's pure logic: the score and its parts, the vitals and stage ranges, and the week. */
class SleepLogicTest {

    private fun row(
        day: String,
        total: Double? = 420.0,
        eff: Double? = 0.9,
        deep: Double? = 80.0,
        rem: Double? = 100.0,
        light: Double? = 240.0,
        rhr: Int? = 55,
        resp: Double? = 14.5,
        spo2: Double? = 97.0,
        dev: Double? = 0.1,
    ) = DailyMetric(
        deviceId = "my-whoop", day = day, totalSleepMin = total, efficiency = eff, deepMin = deep, remMin = rem,
        lightMin = light, restingHr = rhr, respRateBpm = resp, spo2Pct = spo2, skinTempDevC = dev,
    )

    // MARK: Score

    @Test fun wordBands() {
        assertEquals(SleepScoreWord.POOR, SleepScore.word(49))
        assertEquals(SleepScoreWord.FAIR, SleepScore.word(50))
        assertEquals(SleepScoreWord.FAIR, SleepScore.word(69))
        assertEquals(SleepScoreWord.GOOD, SleepScore.word(70))
        assertEquals(SleepScoreWord.GOOD, SleepScore.word(84))
        assertEquals(SleepScoreWord.OPTIMAL, SleepScore.word(85))
    }

    @Test fun partsAreTheCompositeWeights() {
        assertEquals(listOf(50, 20, 20, 10), SleepScorePart.entries.map { it.maxPoints })
    }

    @Test fun componentsRebuildTheComposite() {
        val d = row("2026-09-30", total = 390.0, eff = 0.86, deep = 40.0, rem = 70.0, light = 280.0)
        val c = SleepScore.components(d)!!
        val weighted = SleepScorePart.entries.sumOf { it.maxPoints * c.getValue(it) }
        assertEquals(RestScorer.restFromDaily(d)!!, weighted, 0.01)
    }

    @Test fun aNightIsReadOnItsThreeMeasuredParts() {
        val s = SleepScore.make(row("2026-09-30", total = 390.0, eff = 0.83, deep = 50.0), null)!!
        // Regularity is a neutral constant for one night: it is in the composite, never on the page.
        assertEquals(listOf(SleepScorePart.DURATION, SleepScorePart.INTERRUPTIONS, SleepScorePart.RESTORATIVE), s.parts.map { it.part })
        assertEquals(390.0 / 480.0, s.parts.first().fraction, 1e-9)
        assertEquals(480.0, SleepScore.TARGET_MIN, 1e-9)
    }

    @Test fun partLevelBands() {
        fun level(f: Double) = SleepScorePartScore(SleepScorePart.DURATION, f).level
        assertEquals(SleepScoreWord.OPTIMAL, level(1.0))
        assertEquals(SleepScoreWord.OPTIMAL, level(0.95))
        assertEquals(SleepScoreWord.GOOD, level(0.949))
        assertEquals(SleepScoreWord.GOOD, level(0.85))
        assertEquals(SleepScoreWord.FAIR, level(0.849))
        assertEquals(SleepScoreWord.FAIR, level(0.70))
        assertEquals(SleepScoreWord.POOR, level(0.699))
    }

    @Test fun verdictNamesTheBiggestLoss() {
        fun score(value: Int, vararg f: Double) =
            SleepScore(value, false, SleepScorePart.measured.mapIndexed { i, p -> SleepScorePartScore(p, f[i]) })
        // Duration loses 15 of 50; the others little.
        assertEquals(SleepVerdict.SHORT, score(78, 0.7, 0.95, 0.95).verdict)
        // Interruptions lose 8 of 20, more than duration's 5.
        assertEquals(SleepVerdict.RESTLESS, score(80, 0.9, 0.6, 0.95).verdict)
        assertEquals(SleepVerdict.SHALLOW, score(80, 0.95, 1.0, 0.4).verdict)
        // An optimal night is sound whatever its smallest loss was.
        assertEquals(SleepVerdict.SOUND, score(90, 0.95, 0.9, 0.95).verdict)
        // Nothing measured lost a point: the sentence blames nothing.
        assertEquals(SleepVerdict.SOUND, score(84, 1.0, 1.0, 1.0).verdict)
    }

    @Test fun aRealRowNeverBlamesRegularity() {
        // Everything measured is perfect; only the neutral regularity term keeps the composite off 100.
        val s = SleepScore.make(row("2026-09-30", total = 480.0, eff = 1.0, deep = 100.0, rem = 140.0), null)!!
        assertEquals(95, s.value)
        assertEquals(SleepVerdict.SOUND, s.verdict)
    }

    @Test fun importedScoreKeepsTheNightsOwnParts() {
        val s = SleepScore.make(row("2026-09-30"), importedPct = 93.4)!!
        assertTrue(s.imported)
        assertEquals(93, s.value)
        assertEquals(SleepVerdict.IMPORTED, s.verdict)
        assertEquals(SleepScorePart.measured, s.parts.map { it.part })
    }

    @Test fun noRowNoScore() {
        assertNull(SleepScore.make(null, null))
        assertNull(SleepScore.make(row("2026-09-30", total = null), null))
    }

    // MARK: Vitals

    private fun history(n: Int, rhr: Int = 55) = (1..n).map { i -> row("2026-09-%02d".format(i), rhr = rhr + (i % 3) - 1) }

    @Test fun vitalsLearnForSevenNights() {
        val rows = history(4) + row("2026-09-30")
        val v = SleepVitals.make(rows, "2026-09-30")
        assertEquals(3, v.nightsRemaining)
        assertEquals(4, v.nightsRecorded)
        assertTrue(v.readings.isEmpty())
    }

    @Test fun vitalsTypicalAndOutliers() {
        val typical = SleepVitals.make(history(10) + row("2026-09-30"), "2026-09-30")
        assertEquals(0, typical.nightsRemaining)
        assertEquals(0, typical.outliers)
        assertEquals(SleepVitals.Metric.entries.size, typical.readings.size)
        assertTrue(typical.readings.all { it.level == SleepVitals.Level.TYPICAL })
        val off = SleepVitals.make(history(10) + row("2026-09-30", rhr = 70, spo2 = 90.0), "2026-09-30")
        assertEquals(2, off.outliers)
        assertEquals(SleepVitals.Level.HIGH, off.readings.first { it.metric == SleepVitals.Metric.HEART_RATE }.level)
        assertEquals(SleepVitals.Level.LOW, off.readings.first { it.metric == SleepVitals.Metric.OXYGEN }.level)
    }

    @Test fun aNightWithNoBodyReadingsTeachesNoRange() {
        // Seven nights that recorded sleep but no vitals: nothing to learn a vitals range from.
        val bare = (1..7).map { i -> row("2026-09-%02d".format(i), rhr = null, resp = null, spo2 = null, dev = null) }
        val v = SleepVitals.make(bare + row("2026-09-30"), "2026-09-30")
        assertEquals(SleepVitals.NIGHTS_NEEDED, v.nightsRemaining)
    }

    @Test fun vitalsRangeNeverNarrowerThanResolution() {
        val (lo, hi) = SleepVitals.typicalRange(List(10) { 55.0 }, minHalfWidth = 2.0)!!
        assertEquals(53.0, lo, 1e-9)
        assertEquals(57.0, hi, 1e-9)
    }

    // MARK: Stage ranges

    @Test fun stageRangesNeedSevenNightsAndNeverCoverAwake() {
        assertTrue(SleepStageRanges.make(history(6), "2026-09-30").isEmpty())
        val ranges = SleepStageRanges.make(history(10), "2026-09-30")
        assertEquals(setOf(SleepStageRow.DEEP, SleepStageRow.REM, SleepStageRow.CORE), ranges.keys)
        // Ten identical nights of 80 min deep: the range is the floor either side of it.
        val deep = ranges.getValue(SleepStageRow.DEEP)
        assertEquals(70.0, deep.low, 1e-9)
        assertEquals(90.0, deep.high, 1e-9)
        assertEquals(SleepVitals.Level.LOW, deep.level(69.0))
        assertEquals(SleepVitals.Level.TYPICAL, deep.level(70.0))
        assertEquals(SleepVitals.Level.HIGH, deep.level(91.0))
    }

    @Test fun stageRangesReadOnlyTheNightsBeforeTheDay() {
        val rows = history(10) + row("2026-09-30", deep = 200.0)
        assertEquals(90.0, SleepStageRanges.make(rows, "2026-09-30").getValue(SleepStageRow.DEEP).high, 1e-9)
    }

    // MARK: The week and the ranges

    private val utc = ZoneOffset.UTC

    private fun entry(day: String, onsetHour: Double, asleep: Double = 420.0): SleepNightEntry {
        val d = LocalDate.parse(day)
        val onset = d.atStartOfDay(utc).toEpochSecond() + ((onsetHour - 24) * 3600).toLong()
        return SleepNightEntry(d, onset, onset + 8 * 3600, asleep)
    }

    @Test fun weekBedtimeRoundsToFiveAndNeedsThreeNightsBefore() {
        val day = LocalDate.parse("2026-09-30")
        assertNull(SleepWeek.bedtime(listOf(entry("2026-09-28", 23.0), entry("2026-09-29", 23.0), entry("2026-09-30", 23.5)), day, utc))
        val nights = listOf(
            entry("2026-09-26", 23.0), entry("2026-09-27", 23.0), entry("2026-09-28", 23.0), entry("2026-09-29", 23.0),
            entry("2026-09-30", 23.6),
        )
        val bed = SleepWeek.bedtime(nights, day, utc)!!
        assertEquals(35, bed.diffMin)
        assertEquals(5 * 60.0, bed.usualMin, 1e-9) // 23:00 is five hours into the night clock
        // The bedtime is the page's night against the nights BEFORE it, whatever came after.
        val earlier = SleepWeek.bedtime(nights, LocalDate.parse("2026-09-29"), utc)!!
        assertEquals(0, earlier.diffMin)
    }

    @Test fun weekEndsOnTheNightAndComparesTheWeekBefore() {
        val day = LocalDate.parse("2026-09-30")
        val entries = (0 until 14).map { i -> entry(day.minusDays(i.toLong()).toString(), 23.0, if (i < 7) 450.0 else 420.0) }.sortedBy { it.day }
        val w = SleepWeek.make(entries, day, utc)
        assertEquals(7, w.days.size)
        assertEquals(day, w.days.last())
        assertEquals(450.0, w.averageMin!!, 1e-9)
        assertEquals(30, w.changeMin)
        // A week back, the seven days are the 420-minute ones and there is no fuller week before them.
        val older = SleepWeek.make(entries, day.minusDays(7), utc)
        assertEquals(420.0, older.averageMin!!, 1e-9)
        assertNull(older.changeMin)
    }

    @Test fun aThinWeekHasNoAverage() {
        val day = LocalDate.parse("2026-09-30")
        val w = SleepWeek.make(listOf(entry("2026-09-29", 23.0), entry("2026-09-30", 23.0)), day, utc)
        assertNull(w.averageMin)
        assertEquals(listOf(null, null, null, null, null, 420.0, 420.0), w.asleepMin)
    }

    @Test fun weekWindowHasSevenSlotsAndSixMonthsTwentySix() {
        val today = LocalDate.parse("2026-09-30")
        val entries = listOf(entry("2026-09-29", 23.0), entry("2026-09-30", 22.5))
        val w = SleepHistory.window(SleepRange.WEEK, entries, today, utc)
        assertEquals(7, w.slotStarts.size)
        assertEquals(listOf(5, 6), w.bars.map { it.slot })
        assertEquals(5 * 60.0, w.bars.first().onsetMin, 1e-9) // 23:00 is five hours into the night clock
        assertEquals(26, SleepHistory.window(SleepRange.SIX_MONTHS, entries, today, utc).slotStarts.size)
    }

    /** The tie night of HypnogramSummaryOrderTest: awake and REM share a remainder, so the split order picks. */
    @Test fun stageSharesAddUpTo100AndBreakTiesInTheRowsOrder() {
        val s = SleepPeriodSummary(
            nights = 1, inBedMin = 400.0, asleepMin = 350.0,
            stageMin = mapOf(
                SleepStageRow.AWAKE to 50.0, SleepStageRow.CORE to 205.0,
                SleepStageRow.DEEP to 95.0, SleepStageRow.REM to 50.0,
            ),
            bedtimeOfNightMin = null, wakeOfNightMin = null,
        )
        val p = SleepStageRow.entries.associateWith { s.sharePercent(it)!! }
        assertEquals(100, p.values.sum())
        assertEquals(13, p[SleepStageRow.AWAKE])
        assertEquals(12, p[SleepStageRow.REM])
        assertEquals(51, p[SleepStageRow.CORE])
        assertEquals(24, p[SleepStageRow.DEEP])
        assertNull(s.copy(stageMin = emptyMap()).sharePercent(SleepStageRow.REM))
    }
}
