package com.noop.ui.highlights

import com.noop.analytics.StepsCounter
import com.noop.data.StepSample
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Highlights math (twin of iOS HealthHighlightMath)
//
// The figures behind each kind of Highlights card the Health app draws: today against a usual day, a week's
// average, this month or year against the last, a workout's or a night's range, the minutes after a workout,
// and the days that had one. Pure: series and samples in, the card's numbers out. No store, no copy, no
// views. Days are "yyyy-MM-dd" keys and LocalDates; instants are unix seconds.

/** How often a highlight is renewed, which is the section it is filed under on a subject's Highlights page. */
enum class HighlightCadence { DAILY, WEEKLY, MONTHLY, YEARLY }

/** Which way a figure moved against the one it is read beside. Within 5 % reads as the same. */
enum class HighlightDirection {
    MORE, LESS, SAME;

    companion object {
        fun of(value: Double, reference: Double, tolerance: Double = 0.05): HighlightDirection {
            if (abs(value - reference) <= tolerance * max(abs(reference), 1e-9)) return SAME
            return if (value > reference) MORE else LESS
        }
    }
}

/** A point of a day's running total: how far through the day (0 at midnight, 1 at the next), the total by then. */
data class HighlightDayPoint(val fraction: Double, val total: Double)

/** Today's running total against the usual one by this time of day. */
data class HighlightProgress(
    /** Midnight to now. */
    val today: List<HighlightDayPoint>,
    /** Midnight to midnight, the mean of the days compared. */
    val typical: List<HighlightDayPoint>,
    val nowFraction: Double,
    val todayTotal: Double,
    /** The usual total by now. */
    val typicalTotal: Double,
    val direction: HighlightDirection,
)

/** Seven days side by side with their mean drawn across. */
data class HighlightSevenDay(
    /** Oldest first; null is a day with no reading. */
    val values: List<Double?>,
    val days: List<LocalDate>,
    /** The mean of the days on record. */
    val average: Double,
)

enum class HighlightPeriod { MONTH, YEAR }

/** One period's figure against the period before it. */
data class HighlightComparison(
    val period: HighlightPeriod,
    val current: Double,
    val previous: Double,
    /** The first day of each period, for its name ("October", "2026"). */
    val currentStart: LocalDate,
    val previousStart: LocalDate,
    val direction: HighlightDirection,
)

/** A stretch of time cut into even slices, each with the lowest and highest reading inside it. */
data class HighlightRange(
    /** null is a slice with no reading. */
    val slices: List<Slice?>,
    val start: Long,
    val end: Long,
    val minimum: Double,
    val maximum: Double,
    /** The slices holding the lowest and the highest reading. */
    val minimumIndex: Int,
    val maximumIndex: Int,
) {
    data class Slice(val low: Double, val high: Double)
}

/** Heart rate over the three minutes after a workout ended. */
data class HighlightRecovery(
    val end: Long,
    val readings: List<Reading>,
    val atEnd: Int,
    val afterOneMinute: Int,
    val afterTwoMinutes: Int,
    val afterThreeMinutes: Int,
) {
    /** [seconds] after the workout ended. */
    data class Reading(val seconds: Double, val bpm: Double)

    val drop: Int get() = atEnd - afterThreeMinutes
}

/** Whole weeks of days, each marked as having the thing or not. */
data class HighlightConsistency(
    /** Whole weeks, oldest first, each starting on the first day of the reader's week. */
    val days: List<Day>,
    val count: Int,
    val today: LocalDate,
) {
    enum class Mark { DONE, MISSED, AHEAD }
    data class Day(val date: LocalDate, val mark: Mark)
}

/** What sits under a highlight's sentence. */
sealed interface HighlightBody {
    data class Progress(val progress: HighlightProgress) : HighlightBody
    data class SevenDay(val week: HighlightSevenDay) : HighlightBody
    data class Comparison(val comparison: HighlightComparison) : HighlightBody
    /** [marksEnds]: a bead at the ends of the lowest and highest slice, as Health marks a night's range. */
    data class Range(val range: HighlightRange, val marksEnds: Boolean = false) : HighlightBody
    data class Recovery(val recovery: HighlightRecovery) : HighlightBody
    data class Consistency(val consistency: HighlightConsistency) : HighlightBody
}

object HealthHighlightMath {

    /** Days compared for a "usual day"; a usual day needs this many of them on record. */
    const val TYPICAL_DAYS = 14
    const val MINIMUM_TYPICAL_DAYS = 3

    // MARK: Today against a usual day

    /**
     * [todayHourly] and each row of [priorHourly] are 24 hourly amounts (not running totals). Hours that
     * have not begun are ignored. A prior day with nothing in it is a day off the wrist, not a day of zero,
     * so it is left out of the mean.
     */
    fun progress(todayHourly: List<Double>, priorHourly: List<List<Double>>, nowTs: Long, zone: ZoneId): HighlightProgress? {
        if (todayHourly.size != 24) return null
        val days = priorHourly.filter { it.size == 24 && it.sum() > 0 }
        if (days.size < MINIMUM_TYPICAL_DAYS) return null

        val midnight = java.time.Instant.ofEpochSecond(nowTs).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        val nowHours = min(24.0, max(0.0, (nowTs - midnight) / 3600.0))
        val nowFraction = nowHours / 24

        val typical = arrayListOf(HighlightDayPoint(0.0, 0.0))
        var running = 0.0
        for (hour in 0 until 24) {
            running += days.fold(0.0) { acc, day -> acc + day[hour] } / days.size
            typical += HighlightDayPoint((hour + 1) / 24.0, running)
        }
        if (running <= 0) return null

        val today = arrayListOf(HighlightDayPoint(0.0, 0.0))
        var todayTotal = 0.0
        val whole = nowHours.toInt()
        for (hour in 0 until min(whole, 24)) {
            todayTotal += todayHourly[hour]
            today += HighlightDayPoint((hour + 1) / 24.0, todayTotal)
        }
        if (whole < 24 && nowHours > whole) {
            // The hour in progress holds everything counted in it so far.
            todayTotal += todayHourly[whole]
            today += HighlightDayPoint(nowFraction, todayTotal)
        }

        val typicalTotal = total(typical, nowFraction)
        return HighlightProgress(
            today, typical, nowFraction, todayTotal, typicalTotal,
            HighlightDirection.of(todayTotal, typicalTotal, tolerance = 0.1),
        )
    }

    /** The running total at a point of the day, read off the line between the two points around it. */
    fun total(points: List<HighlightDayPoint>, fraction: Double): Double {
        var before = points.firstOrNull() ?: return 0.0
        for (point in points) {
            if (point.fraction >= fraction) {
                val span = point.fraction - before.fraction
                if (span <= 0) return point.total
                return before.total + (point.total - before.total) * (fraction - before.fraction) / span
            }
            before = point
        }
        return before.total
    }

    // MARK: Seven days

    /** The seven days ending on [lastDay]. Needs three of them on record. */
    fun sevenDay(series: List<Pair<String, Double>>, lastDay: LocalDate): HighlightSevenDay? {
        val byDay = HashMap<String, Double>()
        for ((day, value) in series) byDay[day] = value
        val days = (6 downTo 0).map { lastDay.minusDays(it.toLong()) }
        val values = days.map { byDay[it.toString()] }
        val recorded = values.filterNotNull()
        if (recorded.size < 3) return null
        return HighlightSevenDay(values, days, recorded.sum() / recorded.size)
    }

    // MARK: This period against the last

    /**
     * The daily mean of the month (or year) [today] is in against the one before it. [total] compares the
     * sums instead (a count of workouts). Each period needs enough days on record to stand for it: three of
     * a month, fourteen of a year.
     */
    fun comparison(
        series: List<Pair<String, Double>>,
        period: HighlightPeriod,
        today: LocalDate,
        total: Boolean = false,
    ): HighlightComparison? {
        val currentStart: LocalDate
        val previousStart: LocalDate
        val previousEnd: LocalDate
        when (period) {
            HighlightPeriod.MONTH -> {
                currentStart = today.withDayOfMonth(1)
                previousStart = currentStart.minusMonths(1)
            }
            HighlightPeriod.YEAR -> {
                currentStart = today.withDayOfYear(1)
                previousStart = currentStart.minusYears(1)
            }
        }
        previousEnd = currentStart
        val dayEnd = today.plusDays(1)
        val now = ArrayList<Double>()
        val then = ArrayList<Double>()
        for ((day, value) in series) {
            val date = date(day) ?: continue
            if (date >= currentStart && date < dayEnd) {
                now += value
            } else if (date >= previousStart && date < previousEnd) {
                then += value
            }
        }
        val needed = if (total) 1 else if (period == HighlightPeriod.MONTH) 3 else 14
        if (now.size < needed || then.size < needed) return null
        val a = if (total) now.sum() else now.sum() / now.size
        val b = if (total) then.sum() else then.sum() / then.size
        return HighlightComparison(period, a, b, currentStart, previousStart, HighlightDirection.of(a, b))
    }

    // MARK: A range over time

    /** The slices a stretch is cut into: about three minutes each for a workout, half an hour for a night. */
    const val TARGET_SLICES = 18

    /** A reading with its own low and high (a raw sample has both equal). */
    data class RangeSample(val ts: Long, val low: Double, val high: Double)

    /** The stretch cut into slices of a round length; needs readings in four of them. */
    fun range(samples: List<RangeSample>, start: Long, end: Long): HighlightRange? {
        if (end <= start) return null
        val span = end - start
        val length = sliceLength(span)
        val count = max(1, ceil(span.toDouble() / length).toInt())
        val slices = arrayOfNulls<HighlightRange.Slice>(count)
        for (sample in samples) {
            if (sample.ts < start || sample.ts >= end || sample.high <= 0) continue
            val index = min(count - 1, ((sample.ts - start) / length).toInt())
            val slice = slices[index]
            slices[index] = if (slice != null) {
                HighlightRange.Slice(min(slice.low, sample.low), max(slice.high, sample.high))
            } else {
                HighlightRange.Slice(sample.low, sample.high)
            }
        }
        val filled = slices.withIndex().filter { it.value != null }
        if (filled.size < 4) return null
        // The first of equal lows and the first of equal highs, as Swift's min(by:) and max(by:) pick.
        var lowest = filled.first()
        var highest = filled.first()
        for (entry in filled) {
            if (entry.value!!.low < lowest.value!!.low) lowest = entry
            if (entry.value!!.high > highest.value!!.high) highest = entry
        }
        return HighlightRange(
            slices.toList(), start, end,
            lowest.value!!.low, highest.value!!.high, lowest.index, highest.index,
        )
    }

    /** The round slice length, in seconds, that cuts [span] into the fewest slices above the target. */
    fun sliceLength(span: Long): Long {
        val steps = longArrayOf(60, 120, 180, 300, 600, 900, 1200, 1800, 3600, 7200)
        return steps.firstOrNull { span / it <= TARGET_SLICES } ?: 7200
    }

    // MARK: After a workout

    /** A heart-rate reading. */
    data class BeatSample(val ts: Long, val bpm: Double)

    /**
     * The three minutes after [end]. Each minute's figure is the reading nearest it, within twenty seconds;
     * a minute with none means the strap was off, and there is no card.
     */
    fun recovery(samples: List<BeatSample>, end: Long): HighlightRecovery? {
        val window = samples.filter { it.ts >= end - 20 && it.ts <= end + 200 && it.bpm > 0 }
        fun nearest(target: Long): Int? =
            window.filter { abs(it.ts - target) <= 20 }
                .minByOrNull { abs(it.ts - target) }
                ?.bpm?.roundToInt()
        val atEnd = nearest(end) ?: return null
        val one = nearest(end + 60) ?: return null
        val two = nearest(end + 120) ?: return null
        val three = nearest(end + 180) ?: return null
        if (atEnd <= three) return null
        val readings = window.filter { it.ts >= end && it.ts <= end + 180 }
            .map { HighlightRecovery.Reading((it.ts - end).toDouble(), it.bpm) }
        return HighlightRecovery(end, thinned(readings), atEnd, one, two, three)
    }

    /** One reading per fifteen seconds, which is as many dots as the card has room for. */
    fun thinned(readings: List<HighlightRecovery.Reading>): List<HighlightRecovery.Reading> {
        val out = ArrayList<HighlightRecovery.Reading>()
        var slot = -1
        for (reading in readings) {
            val index = (reading.seconds / 15).toInt()
            if (index != slot) {
                out += reading
                slot = index
            }
        }
        return out
    }

    // MARK: Days that had one

    /** [weeks] whole weeks ending with the week [today] is in. Days after today are still ahead. */
    fun consistency(activeDays: Set<String>, weeks: Int, today: LocalDate, firstDayOfWeek: DayOfWeek): HighlightConsistency? {
        if (weeks <= 0) return null
        val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val first = weekStart.minusDays(7L * (weeks - 1))
        val days = ArrayList<HighlightConsistency.Day>()
        var count = 0
        for (offset in 0 until 7 * weeks) {
            val date = first.plusDays(offset.toLong())
            val mark = when {
                date > today -> HighlightConsistency.Mark.AHEAD
                date.toString() in activeDays -> {
                    count++
                    HighlightConsistency.Mark.DONE
                }
                else -> HighlightConsistency.Mark.MISSED
            }
            days += HighlightConsistency.Day(date, mark)
        }
        return HighlightConsistency(days, count, today)
    }

    // MARK: Steps by the hour

    /**
     * A day's counter ticks cut into 24 hours. Each hour is counted with the last sample before it, so the
     * steps taken across the hour's edge are not lost between two windows.
     */
    fun hourlyTicks(samples: List<StepSample>, dayStart: Long, dayEnd: Long): List<Double> {
        val hours = DoubleArray(24)
        val sorted = samples.sortedBy { it.ts }
        val length = max(1L, (dayEnd - dayStart) / 24)
        var index = 0
        var previous: StepSample? = null
        for (hour in 0 until 24) {
            val hourEnd = if (hour == 23) dayEnd else dayStart + length * (hour + 1)
            val window = ArrayList<StepSample>()
            previous?.let { window += it }
            while (index < sorted.size && sorted[index].ts < hourEnd) {
                window += sorted[index]
                index++
            }
            hours[hour] = (StepsCounter.stepsInWindow(window) ?: 0).toDouble()
            previous = window.lastOrNull() ?: previous
        }
        return hours.toList()
    }

    // MARK: Day keys

    /** "yyyy-MM-dd" → the date, or null. */
    fun date(key: String): LocalDate? = runCatching { LocalDate.parse(key) }.getOrNull()
}
