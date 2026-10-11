package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval

// SleepStageLearned.kt — the learned stage model, staging a night in [SleepStagerV2]'s place.
//
// Kotlin twin of `SleepStageLearned.swift`. When the wearer slept is still the detector's answer. What
// changes is the hypnogram inside an accepted window: the recipe's coefficients were set a priori, the
// model's were fit to human-scored polysomnography (`Tools/SleepML`).
//
// The model itself is two boosted-tree classifiers, installed as plain functions from rows to
// probabilities ([SleepStageTrees] on Android); everything around them — the features, their
// surroundings, the decoder and the tiling into [StageSegment]s — is here.
//
// A night the model cannot be trusted with is not staged by it. It was trained on wrists that report
// motion and heart rate nearly every second, so a window either of them covers thinly, and any night
// with no model installed, comes back null and the caller falls back to the recipe.

/** Class probabilities for each row, in [SleepStageDecoder.stages] order; null when the model could not
 *  answer. Rows are one night's, in order. */
typealias SleepStagePredict = (rows: List<DoubleArray>) -> List<DoubleArray>?

/** The two models and the decoder settings fit with them. */
class SleepStageModel(
    /** Names the fit. A night staged under one version is staged again under another, so this goes
     *  into every key that caches a hypnogram. */
    val version: String,
    /** Each stage's share of the training epochs, and how much of it the decoder divides out. */
    val prior: List<Double>?,
    val weight: Double,
    val smoothing: Double,
    /** Takes rows of [SleepStageContext.firstNames]. */
    val first: SleepStagePredict,
    /** Takes rows of [SleepStageContext.secondNames]. */
    val second: SleepStagePredict,
) {
    companion object {
        /**
         * The pair as two tree files, with the decoder settings the second one carries. null unless the
         * files take exactly this build's columns and carry the settings: a refused pair installs no
         * model, and every night is staged by the recipe. Swift twin:
         * `SleepStageModel.init(first:second:version:)`.
         */
        fun fromTrees(first: SleepStageTrees, second: SleepStageTrees, version: String): SleepStageModel? {
            val stages = SleepStageDecoder.stages
            if (first.columns != SleepStageContext.firstNames || second.columns != SleepStageContext.secondNames ||
                first.classes.toSet() != stages.toSet() || second.classes.toSet() != stages.toSet() ||
                second.meta["classes"] != stages.joinToString(",")) return null   // the order `classPrior` is in
            val prior = second.meta["classPrior"]?.split(',')?.filter { it.isNotEmpty() }
                ?.map { it.toDoubleOrNull() } ?: return null
            if (prior.size != stages.size || prior.any { it == null }) return null
            val weight = second.meta["priorWeight"]?.toDoubleOrNull() ?: return null
            val smoothing = second.meta["smoothing"]?.toDoubleOrNull() ?: return null
            return SleepStageModel(
                version = version, prior = prior.filterNotNull(), weight = weight, smoothing = smoothing,
                first = { first.probabilities(it, stages) },
                second = { second.probabilities(it, stages) },
            )
        }
    }
}

object SleepStageLearned {

    /** The model in use, null until the app installs one. Installed once at launch, before any night is
     *  scored; a later change re-keys every cached hypnogram through [version]. */
    @Volatile
    var model: SleepStageModel? = null

    /** The installed model's version, empty when there is none: what a cache key carries. */
    val version: String get() = model?.version ?: ""

    /** The least share of a window's seconds that must carry a gravity sample, and of its 10 s bins a
     *  heart-rate sample, before the model stages it. The training wrists covered both nearly in full;
     *  below this the model has not been measured, and a sparse-motion night (a strap that banks motion
     *  coarsely) is the recipe's to stage. */
    internal const val MIN_COVERAGE = 0.8

    private data class Key(val version: String, val streams: StagerCache.Key)
    private class Staged(val segments: List<StageSegment>?)

    /** As [StagerCache]: the scoring loop and the edited-night self-heal ask for the same window with the
     *  same streams pass after pass. Holds refusals too, so a thin night is not measured again each pass. */
    private const val MAX_NIGHTS = 24
    private val cache = object : LinkedHashMap<Key, Staged>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Staged>): Boolean = size > MAX_NIGHTS
    }
    private val lock = Any()

    /**
     * The hypnogram of `[start, end]` by the installed model, tiled as [SleepStagerV2.stageSession] tiles
     * its own: the first segment begins at [start], the last ends at [end]. null when no model is
     * installed, when motion or heart rate cover the window too thinly, or when the model does not
     * answer; the caller then stages with the recipe.
     */
    fun stageSession(
        start: Long, end: Long, grav: List<GravitySample>,
        hr: List<HrSample>, rr: List<RrInterval>,
    ): List<StageSegment>? {
        val model = model ?: return null
        if (end <= start) return null
        // Only what the features read: the window and `REACH` either side.
        val lo = start - SleepStageFeatures.REACH - 30; val hi = end + SleepStageFeatures.REACH + 30
        val gravW = grav.filter { it.ts >= lo && it.ts < hi }
        val hrW = hr.filter { it.ts >= lo && it.ts < hi }
        val rrW = rr.filter { it.ts >= lo && it.ts < hi }
        // The recipe tag only names the digest's shape; `version` is what tells one model from another.
        val key = Key(model.version, StagerCache.fingerprint(StagerCache.Version.V2, start, end, gravW, hrW, rrW))
        synchronized(lock) { cache[key] }?.let { return it.segments?.let(StagerCache::copyOf) }
        val segments = stageUncached(model, start, end, gravW, hrW, rrW)
        synchronized(lock) { cache[key] = Staged(segments?.let(StagerCache::copyOf)) }
        return segments
    }

    /** Whether motion and heart rate cover `[start, end)` densely enough for the model. */
    internal fun covers(start: Long, end: Long, grav: List<GravitySample>, hr: List<HrSample>): Boolean {
        val seconds = (end - start).toInt()
        if (seconds <= 0) return false
        val moved = BooleanArray(seconds)
        for (g in grav) if (g.ts >= start && g.ts < end) moved[(g.ts - start).toInt()] = true
        val bins = (seconds + SleepStageFeatures.HR_BIN - 1) / SleepStageFeatures.HR_BIN
        val beat = BooleanArray(bins)
        for (h in hr) if (h.ts >= start && h.ts < end) beat[(h.ts - start).toInt() / SleepStageFeatures.HR_BIN] = true
        return moved.count { it }.toDouble() >= MIN_COVERAGE * seconds.toDouble() &&
            beat.count { it }.toDouble() >= MIN_COVERAGE * bins.toDouble()
    }

    private fun stageUncached(
        model: SleepStageModel, start: Long, end: Long, grav: List<GravitySample>,
        hr: List<HrSample>, rr: List<RrInterval>,
    ): List<StageSegment>? {
        if (!covers(start, end, grav, hr)) return null
        val rows = SleepStageFeatures.rows(start, end, grav, hr, rr)
        if (rows.isEmpty()) return null
        val xs = SleepStageContext.firstInput(rows)
        val p1 = model.first(xs)
        if (p1 == null || !valid(p1, rows.size)) return null
        val p = model.second(SleepStageContext.secondInput(xs, p1))
        if (p == null || !valid(p, rows.size)) return null
        val labels = SleepStageDecoder.decode(p, prior = model.prior, weight = model.weight, smoothing = model.smoothing)
        val segments = ArrayList<StageSegment>()
        for ((i, row) in rows.withIndex()) {
            val segStart = if (i == 0) start else row.start
            val segEnd = if (i == rows.size - 1) end else rows[i + 1].start
            val last = segments.lastOrNull()
            if (last != null && last.stage == labels[i]) {
                last.end = segEnd
            } else {
                segments.add(StageSegment(start = segStart, end = segEnd, stage = labels[i]))
            }
        }
        return segments
    }

    /** A model's answer is usable when it is one finite row of four per epoch. */
    private fun valid(p: List<DoubleArray>, epochs: Int): Boolean =
        p.size == epochs && p.all { row -> row.size == SleepStageDecoder.stages.size && row.all { it.isFinite() } }
}
