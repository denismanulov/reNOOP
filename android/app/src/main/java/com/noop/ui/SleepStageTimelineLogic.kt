package com.noop.ui

import org.json.JSONArray
import com.noop.analytics.SleepStageVocabulary

/** One persisted per-epoch stage segment (wall-clock unix seconds). */
internal data class PersistedSegment(val start: Long, val end: Long, val stage: String)

/**
 * Parse the verbatim per-epoch segments array the on-device stager persists
 * ([{"start","end","stage"}], unix seconds, stage ∈ wake|light|deep|rem — see
 * AnalyticsEngine.encodeStages). Returns null for the imported minutes shapes
 * (the macOS {"light",…} dict and the CSV-import [{stage,min}] array) and any
 * malformed input, so callers keep the synthesized fallback. Pure + unit-tested
 * (see SleepStageSegmentsTest).
 */
internal fun parsePersistedSegments(json: String?): List<PersistedSegment>? {
    if (json.isNullOrBlank()) return null
    val trimmed = json.trim()
    if (!trimmed.startsWith("[")) return null
    return runCatching {
        val arr = JSONArray(trimmed)
        val out = ArrayList<PersistedSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return@runCatching null
            val start = o.optLong("start", Long.MIN_VALUE)
            val end = o.optLong("end", Long.MIN_VALUE)
            val stage = o.optString("stage", "")
            if (start == Long.MIN_VALUE || end <= start || stage.isEmpty()) return@runCatching null
            out.add(PersistedSegment(start, end, stage))
        }
        out.takeIf { it.size >= 2 }
    }.getOrNull()
}

// MARK: - Stage timeline logic (iOS #988 port — pure, unit-tested)



/** Canonical stage key: trims, lowercases, and folds the "wake"/"awake" alias. The single definition of
 *  a stage key in the UI layer — `stageColorFor` (SleepStageBreakdownUi.kt) keys off this rather than
 *  repeating the rule, so adding an alias here is all that is needed. Written as code rather than a
 *  KDoc link because the target is private in another file and would not resolve. */
internal fun canonicalStage(name: String): String {
    // #979: the alias rule has ONE definition — SleepStageVocabulary. This still folds toward
    // "awake" because that is the key the stage-colour table and the minutes dictionaries use.
    val n = name.trim().lowercase()
    return if (SleepStageVocabulary.isWake(n)) "awake" else n
}

