package com.noop.ui.sleep

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.SleepStageTotals
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.ui.AppViewModel
import com.noop.ui.HeroNight
import com.noop.ui.ImportedSleepSeries
import com.noop.ui.SleepModel
import com.noop.ui.Stages
import com.noop.ui.buildSleepModel
import com.noop.ui.fallbackSleepModel
import com.noop.ui.localDayString
import com.noop.ui.napSleepMinutesByDay
import com.noop.ui.parsePersistedSegments
import com.noop.ui.parseSessionStages
import com.noop.ui.selectNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

// MARK: - The nights every Sleep page reads
//
// One load for the Sleep tab and the Sleep History page it pushes: the merged imported ∪ on-device sessions
// grouped by local wake day (newest first), each day's main night picked by the learned midsleep
// (`selectNight`, the rule the analytics rollup uses), and every night decoded once into a
// [SleepNightDetail]. The pushed page reuses the tab's load while the day history is unchanged.

/**
 * A nap: a block of the day outside its main night, decoded for its own page. [stages] is null when the block
 * stored none; its time asleep is then unknown, and the page shows the block's two times without one (the
 * minutes between them are time in bed, the rule `napSleepMinutesByDay` follows).
 */
internal data class SleepNap(
    val session: SleepSession,
    val startTs: Long,
    val endTs: Long,
    val stages: Stages?,
    /** Spans from [startTs]; empty when the nap carries only stage totals, or none. */
    val spans: List<SleepStageSpan>,
) {
    val asleepMin: Double? get() = stages?.asleep
    val windowMin: Double get() = (endTs - startTs) / 60.0
}

/** Everything the Sleep pages draw, decoded once per data change. */
internal class SleepNights(
    val days: List<DailyMetric>,
    val sleeps: List<SleepSession>,
    /** Every recorded block grouped by local wake day, newest day first. */
    val navDays: List<List<SleepSession>>,
    val habitualMidsleepSec: Long?,
    /** One per [navDays] entry (same index); null for a day whose night has no sleep. */
    val details: List<SleepNightDetail?>,
    val imported: ImportedSleepSeries,
    /** The latest-anchored sleep model: the typical stage minutes and the sleep-debt ledger. */
    val model: SleepModel?,
) {
    /** Every night with sleep, oldest first. */
    val entries: List<SleepNightEntry> by lazy {
        details.filterNotNull().sortedBy { it.day }.map { it.entry }
    }

    /** Decoded nights, oldest first. */
    val nights: List<SleepNightDetail> by lazy { details.filterNotNull().sortedBy { it.day } }

    /** The night at [offset] (0 = newest) as the selector resolves it, for editing. */
    fun heroNight(offset: Int): HeroNight? = selectNight(navDays, days, offset, habitualMidsleepSec)

    /** The day row a night is scored from. */
    fun dailyRow(dayKey: String): DailyMetric? = days.lastOrNull { it.day == dayKey }

    /** The navDays index of the night that ended on [wakeDayKey], or -1. */
    fun index(wakeDayKey: String): Int =
        navDays.indexOfFirst { blocks -> blocks.any { localDayString(it.endTs) == wakeDayKey } }

    companion object {
        val EMPTY = SleepNights(emptyList(), emptyList(), emptyList(), null, emptyList(), ImportedSleepSeries(), null)
    }
}

internal object SleepNightsLoader {
    /** Bumped after an edit, a delete, an undo or an added nap, so every Sleep page reloads. */
    var revision by mutableIntStateOf(0)

    private data class Memo(val key: List<Any?>, val nights: SleepNights)

    @Volatile private var memo: Memo? = null

    /** Reload after a change the day history may not show yet. */
    fun invalidate() {
        memo = null
        revision++
    }

    suspend fun load(vm: AppViewModel, days: List<DailyMetric>): SleepNights = withContext(Dispatchers.IO) {
        val key = listOf(vm.activeStrapId, days.size, days.lastOrNull(), revision)
        memo?.takeIf { it.key == key }?.let { return@withContext it.nights }
        val nights = runCatching { read(vm, days) }.getOrDefault(SleepNights.EMPTY)
        memo = Memo(key, nights)
        nights
    }

    private suspend fun read(vm: AppViewModel, days: List<DailyMetric>): SleepNights {
        val active = vm.activeStrapId
        val now = System.currentTimeMillis() / 1000L
        // Read the active-strap ∪ canonical "my-whoop" union (#814/#1008); imported wins per local wake
        // day with the #241 richness exception, sorted by the effective onset (PR #395).
        val imported = vm.repo.sleepSessionsUnion(active, 0L, now)
        val computed = vm.repo.computedSleepSessionsUnion(active, 0L, now)
        fun localEndDay(ts: Long): String {
            val offsetSec = (java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000).toLong()
            return AnalyticsEngine.dayString(ts, offsetSec)
        }
        val sleeps = WhoopRepository.mergeSleepRichness(imported, computed) { localEndDay(it.endTs) }
            .sortedBy { it.effectiveStartTs }
        val habitual = runCatching { vm.repo.habitualMidsleepSec(active) }.getOrNull()
        val navDays = sleeps.groupBy { localDayString(it.endTs) }
            .toSortedMap(reverseOrder())
            .map { (_, blocks) -> blocks.sortedBy { it.effectiveStartTs } }

        suspend fun series(key: String) = runCatching {
            vm.repo.metricSeries("my-whoop", key, "0000-00-00", "9999-99-99")
        }.getOrDefault(emptyList()).associate { it.day to it.value }
        val importedSeries = ImportedSleepSeries(
            performance = series("sleep_performance"),
            consistency = series("sleep_consistency"),
            needMin = series("sleep_need_min"),
            debtMin = series("sleep_debt_min"),
        )
        val details = navDays.indices.map { i ->
            selectNight(navDays, days, i, habitual)?.let { detail(it, i, days) }
        }
        val naps = napSleepMinutesByDay(sleeps, habitual)
        val model = buildSleepModel(days, null, importedSeries, napSleepMinByDay = naps, sessions = sleeps)
            ?: fallbackSleepModel(days, importedSeries, naps, sessions = sleeps)
        return SleepNights(days, sleeps, navDays, habitual, details, importedSeries, model)
    }

    /**
     * One night decoded: the whole bridged night's window, its stage minutes (the group's decoded minutes,
     * else the session's, else the day row's) and its timestamped stages when the night stored them.
     */
    fun detail(night: HeroNight, navIndex: Int, days: List<DailyMetric>): SleepNightDetail? {
        val session = night.session
        val onset = night.heroOnsetTs ?: session.effectiveStartTs
        val wake = night.heroWakeTs ?: session.endTs
        val clamped = SleepStageTotals.clampStagesToOnset(session.stagesJSON, session.effectiveStartTs)
        val decoded = night.groupStages ?: parseSessionStages(clamped)
        val row = days.lastOrNull { it.day == night.dayKey }
        val stages = if (decoded != null) {
            Stages(awake = decoded.awake, light = decoded.light, deep = decoded.deep, rem = decoded.rem)
        } else {
            row?.let { stagesFromRow(it) } ?: return null
        }
        if (stages.asleep <= 0.0) return null
        val segments = night.groupSegments ?: parsePersistedSegments(clamped)
        return SleepNightDetail(
            navIndex = navIndex,
            day = localDate(wake),
            onsetTs = onset,
            wakeTs = wake,
            stages = stages,
            spans = stageSpans(segments, onset),
        )
    }

    /** One nap block decoded: its own window and, when it stored them, its stages. */
    fun nap(session: SleepSession): SleepNap {
        val start = session.effectiveStartTs
        val clamped = SleepStageTotals.clampStagesToOnset(session.stagesJSON, start)
        val stages = parseSessionStages(clamped)
            ?.let { Stages(awake = it.awake, light = it.light, deep = it.deep, rem = it.rem) }
            ?.takeIf { it.asleep > 0.0 }
        val spans = if (stages == null) emptyList() else stageSpans(parsePersistedSegments(clamped), start)
        return SleepNap(session, start, session.endTs, stages, spans)
    }

    /** A night's stages from its day row alone: awake is the in-bed time its efficiency implies. */
    fun stagesFromRow(row: DailyMetric): Stages? {
        val deep = row.deepMin ?: 0.0
        val rem = row.remMin ?: 0.0
        val light = row.lightMin ?: 0.0
        if (deep + rem + light <= 0.0) return null
        val asleep = deep + rem + light
        val eff = row.efficiency?.let { if (it > 1.0) it / 100.0 else it }
        val awake = when {
            eff != null && eff in 0.01..0.999 -> max(0.0, asleep / eff - asleep)
            row.disturbances != null -> row.disturbances * 6.0
            else -> 0.0
        }
        return Stages(awake = awake, light = light, deep = deep, rem = rem)
    }
}
