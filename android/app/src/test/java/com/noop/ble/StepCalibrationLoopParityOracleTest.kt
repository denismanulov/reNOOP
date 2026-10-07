package com.noop.ble

import com.noop.analytics.StepCalibration
import com.noop.data.MemoryKeyValuePrefs
import com.noop.data.StepCalibrationStore
import com.noop.data.StepSample
import com.noop.protocol.Whoop4RawImu
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the WHOOP 4.0 step auto-calibration loop against the Swift source of truth by ORACLE, not by eye:
 * every switch write, every timer, every log line and the learned state, in order.
 *
 * [expected] is the verbatim stdout of the real `Strand/Collect/StepAutoCalibrator.swift` and
 * `Strand/BLE/RawStreamProbe.swift`, wired the way `BLEManager` wires them (the calibrator's
 * `startStream` / `stopStream` are the probe's `burstStarted` / `burstStopped`), compiled standalone
 * with `StepCalibrationStore.swift`, `StepCalibration.swift` and `GaitCadence.swift`
 * (`swiftc -O -swift-version 5 ... shims.swift main.swift && ./t`, Swift 6.3 on macOS arm64). The two
 * app files are unchanged but for their removed package imports; `shims.swift` restates the three
 * declarations they take from other modules (`StepSample`, `Whoop4RawImu.AccelBuffer` with its two
 * constants, `AnalyticsEngine.dayString`).
 *
 * The walk is built from integer arithmetic only (triangle waves in counts, a step every 66 samples),
 * so both languages hand [com.noop.analytics.GaitCadence] the same samples bit for bit, and the learned
 * state is compared as IEEE-754 bit patterns.
 *
 * Scripted: the whole loop with both acknowledgements, and then every way a burst is refused, abandoned
 * or unusable and every way a waiting measurement is dropped, each with the line it logs.
 *
 * Android differs from Swift in four places on purpose (an unknown battery, a history offload starting
 * or running, an answer that arrives before the switch was written, no Live-screen caller). None of
 * them occurs in this script; `StepAutoCalibratorTest`, `RawStreamProbeTest` and
 * `StepCalibrationCoordinatorTest` hold those.
 *
 * The oracle only guards this direction. `StepAutoCalibratorTests` and `RawStreamProbeTests` on the
 * Swift side are what stop Swift drifting away from Kotlin.
 */
class StepCalibrationLoopParityOracleTest {

    /** Verbatim stdout of the Swift build. Do not hand-edit: regenerate from the oracle. */
    private val expected = """
        == a walk is measured, paired and learned ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        log: Raw stream probe: writing the on switch alone (attempt 1/4)
        send: on
        schedule probe: 2000 ms
        log: Raw stream probe: strap acknowledged the on switch after 1 write(s)
        schedule calibrator: 50000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: gait 1.52 steps/s over 40 s (step line 1.00, stride line 1.00); waiting for the history of those seconds
        log: Raw stream probe: writing the off switch alone (attempt 1/4)
        send: off
        schedule probe: 2000 ms
        log: Raw stream probe: writing the off switch alone (attempt 2/4)
        send: off
        schedule probe: 2000 ms
        log: Raw stream probe: strap acknowledged the off switch after 2 write(s)
        offloadSettled battery=80 onScreen=false clock=1100
        log: Step calibration: accepted 78 ticks for 60.6 steps over 40 s (1.287 per step); 1970-01-01 now divides by 1.287, 1 accepted in all
        log: Step calibration: no burst, the last one was 1 min ago
        end: wantsFrames=false armed=false bursts=[1000] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=1970-01-01 dayTicks=4053800000000000 daySteps=404e4da3b23fe974 accepted=1 frozen={}
        == not opted in ==
        offloadSettled battery=80 onScreen=false clock=1000
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == standing still ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: no burst, the offload does not show walking right now
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == low battery ==
        offloadSettled battery=10 onScreen=false clock=1000
        log: Step calibration: no burst, strap battery 10% is under 15%
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == battery just above the floor ==
        offloadSettled battery=15 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        end: wantsFrames=true armed=true bursts=[1000] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == stale history ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: no burst, the offload does not show walking right now
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == app on screen, a waiting measurement still pairs ==
        offloadSettled battery=80 onScreen=true clock=1100
        log: Step calibration: accepted 78 ticks for 60.0 steps over 40 s (1.300 per step); 1970-01-01 now divides by 1.300, 1 accepted in all
        log: Step calibration: no burst, the app is on screen
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=1970-01-01 dayTicks=4053800000000000 daySteps=404e000000000000 accepted=1 frozen={}
        == no raw frame arrives ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        log: Raw stream probe: writing the on switch alone (attempt 1/4)
        send: on
        schedule probe: 2000 ms
        log: Raw stream probe: writing the on switch alone (attempt 2/4)
        send: on
        schedule probe: 2000 ms
        log: Raw stream probe: writing the on switch alone (attempt 3/4)
        send: on
        schedule probe: 2000 ms
        log: Raw stream probe: writing the on switch alone (attempt 4/4)
        send: on
        schedule probe: 2000 ms
        log: Raw stream probe: no answer to the on switch after 4 writes; giving up until the next connection
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst abandoned, no raw frame within 12 s
        log: Raw stream probe: writing the off switch alone (attempt 1/4)
        send: off
        schedule probe: 2000 ms
        log: Raw stream probe: writing the off switch alone (attempt 2/4)
        send: off
        schedule probe: 2000 ms
        log: Raw stream probe: writing the off switch alone (attempt 3/4)
        send: off
        schedule probe: 2000 ms
        log: Raw stream probe: writing the off switch alone (attempt 4/4)
        send: off
        schedule probe: 2000 ms
        log: Raw stream probe: no answer to the off switch after 4 writes; giving up until the next connection
        end: wantsFrames=false armed=true bursts=[1000] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == bursts are limited per day and spaced apart ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst abandoned, no raw frame within 12 s
        offloadSettled battery=80 onScreen=false clock=4660
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (2/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst abandoned, no raw frame within 12 s
        offloadSettled battery=80 onScreen=false clock=8320
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (3/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst abandoned, no raw frame within 12 s
        offloadSettled battery=80 onScreen=false clock=11980
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (4/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst abandoned, no raw frame within 12 s
        offloadSettled battery=80 onScreen=false clock=15640
        log: Step calibration: no burst, 4 already taken in the last 24 h
        offloadSettled battery=80 onScreen=false clock=19300
        log: Step calibration: no burst, 4 already taken in the last 24 h
        offloadSettled battery=80 onScreen=false clock=19900
        log: Step calibration: no burst, 4 already taken in the last 24 h
        end: wantsFrames=false armed=true bursts=[1000, 4660, 8320, 11980] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == a second offload ten minutes after a burst ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst abandoned, no raw frame within 12 s
        offloadSettled battery=80 onScreen=false clock=1600
        log: Step calibration: no burst, the last one was 10 min ago
        end: wantsFrames=false armed=true bursts=[1000] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == an octave-wrong gait is refused ==
        offloadSettled battery=10 onScreen=false clock=1100
        log: Step calibration: measurement refused, 78 ticks for 120.0 steps is 0.65 per step, outside 1.0-1.7
        log: Step calibration: no burst, strap battery 10% is under 15%
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == a measurement whose history never arrives ==
        offloadSettled battery=10 onScreen=false clock=1100
        log: Step calibration: no burst, strap battery 10% is under 15%
        offloadSettled battery=10 onScreen=false clock=22642
        log: Step calibration: measurement dropped, its history never arrived
        log: Step calibration: no burst, strap battery 10% is under 15%
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == history with gaps, a walk that began inside, a release inside ==
        offloadSettled battery=10 onScreen=false clock=1100
        log: Step calibration: measurement dropped, the history has gaps inside the window
        log: Step calibration: no burst, strap battery 10% is under 15%
        offloadSettled battery=10 onScreen=false clock=1100
        log: Step calibration: measurement dropped, the walk was not already under way before the window
        log: Step calibration: no burst, strap battery 10% is under 15%
        offloadSettled battery=10 onScreen=false clock=1100
        log: Step calibration: measurement dropped, a buffered release fell inside the window
        log: Step calibration: no burst, strap battery 10% is under 15%
        offloadSettled battery=10 onScreen=false clock=1100
        log: Step calibration: accepted 80 ticks for 60.0 steps over 40 s (1.333 per step); 1970-01-01 now divides by 1.333, 1 accepted in all
        log: Step calibration: no burst, strap battery 10% is under 15%
        end: wantsFrames=false armed=false bursts=[] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=1970-01-01 dayTicks=4054000000000000 daySteps=404e000000000000 accepted=1 frozen={}
        == a burst with too few contiguous seconds ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        schedule calibrator: 50000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst unusable, only 5 contiguous second(s) of raw data
        end: wantsFrames=false armed=true bursts=[1000] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == a burst with no clear gait ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        schedule calibrator: 50000 ms
        stream: stop
        schedule probe: 1500 ms
        log: Step calibration: burst had no clear gait over 40 s; nothing recorded
        end: wantsFrames=false armed=true bursts=[1000] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == the link drops during a burst, and the next connection switches the stream off ==
        offloadSettled battery=80 onScreen=false clock=1000
        log: Step calibration: walking seen in the offload; raw stream on for 40 s (1/4 in 24 h)
        stream: start
        schedule probe: 1500 ms
        schedule calibrator: 12000 ms
        log: Raw stream probe: writing the on switch alone (attempt 1/4)
        send: on
        schedule probe: 2000 ms
        log: Raw stream probe: strap acknowledged the on switch after 1 write(s)
        schedule calibrator: 50000 ms
        log: Step calibration: burst abandoned, link dropped
        schedule probe: 1500 ms
        log: Raw stream probe: writing the off switch alone (attempt 1/4)
        send: off
        schedule probe: 2000 ms
        log: Raw stream probe: strap acknowledged the off switch after 1 write(s)
        end: wantsFrames=false armed=false bursts=[1000] waiting=[]
        end: state emaTicks=0000000000000000 emaSteps=0000000000000000 day=nil dayTicks=0000000000000000 daySteps=0000000000000000 accepted=0 frozen={}
        == pure rules ==
        ticks steady: success(80)
        walking fresh: true
        walking stale: false
        walking flat: false
        walking empty: false
        walking wrap: true
        walking sparse: false
        longest run: [20, 21, 22, 23, 24]
        constants: burst=40 contiguous=30 firstFrame=12000 perDay=4 spacing=3600 battery=402e000000000000 fresh=30 walkWindow=12 walkTicks=10 pendingAge=21600 steady=6 attempts=4 first=1500 retry=2000
        keys: stepAutoCalibration.enabled stepAutoCalibration.state stepAutoCalibration.pending stepAutoCalibration.burstTimes rawStreamProbe.armed
    """.trimIndent()

    private val out = StringBuilder()

    private fun say(line: String) {
        out.append(line).append('\n')
    }

    private fun hex(x: Double): String = String.format(Locale.ROOT, "%016x", x.toRawBits())

    private fun dump(s: StepCalibration.State): String {
        val frozen = s.frozen.keys.sorted().joinToString(",") { "$it=${hex(s.frozen.getValue(it))}" }
        return "emaTicks=${hex(s.emaTicks)} emaSteps=${hex(s.emaSteps)} day=${s.day ?: "nil"}" +
            " dayTicks=${hex(s.dayTicks)} daySteps=${hex(s.daySteps)} accepted=${s.accepted} frozen={$frozen}"
    }

    private inner class Harness(title: String, enabled: Boolean = true) {
        val prefs = MemoryKeyValuePrefs()
        var clock = 1_000L
        var history: List<StepSample> = emptyList()
        var probeWork = ArrayList<() -> Unit>()
        var calibratorWork = ArrayList<() -> Unit>()
        val probe: RawStreamProbe
        val calibrator: StepAutoCalibrator

        init {
            say("== $title ==")
            StepCalibrationStore.setEnabled(prefs, enabled)
            probe = RawStreamProbe(
                prefs = prefs,
                send = { on -> say("send: ${if (on) "on" else "off"}") },
                log = { say("log: $it") },
                schedule = { delayMs, work ->
                    say("schedule probe: $delayMs ms")
                    probeWork.add(work)
                },
            )
            calibrator = StepAutoCalibrator(
                prefs = prefs,
                now = { clock },
                tzOffsetSeconds = { 0L },
                startStream = { say("stream: start"); probe.burstStarted() },
                stopStream = { say("stream: stop"); probe.burstStopped() },
                steps = { from, to -> history.filter { it.ts in from..to } },
                log = { say("log: $it") },
                schedule = { delayMs, work ->
                    say("schedule calibrator: $delayMs ms")
                    calibratorWork.add(work)
                },
            )
        }

        /** Runs the work that is due now. Work scheduled while running waits for the next call. */
        fun fireProbe() {
            val due = probeWork
            probeWork = ArrayList()
            due.forEach { it() }
        }

        fun fireCalibrator() {
            val due = calibratorWork
            calibratorWork = ArrayList()
            due.forEach { it() }
        }

        /** One history row a second over [range], the counter climbing 1.95 ticks a second from [origin]. */
        fun walk(range: LongRange, origin: Long = 900) {
            history = history + range.map { StepSample("test", it, (1.95 * (it - origin).toDouble()).toInt()) }
        }

        fun runBurst(first: Long, flat: Boolean = false) {
            for (ts in first until first + StepAutoCalibrator.BURST_SECONDS) calibrator.accept(packet(ts, flat))
        }

        fun wait(pending: List<StepAutoCalibrator.Pending>) {
            prefs.putString(StepAutoCalibrator.PENDING_KEY, StepAutoCalibrator.encodePending(pending))
        }

        fun settle(battery: Double?, onScreen: Boolean = false) = runBlocking {
            say("offloadSettled battery=${battery?.toInt() ?: "nil"} onScreen=$onScreen clock=$clock")
            calibrator.offloadSettled(batteryPct = battery, appOnScreen = onScreen)
        }

        fun finish() {
            val waiting = StepAutoCalibrator.decodePending(prefs.getString(StepAutoCalibrator.PENDING_KEY))
                .map { "\"${it.startTs}-${it.endTs}@${hex(it.stepHz)}\"" }
            val bursts = prefs.getString(StepAutoCalibrator.BURST_TIMES_KEY)
                ?.let { json -> org.json.JSONArray(json).let { a -> List(a.length()) { a.getLong(it) } } }
                ?: emptyList()
            say("end: wantsFrames=${calibrator.wantsFrames} armed=${prefs.getBoolean(RawStreamProbe.ARMED_KEY)}" +
                " bursts=$bursts waiting=$waiting")
            say("end: state ${dump(StepCalibrationStore.load(prefs))}")
        }
    }

    /** Triangle wave in counts: period [p] samples, peak [amp], phase [ph] samples. */
    private fun tri(i: Long, p: Long, amp: Long, ph: Long): Long {
        val k = (i + ph) % p
        val half = p / 2
        val up = if (k < half) k else p - k
        return amp * (2 * up - half) / half
    }

    /** The raw IMU packet of second [ts]: a step every 66 samples (about 1.515 steps a second). */
    private fun packet(ts: Long, flat: Boolean = false): Whoop4RawImu.AccelBuffer {
        val x = ShortArray(100)
        val y = ShortArray(100)
        val z = ShortArray(100)
        for (i in 0 until 100) {
            val n = ts * 100 + i
            x[i] = if (flat) 0 else tri(n, 132, 1024, 0).toShort()
            y[i] = if (flat) 4096 else (4096 + tri(n, 66, 819, 3)).toShort()
            z[i] = if (flat) 0 else tri(n, 132, 614, 22).toShort()
        }
        return Whoop4RawImu.AccelBuffer(timestamp = ts, subseconds = 0, x = x, y = y, z = z)
    }

    private fun steady(range: LongRange): List<StepSample> =
        range.map { StepSample("test", it, (2 * (it - 900)).toInt()) }

    private fun render(): String {
        // MARK: The loop, with every switch write

        Harness("a walk is measured, paired and learned").run {
            walk(940L..998L)
            settle(battery = 80.0)
            fireProbe()                        // the on switch, alone
            probe.responseReceived()
            runBurst(first = 1_001)            // 40 packets: the window is full, the stream goes off
            fireProbe()                        // the off switch, alone
            fireProbe()                        // unanswered: written again
            probe.responseReceived()
            fireProbe()                        // nothing more after the acknowledgement
            fireCalibrator()                   // the first-frame timeout and the backstop are both stale
            clock = 1_100
            walk(999L..1_045L)
            settle(battery = 80.0)             // pairs the measurement; no second burst inside the spacing limit
            finish()
        }

        // MARK: Every refusal

        Harness("not opted in", enabled = false).run {
            walk(940L..998L)
            settle(battery = 80.0)
            fireProbe(); fireCalibrator()
            finish()
        }
        Harness("standing still").run {
            history = (940L..998L).map { StepSample("test", it, 500) }
            settle(battery = 80.0)
            finish()
        }
        Harness("low battery").run {
            walk(940L..998L)
            settle(battery = 10.0)
            finish()
        }
        Harness("battery just above the floor").run {
            walk(940L..998L)
            settle(battery = 15.0)
            finish()
        }
        Harness("stale history").run {
            walk(880L..940L)
            settle(battery = 80.0)
            finish()
        }
        Harness("app on screen, a waiting measurement still pairs").run {
            wait(listOf(StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_041, stepHz = 1.5)))
            clock = 1_100
            walk(940L..1_098L)
            settle(battery = 80.0, onScreen = true)
            finish()
        }
        Harness("no raw frame arrives").run {
            walk(940L..998L)
            settle(battery = 80.0)
            repeat(6) { fireProbe() }          // four unanswered on switches, then it gives up
            fireCalibrator()                   // the first-frame timeout
            repeat(6) { fireProbe() }          // four unanswered off switches, then it gives up
            finish()
        }
        Harness("bursts are limited per day and spaced apart").run {
            for (round in 0 until 6) {
                clock = 1_000L + round * (StepAutoCalibrator.MIN_SPACING_SECONDS + 60)
                history = emptyList()
                walk((clock - 60)..(clock - 2), origin = clock - 200)
                settle(battery = 80.0)
                fireCalibrator()               // let each burst time out, freeing the calibrator
                probeWork = ArrayList()
            }
            clock += 600
            history = emptyList()
            walk((clock - 60)..(clock - 2), origin = clock - 200)
            settle(battery = 80.0)
            finish()
        }
        Harness("a second offload ten minutes after a burst").run {
            walk(940L..998L)
            settle(battery = 80.0)
            fireCalibrator()
            probeWork = ArrayList()
            clock = 1_600
            history = emptyList()
            walk(1_540L..1_598L, origin = 1_400)
            settle(battery = 80.0)
            finish()
        }
        Harness("an octave-wrong gait is refused").run {
            wait(listOf(StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_041, stepHz = 3.0)))
            clock = 1_100
            walk(940L..1_045L)
            settle(battery = 10.0)
            finish()
        }
        Harness("a measurement whose history never arrives").run {
            wait(listOf(StepAutoCalibrator.Pending(startTs = 1_001, endTs = 1_041, stepHz = 1.5)))
            clock = 1_100
            walk(940L..1_020L)                 // the history stops inside the window: keep waiting
            settle(battery = 10.0)
            clock = 1_041L + StepAutoCalibrator.PENDING_MAX_AGE_SECONDS + 1
            settle(battery = 10.0)
            finish()
        }
        Harness("history with gaps, a walk that began inside, a release inside").run {
            val window = listOf(StepAutoCalibrator.Pending(startTs = 1_000, endTs = 1_040, stepHz = 1.5))
            wait(window)
            clock = 1_100
            history = steady(990L..1_042L).filter { it.ts !in 1_010L..1_020L }
            settle(battery = 10.0)
            wait(window)
            history = (990L..1_000L).map { StepSample("test", it, 200) } +
                (1_001L..1_042L).map { StepSample("test", it, (200 + 2 * (it - 1_000)).toInt()) }
            settle(battery = 10.0)
            wait(window)
            history = steady(990L..1_042L).map { it.copy(counter = it.counter + if (it.ts >= 1_020) 12 else 0) }
            settle(battery = 10.0)
            wait(window)
            history = steady(990L..1_042L)     // and the same window, clean: 80 ticks for 60 steps
            settle(battery = 10.0)
            finish()
        }
        Harness("a burst with too few contiguous seconds").run {
            walk(940L..998L)
            settle(battery = 80.0)
            for (ts in listOf(1_001L, 1_002L, 1_003L, 1_003L, 1_010L, 1_011L, 1_012L, 1_013L, 1_014L, 1_020L)) {
                calibrator.accept(packet(ts))
            }
            fireCalibrator()                   // the backstop: packets stopped arriving
            finish()
        }
        Harness("a burst with no clear gait").run {
            walk(940L..998L)
            settle(battery = 80.0)
            runBurst(first = 1_001, flat = true)
            finish()
        }
        Harness("the link drops during a burst, and the next connection switches the stream off").run {
            walk(940L..998L)
            settle(battery = 80.0)
            fireProbe()
            probe.responseReceived()
            calibrator.accept(packet(1_001))
            calibrator.disconnected()
            probe.disconnected()
            fireProbe(); fireCalibrator()
            probe.connectSettled()
            fireProbe()
            probe.responseReceived()
            finish()
        }

        // MARK: Pure rules

        say("== pure rules ==")
        val window = StepAutoCalibrator.Pending(startTs = 1_000, endTs = 1_040, stepHz = 1.5)
        val steadyTicks = StepAutoCalibrator.ticks(window, steady(990L..1_042L)) as StepAutoCalibrator.Pairing.Ticks
        say("ticks steady: success(${steadyTicks.ticks})")
        say("walking fresh: ${StepAutoCalibrator.isWalkingNow(steady(960L..995L), now = 1_000)}")
        say("walking stale: ${StepAutoCalibrator.isWalkingNow(steady(900L..950L), now = 1_000)}")
        say("walking flat: ${StepAutoCalibrator.isWalkingNow((960L..995L).map { StepSample("test", it, 7) }, now = 1_000)}")
        say("walking empty: ${StepAutoCalibrator.isWalkingNow(emptyList(), now = 1_000)}")
        val wrapping = (960L..995L).map { StepSample("test", it, ((65_500 + 2 * (it - 960)) and 0xFFFF).toInt()) }
        say("walking wrap: ${StepAutoCalibrator.isWalkingNow(wrapping, now = 1_000)}")
        val sparse = listOf(StepSample("test", 970, 0), StepSample("test", 995, 40))
        say("walking sparse: ${StepAutoCalibrator.isWalkingNow(sparse, now = 1_000)}")
        val run = StepAutoCalibrator.longestContiguousRun(
            listOf(10L, 11L, 12L, 12L, 20L, 21L, 22L, 23L, 24L, 30L).map {
                Whoop4RawImu.AccelBuffer(timestamp = it, subseconds = 0, x = ShortArray(0), y = ShortArray(0), z = ShortArray(0))
            },
        ).map { it.timestamp }
        say("longest run: $run")
        say("constants: burst=${StepAutoCalibrator.BURST_SECONDS} contiguous=${StepAutoCalibrator.MINIMUM_CONTIGUOUS_SECONDS}" +
            " firstFrame=${StepAutoCalibrator.FIRST_FRAME_TIMEOUT_MS} perDay=${StepAutoCalibrator.MAX_BURSTS_PER_DAY}" +
            " spacing=${StepAutoCalibrator.MIN_SPACING_SECONDS} battery=${hex(StepAutoCalibrator.MIN_BATTERY_PCT)}" +
            " fresh=${StepAutoCalibrator.FRESHNESS_SECONDS} walkWindow=${StepAutoCalibrator.WALKING_WINDOW_SECONDS}" +
            " walkTicks=${StepAutoCalibrator.WALKING_MINIMUM_TICKS} pendingAge=${StepAutoCalibrator.PENDING_MAX_AGE_SECONDS}" +
            " steady=${StepAutoCalibrator.MAX_STEADY_TICKS_PER_SECOND} attempts=${RawStreamProbe.MAX_ATTEMPTS}" +
            " first=${RawStreamProbe.FIRST_DELAY_MS} retry=${RawStreamProbe.RETRY_DELAY_MS}")
        say("keys: ${StepCalibrationStore.ENABLED_KEY} ${StepCalibrationStore.STATE_KEY} ${StepAutoCalibrator.PENDING_KEY}" +
            " ${StepAutoCalibrator.BURST_TIMES_KEY} ${RawStreamProbe.ARMED_KEY}")
        return out.toString().trimEnd('\n')
    }

    @Test
    fun matchesTheSwiftBuildLineForLine() {
        val want = expected.lines()
        val got = render().lines()
        for (index in 0 until minOf(want.size, got.size)) {
            assertEquals("line ${index + 1} of the oracle", want[index], got[index])
        }
        assertEquals("line count", want.size, got.size)
        // The comparison cannot quietly stop comparing: 233 lines of Swift output.
        assertEquals(233, want.size)
    }
}
