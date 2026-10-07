package com.noop.ble

import com.noop.protocol.DeviceFamily

/**
 * Notices a SECOND app pulling this strap's history.
 *
 * A WHOOP keeps one history queue with one trim cursor. When another app on the phone (upstream NOOP, the
 * WHOOP app) asks for an offload over the link it shares with this one, its history records can reach us
 * too, as type-47 HISTORICAL_DATA frames while we run no offload of our own (#494 recorded this for
 * 5/MG). Whichever app acks a chunk first makes the strap drop it, so the other app never stores those
 * hours: on 2026-09-30 that is how a night reached upstream NOOP and never reached reNOOP on iOS.
 *
 * The evidence has to be more than a trailing frame of our own session, which the strap can flush just
 * after we finish: nothing counts within [COOLDOWN_MS] of our own offload activity, and one verdict needs
 * [FRAMES_TO_FLAG] records inside [WINDOW_MS] (one foreign chunk is ~50 records, a trailing flush a
 * handful). Pure and clock-injected, so the thresholds are unit-tested without a strap; the methods are
 * synchronized because the client feeds it from the GATT callback thread and the main looper. Twin of the
 * Swift `ForeignOffloadDetector`, with the same thresholds.
 */
class ForeignOffloadDetector {

    /** Wall time of our own last offload activity, or null while there has been none. */
    private var lastOwnActivityMs: Long? = null
    private val hits = ArrayDeque<Long>()

    /** Our own offload moved (request sent, a frame routed to the Backfiller, the session ended). */
    @Synchronized
    fun noteOwnOffloadActivity(nowMs: Long) {
        lastOwnActivityMs = nowMs
        hits.clear()
    }

    /** Feed one history record seen while we run no offload. True when it completes the evidence. */
    @Synchronized
    fun noteHistoryOutsideOwnOffload(nowMs: Long): Boolean {
        val last = lastOwnActivityMs
        if (last != null && nowMs - last < COOLDOWN_MS) return false
        hits.removeAll { nowMs - it >= WINDOW_MS }
        hits.addLast(nowMs)
        if (hits.size < FRAMES_TO_FLAG) return false
        hits.clear()
        return true
    }

    companion object {
        const val COOLDOWN_MS = 30_000L
        const val WINDOW_MS = 60_000L
        const val FRAMES_TO_FLAG = 20

        /**
         * A HISTORICAL_DATA record: the inner type byte is at frame[4] on WHOOP 4.0 and frame[8] under the
         * 5/MG puffin envelope (the same offsets [WhoopBleClient.isOffloadFrame] reads).
         */
        fun isHistoryRecord(frame: ByteArray, family: DeviceFamily): Boolean {
            val typeIndex = if (family == DeviceFamily.WHOOP5) 8 else 4
            return frame.size > typeIndex && (frame[typeIndex].toInt() and 0xFF) == 47
        }
    }
}
