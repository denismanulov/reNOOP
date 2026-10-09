package com.noop.analytics

import com.noop.data.MemoryKeyValuePrefs
import com.noop.data.StepCalibrationStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-day step divisor inside the scoring pass: what rides in the day cache key, which profile
 * `analyzeDay` is handed, and which divisor a day cycle is scaled by. With WHOOP 4.0 step
 * auto-calibration off, all three are the manual divisor, exactly as before the feature existed.
 */
class StepDivisorScoringTest {

    private val window = listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05")
    private val today = "2026-10-05"
    private val manual = 1.26f.toDouble()

    /** A day's cache key, built the way `IntelligenceEngine.rrAwareDayCacheKey` builds it. */
    private fun key(day: String, factors: StepCalibrationStore.Snapshot): String = AnalyzeRecentDayCache.cacheKey(
        owner = "my-whoop", hrCount = 86_400, hrMaxTs = 1_790_000_000L, skinAnchorRaw = 772.0,
        streams = AnalyzeRecentDayCache.streamsWitness("s4|z3600:1790000000|rr5=false", false, factors.factor(day)),
        hrvWindowDetail = false,
    )

    private fun keys(factors: StepCalibrationStore.Snapshot) = window.associateWith { key(it, factors) }

    private fun prefs(enabled: Boolean) = MemoryKeyValuePrefs().also { StepCalibrationStore.setEnabled(it, enabled) }

    // MARK: - The day cache key

    @Test
    fun aNewMeasurementReScoresOnlyItsOwnDay() {
        val prefs = prefs(enabled = true)
        // Two earlier days carry their own learned factors, so the window is not uniform to start with.
        StepCalibrationStore.record(prefs, day = "2026-10-02", steps = 100.0, ticks = 130.0)
        StepCalibrationStore.record(prefs, day = "2026-10-04", steps = 80.0, ticks = 96.0)
        val before = keys(StepCalibrationStore.snapshot(manual, today, prefs))

        StepCalibrationStore.record(prefs, day = today, steps = 60.0, ticks = 66.0)
        val after = keys(StepCalibrationStore.snapshot(manual, today, prefs))

        for (day in window) {
            if (day == today) {
                assertNotEquals("the measured day must be re-scored", before.getValue(day), after.getValue(day))
                assertEquals("stepDiv", AnalyzeRecentDayCache.missReason(before.getValue(day), after.getValue(day)))
            } else {
                assertEquals("$day was not measured and must be reused", before.getValue(day), after.getValue(day))
            }
        }
    }

    /** Moving to a new day freezes the old one at the factor it already had: no key moves at midnight. */
    @Test
    fun aDayRolloverWithoutAMeasurementMovesNoKey() {
        val prefs = prefs(enabled = true)
        StepCalibrationStore.record(prefs, day = "2026-10-03", steps = 100.0, ticks = 130.0)
        StepCalibrationStore.record(prefs, day = "2026-10-04", steps = 80.0, ticks = 96.0)
        val evening = keys(StepCalibrationStore.snapshot(manual, "2026-10-04", prefs))
        val morning = keys(StepCalibrationStore.snapshot(manual, "2026-10-05", prefs))
        assertEquals(evening, morning)
    }

    /**
     * Off: the key of every day is the same whether or not anything was ever learned, and it is the key
     * of the manual divisor. A measurement stored while off moves nothing.
     */
    @Test
    fun offKeysEveryDayOnTheManualDivisorWhateverWasLearned() {
        val plain = keys(StepCalibrationStore.Snapshot(state = null, manual = manual))
        val prefs = prefs(enabled = false)
        assertEquals(plain, keys(StepCalibrationStore.snapshot(manual, today, prefs)))
        StepCalibrationStore.record(prefs, day = today, steps = 60.0, ticks = 66.0)
        StepCalibrationStore.record(prefs, day = "2026-10-03", steps = 100.0, ticks = 130.0)
        assertEquals(plain, keys(StepCalibrationStore.snapshot(manual, today, prefs)))
        for (day in window) assertTrue(plain.getValue(day).endsWith("|stepDiv=${manual.toRawBits()}"))
    }

    @Test
    fun aMovedDivisorIsNamedApartFromTheAliasAndTheStreams() {
        fun third(streams: String, alias: Boolean, divisor: Double) =
            "my-whoop|100:200:nil:s|" + AnalyzeRecentDayCache.streamsWitness(streams, alias, divisor)
        val base = third("a", true, 1.0)
        assertEquals("none", AnalyzeRecentDayCache.missReason(base, base))
        assertEquals("stepDiv", AnalyzeRecentDayCache.missReason(base, third("a", true, 1.3)))
        assertEquals("rrAlias5", AnalyzeRecentDayCache.missReason(base, third("a", false, 1.0)))
        assertEquals("streams", AnalyzeRecentDayCache.missReason(base, third("b", true, 1.0)))
        // Rows landed and a measurement arrived in the same pass: the rows are the cause to chase first.
        assertEquals("streams", AnalyzeRecentDayCache.missReason(base, third("b", true, 1.3)))
        assertEquals("rrAlias5", AnalyzeRecentDayCache.missReason(base, third("a", false, 1.3)))
    }

    // MARK: - The profile analyzeDay is handed

    @Test
    fun offHandsAnalyzeDayTheVeryProfileThePassWasGiven() {
        val profile = UserProfile(weightKg = 81.0, age = 41.0, sex = "male", stepTicksPerStep = manual)
        val prefs = prefs(enabled = false)
        StepCalibrationStore.record(prefs, day = today, steps = 60.0, ticks = 66.0)
        val off = StepCalibrationStore.snapshot(manual, today, prefs)
        for (day in window) assertSame(profile, IntelligenceEngine.dayProfile(profile, day, off))
        // The same when no resolver is wired at all (tests, pure-JVM callers).
        val none = StepCalibrationStore.Snapshot(state = null, manual = profile.stepTicksPerStep)
        for (day in window) assertSame(profile, IntelligenceEngine.dayProfile(profile, day, none))
    }

    @Test
    fun onHandsAnalyzeDayTheDaysOwnDivisorAndNothingElseChanges() {
        val profile = UserProfile(weightKg = 81.0, age = 41.0, sex = "male", stepTicksPerStep = manual)
        val prefs = prefs(enabled = true)
        StepCalibrationStore.record(prefs, day = "2026-10-03", steps = 100.0, ticks = 130.0)
        val on = StepCalibrationStore.snapshot(manual, today, prefs)
        assertSame("nothing learned before 10-03", profile, IntelligenceEngine.dayProfile(profile, "2026-10-02", on))
        val learned = IntelligenceEngine.dayProfile(profile, "2026-10-03", on)
        assertEquals(1.3, learned.stepTicksPerStep, 1e-12)
        assertEquals(profile, learned.copy(stepTicksPerStep = manual))
    }

    // MARK: - The day cycle's divisor

    @Test
    fun aCycleIsScaledByItsWakeDaysDivisorOrTheManualOne() {
        assertEquals(manual, PhysiologicalStepCycleEngine.cycleTicksPerStep("2026-10-03", manual, null), 0.0)
        val prefs = prefs(enabled = true)
        StepCalibrationStore.record(prefs, day = "2026-10-03", steps = 100.0, ticks = 130.0)
        val on = StepCalibrationStore.snapshot(manual, today, prefs)
        assertEquals(1.3, PhysiologicalStepCycleEngine.cycleTicksPerStep("2026-10-03", manual, on), 1e-12)
        assertEquals(manual, PhysiologicalStepCycleEngine.cycleTicksPerStep("2026-10-01", manual, on), 0.0)
        StepCalibrationStore.setEnabled(prefs, false)
        val off = StepCalibrationStore.snapshot(manual, today, prefs)
        for (day in window) assertEquals(manual, PhysiologicalStepCycleEngine.cycleTicksPerStep(day, manual, off), 0.0)
        // 66 ticks for 60 steps, the on-strap check in the Swift commit: 66 / 1.1 is 60.
        assertEquals(60, PhysiologicalStepCycleEngine.scaledCycleSteps(66, 1.1))
        assertEquals(132, PhysiologicalStepCycleEngine.scaledCycleSteps(66, 0.3))   // floor 0.5
    }

    // MARK: - One resolver for every pass

    /**
     * Every production scoring pass must be given the same divisor resolver. A pass without it would
     * score every day on the manual divisor, the next pass with it would move the learned days back,
     * and each would invalidate the other's cached days.
     */
    @Test
    fun everyScoringCallSiteIsGivenTheResolver() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/noop").takeIf(File::isDirectory) ?: File(it, "src/main/java/com/noop") }
            .first { it.isDirectory }
        var calls = 0
        for (path in listOf("ui/AppViewModel.kt", "ble/WhoopBleClient.kt")) {
            val source = File(root, path).readText()
            val opener = Regex("""IntelligenceEngine\.(analyzeRecent|runEffortRescoreIfNeeded)\(\n""")
            for (match in opener.findAll(source)) {
                // The call's own argument list: up to the first line that closes it at its indentation.
                val indent = source.substring(source.lastIndexOf('\n', match.range.first) + 1, match.range.first)
                    .takeWhile { it == ' ' }
                val end = source.indexOf("\n$indent)", match.range.last)
                assertTrue("unterminated call in $path", end > 0)
                val arguments = source.substring(match.range.last, end)
                assertTrue(
                    "a scoring pass in $path is not given NoopPrefs.stepDivisors",
                    Regex("""stepDivisors = NoopPrefs\.stepDivisors\((appContext|context)\),""").containsMatchIn(arguments),
                )
                calls += 1
            }
        }
        assertEquals("the four production call sites", 4, calls)
    }
}
