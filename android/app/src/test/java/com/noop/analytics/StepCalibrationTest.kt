package com.noop.analytics

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepCalibration], case for case the Swift `StepCalibrationTests`: same inputs, same expected values.
 * `StepCalibrationParityOracleTest` is the bit-level check against the Swift build.
 */
class StepCalibrationTest {

    private fun recorded(state: StepCalibration.State, day: String, steps: Double, ticks: Double) =
        StepCalibration.recorded(state, day = day, steps = steps, ticks = ticks)

    /**
     * Swift's `stride(from:through:by:)` over `Double`: each value is ONE fused multiply-add from the
     * start, not a running sum and not `from + i * by`. The fourth ratio of the octave test differs in its
     * last bit between the two spellings; the parity oracle caught it.
     */
    private fun strideThrough(from: Double, through: Double, by: Double): List<Double> {
        val out = ArrayList<Double>()
        var index = 0
        while (Math.fma(index.toDouble(), by, from) <= through) {
            out.add(Math.fma(index.toDouble(), by, from))
            index += 1
        }
        return out
    }

    @Test
    fun acceptsOnlyRatiosInsideTheBand() {
        assertTrue(StepCalibration.accepts(steps = 60.0, ticks = 74.0))        // 1.23, arm swinging
        assertTrue(StepCalibration.accepts(steps = 63.0, ticks = 90.0))        // 1.43, hand in pocket
        assertFalse(StepCalibration.accepts(steps = 60.0, ticks = 59.0))       // the counter never undercounts
        assertFalse(StepCalibration.accepts(steps = 60.0, ticks = 110.0))      // 1.83
        assertFalse(StepCalibration.accepts(steps = 20.0, ticks = 25.0))       // too few steps
        assertFalse(StepCalibration.accepts(steps = 60.0, ticks = 0.0))
    }

    @Test
    fun anOctaveErrorIsRefusedOverTheWholeObservedRange() {
        // The detector's one consequential failure doubles or halves the step count. Every ratio seen on
        // the strap (1.14 to 1.49) then lands outside the band.
        val ratios = strideThrough(from = 1.14, through = 1.49, by = 0.05)
        assertEquals(8, ratios.size)
        for (trueRatio in ratios) {
            val steps = 60.0
            val ticks = steps * trueRatio
            assertTrue(StepCalibration.accepts(steps = steps, ticks = ticks))
            assertFalse("octave high at $trueRatio", StepCalibration.accepts(steps = steps * 2, ticks = ticks))
            assertFalse("octave low at $trueRatio", StepCalibration.accepts(steps = steps / 2, ticks = ticks))
        }
    }

    @Test
    fun manualDivisorAppliesUntilSomethingIsMeasured() {
        val state = StepCalibration.advanced(StepCalibration.State(), to = "2026-10-03")
        assertEquals(1.26, StepCalibration.factor(state, day = "2026-10-03", manual = 1.26), 0.0)
        assertEquals(1.26, StepCalibration.factor(state, day = "2026-09-30", manual = 1.26), 0.0)
        assertNull(StepCalibration.longRunFactor(state))
    }

    @Test
    fun firstDayUsesItsOwnMeasurementsAsARatioOfSums() {
        var state = StepCalibration.State()
        state = recorded(state, "2026-10-03", steps = 60.0, ticks = 72.0)     // 1.20
        state = recorded(state, "2026-10-03", steps = 180.0, ticks = 252.0)   // 1.40, three times longer
        // (72 + 252) / (60 + 180), not the mean of 1.20 and 1.40.
        assertEquals(1.35, StepCalibration.factor(state, day = "2026-10-03", manual = 1.0), 1e-9)
        assertEquals(2, state.accepted)
    }

    @Test
    fun aRefusedMeasurementChangesNothing() {
        val start = recorded(StepCalibration.State(), "2026-10-03", steps = 60.0, ticks = 75.0)
        assertEquals(start, recorded(start, "2026-10-03", steps = 120.0, ticks = 75.0))
    }

    @Test
    fun leavingADayFreezesItAndSeedsTheLongRunFactor() {
        var state = recorded(StepCalibration.State(), "2026-10-03", steps = 100.0, ticks = 125.0)
        state = StepCalibration.advanced(state, to = "2026-10-04")
        assertEquals(1.25, state.frozen["2026-10-03"] ?: 0.0, 1e-9)
        // Zero-seeded sums: after one measured day the ratio is that day's, with no pull toward a seed.
        assertEquals(1.25, StepCalibration.longRunFactor(state) ?: 0.0, 1e-9)
        // A day with no measurement of its own runs on the long-run factor.
        assertEquals(1.25, StepCalibration.factor(state, day = "2026-10-04", manual = 1.0), 1e-9)
    }

    @Test
    fun aDaysOwnEvidenceOutweighsTheLongRunFactor() {
        var state = recorded(StepCalibration.State(), "2026-10-03", steps = 200.0, ticks = 250.0)      // 1.25
        // Next day: hands in pockets, four measurements of 55 steps at 1.49.
        repeat(4) { state = recorded(state, "2026-10-04", steps = 55.0, ticks = 55 * 1.49) }
        val today = StepCalibration.factor(state, day = "2026-10-04", manual = 1.0)
        // (220 * 1.49 + 120 * 1.25) / (220 + 120)
        assertEquals((220 * 1.49 + 120 * 1.25) / 340, today, 1e-9)
        assertTrue(today > 1.40)
        // History is not rewritten by it.
        assertEquals(1.25, StepCalibration.factor(state, day = "2026-10-03", manual = 1.0), 1e-9)
    }

    @Test
    fun longRunFactorIsAnExponentialAverageOfDaySums() {
        var state = StepCalibration.State()
        state = recorded(state, "2026-10-01", steps = 100.0, ticks = 120.0)
        state = recorded(state, "2026-10-02", steps = 100.0, ticks = 140.0)
        state = StepCalibration.advanced(state, to = "2026-10-03")
        // ticks: 0.8 * (0.2 * 120) + 0.2 * 140 ; steps: 0.8 * (0.2 * 100) + 0.2 * 100
        assertEquals((0.8 * 24 + 28) / (0.8 * 20 + 20), StepCalibration.longRunFactor(state) ?: 0.0, 1e-9)
    }

    @Test
    fun anUnmeasuredDayStillFreezesAtTheLongRunFactor() {
        var state = recorded(StepCalibration.State(), "2026-10-01", steps = 100.0, ticks = 130.0)
        state = StepCalibration.advanced(state, to = "2026-10-02")
        state = StepCalibration.advanced(state, to = "2026-10-03")
        state = recorded(state, "2026-10-03", steps = 100.0, ticks = 110.0)
        assertEquals(1.30, state.frozen["2026-10-02"] ?: 0.0, 1e-9)
        assertEquals(1.30, StepCalibration.factor(state, day = "2026-10-02", manual = 1.0), 1e-9)
    }

    @Test
    fun aMeasurementForADayAlreadyLeftIsDropped() {
        var state = recorded(StepCalibration.State(), "2026-10-03", steps = 100.0, ticks = 125.0)
        state = StepCalibration.advanced(state, to = "2026-10-04")
        assertEquals(state, recorded(state, "2026-10-03", steps = 100.0, ticks = 150.0))
    }

    @Test
    fun frozenDaysAreBounded() {
        var state = StepCalibration.State()
        for (index in 0 until StepCalibration.FROZEN_DAY_LIMIT + 30) {
            val day = String.format(Locale.ROOT, "2026-%04d", index)       // any strictly increasing key
            state = recorded(state, day, steps = 100.0, ticks = 125.0)
        }
        state = StepCalibration.advanced(state, to = "2027-0000")
        assertEquals(StepCalibration.FROZEN_DAY_LIMIT, state.frozen.size)
        assertNull(state.frozen["2026-0000"])
    }

    @Test
    fun stateSurvivesAJsonRoundTrip() {
        var state = recorded(StepCalibration.State(), "2026-10-03", steps = 100.0, ticks = 125.0)
        state = recorded(state, "2026-10-04", steps = 55.0, ticks = 80.0)
        val decoded = StepCalibration.State.fromJson(state.toJson()!!)
        assertEquals(state, decoded)
    }
}
