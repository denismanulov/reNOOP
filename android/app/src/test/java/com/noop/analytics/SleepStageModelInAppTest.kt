package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the app asks the learned stage model: the detector, the edited-night re-stage, and the store
 * that reads the shipped pair. Twin of Swift `SleepStageLearnedTests.testTheDetectorStagesWithTheModelOnceInstalled`
 * and of the loading half of `SleepStageModelStoreTests`.
 */
class SleepStageModelInAppTest {

    private val dev = "t"

    @After
    fun tearDown() { SleepStageLearned.model = null }

    /** A model whose second stage is sure of `stage(epoch index)`, and whose first stage says nothing. */
    private fun model(version: String, stage: (Int) -> String) = SleepStageModel(
        version = version, prior = null, weight = 1.0, smoothing = 1.0,
        first = { rows -> rows.map { doubleArrayOf(0.25, 0.25, 0.25, 0.25) } },
        second = { rows ->
            rows.indices.map { i -> SleepStageDecoder.stages.map { if (it == stage(i)) 0.97 else 0.01 }.toDoubleArray() }
        })

    /** The detector stages an accepted night with the installed model, and with the recipe when there is
     *  none. The same streams are asked for twice, so this also pins that the detector's memo does not
     *  hand the recipe's hypnogram to the model, nor the model's back to the recipe. */
    @Test
    fun theDetectorStagesWithTheModelOnceInstalled() {
        val night = 1_749_513_600L + 2 * 3600                       // 02:00 UTC, an overnight window
        val dur = 90 * 60
        val grav = (0 until dur).map { GravitySample(dev, night + it, 0.0, 0.0, 1.0) }
        val hr = (0 until dur).map { HrSample(dev, night + it, 50) }

        val recipe = SleepStager.detectSleep(hr = hr, gravity = grav, useSleepStagerV2 = true)
        assertEquals(1, recipe.size)

        SleepStageLearned.model = model("detector") { if (it < 60) "rem" else "deep" }
        val learned = SleepStager.detectSleep(hr = hr, gravity = grav, useSleepStagerV2 = true)
        assertEquals(1, learned.size)
        assertEquals(recipe[0].start, learned[0].start)
        assertEquals(recipe[0].end, learned[0].end)
        assertEquals(listOf("rem", "deep"), learned[0].stages.map { it.stage })
        assertNotEquals(recipe[0].stages, learned[0].stages)
        assertEquals(1.0, learned[0].efficiency, 1e-9)

        SleepStageLearned.model = null
        assertEquals(recipe, SleepStager.detectSleep(hr = hr, gravity = grav, useSleepStagerV2 = true))
    }

    /** An edited night re-staged inside the wearer's bounds asks the model first, and its bounds hold. */
    @Test
    fun anEditedNightIsRestagedByTheModelInsideItsBounds() {
        val start = 1_749_513_600L + 2 * 3600 + 17
        val end = start + 2 * 3600
        val grav = ((start - 2400) until (end + 2400)).map { GravitySample(dev, it, 0.3, 0.1, 0.94) }
        val hr = ((start - 2400) until (end + 2400)).map { HrSample(dev, it, 52) }

        val recipe = SleepStageHealer.restageFromSamples(start, end, grav, hr, emptyList(), emptyList(), true)
        assertNotNull(recipe)

        SleepStageLearned.model = model("heal") { if (it < 100) "deep" else "rem" }
        val learned = SleepStageHealer.restageFromSamples(start, end, grav, hr, emptyList(), emptyList(), true)
        val segments = AnalyticsEngine.decodeStages(learned)
        assertEquals(listOf("deep", "rem"), segments.map { it.stage })
        assertEquals(start, segments.first().start)
        assertEquals(end, segments.last().end)
        assertNotEquals(recipe, learned)

        // Motion too thin for the model: the recipe stages the night, as it did before there was one.
        val thin = grav.filter { it.ts % 4 == 0L }
        val withModel = SleepStageHealer.restageFromSamples(start, end, thin, hr, emptyList(), emptyList(), true)
        SleepStageLearned.model = null
        val withoutModel = SleepStageHealer.restageFromSamples(start, end, thin, hr, emptyList(), emptyList(), true)
        assertNotNull(withoutModel)
        assertEquals(withoutModel, withModel)
    }

    // ── the store ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theShippedPairLoadsUnderAVersionMadeOfItsTwoSources() {
        val model = SleepStageModelStore.load { SleepStageTestFiles.text(it.removeSuffix(".trees")) }
        assertNotNull(model)
        val first = SleepStageTestFiles.shipped("SleepStageFirst")
        val second = SleepStageTestFiles.shipped("SleepStageSecond")
        assertEquals(first.source + second.source, model!!.version)
        assertEquals(128, model.version.length)
    }

    @Test
    fun aPairThatIsMissingMalformedOrSwappedInstallsNothing() {
        fun text(name: String) = SleepStageTestFiles.text(name.removeSuffix(".trees"))
        assertNull(SleepStageModelStore.load { null })
        assertNull(SleepStageModelStore.load { if (it == SleepStageModelStore.SECOND) null else text(it) })
        assertNull(SleepStageModelStore.load { if (it == SleepStageModelStore.FIRST) text(it).dropLast(40) else text(it) })
        // The two the wrong way round take the wrong columns.
        assertNull(SleepStageModelStore.load {
            text(if (it == SleepStageModelStore.FIRST) SleepStageModelStore.SECOND else SleepStageModelStore.FIRST)
        })
        // A second file without the decoder's settings.
        assertNull(SleepStageModelStore.load {
            if (it == SleepStageModelStore.SECOND) text(it).replace("meta priorWeight", "meta weight") else text(it)
        })
    }

    /** A plain JVM run registers no reader, so nothing is installed and the recipe stages every test. */
    @Test
    fun withNothingRegisteredNothingIsInstalled() {
        SleepStageModelStore.ensureInstalled()
        assertNull(SleepStageLearned.model)
        assertEquals("", SleepStageLearned.version)
    }
}
