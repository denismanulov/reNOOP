package com.noop.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The post-offload scoring pass waits for the offload burst to end, for at most ten minutes. The clock
 * is the test's own: `sleep` moves it, and the offload is a function of the time.
 */
class PostOffloadScoringHoldTest {

    private class Clock {
        var now = 0L
        var looks = 0
    }

    private fun hold(offloadRunningAt: (Long) -> Boolean): Pair<PostOffloadScoringHold.Outcome, Clock> {
        val clock = Clock()
        val outcome = runBlocking {
            PostOffloadScoringHold.await(
                offloadRunning = { clock.looks++; offloadRunningAt(clock.now) },
                nowMs = { clock.now },
                sleep = { clock.now += it },
            )
        }
        return outcome to clock
    }

    @Test
    fun theCapIsTheSwiftOne() {
        assertEquals(10 * 60 * 1_000L, PostOffloadScoringHold.MAX_HOLD_MS)
        assertTrue(
            "the settle must be seen by more than one look, and must be far below the cap",
            PostOffloadScoringHold.SETTLE_MS >= 2 * PostOffloadScoringHold.POLL_MS &&
                PostOffloadScoringHold.SETTLE_MS < PostOffloadScoringHold.MAX_HOLD_MS / 10,
        )
    }

    /** No offload in flight: the pass waits out the settle and reports nothing. */
    @Test
    fun aPassWithNoOffloadRunningWaitsOnlyTheSettle() {
        val (outcome, _) = hold { false }
        assertEquals(
            PostOffloadScoringHold.Outcome(
                waitedMs = PostOffloadScoringHold.SETTLE_MS, sawOffload = false,
                release = PostOffloadScoringHold.Release.OFFLOAD_IDLE, offloadRunningAtRelease = false,
            ),
            outcome,
        )
        assertNull("nothing was held, nothing to say", PostOffloadScoringHold.logLine(outcome))
    }

    /** The pass scheduled by the first chunk does not start while the same offload is still running. */
    @Test
    fun aRunningOffloadHoldsThePassUntilItHasStopped() {
        val (outcome, clock) = hold { now -> now < 90_000L }
        assertEquals(PostOffloadScoringHold.Release.OFFLOAD_IDLE, outcome.release)
        assertEquals("released one settle after the offload stopped, not before", 92_000L, outcome.waitedMs)
        assertTrue(outcome.sawOffload)
        assertFalse(outcome.offloadRunningAtRelease)
        assertEquals("one look per poll", 92_000 / PostOffloadScoringHold.POLL_MS.toInt() + 1, clock.looks)
        assertEquals(
            "re-score: trigger=post-offload waited 92s for the offload; released after 2s without one running",
            PostOffloadScoringHold.logLine(outcome),
        )
    }

    /** An auto-continue re-kick: `backfilling` drops between two sessions of one burst and comes back. */
    @Test
    fun theGapBetweenTwoSessionsOfOneBurstDoesNotRelease() {
        val gap = PostOffloadScoringHold.SETTLE_MS - PostOffloadScoringHold.POLL_MS
        val (outcome, _) = hold { now ->
            now < 60_000L || (now >= 60_000L + gap && now < 180_000L)
        }
        assertEquals("held across the gap, released after the LAST session", 182_000L, outcome.waitedMs)
        assertEquals(PostOffloadScoringHold.Release.OFFLOAD_IDLE, outcome.release)
    }

    /** A catch-up longer than the cap still gets scored: the hold ends at ten minutes, not before. */
    @Test
    fun anOffloadThatNeverStopsIsReleasedAtTheCap() {
        val (outcome, _) = hold { true }
        assertEquals(
            PostOffloadScoringHold.Outcome(
                waitedMs = PostOffloadScoringHold.MAX_HOLD_MS, sawOffload = true,
                release = PostOffloadScoringHold.Release.MAX_HOLD, offloadRunningAtRelease = true,
            ),
            outcome,
        )
        assertEquals(
            "re-score: trigger=post-offload waited 600s for the offload; released at the 10-minute cap, " +
                "offload still running",
            PostOffloadScoringHold.logLine(outcome),
        )
    }

    /** The cap is counted from the start of the hold, and the line says only what the last look found. */
    @Test
    fun theCapReleasesInsideAGapWithoutClaimingTheOffloadWasRunning() {
        val (outcome, _) = hold { now -> now < PostOffloadScoringHold.MAX_HOLD_MS - 1_000L }
        assertEquals(PostOffloadScoringHold.Release.MAX_HOLD, outcome.release)
        assertEquals(PostOffloadScoringHold.MAX_HOLD_MS, outcome.waitedMs)
        assertFalse(outcome.offloadRunningAtRelease)
        assertEquals(
            "re-score: trigger=post-offload waited 600s for the offload; released at the 10-minute cap, " +
                "offload not running at that moment",
            PostOffloadScoringHold.logLine(outcome),
        )
    }

    /** An offload that starts while the settle is being waited out is held too. */
    @Test
    fun anOffloadThatStartsDuringTheSettleIsHeld() {
        val (outcome, _) = hold { now -> now in 1_000L until 30_000L }
        assertTrue(outcome.sawOffload)
        assertEquals(32_000L, outcome.waitedMs)
    }

    /**
     * Where the hold sits. It may delay the phone's own re-score and nothing else, so it has to be inside
     * the coroutine the scoring pass is launched in, and nowhere on the path that acks a chunk: the
     * function a committed chunk calls returns at once, and that call follows the ack.
     */
    @Test
    fun theHoldSitsInTheScoringCoroutineAndNowhereOnTheAckPath() {
        val client = source("WhoopBleClient.kt")
        val backfiller = source("Backfiller.kt")
        assertFalse("the Backfiller must not know the hold exists", backfiller.contains("PostOffloadScoringHold"))
        assertEquals(1, Regex("""PostOffloadScoringHold\.await\(""").findAll(client).count())

        // A member function's body ends at the first closing brace on its own indentation.
        val schedule = client.substringAfter("    private fun schedulePostBackfillAnalysis() {", "")
            .substringBefore("\n    }\n")
        assertTrue("schedulePostBackfillAnalysis must stay a plain function that returns at once", schedule.isNotEmpty())
        val launched = schedule.indexOf("ioScope.launch {")
        val held = schedule.indexOf("PostOffloadScoringHold.await(")
        val scored = schedule.indexOf("IntelligenceEngine.analyzeRecent(")
        assertTrue("launched=$launched held=$held scored=$scored", launched in 0 until held && held < scored)
        // The chunks that asked for a pass during the wait are answered by this one, which is only true
        // while the flag is cleared BEFORE the store is read: a chunk landing after the clear sets it again.
        val answered = schedule.indexOf("analyzeAfterBackfillPending.set(false)")
        val fingerprinted = schedule.indexOf("repository.analysisFingerprint()")
        assertTrue(
            "held=$held answered=$answered fingerprinted=$fingerprinted scored=$scored",
            held < answered && answered < fingerprinted && fingerprinted < scored,
        )

        val finish = backfiller.substringAfter("    private suspend fun finishChunk(", "").substringBefore("\n    }\n")
        val acked = finish.indexOf("ackTrim(trim, endData)")
        val committed = finish.indexOf("let(onChunkCommitted)")
        assertTrue("acked=$acked committed=$committed", acked in 0 until committed)
        assertEquals("one ack site", 1, Regex("""ackTrim\(trim, endData\)""").findAll(backfiller).count())
    }

    private fun source(name: String): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rel = "src/main/java/com/noop/ble/$name"
        val file = listOf(File(userDir, rel), File(userDir, "app/$rel"), File(userDir, "android/app/$rel"))
            .firstOrNull { it.isFile }
        assertNotNull("$name not found from user.dir=$userDir; a skip would read as a pass", file)
        return file!!.readText()
    }
}
