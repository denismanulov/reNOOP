package com.noop.ble

import com.noop.data.LiveStoreReplacement
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.protocol.Crc
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The 2026-09-30 lost night, and what guards against a repeat on Android: a restore that leaves the
 * process on a replaced database must never ack a chunk. Twin of the Swift `OffloadSafetyTests`, with the
 * same frames and the same trim cursor.
 */
class OffloadSafetyTest {

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    /** A sealed WHOOP 4.0 frame around [inner] (`[type][seq][cmd][payload]`), as Swift `frameFromPayload`. */
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

    private fun historyStartFrame(): ByteArray =
        whoop4Frame(byteArrayOf(49, 0, 1) + le32(1_700_000_000L) + byteArrayOf(0, 0) + le32(0) + le32(0))

    private fun historyEndFrame(trim: Long): ByteArray =
        whoop4Frame(byteArrayOf(49, 0, 2) + le32(1_700_000_000L) + byteArrayOf(0, 0) + le32(0) + le32(trim))

    private class MemoryCursorStore : TrimCursorStore {
        val values = HashMap<String, Long>()
        override suspend fun set(name: String, value: Long) { values[name] = value }
        override suspend fun get(name: String): Long? = values[name]
    }

    private class Session(storeReplaced: () -> Boolean) {
        val acked = ArrayList<Long>()
        val cursors = MemoryCursorStore()
        val log = ArrayList<String>()
        val backfiller = Backfiller(
            repository = WhoopRepository(untouchedDao()),
            deviceId = "test",
            cursorStore = cursors,
            ackTrim = { trim, _ -> acked += trim },
            log = { log += it },
            storeReplaced = storeReplaced,
        )
        val replacedLines get() = log.filter { it.contains("the database was replaced by a restore") }
    }

    // MARK: - a replaced store holds every ack

    @Test
    fun aReplacedStoreAcksNothingAndStalls() = runBlocking {
        val s = Session(storeReplaced = { true })
        s.backfiller.begin(DeviceFamily.WHOOP4)
        s.backfiller.ingest(historyStartFrame())
        s.backfiller.ingest(historyEndFrame(trim = 70_476L))
        assertEquals("a chunk that cannot reach the restored store must stay on the strap", emptyList<Long>(), s.acked)
        assertTrue("the stall stamp and the no-ack guard both key off this", s.backfiller.persistStalled)
        assertTrue("the trim cursor must not move past history nobody kept", s.cursors.values.isEmpty())
        assertEquals(
            listOf("Backfill: the database was replaced by a restore — NOT acking trim=70476; restart reNOOP so the strap re-sends this history."),
            s.replacedLines,
        )

        // A later END of the same session is held too, without a second line.
        s.backfiller.ingest(historyEndFrame(trim = 70_477L))
        assertEquals(emptyList<Long>(), s.acked)
        assertEquals(1, s.replacedLines.size)
    }

    @Test
    fun theSameChunkAcksWhenTheStoreWasNotReplaced() = runBlocking {
        val s = Session(storeReplaced = { false })
        s.backfiller.begin(DeviceFamily.WHOOP4)
        s.backfiller.ingest(historyStartFrame())
        s.backfiller.ingest(historyEndFrame(trim = 70_476L))
        assertEquals("control: the guard must not stall a healthy offload", listOf(70_476L), s.acked)
        assertFalse(s.backfiller.persistStalled)
        assertEquals(70_476L, s.cursors.values[Backfiller.STRAP_TRIM_CURSOR])
        assertTrue(s.replacedLines.isEmpty())
    }

    /**
     * Android only: the restore runs on another thread, so the latch can be set while a chunk is being
     * finished. The chunk that was past the first check is held by the second one, before its trim
     * cursor is written.
     */
    @Test
    fun aStoreReplacedWhileAChunkIsBeingFinishedIsHeldToo() = runBlocking {
        var asked = 0
        val s = Session(storeReplaced = { asked++ >= 1 })
        s.backfiller.begin(DeviceFamily.WHOOP4)
        s.backfiller.ingest(historyStartFrame())
        s.backfiller.ingest(historyEndFrame(trim = 70_476L))
        assertEquals(2, asked)
        assertEquals(emptyList<Long>(), s.acked)
        assertTrue(s.backfiller.persistStalled)
        assertTrue(s.cursors.values.isEmpty())
        assertEquals(1, s.replacedLines.size)
    }

    /** A new session starts un-stalled, and is held again by the latch, with its own line. */
    @Test
    fun theLatchOutlivesTheSession() = runBlocking {
        val s = Session(storeReplaced = { true })
        s.backfiller.begin(DeviceFamily.WHOOP4)
        s.backfiller.ingest(historyEndFrame(trim = 1L))
        s.backfiller.begin(DeviceFamily.WHOOP4)
        assertFalse("begin() clears the per-session stall", s.backfiller.persistStalled)
        s.backfiller.ingest(historyEndFrame(trim = 2L))
        assertEquals(emptyList<Long>(), s.acked)
        assertTrue(s.backfiller.persistStalled)
        assertEquals(2, s.replacedLines.size)
    }

    @Test
    fun onlyTheLiveStorePathLatches() {
        val dir = File(System.getProperty("java.io.tmpdir"), "offload-safety-${System.nanoTime()}")
        val live = File(dir, "databases/noop_whoop.db")
        val scratch = File(dir, "cache/restore-scratch.sqlite")
        assertFalse(
            "a unit test restoring into a throwaway file must not latch the test JVM",
            LiveStoreReplacement.isLiveStore(scratch, opened = live),
        )
        assertTrue(LiveStoreReplacement.isLiveStore(live, opened = live))
        assertTrue(
            "the same file, spelled differently",
            LiveStoreReplacement.isLiveStore(File(dir, "databases/../databases/noop_whoop.db"), opened = live),
        )
        assertFalse(
            "a process that never opened the database has no connection a swap could strand",
            LiveStoreReplacement.isLiveStore(live, opened = null),
        )
    }
}

/** The chunks in these tests carry no records, so nothing may reach the store: any DAO call fails the test. */
private fun untouchedDao(): WhoopDao = Proxy.newProxyInstance(
    WhoopDao::class.java.classLoader,
    arrayOf(WhoopDao::class.java),
) { _, method, _ -> throw AssertionError("unexpected DAO call ${method.name}") } as WhoopDao
