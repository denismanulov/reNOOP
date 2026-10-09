package com.noop.ui.workouts

import com.noop.ui.TypedNumber
import com.noop.data.WorkoutRow
import com.noop.ui.WorkoutEditing
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Workouts: the pure rules behind the Fitness-style screens
//
// Compose-free so the unit tests pin them: which activities get a start card, which figure a row leads with,
// how a typed number is read in any locale (CR-11), and the clocks, month titles and date labels the screens
// print. Twins of iOS WorkoutQuickStart (WorkoutsHomeView.swift), WorkoutHistoryRow.headline,
// LiftFormat.number and WorkoutDetailView.clock / LiveWorkoutView.stopwatch.

/**
 * Which activities get a start card, in order: the ones started most recently, then the ones the history holds
 * most often, then Fitness's own defaults, so a fresh install still opens on a sensible list. Twin of iOS
 * `WorkoutQuickStart`. Android starts a live session only for a catalogue sport (the recording keeps a typed
 * `Sport`), so a free-typed recent is resolved against [catalogue] and dropped when it is not in it.
 */
internal object WorkoutQuickStart {
    val defaults = listOf("Walking", "Running", "Strength", "Cycling")
    const val MAX_CARDS = 5

    /** The catch-all sport; it never gets a card of its own ("Other Workout" closes the grid instead). */
    const val DEFAULT_SPORT = "Other"

    fun sports(recents: List<String>, history: List<String>, catalogue: List<String>): List<String> {
        fun known(name: String): String? {
            val display = WorkoutEditing.displaySport(name).trim()
            return catalogue.firstOrNull { it.equals(display, ignoreCase = true) }
        }
        val counts = LinkedHashMap<String, Int>()
        for (sport in history) {
            val k = known(sport) ?: continue
            counts[k] = (counts[k] ?: 0) + 1
        }
        val frequent = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val out = ArrayList<String>(MAX_CARDS)
        for (name in recents.mapNotNull(::known) + frequent + defaults) {
            if (name.equals(DEFAULT_SPORT, ignoreCase = true)) continue
            if (out.any { it.equals(name, ignoreCase = true) }) continue
            out += name
            if (out.size == MAX_CARDS) break
        }
        return out
    }
}

/** The one figure a workout row leads with, as Fitness lists sessions: distance, else energy, else time. */
internal sealed interface WorkoutHeadline {
    data class Distance(val meters: Double) : WorkoutHeadline
    data class Energy(val kcal: Double) : WorkoutHeadline
    data class Duration(val seconds: Double) : WorkoutHeadline

    companion object {
        fun of(row: WorkoutRow): WorkoutHeadline {
            row.distanceM?.takeIf { it > 0 && it.isFinite() }?.let { return Distance(it) }
            row.energyKcal?.takeIf { it > 0 && it.isFinite() }?.let { return Energy(it) }
            return Duration(activeSeconds(row))
        }
    }
}

/** The active time of [row]: its recorded duration, else its span. */
internal fun activeSeconds(row: WorkoutRow): Double =
    row.durationS?.takeIf { it.isFinite() && it >= 0 } ?: (row.endTs - row.startTs).coerceAtLeast(0L).toDouble()

/**
 * Numbers typed by hand (CR-11). A decimal is read by the app's one reader, [TypedNumber], shared with Lab
 * Results and the Journal: a comma decimal ("5,2") reads as well as a point ("5.2"), whatever the phone's
 * language.
 */
internal object WorkoutNumbers {
    fun parse(text: String): Double? = TypedNumber.parse(text)

    /** A whole number ("148"), or null for anything else (a decimal heart rate is not one). */
    fun parseWhole(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || !t.all { it.isDigit() }) return null
        return t.toIntOrNull()
    }

    /**
     * [value] as an editable entry in [locale]: at most [decimals] places, trailing zeros and a dangling
     * separator trimmed, the locale's own decimal mark ("5,2" in Russian, "5.2" in English).
     */
    fun entry(value: Double, decimals: Int, locale: Locale): String {
        var s = String.format(Locale.US, "%.${decimals}f", value)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (sep == '.') s else s.replace('.', sep)
    }
}

/** Clocks and figures the Workouts screens print. */
internal object WorkoutFormat {
    /** "0:55:42" / "12:04": the stopwatch form Fitness uses for every duration on a workout's page. */
    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).roundToInt()
        return if (s >= 3600) {
            "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60)
        } else {
            "%d:%02d".format(Locale.US, s / 60, s % 60)
        }
    }

    /**
     * The recording panel's stopwatch: "04:13.42", or "1:04:13.42" past the hour, with [decimalMark] before
     * the hundredths (the locale's own). Without hundredths (Reduce Motion) "04:13" / "1:04:13".
     */
    fun stopwatch(seconds: Double, hundredths: Boolean, decimalMark: Char = '.'): String {
        // Whole centiseconds (the epsilon keeps 253.42 from reading as 253.41 in binary floating point).
        val centis = (seconds.coerceAtLeast(0.0) * 100 + 1e-6).toLong()
        val whole = centis / 100
        val base = if (whole >= 3600) {
            "%d:%02d:%02d".format(Locale.US, whole / 3600, whole / 60 % 60, whole % 60)
        } else {
            "%02d:%02d".format(Locale.US, whole / 60, whole % 60)
        }
        if (!hundredths) return base
        return base + decimalMark + "%02d".format(Locale.US, centis % 100)
    }

    /** "5,21" (Russian) / "5.21": a distance in km or miles with two decimals in [locale]. */
    fun distanceValue(meters: Double, imperial: Boolean, locale: Locale): String {
        val km = meters / 1000.0
        val v = if (imperial) km * MILES_PER_KM else km
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(v)
    }

    /** "1 234" / "1,234": a whole number grouped the locale's way. */
    fun grouped(value: Double, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).format(value.roundToInt())

    /** "5:38": seconds per km (or per mile) as minutes:seconds; null when there is no pace yet. */
    fun pace(secPerKm: Double?, imperial: Boolean): String? {
        if (secPerKm == null || !secPerKm.isFinite() || secPerKm <= 0) return null
        val s = (if (imperial) secPerKm / MILES_PER_KM else secPerKm).roundToInt()
        return "%d:%02d".format(Locale.US, s / 60, s % 60)
    }

    /** "1 hr 5 min" / "42 min", with the units the caller resolved for the locale. */
    fun durationWords(seconds: Double, hourUnit: String, minuteUnit: String): String {
        val totalMin = (seconds.coerceAtLeast(0.0) / 60.0).roundToInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h $hourUnit $m $minuteUnit"
            h > 0 -> "$h $hourUnit"
            else -> "$m $minuteUnit"
        }
    }

    /** The round of an interval timer's phase clock: "0:30". */
    fun phaseClock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, s / 60, s % 60)
    }

    const val MILES_PER_KM = 0.621371
}

/** How a row's date reads: "Today", "Yesterday", or the short date. */
internal sealed interface WorkoutDay {
    data object Today : WorkoutDay
    data object Yesterday : WorkoutDay
    data class Date(val date: LocalDate) : WorkoutDay

    companion object {
        fun of(startTs: Long, today: LocalDate, zone: ZoneId): WorkoutDay {
            val d = Instant.ofEpochSecond(startTs).atZone(zone).toLocalDate()
            return when (d) {
                today -> Today
                today.minusDays(1) -> Yesterday
                else -> Date(d)
            }
        }
    }
}

/** The history's month sections, newest first, each newest first. */
internal fun workoutMonths(rows: List<WorkoutRow>, zone: ZoneId): List<Pair<YearMonth, List<WorkoutRow>>> =
    rows.groupBy { YearMonth.from(Instant.ofEpochSecond(it.startTs).atZone(zone)) }
        .entries
        .sortedByDescending { it.key }
        .map { (month, list) -> month to list.sortedByDescending { it.startTs } }

/** "September 2026" / "Сентябрь 2026": the standalone month name, capitalised, then the bare year. */
internal fun workoutMonthTitle(month: YearMonth, locale: Locale): String {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    return "$name ${month.year}"
}

/** The filter chips' activities: every one the history holds, most frequent first, ties by name. */
internal fun workoutSportFilters(rows: List<WorkoutRow>): List<String> =
    rows.groupingBy { WorkoutEditing.displaySport(it.sport) }.eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

/**
 * A heart-rate zone's band as the detail page prints it: "<120 bpm" for the first, "170+ bpm" for the last,
 * "120–140" between. [lower]/[upper] are the bands' bpm bounds, Z1..Z5.
 */
internal fun zoneBandLabel(index: Int, lower: List<Double>, upper: List<Double>, bpm: String): String {
    val last = minOf(lower.size, upper.size) - 1
    return when (index) {
        0 -> "<${upper[0].toInt()} $bpm"
        last -> "${lower[last].toInt()}+ $bpm"
        else -> "${lower[index].toInt()}–${upper[index].toInt()}"
    }
}

/** The Effort word for a fill fraction (0..1 of the scale), as iOS `StrainLoadLabel`: 0..4. */
internal fun effortLoadIndex(fraction: Double): Int {
    val f = fraction.coerceIn(0.0, 1.0)
    return when {
        f < 6.0 / 21 -> 0
        f < 10.0 / 21 -> 1
        f < 14.0 / 21 -> 2
        f < 18.0 / 21 -> 3
        else -> 4
    }
}

/** A stable key for one row across reloads (its natural key), used by navigation and pending deletes. */
internal fun workoutKey(row: WorkoutRow): String = "${row.deviceId}|${row.startTs}|${row.source}|${row.sport}"

/**
 * Where a workout came from, as its page names it under the time range: WHOOP, Apple Health, Health
 * Connect, an import, or this phone. Health Connect is told apart from Apple Health by the row's id/source
 * (#53: a binary WHOOP/Apple test once labelled every Health Connect session "Apple").
 */
internal enum class WorkoutOrigin { WHOOP, APPLE_HEALTH, HEALTH_CONNECT, IMPORTED, DEVICE;

    companion object {
        fun of(row: WorkoutRow): WorkoutOrigin = of(row.deviceId, row.source)

        fun of(deviceId: String, source: String): WorkoutOrigin =
            when (WorkoutEditing.classify(source)) {
                com.noop.ui.WorkoutSource.MANUAL, com.noop.ui.WorkoutSource.DETECTED -> DEVICE
                com.noop.ui.WorkoutSource.LIFTING, com.noop.ui.WorkoutSource.ACTIVITY_FILE -> IMPORTED
                com.noop.ui.WorkoutSource.WHOOP -> WHOOP
                com.noop.ui.WorkoutSource.APPLE ->
                    if (deviceId.lowercase() == "health-connect" || source.lowercase().contains("health-connect")) {
                        HEALTH_CONNECT
                    } else APPLE_HEALTH
            }
    }
}

// zonesJSON is a flat one-level numeric object in BOTH stored shapes — "zone1".."zone5"
// (WhoopCsvImporter.zonesJson) and "z1".."z5" (the macOS importer's rows) — so an anchored regex is safe,
// and it keeps org.json (an unmocked Android stub in plain-JVM unit tests) out of test-reachable code.
private val ZONE_KEY = Regex(""""z(?:one)?([1-5])"\s*:\s*(-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)""")

/** Zone percentages (0–100) indexed Z1..Z5, or null when the row has no usable zone data. */
internal fun parseZonePercents(zonesJSON: String?): List<Double>? {
    if (zonesJSON.isNullOrBlank()) return null
    val out = MutableList(5) { 0.0 }
    var any = false
    for (m in ZONE_KEY.findAll(zonesJSON)) {
        val v = m.groupValues[2].toDoubleOrNull() ?: continue
        out[m.groupValues[1].toInt() - 1] = v.coerceIn(0.0, 100.0)
        any = true
    }
    return if (any && out.sum() > 0.0) out else null
}

/** Why the add / edit form cannot save yet: one line under the fields, as iOS ManualWorkoutSheet says it. */
internal enum class ManualProblem { SPORT, START_FUTURE, END_BEFORE_START, END_FUTURE, TOO_SHORT, HEART_RATE, CALORIES, DISTANCE, CHECK }

/**
 * The add / edit form's values made into a row through the same [WorkoutEditing.buildManualRowFromSpan] the
 * engine trusts, with the typed numbers read in any locale (CR-11). [row] is null when it cannot save, and
 * [problem] then names why (in iOS's order). Distance is typed in km or miles and stored in metres (#1195).
 */
internal class ManualWorkoutForm(
    val sport: String,
    val startSeconds: Long,
    val endSeconds: Long,
    val avgHrText: String,
    val kcalText: String,
    val distanceText: String,
    val imperial: Boolean,
    val nowSeconds: Long,
) {
    val avgHr: Int? = WorkoutNumbers.parseWhole(avgHrText)
    val kcal: Double? = WorkoutNumbers.parse(kcalText)
    val distanceMeters: Double? = WorkoutNumbers.parse(distanceText)?.takeIf { it >= 0 }?.let { v ->
        (if (imperial) v / WorkoutFormat.MILES_PER_KM else v) * 1000.0
    }

    /** The row to save (captured fields not yet carried over), or null. */
    val row: WorkoutRow? = run {
        if (avgHrText.isNotBlank() && avgHr == null) return@run null
        if (kcalText.isNotBlank() && kcal == null) return@run null
        if (distanceText.isNotBlank() && distanceMeters == null) return@run null
        WorkoutEditing.buildManualRowFromSpan(
            deviceId = "my-whoop",
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            sport = sport,
            avgHr = avgHr,
            energyKcal = kcal,
            nowSeconds = nowSeconds,
            distanceM = distanceMeters,
        )
    }

    val problem: ManualProblem? = if (row != null) null else when {
        sport.isBlank() -> ManualProblem.SPORT
        startSeconds > nowSeconds -> ManualProblem.START_FUTURE
        endSeconds <= startSeconds -> ManualProblem.END_BEFORE_START
        endSeconds > nowSeconds -> ManualProblem.END_FUTURE
        endSeconds - startSeconds < WorkoutEditing.MIN_MANUAL_SPAN_SECONDS -> ManualProblem.TOO_SHORT
        avgHrText.isNotBlank() && (avgHr == null || avgHr !in 25..250) -> ManualProblem.HEART_RATE
        kcalText.isNotBlank() && (kcal == null || kcal < 0 || kcal > 20_000) -> ManualProblem.CALORIES
        distanceText.isNotBlank() && (distanceMeters == null || distanceMeters > 1_000_000) -> ManualProblem.DISTANCE
        else -> ManualProblem.CHECK
    }
}
