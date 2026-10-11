package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A fixed-sequence generator, so a fixture is the same on every platform and run. Twin of the Swift
 *  tests' `SplitMix`. */
internal class SplitMix(seed: Long) {
    private var state = seed
    fun unit(): Double {
        state += -0x61c8864680b583ebL                // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L   // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L   // 0x94D049BB133111EB
        return ((z xor (z ushr 31)) ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}

/**
 * [SleepStageFeatures] feeds a model trained on other hardware, so what these pin is PORTABILITY: the
 * features must not move when the things that differ between a dataset's device and a strap move — the
 * heart-rate cadence, the wearer's resting pulse, the sensor's noise floor, the clock, the order rows
 * arrive in. Motion is the deliberate exception to "relative": it is measured in g, and that is pinned
 * too. Twin of Swift `SleepStageFeaturesTests`.
 */
class SleepStageFeaturesTest {

    private val start = 1_700_000_017L         // deliberately off the 30 s grid
    private val duration = 2 * 3600L
    private val dev = "t"

    /** Gravity at 1 Hz from 40 min before the window to 40 min after: a still wrist with sensor noise, a
     *  turn every 23 minutes and a short burst of movement every 7. */
    private fun gravity(scale: Double = 1.0): List<GravitySample> {
        val rng = SplitMix(7)
        return ((start - 2400) until (start + duration + 2400)).map { t ->
            val k = t - start
            val side = if (floor(k.toDouble() / 1380) % 2.0 == 0.0) 1.0 else -1.0
            val burst = if ((k % 420) < 6) 0.2 else 0.0
            fun n(): Double = (rng.unit() - 0.5) * (0.002 + burst)
            GravitySample(dev, t, x = scale * (0.3 * side + n()), y = scale * (0.1 + n()), z = scale * (0.94 + n()))
        }
    }

    /** Heart rate at `every`-second cadence: a slow overnight fall, a ten-minute wave and a slower one. */
    private fun heartRate(every: Long = 1, offset: Int = 0): List<HrSample> =
        ((start - 2400) until (start + duration + 2400) step every).map { t ->
            val k = (t - start).toDouble()
            val bpm = 58 - 6 * k / duration.toDouble() + 4 * sin(k / 95) + 2 * sin(k / 1700)
            HrSample(dev, t, roundAway(bpm) + offset)
        }

    private fun intervals(): List<RrInterval> =
        ((start - 2400) until (start + duration + 2400)).map { t ->
            RrInterval(dev, t, 1000 + (45 * sin(2 * PI * t.toDouble() / 4.2)).toInt() + (t % 7).toInt() * 3)
        }

    /** Swift's `rounded()`: a half goes away from zero. */
    private fun roundAway(x: Double): Int = if (x < 0) -((-x).roundToInt()) else x.roundToInt()

    private fun column(rows: List<SleepStageFeatures.Row>, name: String): List<Double?> {
        val i = SleepStageFeatures.names.indexOf(name)
        return rows.map { it.values[i] }
    }

    private fun assertSame(
        a: List<SleepStageFeatures.Row>, b: List<SleepStageFeatures.Row>,
        accuracy: Double, skip: Set<String> = emptySet(),
    ) {
        assertEquals(a.size, b.size)
        for ((ra, rb) in a.zip(b)) {
            for ((i, name) in SleepStageFeatures.names.withIndex()) {
                if (name in skip) continue
                val x = ra.values[i]; val y = rb.values[i]
                if (x == null && y == null) continue
                if (x == null || y == null) fail("$name present in one and missing in the other at ${ra.start}")
                else assertEquals("$name at ${ra.start}", x, y, accuracy)
            }
        }
    }

    // ── shape ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun oneRowPerGridEpochAndOneValuePerName() {
        val rows = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(), intervals())
        val firstEpoch = ((start + 29) / 30) * 30
        assertEquals(firstEpoch, rows.first().start)
        assertEquals(((start + duration - firstEpoch + 29) / 30).toInt(), rows.size)
        for ((i, r) in rows.withIndex()) {
            assertEquals(firstEpoch + 30L * i, r.start)
            assertEquals(SleepStageFeatures.names.size, r.values.size)
            for (v in r.values) if (v != null) assertTrue(v.isFinite())
        }
        assertEquals(SleepStageFeatures.names.size, SleepStageFeatures.names.toSet().size)
    }

    @Test
    fun emptyAndInvertedWindowsYieldNothing() {
        assertTrue(SleepStageFeatures.rows(start, start, gravity(), heartRate(), emptyList()).isEmpty())
        assertTrue(SleepStageFeatures.rows(start, start - 60, emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun noStreamsStillGiveARowPerEpochWithOnlyTheClock() {
        val rows = SleepStageFeatures.rows(start, start + 3600, emptyList(), emptyList(), emptyList())
        assertFalse(rows.isEmpty())
        for (r in rows) {
            for ((i, name) in SleepStageFeatures.names.withIndex()) {
                if (name in listOf("minutes", "fraction", "minutes_left")) assertNotNull(r.values[i])
                else assertNull(name, r.values[i])
            }
        }
    }

    // ── beat intervals are optional ───────────────────────────────────────────────────────────────

    @Test
    fun withoutIntervalsOnlyTheIntervalFeaturesAreMissing() {
        val with = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(), intervals())
        val without = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(), emptyList())
        val rrNames = setOf("rr_rmssd_5m_z", "rr_sdnn_5m_z", "rr_resp_reg_z")
        assertSame(with, without, accuracy = 0.0, skip = rrNames)
        for (name in rrNames) {
            assertTrue(name, column(without, name).all { it == null })
            assertTrue(name, column(with, name).any { it != null })
        }
    }

    // ── portability ───────────────────────────────────────────────────────────────────────────────

    /** The sensor's noise at rest differs threefold between devices and must not read as movement:
     *  with the same movements, a still wrist five times noisier gives the same movement features. */
    @Test
    fun motionFeaturesIgnoreTheSensorsNoiseFloor() {
        fun night(noise: Double): List<GravitySample> {
            val rng = SplitMix(7)
            return ((start - 2400) until (start + duration + 2400)).map { t ->
                val k = t - start
                val side = if (floor(k.toDouble() / 1380) % 2.0 == 0.0) 1.0 else -1.0
                val burst = if ((k % 420) < 6) (if (k % 2 == 0L) 0.2 else -0.2) else 0.0   // far above the threshold
                fun n(): Double = (rng.unit() - 0.5) * noise
                GravitySample(dev, t, x = 0.3 * side + burst + n(), y = 0.1 + n(), z = 0.94 + n())
            }
        }
        val quiet = SleepStageFeatures.rows(start, start + duration, night(0.0008), heartRate(), emptyList())
        val noisy = SleepStageFeatures.rows(start, start + duration, night(0.004), heartRate(), emptyList())
        for (name in listOf("move_share", "move_prev_5m", "move_next_30m", "still_minutes", "still_ahead_minutes")) {
            for ((a, b) in column(quiet, name).zip(column(noisy, name))) {
                assertEquals(name, a ?: -1.0, b ?: -1.0, 1e-9)
            }
        }
        assertTrue(column(quiet, "move_share").any { (it ?: 0.0) > 0 })
    }

    /** Motion is in g: a stream that is not (here, one scaled as if decoded in another unit) reads as a
     *  different night, which is why a source must be brought to g before it gets here. */
    @Test
    fun motionIsMeasuredInG() {
        val a = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(), emptyList())
        val b = SleepStageFeatures.rows(start, start + duration, gravity(scale = 3.7), heartRate(), emptyList())
        val ja = column(a, "jerk_mean").filterNotNull(); val jb = column(b, "jerk_mean").filterNotNull()
        assertEquals(ja.size, jb.size)
        for ((x, y) in ja.zip(jb)) assertEquals(log10(3.7), y - x, 1e-6)
    }

    @Test
    fun heartRateFeaturesIgnoreTheRestingLevel() {
        val a = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(), emptyList())
        val b = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(offset = 17), emptyList())
        // A rank moves by a place or two where two bin means that tied exactly no longer do in floating
        // point; everything else is the same number.
        val ranks = setOf("hr_rank", "hr_sd_11m_rank", "hr_range_rank")
        assertSame(a, b, accuracy = 1e-9, skip = ranks)
        for (name in ranks) {
            for ((x, y) in column(a, name).zip(column(b, name))) assertEquals(name, x ?: -1.0, y ?: -1.0, 0.01)
        }
    }

    /** A source reporting every 5 s and one reporting every second describe the same night. The bins are
     *  10 s, so the two agree up to how a 10 s mean of whole-bpm samples differs between 2 and 10 of them. */
    @Test
    fun heartRateFeaturesBarelyMoveWithTheSourceCadence() {
        val a = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(), emptyList())
        val b = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(every = 5), emptyList())
        for (name in listOf("hr_z", "hr_z_30m", "hr_sd_5m_z", "hr_step_prev", "hr_step_next")) {
            val x = column(a, name).filterNotNull(); val y = column(b, name).filterNotNull()
            assertEquals(name, x.size, y.size)
            val gap = x.zip(y).sumOf { abs(it.first - it.second) } / x.size
            assertTrue("$name: mean absolute difference between cadences, in night units", gap < 0.25)
        }
    }

    @Test
    fun aShiftOfTheClockByWholeEpochsChangesNothing() {
        val a = SleepStageFeatures.rows(start, start + duration, gravity(), heartRate(), intervals())
        val by = 30L * 4321
        val b = SleepStageFeatures.rows(
            start + by, start + duration + by,
            gravity().map { it.copy(ts = it.ts + by) },
            heartRate().map { it.copy(ts = it.ts + by) },
            intervals().map { it.copy(ts = it.ts + by) })
        assertEquals(a.map { it.values }, b.map { it.values })
        assertEquals(a.map { it.start + by }, b.map { it.start })
    }

    @Test
    fun rowsOutsideTheReachAndTheirOrderDoNotMatter() {
        val g = gravity(); val h = heartRate(); val r = intervals()
        val a = SleepStageFeatures.rows(start, start + duration, g, h, r)
        val far = start - 86_400
        val b = SleepStageFeatures.rows(
            start, start + duration,
            (g + GravitySample(dev, far, 9.0, 9.0, 9.0)).reversed(),
            (h + HrSample(dev, far, 190)).reversed(),
            r + RrInterval(dev, far, 300))
        assertSame(a, b, accuracy = 1e-9)
    }

    // ── what the context features mean ────────────────────────────────────────────────────────────

    @Test
    fun stillMinutesCountsFromTheLastMovementIncludingBeforeTheWindow() {
        // Movement only in the minutes before the window opens, ending half a minute short of it (the
        // jerk between the last moving second and the first still one belongs to the later second).
        val rng = SplitMix(3)
        val g = ((start - 1800) until (start + 3600)).map { t ->
            val moving = t >= start - 300 && t < start - 30
            val n = (rng.unit() - 0.5) * (if (moving) 0.5 else 0.0005)
            GravitySample(dev, t, x = 0.2 + n, y = 0.1 - n, z = 0.95 + n)
        }
        val rows = SleepStageFeatures.rows(start, start + 3600, g, emptyList(), emptyList())
        val still = column(rows, "still_minutes").filterNotNull()
        assertEquals(rows.size, still.size)
        assertTrue("the window opens right after the movement", still[0] <= 1.5)
        assertTrue(still[0] > 0)
        for (i in 1 until still.size) assertEquals(0.5, still[i] - still[i - 1], 1e-9)
        val moved = column(rows, "move_share").withIndex().filter { it.value != 0.0 }
        assertTrue("epochs with movement: ${moved.take(5)}", moved.isEmpty())
    }
}

/** [SleepStageDecoder]: the model's probabilities go through V2's own smoothing, nothing else. Twin of
 *  Swift `SleepStageDecoderTests`. */
class SleepStageDecoderTest {

    private fun sure(stage: String, p: Double = 0.94): DoubleArray =
        SleepStageDecoder.stages.map { if (it == stage) p else (1 - p) / 3 }.toDoubleArray()

    @Test
    fun confidentEpochsComeBackUnchanged() {
        val truth = List(20) { "light" } + List(30) { "deep" } + List(10) { "light" } + List(25) { "rem" } +
            List(8) { "wake" } + List(12) { "light" }
        assertEquals(truth, SleepStageDecoder.decode(truth.map { sure(it) }))
    }

    @Test
    fun aLoneWeakEpochIsSmoothedAway() {
        val probs = MutableList(40) { sure("light") }
        probs[20] = doubleArrayOf(0.2, 0.35, 0.45, 0.0)   // deep by a nose, for one epoch
        assertEquals(List(40) { "light" }, SleepStageDecoder.decode(probs))
    }

    @Test
    fun missingValueStandsInForEveryUnmeasuredFeature() {
        val row = SleepStageFeatures.Row(start = 0, values = listOf(1.5, null, -2.0))
        assertEquals(listOf(1.5, SleepStageFeatures.MISSING, -2.0), SleepStageFeatures.modelInput(row))
    }

    /** A short awakening the model is fairly sure of: charged for wake's rarity twice it is smoothed
     *  away, charged once it survives. */
    @Test
    fun dividingByThePriorKeepsAShortAwakening() {
        val prior = listOf(0.05, 0.56, 0.19, 0.20)
        val probs = MutableList(60) { sure("light", 0.85) }
        for (i in 30 until 33) probs[i] = doubleArrayOf(0.55, 0.35, 0.02, 0.08)
        assertFalse(SleepStageDecoder.decode(probs).contains("wake"))
        val kept = SleepStageDecoder.decode(probs, prior = prior)
        assertEquals(listOf("wake", "wake", "wake"), kept.subList(30, 33))
        assertEquals(3, kept.count { it == "wake" })
    }

    /** At full smoothing the decoder IS V2's Viterbi: same emissions in, same path out, for any prior weight. */
    @Test
    fun fullSmoothingWalksThePathV2sViterbiWalks() {
        val rng = SplitMix(11)
        val probs = (0 until 400).map { i ->
            val lean = listOf((i / 37) % 4, (i / 11) % 4)
            val p = (0 until 4).map { k -> 0.05 + rng.unit() * 0.3 + (if (k in lean) 0.6 else 0.0) }
            val total = p.sum()
            p.map { it / total }.toDoubleArray()
        }
        val prior = listOf(0.05, 0.56, 0.19, 0.20)
        for (weight in listOf(0.0, 0.4, 1.0)) {
            val p0 = prior.map { it.pow(weight) }
            val emissions = probs.map { p ->
                mapOf("awake" to ln(p[0] / p0[0]), "light" to ln(p[1] / p0[1]),
                      "deep" to ln(p[2] / p0[2]), "rem" to ln(p[3] / p0[3]))
            }
            assertEquals("weight $weight",
                SleepStagerV2.viterbi(emissions).map { if (it == "awake") "wake" else it },
                SleepStageDecoder.decode(probs, prior = prior, weight = weight))
        }
    }

    /** With no smoothing every epoch stands alone: the path is each row's own best stage. */
    @Test
    fun noSmoothingIsEachEpochOnItsOwn() {
        val rng = SplitMix(5)
        val probs = (0 until 200).map {
            val p = (0 until 4).map { 0.01 + rng.unit() }
            val total = p.sum()
            p.map { it / total }.toDoubleArray()
        }
        val alone = probs.map { row -> SleepStageDecoder.stages[row.indices.maxByOrNull { row[it] }!!] }
        assertEquals(alone, SleepStageDecoder.decode(probs, smoothing = 0.0))
        assertNotEquals(alone, SleepStageDecoder.decode(probs, smoothing = 1.0))
    }

    @Test
    fun anEmptyNightDecodesToNothing() {
        assertTrue(SleepStageDecoder.decode(emptyList()).isEmpty())
    }
}
