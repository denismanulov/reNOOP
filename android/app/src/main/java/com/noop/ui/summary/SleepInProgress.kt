package com.noop.ui.summary

/**
 * Whether the night on the Summary is one the app has not yet seen end.
 *
 * While the wearer sleeps the app keeps syncing and re-scoring, and each pass stores the night as it
 * stands: a session that runs right up to the newest sample on record. On one wearer's night the stored
 * total went 289, 337, 392 ... 473 minutes across the passes from 05:09 to 08:15, and it read 473 half
 * a minute after he got up. Nothing on the Summary said that figure was a night still being counted.
 *
 * So a night is in progress when it ends at the edge of the data and that data is fresh. It stops being
 * in progress the moment the wearer says he is awake, even before the next sync proves it: the detector
 * cannot tell lying still from sleeping, and he just told us which it is.
 *
 * Pure. All times are unix seconds.
 */
internal object SleepInProgress {

    /** A night ending within this of the newest sample has not been seen to end. A scoring pass can
     *  trail the data by a few minutes, and the last stretch before waking is often scored as awake. */
    const val EDGE_SLACK_SECONDS: Long = 10L * 60L

    /** The newest sample has to be this recent. An old edge means the strap has been out of reach, and
     *  the honest state then is "not synced", which the sync line already says. */
    const val FRESH_DATA_SECONDS: Long = 90L * 60L

    /**
     * [bedTs]..[lastAsleepTs] is the night as stored, [newestDataTs] the newest sample on record (null
     * when there is none), [pendingWakeTs] a tapped "I'm awake" not yet applied (null when there is
     * none). A pending mark from before this night began belongs to an earlier night and is ignored.
     */
    fun isInProgress(
        bedTs: Long,
        lastAsleepTs: Long,
        newestDataTs: Long?,
        nowTs: Long,
        pendingWakeTs: Long?,
    ): Boolean {
        if (newestDataTs == null) return false
        if (pendingWakeTs != null && pendingWakeTs > bedTs) return false
        if (nowTs - newestDataTs > FRESH_DATA_SECONDS) return false
        return newestDataTs - lastAsleepTs <= EDGE_SLACK_SECONDS
    }
}

/** The night still being counted, as the Summary's in-progress card shows it. */
internal data class SummarySleepInProgress(
    /** Minutes asleep so far. */
    val asleepMinutes: Double,
)
