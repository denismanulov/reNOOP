package com.noop.ui.highlights

import androidx.annotation.StringRes
import com.noop.R
import com.noop.data.WorkoutRow
import com.noop.ui.m3.MetricHue
import com.noop.ui.metric.HealthCategory
import com.noop.ui.metric.MetricToken

// MARK: - A highlight ready to draw (twin of iOS HighlightItem)
//
// One card: its category row, its sentence, its figures and how those figures are written, where it files on
// the Highlights pages and what it opens.

/** What a set of highlights is about; one row on the All Highlights page and one Highlights page each. */
enum class HighlightSubject(val category: HealthCategory, @StringRes val titleRes: Int) {
    STEPS(HealthCategory.ACTIVITY, R.string.metric_title_steps),
    ENERGY(HealthCategory.ACTIVITY, R.string.metric_title_calories),
    SLEEP(HealthCategory.SLEEP, R.string.metric_title_asleep),
    WORKOUTS(HealthCategory.ACTIVITY, R.string.nav_workouts),
    HEART_RATE(HealthCategory.HEART, R.string.browse_heart_rate);

    /** The navigation segment of this subject's Highlights page. */
    val segment: String get() = name.lowercase()

    companion object {
        fun ofSegment(segment: String): HighlightSubject? = entries.firstOrNull { it.segment == segment }

        /** The subject a metric page's highlights are about, by catalogue key. */
        fun ofMetricKey(key: String): HighlightSubject? = when (HighlightCopy.Kind.of(key)) {
            HighlightCopy.Kind.STEPS -> STEPS
            HighlightCopy.Kind.ENERGY -> ENERGY
            HighlightCopy.Kind.SLEEP -> SLEEP
            null -> null
        }
    }
}

/** Where a card opens from the Summary. */
sealed interface HighlightRoute {
    data class Metric(val key: String, val source: String?) : HighlightRoute
    data object WorkoutHistory : HighlightRoute
    data class Workout(val row: WorkoutRow) : HighlightRoute
    data class SleepNight(val wakeDay: String) : HighlightRoute
}

class HighlightItem(
    val id: String,
    val subject: HighlightSubject,
    val cadence: HighlightCadence,
    /** The category row's name ("Steps", "Heart Rate: Workout"). */
    val title: String,
    val category: HealthCategory,
    val hue: MetricHue,
    val sentence: String,
    val body: HighlightBody,
    /** How this card writes one of its figures ("1 189 kcal", "7 h 30 min"). */
    val figure: (Double) -> List<MetricToken>,
    /** The words over a week's average ("Average Steps"). */
    val weekLabel: String = "",
    val route: HighlightRoute? = null,
)

/** One subject's highlights, filed by how often each is renewed. */
class HighlightGroup(val subject: HighlightSubject, val title: String, val items: List<HighlightItem>) {
    fun items(cadence: HighlightCadence): List<HighlightItem> = items.filter { it.cadence == cadence }
}

@get:StringRes
val HighlightCadence.sectionTitleRes: Int
    get() = when (this) {
        HighlightCadence.DAILY -> R.string.hl_section_daily
        HighlightCadence.WEEKLY -> R.string.hl_section_weekly
        HighlightCadence.MONTHLY -> R.string.hl_section_monthly
        HighlightCadence.YEARLY -> R.string.hl_section_yearly
    }
