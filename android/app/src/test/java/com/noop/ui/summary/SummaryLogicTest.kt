package com.noop.ui.summary

import com.noop.R
import com.noop.analytics.ReadinessEngine
import com.noop.analytics.ReadinessEngine.Copy
import com.noop.analytics.ReadinessEngine.Evidence
import com.noop.analytics.ReadinessEngine.Flag
import com.noop.analytics.ReadinessEngine.Level
import com.noop.data.DailyMetric
import com.noop.ui.AppToday
import com.noop.ui.KeyMetric
import com.noop.ui.UnitSystem
import com.noop.ui.m3.ringFraction
import com.noop.ui.metric.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Pins the Summary's pure decisions: pins, tiles, layout, Charge, days, stamps, readings, highlights, sync. */
class SummaryLogicTest {

    private fun row(day: String, block: DailyMetric.() -> DailyMetric = { this }) =
        DailyMetric(deviceId = "my-whoop", day = day).block()

    // MARK: Pinned list

    @Test fun pinnedDropsRingsAndTiles() {
        val enabled = KeyMetric.defaultOrder
        val pinned = SummaryPins.pinned(enabled, SummaryTilePrefs.defaults)
        // iOS default after rings and the Steps / Calories tiles: HRV, Resting HR, Blood Oxygen, Respiratory, Weight.
        assertEquals(
            listOf(KeyMetric.HRV, KeyMetric.RESTING_HR, KeyMetric.BLOOD_OXYGEN, KeyMetric.RESPIRATORY, KeyMetric.WEIGHT),
            pinned,
        )
    }

    @Test fun unpinnedIsTheRestInCatalogueOrder() {
        val enabled = listOf(KeyMetric.CHARGE, KeyMetric.WEIGHT, KeyMetric.HRV)
        assertEquals(
            listOf(KeyMetric.RESTING_HR, KeyMetric.BLOOD_OXYGEN, KeyMetric.RESPIRATORY, KeyMetric.SKIN_TEMP),
            SummaryPins.unpinned(enabled, SummaryTilePrefs.defaults),
        )
    }

    @Test fun storedKeepsRingAndTileEntriesAndTheNewOrder() {
        val enabled = KeyMetric.defaultOrder
        val stored = SummaryPins.stored(enabled, SummaryTilePrefs.defaults, listOf(KeyMetric.WEIGHT, KeyMetric.HRV))
        assertEquals(
            listOf(
                KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST, KeyMetric.STEPS, KeyMetric.CALORIES,
                KeyMetric.WEIGHT, KeyMetric.HRV,
            ),
            stored,
        )
        assertEquals(listOf(KeyMetric.WEIGHT, KeyMetric.HRV), SummaryPins.pinned(stored, SummaryTilePrefs.defaults))
    }

    @Test fun storedIsNeverEmptySoItCannotDecodeBackToTheDefault() {
        val stored = SummaryPins.stored(listOf(KeyMetric.HRV), SummaryTilePrefs.defaults, emptyList())
        assertEquals(listOf(KeyMetric.CHARGE), stored)
        assertTrue(SummaryPins.pinned(stored, SummaryTilePrefs.defaults).isEmpty())
    }

    // MARK: Tiles and layout

    @Test fun tilesDecodeFallsBackToDefaults() {
        assertEquals(SummaryTilePrefs.defaults, SummaryTilePrefs.decode(null))
        assertEquals(SummaryTilePrefs.defaults, SummaryTilePrefs.decode("steps,steps"))
        assertEquals(SummaryTilePrefs.defaults, SummaryTilePrefs.decode("charge,steps"))
        assertEquals(SummaryTilePrefs.defaults, SummaryTilePrefs.decode("hrv"))
        assertEquals(listOf(KeyMetric.HRV, KeyMetric.WEIGHT), SummaryTilePrefs.decode(" hrv , weight "))
    }

    @Test fun replacingSwapsWhenTheOtherTileHoldsTheMetric() {
        assertEquals("calories,steps", SummaryTilePrefs.replacing("steps,calories", 0, KeyMetric.CALORIES))
        assertEquals("hrv,calories", SummaryTilePrefs.replacing("steps,calories", 0, KeyMetric.HRV))
        // A ring metric is not a tile choice: nothing changes.
        assertEquals("steps,calories", SummaryTilePrefs.replacing("steps,calories", 1, KeyMetric.CHARGE))
        assertEquals(KeyMetric.entries.size - 3, SummaryTilePrefs.choices.size)
    }

    @Test fun layoutRoundTripsAndDefaultsToDetailed() {
        SummaryLayout.entries.forEach { assertEquals(it, SummaryLayout.fromRaw(it.raw)) }
        assertEquals("detailed", SummaryLayout.DETAILED.raw)
        assertEquals("compact", SummaryLayout.COMPACT.raw)
        assertEquals(SummaryLayout.DETAILED, SummaryLayout.fromRaw(null))
        assertEquals(SummaryLayout.DETAILED, SummaryLayout.fromRaw("grid"))
        assertEquals("noop.summaryLayout", SummaryLayoutPrefs.KEY)
    }

    // MARK: Charge, rings, days

    @Test fun chargePrecedence() {
        val prior = row("2026-09-29") { copy(recovery = 64.0) }
        assertEquals(ChargeDisplay.Scored(71.0), ChargeDisplay.resolve(71.0, prior, 2, "2026-09-30"))
        assertEquals(ChargeDisplay.Calibrating(2), ChargeDisplay.resolve(null, prior, 2, "2026-09-30"))
        assertEquals(ChargeDisplay.Carried(64.0, "2026-09-29", stale = false), ChargeDisplay.resolve(null, prior, null, "2026-09-30"))
        assertEquals(ChargeDisplay.Carried(64.0, "2026-09-29", stale = true), ChargeDisplay.resolve(null, prior, null, "2026-10-09"))
        assertEquals(ChargeDisplay.NoData, ChargeDisplay.resolve(null, null, null, "2026-09-30"))
        assertNull(ChargeDisplay.Calibrating(1).pct)
        assertEquals(64.0, ChargeDisplay.Carried(64.0, "2026-09-29", false).pct!!, 0.0)
    }

    @Test fun ringFractionsClamp() {
        assertEquals(0.5f, ringFraction(50.0, 100.0)!!, 0f)
        assertEquals(1f, ringFraction(140.0, 100.0)!!, 0f)
        assertEquals(0f, ringFraction(-3.0, 100.0)!!, 0f)
        assertNull(ringFraction(null, 100.0))
    }

    @Test fun dayOffsets() {
        assertEquals(3, SummaryDay.maxDayOffset("2026-09-27", "2026-09-30"))
        assertEquals(0, SummaryDay.maxDayOffset("2026-10-02", "2026-09-30"))
        assertEquals(0, SummaryDay.maxDayOffset(null, "2026-09-30"))
        assertEquals(0, SummaryDay.maxDayOffset("garbage", "2026-09-30"))
        val anchor = LocalDate.parse("2026-09-30")
        assertEquals(6, SummaryDay.pickedDayOffset(LocalDate.parse("2026-09-24"), anchor))
        assertEquals(0, SummaryDay.pickedDayOffset(LocalDate.parse("2026-10-03"), anchor))
    }

    // MARK: Stamps

    @Test fun stamps() {
        // The today every stamp counts from is an AppToday (its own boundaries: AppTodayTest).
        val today = AppToday.resolve(null, LocalDate.parse("2026-09-30").atTime(12, 0).atZone(ZoneId.of("UTC")))
        assertEquals(SummaryStamp.Today, SummaryStamp.resolve("2026-09-30", today))
        assertEquals(SummaryStamp.Yesterday, SummaryStamp.resolve("2026-09-29", today))
        assertEquals(SummaryStamp.OnDate(LocalDate.parse("2026-09-24")), SummaryStamp.resolve("2026-09-24", today))
        assertNull(SummaryStamp.resolve(null, today))
        // On a past day a value from that very day is not stamped; a carried one is.
        assertNull(SummaryStamp.forCard("2026-09-24", 6, "2026-09-24", today))
        assertEquals(
            SummaryStamp.OnDate(LocalDate.parse("2026-09-23")),
            SummaryStamp.forCard("2026-09-23", 6, "2026-09-24", today),
        )
        assertEquals(SummaryStamp.Today, SummaryStamp.forCard("2026-09-30", 0, "2026-09-30", today))
    }

    // MARK: Readings

    private val today = "2026-09-30"

    @Test fun ringMetricsAreNotCards() {
        val inputs = SummaryMetricInputs(day = null, dayKey = today)
        listOf(KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST).forEach {
            assertNull(SummaryMetricReading.resolve(it, inputs, Locale.US))
        }
    }

    @Test fun hrvCarriesAndStampsItsOwnDay() {
        val carry = row("2026-09-29") { copy(avgHrv = 61.6) }
        val r = SummaryMetricReading.resolve(
            KeyMetric.HRV, SummaryMetricInputs(day = row(today), hrvDay = carry, dayKey = today), Locale.US,
        )!!
        assertEquals("62", r.value)
        assertEquals("ms", r.unit)
        assertEquals("2026-09-29", r.stampDay)
        assertEquals(SummaryChart.LINE, r.chart)
        assertEquals("hrv", r.routeKey)
    }

    @Test fun missingValueHasNoUnitAndNoStamp() {
        val r = SummaryMetricReading.resolve(KeyMetric.RESTING_HR, SummaryMetricInputs(day = row(today), dayKey = today), Locale.US)!!
        assertEquals(SummaryMetricReading.NO_VALUE, r.value)
        assertEquals("", r.unit)
        assertNull(r.stampDay)
        assertFalse(r.hasValue)
    }

    @Test fun bloodOxygenFallsBackToTheCaptionedStrapEstimate() {
        val r = SummaryMetricReading.resolve(
            KeyMetric.BLOOD_OXYGEN, SummaryMetricInputs(day = row(today), spo2Candidate = 95.6, dayKey = today), Locale.US,
        )!!
        assertEquals("96", r.value)
        assertEquals(SummaryCaption.STRAP_ESTIMATE, r.caption)
        assertEquals("spo2_candidate", r.seriesKey)
        assertEquals("spo2", r.routeKey)
        val measured = SummaryMetricReading.resolve(
            KeyMetric.BLOOD_OXYGEN,
            SummaryMetricInputs(day = row(today), spo2Day = row("2026-09-27") { copy(spo2Pct = 97.0) }, spo2Candidate = 95.6, dayKey = today),
            Locale.US,
        )!!
        assertEquals("97", measured.value)
        assertNull(measured.caption)
        assertEquals("2026-09-27", measured.stampDay)
    }

    @Test fun stepsPrecedenceAndRoute() {
        val measured = SummaryMetricReading.resolve(
            KeyMetric.STEPS, SummaryMetricInputs(day = row(today) { copy(steps = 8432) }, importedSteps = 9000, dayKey = today), Locale.US,
        )!!
        assertEquals("8,432", measured.value)
        assertEquals("steps" to MetricCatalog.WHOOP, measured.routeKey to measured.routeSource)
        assertEquals(SummaryChart.BARS, measured.chart)
        val imported = SummaryMetricReading.resolve(
            KeyMetric.STEPS, SummaryMetricInputs(day = row(today), importedSteps = 9000, stepsEstimate = 100.0, dayKey = today), Locale.US,
        )!!
        assertEquals("9,000", imported.value)
        assertEquals(MetricCatalog.PHONE, imported.routeSource)
        val estimate = SummaryMetricReading.resolve(
            KeyMetric.STEPS, SummaryMetricInputs(day = row(today), stepsEstimate = 1234.0, dayKey = today), Locale("ru"),
        )!!
        assertEquals("steps_est", estimate.routeKey)
        assertEquals("1 234", estimate.value)
    }

    @Test fun caloriesImportedFirst() {
        val r = SummaryMetricReading.resolve(
            KeyMetric.CALORIES,
            SummaryMetricInputs(day = row(today) { copy(activeKcalEst = 300.0) }, importedActiveKcal = 412.4, dayKey = today),
            Locale.US,
        )!!
        assertEquals("412", r.value)
        assertEquals("kcal", r.unit)
        assertEquals("active_kcal" to MetricCatalog.PHONE, r.routeKey to r.routeSource)
        val onDevice = SummaryMetricReading.resolve(
            KeyMetric.CALORIES, SummaryMetricInputs(day = row(today) { copy(activeKcalEst = 300.0) }, dayKey = today), Locale.US,
        )!!
        assertEquals("300", onDevice.value)
        assertEquals("energy_kcal" to MetricCatalog.WHOOP, onDevice.routeKey to onDevice.routeSource)
    }

    @Test fun weightFromProfileSaysSoAndHasNoDay() {
        val r = SummaryMetricReading.resolve(
            KeyMetric.WEIGHT,
            SummaryMetricInputs(day = null, profileWeightKg = 80.0, unitSystem = UnitSystem.IMPERIAL, dayKey = today),
            Locale.US,
        )!!
        assertEquals("176.4", r.value)
        assertEquals("lb", r.unit)
        assertEquals(SummaryCaption.FROM_PROFILE, r.caption)
        assertNull(r.stampDay)
        val health = SummaryMetricReading.resolve(
            KeyMetric.WEIGHT,
            SummaryMetricInputs(day = null, healthWeightKg = 77.46, healthWeightDay = "2026-09-28", dayKey = today),
            Locale("ru"),
        )!!
        assertEquals("77,5", health.value)
        assertEquals("2026-09-28", health.stampDay)
    }

    // MARK: Highlights

    private fun signal(key: String, flag: Flag, evidence: Evidence? = null) =
        ReadinessEngine.Signal(key, Copy.TODAY_READINESS_SIGNAL_HRV, Copy.TODAY_READINESS_NORMAL_RANGE, flag, evidence)

    private fun readiness(level: Level, vararg signals: ReadinessEngine.Signal) =
        ReadinessEngine.Readiness(level, Copy.TODAY_READINESS_TITLE, Copy.TODAY_READINESS_BALANCED, signals.toList(), null, null)

    @Test fun highlightsOrderedWorstFirstCappedAndNeutralDropped() {
        val r = readiness(
            Level.STRAINED,
            signal("hrv", Flag.GOOD),
            signal("rhr", Flag.NEUTRAL),
            signal("respRate", Flag.WATCH),
            signal("acwr", Flag.BAD),
            signal("monotony", Flag.WATCH),
        )
        val h = SummaryHighlight.from(r)
        assertEquals(listOf("acwr", "respRate", "monotony"), h.map { it.key })
        assertEquals(listOf("strain", "resp_rate", "strain"), h.map { it.routeKey })
    }

    @Test fun noHighlightsWithoutEnoughHistory() {
        assertTrue(SummaryHighlight.from(readiness(Level.INSUFFICIENT, signal("hrv", Flag.BAD))).isEmpty())
        assertTrue(SummaryHighlight.from(null).isEmpty())
    }

    @Test fun highlightSentences() {
        assertEquals(R.string.summary_hl_hrv_good, SummaryHighlight.sentenceRes("hrv", Flag.GOOD, null))
        assertEquals(R.string.summary_hl_rhr_bad, SummaryHighlight.sentenceRes("rhr", Flag.BAD, null))
        assertEquals(
            R.string.summary_hl_load_down,
            SummaryHighlight.sentenceRes("acwr", Flag.WATCH, Evidence.TrainingLoad(acute = 20.0, chronic = 40.0)),
        )
        assertEquals(
            R.string.summary_hl_load_fast,
            SummaryHighlight.sentenceRes("acwr", Flag.WATCH, Evidence.TrainingLoad(acute = 60.0, chronic = 40.0)),
        )
        assertEquals(R.string.summary_hl_load_steady, SummaryHighlight.sentenceRes("acwr", Flag.GOOD, null))
        assertEquals(R.string.summary_hl_monotony, SummaryHighlight.sentenceRes("monotony", Flag.WATCH, null))
        assertEquals(SummaryHighlight.SENTENCE_NORMAL, SummaryHighlight.sentenceRes("respRate", Flag.GOOD, null))
    }

    // MARK: Sync line

    @Test fun syncLine() {
        assertEquals(SummarySyncLine.Syncing, SummarySyncLine.resolve(true, 100, 200))
        assertEquals(SummarySyncLine.Hidden, SummarySyncLine.resolve(false, null, 200))
        assertEquals(SummarySyncLine.JustNow, SummarySyncLine.resolve(false, 1_000, 1_059))
        assertEquals(SummarySyncLine.JustNow, SummarySyncLine.resolve(false, 1_100, 1_000))
        assertEquals(SummarySyncLine.MinutesAgo(5), SummarySyncLine.resolve(false, 1_000, 1_000 + 5 * 60 + 30))
        assertEquals(SummarySyncLine.HoursAgo(2), SummarySyncLine.resolve(false, 0, 2 * 3_600 + 10))
        assertEquals(SummarySyncLine.DaysAgo(3), SummarySyncLine.resolve(false, 0, 3 * 86_400))
    }
}
