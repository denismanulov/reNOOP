package com.noop.ble

import com.noop.analytics.StepCalibration
import com.noop.data.MemoryKeyValuePrefs
import com.noop.data.StepCalibrationStore
import com.noop.data.StepSample
import com.noop.protocol.Whoop4RawImu
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step auto-calibration loop end to end, with the strap replaced by a synthetic walk: 1.5 steps per
 * second on the accelerometer, 1.95 firmware ticks per second in the history (1.30 ticks per step).
 * Twin of the Swift `StepAutoCalibratorTests`: the same harness, the same inputs, the same expectations,
 * test for test. The tests after the Swift twins hold what Android adds.
 */
class StepAutoCalibratorTest {

    private class Harness(enabled: Boolean = true) {
        val prefs = MemoryKeyValuePrefs()
        var clock = 1_000L
        var history: List<StepSample> = emptyList()
        var streamOn = 0
        var streamOff = 0
        val logs = ArrayList<String>()
        var pending = ArrayList<() -> Unit>()
        var offloadInFlight = false
        val calibrator: StepAutoCalibrator

        init {
            StepCalibrationStore.setEnabled(prefs, enabled)
            calibrator = StepAutoCalibrator(
                prefs = prefs,
                now = { clock },
                tzOffsetSeconds = { 0L },
                startStream = { streamOn += 1 },
                stopStream = { streamOff += 1 },
                steps = { from, to -> history.filter { it.ts in from..to } },
                log = { logs.add(it) },
                schedule = { _, work -> pending.add(work) },
                offloadInFlight = { offloadInFlight },
            )
        }

        /** One history row a second over [range], the counter climbing 1.95 ticks a second from [origin]. */
        fun walk(range: LongRange, origin: Long = 900) {
            history = history + range.map { StepSample("test", it, (1.95 * (it - origin).toDouble()).toInt()) }
        }

        /** The raw IMU packet of second [ts] for a 1.5 steps-per-second gait. */
        fun packet(ts: Long): Whoop4RawImu.AccelBuffer {
            val x = ShortArray(100)
            val y = ShortArray(100)
            val z = ShortArray(100)
            for (i in 0 until 100) {
                val t = ts.toDouble() + i.toDouble() / 100
                val stride = 2 * PI * 0.75 * t
                val step = 2 * PI * 1.5 * t
                x[i] = (4096 * 0.25 * sin(stride)).toInt().toShort()
                y[i] = (4096 * (1 + 0.2 * sin(step))).toInt().toShort()
                z[i] = (4096 * 0.15 * sin(stride + 1.1)).toInt().toShort()
            }
            return Whoop4RawImu.AccelBuffer(timestamp = ts, subseconds = 0, x = x, y = y, z = z)
        }

        fun runBurst(first: Long) {
            for (ts in first until first + StepAutoCalibrator.BURST_SECONDS) calibrator.accept(packet(ts))
        }

        fun settle(batteryPct: Double?, appOnScreen: Boolean = false) = runBlocking {
            calibrator.offloadSettled(batteryPct = batteryPct, appOnScreen = appOnScreen)
        }

        fun wait(vararg pending: StepAutoCalibrator.Pending) {
            prefs.putString(StepAutoCalibrator.PENDING_KEY, StepAutoCalibrator.encodePending(pending.toList()))
        }

        val waiting: List<StepAutoCalibrator.Pending>
            get() = StepAutoCalibrator.decodePending(prefs.getString(StepAutoCalibrator.PENDING_KEY))
    }

    // MARK: - The loop

    @Test
    fun aWalkIsMeasuredPairedAndLearned() {
        val h = Harness()
        h.walk(940L..998L)
        h.settle(batteryPct = 80.0)
        assertEquals(1, h.streamOn)
        assertTrue(h.calibrator.wantsFrames)

        h.runBurst(first = 1_001)
        assertEquals("the stream goes off as soon as the window is full", 1, h.streamOff)
        assertFalse(h.calibrator.wantsFrames)

        // The history of those seconds arrives with a later offload.
        h.clock = 1_100
        h.walk(999L..1_045L)
        h.settle(batteryPct = 80.0)

        val state = StepCalibrationStore.load(h.prefs)
        assertEquals(1, state.accepted)
        assertEquals(1.30, StepCalibration.factor(state, day = "1970-01-01", manual = 1.0), 0.03)
        assertEquals("no second burst inside the spacing limit", 1, h.streamOn)
    }

    @Test
    fun nothingHappensWhenNotOptedIn() {
        val h = Harness(enabled = false)
        h.walk(940L..998L)
        h.settle(batteryPct = 80.0)
        assertEquals(0, h.streamOn)
        assertFalse(h.calibrator.wantsFrames)
    }

    @Test
    fun noBurstWithoutWalkingOrOnALowBattery() {
        val still = Harness()
        still.history = (940L..998L).map { StepSample("test", it, 500) }
        still.settle(batteryPct = 80.0)
        assertEquals(0, still.streamOn)

        val low = Harness()
        low.walk(940L..998L)
        low.settle(batteryPct = 10.0)
        assertEquals(0, low.streamOn)
        assertTrue("a refusal names its reason", low.logs.any { it.contains("strap battery 10%") })

        val stale = Harness()
        stale.walk(880L..940L)                // the newest record is a minute old
        stale.settle(batteryPct = 80.0)
        assertEquals(0, stale.streamOn)
    }

    @Test
    fun noBurstWhileTheAppIsOnScreenButWaitingMeasurementsStillPair() {
        val h = Harness()
        h.wait(StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_041, stepHz = 1.5))
        h.clock = 1_100
        h.walk(940L..1_098L)
        h.settle(batteryPct = 80.0, appOnScreen = true)
        assertEquals(0, h.streamOn)
        assertTrue(h.logs.any { it.contains("the app is on screen") })
        assertEquals(1, StepCalibrationStore.load(h.prefs).accepted)
    }

    @Test
    fun noRawFramesAbandonsTheBurstAndSwitchesTheStreamOff() {
        val h = Harness()
        h.walk(940L..998L)
        h.settle(batteryPct = 80.0)
        h.pending.toList().forEach { it() }   // the first-frame timeout
        assertEquals(1, h.streamOff)
        assertFalse(h.calibrator.wantsFrames)
        assertEquals(null, h.waiting.firstOrNull())
    }

    @Test
    fun burstsAreLimitedPerDayAndSpacedApart() {
        val h = Harness()
        for (round in 0 until 6) {
            h.clock = 1_000L + round * (StepAutoCalibrator.MIN_SPACING_SECONDS + 60)
            h.history = emptyList()
            h.walk((h.clock - 60)..(h.clock - 2), origin = h.clock - 200)
            h.settle(batteryPct = 80.0)
            h.pending.toList().forEach { it() }   // let each burst time out, freeing the calibrator
            h.pending = ArrayList()
        }
        assertEquals(StepAutoCalibrator.MAX_BURSTS_PER_DAY, h.streamOn)
    }

    @Test
    fun anOctaveWrongGaitIsRefusedNotLearned() {
        val h = Harness()
        // A waiting measurement that claims twice the real cadence over a window the history covers.
        h.wait(StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_041, stepHz = 3.0))
        h.clock = 1_100
        h.walk(940L..1_045L)
        h.settle(batteryPct = 10.0)
        assertEquals(0, StepCalibrationStore.load(h.prefs).accepted)
        assertTrue(h.logs.any { it.contains("refused") })
    }

    // MARK: - Pairing rules

    private fun steady(range: LongRange): List<StepSample> =
        range.map { StepSample("test", it, (2 * (it - 900)).toInt()) }

    private val window = StepAutoCalibrator.Pending(startTs = 1_000, endTs = 1_040, stepHz = 1.5)

    private fun failure(history: List<StepSample>) =
        (StepAutoCalibrator.ticks(window, history) as StepAutoCalibrator.Pairing.Failed).failure

    @Test
    fun ticksOverACoveredSteadyWindow() {
        assertEquals(StepAutoCalibrator.Pairing.Ticks(80), StepAutoCalibrator.ticks(window, steady(990L..1_042L)))
    }

    @Test
    fun aWindowWithHistoryGapsIsNotPaired() {
        val gappy = steady(990L..1_042L).filter { it.ts !in 1_010L..1_020L }
        assertEquals(StepAutoCalibrator.PairingFailure.HISTORY_HAS_GAPS, failure(gappy))
    }

    @Test
    fun aWalkThatStartedInsideTheWindowIsNotPaired() {
        // Flat until the window starts: whatever the counter then adds includes a buffered release.
        val flat = (990L..1_000L).map { StepSample("test", it, 200) }
        val after = (1_001L..1_042L).map { StepSample("test", it, (200 + 2 * (it - 1_000)).toInt()) }
        assertEquals(StepAutoCalibrator.PairingFailure.WALK_NOT_UNDER_WAY, failure(flat + after))
    }

    @Test
    fun aReleaseInsideTheWindowIsNotPaired() {
        val samples = steady(990L..1_042L).map { if (it.ts >= 1_020) it.copy(counter = it.counter + 12) else it }
        assertEquals(StepAutoCalibrator.PairingFailure.RELEASE_INSIDE_WINDOW, failure(samples))
    }

    @Test
    fun walkingNowNeedsFreshClimbingHistory() {
        assertTrue(StepAutoCalibrator.isWalkingNow(steady(960L..995L), now = 1_000))
        assertFalse(StepAutoCalibrator.isWalkingNow(steady(900L..950L), now = 1_000))
        assertFalse(StepAutoCalibrator.isWalkingNow((960L..995L).map { StepSample("test", it, 7) }, now = 1_000))
        assertFalse(StepAutoCalibrator.isWalkingNow(emptyList(), now = 1_000))
    }

    @Test
    fun longestContiguousRunSkipsGapsAndDuplicates() {
        val h = Harness()
        val seconds = listOf(10L, 11L, 12L, 12L, 20L, 21L, 22L, 23L, 24L, 30L)
        val run = StepAutoCalibrator.longestContiguousRun(seconds.map(h::packet))
        assertEquals(listOf(20L, 21L, 22L, 23L, 24L), run.map { it.timestamp })
    }

    // MARK: - Limits, each with its one log line

    /** The bounds the feature is allowed: these numbers are the contract, not tuning. */
    @Test
    fun theLimitsAreSwifts() {
        assertEquals(40, StepAutoCalibrator.BURST_SECONDS)
        assertEquals(4, StepAutoCalibrator.MAX_BURSTS_PER_DAY)
        assertEquals(3_600, StepAutoCalibrator.MIN_SPACING_SECONDS)
        assertEquals(15.0, StepAutoCalibrator.MIN_BATTERY_PCT, 0.0)
        assertEquals(12_000L, StepAutoCalibrator.FIRST_FRAME_TIMEOUT_MS)
        assertEquals("stepAutoCalibration.pending", StepAutoCalibrator.PENDING_KEY)
        assertEquals("stepAutoCalibration.burstTimes", StepAutoCalibrator.BURST_TIMES_KEY)
    }

    @Test
    fun everyRefusalLogsItsReasonOnce() {
        fun refusal(prepare: Harness.() -> Unit, battery: Double? = 80.0, onScreen: Boolean = false): List<String> {
            val h = Harness()
            h.walk(940L..998L)
            h.prepare()
            h.settle(batteryPct = battery, appOnScreen = onScreen)
            assertEquals("a refused burst never touches the stream", 0, h.streamOn + h.streamOff)
            return h.logs
        }
        assertEquals(listOf("Step calibration: no burst, the app is on screen"), refusal({}, onScreen = true))
        assertEquals(
            listOf("Step calibration: no burst, strap battery 14% is under 15%"),
            refusal({}, battery = 14.5),          // Swift's "%.0f" sends the tie to the even digit
        )
        assertEquals(
            listOf("Step calibration: no burst, the offload does not show walking right now"),
            refusal({ history = emptyList() }),
        )
        val burstsKey = StepAutoCalibrator.BURST_TIMES_KEY
        assertEquals(
            listOf("Step calibration: no burst, 4 already taken in the last 24 h"),
            refusal({ prefs.putString(burstsKey, "[-80000,-70000,-60000,-50000]") }),
        )
        assertEquals(
            listOf("Step calibration: no burst, the last one was 59 min ago"),
            refusal({ prefs.putString(burstsKey, "[-2599]") }),
        )
        // A burst older than 24 h no longer counts, and exactly an hour is far enough apart.
        val allowed = Harness()
        allowed.walk(940L..998L)
        allowed.prefs.putString(burstsKey, "[-85400,-85401,-85402,-2600]")
        allowed.settle(batteryPct = 80.0)
        assertEquals(1, allowed.streamOn)
        assertEquals("[-2600,1000]", allowed.prefs.getString(burstsKey))
    }

    /** At most four bursts in ANY 24 hours, however the offloads fall: a fifth waits for the first to age out. */
    @Test
    fun aFifthBurstWaitsUntilTheFirstIsADayOld() {
        val h = Harness()
        fun attempt(at: Long): Int {
            h.clock = at
            h.history = emptyList()
            h.walk((at - 60)..(at - 2), origin = at - 200)
            val before = h.streamOn
            h.settle(batteryPct = 80.0)
            h.pending.toList().forEach { it() }
            h.pending = ArrayList()
            return h.streamOn - before
        }
        val starts = listOf(1_000L, 5_000L, 9_000L, 13_000L)
        for (at in starts) assertEquals(1, attempt(at))
        assertEquals(0, attempt(20_000))
        assertEquals(0, attempt(1_000L + 86_399))
        assertEquals(1, attempt(1_000L + 86_400))
    }

    // MARK: - What Android adds

    /** Not under 15 % cannot be shown for a battery that is not known, so no burst is taken. */
    @Test
    fun anUnknownBatteryRefusesTheBurst() {
        val h = Harness()
        h.walk(940L..998L)
        h.settle(batteryPct = null)
        assertEquals(0, h.streamOn)
        assertEquals(listOf("Step calibration: no burst, the strap battery is not known yet"), h.logs)
    }

    /** No burst starts while a history offload of ours is running. */
    @Test
    fun noBurstWhileAHistoryOffloadIsRunning() {
        val h = Harness()
        h.walk(940L..998L)
        h.offloadInFlight = true
        h.settle(batteryPct = 80.0)
        assertEquals(0, h.streamOn)
        assertEquals(listOf("Step calibration: no burst, a history offload is running"), h.logs)
        assertEquals("a refused burst does not use up one of the day's four", null, h.prefs.getString(StepAutoCalibrator.BURST_TIMES_KEY))
    }

    /** An offload that begins during a burst ends it: the stream goes off and nothing is recorded. */
    @Test
    fun aHistoryOffloadStartingEndsTheBurst() {
        val h = Harness()
        h.walk(940L..998L)
        h.settle(batteryPct = 80.0)
        for (ts in 1_001L..1_020L) h.calibrator.accept(h.packet(ts))
        h.calibrator.offloadStarted()
        assertEquals(1, h.streamOff)
        assertFalse(h.calibrator.wantsFrames)
        assertEquals(emptyList<StepAutoCalibrator.Pending>(), h.waiting)
        assertEquals("Step calibration: burst abandoned, a history offload started", h.logs.last())
        // Stale timers and late packets do nothing more.
        h.pending.toList().forEach { it() }
        h.calibrator.accept(h.packet(1_021))
        assertEquals(1, h.streamOff)
    }

    @Test
    fun aHistoryOffloadStartingWithNoBurstDoesNothing() {
        val h = Harness()
        h.calibrator.offloadStarted()
        assertEquals(0, h.streamOn + h.streamOff)
        assertEquals(emptyList<String>(), h.logs)
        assertEquals(0, h.prefs.writes - 1)       // only the harness's own opt-in write
    }

    /**
     * A stored window no burst could have written is dropped without being walked: a reversed one traps
     * in Swift, and a very long one would hold the BLE thread for as long as it takes to count it.
     */
    @Test
    fun aStoredWindowThatIsNotABurstsIsDropped() {
        val h = Harness()
        h.clock = 1_100
        h.walk(940L..1_098L)
        h.wait(
            StepAutoCalibrator.Pending(startTs = 1_041, endTs = 1_001, stepHz = 1.5),
            StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_001, stepHz = 1.5),
            StepAutoCalibrator.Pending(startTs = -4_000_000_000_000L, endTs = 1_041, stepHz = 1.5),
            StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_041, stepHz = 1.5),
        )
        h.settle(batteryPct = 10.0)
        assertEquals(3, h.logs.count { it == "Step calibration: measurement dropped, its stored window is not a burst's" })
        assertEquals("the real one still pairs", 1, StepCalibrationStore.load(h.prefs).accepted)
        assertEquals(emptyList<StepAutoCalibrator.Pending>(), h.waiting)
    }

    // MARK: - Opt-in off

    /**
     * Off: whatever the offloads show, the stream is never touched, no timer is armed and no burst is
     * counted. A measurement taken while it was on still pairs (that reads the store, not the strap).
     */
    @Test
    fun offNeverTouchesTheStreamWhateverTheHistoryShows() {
        val h = Harness(enabled = false)
        for (round in 0 until 8) {
            h.clock = 1_000L + round * 4_000L
            h.history = emptyList()
            h.walk((h.clock - 60)..(h.clock - 2), origin = h.clock - 200)
            h.settle(batteryPct = 80.0)
            h.calibrator.accept(h.packet(h.clock))
            h.calibrator.offloadStarted()
            h.calibrator.disconnected()
        }
        assertEquals(0, h.streamOn + h.streamOff)
        assertEquals(emptyList<() -> Unit>(), h.pending)
        assertEquals(emptyList<String>(), h.logs)
        assertFalse(h.calibrator.wantsFrames)
        assertEquals(null, h.prefs.getString(StepAutoCalibrator.BURST_TIMES_KEY))
    }

    /** Packets outside a burst are ignored: nothing is buffered and nothing ends. */
    @Test
    fun packetsOutsideABurstAreIgnored() {
        val h = Harness()
        h.runBurst(first = 1_001)
        assertEquals(0, h.streamOff)
        assertEquals(emptyList<StepAutoCalibrator.Pending>(), h.waiting)
    }

    /**
     * A waiting measurement is stored as the JSON Swift's `JSONEncoder` writes for `[Pending]`: an array
     * of objects with these three keys, in whatever order.
     */
    @Test
    fun aWaitingMeasurementIsStoredUnderSwiftsKeys() {
        val one = StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_041, stepHz = 1.5)
        val two = StepAutoCalibrator.Pending(startTs = 5_000, endTs = 5_040, stepHz = 1.8125)
        val stored = org.json.JSONArray(StepAutoCalibrator.encodePending(listOf(one, two)))
        assertEquals(2, stored.length())
        assertEquals(setOf("startTs", "endTs", "stepHz"), stored.getJSONObject(0).keySet())
        assertEquals(1_001L, stored.getJSONObject(0).getLong("startTs"))
        assertEquals(1_041L, stored.getJSONObject(0).getLong("endTs"))
        assertEquals(1.5, stored.getJSONObject(0).getDouble("stepHz"), 0.0)
        assertEquals(listOf(one, two), StepAutoCalibrator.decodePending(StepAutoCalibrator.encodePending(listOf(one, two))))
        // What Swift wrote for the same measurement, key order as its encoder happened to emit it.
        assertEquals(listOf(one), StepAutoCalibrator.decodePending("""[{"stepHz":1.5,"endTs":1041,"startTs":1001}]"""))
        assertEquals(emptyList<StepAutoCalibrator.Pending>(), StepAutoCalibrator.decodePending(null))
        assertEquals(emptyList<StepAutoCalibrator.Pending>(), StepAutoCalibrator.decodePending("not json"))
        assertEquals(emptyList<StepAutoCalibrator.Pending>(), StepAutoCalibrator.decodePending("""[{"startTs":1001}]"""))
    }
}
