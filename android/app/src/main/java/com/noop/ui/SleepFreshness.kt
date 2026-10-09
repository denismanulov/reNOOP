package com.noop.ui

/** Which freshness note the Sleep tab shows for the expected current night, if any. */
internal enum class SleepFreshnessStatus {
    SYNCING, CALCULATING, SYNC_FAILED, AWAITING_SYNC, NOT_DETECTED,
}

/** Pure priority ladder for the expected current night. The missing states wait until morning so opening
 * Sleep at 02:00 does not claim the night still in progress has been missed. Swift twin:
 * `resolveSleepFreshness`. */
internal fun resolveSleepFreshness(
    hasCurrentNight: Boolean,
    morningReady: Boolean,
    syncing: Boolean,
    calculating: Boolean,
    syncedSinceDayStart: Boolean,
    syncFailed: Boolean,
): SleepFreshnessStatus? {
    if (syncing) return SleepFreshnessStatus.SYNCING
    // #2108: a night already in hand outranks CALCULATING. It used to sit below, so `hasCurrentNight`
    // could only silence the missing-night states and a finished night was structurally unable to
    // silence this one: the banner said "detecting and staging the night now" directly above that same
    // night scored, timed and staged on screen. A note that contradicts the content beside it is worse
    // than no note, and one that is always on is read by nobody the day it matters. SYNCING stays above,
    // because data still arriving can genuinely change what is shown.
    if (hasCurrentNight) return null
    if (calculating) return SleepFreshnessStatus.CALCULATING
    if (!morningReady) return null
    if (syncFailed) return SleepFreshnessStatus.SYNC_FAILED
    return if (syncedSinceDayStart) SleepFreshnessStatus.NOT_DETECTED
    else SleepFreshnessStatus.AWAITING_SYNC
}
