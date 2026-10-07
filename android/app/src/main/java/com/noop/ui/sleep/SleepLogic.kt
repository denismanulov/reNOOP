package com.noop.ui.sleep

import com.noop.analytics.RestScorer
import com.noop.analytics.StagePercentages
import com.noop.data.DailyMetric
import com.noop.ui.PersistedSegment
import com.noop.ui.Stages
import com.noop.ui.canonicalStage
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - The pure logic of the Sleep tab. The score parts, the vitals ranges and the range windows follow
// iOS Strand/SleepHealth (SleepScore, SleepVitals, SleepHistory); the levels, the verdict and the week are the
// Android tab's own reading of the same figures. No Android types: the screens map the enums to strings.

// MARK: Sleep Score

/** The four parts of the Rest composite, in the composite's own order. */
internal enum class SleepScorePart {
    DURATION, INTERRUPTIONS, RESTORATIVE, REGULARITY;

    /** Points this part is worth out of 100 (the composite's weights). */
    val maxPoints: Int
        get() = when (this) {
            DURATION -> (RestScorer.wDuration * 100).roundToInt()
            INTERRUPTIONS -> (RestScorer.wEfficiency * 100).roundToInt()
            RESTORATIVE -> (RestScorer.wRestorative * 100).roundToInt()
            REGULARITY -> (RestScorer.wConsistency * 100).roundToInt()
        }

    companion object {
        /**
         * The parts one night can be read on, in the order the page lists them. Regularity is left out: the
         * composite gives a single night a neutral constant for it ([RestScorer.NEUTRAL_CONSISTENCY]), so it
         * says nothing about this night and a line or a sentence about it would claim what nothing measured.
         */
        val measured = listOf(DURATION, INTERRUPTIONS, RESTORATIVE)
    }
}

/** The word for a score, and for how one of its parts did. */
internal enum class SleepScoreWord { POOR, FAIR, GOOD, OPTIMAL }

/** How the night did on one part: the share of the part's points it earned (0…1). */
internal data class SleepScorePartScore(val part: SleepScorePart, val fraction: Double) {
    /**
     * The part in the score's own four words. A part is a ratio against its target and stops at 1, so its
     * bands sit higher than the score's: 6 h 48 min of an 8 h target is "good", 5 h 36 min "fair".
     */
    val level: SleepScoreWord
        get() = when {
            fraction >= 0.95 -> SleepScoreWord.OPTIMAL
            fraction >= 0.85 -> SleepScoreWord.GOOD
            fraction >= 0.70 -> SleepScoreWord.FAIR
            else -> SleepScoreWord.POOR
        }

    /** Points of the composite this part cost the night. */
    val lostPoints: Double get() = part.maxPoints * (1 - fraction)
}

/** Which sentence the night card leads with. */
internal enum class SleepVerdict { IMPORTED, SOUND, SHORT, RESTLESS, SHALLOW }

/**
 * One night's sleep score and what it is made of. The score is the one every surface shows: WHOOP's imported
 * figure for the wake-day when the export carried one, else the Rest composite of the day's row
 * ([RestScorer.restFromDaily]). The parts are the composite's own sub-scores for the three things a night can
 * be measured on ([SleepScorePart.measured]). An imported score has no known make-up: its parts are still the
 * night's own figures, and the verdict says only where the score came from.
 */
internal data class SleepScore(val value: Int, val imported: Boolean, val parts: List<SleepScorePartScore>) {

    val word: SleepScoreWord get() = word(value)

    /** An optimal night is sound; any other is named after the part that cost it the most points. */
    val verdict: SleepVerdict
        get() {
            if (imported) return SleepVerdict.IMPORTED
            if (word == SleepScoreWord.OPTIMAL) return SleepVerdict.SOUND
            val worst = parts.maxByOrNull { it.lostPoints }
            if (worst == null || worst.lostPoints < 1.0) return SleepVerdict.SOUND
            return when (worst.part) {
                SleepScorePart.DURATION -> SleepVerdict.SHORT
                SleepScorePart.INTERRUPTIONS -> SleepVerdict.RESTLESS
                SleepScorePart.RESTORATIVE -> SleepVerdict.SHALLOW
                SleepScorePart.REGULARITY -> SleepVerdict.SOUND
            }
        }

    companion object {
        /** The time asleep the score counts as a full night (minutes). */
        val TARGET_MIN: Double = RestScorer.defaultSleepNeedHours * 60.0

        /** The composite's sub-scores for a day row, each 0…1, exactly as [RestScorer.rest] weighs them. */
        fun components(daily: DailyMetric): Map<SleepScorePart, Double>? {
            val tstMin = daily.totalSleepMin ?: return null
            val eff = daily.efficiency ?: return null
            if (tstMin <= 0.0) return null
            val asleepSec = tstMin * 60.0
            val deepSec = (daily.deepMin ?: 0.0) * 60.0
            val remSec = (daily.remMin ?: 0.0) * 60.0
            val needHours = RestScorer.defaultSleepNeedHours.coerceAtLeast(0.1)
            val duration = min(1.0, asleepSec / 3600.0 / needHours)
            val efficiency = eff.coerceIn(0.0, 1.0)
            val deepAdequacy = ((deepSec / asleepSec) / RestScorer.deepShareTarget).coerceIn(0.0, 1.0)
            val deepFactor = RestScorer.deepFloorFactor + (1.0 - RestScorer.deepFloorFactor) * deepAdequacy
            val restorative = min(1.0, (deepSec + remSec) / asleepSec / RestScorer.restorativeTargetShare) * deepFactor
            val consistency = RestScorer.NEUTRAL_CONSISTENCY.coerceIn(0.0, 1.0)
            return mapOf(
                SleepScorePart.DURATION to duration,
                SleepScorePart.INTERRUPTIONS to efficiency,
                SleepScorePart.RESTORATIVE to restorative,
                SleepScorePart.REGULARITY to consistency,
            )
        }

        /** The night's score from its day row and the imported figure for the same wake-day. */
        fun make(daily: DailyMetric?, importedPct: Double?): SleepScore? {
            val c = daily?.let { components(it) }
            val parts = c?.let { m -> SleepScorePart.measured.map { SleepScorePartScore(it, m.getValue(it)) } }.orEmpty()
            if (importedPct != null) return SleepScore(importedPct.roundToInt(), imported = true, parts = parts)
            if (daily == null || c == null) return null
            val composite = RestScorer.restFromDaily(daily) ?: return null
            return SleepScore(composite.roundToInt(), imported = false, parts = parts)
        }

        /** The banding the Rest word has always used. */
        fun word(value: Int): SleepScoreWord = when {
            value < 50 -> SleepScoreWord.POOR
            value < 70 -> SleepScoreWord.FAIR
            value < 85 -> SleepScoreWord.GOOD
            else -> SleepScoreWord.OPTIMAL
        }
    }
}

// MARK: Stage intervals

/** The four rows of the stages chart, top to bottom. */
internal enum class SleepStageRow { AWAKE, REM, CORE, DEEP;

    companion object {
        fun of(stage: String): SleepStageRow = when (canonicalStage(stage)) {
            "awake" -> AWAKE
            "rem" -> REM
            "deep" -> DEEP
            else -> CORE
        }
    }
}

/** One run of a stage in seconds from the night's onset. */
internal data class SleepStageSpan(val row: SleepStageRow, val startSec: Double, val endSec: Double) {
    val durationSec: Double get() = endSec - startSec
}

/** A night's timestamped segments as spans from [onsetTs], clipped to start at the onset. */
internal fun stageSpans(segments: List<PersistedSegment>?, onsetTs: Long): List<SleepStageSpan> =
    segments.orEmpty().sortedBy { it.start }.mapNotNull { s ->
        val start = max(0L, s.start - onsetTs).toDouble()
        val end = (s.end - onsetTs).toDouble()
        if (end > start) SleepStageSpan(SleepStageRow.of(s.stage), start, end) else null
    }

/** Minutes of [row] in [stages]. */
internal fun Stages.minutes(row: SleepStageRow): Double = when (row) {
    SleepStageRow.AWAKE -> awake
    SleepStageRow.REM -> rem
    SleepStageRow.CORE -> light
    SleepStageRow.DEEP -> deep
}

// MARK: Vitals

/** A night's overnight vitals against their typical ranges (iOS `SleepVitals`). */
internal data class SleepVitals(val readings: List<Reading>, val nightsRemaining: Int) {

    /** The body's overnight readings. Time asleep is not one of them here: the night card already leads with it. */
    enum class Metric(val catalogKey: String, val minHalfWidth: Double) {
        HEART_RATE("rhr", 2.0),
        RESPIRATORY("resp_rate", 0.5),
        TEMPERATURE("skin_temp", 0.3),
        OXYGEN("spo2", 1.0);

        /** This metric's reading for one day's row: skin temperature as a deviation or an absolute. */
        fun value(row: DailyMetric, deviation: Boolean = false): Double? = when (this) {
            HEART_RATE -> row.restingHr?.toDouble()
            RESPIRATORY -> row.respRateBpm
            TEMPERATURE -> if (deviation) row.skinTempDevC else row.skinTempC
            OXYGEN -> row.spo2Pct
        }
    }

    /** Where a reading fell against its typical range. */
    enum class Level { LOW, TYPICAL, HIGH }

    data class Reading(val metric: Metric, val value: Double, val low: Double, val high: Double) {
        val level: Level
            get() = when {
                value < low -> Level.LOW
                value > high -> Level.HIGH
                else -> Level.TYPICAL
            }
        val isOutlier: Boolean get() = level != Level.TYPICAL
    }

    val nightsRecorded: Int get() = NIGHTS_NEEDED - nightsRemaining
    val outliers: Int get() = readings.count { it.isOutlier }

    companion object {
        const val NIGHTS_NEEDED = 7
        const val WINDOW = 28

        /** The night that ended on [day] read against up to [WINDOW] nights before it. */
        fun make(rows: List<DailyMetric>, day: String): SleepVitals {
            val tonight = rows.lastOrNull { it.day == day } ?: return SleepVitals(emptyList(), NIGHTS_NEEDED)
            val prior = rows.filter { it.day < day }.sortedBy { it.day }.takeLast(WINDOW)
            val withVitals = prior.count { row ->
                Metric.entries.any { it.value(row) != null || it.value(row, deviation = true) != null }
            }
            val remaining = max(0, NIGHTS_NEEDED - withVitals)
            if (remaining != 0) return SleepVitals(emptyList(), remaining)
            val deviation = tonight.skinTempDevC != null
            val readings = Metric.entries.mapNotNull { metric ->
                val value = metric.value(tonight, deviation) ?: return@mapNotNull null
                val history = prior.mapNotNull { metric.value(it, deviation) }
                if (history.size < NIGHTS_NEEDED) return@mapNotNull null
                val (lo, hi) = typicalRange(history, metric.minHalfWidth) ?: return@mapNotNull null
                Reading(metric, value, lo, hi)
            }
            return SleepVitals(readings, 0)
        }

        /** Mean ± two standard deviations, at least [minHalfWidth] either side. */
        fun typicalRange(values: List<Double>, minHalfWidth: Double): Pair<Double, Double>? {
            if (values.isEmpty()) return null
            val mean = values.sum() / values.size
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            val half = max(2 * sqrt(variance), minHalfWidth)
            return (mean - half) to (mean + half)
        }
    }
}

// MARK: Stage ranges

/** A sleep stage's usual minutes for this reader: the band a night's figure is read against. */
internal data class SleepStageRange(val low: Double, val high: Double) {
    fun level(minutes: Double): SleepVitals.Level = when {
        minutes < low -> SleepVitals.Level.LOW
        minutes > high -> SleepVitals.Level.HIGH
        else -> SleepVitals.Level.TYPICAL
    }
}

internal object SleepStageRanges {
    /** A stage's range is never narrower than this either side of its mean (minutes). */
    const val MIN_HALF_WIDTH_MIN = 10.0

    /**
     * Each sleep stage's usual range over up to [SleepVitals.WINDOW] nights before [day], by the rule the
     * vitals use ([SleepVitals.typicalRange]). A stage with fewer than [SleepVitals.NIGHTS_NEEDED] nights on
     * record has none, and Awake never has one: the day rows store no awake minutes to learn it from.
     */
    fun make(rows: List<DailyMetric>, day: String): Map<SleepStageRow, SleepStageRange> {
        val prior = rows.filter { it.day < day }.sortedBy { it.day }.takeLast(SleepVitals.WINDOW)
        fun range(minutes: (DailyMetric) -> Double?): SleepStageRange? {
            val history = prior.mapNotNull(minutes).filter { it > 0.0 }
            if (history.size < SleepVitals.NIGHTS_NEEDED) return null
            val (lo, hi) = SleepVitals.typicalRange(history, MIN_HALF_WIDTH_MIN) ?: return null
            return SleepStageRange(max(0.0, lo), hi)
        }
        return buildMap {
            range { it.deepMin }?.let { put(SleepStageRow.DEEP, it) }
            range { it.remMin }?.let { put(SleepStageRow.REM, it) }
            range { it.lightMin }?.let { put(SleepStageRow.CORE, it) }
        }
    }
}

// MARK: Nights over time

/** One decoded night: its span, stage totals and (when stored) its timestamped stages. */
internal data class SleepNightDetail(
    /** Index into navDays (0 = newest night on record). */
    val navIndex: Int,
    /** The calendar day the night ended on, and its "yyyy-MM-dd" key. */
    val day: LocalDate,
    val onsetTs: Long,
    val wakeTs: Long,
    val stages: Stages,
    /** Spans from [onsetTs]; empty when the night carries only stage totals. */
    val spans: List<SleepStageSpan>,
) {
    val dayKey: String get() = day.toString()
    val inBedMin: Double get() = stages.total
    val asleepMin: Double get() = stages.asleep
    val entry: SleepNightEntry get() = SleepNightEntry(day, onsetTs, wakeTs, asleepMin)
}

/** A night as (bedtime, wake, time asleep), with a night clock that runs through midnight. */
internal data class SleepNightEntry(val day: LocalDate, val onsetTs: Long, val wakeTs: Long, val asleepMin: Double) {
    /** Minutes after 18:00 on the evening before [day]. */
    fun onsetOfNightMin(zone: ZoneId = ZoneId.systemDefault()): Double = (onsetTs - nightOrigin(day, zone)) / 60.0
    fun wakeOfNightMin(zone: ZoneId = ZoneId.systemDefault()): Double = (wakeTs - nightOrigin(day, zone)) / 60.0

    companion object {
        fun nightOrigin(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toEpochSecond() - 6 * 3600
    }
}

internal enum class SleepRange { WEEK, MONTH, SIX_MONTHS }

/** One bar of a range chart: a night (week / month) or a week's average (6 months). */
internal data class SleepRangeBar(
    val slot: Int,
    val start: LocalDate,
    val onsetMin: Double,
    val wakeMin: Double,
    val asleepMin: Double,
)

internal data class SleepRangeWindow(val range: SleepRange, val slotStarts: List<LocalDate>, val bars: List<SleepRangeBar>) {
    val averageAsleepMin: Double? get() = if (bars.isEmpty()) null else bars.sumOf { it.asleepMin } / bars.size
}

internal object SleepHistory {
    /** The window ending on [today]: 7 nights, 30 nights, or 26 weekly averages (weeks start Monday). */
    fun window(
        range: SleepRange,
        entries: List<SleepNightEntry>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SleepRangeWindow {
        val byDay = entries.associateBy { it.day }
        return when (range) {
            SleepRange.WEEK, SleepRange.MONTH -> {
                val count = if (range == SleepRange.MONTH) 30 else 7
                val starts = (count - 1 downTo 0).map { today.minusDays(it.toLong()) }
                val bars = starts.mapIndexedNotNull { slot, day ->
                    val e = byDay[day] ?: return@mapIndexedNotNull null
                    SleepRangeBar(slot, day, e.onsetOfNightMin(zone), e.wakeOfNightMin(zone), e.asleepMin)
                }
                SleepRangeWindow(range, starts, bars)
            }
            SleepRange.SIX_MONTHS -> {
                val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val starts = (25 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
                val bars = starts.mapIndexedNotNull { slot, weekStart ->
                    val weekEnd = weekStart.plusDays(7)
                    val nights = entries.filter { !it.day.isBefore(weekStart) && it.day.isBefore(weekEnd) }
                    if (nights.isEmpty()) return@mapIndexedNotNull null
                    val n = nights.size.toDouble()
                    SleepRangeBar(
                        slot, weekStart,
                        nights.sumOf { it.onsetOfNightMin(zone) } / n,
                        nights.sumOf { it.wakeOfNightMin(zone) } / n,
                        nights.sumOf { it.asleepMin } / n,
                    )
                }
                SleepRangeWindow(range, starts, bars)
            }
        }
    }

    /** The nights a range view covers, by the same slot boundaries as [window]. */
    fun nightsIn(range: SleepRange, nights: List<SleepNightDetail>, today: LocalDate): List<SleepNightDetail> {
        val first = window(range, emptyList(), today).slotStarts.firstOrNull() ?: return emptyList()
        return nights.filter { !it.day.isBefore(first) && !it.day.isAfter(today) }
    }

    /** The vertical domain of a range chart (night-clock minutes): whole hours either side, at least 2 h. */
    fun domain(window: SleepRangeWindow): Pair<Double, Double> {
        val lo = window.bars.minOfOrNull { it.onsetMin } ?: return 240.0 to 840.0
        val hi = window.bars.maxOf { it.wakeMin }
        val start = (floor(lo / 60) - 1) * 60
        val end = (ceil(hi / 60) + 1) * 60
        return start to max(start + 120, end)
    }
}

/** Averages over a set of nights (a single night averages to itself). */
internal data class SleepPeriodSummary(
    val nights: Int,
    val inBedMin: Double?,
    val asleepMin: Double?,
    val stageMin: Map<SleepStageRow, Double>,
    val bedtimeOfNightMin: Double?,
    val wakeOfNightMin: Double?,
) {
    /**
     * [row]'s whole-percent share of the night, from one largest-remainder split of the four stages, so the
     * four rows add up to exactly 100 instead of four independent roundings landing on 99 or 101. The split
     * runs in the order the old Sleep screen and its read-out used (awake, light, deep, REM): ties go to the
     * lower index, so the order decides which stage gets a spare point. Null when there is nothing to split.
     */
    fun sharePercent(row: SleepStageRow): Int? {
        val apportion = listOf(SleepStageRow.AWAKE, SleepStageRow.CORE, SleepStageRow.DEEP, SleepStageRow.REM)
        val whole = StagePercentages.wholePercentages(apportion.map { stageMin[it] ?: 0.0 }) ?: return null
        return whole[apportion.indexOf(row)]
    }

    val efficiency: Double?
        get() {
            val inBed = inBedMin ?: return null
            val asleep = asleepMin ?: return null
            return if (inBed > 0) asleep / inBed else null
        }

    companion object {
        fun of(nights: List<SleepNightDetail>, zone: ZoneId = ZoneId.systemDefault()): SleepPeriodSummary {
            if (nights.isEmpty()) return SleepPeriodSummary(0, null, null, emptyMap(), null, null)
            val n = nights.size.toDouble()
            fun mean(f: (SleepNightDetail) -> Double) = nights.sumOf(f) / n
            return SleepPeriodSummary(
                nights = nights.size,
                inBedMin = mean { it.inBedMin },
                asleepMin = mean { it.asleepMin },
                stageMin = SleepStageRow.entries.associateWith { row -> mean { it.stages.minutes(row) } },
                bedtimeOfNightMin = mean { it.entry.onsetOfNightMin(zone) },
                wakeOfNightMin = mean { it.entry.wakeOfNightMin(zone) },
            )
        }
    }
}

// MARK: The week around a night

/**
 * The seven days ending on the night the page shows: each day's time asleep, their average against the seven
 * days before, and how the night's bedtime sits against the nights before it. Anchored to the night on the
 * page, not to today, so paging back to an older night shows that night's own week.
 */
internal data class SleepWeek(
    /** The seven calendar days, oldest first; the last is the night on the page. */
    val days: List<LocalDate>,
    /** Time asleep per day (minutes); null where no night was recorded. */
    val asleepMin: List<Double?>,
    /** Mean time asleep over the week's recorded nights; null with fewer than [MIN_NIGHTS]. */
    val averageMin: Double?,
    /** [averageMin] minus the mean of the seven days before, rounded to 5 min; null when either week is thin. */
    val changeMin: Int?,
    val bedtime: Bedtime?,
) {
    /** The night's bedtime against the mean of up to seven recorded nights before it; [diffMin] rounded to 5. */
    data class Bedtime(val usualMin: Double, val diffMin: Int)

    companion object {
        /** Fewer nights than this is not a pattern: no average and no "usual". */
        const val MIN_NIGHTS = 3

        fun make(entries: List<SleepNightEntry>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SleepWeek {
            val byDay = entries.associateBy { it.day }
            val days = (6 downTo 0).map { day.minusDays(it.toLong()) }
            val asleep = days.map { byDay[it]?.asleepMin }
            val recent = asleep.filterNotNull()
            val prior = (13 downTo 7).mapNotNull { byDay[day.minusDays(it.toLong())]?.asleepMin }
            val average = if (recent.size >= MIN_NIGHTS) recent.average() else null
            val change = if (average != null && prior.size >= MIN_NIGHTS) {
                ((average - prior.average()) / 5).roundToInt() * 5
            } else null
            return SleepWeek(days, asleep, average, change, bedtime(entries, day, zone))
        }

        fun bedtime(entries: List<SleepNightEntry>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Bedtime? {
            val night = entries.firstOrNull { it.day == day } ?: return null
            val before = entries.filter { it.day.isBefore(day) }.sortedBy { it.day }.takeLast(7)
            if (before.size < MIN_NIGHTS) return null
            val usual = before.sumOf { it.onsetOfNightMin(zone) } / before.size
            val diff = ((night.onsetOfNightMin(zone) - usual) / 5).roundToInt() * 5
            return Bedtime(usual, diff)
        }
    }
}

/** Hours and minutes of a duration, rounded to the minute. */
internal fun durationParts(minutes: Double): Pair<Int, Int> {
    val total = max(0, minutes.roundToInt())
    return (total / 60) to (total % 60)
}

/** The local calendar day an instant falls on. */
internal fun localDate(ts: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()
