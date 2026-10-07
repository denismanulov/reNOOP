package com.noop.analytics

import com.noop.data.V24AuxByte86Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The nightly gated mean of the WHOOP 4.0 strap-computed blood-oxygen byte (v24 `aux_byte_86`).
 *
 * WHOOP 4.0 twin of [Spo2CandidateNightlyTest], with the same cases: what the number counts, and what
 * it refuses to count. Android only, so there is no Swift suite to share fixtures with yet.
 */
class V24Spo2CandidateNightlyTest {

    private fun session(start: Long, durSec: Long) = DetectedSleep(
        start = start, end = start + durSec, efficiency = 0.9,
        stages = emptyList(), restingHR = 50, avgHRV = 60.0,
    )

    private fun s(ts: Long, byte: Int) = V24AuxByte86Sample(ts, byte)

    @Test fun meanAndSampleCountOverTheSession() {
        val r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            listOf(session(1000, 600)), listOf(s(1100, 94), s(1200, 96), s(1300, 95)))
        assertEquals(95, r?.first)
        assertEquals(3, r?.second)
    }

    /**
     * The codes seen on real straps (8, 16, 40, 128, 168) and the out-of-range results (1..3) are not
     * low blood oxygen. Averaging them in would give a number that is not a percentage of anything.
     */
    @Test fun statusCodesAreExcluded() {
        val r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            listOf(session(1000, 600)),
            listOf(s(1100, 94), s(1110, 8), s(1120, 16), s(1130, 40), s(1140, 128), s(1150, 168),
                s(1160, 3), s(1300, 96)))
        assertEquals(95, r?.first)
        assertEquals(2, r?.second)
    }

    @Test fun readingsOutsideTheSessionAreExcluded() {
        val r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            listOf(session(1000, 600)), listOf(s(500, 99), s(1100, 94), s(5000, 88)))
        assertEquals(94, r?.first)
        assertEquals(1, r?.second)
    }

    @Test fun nullWhenNothingInBandFallsInsideASession() {
        assertNull(AnalyticsEngine.nightlyV24Spo2CandidateMean(
            listOf(session(1000, 600)), listOf(s(1100, 16), s(1200, 168), s(9000, 95))))
        assertNull(AnalyticsEngine.nightlyV24Spo2CandidateMean(emptyList(), listOf(s(1100, 95))))
        assertNull(AnalyticsEngine.nightlyV24Spo2CandidateMean(listOf(session(1000, 600)), emptyList()))
    }

    @Test fun meanRoundsRatherThanFloors() {
        val r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            listOf(session(1000, 600)), listOf(s(1100, 95), s(1200, 96)))
        assertEquals(96, r?.first)
    }

    @Test fun bandBoundariesMatchTheDecoderGate() {
        val r = AnalyticsEngine.nightlyV24Spo2CandidateMean(
            listOf(session(1000, 600)), listOf(s(1100, 69), s(1200, 70), s(1300, 100), s(1400, 101)))
        assertEquals(85, r?.first)
        assertEquals(2, r?.second)
    }
}
