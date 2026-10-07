package com.noop.ble

import kotlinx.coroutines.delay

/**
 * Holds the post-offload scoring pass until the offload it follows is over.
 *
 * The pass is scheduled by the FIRST chunk an offload commits and used to start 1.5 s later. A deep
 * offload is re-kicked session after session (#364 auto-continue) and a session can take minutes, so
 * that was a full multi-day re-score running beside the rest of the download, over the store the
 * download was still writing to. Every chunk committed during a pass then queued one more pass behind
 * it, so the re-scores ran back to back until the burst ended. Waiting for the offload to stop lets ONE
 * pass score everything the burst banked.
 *
 * This waits on the phone's own re-score and on nothing else: it runs inside the coroutine the scoring
 * pass is launched in, after the chunk that scheduled it was already persisted and acked, and it sends
 * nothing to the strap.
 *
 * Twin of the Swift `AppModel.requestPostOffloadRefresh`, which holds on the same condition and the same
 * cap. What releases them differs because they are asked differently: Swift is asked 2 s after each
 * finished session and again 5 s after `backfilling` drops; Android is asked once per committed chunk,
 * so it watches for the offload to stay stopped instead.
 */
internal object PostOffloadScoringHold {

    /** A multi-hour catch-up still shows progress: one hold lasts at most this long. Swift: 10 min. */
    const val MAX_HOLD_MS = 10 * 60 * 1_000L

    /**
     * How long the offload has to stay stopped before the burst counts as over. An auto-continue re-kick
     * follows the end of a session by one store read and one main-looper post, and `backfilling` is false
     * in between; without this the hold would release into the gap between two sessions of one burst.
     */
    const val SETTLE_MS = 2_000L

    /** How often the offload is looked at while holding. */
    const val POLL_MS = 500L

    enum class Release {
        /** The offload stayed stopped for [SETTLE_MS]. */
        OFFLOAD_IDLE,

        /** [MAX_HOLD_MS] passed first. */
        MAX_HOLD,
    }

    /**
     * What one hold did. [waitedMs] runs from the first look to the release. [sawOffload] is whether any
     * look found the offload running; [offloadRunningAtRelease] is what the last look found.
     */
    data class Outcome(
        val waitedMs: Long,
        val sawOffload: Boolean,
        val release: Release,
        val offloadRunningAtRelease: Boolean,
    )

    /**
     * Suspends until [offloadRunning] has been false for [settleMs] on end, or [maxHoldMs] has passed.
     * The clock and the sleep are parameters so the decision is tested without waiting ten minutes.
     */
    suspend fun await(
        offloadRunning: () -> Boolean,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        sleep: suspend (Long) -> Unit = { delay(it) },
        maxHoldMs: Long = MAX_HOLD_MS,
        settleMs: Long = SETTLE_MS,
        pollMs: Long = POLL_MS,
    ): Outcome {
        val startedAt = nowMs()
        var idleSince: Long? = null
        var sawOffload = false
        while (true) {
            val running = offloadRunning()
            val now = nowMs()
            if (running) {
                sawOffload = true
                idleSince = null
            } else if (idleSince == null) {
                idleSince = now
            }
            val idleFor = idleSince?.let { now - it }
            if (idleFor != null && idleFor >= settleMs) {
                return Outcome(now - startedAt, sawOffload, Release.OFFLOAD_IDLE, running)
            }
            if (now - startedAt >= maxHoldMs) {
                return Outcome(now - startedAt, sawOffload, Release.MAX_HOLD, running)
            }
            sleep(pollMs)
        }
    }

    /**
     * The strap-log line for a hold that found an offload running, null for one that did not (the
     * ordinary pass after a short sync, which waits out the settle and has nothing to report). It states
     * the wait that was timed, what ended it, and what the last look at the offload found.
     */
    fun logLine(outcome: Outcome, maxHoldMs: Long = MAX_HOLD_MS, settleMs: Long = SETTLE_MS): String? {
        if (!outcome.sawOffload) return null
        val waited = "re-score: trigger=post-offload waited ${outcome.waitedMs / 1_000L}s for the offload"
        return when (outcome.release) {
            Release.OFFLOAD_IDLE -> "$waited; released after ${settleMs / 1_000L}s without one running"
            Release.MAX_HOLD -> "$waited; released at the ${maxHoldMs / 60_000L}-minute cap, offload " +
                if (outcome.offloadRunningAtRelease) "still running" else "not running at that moment"
        }
    }
}
