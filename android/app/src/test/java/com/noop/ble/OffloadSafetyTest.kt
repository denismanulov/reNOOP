package com.noop.ble

import com.noop.data.LiveStoreReplacement
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.protocol.Crc
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The 2026-09-30 lost night, and what guards against a repeat on Android: a restore that leaves the
 * process on a replaced database must never ack a chunk, and a second app pulling the strap's history
 * must be noticed. Twin of the Swift `OffloadSafetyTests`, with the same frames, trim cursor, instants and
 * thresholds (seconds there, milliseconds here).
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

    // MARK: - offload cadence (instrumentation only)

    /** A session on a clock the test owns: the cursor write is where the phone's time goes. */
    private class TimedSession(var storeReplaced: Boolean = false) {
        var nowMs = 0L
        var cursorWriteMs = 0L
        val acked = ArrayList<Long>()
        val cursorAtAck = ArrayList<Long?>()
        private val cursors = object : TrimCursorStore {
            val values = HashMap<String, Long>()
            override suspend fun set(name: String, value: Long) { nowMs += cursorWriteMs; values[name] = value }
            override suspend fun get(name: String): Long? = values[name]
        }
        val backfiller = Backfiller(
            repository = WhoopRepository(untouchedDao()),
            deviceId = "test",
            cursorStore = cursors,
            ackTrim = { trim, _ ->
                cursorAtAck += cursors.values[Backfiller.STRAP_TRIM_CURSOR]
                acked += trim
            },
            storeReplaced = { storeReplaced },
            nanoTime = { nowMs * 1_000_000L },
        )
    }

    /** Twin of the Swift `testChunkTimingSplitsPhoneAndStrapSides`, with the durations pinned too. */
    @Test
    fun chunkTimingSplitsPhoneAndStrapSides() = runBlocking {
        val s = TimedSession()
        s.backfiller.begin(DeviceFamily.WHOOP4)
        assertNull("nothing acked yet, nothing to say", s.backfiller.sessionChunkTiming.logLine)

        s.backfiller.ingest(historyStartFrame())
        s.cursorWriteMs = 7
        s.backfiller.ingest(historyEndFrame(trim = 1L))
        s.nowMs += 120
        s.backfiller.ingest(historyStartFrame())
        s.cursorWriteMs = 11
        s.backfiller.ingest(historyEndFrame(trim = 2L))

        val timing = s.backfiller.sessionChunkTiming
        assertEquals(
            "only a START that follows one of OUR acks measures the strap",
            Backfiller.ChunkTiming(
                phoneCount = 2, phoneTotalMs = 18, phoneMaxMs = 11,
                strapCount = 1, strapTotalMs = 120, strapMaxMs = 120,
            ),
            timing,
        )
        assertEquals(
            "Backfill: timing chunks=2 phone(end→ack) avg=9ms max=11ms · strap+radio(ack→next start) n=1 avg=120ms max=120ms",
            timing.logLine,
        )
        // The stopwatch reads the clock and nothing else: every chunk is still acked, in order, and
        // only once its trim cursor is on disk.
        assertEquals(listOf(1L, 2L), s.acked)
        assertEquals(listOf<Long?>(1L, 2L), s.cursorAtAck)

        s.backfiller.begin(DeviceFamily.WHOOP4)
        assertEquals("a new session starts clean", Backfiller.ChunkTiming(), s.backfiller.sessionChunkTiming)
        // The ack that closed the last session is not this session's: its START measures nothing.
        s.nowMs += 5_000
        s.backfiller.ingest(historyStartFrame())
        assertEquals(Backfiller.ChunkTiming(), s.backfiller.sessionChunkTiming)
    }

    /** Chunk after chunk under one START: the phone's side is timed, the wait for the strap is not. */
    @Test
    fun chunkTimingUnderOneStartSaysTheStrapSideWasNotMeasured() = runBlocking {
        val s = TimedSession()
        s.backfiller.begin(DeviceFamily.WHOOP4)
        s.backfiller.ingest(historyStartFrame())
        s.cursorWriteMs = 4
        for (trim in 1L..3L) {
            s.backfiller.ingest(historyEndFrame(trim = trim))
            s.nowMs += 90   // the next chunk arrives with no START of its own
        }
        assertEquals(
            "Backfill: timing chunks=3 phone(end→ack) avg=4ms max=4ms · strap+radio(ack→next start) not measured",
            s.backfiller.sessionChunkTiming.logLine,
        )
        // A START after the third ack is measured from THAT ack, not from the first.
        s.backfiller.ingest(historyStartFrame())
        assertEquals(
            Backfiller.ChunkTiming(
                phoneCount = 3, phoneTotalMs = 12, phoneMaxMs = 4,
                strapCount = 1, strapTotalMs = 90, strapMaxMs = 90,
            ),
            s.backfiller.sessionChunkTiming,
        )
    }

    /** A chunk whose ack was held is not a chunk the phone finished: it is not counted. */
    @Test
    fun aHeldAckIsNotTimed() = runBlocking {
        val s = TimedSession(storeReplaced = true)
        s.backfiller.begin(DeviceFamily.WHOOP4)
        s.backfiller.ingest(historyStartFrame())
        s.backfiller.ingest(historyEndFrame(trim = 1L))
        s.nowMs += 250
        s.backfiller.ingest(historyStartFrame())
        assertEquals(emptyList<Long>(), s.acked)
        assertEquals(Backfiller.ChunkTiming(), s.backfiller.sessionChunkTiming)
        assertNull(s.backfiller.sessionChunkTiming.logLine)
    }

    // MARK: - another app pulling the strap's history

    @Test
    fun theThresholdsAreTheSwiftOnes() {
        assertEquals(30_000L, ForeignOffloadDetector.COOLDOWN_MS)
        assertEquals(60_000L, ForeignOffloadDetector.WINDOW_MS)
        assertEquals(20, ForeignOffloadDetector.FRAMES_TO_FLAG)
        assertEquals(600_000L, WhoopBleClient.FOREIGN_OFFLOAD_REPEAT_MS)
    }

    @Test
    fun foreignHistoryNeedsTheFullBurstOutsideTheCooldown() {
        val d = ForeignOffloadDetector()
        val t0 = 1_000_000_000L   // 1_000_000 s
        d.noteOwnOffloadActivity(t0)
        // Our own trailing frames inside the cooldown never count, however many there are.
        for (i in 0 until 100) {
            assertFalse(d.noteHistoryOutsideOwnOffload(t0 + i * 200L))
        }
        val late = t0 + ForeignOffloadDetector.COOLDOWN_MS + 1_000L
        for (i in 0 until ForeignOffloadDetector.FRAMES_TO_FLAG - 1) {
            assertFalse(d.noteHistoryOutsideOwnOffload(late + i * 1_000L))
        }
        assertTrue(
            "a foreign chunk's worth of records inside the window is the evidence",
            d.noteHistoryOutsideOwnOffload(late + ForeignOffloadDetector.FRAMES_TO_FLAG * 1_000L),
        )
    }

    @Test
    fun sparseStraysOutsideTheWindowNeverAddUp() {
        val d = ForeignOffloadDetector()
        val t0 = 2_000_000_000L
        for (i in 0 until ForeignOffloadDetector.FRAMES_TO_FLAG * 3) {
            val t = t0 + i * (ForeignOffloadDetector.WINDOW_MS / 4)
            assertFalse("four strays a minute are not an offload", d.noteHistoryOutsideOwnOffload(t))
        }
    }

    @Test
    fun ownActivityResetsTheEvidence() {
        val d = ForeignOffloadDetector()
        val t0 = 3_000_000_000L
        for (i in 0 until ForeignOffloadDetector.FRAMES_TO_FLAG - 1) {
            d.noteHistoryOutsideOwnOffload(t0 + i * 100L)
        }
        d.noteOwnOffloadActivity(t0 + 2_000L)
        assertFalse("records right after our own request are ours", d.noteHistoryOutsideOwnOffload(t0 + 3_000L))
    }

    /** Android only, to pin both edges: exactly the cooldown is outside it, exactly the window is out of it. */
    @Test
    fun theCooldownAndTheWindowAreHalfOpen() {
        val cooled = ForeignOffloadDetector()
        cooled.noteOwnOffloadActivity(0L)
        for (i in 0 until ForeignOffloadDetector.FRAMES_TO_FLAG - 1) {
            assertFalse(cooled.noteHistoryOutsideOwnOffload(ForeignOffloadDetector.COOLDOWN_MS))
        }
        assertTrue(
            "a record exactly the cooldown after our activity counts",
            cooled.noteHistoryOutsideOwnOffload(ForeignOffloadDetector.COOLDOWN_MS),
        )

        val windowed = ForeignOffloadDetector()
        assertFalse(windowed.noteHistoryOutsideOwnOffload(0L))
        for (i in 0 until ForeignOffloadDetector.FRAMES_TO_FLAG - 1) {
            assertFalse(
                "the record exactly one window old has already left the count",
                windowed.noteHistoryOutsideOwnOffload(ForeignOffloadDetector.WINDOW_MS),
            )
        }
        assertTrue(windowed.noteHistoryOutsideOwnOffload(ForeignOffloadDetector.WINDOW_MS))
    }

    /** One verdict spends its evidence: the next needs a full burst again. */
    @Test
    fun aVerdictStartsTheCountAgain() {
        val d = ForeignOffloadDetector()
        val verdicts = (0 until ForeignOffloadDetector.FRAMES_TO_FLAG * 2).count { d.noteHistoryOutsideOwnOffload(it * 10L) }
        assertEquals(2, verdicts)
    }

    @Test
    fun historyRecordTypeByteSitsWhereEachFamilyPutsIt() {
        val w4 = ByteArray(12)
        w4[4] = 47
        assertTrue(ForeignOffloadDetector.isHistoryRecord(w4, DeviceFamily.WHOOP4))
        assertFalse(ForeignOffloadDetector.isHistoryRecord(w4, DeviceFamily.WHOOP5))
        val w5 = ByteArray(12)
        w5[8] = 47
        assertTrue(ForeignOffloadDetector.isHistoryRecord(w5, DeviceFamily.WHOOP5))
        w4[4] = 43   // the live raw flood
        assertFalse(ForeignOffloadDetector.isHistoryRecord(w4, DeviceFamily.WHOOP4))
        assertFalse(ForeignOffloadDetector.isHistoryRecord(byteArrayOf(0, 1), DeviceFamily.WHOOP4))
    }

    /**
     * The always-on line. It names the conclusion and then exactly what was counted, with the figures
     * taken from the detector, so the two cannot drift apart.
     */
    @Test
    fun theLogLineStatesWhatWasCounted() {
        assertEquals(
            "Another BLE client is pulling this strap's history: 20 history records arrived outside our own " +
                "offload within 60 s, none within 30 s of our own offload activity. Whichever client acks a chunk " +
                "first keeps it; the other never stores those hours. Keep one app connected to the strap.",
            WhoopBleClient.foreignOffloadLine(),
        )
    }

    // MARK: - the other strap apps, and the warning

    @Test
    fun otherAppsPhrase() {
        val join: (List<String>) -> String = { it.joinToString(" and ") }
        assertNull(OtherStrapApps.phrase(emptyList(), join))
        assertEquals("NOOP", OtherStrapApps.phrase(listOf("NOOP"), join))
        assertEquals("NOOP and WHOOP", OtherStrapApps.phrase(listOf("NOOP", "WHOOP"), join))
    }

    @Test
    fun anAppIsNamedOnceWhicheverOfItsIdsIsInstalled() {
        assertEquals(emptyList<String>(), OtherStrapApps.ableToSync { false })
        assertEquals(listOf("NOOP"), OtherStrapApps.ableToSync { it == "com.noop.whoop" })
        assertEquals(listOf("NOOP"), OtherStrapApps.ableToSync { it == "com.noop.whoop.debug" || it == "com.noop.whoop.staging" })
        assertEquals(listOf("WHOOP"), OtherStrapApps.ableToSync { it == "com.whoop.android" })
        assertEquals("in the order of the known list", listOf("NOOP", "WHOOP"), OtherStrapApps.ableToSync { true })
        assertEquals("reNOOP's own ids are never on the list", emptyList<String>(), OtherStrapApps.ableToSync { it.startsWith("com.renoop.") })
    }

    /** Android only: from Android 12 an app without the Nearby devices permission cannot reach a strap. */
    @Test
    fun anInstalledAppCountsOnlyWhileItMayUseBluetooth() {
        assertFalse(OtherStrapApps.canConnect(installed = false, sdkInt = 34, bluetoothGranted = { true }))
        assertTrue(OtherStrapApps.canConnect(installed = true, sdkInt = 34, bluetoothGranted = { true }))
        assertFalse(OtherStrapApps.canConnect(installed = true, sdkInt = 31, bluetoothGranted = { false }))
        // Before Android 12 Bluetooth came with the install, and there is no grant to ask about.
        assertTrue(OtherStrapApps.canConnect(installed = true, sdkInt = 30, bluetoothGranted = { error("not asked before Android 12") }))
        assertFalse(OtherStrapApps.nearbyDevicesRevocable(sdkInt = 30))
        assertTrue(OtherStrapApps.nearbyDevicesRevocable(sdkInt = 31))
    }

    /** Package visibility: an id the manifest does not list under `<queries>` reads as "not installed". */
    @Test
    fun everyKnownIdIsDeclaredInTheManifestQueries() {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rel = "src/main/AndroidManifest.xml"
        val manifest = listOf(File(userDir, rel), File(userDir, "app/$rel"), File(userDir, "android/app/$rel"))
            .firstOrNull { it.isFile }
        assertNotNull("AndroidManifest.xml not found from user.dir=$userDir; a skip would read as a pass", manifest)
        val queries = manifest!!.readText().substringAfter("<queries>").substringBefore("</queries>")
        for (id in OtherStrapApps.known.flatMap { it.packages }) {
            assertTrue("$id is missing from <queries>", queries.contains("<package android:name=\"$id\" />"))
        }
    }

    @Test
    fun theWarningShowsOncePerProcessAndNeverWhenMuted() {
        val muted = OtherStrapAppWarning()
        muted.reportForeignOffload(muted = true)
        assertFalse(muted.presented.value)
        // The mute did not spend the one showing: unmuted, the next sighting still warns.
        muted.reportForeignOffload(muted = false)
        assertTrue(muted.presented.value)

        val warning = OtherStrapAppWarning()
        warning.reportForeignOffload(muted = false)
        assertTrue(warning.presented.value)
        warning.dismiss()
        assertFalse(warning.presented.value)
        warning.reportForeignOffload(muted = false)
        assertFalse("shown at most once per process", warning.presented.value)
    }
}

/** The chunks in these tests carry no records, so nothing may reach the store: any DAO call fails the test. */
private fun untouchedDao(): WhoopDao = Proxy.newProxyInstance(
    WhoopDao::class.java.classLoader,
    arrayOf(WhoopDao::class.java),
) { _, method, _ -> throw AssertionError("unexpected DAO call ${method.name}") } as WhoopDao
