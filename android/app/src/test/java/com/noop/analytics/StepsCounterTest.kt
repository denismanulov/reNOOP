package com.noop.analytics

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the shared windowed step kernel [StepsCounter.stepsInWindow] (#398). The same wrap-aware
 * positive-delta math the daily total uses (see StepsAnalyticsTest), but exercised directly and
 * order-independently so a manual-workout window can reuse it. Returns the RAW motion-tick total (before
 * the caller's `stepTicksPerStep` calibration). Byte-for-byte twin of the Swift StepsCounterTests.
 */
class StepsCounterTest {

    private fun step(ts: Long, counter: Int, activityClass: Int? = null) =
        StepSample(deviceId = "my-whoop", ts = ts, counter = counter, activityClass = activityClass)

    @Test fun sumsPositiveConsecutiveDeltas() {
        // counters 100 -> 150 -> 220 => deltas 50 + 70 = 120
        assertEquals(120, StepsCounter.stepsInWindow(listOf(step(0, 100), step(60, 150), step(120, 220))))
    }

    @Test fun sortsUnorderedInput() {
        // Same three samples shuffled — the kernel sorts by ts, so the result is identical (120).
        assertEquals(120, StepsCounter.stepsInWindow(listOf(step(120, 220), step(0, 100), step(60, 150))))
    }

    @Test fun handlesU16Wraparound() {
        // 65500 -> 20 wraps: (20 - 65500) and 0xFFFF = 56; then 20 -> 80 => 60. Total 116.
        assertEquals(116, StepsCounter.stepsInWindow(listOf(step(0, 65_500), step(60, 20), step(120, 80))))
    }

    @Test fun fewerThanTwoSamplesIsNull() {
        assertNull(StepsCounter.stepsInWindow(emptyList()))
        assertNull(StepsCounter.stepsInWindow(listOf(step(0, 100))))
    }

    @Test fun noForwardMovementIsNull() {
        // Flat counter across the window => no positive delta => null (not 0).
        assertNull(StepsCounter.stepsInWindow(listOf(step(0, 500), step(60, 500), step(120, 500))))
    }

    @Test fun dropsBigGapDeltaAsBoundary() {
        // A jump >= 512 is dropped; the real 40 + 30 survive => 70.
        assertEquals(70, StepsCounter.stepsInWindow(
            listOf(step(0, 100), step(60, 140), step(120, 5_000), step(180, 5_030))))
    }

    @Test fun maxStepDeltaBoundaryIsExclusive() {
        // Exactly MAX_STEP_DELTA (512) is dropped; 511 counts.
        assertNull(StepsCounter.stepsInWindow(listOf(step(0, 0), step(128, 512))))
        assertEquals(511, StepsCounter.stepsInWindow(listOf(step(0, 0), step(128, 511))))
    }

    @Test fun rejectsPhysicallyImpossibleOneSecondSpikeButAllowsSameTicksAcrossTime() {
        // Eight ticks in a second is the ceiling (a 1 Hz record can carry two seconds' worth). Nine in one
        // second cannot be real gait; the same nine across two seconds can be a small history hole and must
        // remain recoverable.
        assertNull(StepsCounter.stepsInWindow(listOf(step(0, 100), step(1, 109))))
        assertEquals(9, StepsCounter.stepsInWindow(listOf(step(0, 100), step(2, 109))))
        assertEquals(8, StepsCounter.stepsInWindow(listOf(step(0, 100), step(1, 108))))
    }

    @Test fun bufferedReleaseAfterFlatRunCounts() {
        // A pedometer holds the first steps of a walk back, then publishes them in one record. Ten flat
        // seconds, a 12-step release, then one more step: all 13 are real.
        val flat = (0L..9L).map { step(it, 100) }
        assertEquals(13, StepsCounter.stepsInWindow(flat + listOf(step(10, 112), step(11, 113))))
    }

    @Test fun releaseIsBoundedByTheConfirmationWindow() {
        // The credit stops at 8 s x 4 ticks = 32, however long the counter was flat.
        val flat = (0L..60L).map { step(it, 100) }
        assertEquals(32, StepsCounter.stepsInWindow(flat + listOf(step(61, 132))))
        assertNull(StepsCounter.stepsInWindow(flat + listOf(step(61, 133))))
    }

    @Test fun spikeRightAfterMovementIsStillRejected() {
        // The counter moved one second ago, so there is no flat run to credit: +9 in a second is dropped.
        assertEquals(2, StepsCounter.stepsInWindow(listOf(step(0, 100), step(1, 102), step(2, 111))))
    }

    @Test fun jitteredSecondDuringSteadyWalkingCounts() {
        // Real WHOOP 4.0 walk, 2026-10-03: steady 2-3 ticks per second with one record carrying 6, where
        // record timing put two seconds' worth of ticks into one. Every tick is a real one: 26 in all.
        var counter = 100
        val samples = listOf(step(0, counter)) + listOf(2, 3, 2, 3, 3, 2, 6, 2, 3).mapIndexed { index, delta ->
            counter += delta
            step(index + 1L, counter)
        }
        assertEquals(26, StepsCounter.stepsInWindow(samples))
    }

    @Test fun classedStreamCountsOnlyWalkAndRunDeltas() {
        // Attribute each counter delta to the later sample, matching the strap's per-record class.
        // still: +10 ignored; walk: +20; run: +15; unknown: +25 ignored => 35 locomotion ticks.
        assertEquals(35, StepsCounter.stepsInWindow(listOf(
            step(0, 100, 0),
            step(10, 110, 0),
            step(20, 130, 1),
            step(30, 145, 2),
            step(40, 170, null),
        )))
    }

    @Test fun legacyUnclassedStreamKeepsCounterFallback() {
        // Rows written before activityClass existed must retain their historical estimate.
        assertEquals(70, StepsCounter.stepsInWindow(listOf(
            step(0, 100), step(10, 140), step(20, 170),
        )))
    }
}
