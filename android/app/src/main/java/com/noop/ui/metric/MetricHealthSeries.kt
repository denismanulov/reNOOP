package com.noop.ui.metric

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// MARK: - Metric page series (twin of iOS MetricHealthSeries)
//
// A metric's daily series in, the picked period out: its bounds, its buckets (days for W and M, weeks for
// 6M, months for Y), the average the header prints, the Highlights card's "latest against your two-week
// average", and the chart's scale and ticks. No view state and no Android types, so every figure on the
// page comes from here and is unit-tested.

/** The period picker's four spans. There is no day view: every catalogue series holds one value per day. */
enum class MetricRange { WEEK, MONTH, SIX_MONTHS, YEAR;

    /** What one chart mark stands for. */
    val bucket: MetricBucket
        get() = when (this) {
            WEEK, MONTH -> MetricBucket.DAY
            SIX_MONTHS -> MetricBucket.WEEK
            YEAR -> MetricBucket.MONTH
        }
}

enum class MetricBucket { DAY, WEEK, MONTH }

/** One chart mark: a day's value, or the mean of the days on record in a week or a month. */
data class MetricPoint(
    val start: LocalDate,
    /** Exclusive. */
    val end: LocalDate,
    val value: Double,
    /** How many days on record went into [value]. */
    val count: Int,
    /** The newest day in the bucket ("yyyy-MM-dd"). */
    val lastDay: String,
)

/** The picked period of a series. */
data class MetricWindow(
    val range: MetricRange,
    val start: LocalDate,
    /** Exclusive. */
    val end: LocalDate,
    /** The day the window ends on: today, or the newest reading when today's span is empty. */
    val anchor: LocalDate,
    val points: List<MetricPoint>,
    /** The day readings inside the window, oldest first. */
    val days: List<Pair<String, Double>>,
) {
    /** The mean of the days on record, not of the buckets, so a short week does not weigh like a full one. */
    val average: Double? get() = if (days.isEmpty()) null else days.sumOf { it.second } / days.size
}

/** The Highlights card: the newest reading against the mean of the fortnight before it. */
data class MetricHighlight(
    val latest: Double,
    val average: Double,
    /** The readings of the fortnight ending on the newest one, oldest first; the last is [latest]. */
    val values: List<Double>,
    val firstDay: String,
    val lastDay: String,
    val direction: Direction,
) {
    enum class Direction { ABOVE, BELOW, CLOSE }
}

object MetricHealthSeries {

    /** "yyyy-MM-dd" → a date, or null. */
    fun date(key: String): LocalDate? = runCatching { LocalDate.parse(key) }.getOrNull()

    private fun startOfWeek(d: LocalDate, firstDay: DayOfWeek): LocalDate = d.with(TemporalAdjusters.previousOrSame(firstDay))

    /** The interval a date's bucket covers: its day, its week (from [firstDay]) or its month. */
    fun bucketInterval(bucket: MetricBucket, d: LocalDate, firstDay: DayOfWeek): Pair<LocalDate, LocalDate> = when (bucket) {
        MetricBucket.DAY -> d to d.plusDays(1)
        MetricBucket.WEEK -> startOfWeek(d, firstDay).let { it to it.plusDays(7) }
        MetricBucket.MONTH -> d.withDayOfMonth(1).let { it to it.plusMonths(1) }
    }

    /**
     * The span a range covers when it ends on [anchor]'s day. Week and month end with that day; 6M runs
     * whole weeks and Y whole months, so the first and last buckets are never cut in half.
     */
    fun bounds(range: MetricRange, anchor: LocalDate, firstDay: DayOfWeek): Pair<LocalDate, LocalDate> {
        val dayEnd = anchor.plusDays(1)
        return when (range) {
            MetricRange.WEEK -> anchor.minusDays(6) to dayEnd
            MetricRange.MONTH -> anchor.minusMonths(1) to dayEnd
            MetricRange.SIX_MONTHS -> {
                val from = dayEnd.minusMonths(6)
                startOfWeek(from, firstDay) to startOfWeek(anchor, firstDay).plusDays(7)
            }
            MetricRange.YEAR -> {
                val end = anchor.withDayOfMonth(1).plusMonths(1)
                end.minusMonths(12) to end
            }
        }
    }

    /**
     * The picked range ending today — or, when today's span has nothing on record, ending on the newest
     * reading, so a series that stopped (a weekly weigh-in, an old import) still draws instead of "No Data".
     */
    fun window(
        series: List<Pair<String, Double>>,
        range: MetricRange,
        today: LocalDate,
        firstDay: DayOfWeek,
    ): MetricWindow {
        val dated = series.mapNotNull { row -> date(row.first)?.let { it to row } }
        var anchor = today
        var span = bounds(range, anchor, firstDay)
        val hasToday = dated.any { it.first >= span.first && it.first < span.second }
        val newest = dated.maxOfOrNull { it.first }
        if (!hasToday && newest != null && newest < span.first) {
            anchor = newest
            span = bounds(range, newest, firstDay)
        }
        val inside = dated.filter { it.first >= span.first && it.first < span.second }.sortedBy { it.first }

        val points = ArrayList<MetricPoint>()
        var bucketStart: LocalDate? = null
        var bucketEnd = span.first
        val values = ArrayList<Double>()
        var lastDay = ""
        fun flush() {
            val s = bucketStart ?: return
            if (values.isEmpty()) return
            points += MetricPoint(s, bucketEnd, values.sum() / values.size, values.size, lastDay)
        }
        for ((d, row) in inside) {
            val (bs, be) = bucketInterval(range.bucket, d, firstDay)
            if (bs != bucketStart) {
                flush()
                bucketStart = bs
                bucketEnd = be
                values.clear()
            }
            values += row.second
            lastDay = row.first
        }
        flush()
        return MetricWindow(range, span.first, span.second, anchor, points, inside.map { it.second })
    }

    /**
     * The newest reading against the mean of the readings in the 13 days before it. Needs four of them, so
     * a single earlier night is never called "your average". Within 5 % of it reads as "close".
     */
    fun highlight(series: List<Pair<String, Double>>): MetricHighlight? {
        val last = series.lastOrNull() ?: return null
        val lastDate = date(last.first) ?: return null
        val from = lastDate.minusDays(13)
        val recent = series.filter { row -> date(row.first)?.let { it >= from && it <= lastDate } ?: false }
        val prior = recent.dropLast(1).map { it.second }
        if (prior.size < 4) return null
        val first = recent.firstOrNull() ?: return null
        val average = prior.sum() / prior.size
        val direction = when {
            abs(last.second - average) <= 0.05 * max(abs(average), 1e-9) -> MetricHighlight.Direction.CLOSE
            last.second > average -> MetricHighlight.Direction.ABOVE
            else -> MetricHighlight.Direction.BELOW
        }
        return MetricHighlight(last.second, average, recent.map { it.second }, first.first, last.first, direction)
    }

    // MARK: - Chart scale

    /** The y scale a window is drawn on, per its [spec]. */
    fun yDomain(values: List<Double>, spec: MetricChartSpec): ClosedFloatingPointRange<Double> {
        if (spec.mark == MetricMark.DIVERGING) {
            val top = max(values.maxOfOrNull { abs(it) } ?: 0.0, 0.3) * 1.25
            return -top..top
        }
        spec.domain?.let { fixed ->
            return min(fixed.start, values.minOrNull() ?: fixed.start)..max(fixed.endInclusive, values.maxOrNull() ?: fixed.endInclusive)
        }
        val lo = values.minOrNull() ?: return 0.0..4.0
        val hi = values.maxOrNull() ?: return 0.0..4.0
        if (spec.mark == MetricMark.BARS) return 0.0..max(hi * 1.12, 1.0)
        val pad = max((hi - lo) * 0.25, max(abs(hi) * 0.04, 1.0))
        return (lo - pad)..(hi + pad)
    }

    /**
     * Round values across the domain, about four of them. A banded score reads its bands off the axis: the
     * ticks sit on the dashed thresholds.
     */
    fun yTicks(domain: ClosedFloatingPointRange<Double>, thresholds: List<Double>): List<Double> {
        if (thresholds.isNotEmpty()) return listOf(domain.start) + thresholds.sorted() + listOf(domain.endInclusive)
        val raw = (domain.endInclusive - domain.start) / 4
        if (raw <= 0 || !raw.isFinite()) return emptyList()
        val mag = 10.0.pow(floor(log10(raw)))
        // 2.5 only where it still lands on whole numbers (25, 250…), so no tick reads "92.5".
        val steps = if (mag >= 10) listOf(1.0, 2.0, 2.5, 5.0, 10.0) else listOf(1.0, 2.0, 5.0, 10.0)
        val step = steps.map { it * mag }.firstOrNull { it >= raw } ?: (10 * mag)
        val ticks = ArrayList<Double>()
        var v = ceil(domain.start / step) * step
        while (v <= domain.endInclusive + step * 1e-6) {
            ticks += v
            v += step
        }
        return ticks
    }

    /**
     * The x-axis tick dates: every day for a week, each week's first day for a month, and each month's
     * first day for 6M and Y.
     */
    fun xTicks(window: MetricWindow, firstDay: DayOfWeek): List<LocalDate> = when (window.range) {
        MetricRange.WEEK -> (0 until 7).map { window.start.plusDays(it.toLong()) }
        MetricRange.MONTH -> {
            var weekStart = startOfWeek(window.start, firstDay)
            if (weekStart < window.start) weekStart = weekStart.plusDays(7)
            (0 until 35 step 7).map { weekStart.plusDays(it.toLong()) }.filter { it < window.end }
        }
        MetricRange.SIX_MONTHS, MetricRange.YEAR -> {
            val out = ArrayList<LocalDate>()
            var cursor = window.start.withDayOfMonth(1)
            if (cursor < window.start) cursor = cursor.plusMonths(1)
            while (cursor < window.end) {
                out += cursor
                cursor = cursor.plusMonths(1)
            }
            out
        }
    }
}
