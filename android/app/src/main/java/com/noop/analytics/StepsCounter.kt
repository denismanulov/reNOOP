package com.noop.analytics

import com.noop.data.StepSample

/**
 * Wrap-aware step derivation from the strap's cumulative step counter (`step_motion_counter@57` on a
 * WHOOP 5/MG, the firmware pedometer `step_counter@92` on a WHOOP 4.0), shared by the daily total
 * ([AnalyticsEngine.analyzeDay]) and any windowed total (a manual workout's `[start, end]`, #398).
 *
 * `step_motion_counter@57` is a CUMULATIVE u16 motion counter: it climbs for both locomotion and some
 * non-step wrist motion, and wraps at 65536. On classed WHOOP 5/MG records, the increment ending at each
 * sample is counted only when the strap labels that sample walk (1) or run (2); still (0) and unknown are
 * rejected. A wholly unclassed legacy window retains the old counter-only estimate so pre-migration history
 * remains readable. The caller applies its per-user `stepTicksPerStep` calibration afterwards. The result is
 * still an estimate, not cloud/clinical parity.
 *
 * Byte-for-byte twin of the Swift `StepsCounter.stepsInWindow`.
 */
object StepsCounter {
    private val LOCOMOTION_ACTIVITY_CLASSES = setOf(1, 2)

    internal fun hasActivityClasses(samples: List<StepSample>): Boolean =
        samples.any { it.activityClass != null }

    internal fun shouldCountDelta(activityClass: Int?, hasActivityClasses: Boolean): Boolean =
        !hasActivityClasses || activityClass in LOCOMOTION_ACTIVITY_CLASSES

    /** Absolute reboot/wrap guard retained independently from the rate plausibility gate below. */
    const val MAX_STEP_DELTA = 512

    /**
     * Ticks one increment may add for each second since the previous sample. Eight, not the four a cadence
     * alone would suggest, because a 1 Hz record can carry two seconds' worth of ticks: on a WHOOP 4.0 walk
     * of 2026-10-03 a steady 2-3 per second was broken by four records of 5 or 6, and that counter runs at
     * about 1.26 ticks per step, so a run sits near four per second before any such doubling.
     */
    const val MAX_TICKS_PER_SECOND = 8

    /**
     * Seconds a pedometer may hold steps back before publishing them. The WHOOP 4.0 counter stays flat
     * while it confirms a walk, then releases the buffered steps in one record (11 to 14 on the 2026-10-03
     * captures). At a slow 1.5 steps per second that is eight seconds.
     */
    const val CONFIRMATION_WINDOW_SECONDS = 8

    /**
     * Ticks credited toward a buffered release for each second the counter stayed flat. This is a sustained
     * cadence, not a single-record burst, so it stays below [MAX_TICKS_PER_SECOND].
     */
    const val RELEASE_TICKS_PER_SECOND = 4

    /**
     * Rate plausibility for one counter increment. Two allowances apply and the larger one wins:
     * [MAX_TICKS_PER_SECOND] for each second since the previous sample, and [RELEASE_TICKS_PER_SECOND] for
     * each second since the counter last moved, capped at [CONFIRMATION_WINDOW_SECONDS]. A buffered release
     * after a flat run is therefore judged against the seconds it covers, while an increment right after
     * another one gets the per-second limit alone. [lastMovedTs] is the timestamp of the latest sample whose
     * counter differed from its predecessor, or of the window's first sample when none has yet.
     *
     * Swift twin: `StepsCounter.isPlausibleDelta(previousTs:currentTs:lastMovedTs:delta:)`.
     */
    internal fun isPlausibleDelta(previousTs: Long, currentTs: Long, lastMovedTs: Long, delta: Int): Boolean {
        if (delta !in 1 until MAX_STEP_DELTA) return false
        val elapsed = currentTs - previousTs
        if (elapsed <= 0L) return false
        val rateAllowance = if (elapsed >= MAX_STEP_DELTA / MAX_TICKS_PER_SECOND) {
            MAX_STEP_DELTA - 1L
        } else {
            elapsed * MAX_TICKS_PER_SECOND
        }
        val releaseAllowance =
            minOf(currentTs - lastMovedTs, CONFIRMATION_WINDOW_SECONDS.toLong()) * RELEASE_TICKS_PER_SECOND
        return delta.toLong() <= maxOf(rateAllowance, releaseAllowance)
    }

    /**
     * Raw wrap-aware locomotion-tick total across [samples]. When any sample carries [StepSample.activityClass],
     * each positive increment is attributed to the later sample and retained only for walk/run. When the
     * whole window is legacy-unclassed, all valid increments retain the historical counter-only fallback.
     * Sorts by `ts` internally and returns `null` for fewer than two samples or no retained movement.
     */
    fun stepsInWindow(samples: List<StepSample>): Int? {
        val sorted = samples.sortedBy { it.ts }
        if (sorted.size < 2) return null
        val hasActivityClasses = hasActivityClasses(sorted)
        var total = 0
        var lastMovedTs = sorted[0].ts
        for (i in 1 until sorted.size) {
            val delta = (sorted[i].counter - sorted[i - 1].counter) and 0xFFFF // wrap-aware u16 increment
            val isLocomotion = shouldCountDelta(sorted[i].activityClass, hasActivityClasses)
            if (isLocomotion && isPlausibleDelta(sorted[i - 1].ts, sorted[i].ts, lastMovedTs, delta)) total += delta
            if (delta != 0) lastMovedTs = sorted[i].ts
        }
        return if (total > 0) total else null
    }
}
