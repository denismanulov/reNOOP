package com.noop.ui.summary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Summary may say a night is still being counted. The times follow one real morning: bed at
 * 00:05, up at 08:15, and a scoring pass at 08:15:50 that still stored the night as running to the edge
 * of the data.
 */
class SleepInProgressTest {

    private val bed = 1_000_000L
    private fun at(hours: Double): Long = bed + (hours * 3600).toLong()

    @Test fun aNightRunningToTheEdgeOfFreshDataIsInProgress() {
        assertTrue(SleepInProgress.isInProgress(bed, at(8.15), newestDataTs = at(8.17), nowTs = at(8.18), pendingWakeTs = null))
    }

    /** The pass after he got up: the night ends at 08:15 and the data now runs well past it. */
    @Test fun aNightThatEndedBeforeTheEdgeIsNot() {
        assertFalse(SleepInProgress.isInProgress(bed, at(8.17), newestDataTs = at(8.6), nowTs = at(8.6), pendingWakeTs = null))
    }

    @Test fun theSlackIsInclusive() {
        val edge = at(8.0)
        val slack = SleepInProgress.EDGE_SLACK_SECONDS
        assertTrue(SleepInProgress.isInProgress(bed, edge - slack, edge, edge, null))
        assertFalse(SleepInProgress.isInProgress(bed, edge - slack - 1, edge, edge, null))
    }

    /** Tapping "I'm awake" ends the in-progress state at once, before any sync has shown the wake. */
    @Test fun aWakeMarkTappedDuringThisNightEndsIt() {
        assertFalse(SleepInProgress.isInProgress(bed, at(8.15), at(8.17), at(8.18), pendingWakeTs = at(8.18)))
    }

    /** A mark left over from the morning before says nothing about tonight. */
    @Test fun aWakeMarkFromBeforeThisNightIsIgnored() {
        assertTrue(SleepInProgress.isInProgress(bed, at(8.15), at(8.17), at(8.18), pendingWakeTs = bed - 3600))
    }

    /** The strap out of reach since 03:00 is "not synced", not "asleep until now". */
    @Test fun staleDataIsNotInProgress() {
        assertFalse(SleepInProgress.isInProgress(bed, at(3.0), newestDataTs = at(3.0), nowTs = at(9.0), pendingWakeTs = null))
        val fresh = SleepInProgress.FRESH_DATA_SECONDS
        assertTrue(SleepInProgress.isInProgress(bed, at(3.0), at(3.0), at(3.0) + fresh, null))
        assertFalse(SleepInProgress.isInProgress(bed, at(3.0), at(3.0), at(3.0) + fresh + 1, null))
    }

    @Test fun noDataAtAllIsNotInProgress() {
        assertFalse(SleepInProgress.isInProgress(bed, at(8.0), newestDataTs = null, nowTs = at(8.0), pendingWakeTs = null))
    }
}
