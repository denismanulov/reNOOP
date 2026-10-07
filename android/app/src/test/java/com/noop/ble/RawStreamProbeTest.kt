package com.noop.ble

import com.noop.data.MemoryKeyValuePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RawStreamProbe] sends the WHOOP 4.0 raw-stream switch on its own and repeats it until the strap
 * answers. Twin of the Swift `RawStreamProbeTests`, with the same harness and expectations for the one
 * caller Android has, a calibration burst. These pin the three things that matter: nothing is sent
 * unless a burst asked for the stream, the write repeats until acknowledged and then stops, and a stream
 * the probe turned on is always turned off again, even by a later launch.
 *
 * The four Swift tests about the Live screen's opt-in (`testDoesNothingWhenNotOptedIn`,
 * `testReconnectWithALiveScreenUpTurnsTheStreamBackOn`,
 * `testABurstEndingLeavesTheStreamOnForAnOptedInLiveScreen`,
 * `testTheLiveScreenLeavingDuringABurstLeavesTheStreamToTheBurst`) have no twin: that caller is not
 * ported (see the class note). Where a Swift test only used `liveStarted` to ask for the stream, the
 * twin asks with `burstStarted`.
 */
class RawStreamProbeTest {

    private class Harness(val prefs: MemoryKeyValuePrefs = MemoryKeyValuePrefs()) {
        val sent = ArrayList<Boolean>()
        val logs = ArrayList<String>()
        val delays = ArrayList<Long>()
        var pending = ArrayList<() -> Unit>()
        val probe = RawStreamProbe(
            prefs = prefs,
            send = { on -> sent.add(on) },
            log = { line -> logs.add(line) },
            schedule = { delayMs, work -> delays.add(delayMs); pending.add(work) },
        )
        val armed: Boolean get() = prefs.getBoolean(RawStreamProbe.ARMED_KEY)

        /** Runs the scheduled work that is due now. Work scheduled while running waits for the next call. */
        fun fire() {
            val due = pending
            pending = ArrayList()
            due.forEach { it() }
        }
    }

    // MARK: - Twins of the Swift tests

    /** Swift `testDoesNothingWhenNotOptedIn`, for Android's only opt-in: no burst ever asked. */
    @Test
    fun doesNothingWhenNoBurstEverAskedForTheStream() {
        val h = Harness()
        h.probe.connectSettled()
        h.probe.disconnected()
        h.probe.connectSettled()
        h.probe.responseReceived()
        h.fire()
        assertEquals(emptyList<Boolean>(), h.sent)
        assertEquals("not even a timer is armed", emptyList<Long>(), h.delays)
        assertFalse(h.armed)
        assertEquals(0, h.prefs.writes)
    }

    @Test
    fun repeatsUntilTheStrapAnswersThenStops() {
        val h = Harness()
        h.probe.burstStarted()
        assertEquals("the write waits for its own moment, apart from every other command", emptyList<Boolean>(), h.sent)
        h.fire()
        h.fire()
        assertEquals(listOf(true, true), h.sent)
        h.probe.responseReceived()
        h.fire()
        h.fire()
        assertEquals("no write after the acknowledgement", listOf(true, true), h.sent)
        assertTrue(h.armed)
        assertEquals(listOf(RawStreamProbe.FIRST_DELAY_MS, RawStreamProbe.RETRY_DELAY_MS, RawStreamProbe.RETRY_DELAY_MS), h.delays)
    }

    @Test
    fun givesUpAfterTheAttemptLimit() {
        val h = Harness()
        h.probe.burstStarted()
        repeat(RawStreamProbe.MAX_ATTEMPTS + 3) { h.fire() }
        assertEquals(RawStreamProbe.MAX_ATTEMPTS, h.sent.size)
        assertEquals(1, h.logs.count { it.contains("giving up until the next connection") })
    }

    @Test
    fun stoppingTurnsTheStreamOffAndClearsTheMarkerOnAcknowledgement() {
        val h = Harness()
        h.probe.burstStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.burstStopped()
        h.fire()
        assertEquals(listOf(true, false), h.sent)
        assertTrue("still on until the strap answers", h.armed)
        h.probe.responseReceived()
        assertFalse(h.armed)
    }

    @Test
    fun aLaterConnectionStillTurnsTheStreamOff() {
        val h = Harness()
        h.probe.burstStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.disconnected()

        h.probe.connectSettled()
        h.fire()
        assertEquals(listOf(true, false), h.sent)
        h.probe.responseReceived()
        assertFalse(h.armed)
    }

    @Test
    fun disconnectCancelsPendingWrites() {
        val h = Harness()
        h.probe.burstStarted()
        h.probe.disconnected()
        h.fire()
        assertEquals(emptyList<Boolean>(), h.sent)
        assertTrue("asked for, so the next connection must switch it off", h.armed)
    }

    @Test
    fun aBurstSwitchesTheStreamOnAndOff() {
        val h = Harness()
        h.probe.burstStarted()
        h.fire()
        h.probe.responseReceived()
        assertEquals(listOf(true), h.sent)
        assertTrue(h.armed)
        h.probe.burstStopped()
        h.fire()
        h.probe.responseReceived()
        assertEquals(listOf(true, false), h.sent)
        assertFalse(h.armed)
    }

    @Test
    fun aResponseNobodyIsWaitingForChangesNothing() {
        val h = Harness()
        h.prefs.putBoolean(RawStreamProbe.ARMED_KEY, true)
        h.probe.responseReceived()
        assertTrue(h.armed)
        assertEquals(emptyList<Boolean>(), h.sent)
    }

    // MARK: - The off switch across launches and failures

    /** A later launch is a new process: a new probe over the same stored marker. */
    @Test
    fun aLaterLaunchStillTurnsTheStreamOff() {
        val first = Harness()
        first.probe.burstStarted()
        first.fire()
        first.probe.responseReceived()           // the stream is on, and the process dies here

        val second = Harness(first.prefs)
        second.probe.connectSettled()
        second.fire()
        assertEquals(listOf(false), second.sent)
        assertTrue(second.armed)
        second.probe.responseReceived()
        assertFalse(second.armed)
    }

    /** The marker is set when the stream is ASKED for, before the switch is written at all. */
    @Test
    fun theMarkerIsSetBeforeTheFirstWrite() {
        val h = Harness()
        h.probe.burstStarted()
        assertTrue(h.armed)
        assertEquals(emptyList<Boolean>(), h.sent)
    }

    /** An off switch nobody answers is given up on for this connection only, and tried again on the next. */
    @Test
    fun theOffSwitchIsWrittenOnEveryLaterConnectionUntilItIsAcknowledged() {
        val h = Harness()
        h.prefs.putBoolean(RawStreamProbe.ARMED_KEY, true)
        for (connection in 1..3) {
            h.probe.connectSettled()
            repeat(RawStreamProbe.MAX_ATTEMPTS + 2) { h.fire() }
            assertEquals(connection * RawStreamProbe.MAX_ATTEMPTS, h.sent.size)
            assertTrue("unanswered, so still marked", h.armed)
            h.probe.disconnected()
        }
        h.probe.connectSettled()
        h.fire()
        h.probe.responseReceived()
        assertFalse(h.armed)
        assertTrue(h.sent.none { it })

        // Acknowledged: the next connection writes nothing.
        val before = h.sent.size
        h.probe.disconnected()
        h.probe.connectSettled()
        h.fire()
        assertEquals(before, h.sent.size)
    }

    /** An answered off switch stops its own retries. */
    @Test
    fun noOffWriteAfterItsAcknowledgement() {
        val h = Harness()
        h.prefs.putBoolean(RawStreamProbe.ARMED_KEY, true)
        h.probe.connectSettled()
        h.fire()
        h.probe.responseReceived()
        repeat(4) { h.fire() }
        assertEquals(listOf(false), h.sent)
    }

    // MARK: - The rule Android adds

    /**
     * An answer that arrives before the off switch has been written cannot be an answer to it. Swift
     * takes it ("after 0 write(s)"), clears the marker and never writes the off switch; here it is
     * ignored and the switch goes out.
     */
    @Test
    fun anAnswerBeforeTheOffSwitchWasWrittenIsNotItsAcknowledgement() {
        val h = Harness()
        h.probe.burstStarted()
        h.fire()                                  // on, first write
        h.fire()                                  // on, second write: the first went unanswered
        h.probe.responseReceived()                // the answer to the first
        h.probe.burstStopped()                    // the burst ends: off is asked for, not yet written
        h.probe.responseReceived()                // the late answer to the second "on"
        assertTrue("the stream was never told to stop", h.armed)
        h.fire()
        assertEquals(listOf(true, true, false), h.sent)
        assertTrue(h.armed)
        h.probe.responseReceived()
        assertFalse(h.armed)
        assertTrue(h.logs.last().endsWith("acknowledged the off switch after 1 write(s)"))
    }

    /** The same on a new connection, where the handshake's own off switch is answered first. */
    @Test
    fun theHandshakesOwnAnswerDoesNotStandInForTheProbesOffSwitch() {
        val h = Harness()
        h.prefs.putBoolean(RawStreamProbe.ARMED_KEY, true)
        h.probe.connectSettled()
        h.probe.responseReceived()                // answers the handshake's write, 1.5 s before ours
        assertTrue(h.armed)
        h.fire()
        assertEquals(listOf(false), h.sent)
        h.probe.responseReceived()
        assertFalse(h.armed)
    }

    /** A burst that is over before its on switch was written never writes "on". */
    @Test
    fun aBurstEndedBeforeItsFirstWriteNeverSwitchesTheStreamOn() {
        val h = Harness()
        h.probe.burstStarted()
        h.probe.burstStopped()
        repeat(3) { h.fire() }
        assertTrue("only the off switch is written", h.sent.isNotEmpty() && h.sent.none { it })
    }

    /** Every line says which direction and which attempt, so a strap log can be read without the code. */
    @Test
    fun theLogNamesTheDirectionAndTheAttempt() {
        val h = Harness()
        h.probe.burstStarted()
        h.fire()
        h.probe.responseReceived()
        assertEquals(
            listOf(
                "Raw stream probe: writing the on switch alone (attempt 1/4)",
                "Raw stream probe: strap acknowledged the on switch after 1 write(s)",
            ),
            h.logs,
        )
    }

    @Test
    fun theKeyIsSwifts() {
        assertEquals("rawStreamProbe.armed", RawStreamProbe.ARMED_KEY)
    }
}
