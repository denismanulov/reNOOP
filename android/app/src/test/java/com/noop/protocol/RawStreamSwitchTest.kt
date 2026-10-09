package com.noop.protocol

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [RawStreamSwitch]: the one command WHOOP 4.0 step auto-calibration sends, and the answer it waits for.
 */
class RawStreamSwitchTest {

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    /** A sealed WHOOP 4.0 frame around [inner] (`[type][seq][cmd][payload]`). */
    private fun whoop4Frame(inner: ByteArray): ByteArray {
        val length = inner.size + 4
        val out = ByteArray(inner.size + 8)
        out[0] = 0xAA.toByte()
        out[1] = (length and 0xFF).toByte()
        out[2] = ((length shr 8) and 0xFF).toByte()
        out[3] = Crc.crc8(byteArrayOf(out[1], out[2])).toByte()
        inner.copyInto(out, 4)
        le32(Crc.crc32(inner)).copyInto(out, length)
        return out
    }

    private fun isAck(frame: ByteArray) =
        RawStreamSwitch.isAcknowledgement(frame, Framing.parseFrame(frame, DeviceFamily.WHOOP4))

    /** COMMAND_RESPONSE(36) to command [cmd]: `[type][seq][cmd][origin_seq][result]`. */
    private fun response(cmd: Int, result: Int = 1) =
        whoop4Frame(byteArrayOf(PacketType.COMMAND_RESPONSE.rawValue.toByte(), 7, cmd.toByte(), 3, result.toByte(), 0, 0, 0))

    @Test
    fun thePayloadIsOneByteOnOrOff() {
        assertEquals(CommandNumber.SEND_R10_R11_REALTIME, RawStreamSwitch.command)
        assertEquals(63, RawStreamSwitch.command.rawValue)
        assertArrayEquals(byteArrayOf(1), RawStreamSwitch.payload(on = true))
        assertArrayEquals(byteArrayOf(0), RawStreamSwitch.payload(on = false))
    }

    /** The payload bytes are the documented ones: the command table is where this file takes them from. */
    @Test
    fun thePayloadMatchesTheProtocolDocument() {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val doc = listOf(File(userDir, "docs/PROTOCOL_IMPLEMENTATION.md"), File(userDir, "../../docs/PROTOCOL_IMPLEMENTATION.md"))
            .firstOrNull { it.exists() }
        assumeTrue("protocol document not found, skipping the lockstep check", doc != null)
        val row = doc!!.readLines().single { it.startsWith("| 63 | `SEND_R10_R11_REALTIME` |") }
        assertTrue(row, row.contains("| `[0x00]` off / `[0x01]` on |"))
        assertEquals("[0x00]", "[0x%02x]".format(RawStreamSwitch.payload(on = false).single()))
        assertEquals("[0x01]", "[0x%02x]".format(RawStreamSwitch.payload(on = true).single()))
    }

    /** The frame the app writes for each direction: the framed command with that one-byte payload. */
    @Test
    fun theWrittenFrameCarriesTheCommandAndThePayload() {
        for (on in listOf(true, false)) {
            val frame = Framing.buildCommand(RawStreamSwitch.command, RawStreamSwitch.payload(on), seq = 9)
            assertTrue(Framing.verifyFrame(frame, DeviceFamily.WHOOP4).ok)
            assertEquals(PacketType.COMMAND.rawValue, frame[4].toInt() and 0xFF)
            assertEquals(9, frame[5].toInt() and 0xFF)
            assertEquals(63, frame[6].toInt() and 0xFF)
            assertEquals(if (on) 1 else 0, frame[7].toInt() and 0xFF)
            assertEquals("one payload byte, then the CRC32", 7 + 1 + 4, frame.size)
        }
    }

    @Test
    fun anIntactResponseToTheSwitchIsItsAcknowledgement() {
        assertTrue(isAck(response(cmd = 63)))
        // The result code is not what makes it an answer: the strap answered either way.
        assertTrue(isAck(response(cmd = 63, result = 0)))
    }

    @Test
    fun aResponseToAnotherCommandIsNot() {
        assertFalse(isAck(response(cmd = CommandNumber.GET_BATTERY_LEVEL.rawValue)))
        assertFalse(isAck(response(cmd = CommandNumber.TOGGLE_REALTIME_HR.rawValue)))
        assertFalse(isAck(response(cmd = 62)))
    }

    /** Opcode 63 at the same offset in a frame of another type is not an answer. */
    @Test
    fun aBareOpcodeByteInAnotherFrameTypeIsNot() {
        val realtime = whoop4Frame(byteArrayOf(PacketType.REALTIME_DATA.rawValue.toByte(), 7, 63, 3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(63, realtime[6].toInt() and 0xFF)
        assertFalse(isAck(realtime))
        val event = whoop4Frame(byteArrayOf(PacketType.EVENT.rawValue.toByte(), 7, 63, 3, 1, 0, 0, 0, 0, 0, 0, 0))
        assertFalse(isAck(event))
    }

    /** A damaged frame never drives state, whatever it claims to be. */
    @Test
    fun aDamagedResponseIsNot() {
        val good = response(cmd = 63)
        val badPayloadCrc = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(isAck(badPayloadCrc))
        val badHeader = good.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertFalse(isAck(badHeader))
        val flippedCommand = good.copyOf().also { it[6] = 62 }   // the CRC32 no longer covers what it carries
        assertFalse(isAck(flippedCommand))
        assertFalse(isAck(good.copyOf(6)))
    }
}
