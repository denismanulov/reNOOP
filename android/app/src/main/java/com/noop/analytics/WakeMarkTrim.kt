package com.noop.analytics

/*
 * WakeMarkTrim.kt - what a tapped "I'm awake" mark does to the night it lands in (#461, the step after
 * Phase 1's pure logging).
 *
 * The detector ends a night when the wrist stops looking asleep, and it is usually right. Its misses
 * have one shape: the wearer wakes and then lies still, and the stillness is read as more sleep. On one
 * wearer's eight nights the detected end sat within 30 seconds of the rise seen in the record on three,
 * and 3 and 19 minutes late on two. A tapped mark is the one input that knows the difference.
 *
 * So a mark may only ever move the end of a night EARLIER, to the instant it was tapped, and only when
 * it lands inside that night and close to its end. It never extends a night, never starts one, and
 * never touches a night it does not fall in: a mark tapped ten minutes after getting up changes
 * nothing, because the night already ended before it.
 *
 * A mark usually arrives BEFORE the night it belongs to has been synced and scored, so it is kept
 * pending and offered to every later scoring pass until it either trims a night or grows too old.
 *
 * Pure: no I/O, no clock reads. All times are unix seconds. Android only, there is no Swift twin yet.
 */
object WakeMarkTrim {

    /**
     * The furthest before a night's detected end a mark may sit and still be that night's waking. A
     * mark deeper inside the night than this is far more likely a stray tap than a wake the detector
     * missed by over an hour, and trimming on it would delete real sleep.
     */
    const val MAX_TRIM_SECONDS: Long = 60L * 60L

    /** How long a mark waits for its night to be synced and scored before it is dropped. */
    const val MAX_PENDING_SECONDS: Long = 12L * 3600L

    sealed interface Decision {
        /** End the night at [index] (into the list passed to [decide]) at [newEndTs]. */
        data class Trim(val index: Int, val newEndTs: Long) : Decision

        /** No night ends later than the mark yet; keep the mark and ask again after the next pass. */
        object Wait : Decision

        /** The mark is too old to belong to any night still being scored; forget it. */
        object Expired : Decision
    }

    /**
     * The end a night spanning `[startTs, endTs]` should take for a mark at [wakeTs], or null to leave
     * it as detected. Non-null only when the mark is strictly inside the night and no further than
     * [MAX_TRIM_SECONDS] before its end.
     */
    fun trimmedEnd(startTs: Long, endTs: Long, wakeTs: Long): Long? =
        if (wakeTs > startTs && wakeTs < endTs && endTs - wakeTs <= MAX_TRIM_SECONDS) wakeTs else null

    /**
     * What to do with a pending mark at [wakeTs], given the recorded [nights] as `(start, end)` pairs
     * and the current time. The first night the mark can trim wins; a mark that fits none waits, and
     * one older than [MAX_PENDING_SECONDS] expires.
     */
    fun decide(nights: List<Pair<Long, Long>>, wakeTs: Long, nowTs: Long): Decision {
        if (nowTs - wakeTs > MAX_PENDING_SECONDS) return Decision.Expired
        nights.forEachIndexed { i, (start, end) ->
            trimmedEnd(start, end, wakeTs)?.let { return Decision.Trim(i, it) }
        }
        return Decision.Wait
    }
}
