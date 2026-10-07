package com.noop.protocol

// RawStreamSwitch.kt — the WHOOP 4.0 raw-stream switch, SEND_R10_R11_REALTIME (63): the command whose
// payload turns the type-43 REALTIME_RAW_DATA stream on and off, and what its answer looks like.
//
// WHERE THE BYTES COME FROM. The payload is one byte, `[0x01]` on and `[0x00]` off: the command table of
// docs/PROTOCOL_IMPLEMENTATION.md, which `RawStreamSwitchTest` reads and holds this file to. The Swift
// app writes the same byte inline (`[on ? 0x01 : 0x00]` in `BLEManager`), and the connect handshake on
// both platforms has always written the off form. Stated here once so no BLE code carries the literal.

object RawStreamSwitch {

    /** The command the payload belongs to. */
    val command: CommandNumber = CommandNumber.SEND_R10_R11_REALTIME

    /** `[0x01]` to switch the raw stream on, `[0x00]` to switch it off. */
    fun payload(on: Boolean): ByteArray = byteArrayOf(if (on) 1 else 0)

    /** WHOOP 4.0 frames carry the command a COMMAND_RESPONSE answers at this frame offset. */
    private const val RESPONSE_COMMAND_OFFSET = 6

    /**
     * True when [frame] is an intact WHOOP 4.0 COMMAND_RESPONSE to the switch. [parsed] is the frame's
     * own parse: the answer drives state (it stops the sender's retries), so it is taken only from a
     * frame that passed the full integrity verdict, never from a bare opcode byte. The answer carries
     * the request's sequence, not its payload, so it does not say which direction it answers.
     *
     * Swift writes the same condition inline in `BLEManager`'s WHOOP 4.0 notify path
     * (`parsed.ok, parsed.typeName == "COMMAND_RESPONSE", frame[6] == ...`).
     */
    fun isAcknowledgement(frame: ByteArray, parsed: ParsedFrame): Boolean =
        parsed.ok && parsed.typeName == "COMMAND_RESPONSE" && frame.size > RESPONSE_COMMAND_OFFSET &&
            (frame[RESPONSE_COMMAND_OFFSET].toInt() and 0xFF) == command.rawValue
}
