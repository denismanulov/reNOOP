package com.noop.ui.summary

import android.content.Context
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.Baselines
import com.noop.analytics.DayCycleMode
import com.noop.analytics.ReadinessEngine
import com.noop.analytics.SkinTempDisplay
import com.noop.analytics.StrainScorer
import com.noop.data.AppleDaily
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.ui.ActiveDayCycle
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.PersistedSegment
import com.noop.ui.ProfileStore
import com.noop.ui.Stages
import com.noop.ui.TemperatureUnit
import com.noop.ui.UnitPrefs
import com.noop.ui.activeDayCycleStart
import com.noop.ui.buildSleepModel
import com.noop.ui.freshRestScore
import com.noop.ui.heroDisplay
import com.noop.ui.lastHrvRow
import com.noop.ui.lastRespRow
import com.noop.ui.lastRestingHrRow
import com.noop.ui.lastScoredRecoveryDay
import com.noop.ui.lastSkinTempReadingRow
import com.noop.ui.lastSpo2Row
import com.noop.ui.lastVitalsRow
import com.noop.ui.sleep.SleepNap
import com.noop.ui.sleep.SleepNightsLoader
import com.noop.ui.localDayString
import com.noop.ui.recoveryCalibrationNights
import com.noop.ui.resolveSkinTempReading
import com.noop.ui.selectNight
import com.noop.ui.stepsForDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

// MARK: - Summary loader (twin of iOS SummaryLoader)
//
// One read of everything the Summary shows for a picked day. Every read and precedence rule is the one the
// old Today home used (the Charge carry, live Effort, the freshness-gated Rest, the per-field vital carries,
// imported-first steps and calories), so moving from Today to the Summary changes no number; only the
// presentation changed. The result is a plain value the screen lays out without touching the repository.

/** Everything the Summary draws for one picked day. */
data class SummarySnapshot(
    val loaded: Boolean = false,
    /** The picked day's key the reads used. */
    val dayKey: String = "",
    val charge: ChargeDisplay = ChargeDisplay.NoData,
    /** Effort on the stored 0–100 axis (today's live in-progress score when it beats the row). */
    val effort: Double? = null,
    /** Sleep performance, 0–100. */
    val rest: Double? = null,
    val metrics: SummaryMetricInputs? = null,
    /** The trailing week one slot per day (null where a day has none), oldest first, by series key. */
    val dailySeries: Map<String, List<Double?>> = emptyMap(),
    /** The week's day keys, oldest first: the slots of [dailySeries]. */
    val weekKeys: List<String> = emptyList(),
    val highlights: List<SummaryHighlight> = emptyList(),
) {
    /** The same week with the empty days dropped, for the pinned cards' mini charts. */
    fun series(key: String): List<Double> = dailySeries[key].orEmpty().filterNotNull()
}

/** The night that ended on the picked day, as the Sleep card shows it. */
internal data class SummarySleepNight(
    /** The picked day's key, the night the card opens on the Sleep tab. */
    val wakeDayKey: String,
    /** When the night ended (unix seconds): the card's stamp. */
    val wakeTs: Long,
    /** When the night's main block began (unix seconds). */
    val bedTs: Long,
    val stages: Stages,
    /** The night's timestamped stage segments, for the compact chart; null when none were stored. */
    val segments: List<PersistedSegment>?,
    /** The day's naps, each a row under the Sleep card that opens the nap's own page. */
    val naps: List<SleepNap> = emptyList(),
)

internal object SummaryLoader {
    const val SERIES_DAYS = 7
    private const val FROM = "0000-00-00"
    private const val TO = "9999-99-99"
    private val PHONE_SOURCES = listOf(WhoopRepository.APPLE_HEALTH_SOURCE, WhoopRepository.HEALTH_CONNECT_SOURCE)

    /**
     * Everything for the day [offset] days before [today]. [today] is the screen's one [AppToday]: the
     * pager title, the day key, the week's slots and every stamp count from it, so none of them can name a
     * different day than the others.
     */
    suspend fun load(
        vm: AppViewModel,
        context: Context,
        offset: Int,
        days: List<DailyMetric>,
        todayRow: DailyMetric?,
        activeDayCycle: ActiveDayCycle?,
        spo2CandidateByDay: Map<String, Double>,
        today: AppToday,
    ): SummarySnapshot = withContext(Dispatchers.IO) {
        val repo = vm.repo
        val active = vm.activeStrapId
        val isToday = offset == 0
        val logical = today.minusDays(offset)
        val dayKey = logical.toString()
        val mode = NoopPrefs.dayCycleMode(context)
        // The cached today row only while it is still today's (it lags the 04:00 roll until the next read).
        val rawDay = (if (isToday) todayRow?.takeIf { it.day == dayKey } else null) ?: days.lastOrNull { it.day == dayKey }
        // Steps alone follow the sleep-onset cycle today, as they did on Today.
        val cycleSteps = activeDayCycle?.steps
        val day = if (isToday && mode == DayCycleMode.SLEEP_ONSET && cycleSteps != null) rawDay?.copy(steps = cycleSteps) else rawDay
        val tkey = day?.day ?: dayKey
        // #547: the carry never selects a day past the later of the logical today and the calendar today.
        val carryToday = maxOf(today.key, today.calendarKey)

        // Charge: scored → calibrating → a real prior night's carry → nothing.
        val hrvEpoch = NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble()
        val calNights = if (isToday) recoveryCalibrationNights(days, day?.recovery != null, hrvEpoch) else null
        val prior = lastScoredRecoveryDay(
            days = days, selectedDayKey = tkey, isToday = isToday, todayScored = day?.recovery != null,
            isCalibrating = calNights != null, today = carryToday,
        )
        val charge = ChargeDisplay.resolve(day?.recovery, prior, calNights, carryToday)

        // Effort: today scores the in-progress window live; the stored row is the floor.
        val live = if (isToday) liveTodayStrain(vm, context, day, logical, mode, activeDayCycle) else null
        val effort = StrainScorer.effectiveEffort(live, day?.strain)

        suspend fun resolved(key: String): Map<String, Double> = runCatching {
            repo.resolvedSeries(key, WhoopRepository.WHOOP_SOURCE, FROM, TO, strapDeviceId = active)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())

        val restByDay = resolved("sleep_performance")
        val latestRest = restByDay.entries.maxByOrNull { it.key }
        val rest = freshRestScore(
            todayValue = restByDay[dayKey], lastDay = latestRest?.key, lastValue = latestRest?.value,
            isTodaySelected = isToday, today = dayKey,
        )
        val stepsEstByDay = resolved("steps_est")
        val onDeviceKcalByDay = resolved("active_kcal")
        val phoneRows: Map<String, List<AppleDaily>> = PHONE_SOURCES.associateWith { src ->
            runCatching { repo.appleDaily(src, "0000-01-01", "9999-12-31") }.getOrDefault(emptyList())
        }
        val apple = phoneRows[WhoopRepository.APPLE_HEALTH_SOURCE].orEmpty()
        val hc = phoneRows[WhoopRepository.HEALTH_CONNECT_SOURCE].orEmpty()
        val weightByDay = weightSeries(repo, phoneRows)

        // Today-only carries so the vitals don't blank between the 04:00 rollover and tonight's sleep.
        val carryKey = maxOf(day?.day ?: "", carryToday)
        val vitalsDay = if (isToday) lastVitalsRow(days, carryKey) else null
        val skinCarry = if (isToday) lastSkinTempReadingRow(days, carryKey) else null
        val skinRow = listOfNotNull(day, vitalsDay, skinCarry).firstOrNull { it.skinTempC != null || it.skinTempDevC != null }
        val skinPrefer = UnitPrefs.skinTempPreferred(context)
        // The Summary's Skin Temp card leads with the same reading Today's tile did, off the same row.
        val skinReading = resolveSkinTempReading(day, vitalsDay, skinCarry, skinPrefer)

        val phoneToday = (apple + hc).filter { it.day == dayKey }
        val phoneWeightToday = phoneToday.mapNotNull { it.weightKg }.maxOrNull()
        val lastWeight = weightByDay.entries.filter { it.key <= dayKey }.maxByOrNull { it.key }
        val unitSystem = UnitPrefs.system(context)
        val profile = ProfileStore.from(context)
        val inputs = SummaryMetricInputs(
            day = day,
            vitalsDay = vitalsDay,
            respDay = if (isToday) lastRespRow(days, carryKey) else null,
            hrvDay = if (isToday) lastHrvRow(days, carryKey) else null,
            restingHrDay = if (isToday) lastRestingHrRow(days, carryKey) else null,
            spo2Day = if (isToday) lastSpo2Row(days, carryKey) else null,
            skinTempReading = skinReading,
            spo2Candidate = spo2CandidateByDay[tkey],
            importedSteps = stepsForDay(apple, hc, dayKey),
            stepsEstimate = stepsEstByDay[dayKey],
            importedActiveKcal = phoneToday.mapNotNull { it.activeKcal?.takeIf { k -> k > 0 } }.maxOrNull(),
            onDeviceKcal = onDeviceKcalByDay[dayKey],
            healthWeightKg = phoneWeightToday ?: lastWeight?.value,
            profileWeightKg = profile.weightKg,
            unitSystem = unitSystem,
            fahrenheit = UnitPrefs.temperature(context) == TemperatureUnit.FAHRENHEIT,
            dayKey = tkey,
            healthWeightDay = if (phoneWeightToday != null) dayKey else lastWeight?.key,
            skinTempDay = skinRow?.day,
        )

        // The trailing week each card draws, ending on the picked day.
        val weekKeys = (SERIES_DAYS - 1 downTo 0).map { logical.minusDays(it.toLong()).toString() }
        val rowByDay = days.associateBy { it.day }
        val phoneByDay = HashMap<String, Pair<Int?, Double?>>()
        for (r in apple + hc) {
            if (r.day !in weekKeys) continue
            val prev = phoneByDay[r.day]
            phoneByDay[r.day] = listOfNotNull(prev?.first, r.steps).maxOrNull() to
                listOfNotNull(prev?.second, r.activeKcal?.takeIf { it > 0 }).maxOrNull()
        }
        fun week(value: (String) -> Double?): List<Double?> = weekKeys.map(value)
        val skinAbsolute = skinReading?.kind == SkinTempDisplay.Kind.ABSOLUTE
        val dailySeries = mapOf(
            "hrv" to week { rowByDay[it]?.avgHrv },
            "rhr" to week { rowByDay[it]?.restingHr?.toDouble() },
            "spo2" to week { rowByDay[it]?.spo2Pct },
            "spo2_candidate" to week { spo2CandidateByDay[it] },
            "resp_rate" to week { rowByDay[it]?.respRateBpm },
            "skin_temp" to week { k ->
                rowByDay[k]?.let { if (skinAbsolute) it.skinTempC else it.skinTempDevC }
            },
            "steps" to week { k ->
                (if (k == tkey) day?.steps else rowByDay[k]?.steps)?.toDouble()
                    ?: phoneByDay[k]?.first?.toDouble() ?: stepsEstByDay[k]
            },
            "energy_kcal" to week { k -> phoneByDay[k]?.second ?: onDeviceKcalByDay[k] ?: rowByDay[k]?.activeKcalEst },
            "weight" to week { weightByDay[it] },
            // Today's bar is the live Effort the rings show, not the row the daily pass last stored.
            "effort" to week { k -> if (k == tkey) effort else rowByDay[k]?.strain },
        )

        SummarySnapshot(
            loaded = true,
            dayKey = dayKey,
            charge = charge,
            effort = effort,
            rest = rest,
            metrics = inputs,
            dailySeries = dailySeries,
            weekKeys = weekKeys,
            highlights = SummaryHighlight.from(runCatching { ReadinessEngine.evaluate(days, day?.day) }.getOrNull()),
        )
    }

    /** Phone weight by day: each source's series first, then its daily aggregate; the first source wins. */
    private suspend fun weightSeries(repo: WhoopRepository, phoneRows: Map<String, List<AppleDaily>>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (src in PHONE_SOURCES) {
            for (row in runCatching { repo.metricSeries(src, "weight", "0000-01-01", "9999-12-31") }.getOrDefault(emptyList())) {
                if (row.value.isFinite() && !out.containsKey(row.day)) out[row.day] = row.value
            }
            for (row in phoneRows[src].orEmpty()) {
                val kg = row.weightKg?.takeIf { it.isFinite() } ?: continue
                if (!out.containsKey(row.day)) out[row.day] = kg
            }
        }
        return out
    }

    /**
     * Today's in-progress Effort over the window the daily pass scores (the sleep-onset cycle when that
     * mode is on, else the logical day's midnight → now), with the same HR-max, resting-HR, method and sex.
     * Null below the scorer's minimum readings, so the stored row stands rather than a made-up value.
     */
    private suspend fun liveTodayStrain(
        vm: AppViewModel,
        context: Context,
        day: DailyMetric?,
        logical: LocalDate,
        mode: DayCycleMode,
        cycle: ActiveDayCycle?,
    ): Double? = runCatching {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000
        val start = activeDayCycleStart(
            mode = mode,
            confirmedOrSyntheticOnset = cycle?.onsetTs,
            calendarStart = logical.atStartOfDay(zone).toEpochSecond(),
        )
        val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, start, now, limit = 200_000)
        val profile = ProfileStore.from(context)
        val maxHr = profile.hrMaxOverride.takeIf { it > 0 }?.toDouble()
            ?: if (profile.age > 0) StrainScorer.tanakaHRmax(profile.age.toDouble()) else null
        StrainScorer.strain(
            hr = hr,
            maxHR = maxHr,
            restingHR = day?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR,
            method = NoopPrefs.effortMethod(context),
            sex = profile.sex,
        )
    }.getOrNull()

    /** The newest heart-rate sample on record for the active strap (unix seconds), or null with none. */
    suspend fun newestDataTs(vm: AppViewModel): Long? = withContext(Dispatchers.IO) {
        runCatching { vm.repo.latestHrSampleTsUnion(vm.activeStrapId) }.getOrNull()
    }

    // MARK: - The Sleep card's night

    private data class SleepMemo(val key: List<Any?>, val sleeps: List<SleepSession>, val habitual: Long?)

    @Volatile private var sleepMemo: SleepMemo? = null

    /**
     * The night that ended on [wakeDayKey], resolved the way the Sleep tab resolves its nights (the merged
     * imported ∪ on-device sessions, grouped by local wake day, the main block by the learned midsleep), so
     * the card and the page it opens show the same night with the same figures. Null when no night ended
     * that day. The session read is reused while the day history is unchanged.
     */
    suspend fun sleepNight(vm: AppViewModel, days: List<DailyMetric>, wakeDayKey: String): SummarySleepNight? =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = vm.activeStrapId
                val memoKey = listOf(active, days.size, days.lastOrNull())
                val memo = sleepMemo?.takeIf { it.key == memoKey } ?: run {
                    val now = System.currentTimeMillis() / 1000L
                    val imported = vm.repo.sleepSessionsUnion(active, 0L, now)
                    val computed = vm.repo.computedSleepSessionsUnion(active, 0L, now)
                    fun localEndDay(ts: Long): String {
                        val offsetSec = (java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000).toLong()
                        return AnalyticsEngine.dayString(ts, offsetSec)
                    }
                    val sleeps = WhoopRepository.mergeSleepRichness(imported, computed) { localEndDay(it.endTs) }
                        .sortedBy { it.effectiveStartTs }
                    SleepMemo(memoKey, sleeps, runCatching { vm.repo.habitualMidsleepSec(active) }.getOrNull())
                        .also { sleepMemo = it }
                }
                val navDays = memo.sleeps.groupBy { localDayString(it.endTs) }
                    .toSortedMap(reverseOrder())
                    .map { (_, blocks) -> blocks.sortedBy { it.effectiveStartTs } }
                val index = navDays.indexOfFirst { blocks -> blocks.any { localDayString(it.endTs) == wakeDayKey } }
                if (index < 0) return@runCatching null
                val night = selectNight(navDays, days, index, memo.habitual) ?: return@runCatching null
                val model = buildSleepModel(
                    days, night.session, selectedDay = night.dayKey,
                    heroStages = night.groupStages, heroSegments = night.groupSegments, sessions = memo.sleeps,
                )
                val display = heroDisplay(model, night) ?: return@runCatching null
                SummarySleepNight(
                    wakeDayKey = wakeDayKey,
                    wakeTs = night.heroWakeTs ?: night.session.endTs,
                    bedTs = night.session.effectiveStartTs,
                    stages = display.stages,
                    segments = display.hypnogramSegments,
                    naps = night.napBlocks.map { SleepNightsLoader.nap(it) },
                )
            }.getOrNull()
        }
}
