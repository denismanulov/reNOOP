package com.noop.ui.workouts

import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * Pins the pure rules behind the Fitness-style Workouts screens: the start-card order (iOS WorkoutQuickStart),
 * the figure a row leads with, number entry in any locale (CR-11), the manual form's verdicts, and the clocks,
 * dates and labels the screens print.
 */
class WorkoutsLogicTest {

    private val catalogue = listOf("Running", "Walking", "Hiking", "Cycling", "Strength", "Yoga", "HIIT", "Tennis", "Other")

    private fun row(
        sport: String = "Running",
        start: Long = 1_000_000L,
        end: Long = start + 3_600,
        durationS: Double? = null,
        kcal: Double? = null,
        distanceM: Double? = null,
        source: String = "manual",
        deviceId: String = "my-whoop",
    ) = WorkoutRow(
        deviceId = deviceId, startTs = start, endTs = end, sport = sport, source = source,
        durationS = durationS, energyKcal = kcal, distanceM = distanceM,
    )

    // MARK: Start cards

    @Test
    fun quickStart_freshInstall_isFitnessDefaults() {
        assertEquals(
            listOf("Walking", "Running", "Strength", "Cycling"),
            WorkoutQuickStart.sports(recents = emptyList(), history = emptyList(), catalogue = catalogue),
        )
    }

    @Test
    fun quickStart_recentsFirst_thenMostFrequent_thenDefaults_capped() {
        val history = listOf("Yoga", "Yoga", "Yoga", "Tennis", "Tennis", "HIIT", "Running")
        assertEquals(
            listOf("Cycling", "Yoga", "Tennis", "HIIT", "Running"),
            WorkoutQuickStart.sports(recents = listOf("Cycling"), history = history, catalogue = catalogue),
        )
    }

    @Test
    fun quickStart_frequencyTiesBreakByName_andCaseInsensitiveDedup() {
        val history = listOf("Tennis", "Hiking", "hiking", "Tennis")
        assertEquals(
            listOf("Hiking", "Tennis", "Walking", "Running", "Strength"),
            WorkoutQuickStart.sports(recents = emptyList(), history = history, catalogue = catalogue),
        )
        // A recent resolves to the catalogue's own spelling and is not repeated by the frequency pass.
        assertEquals(
            listOf("Tennis", "Hiking", "Walking", "Running", "Strength"),
            WorkoutQuickStart.sports(recents = listOf("tennis"), history = history, catalogue = catalogue),
        )
    }

    @Test
    fun quickStart_dropsOtherAndNamesOutsideTheCatalogue() {
        val history = listOf("Other", "Other", "Morning Walk", "TraditionalStrengthTraining", "Strength")
        val out = WorkoutQuickStart.sports(recents = listOf("Underwater basket weaving", "Other"), history = history, catalogue = catalogue)
        assertTrue("Other" !in out)
        assertTrue(out.none { it.contains("basket") })
        assertEquals(listOf("Strength", "Walking", "Running", "Cycling"), out)
        assertEquals(WorkoutQuickStart.MAX_CARDS, WorkoutQuickStart.sports(emptyList(), catalogue, catalogue).size)
    }

    // MARK: Headline figure

    @Test
    fun headline_distanceBeatsEnergyBeatsDuration() {
        assertEquals(WorkoutHeadline.Distance(5210.0), WorkoutHeadline.of(row(distanceM = 5210.0, kcal = 400.0)))
        assertEquals(WorkoutHeadline.Energy(318.0), WorkoutHeadline.of(row(kcal = 318.0)))
        assertEquals(WorkoutHeadline.Energy(318.0), WorkoutHeadline.of(row(distanceM = 0.0, kcal = 318.0)))
        assertEquals(WorkoutHeadline.Duration(2520.0), WorkoutHeadline.of(row(durationS = 2520.0, kcal = 0.0)))
        // No recorded duration: the span.
        assertEquals(WorkoutHeadline.Duration(3600.0), WorkoutHeadline.of(row()))
    }

    @Test
    fun headline_formatsInTheLocale() {
        assertEquals("5,21", WorkoutFormat.distanceValue(5210.0, imperial = false, Locale("ru")))
        assertEquals("5.21", WorkoutFormat.distanceValue(5210.0, imperial = false, Locale.US))
        assertEquals("3.24", WorkoutFormat.distanceValue(5210.0, imperial = true, Locale.US))
        assertEquals("1,234", WorkoutFormat.grouped(1234.4, Locale.US))
        assertEquals("42 min", WorkoutFormat.durationWords(2520.0, "hr", "min"))
        assertEquals("1 hr 5 min", WorkoutFormat.durationWords(3900.0, "hr", "min"))
        assertEquals("2 ч", WorkoutFormat.durationWords(7200.0, "ч", "мин"))
    }

    // MARK: Number entry (CR-11)

    @Test
    fun numbers_commaOrPointDecimal() {
        assertEquals(5.2, WorkoutNumbers.parse("5,2")!!, 1e-9)
        assertEquals(5.2, WorkoutNumbers.parse("5.2")!!, 1e-9)
        assertEquals(12.0, WorkoutNumbers.parse(" 12 ")!!, 1e-9)
        assertEquals(5.0, WorkoutNumbers.parse("5,")!!, 1e-9)
    }

    @Test
    fun numbers_groupingByEitherConvention() {
        assertEquals(1234.5, WorkoutNumbers.parse("1 234,5")!!, 1e-9)
        assertEquals(1234.5, WorkoutNumbers.parse("1 234,5")!!, 1e-9)
        assertEquals(1234.5, WorkoutNumbers.parse("1,234.5")!!, 1e-9)
        assertEquals(1234.5, WorkoutNumbers.parse("1.234,5")!!, 1e-9)
    }

    @Test
    fun numbers_rejectNonsense() {
        assertNull(WorkoutNumbers.parse(""))
        assertNull(WorkoutNumbers.parse("  "))
        assertNull(WorkoutNumbers.parse("abc"))
        assertNull(WorkoutNumbers.parse("5,2,1"))
        assertNull(WorkoutNumbers.parse("1.2.3"))
        assertNull(WorkoutNumbers.parse("NaN"))
        assertNull(WorkoutNumbers.parse("Infinity"))
    }

    @Test
    fun numbers_wholeOnlyForHeartRate() {
        assertEquals(148, WorkoutNumbers.parseWhole(" 148 "))
        assertNull(WorkoutNumbers.parseWhole("148,5"))
        assertNull(WorkoutNumbers.parseWhole("-3"))
        assertNull(WorkoutNumbers.parseWhole(""))
    }

    @Test
    fun numbers_entryUsesTheLocaleMark() {
        assertEquals("5,2", WorkoutNumbers.entry(5.2, 2, Locale("ru")))
        assertEquals("5.2", WorkoutNumbers.entry(5.20, 2, Locale.US))
        assertEquals("318", WorkoutNumbers.entry(318.0, 0, Locale.GERMANY))
        assertEquals("10", WorkoutNumbers.entry(10.0, 2, Locale.US))
        // Round trip: what the field shows parses back to the same value.
        assertEquals(3.24, WorkoutNumbers.parse(WorkoutNumbers.entry(3.24, 2, Locale.FRANCE))!!, 1e-9)
    }

    // MARK: Manual form

    private val now = 2_000_000_000L

    private fun form(
        sport: String = "Running",
        start: Long = now - 2_700,
        end: Long = now,
        hr: String = "",
        kcal: String = "",
        distance: String = "",
        imperial: Boolean = false,
    ) = ManualWorkoutForm(sport, start, end, hr, kcal, distance, imperial, now)

    @Test
    fun manualForm_commaDistanceIsStoredInMetres() {
        val f = form(distance = "5,2", kcal = "318,5", hr = "148")
        assertNull(f.problem)
        assertEquals(5200.0, f.row!!.distanceM!!, 1e-6)
        assertEquals(318.5, f.row!!.energyKcal!!, 1e-9)
        assertEquals(148, f.row!!.avgHr)
        assertEquals("manual", f.row!!.source)
    }

    @Test
    fun manualForm_milesConvertToMetres() {
        val f = form(distance = "3,1", imperial = true)
        assertEquals(3.1 / WorkoutFormat.MILES_PER_KM * 1000.0, f.row!!.distanceM!!, 1e-6)
    }

    @Test
    fun manualForm_namesWhatIsWrong() {
        assertEquals(ManualProblem.SPORT, form(sport = " ").problem)
        assertEquals(ManualProblem.END_BEFORE_START, form(start = now - 60, end = now - 120).problem)
        assertEquals(ManualProblem.END_FUTURE, form(end = now + 60).problem)
        assertEquals(ManualProblem.TOO_SHORT, form(start = now - 30).problem)
        assertEquals(ManualProblem.HEART_RATE, form(hr = "300").problem)
        assertEquals(ManualProblem.HEART_RATE, form(hr = "148,5").problem)
        assertEquals(ManualProblem.CALORIES, form(kcal = "25 000").problem)
        assertEquals(ManualProblem.CALORIES, form(kcal = "lots").problem)
        assertEquals(ManualProblem.DISTANCE, form(distance = "1 500").problem)
        assertEquals(ManualProblem.CHECK, form(start = now - 25 * 3_600).problem)
    }

    // MARK: Clocks, dates, labels

    @Test
    fun clocks() {
        assertEquals("12:04", WorkoutFormat.clock(724.0))
        assertEquals("55:42", WorkoutFormat.clock(3342.0))
        assertEquals("1:00:00", WorkoutFormat.clock(3600.0))
        assertEquals("0:00", WorkoutFormat.clock(-5.0))
        assertEquals("04:13.42", WorkoutFormat.stopwatch(253.42, hundredths = true))
        assertEquals("04:13,42", WorkoutFormat.stopwatch(253.42, hundredths = true, decimalMark = ','))
        assertEquals("04:13", WorkoutFormat.stopwatch(253.42, hundredths = false))
        assertEquals("1:04:13.00", WorkoutFormat.stopwatch(3853.0, hundredths = true))
        assertEquals("5:38", WorkoutFormat.pace(338.0, imperial = false))
        assertNull(WorkoutFormat.pace(null, imperial = false))
        assertEquals("0:30", WorkoutFormat.phaseClock(30))
    }

    @Test
    fun dayLabel_todayYesterdayOrDate() {
        val today = LocalDate.of(2026, 9, 30)
        val noon = today.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals(WorkoutDay.Today, WorkoutDay.of(noon, today, ZoneOffset.UTC))
        assertEquals(WorkoutDay.Yesterday, WorkoutDay.of(noon - 86_400, today, ZoneOffset.UTC))
        assertEquals(WorkoutDay.Date(LocalDate.of(2026, 9, 26)), WorkoutDay.of(noon - 4 * 86_400, today, ZoneOffset.UTC))
    }

    @Test
    fun months_newestFirst_titlesStandalone() {
        val sep = LocalDate.of(2026, 9, 10).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val aug = LocalDate.of(2026, 8, 31).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val months = workoutMonths(listOf(row(start = aug), row(start = sep), row(start = sep + 86_400)), ZoneOffset.UTC)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8)), months.map { it.first })
        assertEquals(listOf(sep + 86_400, sep), months.first().second.map { it.startTs })
        assertEquals("September 2026", workoutMonthTitle(YearMonth.of(2026, 9), Locale.US))
        assertEquals("Сентябрь 2026", workoutMonthTitle(YearMonth.of(2026, 9), Locale("ru")))
    }

    @Test
    fun filters_mostFrequentFirst() {
        val rows = listOf(row(sport = "Yoga"), row(sport = "Running"), row(sport = "Running"), row(sport = "Cycling"))
        assertEquals(listOf("Running", "Cycling", "Yoga"), workoutSportFilters(rows))
    }

    @Test
    fun zoneBands_firstLastAndBetween() {
        val lower = listOf(95.0, 114.0, 133.0, 152.0, 171.0)
        val upper = listOf(114.0, 133.0, 152.0, 171.0, 190.0)
        assertEquals("<114 bpm", zoneBandLabel(0, lower, upper, "bpm"))
        assertEquals("133–152", zoneBandLabel(2, lower, upper, "bpm"))
        assertEquals("171+ bpm", zoneBandLabel(4, lower, upper, "bpm"))
    }

    @Test
    fun effortWords_followTheFillFraction() {
        assertEquals(0, effortLoadIndex(0.1))
        assertEquals(1, effortLoadIndex(0.40))
        assertEquals(2, effortLoadIndex(0.60))
        assertEquals(3, effortLoadIndex(0.80))
        assertEquals(4, effortLoadIndex(0.95))
        assertEquals(4, effortLoadIndex(3.0))
    }

    @Test
    fun origin_namesWhereAWorkoutCameFrom() {
        // #53: a Health Connect session is not "Apple Health".
        assertEquals(WorkoutOrigin.HEALTH_CONNECT, WorkoutOrigin.of("health-connect", "health-connect"))
        assertEquals(WorkoutOrigin.APPLE_HEALTH, WorkoutOrigin.of("apple-health", "Apple Health"))
        assertEquals(WorkoutOrigin.WHOOP, WorkoutOrigin.of("my-whoop", "my-whoop"))
        assertEquals(WorkoutOrigin.DEVICE, WorkoutOrigin.of("my-whoop", "manual"))
        assertEquals(WorkoutOrigin.DEVICE, WorkoutOrigin.of("my-whoop-noop", "my-whoop-noop"))
        assertEquals(WorkoutOrigin.IMPORTED, WorkoutOrigin.of("lifting", "lifting"))
        assertEquals(WorkoutOrigin.IMPORTED, WorkoutOrigin.of("activity-file", "activity-file"))
    }

    @Test
    fun nowRunning_intervalsWinOverAWorkout() {
        assertEquals(NowRunning.Kind.Intervals, NowRunning.kind(workoutActive = true, intervalsInProgress = true))
        assertEquals(NowRunning.Kind.Workout, NowRunning.kind(workoutActive = true, intervalsInProgress = false))
        assertNull(NowRunning.kind(workoutActive = false, intervalsInProgress = false))
    }
}
