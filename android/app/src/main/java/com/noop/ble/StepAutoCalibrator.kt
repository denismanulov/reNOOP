package com.noop.ble

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.GaitCadence
import com.noop.analytics.StepCalibration
import com.noop.data.KeyValuePrefs
import com.noop.data.StepCalibrationStore
import com.noop.data.StepSample
import com.noop.protocol.Whoop4RawImu
import org.json.JSONArray
import org.json.JSONObject

/**
 * Takes the short raw-accelerometer measurements [StepCalibration] learns from, on a WHOOP 4.0. Kotlin
 * twin of the Swift `StepAutoCalibrator` (Strand/Collect/StepAutoCalibrator.swift): the same limits, the
 * same pairing rules, the same log lines.
 *
 * One measurement: right after a history offload shows the wearer walking, the raw stream is switched
 * on for [BURST_SECONDS], [GaitCadence] reads the step frequency from it, and the stream is switched
 * off. The firmware ticks for those same seconds only exist once a later offload brings their history
 * records, so the measurement waits as [Pending] until then.
 *
 * WHAT KEEPS IT CHEAP. At most [MAX_BURSTS_PER_DAY] bursts in any 24 hours, at least
 * [MIN_SPACING_SECONDS] apart, never below [MIN_BATTERY_PCT], and only when the newest history already
 * shows steady walking. A burst that finds no clear gait costs its seconds and records nothing.
 *
 * WHAT KEEPS IT HONEST. A measurement is used only when the history covers its whole window, the walk
 * was already under way before the window (so no buffered release falls inside it), and the resulting
 * ratio passes [StepCalibration.accepts]. Everything else is dropped with one log line saying why.
 *
 * WHERE ANDROID IS STRICTER THAN SWIFT, each with its own log line:
 * - an unknown strap battery refuses a burst. Swift only refuses a known one under the floor.
 * - no burst starts while a history offload of ours is running ([offloadInFlight], asked again after
 *   the last read), and one that begins during a burst ends it ([offloadStarted]). Swift checks once,
 *   five seconds after the offload, and lets a burst and a later offload overlap.
 * - a stored waiting measurement whose window is not a burst's (empty, reversed, longer than a burst)
 *   is dropped before anything walks it.
 *
 * The cadence read here feeds the day's step divisor and nothing else: never recovery, never illness.
 *
 * Not thread-safe: the BLE client drives it on the main looper. [wantsFrames] alone may be read from
 * the thread frames arrive on.
 */
class StepAutoCalibrator(
    private val prefs: KeyValuePrefs,
    private val now: () -> Long,
    private val tzOffsetSeconds: () -> Long,
    private val startStream: () -> Unit,
    private val stopStream: () -> Unit,
    private val steps: suspend (from: Long, to: Long) -> List<StepSample>,
    private val log: (String) -> Unit,
    private val schedule: (delayMs: Long, work: () -> Unit) -> Unit,
    private val offloadInFlight: () -> Boolean = { false },
) {
    companion object {
        const val PENDING_KEY = "stepAutoCalibration.pending"
        const val BURST_TIMES_KEY = "stepAutoCalibration.burstTimes"

        const val BURST_SECONDS = 40

        /** A window with fewer contiguous seconds than this is not analysed. */
        const val MINIMUM_CONTIGUOUS_SECONDS = 30
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        const val MAX_BURSTS_PER_DAY = 4
        const val MIN_SPACING_SECONDS = 3_600

        /**
         * No burst below this strap charge. A burst is 40 seconds of streaming, so the guard is there for
         * a strap about to run out, not to save charge in general: a higher floor (30 was tried) kept the
         * calibration idle for the last third of every charge cycle.
         */
        const val MIN_BATTERY_PCT = 15.0

        /** The newest history record must be this recent for "walking now" to mean now. */
        const val FRESHNESS_SECONDS = 30

        /** Ticks the counter must have added over the last [WALKING_WINDOW_SECONDS] to call it walking. */
        const val WALKING_WINDOW_SECONDS = 12
        const val WALKING_MINIMUM_TICKS = 10

        /** A pending measurement whose history never arrives is dropped after this long. */
        const val PENDING_MAX_AGE_SECONDS = 6 * 3_600

        /** The largest one-second increment a steady walk produces; anything above is a buffered release. */
        const val MAX_STEADY_TICKS_PER_SECOND = 6

        /**
         * Firmware ticks over a pending measurement's window, or why the window cannot be trusted.
         * Swift twin: `StepAutoCalibrator.ticks(for:in:)`.
         */
        fun ticks(pending: Pending, history: List<StepSample>): Pairing {
            val counter = HashMap<Long, Int>()
            for (sample in history) counter[sample.ts] = sample.counter
            val seconds = pending.endTs - pending.startTs
            val inside = (pending.startTs..pending.endTs).filter { counter.containsKey(it) }
            val start = counter[pending.startTs]
            val end = counter[pending.endTs]
            if (start == null || end == null || inside.size * 10L < (seconds + 1) * 9) {
                return Pairing.Failed(PairingFailure.HISTORY_HAS_GAPS)
            }
            val before = counter[pending.startTs - 8]
            if (before == null || ((start - before) and 0xFFFF) < 6) {
                return Pairing.Failed(PairingFailure.WALK_NOT_UNDER_WAY)
            }
            for ((earlier, later) in inside.zipWithNext()) {
                val perSecond = ((counter.getValue(later) - counter.getValue(earlier)) and 0xFFFF).toDouble() /
                    (later - earlier).toDouble()
                if (perSecond > MAX_STEADY_TICKS_PER_SECOND.toDouble()) {
                    return Pairing.Failed(PairingFailure.RELEASE_INSIDE_WINDOW)
                }
            }
            return Pairing.Ticks((end - start) and 0xFFFF)
        }

        /**
         * True when the newest history is fresh and the counter has been climbing through its last
         * seconds. Swift twin: `StepAutoCalibrator.isWalkingNow(_:now:)`.
         */
        fun isWalkingNow(history: List<StepSample>, now: Long): Boolean {
            val sorted = history.sortedBy { it.ts }
            val newest = sorted.lastOrNull() ?: return false
            if (now - newest.ts > FRESHNESS_SECONDS) return false
            val earlier = sorted.lastOrNull { it.ts <= newest.ts - WALKING_WINDOW_SECONDS } ?: return false
            if (newest.ts - earlier.ts > WALKING_WINDOW_SECONDS + 3) return false
            return ((newest.counter - earlier.counter) and 0xFFFF) >= WALKING_MINIMUM_TICKS
        }

        /**
         * The longest stretch of packets whose timestamps run second by second, one packet per second.
         * Swift twin: `StepAutoCalibrator.longestContiguousRun(_:)`.
         */
        fun longestContiguousRun(buffers: List<Whoop4RawImu.AccelBuffer>): List<Whoop4RawImu.AccelBuffer> {
            val bySecond = HashMap<Long, Whoop4RawImu.AccelBuffer>()
            for (buffer in buffers) if (!bySecond.containsKey(buffer.timestamp)) bySecond[buffer.timestamp] = buffer
            var best: List<Whoop4RawImu.AccelBuffer> = emptyList()
            var run = ArrayList<Whoop4RawImu.AccelBuffer>()
            for (second in bySecond.keys.sorted()) {
                val last = run.lastOrNull()
                if (last != null && second != last.timestamp + 1) run = ArrayList()
                run.add(bySecond.getValue(second))
                if (run.size > best.size) best = ArrayList(run)
            }
            return best
        }

        /** The JSON Swift's `JSONEncoder` writes for `[Pending]`: an array of objects with these three keys. */
        internal fun encodePending(pending: List<Pending>): String {
            val array = JSONArray()
            for (item in pending) {
                array.put(JSONObject().put("startTs", item.startTs).put("endTs", item.endTs).put("stepHz", item.stepHz))
            }
            return array.toString()
        }

        /** Empty when [json] is not such an array in full, like Swift's failed decode. */
        internal fun decodePending(json: String?): List<Pending> {
            if (json == null) return emptyList()
            return try {
                val array = JSONArray(json)
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    Pending(startTs = item.getLong("startTs"), endTs = item.getLong("endTs"), stepHz = item.getDouble("stepHz"))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** One burst's reading, waiting for the history of its seconds. */
    data class Pending(val startTs: Long, val endTs: Long, val stepHz: Double)

    enum class PairingFailure(val reason: String) {
        HISTORY_HAS_GAPS("the history has gaps inside the window"),
        WALK_NOT_UNDER_WAY("the walk was not already under way before the window"),
        RELEASE_INSIDE_WINDOW("a buffered release fell inside the window"),
    }

    /** What [ticks] found: the tick count, or why the window cannot be trusted. */
    sealed class Pairing {
        data class Ticks(val ticks: Int) : Pairing()
        data class Failed(val failure: PairingFailure) : Pairing()
    }

    private enum class Phase { IDLE, AWAITING_FIRST_FRAME, COLLECTING }

    private var phase = Phase.IDLE
        set(value) {
            field = value
            wantsFrames = value != Phase.IDLE
        }
    private var buffers = ArrayList<Whoop4RawImu.AccelBuffer>()

    /** Bumped whenever a burst starts or ends, so a timer armed for an earlier one does nothing. */
    private var generation = 0

    /**
     * True while [offloadSettled] is between its suspensions, so offloads that end back to back cannot
     * pair the same waiting measurement twice.
     */
    private var settling = false

    /** True while a burst wants raw frames; the BLE client decodes them only then. */
    @Volatile
    var wantsFrames: Boolean = false
        private set

    // MARK: - Driven by the BLE client

    /**
     * A history offload finished and none followed it. Pairs waiting measurements with the history
     * that just arrived, then decides whether to measure now.
     *
     * [appOnScreen] is true when the wearer is looking at this app on the phone. No burst is taken
     * then: that arm is held still, and the factor measured so (1.10 to 1.14 on 2026-10-03, against
     * 1.23 to 1.27 with the arm free) is not the one the day's other steps were walked with.
     */
    suspend fun offloadSettled(batteryPct: Double?, appOnScreen: Boolean) {
        if (settling) return
        settling = true
        try {
            resolvePending()
            if (!StepCalibrationStore.isEnabled(prefs) || phase != Phase.IDLE) return
            // Every way out below says why. With the opt-in on, a calibration that never measures has to
            // be explainable from the strap log alone.
            if (appOnScreen) {
                log("Step calibration: no burst, the app is on screen")
                return
            }
            if (batteryPct == null) {
                log("Step calibration: no burst, the strap battery is not known yet")
                return
            }
            if (batteryPct < MIN_BATTERY_PCT) {
                log("Step calibration: no burst, strap battery ${fmt(batteryPct, 0)}% is" +
                    " under ${MIN_BATTERY_PCT.toInt()}%")
                return
            }
            val at = now()
            val recent = burstTimes().filter { at - it < 86_400 }
            if (recent.size >= MAX_BURSTS_PER_DAY) {
                log("Step calibration: no burst, ${recent.size} already taken in the last 24 h")
                return
            }
            val last = recent.maxOrNull()
            if (last != null && at - last < MIN_SPACING_SECONDS) {
                log("Step calibration: no burst, the last one was ${(at - last) / 60} min ago")
                return
            }
            val history = steps(at - FRESHNESS_SECONDS - WALKING_WINDOW_SECONDS - 5, at + 5)
            if (phase != Phase.IDLE) return
            if (!isWalkingNow(history, at)) {
                log("Step calibration: no burst, the offload does not show walking right now")
                return
            }
            if (offloadInFlight()) {
                log("Step calibration: no burst, a history offload is running")
                return
            }

            generation += 1
            val started = generation
            phase = Phase.AWAITING_FIRST_FRAME
            buffers = ArrayList()
            saveBurstTimes(recent + at)
            log("Step calibration: walking seen in the offload; raw stream on for $BURST_SECONDS s" +
                " (${recent.size + 1}/$MAX_BURSTS_PER_DAY in 24 h)")
            startStream()
            schedule(FIRST_FRAME_TIMEOUT_MS) {
                if (generation != started || phase != Phase.AWAITING_FIRST_FRAME) return@schedule
                end(reason = "no raw frame within ${FIRST_FRAME_TIMEOUT_MS / 1_000} s")
            }
        } finally {
            settling = false
        }
    }

    /** One decoded raw IMU packet. Ignored unless a burst is running. */
    fun accept(buffer: Whoop4RawImu.AccelBuffer) {
        if (phase == Phase.IDLE) return
        if (phase == Phase.AWAITING_FIRST_FRAME) {
            phase = Phase.COLLECTING
            val started = generation
            // A backstop: the strap sends one packet a second, so the count below normally ends the
            // burst first. This fires only when packets stop arriving.
            schedule((BURST_SECONDS + 10) * 1_000L) {
                if (generation != started || phase != Phase.COLLECTING) return@schedule
                end(reason = null)
            }
        }
        buffers.add(buffer)
        if (buffers.mapTo(HashSet()) { it.timestamp }.size >= BURST_SECONDS) end(reason = null)
    }

    /** The link dropped mid-burst: the stream is gone with it and the samples are not a full window. */
    fun disconnected() {
        if (phase == Phase.IDLE) return
        generation += 1
        phase = Phase.IDLE
        buffers = ArrayList()
        log("Step calibration: burst abandoned, link dropped")
    }

    /**
     * A history offload of ours began. A burst in flight ends here and its stream is switched off: the
     * raw stream takes air time the offload needs, and what was collected is not a full window. No
     * Swift twin (see the class note).
     */
    fun offloadStarted() {
        if (phase == Phase.IDLE) return
        end(reason = "a history offload started")
    }

    // MARK: - Burst end

    private fun end(reason: String?) {
        generation += 1
        phase = Phase.IDLE
        stopStream()
        val collected = buffers
        buffers = ArrayList()
        if (reason != null) {
            log("Step calibration: burst abandoned, $reason")
            return
        }
        val window = longestContiguousRun(collected)
        val first = window.firstOrNull()
        val last = window.lastOrNull()
        if (window.size < MINIMUM_CONTIGUOUS_SECONDS || first == null || last == null) {
            log("Step calibration: burst unusable, only ${window.size} contiguous second(s) of raw data")
            return
        }
        fun g(axis: (Whoop4RawImu.AccelBuffer) -> ShortArray): DoubleArray {
            val out = DoubleArray(window.sumOf { axis(it).size })
            var index = 0
            for (buffer in window) for (sample in axis(buffer)) out[index++] = sample.toDouble() * Whoop4RawImu.gPerLSB
            return out
        }
        val rate = Whoop4RawImu.samplesPerPacket.toDouble()
        val estimate = GaitCadence.estimate(x = g { it.x }, y = g { it.y }, z = g { it.z }, sampleRate = rate)
        if (estimate == null) {
            log("Step calibration: burst had no clear gait over ${window.size} s; nothing recorded")
            return
        }
        val pending = Pending(startTs = first.timestamp, endTs = last.timestamp + 1, stepHz = estimate.stepHz)
        savePending(loadPending() + pending)
        log("Step calibration: gait ${fmt(estimate.stepHz, 2)} steps/s over" +
            " ${window.size} s (step line ${fmt(estimate.stepStrength, 2)}," +
            " stride line ${fmt(estimate.strideStrength, 2)});" +
            " waiting for the history of those seconds")
    }

    // MARK: - Pairing with history

    private suspend fun resolvePending() {
        val waiting = loadPending()
        if (waiting.isEmpty()) return
        val still = ArrayList<Pending>()
        for (pending in waiting) {
            val at = now()
            if (at - pending.endTs > PENDING_MAX_AGE_SECONDS) {
                log("Step calibration: measurement dropped, its history never arrived")
                continue
            }
            val seconds = pending.endTs - pending.startTs
            // A burst's window is at most [BURST_SECONDS] long. Anything else was not written by this
            // class, and walking a window of arbitrary size second by second would stall the thread
            // the BLE link runs on. Swift has no such check: there a window that ends before it starts
            // traps, and a very long one hangs.
            if (seconds <= 0 || seconds > BURST_SECONDS) {
                log("Step calibration: measurement dropped, its stored window is not a burst's")
                continue
            }
            val history = steps(pending.startTs - 10, pending.endTs + 2)
            val newest = history.maxOfOrNull { it.ts }
            if (newest == null || newest < pending.endTs) {
                still.add(pending)
                continue
            }
            val stepCount = pending.stepHz * seconds.toDouble()
            when (val pairing = ticks(pending, history)) {
                is Pairing.Failed -> log("Step calibration: measurement dropped, ${pairing.failure.reason}")
                is Pairing.Ticks -> {
                    val ticks = pairing.ticks
                    val ratio = ticks.toDouble() / stepCount
                    if (!StepCalibration.accepts(steps = stepCount, ticks = ticks.toDouble())) {
                        log("Step calibration: measurement refused, $ticks ticks for" +
                            " ${fmt(stepCount, 1)} steps is" +
                            " ${fmt(ratio, 2)} per step, outside" +
                            " ${StepCalibration.RATIO_BAND.start}-${StepCalibration.RATIO_BAND.endInclusive}")
                        continue
                    }
                    val day = AnalyticsEngine.dayString(pending.startTs, tzOffsetSeconds())
                    val state = StepCalibrationStore.record(prefs, day = day, steps = stepCount, ticks = ticks.toDouble())
                    val dayFactor = state.frozen[day] ?: StepCalibration.factor(state, day = day, manual = ratio)
                    log("Step calibration: accepted $ticks ticks for" +
                        " ${fmt(stepCount, 1)} steps over $seconds s" +
                        " (${fmt(ratio, 3)} per step); $day now divides by" +
                        " ${fmt(dayFactor, 3)}, ${state.accepted} accepted in all")
                }
            }
        }
        savePending(still)
    }

    // MARK: - Persistence

    /**
     * [value] to [decimals] places the way Swift's `String(format: "%.Nf")` prints it: the exact binary
     * value, a tie going to the even digit. `java.util.Formatter` rounds ties up, so a strap battery of
     * 14.5 would read "15% is under 15%" here and "14%" in a Swift log.
     */
    private fun fmt(value: Double, decimals: Int): String =
        if (value.isNaN() || value.isInfinite()) value.toString()
        else java.math.BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_EVEN).toPlainString()

    /** Burst start times, unix seconds. Swift keeps an integer array in user defaults; here a JSON array. */
    private fun burstTimes(): List<Long> {
        val json = prefs.getString(BURST_TIMES_KEY) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getLong(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveBurstTimes(times: List<Long>) {
        val array = JSONArray()
        for (time in times) array.put(time)
        prefs.putString(BURST_TIMES_KEY, array.toString())
    }

    private fun loadPending(): List<Pending> = decodePending(prefs.getString(PENDING_KEY))

    private fun savePending(pending: List<Pending>) {
        prefs.putString(PENDING_KEY, encodePending(pending))
    }
}
