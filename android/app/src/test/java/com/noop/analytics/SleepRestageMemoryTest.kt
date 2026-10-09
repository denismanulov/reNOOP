package com.noop.analytics

import com.noop.data.DAY_STREAM_FINGERPRINT_SQL
import com.noop.data.GravitySample
import com.noop.data.HAS_WHOOP5_RR_SOURCE_SQL
import com.noop.data.HrSample
import com.noop.data.PROMOTE_WHOOP4_HISTORY_SQL
import com.noop.data.PairedDeviceRow
import com.noop.data.RR_INTERVALS_SQL
import com.noop.data.RespSample
import com.noop.data.RrInterval
import com.noop.data.SleepSession
import com.noop.data.StepSample
import com.noop.data.WHOOP4_RR_INTERVALS_SQL
import com.noop.data.WHOOP5_RR_INTERVALS_SQL
import com.noop.data.WhoopDao
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The edited-night self-heal skips a night whose inputs have not changed since it was last re-staged
 * ([SleepStageHealer.RestagedNights]). Three things are held here:
 *
 *  - the skip engages: an unchanged night is not read again;
 *  - it cannot engage when an input moved: each thing a re-stage reads is changed in turn, and the
 *    night is read again every time;
 *  - it changes nothing that is stored or returned: the pass with the memory and the pass without it
 *    run side by side over two identical stores, through every one of those changes.
 *
 * The raw streams live in SQLite and the witness is the production statement, so a count the witness
 * reads and a row the re-stage reads come from the same table.
 */
class SleepRestageMemoryTest {

    private companion object {
        const val STRAP = "my-whoop"
        const val COMPUTED = "my-whoop-noop"

        /** 2025-06-10 00:00:00 UTC. */
        const val MIDNIGHT = 1_749_513_600L

        /** 01:20, so the read range (an hour of lead-in) starts at 00:20, off the hour. */
        const val NIGHT = MIDNIGHT + 3_600L + 1_200L
        const val NIGHT_SECONDS = 2 * 3_600
        const val NIGHT_END = NIGHT + NIGHT_SECONDS - 1

        /** One second of the night left without a heart-rate row, and one without a gravity row, for a
         *  later row to land in: the store only ever gains rows at timestamps it did not have. */
        const val HR_HOLE = NIGHT + 777L
        const val GRAVITY_HOLE = NIGHT + 60L

        const val WINDOW_FROM = MIDNIGHT - 30 * 86_400L
        const val WINDOW_TO = MIDNIGHT + 30 * 86_400L
    }

    /** A store: raw streams in SQLite, the registry and the sleep sessions in maps, every DAO call counted. */
    private class Store : AutoCloseable {
        val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val sleeps = LinkedHashMap<Pair<String, Long>, SleepSession>()
        val owners = LinkedHashMap<String, PairedDeviceRow>()
        val calls = HashMap<String, Int>()
        var stageWrites = 0

        /** Thrown from the witness read while set. */
        var witnessFailure: Throwable? = null

        /** Runs once, inside the next raw gravity read: a row landing while a night is being read. */
        var duringNextGravityRead: (() -> Unit)? = null

        init {
            sql("CREATE TABLE rrInterval(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, rrMs INTEGER NOT NULL, " +
                "seq INTEGER NOT NULL, synced INTEGER NOT NULL, ord INTEGER, srcChannel INTEGER, tsSuspect INTEGER, " +
                "PRIMARY KEY(deviceId, ts, rrMs, seq))")
            sql(WhoopDatabase.RR_SOURCE_INDEX_SQL)
            sql("CREATE TABLE pairedDevice(id TEXT PRIMARY KEY, brand TEXT, model TEXT, status TEXT)")
            sql("CREATE TABLE hrSample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm INTEGER NOT NULL, " +
                "synced INTEGER NOT NULL, PRIMARY KEY(deviceId, ts))")
            sql("CREATE TABLE ppgHrSample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm INTEGER NOT NULL, " +
                "PRIMARY KEY(deviceId, ts))")
            sql("CREATE TABLE gravitySample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, x REAL NOT NULL, " +
                "y REAL NOT NULL, z REAL NOT NULL, PRIMARY KEY(deviceId, ts))")
            sql("CREATE TABLE respSample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, raw INTEGER NOT NULL, " +
                "PRIMARY KEY(deviceId, ts))")
            sql("CREATE TABLE stepSample(deviceId TEXT NOT NULL, ts INTEGER NOT NULL, counter INTEGER NOT NULL, " +
                "PRIMARY KEY(deviceId, ts))")
            listOf("spo2Sample", "skinTempSample", "sleepStateSample", "event").forEach {
                sql("CREATE TABLE $it(deviceId TEXT, ts INTEGER)")
            }
        }

        val dao: WhoopDao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) { _, method, a ->
            val args = a ?: emptyArray()
            calls.merge(method.name, 1, Int::plus)
            fun window() = listOf("deviceId", "from", "to", "limit").zip(args.take(4)).toMap()
            when (method.name) {
                "pairedDevice" -> owners[args[0] as String]
                "pairedDevices" -> owners.values.toList()
                "activeDeviceId" -> owners.values.singleOrNull { it.status == "active" }?.id
                "gravitySamples" -> {
                    duringNextGravityRead?.also { duringNextGravityRead = null }?.invoke()
                    query("SELECT * FROM gravitySample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
                        "ORDER BY ts ASC LIMIT :limit", window()) {
                        GravitySample(it.getString("deviceId"), it.getLong("ts"), it.getDouble("x"), it.getDouble("y"), it.getDouble("z"))
                    }
                }
                // The DAO's COALESCE union: measured heart rate, and PPG-derived only where none was measured.
                "hrSamples" -> query(
                    "SELECT deviceId, ts, bpm, synced FROM (" +
                        "SELECT deviceId, ts, bpm, synced FROM hrSample " +
                        "WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
                        "UNION ALL " +
                        "SELECT p.deviceId AS deviceId, p.ts AS ts, p.bpm AS bpm, 0 AS synced FROM ppgHrSample p " +
                        "WHERE p.deviceId = :deviceId AND p.ts >= :from AND p.ts <= :to " +
                        "AND NOT EXISTS (SELECT 1 FROM hrSample h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)" +
                        ") ORDER BY ts ASC LIMIT :limit", window(),
                ) { HrSample(it.getString("deviceId"), it.getLong("ts"), it.getInt("bpm")) }
                "respSamples" -> query("SELECT * FROM respSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
                    "ORDER BY ts ASC LIMIT :limit", window()) {
                    RespSample(it.getString("deviceId"), it.getLong("ts"), it.getInt("raw"))
                }
                "stepSamples" -> query("SELECT * FROM stepSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
                    "ORDER BY ts ASC LIMIT :limit", window()) {
                    StepSample(it.getString("deviceId"), it.getLong("ts"), it.getInt("counter"))
                }
                "rrIntervals", "whoop5RrIntervals", "whoop4RrIntervals" -> query(
                    when (method.name) {
                        "whoop5RrIntervals" -> WHOOP5_RR_INTERVALS_SQL
                        "whoop4RrIntervals" -> WHOOP4_RR_INTERVALS_SQL
                        else -> RR_INTERVALS_SQL
                    },
                    window(),
                ) { r ->
                    fun optional(column: String) = r.getInt(column).let { if (r.wasNull()) null else it }
                    RrInterval(r.getString("deviceId"), r.getLong("ts"), r.getInt("rrMs"), r.getInt("seq"),
                        r.getInt("synced"), optional("ord"), optional("srcChannel"), optional("tsSuspect"))
                }
                "hasWhoop5RrSource" -> query(HAS_WHOOP5_RR_SOURCE_SQL, mapOf("deviceId" to args[0])) { it.getBoolean(1) }.single()
                "hasWhoop4HistoricalRrSource" -> query(
                    "SELECT EXISTS(SELECT 1 FROM rrInterval WHERE deviceId = :deviceId AND srcChannel = 8)",
                    mapOf("deviceId" to args[0])) { it.getBoolean(1) }.single()
                "countHrInWindow" -> query(
                    "SELECT COUNT(*) FROM hrSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to",
                    window() - "limit") { it.getInt(1) }.single()
                "maxHrTsInWindow" -> query(
                    "SELECT COALESCE(MAX(ts), 0) FROM hrSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to",
                    window() - "limit") { it.getLong(1) }.single()
                "dayStreamFingerprint" -> {
                    witnessFailure?.let { throw it }
                    query(DAY_STREAM_FINGERPRINT_SQL, window() - "limit") { it.getString(1) }.single()
                }
                "sleepSessions" -> sleeps.values
                    .filter { it.deviceId == args[0] && it.startTs in (args[1] as Long)..(args[2] as Long) }
                    .sortedBy { it.startTs }.take(args[3] as Int)
                "updateSleepStages" -> {
                    val key = (args[0] as String) to (args[1] as Long)
                    val row = sleeps[key]
                    if (row == null || !row.userEdited) 0
                    else { sleeps[key] = row.copy(stagesJSON = args[2] as String); stageWrites++; 1 }
                }
                else -> error("Unimplemented DAO call: ${method.name}")
            }
        } as WhoopDao

        val repo = WhoopRepository(dao)

        /** Raw gravity reads so far: a re-stage starts with one, a skipped night makes none. */
        val nightsRead: Int get() = calls["gravitySamples"] ?: 0

        fun sql(text: String) { db.createStatement().use { it.execute(text) } }

        fun update(text: String, values: Map<String, Any?> = emptyMap()): Int =
            statement(text, values).use { it.executeUpdate() }

        private fun statement(text: String, values: Map<String, Any?>) = run {
            val names = ArrayList<String>()
            val bound = Regex(":([A-Za-z][A-Za-z0-9]*)").replace(text) { names += it.groupValues[1]; "?" }
            db.prepareStatement(bound).also { stmt ->
                names.forEachIndexed { index, name ->
                    require(values.containsKey(name)) { "Missing SQL bind: $name" }
                    stmt.setObject(index + 1, values[name])
                }
            }
        }

        fun <T> query(text: String, values: Map<String, Any?> = emptyMap(), map: (ResultSet) -> T): List<T> =
            statement(text, values).use { stmt ->
                stmt.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
            }

        /** Runs [fill] in one transaction; a night is tens of thousands of rows. */
        fun bulk(fill: () -> Unit) {
            db.autoCommit = false
            try { fill(); db.commit() } finally { db.autoCommit = true }
        }

        fun register(id: String, model: String, brand: String = "WHOOP", status: String = "paired") {
            owners.replaceAll { _, row -> if (status == "active") row.copy(status = "paired") else row }
            owners[id] = PairedDeviceRow(id, brand, model, null, sourceKind = "liveBLE",
                capabilities = "hr,hrv", status = status, addedAt = 1, lastSeenAt = 1)
            update("INSERT OR REPLACE INTO pairedDevice VALUES(:id, :brand, :model, :status)",
                mapOf("id" to id, "brand" to brand, "model" to model, "status" to status))
        }

        fun gravity(ts: Long, x: Double = 0.2, y: Double = 0.3, z: Double = 0.9, device: String = STRAP) =
            update("INSERT OR IGNORE INTO gravitySample VALUES(:d, :t, :x, :y, :z)",
                mapOf("d" to device, "t" to ts, "x" to x, "y" to y, "z" to z))

        fun hr(ts: Long, bpm: Int = 58, device: String = STRAP) =
            update("INSERT OR IGNORE INTO hrSample VALUES(:d, :t, :b, 0)", mapOf("d" to device, "t" to ts, "b" to bpm))

        fun rr(ts: Long, rrMs: Int = 1_010, channel: Int? = null, device: String = STRAP) =
            update("INSERT OR IGNORE INTO rrInterval(deviceId, ts, rrMs, seq, synced, ord, srcChannel, tsSuspect) " +
                "VALUES(:d, :t, :r, 0, 0, 0, :c, NULL)", mapOf("d" to device, "t" to ts, "r" to rrMs, "c" to channel))

        /**
         * A worn night at 1 Hz: a wrist that settles and turns over now and then, a heart rate that drifts,
         * a beat a second, a breathing waveform. Seeded, so two stores given the same call hold the same rows.
         * [gravityGap] leaves those seconds of the night without gravity, for a later offload to bring.
         */
        fun seedNight(start: Long, seconds: Int = NIGHT_SECONDS, seed: Long = 7L, gravityGap: IntRange = IntRange.EMPTY) = bulk {
            val random = java.util.Random(seed)
            val gravityRow = db.prepareStatement("INSERT OR IGNORE INTO gravitySample VALUES(?, ?, ?, ?, ?)")
            val hrRow = db.prepareStatement("INSERT OR IGNORE INTO hrSample VALUES(?, ?, ?, 0)")
            val rrRow = db.prepareStatement("INSERT OR IGNORE INTO rrInterval(deviceId, ts, rrMs, seq, synced, ord, " +
                "srcChannel, tsSuspect) VALUES(?, ?, ?, 0, 0, 0, NULL, NULL)")
            val respRow = db.prepareStatement("INSERT OR IGNORE INTO respSample VALUES(?, ?, ?)")
            for (s in 0 until seconds) {
                val ts = start + s
                val posture = (s / 1_500) % 3
                val noise = if (s % 1_500 < 20) 0.4 else 0.004
                val x = (if (posture == 0) 0.1 else 0.7) + random.nextGaussian() * noise
                val y = (if (posture == 1) 0.8 else 0.2) + random.nextGaussian() * noise
                val z = (if (posture == 2) 0.2 else 0.6) + random.nextGaussian() * noise
                if (ts != GRAVITY_HOLE && s !in gravityGap) {
                    gravityRow.setString(1, STRAP); gravityRow.setLong(2, ts)
                    gravityRow.setDouble(3, x); gravityRow.setDouble(4, y); gravityRow.setDouble(5, z)
                    gravityRow.addBatch()
                }
                val bpm = 54 + (6 * kotlin.math.sin(s / 900.0)).toInt() + random.nextInt(3)
                if (ts != HR_HOLE) {
                    hrRow.setString(1, STRAP); hrRow.setLong(2, ts); hrRow.setInt(3, bpm); hrRow.addBatch()
                }
                rrRow.setString(1, STRAP); rrRow.setLong(2, ts)
                rrRow.setInt(3, 60_000 / bpm + (25 * kotlin.math.sin(2 * Math.PI * s / 4.3)).toInt()); rrRow.addBatch()
                respRow.setString(1, STRAP); respRow.setLong(2, ts)
                respRow.setInt(3, 2_000 + (300 * kotlin.math.sin(2 * Math.PI * s / 4.3)).toInt()); respRow.addBatch()
            }
            for (rows in listOf(gravityRow, hrRow, rrRow, respRow)) rows.use { it.executeBatch() }
        }

        /** An edited night whose stages were made up at edit time: one block of "wake" over its bounds. */
        fun editedNight(start: Long, end: Long, adjustedStart: Long? = null) {
            val from = adjustedStart ?: start
            sleeps[COMPUTED to start] = SleepSession(deviceId = COMPUTED, startTs = start, endTs = end,
                stagesJSON = AnalyticsEngine.encodeStages(listOf(StageSegment(start = from, end = end, stage = "wake"))),
                userEdited = true, startTsAdjusted = adjustedStart)
        }

        fun night(start: Long = NIGHT): SleepSession = sleeps.getValue(COMPUTED to start)

        /** The pass as the engine runs it: with this store's memory. */
        fun heal(strap: String = STRAP, v2: Boolean = true, motionAwareWake: Boolean = false): List<SleepSession> = runBlocking {
            SleepStageHealer.selfHealEditedStages(repo, COMPUTED, strap, WINDOW_FROM, WINDOW_TO, v2, motionAwareWake)
        }

        /** The pass as it was before the memory existed: every edited night re-read and re-staged. */
        fun healWithoutMemory(v2: Boolean = true, motionAwareWake: Boolean = false): List<SleepSession> = runBlocking {
            SleepStageHealer.healEditedStages(repo, COMPUTED, STRAP, WINDOW_FROM, WINDOW_TO, v2, motionAwareWake, memory = null)
        }

        fun inputs(strap: String = STRAP, v2: Boolean = true, motionAwareWake: Boolean = false) = runBlocking {
            val row = night()
            SleepStageHealer.restageInputs(repo, strap, row.effectiveStartTs, row.endTs, v2, motionAwareWake)
        }

        override fun close() = db.close()
    }

    /** One dense edited night, healed and then seen unchanged once, so the next pass would skip it. */
    private fun settledStore(): Store = Store().also { store ->
        store.seedNight(NIGHT)
        store.editedNight(NIGHT, NIGHT_END)
        store.heal()
        assertEquals("the first pass reads the night and heals it", 1, store.nightsRead)
        assertEquals(1, store.stageWrites)
        store.heal()
        assertEquals("the second pass finds it unchanged and does not read it", 1, store.nightsRead)
        assertEquals(1, store.repo.restagedNights.size)
    }

    // MARK: - the skip engages

    @Test
    fun anUnchangedNightIsNotReadAgain() {
        settledStore().use { store ->
            val healed = store.night().stagesJSON
            val rowReads = listOf("gravitySamples", "hrSamples", "respSamples", "stepSamples",
                "rrIntervals", "whoop5RrIntervals", "whoop4RrIntervals")
            val before = rowReads.associateWith { store.calls[it] ?: 0 }
            repeat(3) {
                val rows = store.heal()
                assertEquals(listOf(store.night()), rows)
            }
            assertEquals("no raw row is fetched for an unchanged night", before, rowReads.associateWith { store.calls[it] ?: 0 })
            assertEquals("and nothing is written", 1, store.stageWrites)
            assertEquals(healed, store.night().stagesJSON)
        }
    }

    /** A night with too little raw to stage is remembered as that: no stages, and no need to look again. */
    @Test
    fun aNightTooSparseToStageIsNotReadAgainEither() {
        Store().use { store ->
            store.editedNight(NIGHT, NIGHT_END)
            val fabricated = store.night().stagesJSON
            store.heal()
            store.heal()
            assertEquals(1, store.nightsRead)
            assertEquals(0, store.stageWrites)
            assertEquals(fabricated, store.night().stagesJSON)
            // Its raw arrives: the night is read again and heals.
            store.seedNight(NIGHT)
            store.heal()
            assertEquals(2, store.nightsRead)
            assertEquals(1, store.stageWrites)
            assertNotEquals(fabricated, store.night().stagesJSON)
        }
    }

    // MARK: - it cannot engage when an input moved

    /** Each input a re-stage reads, changed alone. The night must be read again, and then settle again. */
    @Test
    fun everyInputThatMovesIsReStaged() {
        class Change(
            val name: String,
            val strap: String = STRAP,
            val v2: Boolean = true,
            val motionAwareWake: Boolean = false,
            /** False for the one change that is not an input of the re-stage but of the comparison after it. */
            val movesTheInputs: Boolean = true,
            val apply: (Store) -> Unit = {},
        )
        val changes = listOf(
            Change("the bed time is corrected again") { it.sleeps[COMPUTED to NIGHT] = it.night().copy(startTsAdjusted = NIGHT + 600L) },
            Change("the wake time is corrected again") { it.sleeps[COMPUTED to NIGHT] = it.night().copy(endTs = NIGHT_END - 600L) },
            Change("the sleep-staging switch is flipped", v2 = false),
            Change("the motion-aware wake switch is flipped", motionAwareWake = true),
            Change("the raw is read under another strap id", strap = "whoop-other"),
            Change("a gravity row lands in the night") { assertEquals(1, it.gravity(GRAVITY_HOLE)) },
            Change("a gravity row lands in the lead-in hour") { assertEquals(1, it.gravity(NIGHT - 1_800L)) },
            Change("a heart-rate row lands in the night") { assertEquals(1, it.hr(HR_HOLE)) },
            Change("a heart-rate row lands in the lead-out hour") { assertEquals(1, it.hr(NIGHT_END + 900L)) },
            Change("a PPG-derived heart-rate row lands") {
                assertEquals(1, it.update("INSERT INTO ppgHrSample VALUES('$STRAP', $HR_HOLE, 57)"))
            },
            Change("an R-R beat lands") { assertEquals(1, it.rr(NIGHT + 300L, rrMs = 1_234)) },
            Change("a beat is relabelled in place as type-47 history") {
                assertEquals(1, it.update(PROMOTE_WHOOP4_HISTORY_SQL, mapOf("deviceId" to STRAP, "ts" to NIGHT + 300L,
                    "rrMs" to it.query("SELECT rrMs FROM rrInterval WHERE ts = ${NIGHT + 300}") { r -> r.getInt(1) }.single(),
                    "seq" to 0, "ord" to 3)))
            },
            Change("a beat is marked suspect in place") {
                assertEquals(1, it.update("UPDATE rrInterval SET tsSuspect = 1 WHERE ts = ${NIGHT + 301}"))
            },
            Change("a respiration row lands") {
                assertEquals(1, it.update("INSERT INTO respSample VALUES('$STRAP', ${NIGHT - 900}, 2100)"))
            },
            Change("a step row lands") {
                assertEquals(1, it.update("INSERT INTO stepSample VALUES('$STRAP', ${NIGHT + 400}, 12)"))
            },
            Change("an hour of the night's raw is deleted") {
                assertEquals(3_599, it.update("DELETE FROM hrSample WHERE ts >= $NIGHT AND ts < ${NIGHT + 3_600}"))
            },
            Change("a row is re-keyed to another device") {
                assertEquals(1, it.update("UPDATE OR IGNORE gravitySample SET deviceId = 'whoop-other' WHERE ts = ${NIGHT + 90}"))
            },
            Change("the strap is registered as a WHOOP 4.0") { it.register(STRAP, "4.0") },
            Change("the strap is registered as a WHOOP 5.0") { it.register(STRAP, "5.0 MG") },
            Change("its first 5/MG-tagged beat is banked, a week before the night") {
                assertEquals(1, it.rr(NIGHT - 7 * 86_400L, channel = 5))
            },
            Change("another strap becomes the active one and the canonical alias takes its policy") {
                it.register("whoop-new", "5.0 MG", status = "active")
            },
            Change("its stored stages are replaced by a later edit", movesTheInputs = false) {
                it.sleeps[COMPUTED to NIGHT] = it.night().copy(
                    stagesJSON = AnalyticsEngine.encodeStages(listOf(StageSegment(NIGHT, NIGHT_END, "light"))))
            },
        )
        assertEquals(22, changes.size)
        for (change in changes) {
            settledStore().use { store ->
                val before = store.inputs()
                assertNotNull(before)
                change.apply(store)
                val after = store.inputs(change.strap, change.v2, change.motionAwareWake)
                assertNotNull(after)
                assertEquals("${change.name}: witness moved", change.movesTheInputs, before != after)
                store.heal(change.strap, change.v2, change.motionAwareWake)
                assertEquals("${change.name}: the night must be read again", 2, store.nightsRead)
                // And it settles on the new state: the same pass again reads nothing.
                store.heal(change.strap, change.v2, change.motionAwareWake)
                assertEquals("${change.name}: then it is remembered again", 2, store.nightsRead)
                // Going back to the old arguments is a change too.
                if (change.strap != STRAP || !change.v2 || change.motionAwareWake) {
                    store.heal()
                    assertEquals("${change.name}: and back", 3, store.nightsRead)
                }
            }
        }
    }

    /**
     * "Has this strap ever banked a type-47 beat" is asked of the whole table and picks the R-R read. A
     * beat banked a week away moves no count in the night's window, and still changes what the read
     * returns for it. Only the read policy in the witness can see that.
     */
    @Test
    fun theFirstType47BeatAnywhereIsAnInput() {
        settledStore().use { store ->
            // A realtime-labelled beat beside the unlabelled one: the generic read returns both, the
            // WHOOP 4 read one source for the hour.
            assertEquals(1, store.rr(NIGHT + 300L, rrMs = 1_111, channel = 9))
            store.heal()
            store.heal()
            assertEquals(2, store.nightsRead)
            val before = store.inputs()!!
            val beatsBefore = runBlocking { store.repo.rrIntervalsForDevice(STRAP, NIGHT, NIGHT_END) }

            assertEquals(1, store.rr(NIGHT - 7 * 86_400L, channel = 8))
            val after = store.inputs()!!
            assertEquals("no windowed count moved", before.streams, after.streams)
            assertEquals(before.hrCount to before.hrMaxTs, after.hrCount to after.hrMaxTs)
            assertEquals(WhoopRepository.RrReadPolicy.GENERIC, before.rrPolicy)
            assertEquals(WhoopRepository.RrReadPolicy.WHOOP4, after.rrPolicy)
            assertNotEquals("and the read returns different beats for the night",
                beatsBefore, runBlocking { store.repo.rrIntervalsForDevice(STRAP, NIGHT, NIGHT_END) })

            store.heal()
            assertEquals("so the night is read again", 3, store.nightsRead)
        }
    }

    /**
     * The WHOOP 4 read chooses one R-R source per UTC hour, so it reads the whole of the range's first
     * hour. A type-47 beat in that hour BEFORE the range changes which beats come back inside it. The
     * witness counts whole hours for that reason; counted over the range alone it would not move.
     */
    @Test
    fun aBeatInTheHourBeforeTheReadRangeIsAnInput() {
        Store().use { store ->
            store.register(STRAP, "4.0")
            store.seedNight(NIGHT)
            // Unlabelled beats in the lead-in, the part of the range that shares its hour with the margin.
            store.bulk { for (s in 0 until 600) store.rr(NIGHT - 3_600L + s, rrMs = 990 + s % 7) }
            store.editedNight(NIGHT, NIGHT_END)
            store.heal()
            store.heal()
            assertEquals(1, store.nightsRead)

            val lo = NIGHT - 3_600L
            val hi = NIGHT_END + 3_600L
            val marginTs = lo - 600L
            assertEquals("the margin beat is in the range's first hour, outside the range", lo / 3_600L, marginTs / 3_600L)
            val rangeOnly = runBlocking { store.repo.dayStreamFingerprint(STRAP, lo, hi) }
            val beatsBefore = runBlocking { store.repo.rrIntervalsForDevice(STRAP, lo, hi) }
            val before = store.inputs()!!

            assertEquals(1, store.rr(marginTs, channel = 8))
            assertEquals("counted over the read range alone, nothing moved",
                rangeOnly, runBlocking { store.repo.dayStreamFingerprint(STRAP, lo, hi) })
            assertNotEquals("yet the read now returns different beats inside the range",
                beatsBefore, runBlocking { store.repo.rrIntervalsForDevice(STRAP, lo, hi) })
            assertNotEquals("the witness counts whole hours and moved", before, store.inputs())

            store.heal()
            assertEquals("so the night is read again", 2, store.nightsRead)
        }
    }

    @Test
    fun aWitnessThatCannotBeReadNeverSkips() {
        settledStore().use { store ->
            store.witnessFailure = java.sql.SQLException("database is locked")
            assertNull(store.inputs())
            repeat(3) { store.heal() }
            assertEquals("every pass reads the night while its witness cannot be read", 4, store.nightsRead)
            assertEquals("and the stages it re-derives are the stored ones", 1, store.stageWrites)
            // Readable again, and the night is as it was remembered: skipped at once.
            store.witnessFailure = null
            store.heal()
            assertEquals(4, store.nightsRead)
        }
    }

    /** A pass that is being cancelled is not a witness that could not be read: it stops. */
    @Test
    fun cancellationIsNotSwallowedAsAnUnreadableWitness() {
        settledStore().use { store ->
            store.witnessFailure = CancellationException("the scoring pass was cancelled")
            try {
                store.heal()
                fail("the cancellation must reach the caller")
            } catch (expected: CancellationException) {
                assertEquals("the scoring pass was cancelled", expected.message)
            }
            assertEquals("and nothing was read past the witness", 1, store.nightsRead)
        }
    }

    /** Rows that land while a night is being read: its stages are not remembered against either witness. */
    @Test
    fun aNightThatMovedWhileItWasReadIsReadAgain() {
        Store().use { store ->
            store.seedNight(NIGHT)
            store.editedNight(NIGHT, NIGHT_END)
            store.duringNextGravityRead = { assertEquals(1, store.hr(HR_HOLE)) }
            store.heal()
            assertEquals(1, store.nightsRead)
            assertEquals("its witness differed before and after the read", 0, store.repo.restagedNights.size)
            store.heal()
            assertEquals("so the next pass reads it again", 2, store.nightsRead)
            assertEquals(1, store.repo.restagedNights.size)
            store.heal()
            assertEquals(2, store.nightsRead)
        }
    }

    @Test
    fun aNightThatLeftTheWindowIsForgotten() {
        settledStore().use { store ->
            assertEquals(1, store.repo.restagedNights.size)
            runBlocking {
                SleepStageHealer.selfHealEditedStages(store.repo, COMPUTED, STRAP, NIGHT + 86_400L, WINDOW_TO, true, false)
            }
            assertEquals(0, store.repo.restagedNights.size)
            // Another computed source's pass leaves this one's nights alone.
            store.heal()
            assertEquals(1, store.repo.restagedNights.size)
            runBlocking {
                SleepStageHealer.selfHealEditedStages(store.repo, "whoop-other-noop", STRAP, WINDOW_FROM, WINDOW_TO, true, false)
            }
            assertEquals(1, store.repo.restagedNights.size)
        }
    }

    /** Two stores are two memories: what one settled says nothing about the other. */
    @Test
    fun theMemoryBelongsToItsStore() {
        settledStore().use { first ->
            Store().use { second ->
                second.seedNight(NIGHT)
                second.editedNight(NIGHT, NIGHT_END)
                second.heal()
                assertEquals("the second store reads its own night", 1, second.nightsRead)
                assertEquals(first.night(), second.night())
            }
        }
    }

    // MARK: - it changes nothing that is stored or returned

    /**
     * The pass without the memory and the pass with it, over two stores given the same rows and the same
     * history of changes. After every pass both must return the same sessions and hold the same stages.
     */
    @Test
    fun withAndWithoutTheMemoryThePassStoresAndReturnsTheSame() {
        val secondNight = NIGHT + 86_400L
        val sparseNight = NIGHT + 2 * 86_400L
        fun seeded() = Store().also { store ->
            store.seedNight(NIGHT, seed = 11L, gravityGap = 2_000 until 2_600)
            store.seedNight(secondNight, seed = 12L)
            store.editedNight(NIGHT, NIGHT_END)
            // A bed time the user moved half an hour later than detected.
            store.editedNight(secondNight, secondNight + NIGHT_SECONDS - 1, adjustedStart = secondNight + 1_800L)
            store.editedNight(sparseNight, sparseNight + NIGHT_SECONDS - 1)
        }
        class Step(val name: String, val v2: Boolean = true, val motionAwareWake: Boolean = false, val apply: (Store) -> Unit = {})
        val steps = listOf(
            Step("the first pass"),
            Step("nothing changed"),
            Step("nothing changed, again"),
            Step("ten restless minutes of the first night arrive late") { store ->
                store.bulk {
                    val random = java.util.Random(3L)
                    for (s in 2_000 until 2_600) {
                        assertEquals(1, store.gravity(NIGHT + s, random.nextDouble(), random.nextDouble(), random.nextDouble()))
                    }
                }
            },
            Step("nothing changed after the motion"),
            Step("staged with V1", v2 = false),
            Step("V1 again", v2 = false),
            Step("back to V2"),
            Step("faster beats land in the second night") { store ->
                store.bulk { for (s in 0 until 1_200) store.rr(secondNight + 2_400L + s, rrMs = 640 + s % 40) }
            },
            Step("an hour of beats is relabelled as type-47 history") { store ->
                store.bulk {
                    store.update("UPDATE rrInterval SET srcChannel = 8, ord = 1 WHERE deviceId = '$STRAP' " +
                        "AND srcChannel IS NULL AND ts >= ${NIGHT + 3_600} AND ts < ${NIGHT + 7_200}")
                }
            },
            Step("nothing changed after the relabel"),
            Step("the strap is registered as a WHOOP 4.0") { it.register(STRAP, "4.0") },
            Step("the first night's bed time is moved and its stages made up again") { store ->
                val row = store.night(NIGHT)
                store.sleeps[COMPUTED to NIGHT] = row.copy(startTsAdjusted = NIGHT + 900L,
                    stagesJSON = AnalyticsEngine.encodeStages(listOf(StageSegment(NIGHT + 900L, NIGHT_END, "wake"))))
            },
            Step("nothing changed after the edit"),
            Step("motion-aware wake on", motionAwareWake = true),
            Step("steps land in the second night", motionAwareWake = true) { store ->
                store.bulk { for (s in 0 until 900) store.update("INSERT INTO stepSample VALUES('$STRAP', ${secondNight + 600 + s}, ${s * 2})") }
            },
            Step("motion-aware wake off"),
            Step("a 5/MG-tagged beat is banked a week earlier") { assertEquals(1, it.rr(NIGHT - 7 * 86_400L, channel = 5)) },
            Step("the sparse night's raw arrives") { it.seedNight(sparseNight, seed = 13L) },
            Step("nothing changed at the end"),
            Step("the second night stops being an edit") { store ->
                store.sleeps[COMPUTED to secondNight] = store.night(secondNight).copy(userEdited = false)
            },
            Step("nothing changed after that"),
        )
        seeded().use { without ->
            seeded().use { with ->
                var skippedPasses = 0
                for (step in steps) {
                    step.apply(without)
                    step.apply(with)
                    val readBefore = with.nightsRead
                    val expected = without.healWithoutMemory(step.v2, step.motionAwareWake)
                    val actual = with.heal(v2 = step.v2, motionAwareWake = step.motionAwareWake)
                    assertEquals("${step.name}: returned sessions", expected, actual)
                    assertEquals("${step.name}: stored sessions", without.sleeps, with.sleeps)
                    if (step.name.startsWith("nothing changed") || step.name == "V1 again") {
                        assertEquals("${step.name}: no night is read", readBefore, with.nightsRead)
                        skippedPasses++
                    }
                }
                assertEquals(8, skippedPasses)
                assertEquals("without the memory every pass reads every edited night",
                    20 * 3 + 2 * 2, without.nightsRead)
                assertTrue("with it, far fewer: ${with.nightsRead}", with.nightsRead < without.nightsRead / 2)
                assertEquals("the same stages were written the same number of times", without.stageWrites, with.stageWrites)
                assertTrue("the fixture must actually heal nights", with.stageWrites >= 6)
                assertFalse(with.sleeps.values.any { it.stagesJSON == null })
            }
        }
    }
}
