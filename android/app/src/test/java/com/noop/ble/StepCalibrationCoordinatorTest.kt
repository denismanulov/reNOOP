package com.noop.ble

import com.noop.data.MemoryKeyValuePrefs
import com.noop.data.StepCalibrationStore
import com.noop.data.StepSample
import com.noop.protocol.Whoop4RawImu
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepCalibrationCoordinator] is everything the BLE client does for WHOOP 4.0 step auto-calibration.
 * These run it on a virtual clock, so they hold WHEN the raw-stream switch is written as well as
 * whether: the only thing the feature ever writes to a strap.
 */
class StepCalibrationCoordinatorTest {

    private class Harness(enabled: Boolean, armed: Boolean = false) {
        val prefs = MemoryKeyValuePrefs()
        var nowMs = 1_000_000L
        private var sequence = 0
        private val timers = ArrayList<Triple<Long, Int, () -> Unit>>()

        /** Every switch write: virtual time since the start, and the direction. */
        val writes = ArrayList<Pair<Long, Boolean>>()
        val logs = ArrayList<String>()
        var history: List<StepSample> = emptyList()
        var whoop4 = true
        var connected = true
        var offloadInFlight = false
        var battery: Double? = 80.0
        var onScreen = false
        val coordinator: StepCalibrationCoordinator

        init {
            if (enabled) StepCalibrationStore.setEnabled(prefs, true)
            if (armed) prefs.putBoolean(RawStreamProbe.ARMED_KEY, true)
            coordinator = StepCalibrationCoordinator(
                prefs = prefs,
                sendSwitch = { on -> writes.add((nowMs - 1_000_000L) to on) },
                steps = { from, to -> history.filter { it.ts in from..to } },
                log = { logs.add(it) },
                schedule = { delayMs, work -> timers.add(Triple(nowMs + delayMs, sequence++, work)) },
                launch = { block -> runBlocking { block() } },
                isWhoop4 = { whoop4 },
                isConnected = { connected },
                offloadInFlight = { offloadInFlight },
                batteryPct = { battery },
                appOnScreen = { onScreen },
                now = { nowMs / 1_000L },
                tzOffsetSeconds = { 0L },
            )
        }

        val prefsWritesAtStart = prefs.writes
        val armed: Boolean get() = prefs.getBoolean(RawStreamProbe.ARMED_KEY)
        val timersArmed: Int get() = timers.size

        /** Moves the clock forward, running each timer at its own time, in the order they fall due. */
        fun advance(ms: Long) {
            val until = nowMs + ms
            while (true) {
                val next = timers.filter { it.first <= until }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                timers.remove(next)
                nowMs = next.first
                next.third()
            }
            nowMs = until
        }

        /** History that shows steady walking right up to now (two ticks a second). */
        fun walkingNow() {
            val now = nowMs / 1_000L
            history = ((now - 60)..(now - 2)).map { StepSample("test", it, (2 * (it - now + 500)).toInt()) }
        }

        /** One raw IMU packet per second from [first], a clear 1.5 steps-per-second gait. */
        fun streamPackets(first: Long, count: Int) {
            for (ts in first until first + count) {
                if (coordinator.wantsFrames) coordinator.rawImu(packet(ts))
            }
        }

        private fun packet(ts: Long): Whoop4RawImu.AccelBuffer {
            val x = ShortArray(100)
            val y = ShortArray(100)
            val z = ShortArray(100)
            for (i in 0 until 100) {
                val t = ts.toDouble() + i.toDouble() / 100
                x[i] = (1_024 * kotlin.math.sin(2 * kotlin.math.PI * 0.75 * t)).toInt().toShort()
                y[i] = (4_096 + 819 * kotlin.math.sin(2 * kotlin.math.PI * 1.5 * t)).toInt().toShort()
                z[i] = (614 * kotlin.math.sin(2 * kotlin.math.PI * 0.75 * t + 1.1)).toInt().toShort()
            }
            return Whoop4RawImu.AccelBuffer(timestamp = ts, subseconds = 0, x = x, y = y, z = z)
        }
    }

    // MARK: - Opt-in off

    /**
     * The default install: the opt-in off and no stream ever asked for. Through connections, offloads
     * that show walking, raw frames, acknowledgements and disconnects, the switch is never written, no
     * preference is written, and nothing is logged.
     */
    @Test
    fun offWritesNothingToTheStrapWhateverHappens() {
        val h = Harness(enabled = false)
        repeat(6) { round ->
            h.coordinator.connectSettled()
            h.advance(10_000)
            h.offloadInFlight = true
            h.coordinator.offloadStarted()
            h.offloadInFlight = false
            h.walkingNow()
            h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
            h.advance(StepCalibrationCoordinator.SETTLE_DELAY_MS)
            assertFalse(h.coordinator.wantsFrames)
            h.streamPackets(first = h.nowMs / 1_000L, count = 5)
            h.coordinator.switchAcknowledged()
            h.advance(4_000_000)                  // over an hour: the spacing limit is not what holds it back
            if (round % 2 == 1) h.coordinator.disconnected()
        }
        assertEquals(emptyList<Pair<Long, Boolean>>(), h.writes)
        assertEquals(emptyList<String>(), h.logs)
        assertEquals(h.prefsWritesAtStart, h.prefs.writes)
        assertFalse(h.armed)
    }

    /** Off, but an earlier session left the stream marked on: the OFF switch, and only it, is written. */
    @Test
    fun offStillSwitchesOffAStreamAnEarlierSessionLeftOn() {
        val h = Harness(enabled = false, armed = true)
        h.coordinator.connectSettled()
        h.advance(RawStreamProbe.FIRST_DELAY_MS - 1)
        assertEquals("alone, 1.5 s after the handshake was written", emptyList<Pair<Long, Boolean>>(), h.writes)
        h.advance(1)
        assertEquals(listOf(1_500L to false), h.writes)
        h.advance(RawStreamProbe.RETRY_DELAY_MS)  // unanswered: written again 2 s later
        assertEquals(listOf(1_500L to false, 3_500L to false), h.writes)
        h.coordinator.switchAcknowledged()
        assertFalse(h.armed)
        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(600_000)
        assertEquals("nothing after the acknowledgement, and never an on switch", 2, h.writes.size)
    }

    // MARK: - One measurement, on the clock

    @Test
    fun aBurstWritesOnThenOffAndNothingElse() {
        val h = Harness(enabled = true)
        h.coordinator.connectSettled()
        h.advance(60_000)
        assertEquals("nothing was ever asked for, so the connection writes nothing", emptyList<Pair<Long, Boolean>>(), h.writes)

        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(StepCalibrationCoordinator.SETTLE_DELAY_MS - 1)
        assertFalse("the check waits five seconds for a following offload", h.armed)
        h.advance(1)
        assertTrue("the marker is set as soon as the stream is asked for", h.armed)
        assertTrue(h.coordinator.wantsFrames)
        assertEquals(emptyList<Pair<Long, Boolean>>(), h.writes)

        h.advance(RawStreamProbe.FIRST_DELAY_MS)
        assertEquals(listOf(66_500L to true), h.writes)
        h.coordinator.switchAcknowledged()
        h.streamPackets(first = h.nowMs / 1_000L, count = StepAutoCalibrator.BURST_SECONDS)
        assertFalse("forty seconds of packets end the burst", h.coordinator.wantsFrames)
        h.advance(RawStreamProbe.FIRST_DELAY_MS)
        assertEquals(listOf(66_500L to true, 68_000L to false), h.writes)
        h.coordinator.switchAcknowledged()
        assertFalse(h.armed)

        h.advance(3_000_000)
        assertEquals("no retry, no timer and no second burst after that", 2, h.writes.size)
        assertEquals(1, h.logs.count { it.startsWith("Step calibration: gait ") })
    }

    /** A burst the strap never answers and never streams for: four on, then four off, then silence. */
    @Test
    fun anUnansweredBurstIsBoundedToFourWritesEachWay() {
        val h = Harness(enabled = true)
        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(3_000_000)
        assertEquals(listOf(true, true, true, true, false, false, false, false), h.writes.map { it.second })
        // On at +1.5, 3.5, 5.5, 7.5 s; the first-frame timeout at +12 s; off from +13.5 s.
        assertEquals(listOf(6_500L, 8_500L, 10_500L, 12_500L, 18_500L, 20_500L, 22_500L, 24_500L), h.writes.map { it.first })
        assertTrue("unanswered: the next connection writes the off switch again", h.armed)
        assertEquals(0, h.timersArmed)
    }

    // MARK: - The gates the client's hooks carry

    private fun settledBurst(prepare: Harness.() -> Unit, reason: String = StepCalibrationCoordinator.COMPLETE): Harness {
        val h = Harness(enabled = true)
        h.walkingNow()
        h.prepare()
        h.coordinator.offloadEnded(reason)
        h.advance(StepCalibrationCoordinator.SETTLE_DELAY_MS + 60_000)
        return h
    }

    @Test
    fun onlyATrueHistoryCompleteOnAWhoop4StartsAnything() {
        assertEquals(4, settledBurst({}).writes.count { it.second })
        for (reason in listOf("timeout", "aborted by user", "disconnect", "")) {
            val h = settledBurst({}, reason = reason)
            assertEquals(reason, emptyList<Pair<Long, Boolean>>(), h.writes)
            assertEquals(reason, 0, h.prefs.writes - 1)
        }
        val other = settledBurst({ whoop4 = false })
        assertEquals(emptyList<Pair<Long, Boolean>>(), other.writes)
        assertEquals(emptyList<String>(), other.logs)
    }

    @Test
    fun noBurstWhenAnotherOffloadIsRunningOrTheLinkIsDownFiveSecondsLater() {
        val running = Harness(enabled = true)
        running.walkingNow()
        running.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        running.offloadInFlight = true            // the auto-continue kicked the next one
        running.advance(60_000)
        assertEquals(emptyList<Pair<Long, Boolean>>(), running.writes)

        val down = Harness(enabled = true)
        down.walkingNow()
        down.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        down.connected = false
        down.advance(60_000)
        assertEquals(emptyList<Pair<Long, Boolean>>(), down.writes)

        val dropped = Harness(enabled = true)
        dropped.walkingNow()
        dropped.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        dropped.coordinator.disconnected()        // dropped and back up inside the five seconds
        dropped.advance(60_000)
        assertEquals(emptyList<Pair<Long, Boolean>>(), dropped.writes)
    }

    /** Offloads ending back to back: one check, five seconds after the LAST. */
    @Test
    fun ofSeveralOffloadsEndingBackToBackOnlyTheLastOnesCheckRuns() {
        val h = Harness(enabled = true)
        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(3_000)
        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(3_000)                          // 6 s after the first, 3 s after the second
        assertFalse(h.armed)
        h.advance(2_000)
        assertTrue(h.armed)
        assertEquals(1, h.logs.count { it.contains("walking seen in the offload") })
    }

    @Test
    fun theRefusalsTheClientFeedsAreHonoured() {
        assertEquals(
            listOf("Step calibration: no burst, the app is on screen"),
            settledBurst({ onScreen = true }).logs,
        )
        assertEquals(
            listOf("Step calibration: no burst, strap battery 9% is under 15%"),
            settledBurst({ battery = 9.0 }).logs,
        )
        assertEquals(
            listOf("Step calibration: no burst, the strap battery is not known yet"),
            settledBurst({ battery = null }).logs,
        )
        for (h in listOf(settledBurst({ onScreen = true }), settledBurst({ battery = 9.0 }), settledBurst({ battery = null }))) {
            assertEquals(emptyList<Pair<Long, Boolean>>(), h.writes)
        }
    }

    // MARK: - A history offload and a burst never share the air for long

    /** An offload that begins before the on switch went out: the stream is never switched on. */
    @Test
    fun anOffloadStartingBeforeTheOnSwitchWasWrittenMeansItNeverIs() {
        val h = Harness(enabled = true)
        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(StepCalibrationCoordinator.SETTLE_DELAY_MS + 1_000)   // asked for, not yet written
        h.offloadInFlight = true
        h.coordinator.offloadStarted()
        h.advance(60_000)
        assertTrue(h.writes.isNotEmpty())
        assertTrue("only the off switch", h.writes.none { it.second })
        assertTrue(h.logs.contains("Step calibration: burst abandoned, a history offload started"))
    }

    /** An offload that begins during the burst ends it: off is written 1.5 s later, not 40 s later. */
    @Test
    fun anOffloadStartingDuringABurstSwitchesTheStreamOff() {
        val h = Harness(enabled = true)
        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(StepCalibrationCoordinator.SETTLE_DELAY_MS + RawStreamProbe.FIRST_DELAY_MS)
        h.coordinator.switchAcknowledged()
        h.streamPackets(first = h.nowMs / 1_000L, count = 10)
        h.advance(10_000)
        h.offloadInFlight = true
        h.coordinator.offloadStarted()
        assertFalse(h.coordinator.wantsFrames)
        h.advance(RawStreamProbe.FIRST_DELAY_MS)
        assertEquals(listOf(6_500L to true, 18_000L to false), h.writes)
    }

    // MARK: - Disconnects

    @Test
    fun aLinkDropDuringABurstLeavesTheMarkerAndTheNextConnectionSwitchesOff() {
        val h = Harness(enabled = true)
        h.walkingNow()
        h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
        h.advance(StepCalibrationCoordinator.SETTLE_DELAY_MS + RawStreamProbe.FIRST_DELAY_MS)
        h.coordinator.switchAcknowledged()
        h.coordinator.disconnected()
        assertFalse(h.coordinator.wantsFrames)
        h.advance(120_000)
        assertEquals("nothing can be written on a dead link", listOf(true), h.writes.map { it.second })
        assertTrue(h.armed)

        h.coordinator.connectSettled()
        h.advance(RawStreamProbe.FIRST_DELAY_MS)
        assertEquals(listOf(true, false), h.writes.map { it.second })
        h.coordinator.switchAcknowledged()
        assertFalse(h.armed)
    }

    /** A 5/MG link cannot carry the WHOOP 4.0 switch: the marker waits for the 4.0 to come back. */
    @Test
    fun aMarkedStreamIsNotSwitchedOffOverAnotherStrapFamily() {
        val h = Harness(enabled = false, armed = true)
        h.whoop4 = false
        h.coordinator.connectSettled()
        h.advance(60_000)
        assertEquals(emptyList<Pair<Long, Boolean>>(), h.writes)
        assertTrue(h.armed)
    }

    // MARK: - A whole day

    /**
     * A day of offloads every 15 minutes while walking, each burst answered and filled: at most four
     * bursts in 24 hours, at least an hour apart, and every "on" followed by an acknowledged "off".
     */
    @Test
    fun aDayOfWalkingTakesFourBurstsAnHourApart() {
        val h = Harness(enabled = true)
        h.coordinator.connectSettled()
        repeat(96) {
            h.walkingNow()
            h.coordinator.offloadEnded(StepCalibrationCoordinator.COMPLETE)
            h.advance(StepCalibrationCoordinator.SETTLE_DELAY_MS + RawStreamProbe.FIRST_DELAY_MS)
            if (h.coordinator.wantsFrames) {
                h.coordinator.switchAcknowledged()
                h.streamPackets(first = h.nowMs / 1_000L, count = StepAutoCalibrator.BURST_SECONDS)
                h.advance(RawStreamProbe.FIRST_DELAY_MS)
                h.coordinator.switchAcknowledged()
            }
            h.advance(15 * 60_000L - StepCalibrationCoordinator.SETTLE_DELAY_MS - 2 * RawStreamProbe.FIRST_DELAY_MS)
        }
        val on = h.writes.filter { it.second }.map { it.first }
        val off = h.writes.filter { !it.second }.map { it.first }
        assertEquals(StepAutoCalibrator.MAX_BURSTS_PER_DAY, on.size)
        assertEquals(on.size, off.size)
        for ((earlier, later) in on.zipWithNext()) {
            assertTrue(later - earlier >= StepAutoCalibrator.MIN_SPACING_SECONDS * 1_000L)
        }
        for ((start, stop) in on.zip(off)) assertTrue(stop > start && stop - start <= 60_000)
        assertFalse(h.armed)
    }
}
