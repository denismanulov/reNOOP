package com.noop.protocol

import com.noop.data.V24AuxByte86Mapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WHOOP 4.0 strap-computed blood-oxygen byte at absolute offset 86 of a 104-byte v24 record.
 *
 * Every frame below is a real record from the overnight strap logs two contributors attached to
 * #1617 (the Test Centre `spo2re` dump, which prints the full frame). They are the same captures that
 * issue read `red`/`ir` from and concluded a 4.0 banks no percentage; the result byte sits 16 bytes
 * further down the record and was not examined there.
 *
 * What these pin is the decode and the storage rule, not accuracy: no capture has been compared with a
 * reference oximeter or with the WHOOP app's nightly figure.
 */
class Whoop4AuxByte86Test {
    private fun bytes(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Strap A, 2026-08-26: inside a measurement window (channel words 0x0b01 / 0x0562), byte = 94.
    private val percentA =
        "aa6400a12f1805b88f9c036f5a8e6a68158054400145015f030000000000000000ae43ff00a5e43be10a86bee1ca193eec9f813f0000803fe10a86bee1ca193eec9f813f10027402a60375024d019006010b620520005e0000000001ddea000000000000d974b36e"

    // Strap A, same night, earlier window: byte = 168 = 128 + 32 + 8, a status code and not a percentage.
    private val codeA =
        "aa6400a12f1805fe7c9c036e488e6a7847805440013d0000000000000000000061344048709adf3d663eb3be1f65233e141a783f0098efc6663eb3be1f65233e141a783f10027402aa03760252016007010b62052100a80000000001c4ea0000000000000d52b206"

    // Strap A, same night, outside any window (channel words 0x0c01 / 0x0c02 = disabled): byte = 0.
    private val idleA =
        "aa6400a12f1805ef359c031e048e6ac83c805440013d01b70300000000000000003f0aff00bc3c3c71a95a3f0060bfbe48a9093f0004444671a95a3f0060bfbe48a9093f04026802000368024d019007010c020c0c000000000d000141ea0000000000003d5b599e"

    // Strap B, 2026-08-26: a second strap's window (channel words 0x0b51 / 0x0552), byte = 94.
    private val percentB =
        "aa6400a12f180591191602b16e8e6a781c80544a012e0000000000000000000000fd48ff403b673c1f75313e148a533fa49c063f0000dec61f75313e148a533fa49c063fd50159022803570250016002510b520520005e000000000494ee000000000000bdffe833"

    @Test fun aPercentageIsDecodedAndOfferedAsACandidate() {
        for (hex in listOf(percentA, percentB)) {
            val p = decodeHistorical(bytes(hex), DeviceFamily.WHOOP4)!!
            assertEquals(24, p["hist_version"])
            assertEquals(94, p["aux_byte_86"])
            assertEquals(94, p["spo2_candidate_86"])
        }
    }

    /** A status code is carried raw and is NOT offered as a percentage. */
    @Test fun aStatusCodeIsCarriedRawButIsNotACandidate() {
        val p = decodeHistorical(bytes(codeA), DeviceFamily.WHOOP4)!!
        assertEquals(168, p["aux_byte_86"])
        assertNull(p["spo2_candidate_86"])
    }

    /** Zero is "nothing computed this second", never 0%. */
    @Test fun anIdleRecordReadsZeroAndIsNotACandidate() {
        val p = decodeHistorical(bytes(idleA), DeviceFamily.WHOOP4)!!
        assertEquals(0, p["aux_byte_86"])
        assertNull(p["spo2_candidate_86"])
    }

    /** The field the #1617 analysis was about is untouched by this decode. */
    @Test fun theRedAndIrFieldsStillDecodeBesideIt() {
        val p = decodeHistorical(bytes(percentA), DeviceFamily.WHOOP4)!!
        assertEquals(0x0210, p["spo2_red"])
        assertEquals(0x0274, p["spo2_ir"])
    }

    /** Only nonzero bytes are banked, each as one event at the record's own second. */
    @Test fun onlyNonzeroBytesAreBankedAsEvents() {
        val batch = extractHistoricalStreams(
            listOf(bytes(idleA), bytes(codeA), bytes(percentA)), 0, 0, DeviceFamily.WHOOP4,
        )
        val banked = batch.events.filter { it.kind == V24AuxByte86Mapping.EVENT_KIND }
        assertEquals(listOf(1787709550L, 1787714159L), banked.map { it.ts })
        assertEquals(listOf("{\"byte\":168}", "{\"byte\":94}"), banked.map { it.payloadJSON })
        // The idle record still banked its ordinary streams; it just has no reading to record.
        assertTrue(batch.hr.any { it.ts == 1787692062L })
        assertFalse(banked.any { it.ts == 1787692062L })
    }

    /** The same bytes read as another family's record must not bank a WHOOP 4.0 reading. */
    @Test fun aWhoop5DecodeOfTheSameBytesBanksNothing() {
        val batch = extractHistoricalStreams(listOf(bytes(percentA)), 0, 0, DeviceFamily.WHOOP5)
        assertTrue(batch.events.none { it.kind == V24AuxByte86Mapping.EVENT_KIND })
    }
}
