package com.noop.ble

import com.noop.data.KeyValuePrefs

/**
 * Sends the WHOOP 4.0 raw-stream switch (SEND_R10_R11_REALTIME) on its own, apart from every other
 * command, and repeats it until the strap answers. Kotlin twin of the Swift `RawStreamProbe`
 * (Strand/BLE/RawStreamProbe.swift), for the one caller Android has: [StepAutoCalibrator]'s short
 * bursts, which carry their own opt-in.
 *
 * Why it exists. Swift's Live screen sends that switch immediately before TOGGLE_REALTIME_HR. On one
 * WHOOP 4.0 (2026-10-03, a restored connection) the strap answered the toggle eight times out of eight
 * and the switch none, and no type-43 frame arrived. Written alone as a confirmed write the switch was
 * acknowledged on the first attempt and the raw stream started.
 *
 * Turning the stream off is not gated on anything: once the probe has asked for the stream it records
 * that under [ARMED_KEY], and any later connection, in this process or a later one, keeps writing the
 * off switch until the strap acknowledges it, because a raw stream left running costs strap battery and
 * competes with the history offload.
 *
 * The acknowledgement is the strap's COMMAND_RESPONSE to the opcode. It carries the request's sequence,
 * not its payload, so it cannot say which direction it answers; the probe takes it as answering the
 * last direction it wrote.
 *
 * WHAT ANDROID LEAVES OUT. Swift's second caller is the Live screen, behind the `rawStreamProbe` user
 * default, which is set as a launch argument and has no UI. Android has no launch arguments and its Live
 * screen never switches the raw stream on, so `liveStarted`, `liveStopped`, that key and the
 * `liveWanted` argument of `connectSettled` are not ported. One consequence: nothing but a burst ever
 * wants the stream on here, so [connectSettled] switches a marked stream off whatever is on screen.
 *
 * ONE RULE ANDROID ADDS. An answer that arrives before the probe has written the direction it is
 * waiting for is ignored ([responseReceived]). Swift takes it as the acknowledgement, "after 0
 * write(s)", cancels the write and, for the off direction, clears the marker. The answer can only be to
 * an earlier write (the connect handshake writes the same opcode on Android, and a late answer to an
 * "on" can still be in flight when a burst is abandoned), so taking it would leave a stream that was
 * never told to stop marked as stopped.
 *
 * Not thread-safe: the BLE client drives it on the main looper.
 */
class RawStreamProbe(
    private val prefs: KeyValuePrefs,
    private val send: (on: Boolean) -> Unit,
    private val log: (String) -> Unit,
    private val schedule: (delayMs: Long, work: () -> Unit) -> Unit,
) {
    companion object {
        const val ARMED_KEY = "rawStreamProbe.armed"
        const val MAX_ATTEMPTS = 4
        const val FIRST_DELAY_MS = 1_500L
        const val RETRY_DELAY_MS = 2_000L
    }

    /** The direction still waiting for the strap's answer, null when nothing is outstanding. */
    private var awaiting: Boolean? = null
    private var attempts = 0

    /** Bumped whenever the outstanding request changes, so scheduled work from an older one is a no-op. */
    private var generation = 0

    private val isArmed: Boolean get() = prefs.getBoolean(ARMED_KEY)

    /** A calibration burst begins: the stream goes on. */
    fun burstStarted() {
        request(on = true)
    }

    /** The burst is over: the stream goes off. */
    fun burstStopped() {
        request(on = false)
    }

    /**
     * The connection finished its handshake. Clears a stream an earlier connection or launch left on.
     * Nothing is written, now or later on this connection, unless the stream was ever asked for and its
     * off switch has not been acknowledged since.
     */
    fun connectSettled() {
        if (isArmed) request(on = false)
    }

    /** The link dropped: nothing can be written, and the next connection starts from [connectSettled]. */
    fun disconnected() {
        generation += 1
        awaiting = null
    }

    /** The strap answered the raw-stream opcode. */
    fun responseReceived() {
        val direction = awaiting ?: return
        // Not yet written in this direction: the answer belongs to an earlier write (see the class note).
        if (attempts == 0) return
        generation += 1
        awaiting = null
        if (!direction) prefs.putBoolean(ARMED_KEY, false)
        log("Raw stream probe: strap acknowledged the ${if (direction) "on" else "off"} switch" +
            " after $attempts write(s)")
    }

    private fun request(on: Boolean) {
        generation += 1
        awaiting = on
        attempts = 0
        if (on) prefs.putBoolean(ARMED_KEY, true)
        scheduleAttempt(FIRST_DELAY_MS)
    }

    private fun scheduleAttempt(delayMs: Long) {
        val scheduled = generation
        schedule(delayMs) {
            val direction = awaiting
            if (generation != scheduled || direction == null) return@schedule
            if (attempts >= MAX_ATTEMPTS) {
                awaiting = null
                log("Raw stream probe: no answer to the ${if (direction) "on" else "off"} switch after" +
                    " $attempts writes; giving up until the next connection")
                return@schedule
            }
            attempts += 1
            log("Raw stream probe: writing the ${if (direction) "on" else "off"} switch alone" +
                " (attempt $attempts/$MAX_ATTEMPTS)")
            send(direction)
            scheduleAttempt(RETRY_DELAY_MS)
        }
    }
}
