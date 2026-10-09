package com.noop.friends

import com.noop.NoopApplication
import com.noop.analytics.DayCycleIntelligenceIntegration
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.ui.ActiveDayCycle
import com.noop.ui.AppToday
import com.noop.ui.ProfileStore
import com.noop.ui.WorkoutEditing
import com.noop.ui.WorkoutListLoader
import com.noop.ui.effectiveActiveStrapId
import com.noop.ui.logicalDayKeyNow
import com.noop.ui.resolveActiveDayCycle
import com.noop.ui.resolveTodayRow
import com.noop.ui.sleep.SleepScore
import com.noop.ui.summary.ChargeDisplay
import com.noop.ui.summary.SummaryLoader
import com.noop.ui.workouts.activeSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// MARK: - Friends: reading a day's figures out of the app
//
// The one place the upload touches the app's data, and it derives nothing: every figure is asked of the
// resolver the wearer's own screens use, so a friend sees what the wearer sees.
//
//   recovery, strain, sleepScore, restingBpm  SummaryLoader.load (the Summary's rings and Resting HR row)
//   sleep                                     SummaryLoader.sleepNight (the Summary's Sleep card, itself the
//                                             Sleep tab's night), need = SleepScore.TARGET_MIN
//   workouts                                  WorkoutListLoader.load (the Workouts tab's list)
//   heart rate                                WhoopRepository.hrSamplesUnion (the samples the Summary's live
//                                             Strain is scored from)
//
// Runs with no screen alive (a WorkManager worker after a strap sync), so the inputs a screen would take
// from AppViewModel are rebuilt here the way the view model builds them: the merged recent days, today's
// row, the active day cycle and the app's one "today".

internal object FriendsDaySource {
    /** How many days go up on each run: today and yesterday. */
    const val DAYS = 2

    /** A day's key ("yyyy-MM-dd", the app's own logical day) and what the app holds for it. */
    class Day(val key: String, val inputs: FriendsDayInputs)

    /**
     * Today and yesterday, newest first. Only the sections [share] has on are read at all: a switched-off
     * section costs no query and exists nowhere outside the database.
     */
    suspend fun recentDays(app: NoopApplication, share: FriendShare): List<Day> = withContext(Dispatchers.IO) {
        val context = app.applicationContext
        val repo = app.repository
        // The two ids AppViewModel reads with: `deviceId` (its day history) and `activeStrapId` (the rest).
        val deviceId = app.activeDeviceId
        val published = app.sourceCoordinator.activeDeviceId.value
        val active = published ?: deviceId
        // AppViewModel.recentDays, .today and .activeDayCycle, in that order.
        val days = repo.recentDaysMergedFlow(deviceId).first()
        val todayRow = resolveTodayRow(days, logicalDayKeyNow(), LocalDate.now().toString())
        val cycle = activeDayCycle(repo, effectiveActiveStrapId(published, deviceId), days)
        val today = AppToday.now(todayRow?.day)
        val zone = ZoneId.systemDefault()
        val nowTs = System.currentTimeMillis() / 1000L

        val needsSummary = share.scores || share.hr
        val allWorkouts = if (share.workouts) {
            runCatching { WorkoutListLoader.load(context, repo, deviceId, ProfileStore.from(context)) }.getOrNull()
        } else null

        (0 until DAYS).map { offset ->
            val date = today.minusDays(offset)
            val key = date.toString()
            val snapshot = if (needsSummary) {
                // The SpO2 candidate map only feeds the Blood Oxygen card, which is not shared.
                SummaryLoader.load(repo, active, context, offset, days, todayRow, cycle, emptyMap(), today)
            } else null
            val night = if (share.sleep) SummaryLoader.sleepNight(repo, active, days, key) else null
            val dayStart = date.atStartOfDay(zone).toEpochSecond()
            val dayEnd = minOf(date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1, nowTs)
            val hr = if (share.hr && dayEnd >= dayStart) {
                runCatching { repo.hrSamplesUnion(active, dayStart, dayEnd, limit = HR_READ_LIMIT) }
                    .getOrDefault(emptyList())
                    .map { FriendHrPoint(it.ts, it.bpm) }
            } else emptyList()

            Day(
                key,
                FriendsDayInputs(
                    // A prior night carried onto an unscored today is that night's score, not today's: the
                    // Summary says so beside it, and a day on the wire has nowhere to say it.
                    recovery = (snapshot?.charge as? ChargeDisplay.Scored)?.value.takeIf { share.scores },
                    strain = snapshot?.effort.takeIf { share.scores },
                    sleepScore = snapshot?.restOfDay.takeIf { share.scores },
                    sleep = night?.let {
                        FriendsSleepInput(
                            startTs = it.bedTs,
                            endTs = it.wakeTs,
                            asleepMin = it.stages.asleep,
                            awakeMin = it.stages.awake,
                            remMin = it.stages.rem,
                            lightMin = it.stages.light,
                            deepMin = it.stages.deep,
                            needMin = SleepScore.TARGET_MIN,
                        )
                    },
                    workouts = allWorkouts?.filter { localDay(it.startTs, zone) == date }?.map(::workout),
                    hrSamples = hr,
                    // The day's own row only, for the same reason as Recovery above.
                    restingBpm = snapshot?.metrics?.day?.restingHr.takeIf { share.hr },
                ),
            )
        }
    }

    /** One row of the Workouts list as it goes to a friend: its English label, active time and stored Effort. */
    internal fun workout(row: WorkoutRow) = FriendsWorkoutInput(
        startTs = row.startTs,
        sport = WorkoutEditing.displaySport(row.sport),
        durationS = activeSeconds(row),
        strain = row.strain,
        avgHr = row.avgHr,
        maxHr = row.maxHr,
        kcal = row.energyKcal,
    )

    private fun localDay(ts: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()

    /** AppViewModel.activeDayCycle read once: the newest sleep-onset boundary among the visible days. */
    private suspend fun activeDayCycle(repo: WhoopRepository, activeId: String, days: List<DailyMetric>): ActiveDayCycle? {
        if (days.isEmpty()) return null
        val from = days.first().day
        val to = days.last().day
        return runCatching {
            resolveActiveDayCycle(
                days,
                repo.computedDailyUnionFlow(activeId, from, to).first(),
                repo.metricSeriesComputedUnionFlow(activeId, DayCycleIntelligenceIntegration.ONSET_KEY, from, to).first(),
                System.currentTimeMillis() / 1_000L,
            )
        }.getOrNull()
    }

    /** A day at one sample a second is 86 400 rows; the same ceiling the Summary's live Strain reads under. */
    private const val HR_READ_LIMIT = 200_000
}
