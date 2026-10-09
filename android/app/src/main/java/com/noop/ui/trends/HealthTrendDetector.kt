package com.noop.ui.trends

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// MARK: - Health trend detector (twin of iOS HealthTrendDetector)
//
// Whether a metric's recent readings have moved away from where they were, read the way Health's Trends
// read it: one span split into an earlier and a recent period ("Trending lower for 12 weeks", "18-week avg"
// against "12-week avg"), the split placed where the two periods differ most.
//
// Pure: a daily series in, one result out. Two scales are tried, weeks first (a long trend is the bigger
// story), then days. Thresholds, windows and arithmetic are the Swift detector's, byte for byte; the unit
// test's expected values are that detector's own output (the parity oracle).

/** One detected trend. */
data class HealthTrend(
    val direction: Direction,
    val unit: Unit,
    /** Every slot of the span, oldest first: a day's reading or a week's mean, null where nothing was recorded. */
    val slots: List<Double?>,
    /** How many trailing slots form the recent period ("for 5 days"); the slots before it are the baseline. */
    val recentCount: Int,
    val baselineAverage: Double,
    val recentAverage: Double,
    /** Welch's t between the two periods: how clearly they differ. Orders trends by strength. */
    val strength: Double,
    /** Whether the move is good news; null for a metric with no better direction. */
    val isImprovement: Boolean?,
) {
    enum class Direction { HIGHER, LOWER }

    /** What one slot of the span stands for. */
    enum class Unit { DAY, WEEK }

    val baselineCount: Int get() = slots.size - recentCount

    /** The baseline's length from its first reading: what its average actually covers ("14-week avg"). */
    val baselineLength: Int get() = baselineCount - slots.indexOfFirst { it != null }.let { if (it < 0) 0 else it }
}

sealed interface HealthTrendResult {
    data class Trend(val trend: HealthTrend) : HealthTrendResult
    /** Enough recent readings to judge, and no clear change. */
    data object Steady : HealthTrendResult
    /** Too few readings, or none recent enough, to say anything. */
    data object Insufficient : HealthTrendResult
}

object HealthTrendDetector {

    /** One way of reading the span: its slot, how many slots, where the recent period may start, and how
     *  many readings each period needs before a comparison means anything. */
    data class Scale(
        val unit: HealthTrend.Unit,
        val span: Int,
        val recentLengths: IntRange,
        val minRecentPoints: Int,
        val minBaselinePoints: Int,
        /** The newest slots of which at least one must hold a reading: a trend is about now. */
        val freshSlots: Int,
    )

    val weekly = Scale(HealthTrend.Unit.WEEK, span = 26, recentLengths = 4..13, minRecentPoints = 3, minBaselinePoints = 6, freshSlots = 1)
    val daily = Scale(HealthTrend.Unit.DAY, span = 28, recentLengths = 5..14, minRecentPoints = 4, minBaselinePoints = 10, freshSlots = 3)

    /** The |t| a split must reach; every candidate split is a test of its own, so well above 1.96. */
    const val MIN_STRENGTH = 3.0
    /** The smallest change worth a card, in standard deviations of the baseline. */
    const val MIN_EFFECT = 0.5
    /** The share of recent readings that must sit on the trend's side of the baseline average. */
    const val MIN_AGREEMENT = 0.7

    /** The metric's trend as of [today] ("yyyy-MM-dd"); [series] is one value per "yyyy-MM-dd" day. */
    fun detect(series: List<Pair<String, Double>>, today: String, higherIsBetter: Boolean?): HealthTrendResult {
        val todayIndex = dayNumber(today) ?: return HealthTrendResult.Insufficient
        val byDay = HashMap<Int, Double>()
        for ((day, value) in series) dayNumber(day)?.let { byDay[it] = value }
        var judged = false
        for (scale in listOf(weekly, daily)) {
            when (val r = best(slots(byDay, todayIndex, scale), scale, higherIsBetter)) {
                is HealthTrendResult.Trend -> return r
                HealthTrendResult.Steady -> judged = true
                HealthTrendResult.Insufficient -> Unit
            }
        }
        return if (judged) HealthTrendResult.Steady else HealthTrendResult.Insufficient
    }

    /** Worse news first, then good news, then moves with no better direction; the clearer trend first. */
    fun precedes(a: HealthTrend, b: HealthTrend): Boolean {
        fun rank(t: HealthTrend): Int = when (t.isImprovement) {
            false -> 0
            true -> 1
            null -> 2
        }
        return if (rank(a) != rank(b)) rank(a) < rank(b) else abs(a.strength) > abs(b.strength)
    }

    /** [precedes] as a comparator, for sorting. */
    val order: Comparator<HealthTrend> = Comparator { a, b ->
        when {
            precedes(a, b) -> -1
            precedes(b, a) -> 1
            else -> 0
        }
    }

    /** The span's slots, oldest first, ending with the slot that holds [today]. A week slot is the mean of
     *  the readings in its seven days. */
    fun slots(byDay: Map<Int, Double>, today: Int, scale: Scale): List<Double?> {
        val width = if (scale.unit == HealthTrend.Unit.WEEK) 7 else 1
        return (0 until scale.span).map { k ->
            val last = today - width * (scale.span - 1 - k)
            val values = (last - width + 1..last).mapNotNull { byDay[it] }
            if (values.isEmpty()) null else values.sum() / values.size
        }
    }

    private fun best(slots: List<Double?>, scale: Scale, higherIsBetter: Boolean?): HealthTrendResult {
        val recorded = slots.filterNotNull()
        if (recorded.size < scale.minRecentPoints + scale.minBaselinePoints ||
            slots.takeLast(scale.freshSlots).none { it != null }
        ) return HealthTrendResult.Insufficient
        var found: HealthTrend? = null
        var judged = false
        for (length in scale.recentLengths) {
            val recent = slots.takeLast(length).filterNotNull()
            val baseline = slots.take(slots.size - length).filterNotNull()
            if (recent.size < scale.minRecentPoints || baseline.size < scale.minBaselinePoints) continue
            judged = true
            val mr = mean(recent)
            val mb = mean(baseline)
            val vr = variance(recent, mr)
            val vb = variance(baseline, mb)
            val se = sqrt(vr / recent.size + vb / baseline.size)
            val delta = mr - mb
            if (delta == 0.0) continue
            val t = delta / max(se, 1e-9)
            val effect = abs(delta) / max(sqrt(vb), 1e-9)
            val agreeing = recent.count { if (delta > 0) it > mb else it < mb }
            if (abs(t) < MIN_STRENGTH || effect < MIN_EFFECT || agreeing.toDouble() / recent.size < MIN_AGREEMENT) continue
            // Ties go to the longer recent period: the same evidence reads as the longer-lived trend.
            val current = found
            if (current != null && abs(t) < abs(current.strength)) continue
            val direction = if (delta > 0) HealthTrend.Direction.HIGHER else HealthTrend.Direction.LOWER
            found = HealthTrend(
                direction = direction, unit = scale.unit, slots = slots, recentCount = length,
                baselineAverage = mb, recentAverage = mr, strength = t,
                isImprovement = higherIsBetter?.let { it == (direction == HealthTrend.Direction.HIGHER) },
            )
        }
        found?.let { return HealthTrendResult.Trend(it) }
        return if (judged) HealthTrendResult.Steady else HealthTrendResult.Insufficient
    }

    private fun mean(v: List<Double>): Double = v.sum() / v.size

    private fun variance(v: List<Double>, m: Double): Double {
        if (v.size <= 1) return 0.0
        var s = 0.0
        for (x in v) s += (x - m) * (x - m)
        return s / (v.size - 1)
    }

    /** "yyyy-MM-dd" → days since 1970-01-01 in the proleptic Gregorian calendar (no time zone, no DST). */
    fun dayNumber(key: String): Int? {
        val parts = key.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3 || parts[1] !in 1..12 || parts[2] !in 1..31) return null
        val y = if (parts[1] <= 2) parts[0] - 1 else parts[0]
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val m = parts[1]
        val d = parts[2]
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
