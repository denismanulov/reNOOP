package com.noop.ui.summary

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noop.ui.KeyMetric
import com.noop.ui.KeyMetricPrefs
import com.noop.ui.NoopPrefs

// MARK: - Summary preferences
//
// Three display-only choices the Summary keeps: which of its two layouts it draws, which two metrics the
// fitness tiles under the rings show, and (through the shared Key Metrics list) which metrics are pinned.
// All live in the app's one SharedPreferences store; none of them changes a computed or stored value.

/** The Summary's two arrangements of the same cards. */
enum class SummaryLayout(val raw: String) {
    /** Denis's iOS structure: rings card, two tiles, full-width pinned cards, trends, highlights. */
    DETAILED("detailed"),

    /** Fitbit-like: three score dials, a highlight banner, a two-column grid, trends in a row. */
    COMPACT("compact");

    companion object {
        /** Anything unknown or unset is the default, Detailed. */
        fun fromRaw(raw: String?): SummaryLayout = entries.firstOrNull { it.raw == raw } ?: DETAILED
    }
}

/**
 * The layout choice, stored as `noop.summaryLayout` = "detailed" | "compact". Held in snapshot state as
 * well, so the Summary, its Edit sheet and Settings all redraw the moment any of them changes it.
 */
object SummaryLayoutPrefs {
    const val KEY = "noop.summaryLayout"

    private var cached by mutableStateOf<SummaryLayout?>(null)

    /** The stored layout (Detailed when never set). Reading it in composition subscribes to changes. */
    fun layout(context: Context): SummaryLayout =
        cached ?: SummaryLayout.fromRaw(NoopPrefs.of(context).getString(KEY, null)).also { cached = it }

    fun setLayout(context: Context, layout: SummaryLayout) {
        NoopPrefs.of(context).edit().putString(KEY, layout.raw).apply()
        cached = layout
    }
}

/**
 * The two metrics the fitness tiles under the rings show, stored as "a,b" under the iOS key
 * `summary.tiles` (twin of iOS `SummaryTilePrefs`). Anything unreadable falls back to the pair a fresh
 * install gets; the two slots never hold the same metric.
 */
object SummaryTilePrefs {
    const val KEY = "summary.tiles"
    val defaults: List<KeyMetric> = listOf(KeyMetric.STEPS, KeyMetric.CALORIES)

    /** Metrics a tile can show: every Key Metric except the three the rings already carry. */
    val choices: List<KeyMetric> = KeyMetric.entries.filter { it !in SummaryPins.rings }

    fun decode(raw: String?): List<KeyMetric> {
        val picked = raw.orEmpty().split(",")
            .mapNotNull { KeyMetric.fromRaw(it.trim()) }
            .filter { it in choices }
        return if (picked.size == 2 && picked[0] != picked[1]) picked else defaults
    }

    /** Puts [metric] in [slot]; when the other tile already shows it, the two swap. */
    fun replacing(raw: String?, slot: Int, metric: KeyMetric): String {
        val tiles = decode(raw).toMutableList()
        if (slot !in tiles.indices || metric !in choices) return encode(tiles)
        val other = tiles.indexOf(metric)
        if (other >= 0 && other != slot) {
            tiles[other] = tiles[slot]
            tiles[slot] = metric
        } else {
            tiles[slot] = metric
        }
        return encode(tiles)
    }

    fun encode(tiles: List<KeyMetric>): String = tiles.joinToString(",") { it.raw }

    fun tiles(context: Context): List<KeyMetric> = decode(NoopPrefs.of(context).getString(KEY, null))

    fun replace(context: Context, slot: Int, metric: KeyMetric): List<KeyMetric> {
        val raw = replacing(NoopPrefs.of(context).getString(KEY, null), slot, metric)
        NoopPrefs.of(context).edit().putString(KEY, raw).apply()
        return decode(raw)
    }
}

/**
 * Which Key Metrics the Summary pins as cards. The pinned list is the shared Key Metrics selection
 * (`today.keyMetrics`, also what a metric page's "Pin in Summary" writes) minus what the rings and the
 * two tiles already show, exactly as iOS `SummaryView.pinnedMetrics` filters it. The Edit sheet edits only
 * those pinnable metrics and keeps every ring / tile entry of the stored list as it was.
 */
object SummaryPins {
    val rings: Set<KeyMetric> = setOf(KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST)

    /** The pinned cards, in the user's order: enabled metrics the rings and tiles do not already show. */
    fun pinned(enabled: List<KeyMetric>, tiles: List<KeyMetric>): List<KeyMetric> =
        enabled.filter { it !in rings && it !in tiles }

    /** The metrics the Edit sheet offers but that are not pinned, in the catalogue's order. */
    fun unpinned(enabled: List<KeyMetric>, tiles: List<KeyMetric>): List<KeyMetric> =
        KeyMetric.entries.filter { it !in rings && it !in tiles && it !in enabled }

    /**
     * The stored list after the sheet set the pinned cards to [pinned]: every ring and tile entry the
     * stored list held stays, in its place at the front, followed by the new pinned order. Never empty,
     * because an empty stored list decodes back to the full default and would re-pin everything: when
     * nothing else is left, the Charge ring (always drawn anyway) holds the list open.
     */
    fun stored(enabled: List<KeyMetric>, tiles: List<KeyMetric>, pinned: List<KeyMetric>): List<KeyMetric> {
        val kept = enabled.filter { it in rings || it in tiles }
        val out = (kept + pinned.filter { it !in rings && it !in tiles }).distinct()
        return out.ifEmpty { listOf(KeyMetric.CHARGE) }
    }

    fun enabled(context: Context): List<KeyMetric> = KeyMetricPrefs.enabled(context)

    fun save(context: Context, tiles: List<KeyMetric>, pinned: List<KeyMetric>): List<KeyMetric> {
        val next = stored(KeyMetricPrefs.enabled(context), tiles, pinned)
        KeyMetricPrefs.setEnabled(context, next)
        return next
    }
}
