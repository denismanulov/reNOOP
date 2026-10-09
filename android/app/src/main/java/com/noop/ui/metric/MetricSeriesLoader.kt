package com.noop.ui.metric

import android.content.Context
import com.noop.analytics.SkinTempDisplay
import com.noop.analytics.VitalBands
import com.noop.data.AppleDaily
import com.noop.data.DailyMetric
import com.noop.data.Vo2MaxEstimator
import com.noop.data.WhoopRepository
import com.noop.ui.AppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.SPO2_CANDIDATE_ATTRIBUTION_SOURCE
import com.noop.ui.UnitPrefs
import com.noop.ui.shouldExplainShortenedSkinTempSeries
import com.noop.ui.shouldExplainSkinTempFallback
import com.noop.ui.vo2MaxAttributionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// MARK: - Metric series loader (twin of iOS MetricSeriesLoader + Repository.exploreSeries)
//
// One metric's daily series, read the one way every screen that shows it reads it (its page, its All
// Metrics card, its trend card), so they can never disagree. Each day also keeps the raw source id that
// supplied it, for "Show All Data" and the VO₂max line breaks.
//
// The WHOOP source layers three stores, imported-wins per day, exactly as iOS `exploreSeries` does: the
// merged daily row's column (imported ∪ on-device), then the on-device ("-noop") series, then the imported
// export's series, each across the active strap and the canonical "my-whoop". The phone source reads an
// Apple Health export and a Health Connect sync (series first, then their daily aggregates); every other
// source reads only its own series.

/** Why a skin-temperature series leads with what it does, when that needs saying (#1847 / #1848). */
enum class SkinTempNote { FALLBACK_TO_DEVIATION, SHORTENED_TO_ABSOLUTE }

/** A metric's full history: ascending (day, value), the source per day, and a skin-temperature note. */
data class MetricPageSeries(
    val series: List<Pair<String, Double>> = emptyList(),
    val sourceByDay: Map<String, String> = emptyMap(),
    val skinTempNote: SkinTempNote? = null,
)

/** Everything a load reads once and shares across metrics. */
class MetricLoadContext(
    val repo: WhoopRepository,
    val activeStrapId: String,
    /** The merged daily history (imported ∪ on-device), oldest first. */
    val days: List<DailyMetric>,
    /** Apple Health export and Health Connect daily aggregates, per source id. */
    val phoneDaily: Map<String, List<AppleDaily>>,
    val skinTemp: SkinTempDisplay.Kind,
    val spo2CandidateOn: Boolean,
)

object MetricSeriesLoader {
    private const val FROM = "0000-01-01"
    private const val TO = "9999-12-31"
    private val PHONE_SOURCES = listOf(WhoopRepository.APPLE_HEALTH_SOURCE, WhoopRepository.HEALTH_CONNECT_SOURCE)

    /** The last context read, its inputs and when: All Metrics → a page → All Data reuse one read. */
    @Volatile private var memo: Triple<List<Any?>, Long, MetricLoadContext>? = null
    private const val MEMO_MS = 30_000L

    /**
     * Reads the shared inputs once, off the main thread. A read of the same inputs (strap, day history,
     * preferences) from the last half minute is reused; the series themselves are always read fresh.
     */
    suspend fun context(vm: AppViewModel, context: Context): MetricLoadContext = withContext(Dispatchers.IO) {
        val active = vm.activeStrapId
        val recent = vm.recentDays.value
        val inputs = listOf(
            active, recent.size, recent.lastOrNull(),
            UnitPrefs.skinTempPreferred(context), NoopPrefs.spo2CandidateDisplay(context),
        )
        val now = System.currentTimeMillis()
        memo?.let { (key, at, ctx) -> if (key == inputs && now - at < MEMO_MS) return@withContext ctx }
        MetricLoadContext(
            repo = vm.repo,
            activeStrapId = active,
            days = runCatching { vm.repo.daysMerged(active) }.getOrDefault(emptyList()),
            phoneDaily = PHONE_SOURCES.associateWith { src ->
                runCatching { vm.repo.appleDaily(src, FROM, TO) }.getOrDefault(emptyList())
            },
            skinTemp = UnitPrefs.skinTempPreferred(context),
            spo2CandidateOn = NoopPrefs.spo2CandidateDisplay(context),
        ).also { memo = Triple(inputs, now, it) }
    }

    /** The phone daily aggregate backing a phone key, or null. */
    internal fun phoneColumn(key: String, row: AppleDaily): Double? = when (key) {
        "steps" -> row.steps?.takeIf { it >= 0 }?.toDouble()
        "active_kcal" -> row.activeKcal?.takeIf { it > 0 }
        "vo2max" -> row.vo2max
        "weight" -> row.weightKg
        else -> null
    }?.takeIf { it.isFinite() }

    /**
     * The metric's series and per-day provenance. [provenance] false skips the per-day VO₂max estimator
     * lookups, which only "Show All Data" and the line breaks read.
     */
    suspend fun load(metric: MetricDescriptor, ctx: MetricLoadContext, provenance: Boolean = true): MetricPageSeries =
        withContext(Dispatchers.IO) {
            val repo = ctx.repo
            val byDay = HashMap<String, Double>()
            val source = HashMap<String, String>()
            when (metric.source) {
                MetricCatalog.WHOOP -> {
                    // Lowest first: each later layer overwrites, so an imported export wins its day.
                    for (d in ctx.days) {
                        val v = WhoopRepository.dailyColumn(metric.key, d)?.takeIf { it.isFinite() } ?: continue
                        byDay[d.day] = v
                        source[d.day] = d.deviceId
                    }
                    val computed = WhoopRepository.computedSourceIdsFor(ctx.activeStrapId)
                    val imported = WhoopRepository.importedSourceIdsFor(ctx.activeStrapId)
                    for (id in computed.asReversed() + imported.asReversed()) {
                        for (row in runCatching { repo.metricSeries(id, metric.key, FROM, TO) }.getOrDefault(emptyList())) {
                            if (!row.value.isFinite()) continue
                            byDay[row.day] = row.value
                            source[row.day] = id
                        }
                    }
                }
                MetricCatalog.PHONE -> {
                    // First source wins its day: an Apple Health export, then Health Connect, each its series
                    // before its daily aggregate (the order Today reads imported steps in).
                    for (src in PHONE_SOURCES) {
                        for (row in runCatching { repo.metricSeries(src, metric.key, FROM, TO) }.getOrDefault(emptyList())) {
                            if (!row.value.isFinite() || byDay.containsKey(row.day)) continue
                            byDay[row.day] = row.value
                            source[row.day] = src
                        }
                        for (row in ctx.phoneDaily[src].orEmpty()) {
                            val v = phoneColumn(metric.key, row) ?: continue
                            if (byDay.containsKey(row.day)) continue
                            byDay[row.day] = v
                            source[row.day] = src
                        }
                    }
                }
                else -> for (row in runCatching { repo.metricSeries(metric.source, metric.key, FROM, TO) }.getOrDefault(emptyList())) {
                    if (!row.value.isFinite()) continue
                    byDay[row.day] = row.value
                    source[row.day] = metric.source
                }
            }
            var series = byDay.entries.sortedBy { it.key }.map { it.key to it.value }
            // #1705: a skin_temp window holds one scale only (the newest reading's).
            if (metric.key == "skin_temp") {
                SkinTempDisplay.dominantKind(series.map { it.second })?.let { keep ->
                    series = series.filter { SkinTempDisplay.kind(it.second) == keep }
                }
            }
            var out = MetricPageSeries(series, source)

            if (metric.key == "vo2max_est" && provenance) {
                val attributed = HashMap<String, String>()
                for ((day, _) in series) {
                    val sourceId = source[day] ?: continue
                    val tag = runCatching { repo.scoreInputSource(sourceId, day, metric.key) }.getOrNull()
                    attributed[day] = vo2MaxAttributionSource(Vo2MaxEstimator.fromProvenanceId(tag))
                }
                out = out.copy(sourceByDay = attributed)
            }
            // #103: fill the calibrated SpO₂ series' missing days from the strap candidate while its
            // Experimental switch is on. Calibrated days always win.
            if (metric.key == "spo2" && metric.source == MetricCatalog.WHOOP && ctx.spo2CandidateOn) {
                val candidate = runCatching {
                    repo.metricSeriesComputedUnion(ctx.activeStrapId, "spo2_candidate", FROM, TO)
                }.getOrDefault(emptyList())
                if (candidate.isNotEmpty()) {
                    val merged = HashMap(out.series.toMap())
                    val sources = HashMap(out.sourceByDay)
                    for (row in candidate) {
                        if (merged.containsKey(row.day) || !row.value.isFinite()) continue
                        merged[row.day] = row.value
                        sources[row.day] = SPO2_CANDIDATE_ATTRIBUTION_SOURCE
                    }
                    out = MetricPageSeries(merged.entries.sortedBy { it.key }.map { it.key to it.value }, sources)
                }
            }
            if (metric.key == "skin_temp" && metric.source == MetricCatalog.WHOOP) {
                skinTemperature(metric, ctx.days, ctx.skinTemp)?.let { out = it }
            }
            out
        }

    /**
     * #1846 / #1848 / #1850: the skin-temperature series leads with the kind Settings asks for across the
     * whole history (a temperature by default), falls back to the other kind rather than going empty, and
     * says so when it does. An absolute may sit in either column (#622), so both count. Null when no night
     * carries either number.
     */
    internal fun skinTemperature(
        metric: MetricDescriptor,
        days: List<DailyMetric>,
        prefer: SkinTempDisplay.Kind,
    ): MetricPageSeries? {
        val anyAbsolute = days.any { row ->
            row.skinTempC != null || row.skinTempDevC?.let { VitalBands.isAbsoluteSkinTemp(it) } == true
        }
        val anyDeviation = days.any { row -> row.skinTempDevC?.let { !VitalBands.isAbsoluteSkinTemp(it) } == true }
        if (!anyAbsolute && !anyDeviation) return null
        val leadsAbsolute = when (prefer) {
            SkinTempDisplay.Kind.ABSOLUTE -> anyAbsolute
            SkinTempDisplay.Kind.DEVIATION -> !anyDeviation && anyAbsolute
        }
        val series = if (leadsAbsolute) {
            days.mapNotNull { row ->
                (row.skinTempC ?: row.skinTempDevC?.takeIf { VitalBands.isAbsoluteSkinTemp(it) })?.let { row.day to it }
            }
        } else {
            days.mapNotNull { row -> row.skinTempDevC?.takeIf { !VitalBands.isAbsoluteSkinTemp(it) }?.let { row.day to it } }
        }.sortedBy { it.first }
        val rowsWithEither = days.count { it.skinTempC != null || it.skinTempDevC != null }
        val note = when {
            shouldExplainSkinTempFallback(prefer, leadsAbsolute, anyAbsolute) -> SkinTempNote.FALLBACK_TO_DEVIATION
            shouldExplainShortenedSkinTempSeries(
                leadsAbsolute = leadsAbsolute, shownReadings = series.size, rowsWithEitherNumber = rowsWithEither,
            ) -> SkinTempNote.SHORTENED_TO_ABSOLUTE
            else -> null
        }
        return MetricPageSeries(series, series.associate { it.first to metric.source }, note)
    }

    /**
     * Which of [catalog]'s metrics hold at least one reading, asked cheaply: one distinct-key query per
     * source plus one in-memory pass over the daily rows, never a full series per metric.
     */
    suspend fun nonEmptyIds(catalog: List<MetricDescriptor>, ctx: MetricLoadContext): Set<String> =
        withContext(Dispatchers.IO) {
            val repo = ctx.repo
            suspend fun keys(id: String): Set<String> = runCatching { repo.metricKeys(id).toSet() }.getOrDefault(emptySet())
            val whoopKeys = (WhoopRepository.importedSourceIdsFor(ctx.activeStrapId) +
                WhoopRepository.computedSourceIdsFor(ctx.activeStrapId)).flatMap { keys(it) }.toSet()
            val phoneKeys = PHONE_SOURCES.flatMap { keys(it) }.toSet()
            val otherKeys = HashMap<String, Set<String>>()
            catalog.filter { it.source != MetricCatalog.WHOOP && it.source != MetricCatalog.PHONE }
                .map { it.source }.distinct().forEach { otherKeys[it] = keys(it) }
            catalog.filter { metric ->
                when (metric.source) {
                    MetricCatalog.WHOOP -> metric.key in whoopKeys ||
                        ctx.days.any { WhoopRepository.dailyColumn(metric.key, it) != null } ||
                        (metric.key == "skin_temp" && ctx.days.any { it.skinTempC != null }) ||
                        (metric.key == "spo2" && ctx.spo2CandidateOn && "spo2_candidate" in whoopKeys)
                    MetricCatalog.PHONE -> metric.key in phoneKeys ||
                        ctx.phoneDaily.values.any { rows -> rows.any { phoneColumn(metric.key, it) != null } }
                    else -> metric.key in otherKeys[metric.source].orEmpty()
                }
            }.map { it.id }.toSet()
        }

    /** Loads [metrics] together, a few at a time, off the main thread; by metric id, non-empty only. */
    suspend fun loadAll(
        metrics: List<MetricDescriptor>,
        ctx: MetricLoadContext,
        provenance: Boolean = false,
    ): Map<String, MetricPageSeries> = coroutineScope {
        val gate = Semaphore(6)
        metrics.map { metric ->
            async(Dispatchers.IO) { gate.withPermit { metric.id to load(metric, ctx, provenance) } }
        }.awaitAll().filter { it.second.series.isNotEmpty() }.toMap()
    }

    /**
     * The entry a link that names only [key] opens: of the sources recording it, the one All Metrics would
     * show (the freshest; ties by source priority). When none records it, the key's fallback (a measured
     * figure first, an on-device estimate otherwise); when nothing at all does, the catalogue's first entry.
     */
    suspend fun resolve(key: String, ctx: MetricLoadContext): MetricDescriptor? {
        for (k in listOfNotNull(key, MetricCatalog.fallbackKey(key))) {
            val candidates = MetricCatalog.byKey(k)
            val nonEmpty = nonEmptyIds(candidates, ctx)
            val withData = candidates.filter { it.id in nonEmpty }
            if (withData.isEmpty()) continue
            if (withData.size == 1) return withData.first()
            val latest = loadAll(withData, ctx).mapValues { it.value.series.last().first }
            return AllMetricsCatalog.oneSourcePerKey(withData.filter { it.id in latest }, latest).firstOrNull()
                ?: withData.first()
        }
        return MetricCatalog.byKey(key).firstOrNull()
    }
}
