package com.noop.ui.summary

import androidx.annotation.StringRes
import com.noop.R
import com.noop.analytics.ReadinessEngine
import com.noop.analytics.SkinTempDisplay
import com.noop.data.DailyMetric
import com.noop.ui.AppToday
import com.noop.ui.DayRelation
import com.noop.ui.KeyMetric
import com.noop.ui.UnitFormatter
import com.noop.ui.UnitSystem
import com.noop.ui.isCarryStale
import com.noop.ui.metric.MetricCatalog
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Summary logic (pure twins of iOS Strand/Summary/*)
//
// Everything the Summary decides that does not need a screen: what the Charge ring may honestly claim,
// how far the day pager may go, the "Today / Yesterday / 24 Sep" stamps, what a pinned card says, which
// readiness signals earn a highlight, and the quiet sync line. Kept free of Android and Compose so the
// JVM tests pin them; the screen only formats the results into words.

/**
 * What the Charge ring can honestly say for the picked day (twin of iOS `ChargeDisplay`): the day's own
 * score, a REAL prior night's carried with whose it is, the calibration countdown, or nothing.
 */
sealed class ChargeDisplay {
    data class Scored(val value: Double) : ChargeDisplay()

    /** A prior night's score on today, before tonight is scored. [stale] relabels it "Latest sleep". */
    data class Carried(val value: Double, val priorDay: String, val stale: Boolean) : ChargeDisplay()

    data class Calibrating(val nights: Int) : ChargeDisplay()

    object NoData : ChargeDisplay()

    /** The number the ring draws, or null for the honest empty states. */
    val pct: Double?
        get() = when (this) {
            is Scored -> value
            is Carried -> value
            else -> null
        }

    companion object {
        /**
         * Scored beats calibrating beats a carried prior night beats nothing. [priorScored] is the row
         * `lastScoredRecoveryDay` picked (it is null off today, while calibrating, or once today scored).
         */
        fun resolve(
            todayRecovery: Double?,
            priorScored: DailyMetric?,
            calibrationNights: Int?,
            todayKey: String,
        ): ChargeDisplay {
            if (todayRecovery != null) return Scored(todayRecovery)
            if (calibrationNights != null) return Calibrating(calibrationNights)
            val prior = priorScored ?: return NoData
            val pct = prior.recovery ?: return NoData
            return Carried(pct, prior.day, isCarryStale(prior.day, todayKey))
        }
    }
}

/** The catalogue keys of the three rings (twin of iOS `HeroRingMetric`): what each figure's page opens on. */
object HeroRingMetric {
    const val CHARGE = "recovery"
    const val EFFORT = "strain"
    const val REST = "sleep_performance"

    /** Charge, Effort, Rest, in the order the rings card lists them. */
    val all: List<String> = listOf(CHARGE, EFFORT, REST)
}

/** Day arithmetic for the pager: offset 0 is today's logical day (rolls at 04:00), 1 yesterday, … */
object SummaryDay {
    /**
     * Whole days from [todayKey] back to [earliestDayKey] (both "yyyy-MM-dd"). A missing or unparseable
     * earliest day, or one on / after today, gives 0: today is then the only day the pager offers.
     */
    fun maxDayOffset(earliestDayKey: String?, todayKey: String): Int {
        val earliest = parse(earliestDayKey) ?: return 0
        val today = parse(todayKey) ?: return 0
        return ChronoUnit.DAYS.between(earliest, today).toInt().coerceAtLeast(0)
    }

    /**
     * The offset a date picked in the calendar lands on, counted from the LOGICAL day (so a pick between
     * midnight and 04:00 stays in step with the visible date). A future pick collapses to today.
     */
    fun pickedDayOffset(picked: LocalDate, anchorLogicalDay: LocalDate): Int =
        ChronoUnit.DAYS.between(picked, anchorLogicalDay).toInt().coerceAtLeast(0)

    internal fun parse(key: String?): LocalDate? = key?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/** A card's recency stamp: "Today", "Yesterday", or the date the value was measured on. */
sealed class SummaryStamp {
    object Today : SummaryStamp()
    object Yesterday : SummaryStamp()
    data class OnDate(val date: LocalDate) : SummaryStamp()

    companion object {
        /**
         * The stamp of [dayKey] against [today], the same [AppToday] a metric page stamps its latest reading
         * with, so one value cannot read "Today" here and "Yesterday" there. Pure, so the words never depend
         * on when the screen redraws.
         */
        internal fun resolve(dayKey: String?, today: AppToday): SummaryStamp? = when (val r = today.relation(dayKey)) {
            null -> null
            DayRelation.Today -> Today
            DayRelation.Yesterday -> Yesterday
            is DayRelation.OnDate -> OnDate(r.date)
        }

        /**
         * A pinned card's stamp on the picked day. On a past day the pager already names the day, so a
         * value from that very day says nothing more; only a value carried from another day is stamped.
         */
        internal fun forCard(dayKey: String?, dayOffset: Int, selectedKey: String, today: AppToday): SummaryStamp? =
            if (dayOffset != 0 && dayKey == selectedKey) null else resolve(dayKey, today)
    }
}

/** Everything the pinned cards and tiles read for one picked day. Built once per load. */
data class SummaryMetricInputs(
    /** The picked day's own row. */
    val day: DailyMetric?,
    /** Today-only carries for when the morning row has no vitals yet (null on a past day). */
    val vitalsDay: DailyMetric? = null,
    val respDay: DailyMetric? = null,
    val hrvDay: DailyMetric? = null,
    val restingHrDay: DailyMetric? = null,
    /** Today-only: the freshest prior row with a measured SpO₂ (computed rows never carry one). */
    val spo2Day: DailyMetric? = null,
    val skinTempReading: SkinTempDisplay.Reading? = null,
    /** The unvalidated strap SpO₂ estimate, non-null only while its Experimental switch is on. */
    val spo2Candidate: Double? = null,
    val importedSteps: Int? = null,
    val stepsEstimate: Double? = null,
    val importedActiveKcal: Double? = null,
    /** NOOP's on-device calorie estimate for the day. */
    val onDeviceKcal: Double? = null,
    /** Phone weight for the day, or its most recent reading before it. */
    val healthWeightKg: Double? = null,
    val profileWeightKg: Double = 75.0,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val fahrenheit: Boolean = false,
    /** The picked day's key: the stamp for values that belong to it outright. */
    val dayKey: String,
    val healthWeightDay: String? = null,
    val skinTempDay: String? = null,
)

/** How a card draws its week: a line for levels, bars for daily totals. */
enum class SummaryChart { LINE, BARS }

/** A caption a card carries under its value. */
enum class SummaryCaption { STRAP_ESTIMATE, FROM_PROFILE }

/** What one pinned card or tile says (twin of iOS `SummaryMetricReading`). */
data class SummaryMetricReading(
    /** Formatted number, or [NO_VALUE]. */
    val value: String,
    /** A unit key for `localizedUnit` ("ms", "bpm", "rpm", "%", "kcal", "kg", "lb"); empty when none. */
    val unit: String,
    val caption: SummaryCaption?,
    /** Key into the loader's week series. */
    val seriesKey: String,
    val chart: SummaryChart,
    /** The metric page the card opens: catalogue key, and a source when it must be a specific one. */
    val routeKey: String,
    val routeSource: String? = null,
    /** The day the value was measured on, for the stamp; null for a profile weight or no value. */
    val stampDay: String? = null,
) {
    val hasValue: Boolean get() = value != NO_VALUE

    companion object {
        const val NO_VALUE = "–"

        /** Null for the ring metrics, which are never pinned cards. */
        fun resolve(metric: KeyMetric, i: SummaryMetricInputs, locale: Locale): SummaryMetricReading? {
            val f = SummaryNumbers(locale)
            return when (metric) {
                KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST -> null
                KeyMetric.HRV -> {
                    val row = if (i.day?.avgHrv != null) i.day else i.hrvDay
                    reading(f.int(row?.avgHrv), "ms", "hrv", day = row?.day)
                }
                KeyMetric.RESTING_HR -> {
                    val row = if (i.day?.restingHr != null) i.day else i.restingHrDay
                    reading(f.int(row?.restingHr?.toDouble()), "bpm", "rhr", day = row?.day)
                }
                KeyMetric.BLOOD_OXYGEN -> {
                    val row = listOfNotNull(i.day, i.vitalsDay, i.spo2Day).firstOrNull { it.spo2Pct != null }
                    val real = row?.spo2Pct
                    val candidate = if (real == null) i.spo2Candidate else null
                    reading(
                        f.int(real ?: candidate), "%", if (candidate != null) "spo2_candidate" else "spo2",
                        routeKey = "spo2",
                        caption = if (candidate != null) SummaryCaption.STRAP_ESTIMATE else null,
                        day = if (real != null) row?.day else i.dayKey,
                    )
                }
                KeyMetric.RESPIRATORY -> {
                    val row = listOfNotNull(i.day, i.vitalsDay, i.respDay).firstOrNull { it.respRateBpm != null }
                    reading(f.decimal(row?.respRateBpm), "rpm", "resp_rate", day = row?.day)
                }
                KeyMetric.STEPS -> {
                    val count = i.day?.steps?.toDouble() ?: i.importedSteps?.toDouble() ?: i.stepsEstimate
                    val (key, source) = stepsRoute(i.day?.steps != null, i.importedSteps != null)
                    SummaryMetricReading(
                        value = f.grouped(count), unit = "", caption = null, seriesKey = "steps",
                        chart = SummaryChart.BARS, routeKey = key, routeSource = source,
                        stampDay = if (count == null) null else i.dayKey,
                    )
                }
                KeyMetric.CALORIES -> {
                    val kcal = i.importedActiveKcal ?: i.onDeviceKcal ?: i.day?.activeKcalEst
                    val (key, source) = caloriesRoute(i.importedActiveKcal != null)
                    SummaryMetricReading(
                        value = f.int(kcal), unit = if (kcal == null) "" else "kcal", caption = null,
                        seriesKey = "energy_kcal", chart = SummaryChart.BARS, routeKey = key, routeSource = source,
                        stampDay = if (kcal == null) null else i.dayKey,
                    )
                }
                KeyMetric.WEIGHT -> {
                    val unit = if (i.unitSystem == UnitSystem.IMPERIAL) "lb" else "kg"
                    fun mass(kg: Double) =
                        f.decimal(if (i.unitSystem == UnitSystem.IMPERIAL) UnitFormatter.kgToPounds(kg) else kg)
                    val health = i.healthWeightKg
                    if (health != null) {
                        reading(mass(health), unit, "weight", day = i.healthWeightDay)
                    } else {
                        // A profile figure was typed in, not measured: it has no day, so it says where it came from.
                        reading(mass(i.profileWeightKg), unit, "weight", caption = SummaryCaption.FROM_PROFILE)
                    }
                }
                KeyMetric.SKIN_TEMP -> {
                    val r = i.skinTempReading
                    reading(
                        r?.let { SkinTempDisplay.formatReading(it, fahrenheit = i.fahrenheit) } ?: NO_VALUE,
                        "", "skin_temp", day = if (r == null) null else i.skinTempDay,
                    )
                }
            }
        }

        /** The Steps card opens the page of the figure it shows: measured, imported, else the estimate. */
        fun stepsRoute(hasMeasured: Boolean, hasImported: Boolean): Pair<String, String> = when {
            hasMeasured -> "steps" to MetricCatalog.WHOOP
            hasImported -> "steps" to MetricCatalog.PHONE
            else -> "steps_est" to MetricCatalog.WHOOP
        }

        /** The Calories card opens the imported figure's page when the day has one, else NOOP's estimate. */
        fun caloriesRoute(hasImported: Boolean): Pair<String, String> =
            if (hasImported) "active_kcal" to MetricCatalog.PHONE else "energy_kcal" to MetricCatalog.WHOOP

        private fun reading(
            value: String,
            unit: String,
            key: String,
            routeKey: String = key,
            caption: SummaryCaption? = null,
            day: String? = null,
        ): SummaryMetricReading {
            val has = value != NO_VALUE
            return SummaryMetricReading(
                value = value, unit = if (has) unit else "", caption = caption, seriesKey = key,
                chart = SummaryChart.LINE, routeKey = routeKey, stampDay = if (has) day else null,
            )
        }
    }
}

/** The Summary's number formats in the app language ("79,9" in Russian, "8 432" grouped). */
class SummaryNumbers(private val locale: Locale) {
    fun int(v: Double?): String =
        if (v == null || !v.isFinite()) SummaryMetricReading.NO_VALUE else v.roundToInt().toString()

    fun decimal(v: Double?): String {
        if (v == null || !v.isFinite()) return SummaryMetricReading.NO_VALUE
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = 1
        nf.maximumFractionDigits = 1
        nf.isGroupingUsed = false
        return nf.format(v)
    }

    fun grouped(v: Double?): String {
        if (v == null || !v.isFinite()) return SummaryMetricReading.NO_VALUE
        val nf = NumberFormat.getIntegerInstance(locale)
        nf.isGroupingUsed = true
        return nf.format(v.toLong())
    }
}

/** A readiness signal worth a "Highlights" card (twin of iOS `SummaryHighlight`). */
data class SummaryHighlight(
    /** The engine's signal key: "hrv" | "rhr" | "respRate" | "acwr" | "monotony". */
    val key: String,
    val flag: ReadinessEngine.Flag,
    val evidence: ReadinessEngine.Evidence?,
    /** The metric page the card opens. */
    val routeKey: String,
) {
    companion object {
        const val MAX_COUNT = 3

        /**
         * At most three cards: a neutral signal is not news, and the rest are ordered most-concerning
         * first (bad, watch, good), stable within a severity, so a warning never sits under a compliment.
         */
        fun from(readiness: ReadinessEngine.Readiness?): List<SummaryHighlight> {
            if (readiness == null || readiness.level == ReadinessEngine.Level.INSUFFICIENT) return emptyList()
            return readiness.signals
                .filter { it.flag != ReadinessEngine.Flag.NEUTRAL }
                .withIndex()
                .sortedWith(compareBy({ rank(it.value.flag) }, { it.index }))
                .take(MAX_COUNT)
                .map { (_, s) -> SummaryHighlight(s.key, s.flag, s.evidence, routeKey(s.key)) }
        }

        /** Training load and variety are both Effort stories. */
        fun routeKey(signalKey: String): String = when (signalKey) {
            "hrv" -> "hrv"
            "rhr" -> "rhr"
            "respRate" -> "resp_rate"
            else -> HeroRingMetric.EFFORT
        }

        private fun rank(flag: ReadinessEngine.Flag): Int = when (flag) {
            ReadinessEngine.Flag.BAD -> 0
            ReadinessEngine.Flag.WATCH -> 1
            ReadinessEngine.Flag.GOOD -> 2
            ReadinessEngine.Flag.NEUTRAL -> 3
        }

        /** The signal's short name (iOS `ReadinessCopy.label`). */
        @StringRes
        fun titleRes(key: String): Int = when (key) {
            "hrv" -> R.string.today_metric_hrv
            "rhr" -> R.string.today_metric_resting_hr
            "respRate" -> R.string.summary_hl_respiratory_rate
            "acwr" -> R.string.summary_hl_training_load
            else -> R.string.summary_hl_training_variety
        }

        /**
         * The signal as one whole sentence (iOS `ReadinessCopy.sentence`). [SENTENCE_NORMAL] means the
         * generic "<label> is in your normal range." with the title as its argument.
         */
        @StringRes
        fun sentenceRes(key: String, flag: ReadinessEngine.Flag, evidence: ReadinessEngine.Evidence?): Int =
            when (key to flag) {
                "hrv" to ReadinessEngine.Flag.GOOD -> R.string.summary_hl_hrv_good
                "hrv" to ReadinessEngine.Flag.WATCH -> R.string.summary_hl_hrv_watch
                "hrv" to ReadinessEngine.Flag.BAD -> R.string.summary_hl_hrv_bad
                "rhr" to ReadinessEngine.Flag.GOOD -> R.string.summary_hl_rhr_good
                "rhr" to ReadinessEngine.Flag.WATCH -> R.string.summary_hl_rhr_watch
                "rhr" to ReadinessEngine.Flag.BAD -> R.string.summary_hl_rhr_bad
                "respRate" to ReadinessEngine.Flag.BAD -> R.string.summary_hl_resp_bad
                "respRate" to ReadinessEngine.Flag.WATCH -> R.string.summary_hl_resp_watch
                "acwr" to ReadinessEngine.Flag.BAD -> R.string.summary_hl_load_bad
                "acwr" to ReadinessEngine.Flag.WATCH -> {
                    val load = evidence as? ReadinessEngine.Evidence.TrainingLoad
                    if (load != null && load.acute < load.chronic) R.string.summary_hl_load_down
                    else R.string.summary_hl_load_fast
                }
                else -> when (key) {
                    "acwr" -> R.string.summary_hl_load_steady
                    "monotony" -> R.string.summary_hl_monotony
                    else -> SENTENCE_NORMAL
                }
            }

        val SENTENCE_NORMAL: Int = R.string.summary_hl_normal
    }
}

/** The quiet line under the Summary: "Syncing…", "Updated just now", "Updated 5 minutes ago", or nothing. */
sealed class SummarySyncLine {
    object Syncing : SummarySyncLine()
    object JustNow : SummarySyncLine()
    data class MinutesAgo(val minutes: Long) : SummarySyncLine()
    data class HoursAgo(val hours: Long) : SummarySyncLine()
    data class DaysAgo(val days: Long) : SummarySyncLine()
    object Hidden : SummarySyncLine()

    companion object {
        /**
         * Offloading wins; otherwise the last completed sync's age (under a minute is "just now"); with
         * neither, nothing (a cold start, or a 5/MG whose history sync is still experimental). [nowSec] and
         * [lastSyncAtSec] are unix seconds; a sync stamped in the future reads as just now.
         */
        fun resolve(backfilling: Boolean, lastSyncAtSec: Long?, nowSec: Long): SummarySyncLine {
            if (backfilling) return Syncing
            val at = lastSyncAtSec ?: return Hidden
            val secs = (nowSec - at).coerceAtLeast(0)
            return when {
                secs < 60 -> JustNow
                secs < 3_600 -> MinutesAgo(secs / 60)
                secs < 86_400 -> HoursAgo(secs / 3_600)
                else -> DaysAgo(secs / 86_400)
            }
        }
    }
}
