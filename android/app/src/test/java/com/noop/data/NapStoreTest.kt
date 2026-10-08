package com.noop.data

import com.noop.analytics.NapCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [NapStore]'s id / dedup / retention contract (PR #569 reimpl). The
 * SharedPreferences + org.json I/O isn't unit-testable on plain JVM (org.json isn't mocked, matching the
 * CaffeineLog store), so the dedup + retention rules are exposed as pure functions and tested here.
 */
class NapStoreTest {

    private fun cand(start: Long, end: Long, meanHr: Int? = 55, conf: Double = 0.6) =
        NapCandidate(start = start, end = end, meanHr = meanHr, confidence = conf)

    @Test fun idFor_isStableForTheSameWindow() {
        assertEquals(NapStore.idFor(cand(100, 200)), NapStore.idFor(cand(100, 200, meanHr = 99)))
    }

    @Test fun idFor_differsForDifferentWindows() {
        assertFalse(NapStore.idFor(cand(100, 200)) == NapStore.idFor(cand(100, 201)))
    }

    @Test fun endTsOf_parsesTheEnd() {
        assertEquals(200L, NapStore.endTsOf(NapStore.idFor(cand(100, 200))))
    }

    @Test fun endTsOf_malformedIsNull() {
        assertNull(NapStore.endTsOf("not-an-id"))
        assertNull(NapStore.endTsOf("100|"))
        assertNull(NapStore.endTsOf("100"))
    }

    @Test fun shouldEnqueue_newWindowEnqueues() {
        assertTrue(NapStore.shouldEnqueue(cand(100, 200), emptySet(), emptySet()))
    }

    @Test fun shouldEnqueue_alreadyPendingDoesNot() {
        val c = cand(100, 200)
        assertFalse(NapStore.shouldEnqueue(c, setOf(NapStore.idFor(c)), emptySet()))
    }

    @Test fun shouldEnqueue_previouslyDismissedDoesNot() {
        val c = cand(100, 200)
        assertFalse(NapStore.shouldEnqueue(c, emptySet(), setOf(NapStore.idFor(c))))
    }

    @Test fun shouldEnqueue_differentWindowStillEnqueuesEvenWhenOthersPending() {
        val pending = setOf(NapStore.idFor(cand(100, 200)))
        assertTrue(NapStore.shouldEnqueue(cand(300, 400), pending, emptySet()))
    }

    @Test fun pruneDismissed_keepsFreshDropsStale() {
        val fresh = NapStore.idFor(cand(0, 10_000))
        val stale = NapStore.idFor(cand(0, 1_000))
        val kept = NapStore.pruneDismissed(setOf(fresh, stale), cutoff = 5_000)
        assertTrue(fresh in kept)
        assertFalse(stale in kept)
    }

    @Test fun pruneDismissed_dropsMalformedIds() {
        val good = NapStore.idFor(cand(0, 10_000))
        val kept = NapStore.pruneDismissed(setOf(good, "garbage", "5|"), cutoff = 1_000)
        assertEquals(setOf(good), kept)
    }

    @Test fun pruneDismissed_boundaryIsInclusive() {
        val onCutoff = NapStore.idFor(cand(0, 5_000))
        assertTrue(onCutoff in NapStore.pruneDismissed(setOf(onCutoff), cutoff = 5_000))
    }

    // ── Overlap, not exact id ────────────────────────────────────────────────

    /**
     * The detector re-reads the same stretch on every offload and its end moves. Real case, one night:
     * 00:12 to 01:10, then 00:12 to 01:40 twice with the end one minute apart, all three queued.
     */
    @Test fun shouldEnqueue_aLongerReadingOfAPendingStretchIsNotNew() {
        val pending = setOf(NapStore.idFor(cand(1_000, 4_480)))
        assertFalse(NapStore.shouldEnqueue(cand(1_000, 6_280), pending, emptySet()))
        assertFalse(NapStore.shouldEnqueue(cand(1_000, 6_220), pending, emptySet()))
    }

    @Test fun shouldEnqueue_aStretchOverlappingADismissedOneStaysDismissed() {
        val dismissed = setOf(NapStore.idFor(cand(1_000, 4_480)))
        assertFalse(NapStore.shouldEnqueue(cand(1_000, 6_280), emptySet(), dismissed))
        assertTrue(NapStore.shouldEnqueue(cand(6_400, 9_000), emptySet(), dismissed))
    }

    /** Back-to-back stretches are two naps: one ending where the next begins does not overlap it. */
    @Test fun overlaps_touchingEndsDoNotCount() {
        assertFalse(NapStore.overlaps(100, 200, 200, 300))
        assertTrue(NapStore.overlaps(100, 201, 200, 300))
        assertTrue(NapStore.overlaps(100, 400, 200, 300))
    }

    @Test fun queueAfter_keepsOneEntryPerStretchAndItIsTheLongest() {
        var q = NapStore.queueAfter(emptyList(), cand(1_000, 4_480), emptySet())
        q = NapStore.queueAfter(q, cand(1_000, 6_220), emptySet())
        q = NapStore.queueAfter(q, cand(1_000, 6_280), emptySet())
        q = NapStore.queueAfter(q, cand(1_000, 6_100), emptySet())
        assertEquals(listOf(cand(1_000, 6_280)), q)
    }

    @Test fun queueAfter_separateStretchesBothStayNewestFirst() {
        val q = NapStore.queueAfter(listOf(cand(1_000, 4_000)), cand(9_000, 12_000), emptySet())
        assertEquals(listOf(cand(9_000, 12_000), cand(1_000, 4_000)), q)
    }

    @Test fun queueAfter_aDismissedStretchChangesNothing() {
        val pending = listOf(cand(9_000, 12_000))
        val dismissed = setOf(NapStore.idFor(cand(1_000, 4_000)))
        assertEquals(pending, NapStore.queueAfter(pending, cand(1_500, 5_000), dismissed))
    }

    // ── A stretch that is already sleep on record ────────────────────────────

    /**
     * The reported case: the review list held 07:32 to 08:15, 06:23 to 07:30 and 00:12 to 01:40, all of
     * them inside the night's own session. Detection runs on every offload, the night's included.
     */
    @Test fun outsideSleep_dropsWhatFallsInsideARecordedSession() {
        val night = 10_000L to 40_000L
        val inside = listOf(cand(11_000, 16_000), cand(33_000, 37_000), cand(37_100, 39_700))
        assertEquals(emptyList<NapCandidate>(), NapStore.outsideSleep(inside, listOf(night)))
    }

    @Test fun outsideSleep_keepsANapClearOfEverySession() {
        val night = 10_000L to 40_000L
        val afternoon = cand(60_000, 62_400)
        assertEquals(listOf(afternoon), NapStore.outsideSleep(listOf(cand(11_000, 16_000), afternoon), listOf(night)))
    }

    /** Partly inside counts: the stretch before getting up is the end of the night, not a nap. */
    @Test fun outsideSleep_dropsAPartialOverlapButNotATouchingWindow() {
        val night = 10_000L to 40_000L
        assertEquals(emptyList<NapCandidate>(), NapStore.outsideSleep(listOf(cand(39_000, 41_000)), listOf(night)))
        assertEquals(listOf(cand(40_000, 42_000)), NapStore.outsideSleep(listOf(cand(40_000, 42_000)), listOf(night)))
    }

    @Test fun outsideSleep_withNoSessionsKeepsEverything() {
        val all = listOf(cand(1_000, 3_000), cand(5_000, 8_000))
        assertEquals(all, NapStore.outsideSleep(all, emptyList()))
    }
}
