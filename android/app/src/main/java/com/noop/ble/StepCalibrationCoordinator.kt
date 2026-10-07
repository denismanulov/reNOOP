package com.noop.ble

import com.noop.data.KeyValuePrefs
import com.noop.data.StepSample
import com.noop.protocol.Whoop4RawImu

/**
 * Everything the BLE client does for WHOOP 4.0 step auto-calibration, behind the handful of events the
 * client feeds it. It owns the [RawStreamProbe] and the [StepAutoCalibrator] and holds the conditions
 * the Swift `BLEManager` writes inline at its hook sites, so they can be tested without a radio: the
 * client is a 13,000-line class no plain-JVM test can build.
 *
 * THE ONLY THING IT EVER WRITES to the strap is the raw-stream switch, through [sendSwitch]:
 * - ON, only from a burst the calibrator decided to take. That needs the opt-in on, and every limit in
 *   [StepAutoCalibrator]; the checks that live here are that the strap is a WHOOP 4.0, that the offload
 *   which just completed was a true HISTORY_COMPLETE, and that [SETTLE_DELAY_MS] later no other offload
 *   has ended since, none is running and the link is still up.
 * - OFF, when a burst ends, and after [connectSettled] on any later connection for as long as an
 *   earlier "on" has not had its "off" acknowledged. That one is not behind the opt-in, on purpose.
 * With the opt-in off and no such marker left, nothing is written at all.
 *
 * Nothing here touches the connection, the bond, the handshake or the offload: it is told about them.
 *
 * Not thread-safe. The client calls everything on the main looper; [wantsFrames] alone may be read
 * from the thread frames arrive on.
 */
class StepCalibrationCoordinator(
    prefs: KeyValuePrefs,
    /** Writes SEND_R10_R11_REALTIME as a confirmed write, on its own. */
    sendSwitch: (on: Boolean) -> Unit,
    /** Stored step-counter rows of the active strap over `[from, to]`, oldest first. */
    steps: suspend (from: Long, to: Long) -> List<StepSample>,
    private val log: (String) -> Unit,
    private val schedule: (delayMs: Long, work: () -> Unit) -> Unit,
    /** Runs a suspending block on the thread everything else here runs on. */
    private val launch: (suspend () -> Unit) -> Unit,
    private val isWhoop4: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val offloadInFlight: () -> Boolean,
    private val batteryPct: () -> Double?,
    /** True while the wearer has this app on screen; see [StepAutoCalibrator.offloadSettled]. */
    private val appOnScreen: () -> Boolean,
    now: () -> Long = { System.currentTimeMillis() / 1_000L },
    tzOffsetSeconds: () -> Long = {
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1_000L
    },
) {
    companion object {
        /**
         * Offloads often end in quick succession, so the calibrator waits this long after one and runs
         * only if none is in flight by then. Swift: the five-second `asyncAfter` in `exitBackfilling`.
         */
        const val SETTLE_DELAY_MS = 5_000L

        /** The only offload end that brings a fresh window. A timeout or a dropped link does not. */
        const val COMPLETE = "HISTORY_COMPLETE"
    }

    private val probe = RawStreamProbe(prefs = prefs, send = sendSwitch, log = log, schedule = schedule)

    private val calibrator = StepAutoCalibrator(
        prefs = prefs,
        now = now,
        tzOffsetSeconds = tzOffsetSeconds,
        startStream = { probe.burstStarted() },
        stopStream = { probe.burstStopped() },
        steps = steps,
        log = log,
        schedule = schedule,
        offloadInFlight = offloadInFlight,
    )

    /**
     * Bumped at every offload end and at every disconnect, so of several offloads ending back to back
     * only the last one's delayed check runs, and none runs for a link that has since dropped.
     */
    private var settleCheck = 0

    /** True while a burst wants raw IMU frames. One volatile read, so asking costs nothing per frame. */
    val wantsFrames: Boolean get() = calibrator.wantsFrames

    /** The connect handshake has been written. Switches off a stream an earlier connection left on. */
    fun connectSettled() {
        if (isWhoop4()) probe.connectSettled()
    }

    /** The link dropped. */
    fun disconnected() {
        settleCheck += 1
        probe.disconnected()
        calibrator.disconnected()
    }

    /** A history offload of ours began. */
    fun offloadStarted() {
        calibrator.offloadStarted()
    }

    /** A history offload of ours ended, for [reason] (the client's own `exitBackfilling` reason). */
    fun offloadEnded(reason: String) {
        if (reason != COMPLETE || !isWhoop4()) return
        settleCheck += 1
        val check = settleCheck
        schedule(SETTLE_DELAY_MS) {
            if (check != settleCheck || offloadInFlight() || !isConnected()) return@schedule
            val battery = batteryPct()
            val onScreen = appOnScreen()
            launch { calibrator.offloadSettled(batteryPct = battery, appOnScreen = onScreen) }
        }
    }

    /** One decoded raw IMU packet, while [wantsFrames]. */
    fun rawImu(buffer: Whoop4RawImu.AccelBuffer) {
        calibrator.accept(buffer)
    }

    /** The strap answered the raw-stream switch (see `RawStreamSwitch.isAcknowledgement`). */
    fun switchAcknowledged() {
        probe.responseReceived()
    }
}
