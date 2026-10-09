package com.noop.ui

import android.content.Context
import com.noop.data.JournalEntry
import java.time.LocalDate

// MARK: - Native journal logging (pure helpers; the Journal page in ui/journal draws them)

/** Native answers are written under this dedicated source id, NEVER under "my-whoop": journal's
 *  PK is (deviceId, day, question) and has no source column, so a CSV re-import would silently
 *  overwrite in-app answers (and clears could delete imported rows). */
const val JOURNAL_DEVICE_ID = "noop-journal"

/** Starter behaviour catalog (mirrors WHOOP's most popular journal questions, full-question
 *  phrasing matching the export style). Question strings are opaque exact-match labels to the
 *  effects engine, so imported question strings always take precedence (mergeJournalCatalog).
 *  They are DATA, not UI literals, stored verbatim in the journal table and never localised.
 *  Mirrors macOS JournalCatalogStore.starterQuestions value-for-value. */
val STARTER_JOURNAL_QUESTIONS: List<String> = listOf(
    "Did you drink any alcohol?",
    "Did you have caffeine late in the day?",
    "Did you view a screen in bed?",
    "Did you eat close to bedtime?",
    "Did you feel stressed?",
    "Did you use a sauna?",
    "Did you share your bed?",
    "Did you feel sick or ill?",
    "Did you take magnesium?",
    "Did you read before bed?",
)

/** Dedup/identity key for a question. Normalises ALL whitespace, leading/trailing AND internal
 *  runs collapse to a single space, then lowercases. A WHOOP export commonly leaves a trailing
 *  newline or non-breaking space on a journal cell; folding it here is what keeps an imported
 *  "Did you take magnesium?\n" from sitting beside the starter "Did you take magnesium?" as two
 *  separate rows (#224). The DISPLAYED string stays verbatim, only the match key is normalised, 
 *  so the stored behaviour key the effects engine joins on is untouched.
 *  Kept value-for-value in step with macOS `JournalCatalogStore.norm` (JournalCatalog.swift). */
internal fun normJournalKey(s: String): String =
    // Collapse every run of whitespace to a single space, then trim + lowercase. Uses Kotlin's
    // `Char.isWhitespace()` (Unicode-aware, it includes non-breaking space U+00A0 etc.) rather than
    // a regex: the previous `Regex("(?U)\\s+")` compiled on the desktop JVM but THREW
    // PatternSyntaxException on Android's ICU engine (the `(?U)` inline flag is unsupported there),
    // crashing the Insights screen for anyone with journal entries to merge (#224/#267). Matches the
    // Swift `.whitespacesAndNewlines` normalisation value-for-value.
    buildString {
        var prevSpace = true // suppress leading whitespace
        for (c in s) {
            if (c.isWhitespace()) {
                if (!prevSpace) append(' ')
                prevSpace = true
            } else {
                append(c)
                prevSpace = false
            }
        }
    }.trim().lowercase()

/** Catalog = imported questions (exact strings → logged days join imported history), then starter
 *  defaults, then user customs. Case-insensitive dedupe, first casing wins, with `hidden` questions
 *  (starter/imported ones the user removed) filtered out. */
internal fun mergeJournalCatalog(
    imported: List<String>,
    custom: List<String>,
    hidden: List<String> = emptyList(),
    starter: List<String> = STARTER_JOURNAL_QUESTIONS,
): List<String> {
    val hiddenSet = hidden.map { normJournalKey(it) }.toHashSet()
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    for (q in imported + starter + custom) {
        // Display text trims surrounding whitespace; the dedup key normalises ALL whitespace (see
        // normJournalKey) so an imported "…magnesium?\n" folds onto the starter (#224).
        val t = q.trim()
        val key = normJournalKey(q)
        if (t.isNotEmpty() && key !in hiddenSet && seen.add(key)) out.add(t)
    }
    return out
}

/** Union of imported + native entries; on a (day, question) collision the NATIVE row wins (the
 *  in-app answer is the user's most recent explicit action and stays editable). */
internal fun mergeJournalEntries(
    imported: List<JournalEntry>,
    native: List<JournalEntry>,
): List<JournalEntry> {
    val byKey = LinkedHashMap<Pair<String, String>, JournalEntry>()
    for (e in imported) byKey[e.day to e.question] = e
    for (e in native) byKey[e.day to e.question] = e
    return byKey.values.sortedWith(compareBy({ it.day }, { it.question }))
}

/** Importer convention (WhoopCsvImporter.parseJournal): journal day = the wake/cycle day whose
 *  morning recovery the previous ~24 h affected. "Log today" = answers about yesterday / last
 *  night, attributed to TODAY's local day key; daysBack=1 edits yesterday; daysBack=-1 logs ahead
 *  for TOMORROW (today's activities inform tomorrow's recovery). */
internal fun journalDayKey(daysBack: Long = 0L, today: LocalDate = LocalDate.now()): String =
    today.minusDays(daysBack).toString()

/** How many days back the journal day picker can reach (#656): today (0) plus the 6 prior days = a
 *  7-day backfill window, plus Tomorrow (-1). Bounded on purpose — journal answers feed the
 *  correlation engine, so unbounded backfill of stale days would distort it (matches WHOOP's limited
 *  retroactive window and the strip the Today widget shows). */
internal const val JOURNAL_BACKFILL_DAYS = 6

// MARK: - Custom-question persistence
//
// Stored in the shared "noop_prefs" file under a "noop."-prefixed key, newline-joined (a string
// set loses order). Kept here rather than in NoopPrefs so the logging card is self-contained.

private const val JOURNAL_PREFS = "noop_prefs"
private const val JOURNAL_CUSTOM_KEY = "noop.journalCustomQuestions"
private const val JOURNAL_HIDDEN_KEY = "noop.journalHiddenQuestions"

private fun loadJournalList(context: Context, key: String): List<String> =
    (context.getSharedPreferences(JOURNAL_PREFS, Context.MODE_PRIVATE)
        .getString(key, "") ?: "")
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun saveJournalList(context: Context, key: String, questions: List<String>) {
    context.getSharedPreferences(JOURNAL_PREFS, Context.MODE_PRIVATE)
        .edit().putString(key, questions.joinToString("\n")).apply()
}

internal fun loadCustomJournalQuestions(context: Context): List<String> =
    loadJournalList(context, JOURNAL_CUSTOM_KEY)

internal fun saveCustomJournalQuestions(context: Context, questions: List<String>) =
    saveJournalList(context, JOURNAL_CUSTOM_KEY, questions)

internal fun loadHiddenJournalQuestions(context: Context): List<String> =
    loadJournalList(context, JOURNAL_HIDDEN_KEY)

internal fun saveHiddenJournalQuestions(context: Context, questions: List<String>) =
    saveJournalList(context, JOURNAL_HIDDEN_KEY, questions)
