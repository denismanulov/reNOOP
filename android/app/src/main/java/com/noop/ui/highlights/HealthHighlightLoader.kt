package com.noop.ui.highlights

import android.content.Context
import com.noop.R
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.ui.AppViewModel
import com.noop.ui.m3.MetricHue
import com.noop.ui.m3.metricHue
import com.noop.ui.metric.AllMetricsCatalog
import com.noop.ui.metric.HealthCategory
import com.noop.ui.metric.MetricCatalog
import com.noop.ui.metric.MetricDescriptor
import com.noop.ui.metric.MetricHealthStyle
import com.noop.ui.metric.MetricSeriesLoader
import com.noop.ui.metric.MetricToken
import com.noop.ui.metric.MetricUnits
import com.noop.ui.metric.localizedUnit
import com.noop.ui.metric.metricTokens
import com.noop.ui.metric.metricUnits
import com.noop.ui.sleep.SleepNightEntry
import com.noop.ui.sleep.SleepNightsLoader
import com.noop.ui.uiString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// MARK: - Highlights loader (twin of iOS HealthHighlightLoader)
//
// Reads what each card needs and resolves it into cards ready to draw. One loader for every place a highlight
// shows (the Summary, a metric's page, Sleep, the Highlights pages), so the same card never reads two ways.
// The arithmetic lives in [HealthHighlightMath].
//
// Two Android differences. The steps subject merges the strap's measured count, the phone's and the strap's
// estimate per day, as iOS `resolvedSteps` does, from Android's own sources (Health Connect for the phone).
// Today against a usual day reads the strap's step counter only: nothing on Android stores the phone's steps
// by the hour, so a phone-only wearer has the week, month and year cards without that one.

/** What one read needs beyond the view model: the reader's units, language, clock and week. */
class HighlightContext(
    val units: MetricUnits,
    val locale: Locale,
    val zone: ZoneId = ZoneId.systemDefault(),
    val nowTs: Long = System.currentTimeMillis() / 1000,
) {
    val today: LocalDate get() = Instant.ofEpochSecond(nowTs).atZone(zone).toLocalDate()
    val firstDayOfWeek get() = WeekFields.of(locale).firstDayOfWeek

    fun dayKey(ts: Long): String = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()

    companion object {
        fun of(context: Context): HighlightContext =
            HighlightContext(metricUnits(context), context.resources.configuration.locales[0])
    }
}

object HealthHighlightLoader {

    /** A workout or a night counts as "your last" for this long; after it the card would be old news. */
    const val RECENT_SECONDS = 7 * 86_400L

    /** The cards a page leads with; the rest are behind "Show All". */
    const val LEAD_CARDS = 3

    // MARK: Everything

    /** Every subject that has a highlight, in the order the All Highlights page lists them. */
    suspend fun all(vm: AppViewModel, context: Context, hc: HighlightContext = HighlightContext.of(context)): List<HighlightGroup> =
        HighlightSubject.entries.mapNotNull { group(it, vm, context, hc) }

    /**
     * One subject's highlights. [metric] reads that one catalogue entry's own series instead of the
     * subject's merged one, so a metric page and the page its "Show All" opens read the same figures.
     */
    suspend fun group(
        subject: HighlightSubject,
        vm: AppViewModel,
        context: Context,
        hc: HighlightContext = HighlightContext.of(context),
        metric: MetricDescriptor? = null,
    ): HighlightGroup? = runCatching {
        when (subject) {
            HighlightSubject.STEPS, HighlightSubject.ENERGY, HighlightSubject.SLEEP -> {
                val (read, series) = if (metric != null) {
                    metric to MetricSeriesLoader.load(metric, MetricSeriesLoader.context(vm, context), provenance = false).series
                } else {
                    subjectSeries(subject, vm, context) ?: return@runCatching null
                }
                metric(subject, read, series, vm, hc)
            }
            HighlightSubject.WORKOUTS -> workouts(vm.workouts.value, vm, hc)
            HighlightSubject.HEART_RATE -> heartRate(vm.workouts.value, vm, hc)
        }
    }.getOrNull()

    /** The few the Summary leads with: what is new today first, then the week. */
    fun summary(groups: List<HighlightGroup>, limit: Int = LEAD_CARDS): List<HighlightItem> {
        val seen = HashSet<String>()
        val out = ArrayList<HighlightItem>()
        for (cadence in listOf(HighlightCadence.DAILY, HighlightCadence.WEEKLY)) {
            for (group in groups) {
                // A workout's heart rate files under both Workouts and Heart Rate; one card is enough.
                for (item in group.items(cadence)) if (seen.add(item.sentence)) out += item
            }
        }
        return out.take(limit)
    }

    /**
     * The metric a subject reads and its whole daily series: steps merged per day from the strap's count,
     * the phone's and the strap's estimate; calories from the phone when it has any, else the strap's
     * estimate; time asleep from the nights.
     */
    private suspend fun subjectSeries(
        subject: HighlightSubject,
        vm: AppViewModel,
        context: Context,
    ): Pair<MetricDescriptor, List<Pair<String, Double>>>? {
        val ctx = MetricSeriesLoader.context(vm, context)
        suspend fun series(key: String, source: String): List<Pair<String, Double>> =
            MetricCatalog.metric(key, source)?.let { MetricSeriesLoader.load(it, ctx, provenance = false).series }.orEmpty()
        return when (subject) {
            HighlightSubject.STEPS -> {
                val metric = MetricCatalog.metric("steps", MetricCatalog.WHOOP) ?: return null
                val byDay = LinkedHashMap<String, Double>()
                for (layer in listOf(
                    series("steps", MetricCatalog.WHOOP), series("steps", MetricCatalog.PHONE),
                    series("steps_est", MetricCatalog.WHOOP),
                )) {
                    for ((day, value) in layer) if (day !in byDay) byDay[day] = value
                }
                metric to byDay.entries.sortedBy { it.key }.map { it.key to it.value }
            }
            HighlightSubject.ENERGY -> {
                val phone = series("active_kcal", MetricCatalog.PHONE)
                if (phone.isNotEmpty()) MetricCatalog.metric("active_kcal", MetricCatalog.PHONE)?.let { it to phone }
                else MetricCatalog.metric("energy_kcal", MetricCatalog.WHOOP)?.let { it to series(it.key, it.source) }
            }
            HighlightSubject.SLEEP ->
                MetricCatalog.metric("sleep_total_min", MetricCatalog.WHOOP)?.let { it to series(it.key, it.source) }
            else -> null
        }
    }

    // MARK: A metric

    /** Steps, calories and time asleep: today against a usual day (steps), the week, the month, the year. */
    suspend fun metric(
        subject: HighlightSubject,
        metric: MetricDescriptor,
        series: List<Pair<String, Double>>,
        vm: AppViewModel,
        hc: HighlightContext,
    ): HighlightGroup? {
        val kind = HighlightCopy.Kind.of(metric.key) ?: return null
        val category = AllMetricsCatalog.category(metric)
        val hue = metricHue(metric.key, metric.category)
        val figure: (Double) -> List<MetricToken> = { metricTokens(metric, it, hc.units, hc.locale) }
        // A daily mean reads "per day" beside its unit; a night's sleep is already a whole.
        val perDay: (Double) -> List<MetricToken> = { value ->
            val tokens = figure(value)
            val unit = tokens.lastOrNull()
            if (kind == HighlightCopy.Kind.SLEEP || unit == null || !unit.isUnit) tokens
            else tokens.dropLast(1) + MetricToken(HighlightCopy.perDay(unit.text), true)
        }
        // Steps open the freshest source's page, as the Summary's Steps card does.
        val route = HighlightRoute.Metric(metric.key, if (kind == HighlightCopy.Kind.STEPS) null else metric.source)
        fun item(id: String, cadence: HighlightCadence, sentence: String, body: HighlightBody,
                 figure: (Double) -> List<MetricToken>, weekLabel: String = "") = HighlightItem(
            id = "${subject.segment}.$id", subject = subject, cadence = cadence, title = metric.title,
            category = category, hue = hue, sentence = sentence, body = body, figure = figure,
            weekLabel = weekLabel, route = route,
        )

        val items = ArrayList<HighlightItem>()
        if (kind == HighlightCopy.Kind.STEPS) {
            val hours = hourlySteps(series, vm, hc)
            val progress = hours?.let { HealthHighlightMath.progress(it.first, it.second, hc.nowTs, hc.zone) }
            if (progress != null) {
                items += item("today", HighlightCadence.DAILY, HighlightCopy.progress(progress.direction),
                    HighlightBody.Progress(progress), figure)
            }
        }
        // A night belongs to the day it ends on, so today's is whole; a day of steps is whole only once over.
        val lastWhole = if (kind == HighlightCopy.Kind.SLEEP) hc.today else hc.today.minusDays(1)
        HealthHighlightMath.sevenDay(series, lastWhole)?.let { week ->
            items += item("week", HighlightCadence.WEEKLY, HighlightCopy.week(kind, spoken(kind, week.average, hc.locale)),
                HighlightBody.SevenDay(week), figure, HighlightCopy.weekLabel(kind))
        }
        for ((period, cadence, id) in listOf(
            Triple(HighlightPeriod.MONTH, HighlightCadence.MONTHLY, "month"),
            Triple(HighlightPeriod.YEAR, HighlightCadence.YEARLY, "year"),
        )) {
            HealthHighlightMath.comparison(series, period, hc.today)?.let { comparison ->
                items += item(id, cadence, HighlightCopy.comparison(kind, comparison), HighlightBody.Comparison(comparison), perDay)
            }
        }
        return if (items.isEmpty()) null else HighlightGroup(subject, metric.title, items)
    }

    /** A week's average as its sentence reads it: "8 502 steps", "1 189 kilocalories", "7 hr 30 min". */
    fun spoken(kind: HighlightCopy.Kind, value: Double, locale: Locale): String {
        val count = value.roundToInt()
        val number = MetricHealthStyle.number(count.toDouble(), 0, locale)
        return when (kind) {
            HighlightCopy.Kind.STEPS -> "$number ${HighlightCopy.unit(R.plurals.hl_unit_steps, count)}"
            HighlightCopy.Kind.ENERGY -> "$number ${HighlightCopy.unit(R.plurals.hl_unit_kcal, count)}"
            HighlightCopy.Kind.SLEEP -> duration(value)
        }
    }

    /** "7 hr 42 min", "8 hr", "25 min", outside composition. */
    fun duration(minutes: Double): String {
        val total = minutes.roundToInt()
        val h = total / 60
        val m = total % 60
        return when {
            h > 0 && m > 0 -> uiString(R.string.sleep_duration_hm, h, m)
            h > 0 -> uiString(R.string.sleep_duration_h, h)
            else -> uiString(R.string.sleep_duration_m, m)
        }
    }

    /** Minutes as "7 hr 12 min" runs: the hours left out under one, the minutes at a whole hour. */
    fun durationTokens(minutes: Double): List<MetricToken> =
        MetricHealthStyle.tokens("", "min", 0, minutes, MetricUnits(), Locale.ROOT, ::localizedUnit)

    // MARK: Workouts

    suspend fun workouts(rows: List<WorkoutRow>, vm: AppViewModel, hc: HighlightContext): HighlightGroup? {
        if (rows.isEmpty()) return null
        val subject = HighlightSubject.WORKOUTS
        val title = uiString(subject.titleRes)
        fun item(id: String, cadence: HighlightCadence, sentence: String, body: HighlightBody,
                 figure: (Double) -> List<MetricToken> = ::durationTokens, weekLabel: String = "") = HighlightItem(
            id = "workouts.$id", subject = subject, cadence = cadence, title = title, category = HealthCategory.ACTIVITY,
            hue = MetricHue.Activity, sentence = sentence, body = body, figure = figure, weekLabel = weekLabel,
            route = HighlightRoute.WorkoutHistory,
        )

        val items = ArrayList(lastWorkoutHeartRate(rows, vm, subject, hc))

        val minutes = HashMap<String, Double>()
        val counts = HashMap<String, Double>()
        for (row in rows) {
            val day = hc.dayKey(row.startTs)
            minutes[day] = (minutes[day] ?: 0.0) + (row.durationS ?: (row.endTs - row.startTs).toDouble()) / 60
            counts[day] = (counts[day] ?: 0.0) + 1
        }
        val yesterday = hc.today.minusDays(1)
        // A day without a workout is a day of no minutes, not a day off the record.
        val week = (6 downTo 0).map { back ->
            val key = yesterday.minusDays(back.toLong()).toString()
            key to (minutes[key] ?: 0.0)
        }
        if (week.any { it.second > 0 }) {
            HealthHighlightMath.sevenDay(week, yesterday)?.let { seven ->
                items += item("week", HighlightCadence.WEEKLY, HighlightCopy.week(null, duration(seven.average)),
                    HighlightBody.SevenDay(seven), weekLabel = HighlightCopy.weekLabel(null))
            }
        }
        val active = counts.keys
        HealthHighlightMath.consistency(active, 1, hc.today, hc.firstDayOfWeek)?.takeIf { it.count > 0 }?.let { days ->
            items += item("days.week", HighlightCadence.WEEKLY, HighlightCopy.workoutDays(days.count, 1), HighlightBody.Consistency(days))
        }
        HealthHighlightMath.consistency(active, 4, hc.today, hc.firstDayOfWeek)?.takeIf { it.count > 0 }?.let { days ->
            items += item("days.month", HighlightCadence.MONTHLY, HighlightCopy.workoutDays(days.count, 4), HighlightBody.Consistency(days))
        }
        val series = counts.entries.sortedBy { it.key }.map { it.key to it.value }
        HealthHighlightMath.comparison(series, HighlightPeriod.MONTH, hc.today, total = true)?.let { comparison ->
            items += item("month", HighlightCadence.MONTHLY, HighlightCopy.workoutsMonth(comparison),
                HighlightBody.Comparison(comparison), figure = { value ->
                    val count = value.roundToInt()
                    listOf(MetricToken("$count", false), MetricToken(HighlightCopy.unit(R.plurals.hl_unit_workouts, count), true))
                })
        }
        return if (items.isEmpty()) null else HighlightGroup(subject, title, items)
    }

    // MARK: Heart rate

    suspend fun heartRate(rows: List<WorkoutRow>, vm: AppViewModel, hc: HighlightContext): HighlightGroup? {
        val subject = HighlightSubject.HEART_RATE
        val items = ArrayList(lastWorkoutHeartRate(rows, vm, subject, hc))
        sleepHeartRate(vm, subject, hc)?.let { items += it }
        return if (items.isEmpty()) null else HighlightGroup(subject, uiString(subject.titleRes), items)
    }

    private val beats: (Double) -> List<MetricToken> = { listOf(MetricToken("${it.roundToInt()}", false)) }

    /** The last workout's minutes after it ended, then its range, when it is recent enough to be news. */
    private suspend fun lastWorkoutHeartRate(
        rows: List<WorkoutRow>,
        vm: AppViewModel,
        subject: HighlightSubject,
        hc: HighlightContext,
    ): List<HighlightItem> = withContext(Dispatchers.IO) {
        val last = rows.filter { it.endTs <= hc.nowTs }.maxByOrNull { it.endTs } ?: return@withContext emptyList()
        if (hc.nowTs - last.endTs > RECENT_SECONDS) return@withContext emptyList()
        fun item(id: String, titleRes: Int, sentence: String, body: HighlightBody) = HighlightItem(
            id = "${subject.segment}.$id", subject = subject, cadence = HighlightCadence.DAILY, title = uiString(titleRes),
            category = HealthCategory.HEART, hue = MetricHue.Heart, sentence = sentence, body = body, figure = beats,
            route = HighlightRoute.Workout(last),
        )
        val items = ArrayList<HighlightItem>()
        val ids = WhoopRepository.workoutHrDeviceIds(last.source, last.deviceId, vm.activeStrapId)
        val after = runCatching { vm.repo.hrSamplesFor(ids, last.endTs - 30, last.endTs + 210, limit = 2_000) }.getOrDefault(emptyList())
        HealthHighlightMath.recovery(after.map { HealthHighlightMath.BeatSample(it.ts, it.bpm.toDouble()) }, last.endTs)?.let {
            items += item("recovery", R.string.hl_title_hr_recovery, HighlightCopy.recovery(last.sport, it.drop), HighlightBody.Recovery(it))
        }
        val buckets = vm.workoutHrBuckets(last.startTs, last.endTs, last.source, last.deviceId)
        HealthHighlightMath.range(
            buckets.map { HealthHighlightMath.RangeSample(it.bucket, it.minBpm, it.maxBpm) }, last.startTs, last.endTs,
        )?.let { range ->
            items += item("workout", R.string.hl_title_hr_workout,
                HighlightCopy.workoutHeartRate(last.sport, range.minimum.roundToInt(), range.maximum.roundToInt()),
                HighlightBody.Range(range))
        }
        items
    }

    /** Last night's heart rate, from falling asleep to waking. */
    private suspend fun sleepHeartRate(vm: AppViewModel, subject: HighlightSubject, hc: HighlightContext): HighlightItem? {
        val night: SleepNightEntry = SleepNightsLoader.load(vm, vm.recentDays.value).entries.lastOrNull() ?: return null
        if (hc.nowTs - night.wakeTs > 36 * 3600) return null
        val buckets = withContext(Dispatchers.IO) {
            runCatching { vm.repo.hrBucketsUnion(vm.activeStrapId, night.onsetTs, night.wakeTs, 300) }.getOrDefault(emptyList())
        }
        val range = HealthHighlightMath.range(
            buckets.map { HealthHighlightMath.RangeSample(it.bucket, it.minBpm, it.maxBpm) }, night.onsetTs, night.wakeTs,
        ) ?: return null
        return HighlightItem(
            id = "${subject.segment}.sleep", subject = subject, cadence = HighlightCadence.DAILY,
            title = uiString(R.string.hl_title_hr_sleep), category = HealthCategory.HEART, hue = MetricHue.Heart,
            sentence = HighlightCopy.sleepHeartRate(range.minimum.roundToInt(), range.maximum.roundToInt()),
            body = HighlightBody.Range(range, marksEnds = true), figure = beats,
            route = HighlightRoute.SleepNight(night.day.toString()),
        )
    }

    // MARK: Steps by the hour

    /** Settled days' hours by strap and day, kept for the life of the process. */
    private val hourCache = ConcurrentHashMap<String, List<Double>>()

    /**
     * Today's steps hour by hour and the same for the days before, each day scaled to the total the
     * subject's own series gives it, so the card's "today" is the figure every other screen shows. The
     * strap's counter only (see the file header).
     */
    private suspend fun hourlySteps(
        series: List<Pair<String, Double>>,
        vm: AppViewModel,
        hc: HighlightContext,
    ): Pair<List<Double>, List<List<Double>>>? = withContext(Dispatchers.IO) {
        val days = (0..HealthHighlightMath.TYPICAL_DAYS).map { hc.today.minusDays(it.toLong()) }
        val raw = strapHours(days, vm, hc) ?: return@withContext null
        val totals = HashMap<String, Double>()
        for ((day, value) in series) totals[day] = value
        val scales = days.zip(raw).map { (day, hours) ->
            val sum = hours.sum()
            val total = totals[day.toString()]
            if (sum > 0 && total != null) total / sum else null
        }
        val known = scales.filterNotNull().filter { it > 0 }
        if (known.isEmpty()) return@withContext null
        // A day the series has no total for yet (today, before its first score) borrows the usual scale.
        val usual = known.sorted()[known.size / 2]
        val scaled = raw.zip(scales).map { (hours, scale) -> hours.map { it * (scale ?: usual) } }
        scaled[0] to scaled.drop(1)
    }

    /** The first strap id whose counter covers today, hour by hour, for each day. null when none does. */
    private suspend fun strapHours(days: List<LocalDate>, vm: AppViewModel, hc: HighlightContext): List<List<Double>>? {
        val today = days.firstOrNull() ?: return null
        for (id in WhoopRepository.importedSourceIdsFor(vm.activeStrapId)) {
            val first = dayHours(today, id, vm, hc, cached = false)
            if (first.sum() <= 0) continue
            val out = arrayListOf(first)
            for ((back, day) in days.withIndex().drop(1)) {
                // The strap hands over a day's history after the fact, so the last days are read afresh; an
                // older day is settled and is read once.
                out += dayHours(day, id, vm, hc, cached = back > 2)
            }
            return out
        }
        return null
    }

    private suspend fun dayHours(day: LocalDate, id: String, vm: AppViewModel, hc: HighlightContext, cached: Boolean): List<Double> {
        val key = "$id|$day"
        if (cached) hourCache[key]?.let { return it }
        val start = day.atStartOfDay(hc.zone).toEpochSecond()
        val end = day.plusDays(1).atStartOfDay(hc.zone).toEpochSecond()
        val samples = runCatching { vm.repo.stepSamples(id, start, end - 1, limit = 200_000) }.getOrDefault(emptyList())
        val hours = HealthHighlightMath.hourlyTicks(samples, start, end)
        if (cached) hourCache[key] = hours
        return hours
    }
}
