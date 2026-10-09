package com.noop.ui.insights

import com.noop.R
import com.noop.analytics.DoseResponse
import com.noop.analytics.DoseResponseEngine
import com.noop.analytics.DoseResponsePriors
import com.noop.analytics.DosedBehavior
import com.noop.analytics.EffectRanker
import com.noop.analytics.RankedEffect
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import com.noop.data.JournalEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - What Moves You (twin of iOS InsightsHubView's model + InsightCopy + InsightsRelationships)
//
// Everything is pure association on the user's own logged days, never advice, diagnosis or cause:
//  1. Habits: the lag-aware EffectRanker feed for the selected outcome, with/without means.
//  2. Alcohol / caffeine: the personal DoseResponseEngine curve, shrunk toward a documented prior; a card
//     shows only once the user's own nights, not the prior, carry the curve.
//  3. Metrics and mood: curated metric-pair correlations and what tracks the logged mood.
// All maths lives in com.noop.analytics; this file shapes the inputs (as the old Insights hub did, unchanged)
// and picks the words. No coefficients, no forecasts, no causal wording (WM-2).

/** The outcome the habit feed is ranked against (Charge / HRV / Rest / Resting HR). */
internal enum class WmyOutcome(
    /** The metric's name as the Summary's tiles print it. */
    val labelRes: Int,
    /** The engine's outcome label, carried onto each RankedEffect. */
    val outcomeName: String,
    /** The series key. */
    val key: String,
    val pick: (DailyMetric) -> Double?,
) {
    Recovery(R.string.today_metric_charge, "Charge", "recovery", { it.recovery }),
    Hrv(R.string.today_metric_hrv, "HRV", "hrv", { it.avgHrv }),
    Sleep(R.string.today_metric_rest, "Rest", "sleep_performance", { it.efficiency }),
    Rhr(R.string.today_metric_resting_hr, "Resting HR", "rhr", { it.restingHr?.toDouble() }),
    ;

    /** The unit the figure pair sets after the number. */
    val unitRes: Int?
        get() = when (this) {
            Recovery, Sleep -> null
            Hrv -> R.string.metric_unit_ms
            Rhr -> R.string.metric_unit_bpm
        }

    /** True for the outcomes shown as a percentage. */
    val isPercent: Boolean get() = this == Recovery || this == Sleep
}

/** One dose card's data: the behaviour and the engine's estimate. */
internal data class WmyDoseCard(val behavior: DosedBehavior, val response: DoseResponse) {
    val id: String get() = behavior.raw
    /** Caffeine's "dose" is its timing, not milligrams. */
    val timingProxy: Boolean get() = behavior == DosedBehavior.CAFFEINE
}

/** A curated metric relationship and its Pearson r. */
internal data class WmyRelationship(val id: String, val titleRes: Int, val r: Double, val n: Int)

/** One mood link: the body signal (as a word resource or the HRV title) and its correlation with mood. */
internal data class WmyMoodLine(val id: String, val metricRes: Int, val r: Double, val n: Int)

/** What the page loaded: journal behaviour days, the outcome series and the dose cards. */
internal data class WmySnapshot(
    val loaded: Boolean = false,
    val behaviours: Map<String, Set<String>> = emptyMap(),
    /** Per behaviour, the days it was logged NO — the only legitimate control group. */
    val controls: Map<String, Set<String>> = emptyMap(),
    val outcomeByKey: Map<String, Map<String, Double>> = emptyMap(),
    val doseCards: List<WmyDoseCard> = emptyList(),
    val relationships: List<WmyRelationship> = emptyList(),
    val moodLines: List<WmyMoodLine> = emptyList(),
) {
    /** The habit feed for [outcome], re-ranked cheaply without the database. */
    fun rank(outcome: WmyOutcome): List<RankedEffect> =
        if (!loaded) emptyList()
        else EffectRanker.rank(behaviours, controls, outcomeByKey[outcome.key] ?: emptyMap(), outcome.outcomeName)
}

internal object WhatMovesYou {
    /** The source id dose rows are parked under (mirrors the mood store's isolation). */
    const val DOSE_SOURCE = "noop-journal-dose"

    /** Mood days AND paired observations needed before a mood line shows. */
    const val MOOD_MIN_DAYS = 7
    /** The correlation magnitude a mood line needs. */
    const val MOOD_MIN_ABS_R = 0.3

    fun doseKey(behavior: DosedBehavior): String = "dose_${behavior.raw}"

    /** Whether a journal question is the dosed behaviour (so its yes-days back-fill dose = 1). */
    fun matches(behavior: DosedBehavior, question: String): Boolean {
        val q = question.lowercase(java.util.Locale.US)
        return when (behavior) {
            DosedBehavior.ALCOHOL -> q.contains("alcohol") || q.contains("drink")
            DosedBehavior.CAFFEINE -> q.contains("caffeine") || q.contains("coffee")
        }
    }

    /** The series key a DoseResponsePriors outcome NAME maps to. */
    fun outcomeKeyFor(engineName: String): String = when (engineName) {
        "Charge" -> "recovery"
        "HRV" -> "hrv"
        "Rest" -> "sleep_performance"
        "Resting HR" -> "rhr"
        else -> "recovery"
    }

    /**
     * Builds the snapshot from merged journal [entries], the cached [days], the dose rows by behaviour
     * and the mood series. The outcome series come straight off the cached DailyMetric rows (the
     * guaranteed Android source), as the old Insights hub read them.
     */
    fun build(
        entries: List<JournalEntry>,
        days: List<DailyMetric>,
        doseRows: Map<DosedBehavior, Map<String, Double>>,
        mood: List<Pair<String, Double>>,
    ): WmySnapshot {
        // Yes days and NO days, kept apart: an unanswered day is in neither.
        val byBehaviour = HashMap<String, MutableSet<String>>()
        val controls = HashMap<String, MutableSet<String>>()
        for (e in entries) {
            (if (e.answeredYes) byBehaviour else controls).getOrPut(e.question) { mutableSetOf() }.add(e.day)
        }
        val outcomeByKey = HashMap<String, Map<String, Double>>()
        for (o in WmyOutcome.entries) {
            val dict = HashMap<String, Double>()
            for (d in days) o.pick(d)?.let { dict[d.day] = it }
            outcomeByKey[o.key] = dict
        }
        // Logged "yes" days back-fill dose = 1; explicit dose rows override.
        val cards = ArrayList<WmyDoseCard>()
        for (behavior in DosedBehavior.entries) {
            val doses = HashMap<String, Int>()
            for ((question, set) in byBehaviour) if (matches(behavior, question)) {
                for (day in set) doses[day] = max(doses[day] ?: 0, 1)
            }
            for ((day, v) in doseRows[behavior].orEmpty()) doses[day] = v.roundToInt()
            if (doses.isEmpty()) continue
            val outcomeName = DoseResponsePriors.defaultOutcome(behavior)
            val response = DoseResponseEngine.estimate(behavior, doses, outcomeByKey[outcomeKeyFor(outcomeName)] ?: emptyMap())
                ?: continue
            // A curve still carried by the population prior is not the user's pattern: no card.
            if (response.priorDominated) continue
            cards.add(WmyDoseCard(behavior, response))
        }
        return WmySnapshot(
            loaded = true,
            behaviours = byBehaviour.mapValues { it.value.toSet() },
            controls = controls.mapValues { it.value.toSet() },
            outcomeByKey = outcomeByKey,
            doseCards = cards,
            relationships = relationships(outcomeByKey),
            moodLines = moodLines(days, mood),
        )
    }

    /** The curated metric pairs, each with a computable correlation over shared days. */
    fun relationships(byKey: Map<String, Map<String, Double>>): List<WmyRelationship> {
        fun series(key: String) = byKey[key].orEmpty().toSortedMap().map { it.key to it.value }
        val out = ArrayList<WmyRelationship>()
        pearson(alignByDay(series("sleep_performance"), series("recovery")))?.let { (r, n) ->
            out += WmyRelationship("sleep-rec", R.string.wmy_rel_rest_charge, r, n)
        }
        pearson(alignByDay(series("hrv"), series("recovery")))?.let { (r, n) ->
            out += WmyRelationship("hrv-rec", R.string.wmy_rel_hrv_charge, r, n)
        }
        pearson(alignByDay(series("rhr"), series("recovery")))?.let { (r, n) ->
            out += WmyRelationship("rhr-rec", R.string.wmy_rel_rhr_charge, r, n)
        }
        // Today's charge against the next entry's charge: how much one day carries into the next.
        val rec = series("recovery")
        if (rec.size > 1) {
            pearson((0 until rec.size - 1).map { rec[it].second to rec[it + 1].second })?.let { (r, n) ->
                out += WmyRelationship("rec-lag", R.string.wmy_rel_charge_lag, r, n)
            }
        }
        return out
    }

    /** Up to three body signals whose correlation with the logged mood clears the gate, strongest first. */
    fun moodLines(days: List<DailyMetric>, mood: List<Pair<String, Double>>): List<WmyMoodLine> {
        if (mood.size < MOOD_MIN_DAYS) return emptyList()
        val candidates = listOf(
            Triple("mind-hrv", R.string.today_metric_hrv, days.mapNotNull { d -> d.avgHrv?.let { d.day to it } }),
            Triple("mind-recovery", R.string.wmy_mood_recovery, days.mapNotNull { d -> d.recovery?.let { d.day to it } }),
            Triple("mind-sleep", R.string.wmy_mood_sleep_duration, days.mapNotNull { d -> d.totalSleepMin?.let { d.day to it } }),
        )
        return candidates.mapNotNull { (id, res, series) ->
            val (r, n) = pearson(alignByDay(series, mood)) ?: return@mapNotNull null
            if (n < MOOD_MIN_DAYS || abs(r) < MOOD_MIN_ABS_R) null else WmyMoodLine(id, res, r, n)
        }.sortedByDescending { abs(it.r) }.take(3)
    }

    /** Inner-join two day-keyed series on the day key → (x, y) pairs sorted by day. */
    fun alignByDay(a: List<Pair<String, Double>>, b: List<Pair<String, Double>>): List<Pair<Double, Double>> {
        val mapB = b.toMap()
        return a.toMap().entries.filter { mapB.containsKey(it.key) }.sortedBy { it.key }.map { it.value to mapB.getValue(it.key) }
    }

    /** Pearson r over the pairs, with n. Null with fewer than 3 pairs or no variance. */
    fun pearson(xy: List<Pair<Double, Double>>): Pair<Double, Int>? {
        val n = xy.size
        if (n < 3) return null
        val mx = xy.sumOf { it.first } / n
        val my = xy.sumOf { it.second } / n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for ((x, y) in xy) {
            val dx = x - mx
            val dy = y - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return null
        return (sxy / (sqrt(sxx) * sqrt(syy))).coerceIn(-1.0, 1.0) to n
    }

    // MARK: Words (WM-2: association only, never cause)

    /** The sentence resource for a habit effect, given its delta and lag. */
    fun effectSentenceRes(delta: Double, lag: Int): Int = when {
        delta == 0.0 -> R.string.wmy_effect_none
        lag == 0 -> if (delta < 0) R.string.wmy_effect_lower_same else R.string.wmy_effect_higher_same
        lag == 1 -> if (delta < 0) R.string.wmy_effect_lower_next else R.string.wmy_effect_higher_next
        else -> if (delta < 0) R.string.wmy_effect_lower_later else R.string.wmy_effect_higher_later
    }

    /** "No link." … "Very strong link: when one rises, the other falls." */
    fun relationshipSentenceRes(r: Double): Int {
        val m = abs(r)
        val up = r >= 0
        return when {
            m < 0.1 -> R.string.wmy_link_none
            m < 0.3 -> if (up) R.string.wmy_link_weak_up else R.string.wmy_link_weak_down
            m < 0.5 -> if (up) R.string.wmy_link_moderate_up else R.string.wmy_link_moderate_down
            m < 0.7 -> if (up) R.string.wmy_link_strong_up else R.string.wmy_link_strong_down
            else -> if (up) R.string.wmy_link_very_strong_up else R.string.wmy_link_very_strong_down
        }
    }

    /** Cohen's d → the conventional magnitude word. */
    fun effectSizeRes(d: Double): Int = when {
        abs(d) < 0.2 -> R.string.wmy_size_negligible
        abs(d) < 0.5 -> R.string.wmy_size_small
        abs(d) < 0.8 -> R.string.wmy_size_moderate
        else -> R.string.wmy_size_large
    }

    fun confidenceRes(c: ScoreConfidence): Int = when (c) {
        ScoreConfidence.SOLID -> R.string.wmy_confidence_solid
        ScoreConfidence.BUILDING -> R.string.wmy_confidence_building
        ScoreConfidence.CALIBRATING -> R.string.wmy_confidence_calibrating
    }

    /** The dose card's one sentence: which way the outcome tends to sit, never how much one more causes. */
    fun doseSentenceRes(behavior: DosedBehavior, perUnit: Double): Int = when {
        (abs(perUnit) * 10).roundToInt() == 0 -> R.string.wmy_dose_flat
        behavior == DosedBehavior.ALCOHOL -> if (perUnit < 0) R.string.wmy_dose_alcohol_lower else R.string.wmy_dose_alcohol_higher
        else -> if (perUnit < 0) R.string.wmy_dose_caffeine_lower else R.string.wmy_dose_caffeine_higher
    }

    /** The engine's outcome name in the reader's language. */
    fun engineOutcomeRes(name: String): Int = when (name) {
        "HRV" -> R.string.today_metric_hrv
        "Rest" -> R.string.today_metric_rest
        "Resting HR" -> R.string.today_metric_resting_hr
        else -> R.string.today_metric_charge
    }
}
