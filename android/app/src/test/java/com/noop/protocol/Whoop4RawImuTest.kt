package com.noop.protocol

import java.io.File
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [Whoop4RawImu.accel], the Kotlin twin of the Swift `Whoop4RawImu.accel`.
 *
 * The first three tests are the Swift `Whoop4RawImuTests`, case for case, over the same parity corpus
 * (`frames.json`, a byte-identical copy of the Swift test resource). The corpus holds one 1928-byte raw
 * IMU packet and its axes are constant (0, 0, 4096), so by itself it can neither tell X from Y nor show
 * that a sample's sign survives. [oracleCasesDecodeAsTheSwiftDecoderDoes] covers that: frames with
 * distinct, signed, edge-valued samples in every axis, each decoded by the Swift decoder itself
 * (`whoop4_raw_imu_oracle.json`).
 */
class Whoop4RawImuTest {

    private fun resource(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assertNotNull("$name missing from test classpath", stream)
        return stream!!.bufferedReader().use { it.readText() }
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun corpus(): List<ByteArray> {
        val entries = JSONArray(resource("frames.json"))
        return (0 until entries.length()).map { hexToBytes(entries.getJSONObject(it).getString("hex")) }
    }

    /** A file of the Swift tree, or null when the test runs without it (Gradle's user.dir is android/app). */
    private fun swiftTreeFile(relative: String): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(File(userDir, relative), File(userDir, "../../$relative")).firstOrNull { it.exists() }
    }

    // MARK: - The Swift suite

    @Test
    fun decodesTheImuPacketsOfTheCorpus() {
        val imu = corpus().filter { it.size == 1928 }
        // One packet, and the values pinned below are that packet's: a second one needs its own.
        assertEquals(1, imu.size)
        for (frame in imu) {
            val buffer = Whoop4RawImu.accel(frame)
            assertNotNull(buffer)
            buffer!!
            assertEquals(100, buffer.x.size)
            assertEquals(100, buffer.y.size)
            assertEquals(100, buffer.z.size)
            // The Swift test compares with its schema interpreter (`parseFrame`), which reports the
            // timestamp and each axis as a mean rounded to one decimal. Android's `parseFrame` does not
            // decode this packet type, so the interpreter's answers for this packet are pinned here from
            // the Swift run: timestamp 31538447, accelX_mean 0, accelY_mean 0, accelZ_mean 4096.
            assertEquals(31_538_447L, buffer.timestamp)
            val reported = listOf("accelX" to 0.0, "accelY" to 0.0, "accelZ" to 4096.0)
            for ((axis, samples) in listOf(buffer.x, buffer.y, buffer.z).withIndex()) {
                val mean = samples.sumOf { it.toInt() }.toDouble() / 100
                assertEquals(reported[axis].first, reported[axis].second, mean, 0.051)
            }
            // A worn or resting strap reads about 1 g.
            val magnitudes = (0 until 100).map { i ->
                val x = buffer.x[i].toDouble()
                val y = buffer.y[i].toDouble()
                val z = buffer.z[i].toDouble()
                sqrt(x * x + y * y + z * z) * Whoop4RawImu.gPerLSB
            }
            assertEquals(1.0, magnitudes.sum() / 100, 0.25)
        }
    }

    @Test
    fun everyOtherFrameOfTheCorpusIsRefused() {
        val others = corpus().filter { it.size != 1928 }
        assertEquals(95, others.size)
        for (frame in others) assertNull(Whoop4RawImu.accel(frame))
    }

    @Test
    fun aDamagedImuPacketIsRefused() {
        val frame = corpus().first { it.size == 1928 }.copyOf()
        frame[300] = (frame[300].toInt() xor 0x40).toByte()
        assertNull(Whoop4RawImu.accel(frame))
    }

    // MARK: - Parity with the Swift decoder, by oracle

    /**
     * Every case of `whoop4_raw_imu_oracle.json` decodes to exactly what the Swift decoder returned for
     * the same bytes: null where it refused, otherwise the same timestamp, subseconds and 300 samples.
     */
    @Test
    fun oracleCasesDecodeAsTheSwiftDecoderDoes() {
        val cases = JSONObject(resource("whoop4_raw_imu_oracle.json")).getJSONArray("cases")
        var decoded = 0
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val got = Whoop4RawImu.accel(hexToBytes(case.getString("hex")))
            if (case.isNull("accel")) {
                assertNull("$name: Swift refuses this frame", got)
                continue
            }
            val want = case.getJSONObject("accel")
            assertNotNull("$name: Swift decodes this frame", got)
            got!!
            assertEquals("$name timestamp", want.getLong("timestamp"), got.timestamp)
            assertEquals("$name subseconds", want.getInt("subseconds"), got.subseconds)
            for ((axis, samples) in listOf("x" to got.x, "y" to got.y, "z" to got.z)) {
                val list = want.getJSONArray(axis)
                val expected = ShortArray(list.length()) { list.getInt(it).toShort() }
                assertEquals("$name $axis count", Whoop4RawImu.samplesPerPacket, expected.size)
                assertArrayEquals("$name $axis", expected, samples)
            }
            decoded += 1
        }
        // The oracle cannot quietly shrink: 24 frames, 5 of them decoded.
        assertEquals(24, cases.length())
        assertEquals(5, decoded)
    }

    /** What the oracle's edge samples are there to show: a sample keeps its sign, a timestamp its top bit. */
    @Test
    fun samplesAreSignedAndTheTimestampIsUnsigned() {
        val cases = JSONObject(resource("whoop4_raw_imu_oracle.json")).getJSONArray("cases")
        val byName = (0 until cases.length()).map { cases.getJSONObject(it) }.associateBy { it.getString("name") }
        fun decode(name: String) = Whoop4RawImu.accel(hexToBytes(byName.getValue(name).getString("hex")))!!

        val allOnes = decode("varied_b_timestamp_and_subseconds_all_ones")
        assertEquals(4_294_967_295L, allOnes.timestamp)
        assertEquals(65_535, allOnes.subseconds)
        assertEquals(2_147_483_648L, decode("varied_c_timestamp_bit_31").timestamp)

        val varied = decode("varied_a")
        assertEquals(Short.MIN_VALUE, varied.x[0])
        assertEquals(Short.MAX_VALUE, varied.x[1])
        assertEquals((-1).toShort(), varied.x[2])
        // The three axes hold different samples, so reading one from another's offset cannot pass.
        assertFalse(varied.x.contentEquals(varied.y))
        assertFalse(varied.y.contentEquals(varied.z))
        assertFalse(varied.x.contentEquals(varied.z))
        // A buffer is a value: the same bytes decode to equal buffers, different bytes do not.
        assertEquals(varied, decode("varied_a"))
        assertEquals(varied.hashCode(), decode("varied_a").hashCode())
        assertFalse(varied == decode("varied_d"))
    }

    // MARK: - Lockstep with the Swift tree

    /**
     * The Swift decoder reads its offsets from the schema at run time; the Kotlin one restates them. This
     * holds each restated constant to the schema file, so a schema edit that Android did not follow fails
     * here. Skips when the Swift tree is absent, like [CommandCatalogueTest].
     */
    @Test
    fun layoutConstantsMatchTheSharedSchema() {
        val rel = "Packages/WhoopProtocol/Sources/WhoopProtocol/Resources/whoop_protocol.json"
        val schemaFile = swiftTreeFile(rel)
        assumeTrue("shared schema not found, skipping the layout lockstep check", schemaFile != null)
        val packets = JSONObject(schemaFile!!.readText()).getJSONObject("packets")
        val packet = packets.getJSONObject("REALTIME_RAW_DATA")

        // The decoder gates on the type byte alone: no other packet may claim it, by type or by alias.
        assertEquals(PacketType.REALTIME_RAW_DATA.rawValue, packet.getInt("type"))
        for (name in packets.keys()) {
            if (name == "REALTIME_RAW_DATA") continue
            val other = packets.getJSONObject(name)
            val aliases = other.optJSONArray("aliases")
            val claimed = listOf(other.getInt("type")) + (0 until (aliases?.length() ?: 0)).map { aliases!!.getInt(it) }
            assertFalse("$name also claims type 43", PacketType.REALTIME_RAW_DATA.rawValue in claimed)
        }

        val fields = packet.getJSONArray("fields")
        fun field(name: String): JSONObject =
            (0 until fields.length()).map { fields.getJSONObject(it) }.single { it.getString("name") == name }
        assertEquals(Whoop4RawImu.timestampOffset, field("timestamp").getInt("off"))
        assertEquals(Whoop4RawImu.timestampLength, field("timestamp").getInt("len"))
        assertEquals(Whoop4RawImu.subsecondsOffset, field("subseconds").getInt("off"))
        assertEquals(Whoop4RawImu.subsecondsLength, field("subseconds").getInt("len"))

        // Exactly one variant is an IMU layout, and it is the one the decoder accepts.
        val variants = packet.getJSONObject("variants")
        val imuKeys = variants.keys().asSequence().filter { variants.getJSONObject(it).getString("kind") == "imu" }.toList()
        assertEquals(listOf(Whoop4RawImu.imuPayloadLength.toString()), imuKeys)
        val variant = variants.getJSONObject(imuKeys.single())
        assertEquals(Whoop4RawImu.samplesPerPacket, variant.getInt("samples"))
        assertEquals(Whoop4RawImu.gPerLSB, variant.getDouble("accel_scale"), 0.0)

        val axes = variant.getJSONArray("axes")
        val accel = (0 until axes.length()).map { axes.getJSONArray(it) }
            .filter { it.getString(2) == "accel" }
            .associate { it.getString(0) to it.getInt(1) }
        assertEquals(
            mapOf(
                "accelX" to Whoop4RawImu.accelXOffset,
                "accelY" to Whoop4RawImu.accelYOffset,
                "accelZ" to Whoop4RawImu.accelZOffset,
            ),
            accel,
        )
    }

    /** The Android corpus is the Swift corpus, byte for byte. Skips when the Swift tree is absent. */
    @Test
    fun corpusCopiesAreIdentical() {
        val swiftFile = swiftTreeFile("Packages/WhoopProtocol/Tests/WhoopProtocolTests/Resources/frames.json")
        assumeTrue("swift corpus not found, skipping the cross-copy identity check", swiftFile != null)
        val androidBytes = javaClass.classLoader!!.getResourceAsStream("frames.json")!!.use { it.readBytes() }
        assertTrue(
            "frames.json copies differ: keep the Android and Swift copies in lockstep",
            androidBytes.contentEquals(swiftFile!!.readBytes()),
        )
    }
}
