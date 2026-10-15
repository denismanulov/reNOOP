package com.noop.ui.highlights

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.noop.NoopApplication
import com.noop.R
import com.noop.ui.uiString

// MARK: - Highlights copy (twin of iOS HighlightCopy)
//
// The sentence over each card and the words inside it. strings.xml holds each sentence with `@TOKEN@`
// slots, since which figures a sentence names differs by language (one tongue says "than last year",
// another "than in 2025"). Every slot a sentence may carry is filled here; [TOKENS] is the full list, and a
// test holds every locale's strings to it.

object HighlightCopy {

    /** Every slot a sentence may carry. */
    val TOKENS: Set<String> = setOf(
        "SEVEN_DAY_AVERAGE_UNIT", "THIS_YEAR", "LAST_YEAR", "WORKOUT_HR_RANGE_UNIT", "RECOVERY_HR_DELTA_UNIT",
        "HEART_RATE_RANGE_PLUS_UNIT", "MOST_RECENT_VALUE_PLUS_UNIT", "DELTA_TOTAL", "DATA_TYPE_UNIT",
        "DISPLAY_NAME", "LOW_RANGE_VALUE", "HIGH_RANGE_VALUE", "UNIT",
    )

    fun fill(sentence: String, values: Map<String, String>): String {
        var out = sentence
        for ((name, value) in values) out = out.replace("@$name@", value)
        return out
    }

    /** The string under [id], its slots filled. */
    fun text(@StringRes id: Int, values: Map<String, String> = emptyMap()): String = fill(uiString(id), values)

    /** The unit word for [count] ("steps", "шагов"). */
    fun unit(@PluralsRes id: Int, count: Int): String = NoopApplication.localizedPlural(id, count)

    /** The metrics whose highlights have sentences of their own. */
    enum class Kind {
        STEPS, ENERGY, SLEEP;

        companion object {
            fun of(key: String): Kind? = when (key) {
                "steps", "steps_est" -> STEPS
                "energy_kcal", "active_kcal" -> ENERGY
                "sleep_total_min" -> SLEEP
                else -> null
            }
        }
    }

    // MARK: Sentences

    fun progress(direction: HighlightDirection): String = text(
        when (direction) {
            HighlightDirection.MORE -> R.string.hl_progress_more_steps
            HighlightDirection.LESS -> R.string.hl_progress_less_steps
            HighlightDirection.SAME -> R.string.hl_progress_same_steps
        },
    )

    /** [average] is the figure with its unit, as the sentence reads it ("8 502 steps"). */
    fun week(kind: Kind?, average: String): String = text(
        when (kind) {
            Kind.STEPS -> R.string.hl_week_steps
            Kind.ENERGY -> R.string.hl_week_energy
            Kind.SLEEP -> R.string.hl_week_sleep
            null -> R.string.hl_week_workouts
        },
        mapOf("SEVEN_DAY_AVERAGE_UNIT" to average),
    )

    /** The words over a week's average ("Average Steps"); null [kind] is a workout's minutes. */
    fun weekLabel(kind: Kind?): String = uiString(
        when (kind) {
            Kind.STEPS -> R.string.hl_week_label_steps
            Kind.ENERGY -> R.string.hl_week_label_energy
            Kind.SLEEP -> R.string.hl_week_label_sleep
            null -> R.string.hl_week_label_workouts
        },
    )

    fun comparison(kind: Kind, comparison: HighlightComparison): String {
        val d = comparison.direction
        val id = when (comparison.period) {
            HighlightPeriod.MONTH -> when (kind) {
                Kind.STEPS -> pick(d, R.string.hl_month_more_steps, R.string.hl_month_less_steps, R.string.hl_month_same_steps)
                Kind.ENERGY -> pick(d, R.string.hl_month_more_energy, R.string.hl_month_less_energy, R.string.hl_month_same_energy)
                Kind.SLEEP -> pick(d, R.string.hl_month_more_sleep, R.string.hl_month_less_sleep, R.string.hl_month_same_sleep)
            }
            HighlightPeriod.YEAR -> when (kind) {
                Kind.STEPS -> pick(d, R.string.hl_year_more_steps, R.string.hl_year_less_steps, R.string.hl_year_same_steps)
                Kind.ENERGY -> pick(d, R.string.hl_year_more_energy, R.string.hl_year_less_energy, R.string.hl_year_same_energy)
                Kind.SLEEP -> pick(d, R.string.hl_year_more_sleep, R.string.hl_year_less_sleep, R.string.hl_year_same_sleep)
            }
        }
        return text(
            id,
            mapOf("THIS_YEAR" to comparison.currentStart.year.toString(), "LAST_YEAR" to comparison.previousStart.year.toString()),
        )
    }

    fun workoutsMonth(comparison: HighlightComparison): String {
        val now = Math.round(comparison.current).toInt()
        val delta = kotlin.math.abs(now - Math.round(comparison.previous).toInt())
        return text(
            pick(
                comparison.direction, R.string.hl_month_more_workouts, R.string.hl_month_less_workouts,
                R.string.hl_month_same_workouts,
            ),
            mapOf(
                "MOST_RECENT_VALUE_PLUS_UNIT" to "$now ${unit(R.plurals.hl_unit_workouts, now)}",
                "DELTA_TOTAL" to "$delta",
                "DATA_TYPE_UNIT" to unit(R.plurals.hl_unit_workouts, delta),
            ),
        )
    }

    fun workoutHeartRate(sport: String, low: Int, high: Int): String =
        text(workoutSentence(sportKey(sport)), mapOf("WORKOUT_HR_RANGE_UNIT" to beatsRange(low, high)))

    fun recovery(sport: String, drop: Int): String = text(
        recoverySentence(sportKey(sport)),
        mapOf("RECOVERY_HR_DELTA_UNIT" to "$drop ${unit(R.plurals.hl_unit_bpm, drop)}"),
    )

    fun sleepHeartRate(low: Int, high: Int): String =
        text(R.string.hl_hr_sleep, mapOf("HEART_RATE_RANGE_PLUS_UNIT" to beatsRange(low, high)))

    fun workoutDays(count: Int, weeks: Int): String = NoopApplication.localizedPlural(
        if (weeks == 1) R.plurals.hl_days_week_workouts else R.plurals.hl_days_month_workouts, count, count,
    )

    fun title(name: String): String = text(R.string.hl_title_for, mapOf("DISPLAY_NAME" to name))

    /** A daily mean's unit ("steps/day"). */
    fun perDay(unit: String): String = text(R.string.hl_perday, mapOf("UNIT" to unit))

    // MARK: Pieces

    /** "72–166 beats per minute". */
    fun beatsRange(low: Int, high: Int): String {
        val range = text(R.string.hl_range, mapOf("LOW_RANGE_VALUE" to "$low", "HIGH_RANGE_VALUE" to "$high"))
        return "$range ${unit(R.plurals.hl_unit_bpm, high)}"
    }

    private fun pick(direction: HighlightDirection, more: Int, less: Int, same: Int): Int = when (direction) {
        HighlightDirection.MORE -> more
        HighlightDirection.LESS -> less
        HighlightDirection.SAME -> same
    }

    /** The sports with a sentence of their own; anything else reads "your last workout". */
    fun sportKey(sport: String): String = when (sport.lowercase().replace(" ", "")) {
        "running", "run", "outdoorrun", "trailrunning" -> "run"
        "treadmill", "indoorrun", "indoorrunning" -> "indoorRun"
        "walking", "walk", "outdoorwalk" -> "walk"
        "cycling", "outdoorcycle", "biking", "mountainbiking" -> "cycle"
        "indoorcycling", "indoorcycle", "spin", "spinning" -> "indoorCycle"
        "hiking", "hike" -> "hike"
        "yoga" -> "yoga"
        "hiit", "highintensityintervaltraining" -> "hiit"
        "rowing", "rower" -> "row"
        "elliptical" -> "elliptical"
        "stairstepper", "stairs", "stairclimbing" -> "stairs"
        else -> "other"
    }

    private fun workoutSentence(sport: String): Int = when (sport) {
        "run" -> R.string.hl_hr_workout_run
        "indoorRun" -> R.string.hl_hr_workout_indoorrun
        "walk" -> R.string.hl_hr_workout_walk
        "cycle" -> R.string.hl_hr_workout_cycle
        "indoorCycle" -> R.string.hl_hr_workout_indoorcycle
        "hike" -> R.string.hl_hr_workout_hike
        "yoga" -> R.string.hl_hr_workout_yoga
        "hiit" -> R.string.hl_hr_workout_hiit
        "row" -> R.string.hl_hr_workout_row
        "elliptical" -> R.string.hl_hr_workout_elliptical
        "stairs" -> R.string.hl_hr_workout_stairs
        else -> R.string.hl_hr_workout_other
    }

    private fun recoverySentence(sport: String): Int = when (sport) {
        "run" -> R.string.hl_hr_recovery_run
        "indoorRun" -> R.string.hl_hr_recovery_indoorrun
        "walk" -> R.string.hl_hr_recovery_walk
        "cycle" -> R.string.hl_hr_recovery_cycle
        "indoorCycle" -> R.string.hl_hr_recovery_indoorcycle
        "hike" -> R.string.hl_hr_recovery_hike
        "yoga" -> R.string.hl_hr_recovery_yoga
        "hiit" -> R.string.hl_hr_recovery_hiit
        "row" -> R.string.hl_hr_recovery_row
        "elliptical" -> R.string.hl_hr_recovery_elliptical
        "stairs" -> R.string.hl_hr_recovery_stairs
        else -> R.string.hl_hr_recovery_other
    }
}
