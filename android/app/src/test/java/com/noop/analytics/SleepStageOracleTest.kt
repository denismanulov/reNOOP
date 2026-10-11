package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * One night, from its streams to its hypnogram, against the oracle the Swift package writes
 * (`sleep_stage_oracle.json`, by `SleepStageTreesTests` with `SLEEP_STAGE_ORACLE_WRITE=1`). The Swift
 * test reads the same file, and `SleepStageTreesAgainstCoreMLTests` holds Core ML to it, so the three
 * implementations of the learned stage model cannot drift apart unnoticed.
 *
 * Each step is asserted in order, so a mismatch names the step that broke. Numbers that pass through a
 * transcendental function (`log10`, `acos`, `exp`) may differ in the last place between platforms and are
 * compared to 1e-9; epoch starts, stages and segments must be equal exactly. The oracle is never edited
 * to make this pass: a mismatch is a fault in the Kotlin twin.
 */
class SleepStageOracleTest {

    @After
    fun tearDown() { SleepStageLearned.model = null }

    private class Night(
        val start: Long, val end: Long,
        val grav: List<GravitySample>, val hr: List<HrSample>, val rr: List<RrInterval>,
    )

    private val oracle: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("sleep_stage_oracle.json")
        assertNotNull("sleep_stage_oracle.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().use { it.readText() })
    }

    /** Streams are stored one entry a second from `from`, `null` where there is no sample, gravity in
     *  1/scale g; beat intervals as seconds after `from` and milliseconds. */
    private fun night(): Night {
        val g = oracle.getJSONObject("gravity")
        val gFrom = g.getLong("from"); val scale = g.getDouble("scale")
        val gx = g.getJSONArray("x"); val gy = g.getJSONArray("y"); val gz = g.getJSONArray("z")
        val grav = ArrayList<GravitySample>()
        for (i in 0 until gx.length()) {
            if (gx.isNull(i)) continue
            grav.add(GravitySample(DEVICE, gFrom + i, gx.getDouble(i) / scale, gy.getDouble(i) / scale, gz.getDouble(i) / scale))
        }
        val h = oracle.getJSONObject("heartRate")
        val hFrom = h.getLong("from"); val bpm = h.getJSONArray("bpm")
        val hr = ArrayList<HrSample>()
        for (i in 0 until bpm.length()) if (!bpm.isNull(i)) hr.add(HrSample(DEVICE, hFrom + i, bpm.getInt(i)))
        val r = oracle.getJSONObject("beatIntervals")
        val rFrom = r.getLong("from"); val at = r.getJSONArray("at"); val ms = r.getJSONArray("ms")
        assertEquals(at.length(), ms.length())
        // `seq` keeps the order beats arrived in within a second, as the stored table does.
        val rr = List(at.length()) { RrInterval(DEVICE, rFrom + at.getLong(it), ms.getInt(it), seq = it) }
        return Night(oracle.getLong("start"), oracle.getLong("end"), grav, hr, rr)
    }

    private fun close(a: Double, b: Double): Boolean = abs(a - b) <= 1e-9 * max(1.0, max(abs(a), abs(b)))

    private fun compare(key: String, got: List<DoubleArray>) {
        val expected = oracle.getJSONArray(key)
        assertEquals(key, expected.length(), got.size)
        for (i in 0 until expected.length()) {
            val row = expected.getJSONArray(i)
            assertEquals("$key row $i", row.length(), got[i].size)
            for (j in 0 until row.length()) {
                val e = row.getDouble(j)
                if (!close(e, got[i][j])) fail("$key row $i column $j: ${got[i][j]} is not $e")
            }
        }
    }

    private fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }

    @Test
    fun oneNightComesOutAsTheCommittedOracleSays() {
        val first = SleepStageTestFiles.shipped("SleepStageFirst")
        val second = SleepStageTestFiles.shipped("SleepStageSecond")

        // The oracle was written from these very trees, or it proves nothing about them.
        val trees = oracle.getJSONObject("trees")
        assertEquals("the oracle was written from other trees", trees.getString("first"), first.source)
        assertEquals("the oracle was written from other trees", trees.getString("second"), second.source)
        val model = SleepStageModel.fromTrees(first, second, version = first.source + second.source)
        assertNotNull("the shipped pair is refused", model)
        model!!
        val night = night()

        // 1. Features.
        val rows = SleepStageFeatures.rows(night.start, night.end, night.grav, night.hr, night.rr)
        assertEquals(strings(oracle.getJSONArray("featureNames")), SleepStageFeatures.names)
        val features = oracle.getJSONArray("features")
        assertEquals("epochs", features.length(), rows.size)
        for (i in 0 until features.length()) {
            val expected = features.getJSONArray(i)
            assertEquals(expected.length(), rows[i].values.size)
            for (j in 0 until expected.length()) {
                val name = SleepStageFeatures.names[j]
                val got = rows[i].values[j]
                if (expected.isNull(j)) {
                    assertNull("epoch $i $name", got)
                } else {
                    val e = expected.getDouble(j)
                    if (got == null || !close(e, got)) fail("epoch $i $name: $got is not $e")
                }
            }
        }
        val epochStarts = oracle.getJSONArray("epochStarts")
        assertEquals(List(epochStarts.length()) { epochStarts.getLong(it) }, rows.map { it.start })

        // 2. The first model's input.
        val sampleArray = oracle.getJSONArray("sampleEpochs")
        val sampled = List(sampleArray.length()) { sampleArray.getInt(it) }
        assertEquals((0 until rows.size step SAMPLE_EVERY).toList(), sampled)
        val xs = SleepStageContext.firstInput(rows)
        compare("firstInput", sampled.map { xs[it] })

        // 3. The first model's probabilities.
        val p1 = model.first(xs)
        assertNotNull(p1)
        compare("firstProbabilities", p1!!)

        // 4. What the second model's input adds.
        val xs2 = SleepStageContext.secondInput(xs, p1)
        val width = SleepStageContext.firstNames.size
        compare("neighbours", sampled.map { xs2[it].copyOfRange(width, xs2[it].size) })
        for (i in sampled) assertTrue("row $i begins with the first input", xs2[i].copyOf(width).contentEquals(xs[i]))

        // 5. The second model's probabilities.
        val p2 = model.second(xs2)
        assertNotNull(p2)
        compare("secondProbabilities", p2!!)

        // 6. Stages.
        assertEquals(strings(oracle.getJSONArray("stageOrder")), SleepStageDecoder.stages)
        val stages = SleepStageDecoder.decode(p2, prior = model.prior, weight = model.weight, smoothing = model.smoothing)
        assertEquals(strings(oracle.getJSONArray("stages")), stages)
        // An oracle that exercised one stage only would prove little about the decoder.
        assertTrue(stages.toSet().size >= 3)

        // 7. Segments, through the entry the app calls.
        SleepStageLearned.model = model
        val segments = SleepStageLearned.stageSession(night.start, night.end, night.grav, night.hr, night.rr)
        assertNotNull("the oracle night is dense enough for the model", segments)
        val expected = oracle.getJSONArray("segments")
        assertEquals(
            List(expected.length()) { expected.getJSONArray(it).let { s -> StageSegment(s.getLong(0), s.getLong(1), s.getString(2)) } },
            segments)
        assertEquals(night.start, segments!!.first().start)
        assertEquals(night.end, segments.last().end)
    }

    private companion object {
        const val DEVICE = "oracle"
        /** The epochs whose full model inputs the oracle carries. */
        const val SAMPLE_EVERY = 16
    }
}

/** The tree files the app ships, read from the source tree: a JVM test has no asset manager. */
internal object SleepStageTestFiles {
    fun text(name: String): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rel = "src/main/assets/sleepstage/$name.trees"
        val found = listOf(File(userDir, rel), File(userDir, "app/$rel"), File(userDir, "android/app/$rel"))
            .firstOrNull { it.isFile }
        assertNotNull("$rel not found from user.dir=$userDir; a skip would read as a pass", found)
        return found!!.readText()
    }

    fun shipped(name: String): SleepStageTrees {
        val trees = SleepStageTrees.parse(text(name))
        assertNotNull("$name.trees is not readable", trees)
        return trees!!
    }
}
