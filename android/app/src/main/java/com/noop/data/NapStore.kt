package com.noop.data

import android.content.Context
import com.noop.analytics.NapCandidate
import org.json.JSONArray
import org.json.JSONObject

/**
 * NapStore — on-device, JSON-in-SharedPreferences persistence for the short-nap REVIEW QUEUE
 * (reimplemented from @cbarrado's PR #569 under NoopApp identity). Deliberately NO Room: a nap candidate
 * is a tiny, transient review item, not a first-class data row — it lives only until the user accepts it
 * (→ `WhoopRepository.addManualNap`, the #508 hand-corrected-nap path) or dismisses it. Mirrors the
 * CaffeineLog JSON-list pattern (org.json + the shared "noop_prefs" store); nothing leaves the device.
 *
 * A candidate carries a stable [NapCandidate]-derived id (start|end), and the queue is deduplicated by
 * OVERLAP, not by that id: the detector re-reads the same stretch of stillness on every offload and its
 * end moves by seconds or grows by minutes, so an exact-id test queued one nap three times. A window the
 * user dismissed stays dismissed for anything that overlaps it (tracked in a small dismissed-id set,
 * retention-pruned with the queue).
 *
 * A candidate that overlaps a recorded sleep session is not a nap to review and is dropped
 * ([outsideSleep]): the detector runs on every offload, night included, and a night is made of quiet
 * stretches of exactly nap length.
 *
 * All times are wall-clock unix SECONDS. Pure aside from the single SharedPreferences read/write; the
 * detection itself is the pure [com.noop.analytics.NapDetector].
 */
object NapStore {

    private const val PREFS = "noop_prefs"
    private const val KEY_PENDING = "noop.napPending"
    private const val KEY_DISMISSED = "noop.napDismissedIds"

    /** Drop pending/dismissed entries whose window ended more than this many hours ago — a stale, never
     *  reviewed candidate shouldn't linger, and the blob must stay bounded. */
    private const val RETENTION_HOURS = 36.0

    /** Stable id for a candidate window: a re-detect of the same span maps to the same id (idempotent). */
    fun idFor(c: NapCandidate): String = "${c.start}|${c.end}"

    /** End-ts parsed out of an id ("start|end"), or null if malformed — used only for retention pruning.
     *  Public + pure so the retention/dedup logic is unit-testable without a Context. */
    fun endTsOf(id: String): Long? = id.substringAfter('|', "").toLongOrNull()

    /** Start-ts parsed out of an id ("start|end"), or null if malformed. Public + pure, as [endTsOf]. */
    fun startTsOf(id: String): Long? = id.substringBefore('|', "").toLongOrNull()

    /** Do the windows `[aStart, aEnd]` and `[bStart, bEnd]` share any time? Touching ends do not count. */
    fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean = aStart < bEnd && bStart < aEnd

    /** Does [candidate] overlap the window an [id] names? A malformed id only matches itself. */
    private fun overlapsId(candidate: NapCandidate, id: String): Boolean {
        val start = startTsOf(id)
        val end = endTsOf(id)
        if (start == null || end == null) return id == idFor(candidate)
        return overlaps(candidate.start, candidate.end, start, end)
    }

    /**
     * Pure enqueue decision: a freshly-detected [candidate] should be queued only when its window
     * overlaps nothing already pending AND nothing previously dismissed. Pulled out of [enqueue] so the
     * dedup contract is unit-testable without SharedPreferences. [pendingIds]/[dismissedIds] are the
     * current id sets.
     */
    fun shouldEnqueue(candidate: NapCandidate, pendingIds: Set<String>, dismissedIds: Set<String>): Boolean =
        dismissedIds.none { overlapsId(candidate, it) } && pendingIds.none { overlapsId(candidate, it) }

    /**
     * The queue after [candidate] arrives, newest window first. Pure.
     *
     * - It overlaps a dismissed window: unchanged. The user already said no to that stretch.
     * - It overlaps nothing pending: added.
     * - It overlaps something pending: the same stretch read again, so ONE entry stays, the longest
     *   reading of it (an earlier entry wins a tie). A nap still in progress at one offload is seen
     *   whole at the next.
     */
    fun queueAfter(
        pending: List<NapCandidate>,
        candidate: NapCandidate,
        dismissedIds: Set<String>,
    ): List<NapCandidate> {
        if (dismissedIds.any { overlapsId(candidate, it) }) return pending
        val clashing = pending.filter { overlaps(it.start, it.end, candidate.start, candidate.end) }
        val kept = if (clashing.isEmpty()) candidate else (clashing + candidate).maxByOrNull { it.durationS }!!
        return (pending - clashing.toSet() + kept).sortedByDescending { it.start }
    }

    /**
     * [candidates] without those that overlap a recorded sleep session. [sleep] is `(start, end)` pairs
     * in unix seconds, main sleep and naps alike: either way the stretch is already sleep on record and
     * there is nothing left to ask the user. Pure.
     */
    fun outsideSleep(candidates: List<NapCandidate>, sleep: List<Pair<Long, Long>>): List<NapCandidate> =
        candidates.filterNot { c -> sleep.any { (start, end) -> overlaps(c.start, c.end, start, end) } }

    /** Pure retention prune of a dismissed-id set: keep only ids whose window end is at/after [cutoff].
     *  Malformed ids (no parseable end) are dropped. Unit-testable without a Context. */
    fun pruneDismissed(ids: Set<String>, cutoff: Long): Set<String> =
        ids.filter { endTsOf(it)?.let { e -> e >= cutoff } ?: false }.toSet()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The pending nap candidates awaiting review, newest window first, stale entries pruned. */
    fun pending(context: Context, nowEpochSec: Long = System.currentTimeMillis() / 1000L): List<NapCandidate> {
        val raw = prefs(context).getString(KEY_PENDING, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val cutoff = nowEpochSec - (RETENTION_HOURS * 3600).toLong()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val start = o.optLong("start", Long.MIN_VALUE)
                val end = o.optLong("end", Long.MIN_VALUE)
                if (start == Long.MIN_VALUE || end == Long.MIN_VALUE || end <= start) return@mapNotNull null
                if (end < cutoff) return@mapNotNull null
                NapCandidate(
                    start = start,
                    end = end,
                    meanHr = if (o.has("meanHr") && !o.isNull("meanHr")) o.optInt("meanHr") else null,
                    confidence = o.optDouble("confidence", 0.0),
                )
            }.sortedByDescending { it.start }
        }.getOrDefault(emptyList())
    }

    /** Dismissed window ids (so a re-detect of an already-reviewed window doesn't re-queue). */
    private fun dismissedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISMISSED, emptySet())?.toSet() ?: emptySet()

    private fun writePending(context: Context, list: List<NapCandidate>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("start", c.start)
            o.put("end", c.end)
            if (c.meanHr != null) o.put("meanHr", c.meanHr) else o.put("meanHr", JSONObject.NULL)
            o.put("confidence", c.confidence)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    /**
     * Queue a freshly-detected candidate for review. Returns false when the window overlaps one already
     * pending OR previously dismissed — so the same stretch can be re-detected on every offload without
     * spamming the queue. Returns true when it was newly enqueued.
     */
    fun enqueue(context: Context, candidate: NapCandidate, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        val current = pending(context, nowEpochSec)
        val dismissed = dismissedIds(context)
        val next = queueAfter(current, candidate, dismissed)
        if (next != current) writePending(context, next)
        // "Newly enqueued" means a stretch the queue did not hold; a longer reading of one it did
        // replaces that entry without counting as new.
        return shouldEnqueue(candidate, current.map { idFor(it) }.toSet(), dismissed)
    }

    /**
     * Drop every pending candidate that overlaps a recorded sleep session and return what is left. Not
     * recorded as dismissed: the detector applies the same rule, so they cannot come back.
     */
    fun dropOverlappingSleep(
        context: Context,
        sleep: List<Pair<Long, Long>>,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
    ): List<NapCandidate> {
        val current = pending(context, nowEpochSec)
        val next = outsideSleep(current, sleep)
        if (next != current) writePending(context, next)
        return next
    }

    /** Remove a candidate from the pending queue WITHOUT marking it dismissed (used after accept). */
    fun remove(context: Context, id: String, nowEpochSec: Long = System.currentTimeMillis() / 1000L): List<NapCandidate> {
        val next = pending(context, nowEpochSec).filterNot { idFor(it) == id }
        writePending(context, next)
        return next
    }

    /**
     * Dismiss a candidate: drop it from pending AND record its id so a later re-detect can't re-queue it.
     * The dismissed-id set is pruned to the retention horizon so it can't grow without bound.
     */
    fun dismiss(context: Context, id: String, nowEpochSec: Long = System.currentTimeMillis() / 1000L): List<NapCandidate> {
        val cutoff = nowEpochSec - (RETENTION_HOURS * 3600).toLong()
        // Keep only still-fresh dismissed ids, then record this one (pure prune, unit-tested).
        val kept = pruneDismissed(dismissedIds(context), cutoff).toMutableSet()
        kept.add(id)
        prefs(context).edit().putStringSet(KEY_DISMISSED, kept).apply()
        return remove(context, id, nowEpochSec)
    }
}
