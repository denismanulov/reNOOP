package com.noop.ui.live

import com.noop.data.HrBucket
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

// MARK: - Heart Rate (twin of iOS LiveView's LiveDay / LiveBeatClock / LiveProblemLine)
//
// The pure half of the Heart Rate page: today's heart rate folded into one min–max bar per clock hour,
// the chart's frame, the heart's beat phase, and which one problem (if any) the page names. Kept free of
// Compose so the JVM tests pin it.

/** One clock hour of today's heart rate: the lowest and highest sample in it. */
internal data class HeartRateHour(val hour: Int, val low: Double, val high: Double)

/** The latest stored reading: what the big figure shows when nothing is streaming. */
internal data class HeartRateReading(val bpm: Int, val epochSec: Long)

/** Today's heart rate, read once per refresh (iOS `LiveDay`). */
internal data class HeartRateDay(
    val hours: List<HeartRateHour>,
    val resting: Int?,
    val last: HeartRateReading?,
) {
    val low: Int? get() = hours.minOfOrNull { it.low }?.roundToInt()
    val high: Int? get() = hours.maxOfOrNull { it.high }?.roundToInt()

    companion object {
        /**
         * One bar per clock hour from five-minute buckets of `[start of today, now]`. Each bucket carries
         * its own min and max, so folding them keeps the day's true extremes without the raw ~1 Hz rows.
         * Buckets before [dayStartSec] are dropped (iOS `LiveDay.hours`).
         */
        fun hours(buckets: List<HrBucket>, dayStartSec: Long, zone: ZoneId): List<HeartRateHour> {
            val byHour = sortedMapOf<Int, Pair<Double, Double>>()
            for (b in buckets) {
                if (b.bucket < dayStartSec) continue
                val h = Instant.ofEpochSecond(b.bucket).atZone(zone).hour
                val cur = byHour[h]
                byHour[h] = Pair(
                    minOf(cur?.first ?: b.minBpm, b.minBpm),
                    maxOf(cur?.second ?: b.maxBpm, b.maxBpm),
                )
            }
            return byHour.map { (h, v) -> HeartRateHour(h, v.first, v.second) }
        }

        /**
         * The newest reading: a raw sample from the last hour, else the day's newest five-minute bucket
         * (stamped at its end, never later than [nowSec]).
         */
        fun lastReading(recentBpm: Int?, recentTs: Long?, buckets: List<HrBucket>, nowSec: Long): HeartRateReading? {
            if (recentBpm != null && recentTs != null) return HeartRateReading(recentBpm, recentTs)
            val b = buckets.maxByOrNull { it.bucket } ?: return null
            return HeartRateReading(b.avgBpm.roundToInt(), minOf(b.bucket + 300, nowSec))
        }
    }
}

/** The chart's y range: Health frames a heart-rate day around its readings, not from zero. */
internal fun heartRateYDomain(hours: List<HeartRateHour>): ClosedFloatingPointRange<Double> {
    val lo = hours.minOfOrNull { it.low } ?: 40.0
    val hi = hours.maxOfOrNull { it.high } ?: 120.0
    return (floor(lo / 20) * 20 - 10)..(ceil(hi / 20) * 20 + 10)
}

/** The y-axis values (about three) inside [domain], on multiples of 20 as Health's trailing axis has them. */
internal fun heartRateYTicks(domain: ClosedFloatingPointRange<Double>): List<Int> {
    val start = ceil(domain.start / 20).toInt() * 20
    val ticks = (start..domain.endInclusive.toInt() step 20).toList()
    // Keep three at most, evenly picked, so a wide day does not crowd the axis.
    if (ticks.size <= 3) return ticks
    val stepEvery = ceil(ticks.size / 3.0).toInt()
    return ticks.filterIndexed { i, _ -> i % stepEvery == 0 }
}

/**
 * The heart's beat phase (iOS `LiveBeatClock`). A new rate re-anchors the phase where it stands, so the
 * beat speeds up or slows down without jumping mid-stroke.
 */
internal class HeartBeatClock(nowMs: Long = 0L) {
    private var anchorMs = nowMs
    private var anchorPhase = 0.0
    private var beatsPerSecond = 1.0

    private fun phase(atMs: Long): Double = anchorPhase + maxOf(0L, atMs - anchorMs) / 1000.0 * beatsPerSecond

    fun retime(bpm: Int?, atMs: Long) {
        anchorPhase = phase(atMs) % 1.0
        anchorMs = atMs
        if (bpm != null && bpm > 0) beatsPerSecond = bpm / 60.0
    }

    /** One smooth swell and release per beat: 0 at rest, 1 at the peak. */
    fun pulse(atMs: Long): Double {
        val f = phase(atMs) % 1.0
        return 0.5 - 0.5 * cos(2 * Math.PI * f)
    }
}

/** What keeps the heart rate from being live, in the order the page asks (iOS `LiveProblemLine.problem`). */
internal enum class HeartRateProblem { RingLink, NotConnected, Connecting, NotFullyPaired, OffWrist, SyncError, LowBattery }

/** The inputs [heartRateProblem] reads, so the order is testable without a LiveState. */
internal data class HeartRateLink(
    val activeIsOura: Boolean,
    val ringStreaming: Boolean,
    val connected: Boolean,
    val bonded: Boolean,
    val encryptedBond: Boolean,
    val worn: Boolean,
    val lastSyncError: String?,
    val batteryPct: Double?,
    val charging: Boolean?,
)

/** The one problem the page names, or null when the heart rate is live and nothing is wrong. */
internal fun heartRateProblem(l: HeartRateLink): HeartRateProblem? = when {
    // A ring reads its OWN link phase (#2305): `connected` is whichever source last wrote it.
    l.activeIsOura -> if (l.ringStreaming) null else HeartRateProblem.RingLink
    !l.connected -> HeartRateProblem.NotConnected
    !l.bonded -> HeartRateProblem.Connecting
    !l.encryptedBond -> HeartRateProblem.NotFullyPaired
    !l.worn -> HeartRateProblem.OffWrist
    !l.lastSyncError.isNullOrEmpty() -> HeartRateProblem.SyncError
    l.batteryPct != null && l.batteryPct <= 15 && l.charging != true -> HeartRateProblem.LowBattery
    else -> null
}
