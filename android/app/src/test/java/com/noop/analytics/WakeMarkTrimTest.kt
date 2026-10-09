package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a tapped "I'm awake" mark may do to a night: end it earlier, at the mark, and nothing else.
 *
 * The two real cases are one wearer's nights of 2026-10-07 and 2026-10-08, where the record shows him
 * up at 08:02 and 08:15 and the detector closed the night at 08:21:27 and 08:18:06.
 */
class WakeMarkTrimTest {

    private val h = 3600L
    private val start = 100 * h                  // an arbitrary bedtime
    private val end = start + 8 * h              // the detected end, eight hours later

    @Test fun aMarkShortlyBeforeTheDetectedEndBecomesTheEnd() {
        assertEquals(end - 186, WakeMarkTrim.trimmedEnd(start, end, end - 186))        // 3 min 6 s late
        assertEquals(end - 19 * 60, WakeMarkTrim.trimmedEnd(start, end, end - 19 * 60)) // 19 min late
    }

    /** A night is never made longer: a mark after the detected end leaves it alone. */
    @Test fun aMarkAtOrAfterTheDetectedEndChangesNothing() {
        assertNull(WakeMarkTrim.trimmedEnd(start, end, end))
        assertNull(WakeMarkTrim.trimmedEnd(start, end, end + 600))
    }

    /** A mark deep inside the night is a stray tap, not a wake the detector missed by hours. */
    @Test fun aMarkMoreThanAnHourBeforeTheEndChangesNothing() {
        assertEquals(end - h, WakeMarkTrim.trimmedEnd(start, end, end - h))
        assertNull(WakeMarkTrim.trimmedEnd(start, end, end - h - 1))
        assertNull(WakeMarkTrim.trimmedEnd(start, end, start + 600))
    }

    @Test fun aMarkBeforeTheNightBeganChangesNothing() {
        assertNull(WakeMarkTrim.trimmedEnd(start, end, start))
        assertNull(WakeMarkTrim.trimmedEnd(start, end, start - 60))
    }

    @Test fun decidePicksTheNightTheMarkFallsIn() {
        val earlier = (start - 30 * h) to (start - 22 * h)
        val tonight = start to end
        val d = WakeMarkTrim.decide(listOf(earlier, tonight), wakeTs = end - 186, nowTs = end + 60)
        assertEquals(WakeMarkTrim.Decision.Trim(index = 1, newEndTs = end - 186), d)
    }

    /**
     * The usual order of events: the mark is tapped before the night has synced, so the newest night on
     * record still ends before it. That is not "nothing to do"; the same night grows past the mark on
     * the next pass, and the mark has to still be there.
     */
    @Test fun decideWaitsWhileTheNightHasNotReachedTheMarkYet() {
        val partial = start to (end - 30 * 60)
        val wake = end - 186
        assertTrue(WakeMarkTrim.decide(listOf(partial), wake, nowTs = wake + 30) is WakeMarkTrim.Decision.Wait)
        assertEquals(
            WakeMarkTrim.Decision.Trim(0, wake),
            WakeMarkTrim.decide(listOf(start to end), wake, nowTs = wake + 900),
        )
    }

    @Test fun decideWaitsWithNoNightsAtAll() {
        assertTrue(WakeMarkTrim.decide(emptyList(), wakeTs = end, nowTs = end + 60) is WakeMarkTrim.Decision.Wait)
    }

    @Test fun decideExpiresAnOldMarkEvenIfANightWouldFit() {
        val wake = end - 186
        val late = wake + WakeMarkTrim.MAX_PENDING_SECONDS + 1
        assertTrue(WakeMarkTrim.decide(listOf(start to end), wake, late) is WakeMarkTrim.Decision.Expired)
        assertEquals(
            WakeMarkTrim.Decision.Trim(0, wake),
            WakeMarkTrim.decide(listOf(start to end), wake, wake + WakeMarkTrim.MAX_PENDING_SECONDS),
        )
    }
}
