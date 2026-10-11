package com.noop.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every stored night is staged again once per learned stage model, and the model it was staged with is
 * recorded only once that pass has returned. Twin of the pending rule and the ordering of Swift
 * `IntelligenceEngine.runSleepStageModelRestageIfNeeded`.
 */
class SleepStageRestageTest {

    @After
    fun tearDown() { SleepStageLearned.model = null }

    private fun install(version: String) {
        SleepStageLearned.model = SleepStageModel(version, null, 1.0, 1.0, first = { null }, second = { null })
    }

    @Test
    fun theHistoryIsPendingUntilItWasStagedWithTheInstalledModel() {
        assertFalse(IntelligenceEngine.sleepStageRestageIsPending(installed = "", restaged = null))
        assertFalse(IntelligenceEngine.sleepStageRestageIsPending(installed = "", restaged = "a"))
        assertTrue(IntelligenceEngine.sleepStageRestageIsPending(installed = "a", restaged = null))
        assertTrue(IntelligenceEngine.sleepStageRestageIsPending(installed = "b", restaged = "a"))
        assertFalse(IntelligenceEngine.sleepStageRestageIsPending(installed = "a", restaged = "a"))
    }

    @Test
    fun withNoModelNothingRunsAndNothingIsRecorded() = runBlocking {
        var stored: String? = null
        var passes = 0
        val ran = IntelligenceEngine.runSleepStageModelRestageIfNeeded({ stored }, { stored = it }) { passes++ }
        assertFalse(ran)
        assertEquals(0, passes)
        assertNull(stored)
    }

    @Test
    fun thePassRunsOncePerModel() = runBlocking {
        var stored: String? = null
        var passes = 0
        install("a")
        assertTrue(IntelligenceEngine.runSleepStageModelRestageIfNeeded({ stored }, { stored = it }) {
            assertNull("the version is not recorded before the pass has returned", stored)
            passes++
        })
        assertEquals("a", stored)
        assertFalse(IntelligenceEngine.runSleepStageModelRestageIfNeeded({ stored }, { stored = it }) { passes++ })
        assertEquals(1, passes)

        // A build with refit models stages the history again.
        install("b")
        assertTrue(IntelligenceEngine.runSleepStageModelRestageIfNeeded({ stored }, { stored = it }) { passes++ })
        assertEquals("b", stored)
        assertEquals(2, passes)
    }

    @Test
    fun aPassThatWasCancelledOrFailedRunsAgain() = runBlocking {
        var stored: String? = null
        install("a")
        for (failure in listOf(CancellationException("left"), IllegalStateException("store"))) {
            try {
                IntelligenceEngine.runSleepStageModelRestageIfNeeded({ stored }, { stored = it }) { throw failure }
                fail("the failure is the caller's to see")
            } catch (e: Exception) {
                assertEquals(failure, e)
            }
            assertNull(stored)
        }
        var passes = 0
        assertTrue(IntelligenceEngine.runSleepStageModelRestageIfNeeded({ stored }, { stored = it }) { passes++ })
        assertEquals(1, passes)
        assertEquals("a", stored)
    }
}
