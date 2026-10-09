package com.noop.ui.live

import com.noop.data.HrBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The pure half of the Heart Rate page: hourly folding, the chart frame, the beat and the problem order. */
class HeartRateLogicTest {

    private val zone = ZoneId.of("UTC")
    private val dayStart = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toEpochSecond()

    private fun bucket(hour: Int, minute: Int, avg: Double, min: Double, max: Double) =
        HrBucket(dayStart + hour * 3600L + minute * 60L, avg, min, max)

    @Test fun foldsBucketsIntoClockHoursKeepingTrueExtremes() {
        val hours = HeartRateDay.hours(
            listOf(
                bucket(7, 0, 60.0, 55.0, 70.0),
                bucket(7, 5, 80.0, 62.0, 120.0),
                bucket(9, 30, 70.0, 66.0, 74.0),
            ),
            dayStart,
            zone,
        )
        assertEquals(listOf(HeartRateHour(7, 55.0, 120.0), HeartRateHour(9, 66.0, 74.0)), hours)
        val day = HeartRateDay(hours, resting = 52, last = null)
        assertEquals(55, day.low)
        assertEquals(120, day.high)
    }

    @Test fun dropsBucketsBeforeTheDayStarts() {
        val hours = HeartRateDay.hours(
            listOf(HrBucket(dayStart - 300, 60.0, 50.0, 70.0), bucket(0, 5, 58.0, 54.0, 61.0)),
            dayStart,
            zone,
        )
        assertEquals(listOf(HeartRateHour(0, 54.0, 61.0)), hours)
    }

    @Test fun lastReadingPrefersARecentSampleElseTheNewestBucketEnd() {
        assertEquals(HeartRateReading(64, 1000L), HeartRateDay.lastReading(64, 1000L, emptyList(), 2000L))
        val b = bucket(8, 0, 71.6, 60.0, 80.0)
        assertEquals(HeartRateReading(72, b.bucket + 300), HeartRateDay.lastReading(null, null, listOf(b), b.bucket + 1000))
        // Never later than now.
        assertEquals(HeartRateReading(72, b.bucket + 100), HeartRateDay.lastReading(null, null, listOf(b), b.bucket + 100))
        assertNull(HeartRateDay.lastReading(null, null, emptyList(), 0L))
    }

    @Test fun yDomainFramesTheReadingsNotZero() {
        val d = heartRateYDomain(listOf(HeartRateHour(1, 52.0, 141.0)))
        assertEquals(30.0, d.start, 0.0)
        assertEquals(170.0, d.endInclusive, 0.0)
        assertEquals(30.0..130.0, heartRateYDomain(emptyList()))
        val ticks = heartRateYTicks(d)
        assert(ticks.size in 1..3) { "ticks $ticks" }
        assert(ticks.all { it % 20 == 0 && it >= 30 && it <= 170 }) { "ticks $ticks" }
    }

    @Test fun beatClockRestsAtZeroAndPeaksMidBeat() {
        val clock = HeartBeatClock(0L)
        clock.retime(60, 0L)
        assertEquals(0.0, clock.pulse(0L), 1e-9)
        assertEquals(1.0, clock.pulse(500L), 1e-9)
        // A new rate re-anchors without a jump.
        val before = clock.pulse(250L)
        clock.retime(120, 250L)
        assertEquals(before, clock.pulse(250L), 1e-9)
    }

    private val ok = HeartRateLink(
        activeIsOura = false, ringStreaming = false, connected = true, bonded = true, encryptedBond = true,
        worn = true, lastSyncError = null, batteryPct = 80.0, charging = false,
    )

    @Test fun problemOrderMatchesIos() {
        assertNull(heartRateProblem(ok))
        assertEquals(HeartRateProblem.NotConnected, heartRateProblem(ok.copy(connected = false, bonded = false)))
        assertEquals(HeartRateProblem.Connecting, heartRateProblem(ok.copy(bonded = false)))
        assertEquals(HeartRateProblem.NotFullyPaired, heartRateProblem(ok.copy(encryptedBond = false, worn = false)))
        assertEquals(HeartRateProblem.OffWrist, heartRateProblem(ok.copy(worn = false, lastSyncError = "x")))
        assertEquals(HeartRateProblem.SyncError, heartRateProblem(ok.copy(lastSyncError = "stalled", batteryPct = 5.0)))
        assertEquals(HeartRateProblem.LowBattery, heartRateProblem(ok.copy(batteryPct = 15.0)))
        assertNull(heartRateProblem(ok.copy(batteryPct = 15.0, charging = true)))
        assertNull(heartRateProblem(ok.copy(lastSyncError = "")))
    }

    @Test fun aRingReadsItsOwnLink() {
        assertEquals(HeartRateProblem.RingLink, heartRateProblem(ok.copy(activeIsOura = true, ringStreaming = false)))
        assertNull(heartRateProblem(ok.copy(activeIsOura = true, ringStreaming = true, connected = false)))
    }
}
