package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

/**
 * [SleepStageLearned] hands a night to the installed model and tiles its answer the way the recipe tiles
 * its own. What these pin is the contract with the caller: a hypnogram that covers the window exactly,
 * or null — never a guess — whenever the model is absent, starved of data, or does not answer. Twin of
 * Swift `SleepStageLearnedTests`.
 */
class SleepStageLearnedTest {

    private val start = 1_700_000_017L           // off the 30 s grid, as a detected onset is
    private val duration = 3 * 3600L
    private val end get() = start + duration
    private val dev = "t"

    @After
    fun tearDown() { SleepStageLearned.model = null }

    private fun gravity(every: Long = 1, from: Long? = null, to: Long? = null): List<GravitySample> =
        ((from ?: (start - 2400)) until (to ?: (end + 2400)) step every).map { t ->
            GravitySample(dev, t, x = 0.3 + 0.001 * sin(t.toDouble()), y = 0.1, z = 0.94)
        }

    private fun heartRate(every: Long = 1): List<HrSample> =
        ((start - 2400) until (end + 2400) step every).map { t ->
            HrSample(dev, t, 55 + (4 * sin((t - start).toDouble() / 600)).toInt())
        }

    private val flat: SleepStagePredict = { rows -> rows.map { doubleArrayOf(0.1, 0.7, 0.1, 0.1) } }

    /** A model whose second stage is sure of `stage(epoch index)`, and whose first stage says nothing. */
    private fun model(version: String = "test", stage: (Int) -> String) = SleepStageModel(
        version = version, prior = null, weight = 1.0, smoothing = 1.0,
        first = { rows -> rows.map { doubleArrayOf(0.25, 0.25, 0.25, 0.25) } },
        second = { rows ->
            rows.indices.map { i -> SleepStageDecoder.stages.map { if (it == stage(i)) 0.97 else 0.01 }.toDoubleArray() }
        })

    private fun stage(grav: List<GravitySample> = gravity(), hr: List<HrSample> = heartRate()) =
        SleepStageLearned.stageSession(start, end, grav, hr, emptyList())

    @Test
    fun withoutAModelThereIsNoAnswer() {
        assertNull(stage())
        assertEquals("", SleepStageLearned.version)
    }

    @Test
    fun theHypnogramTilesTheWindowExactly() {
        SleepStageLearned.model = model { i -> if (i < 100) "light" else if (i < 220) "deep" else if (i < 300) "rem" else "wake" }
        val segs = stage()
        assertNotNull(segs)
        segs!!
        assertEquals(listOf("light", "deep", "rem", "wake"), segs.map { it.stage })
        assertEquals(start, segs.first().start)
        assertEquals(end, segs.last().end)
        for ((a, b) in segs.zip(segs.drop(1))) assertEquals(a.end, b.start)
        val grid = ((start + 29) / 30) * 30
        assertEquals(grid + 100 * 30, segs[1].start)
        assertEquals(grid + 220 * 30, segs[2].start)
        assertEquals(grid + 300 * 30, segs[3].start)
    }

    @Test
    fun eachModelIsGivenItsOwnColumns() {
        var firstWidth = 0; var secondWidth = 0; var firstRows = 0; var secondRows = 0; var backOne = 0.0
        SleepStageLearned.model = SleepStageModel(
            version = "widths", prior = null, weight = 1.0, smoothing = 1.0,
            first = { rows -> firstWidth = rows.firstOrNull()?.size ?: 0; firstRows = rows.size; flat(rows) },
            second = { rows ->
                secondWidth = rows.firstOrNull()?.size ?: 0; secondRows = rows.size
                val col = SleepStageContext.secondNames.indexOf("p_light_back_1")
                backOne = if (rows.size > 5) rows[5][col] else 0.0
                flat(rows)
            })
        assertNotNull(stage())
        assertEquals(SleepStageContext.firstNames.size, firstWidth)
        assertEquals(SleepStageContext.secondNames.size, secondWidth)
        assertEquals(firstRows, secondRows)
        // the second model reads the first one's probabilities, not a constant of its own
        assertEquals(0.7, backOne, 0.0)
    }

    /** A strap that banks motion coarsely is the recipe's to stage: the model was never shown such a night. */
    @Test
    fun thinMotionIsNotStaged() {
        SleepStageLearned.model = model { "light" }
        assertNull(stage(grav = gravity(every = 4)))
        assertNull(stage(grav = emptyList()))
        // motion for the first half of the night only
        assertNull(stage(grav = gravity(to = start + duration / 2)))
    }

    @Test
    fun thinHeartRateIsNotStagedButAFiveSecondCadenceIs() {
        SleepStageLearned.model = model { "light" }
        assertNull(stage(hr = heartRate(every = 60)))
        assertNull(stage(hr = emptyList()))
        assertNotNull(stage(hr = heartRate(every = 5)))
    }

    /** The gate is a share of the window, 80 % of its seconds and of its 10 s bins, and it is inclusive. */
    @Test
    fun theCoverageGateSitsAtEightyPercent() {
        val s = 1_700_000_000L; val e = s + 1000
        fun grav(n: Int) = (0 until n).map { GravitySample(dev, s + it, 0.0, 0.0, 1.0) }
        fun hr(bins: Int) = (0 until bins).map { HrSample(dev, s + it * 10L, 50) }
        assertEquals(true, SleepStageLearned.covers(s, e, grav(800), hr(80)))
        assertEquals(false, SleepStageLearned.covers(s, e, grav(799), hr(80)))
        assertEquals(false, SleepStageLearned.covers(s, e, grav(800), hr(79)))
        // Samples outside the window do not count toward it.
        assertEquals(false, SleepStageLearned.covers(s, e, grav(799) + GravitySample(dev, e, 0.0, 0.0, 1.0), hr(80)))
        assertEquals(false, SleepStageLearned.covers(s, s, grav(800), hr(80)))
    }

    @Test
    fun aModelThatDoesNotAnswerLeavesTheNightToTheRecipe() {
        val bad: List<Pair<String, SleepStagePredict>> = listOf(
            "null" to { _ -> null },
            "short" to { rows -> flat(rows)!!.dropLast(1) },
            "narrow" to { rows -> rows.map { doubleArrayOf(0.5, 0.5) } },
            "nan" to { rows -> rows.map { doubleArrayOf(0.1, Double.NaN, 0.1, 0.1) } },
        )
        for ((name, predict) in bad) {
            SleepStageLearned.model = SleepStageModel("first-$name", null, 1.0, 1.0, first = predict, second = flat)
            assertNull(name, stage())
            SleepStageLearned.model = SleepStageModel("second-$name", null, 1.0, 1.0, first = flat, second = predict)
            assertNull(name, stage())
        }
    }

    /** A night staged under one version is not served to another from the cache. */
    @Test
    fun anotherVersionStagesTheNightAgain() {
        val grav = gravity(); val hr = heartRate()
        SleepStageLearned.model = model(version = "a") { "light" }
        assertEquals(listOf("light"), stage(grav, hr)?.map { it.stage })
        SleepStageLearned.model = model(version = "b") { "deep" }
        assertEquals("b", SleepStageLearned.version)
        assertEquals(listOf("deep"), stage(grav, hr)?.map { it.stage })
    }

    /** A caller that stretches a segment it was handed does not change what the next caller is handed. */
    @Test
    fun aCachedNightIsHandedOutAsACopy() {
        // Its own version: the cache outlives a test, and another test stages these streams as "test".
        SleepStageLearned.model = model(version = "copy") { "light" }
        val grav = gravity(); val hr = heartRate()
        val a = stage(grav, hr)!!
        a[0].end = a[0].end + 999
        a[0].stage = "wake"
        val b = stage(grav, hr)!!
        assertEquals(end, b[0].end)
        assertEquals("light", b[0].stage)
    }

    @Test
    fun anEmptyOrInvertedWindowIsNotStaged() {
        SleepStageLearned.model = model { "light" }
        assertNull(SleepStageLearned.stageSession(end, start, gravity(), heartRate(), emptyList()))
        assertNull(SleepStageLearned.stageSession(start, start, gravity(), heartRate(), emptyList()))
    }
}
