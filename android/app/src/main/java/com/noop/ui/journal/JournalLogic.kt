package com.noop.ui.journal

import com.noop.R
import com.noop.data.JournalEntry
import com.noop.ui.JournalCatalogItem
import com.noop.ui.JournalGroup
import com.noop.ui.normJournalKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// MARK: - Journal (twin of iOS JournalView's pure parts)
//
// The day strip's offsets and fill, the Logged card's rows, and the on-screen names of built-in journal
// questions (WM-1). Kept free of Compose so the JVM tests pin them.

/** Days back from today shown in the strip, left to right: six days ago … today, then tomorrow (-1). */
internal val JOURNAL_STRIP_OFFSETS: List<Long> = (-1L..6L).reversed().toList()

/**
 * How much of a day's journal is filled in: answered habits plus mood, over the habit list plus one
 * (iOS `fraction(_:)`), clamped to 0..1.
 */
internal fun journalDayFraction(answeredQuestions: Int, hasMood: Boolean, itemCount: Int): Float {
    val answered = answeredQuestions + if (hasMood) 1 else 0
    if (answered <= 0) return 0f
    return minOf(1f, answered.toFloat() / maxOf(itemCount + 1, 1))
}

/** The local calendar day ("yyyy-MM-dd") an intake at [epochSec] belongs to. */
internal fun journalLocalDay(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate().toString()

/** Noon of [day] in [zone]: the time a caffeine intake logged for an earlier day is stamped at. */
internal fun journalNoonEpoch(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    day.atTime(12, 0).atZone(zone).toEpochSecond()

/** A logged number as the Logged card prints it: whole numbers without decimals, else one decimal. */
internal fun journalNumber(v: Double): String =
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.1f", v)

/** A stored 1–5 mood rounded onto the scale. */
internal fun moodStep(value: Double): Int = value.roundToInt().coerceIn(1, 5)

/** The face for a mood step (iOS `MoodStore.face`). */
internal fun moodFace(step: Int): String = when {
    step <= 1 -> "😞" // 😞
    step == 2 -> "😕" // 😕
    step == 3 -> "😐" // 😐
    step == 4 -> "🙂" // 🙂
    else -> "😄"      // 😄
}

/** The neutral word for a mood step (iOS `MoodStore.label`): never a verdict. */
internal fun moodLabelRes(step: Int): Int = when {
    step <= 1 -> R.string.journal_mood_rough
    step == 2 -> R.string.journal_mood_low
    step == 3 -> R.string.journal_mood_okay
    step == 4 -> R.string.journal_mood_good
    else -> R.string.journal_mood_great
}

/**
 * Built-in journal questions in the reader's language (WM-1). The stored question stays the verbatim
 * English key the effects engine joins on; only the label is translated. A key the map does not carry
 * (an imported question NOOP has no translation for, a question the user typed) reads as stored.
 */
private val JOURNAL_QUESTION_LABELS: Map<String, Int> = mapOf(
    "Did you drink any alcohol?" to R.string.journal_q_alcohol_any,
    "Did you have caffeine late in the day?" to R.string.journal_q_caffeine_late,
    "Did you view a screen in bed?" to R.string.journal_q_screen_in_bed,
    "Did you eat close to bedtime?" to R.string.journal_q_eat_late,
    "Did you feel stressed?" to R.string.journal_q_stressed,
    "Did you use a sauna?" to R.string.journal_q_sauna,
    "Did you share your bed?" to R.string.journal_q_share_bed,
    "Did you feel sick or ill?" to R.string.journal_q_sick,
    "Did you take magnesium?" to R.string.journal_q_magnesium,
    "Did you read before bed?" to R.string.journal_q_read,
    "Did you nap?" to R.string.journal_q_nap,
    "Did you have any caffeine?" to R.string.journal_q_caffeine_any,
    "Did you drink alcohol?" to R.string.journal_q_alcohol,
).mapKeys { normJournalKey(it.key) }

/** The label resource of a built-in question, or null when it shows as stored. */
internal fun journalQuestionLabelRes(canonical: String): Int? = JOURNAL_QUESTION_LABELS[normJournalKey(canonical)]

/**
 * How a journal item is named on screen (iOS `JournalCatalogItem.display`): the user's rename; a question
 * the user typed, as typed; a built-in one through [translate] (the reader's language when one exists).
 */
internal fun journalItemLabel(item: JournalCatalogItem, translate: (Int) -> String): String {
    item.displayName?.let { return it }
    if (item.custom) return item.canonical
    return journalQuestionLabelRes(item.canonical)?.let(translate) ?: item.canonical.trim()
}

/** The name of a journal question key, looked up through the catalog [items] (renames) first. */
internal fun journalQuestionLabel(
    canonical: String,
    items: List<JournalCatalogItem>,
    translate: (Int) -> String,
): String {
    val key = normJournalKey(canonical)
    val item = items.firstOrNull { normJournalKey(it.canonical) == key }
    if (item != null) return journalItemLabel(item, translate)
    return journalQuestionLabelRes(canonical)?.let(translate) ?: canonical.trim()
}

/** The string resource of a journal group's title. */
internal fun journalGroupTitleRes(group: JournalGroup): Int = when (group) {
    JournalGroup.Supplements -> R.string.journal_group_supplements
    JournalGroup.Nutrition -> R.string.journal_group_nutrition
    JournalGroup.Lifestyle -> R.string.journal_group_lifestyle
    JournalGroup.Health -> R.string.journal_group_health
    JournalGroup.Behaviour -> R.string.journal_group_behaviour
    JournalGroup.Other -> R.string.journal_group_other
}

/** The questions answered on [day] across [entries] (imported ∪ native), deduped by key. */
internal fun journalAnsweredCount(entries: List<JournalEntry>, day: String): Int =
    entries.filter { it.day == day }.map { normJournalKey(it.question) }.toSet().size
