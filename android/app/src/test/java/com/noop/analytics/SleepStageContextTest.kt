package com.noop.analytics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * [SleepStageContext] puts a stretch of the night into one row. What these pin is that each column is
 * the window its name says, that a window stops at the night's edge instead of inventing a value, and
 * that a missing measurement is left out of a mean and never read as a number. Twin of Swift
 * `SleepStageContextTests`.
 */
class SleepStageContextTest {

    private val c = SleepStageContext

    /** A night of feature rows where every feature is `value(epoch, column)`. */
    private fun night(n: Int, value: (Int, Int) -> Double?): List<List<Double?>> =
        (0 until n).map { i -> SleepStageFeatures.names.indices.map { value(i, it) } }

    private fun column(name: String): Int = c.names.indexOf(name).also { assertTrue(name, it >= 0) }

    /** The mean of the present values at `lo..hi`, clipped to the column, the slow way. */
    private fun mean(x: List<Double?>, lo: Int, hi: Int): Double? {
        val p = (max(0, lo)..min(x.size - 1, hi)).mapNotNull { x[it] }
        return if (p.isEmpty()) null else p.sum() / p.size
    }

    private fun assertClose(a: Double?, b: Double?, what: String) {
        if (a == null && b == null) return
        if (a == null || b == null) fail("$what: $a against $b") else assertEquals(what, b, a, 1e-9)
    }

    @Test
    fun oneValuePerNameAndNoNameTwice() {
        val out = c.surroundingsOfValues(night(50) { i, col -> (i + col).toDouble() })
        assertEquals(50, out.size)
        assertTrue(out.all { it.size == c.names.size })
        assertEquals(c.sources.size * 10, c.names.size)
        assertEquals(c.secondNames.size, c.secondNames.toSet().size)
        assertEquals(SleepStageFeatures.names.size + c.names.size, c.firstNames.size)
        assertEquals(c.firstNames.size + 4 * 15, c.secondNames.size)
        assertTrue(c.sources.all { it in SleepStageFeatures.names })
        // The widths the shipped trees were fit on.
        assertEquals(186, c.firstNames.size)
        assertEquals(246, c.secondNames.size)
    }

    @Test
    fun anEmptyNightHasNoSurroundings() {
        assertTrue(c.surroundingsOfValues(emptyList()).isEmpty())
        assertTrue(c.neighbours(emptyList()).isEmpty())
    }

    /** A feature that never changes is its own mean over any window, and stands nowhere from it. */
    @Test
    fun aFlatNightIsItsOwnSurroundings() {
        val out = c.surroundingsOfValues(night(400) { _, col -> col + 0.25 })
        val src = SleepStageFeatures.names.indexOf("hr_z")
        for (i in listOf(0, 1, 7, 200, 398, 399)) {
            for (name in listOf("hr_z_around_21", "hr_z_around_61", "hr_z_around_181")) {
                assertClose(out[i][column(name)], src + 0.25, "$name at $i")
            }
            assertClose(out[i][column("hr_z_vs_181")], 0.0, "vs at $i")
        }
    }

    /** Every column against the window its name says, on a night with holes in it. */
    @Test
    fun eachColumnIsTheWindowItsNameSays() {
        val rng = SplitMix(3)
        val src = SleepStageFeatures.names.indexOf("move_share")
        val x: List<Double?> = (0 until 500).map { i -> if (i % 17 == 3 || i in 120 until 150) null else rng.unit() }
        val out = c.surroundingsOfValues(night(500) { i, col -> if (col == src) x[i] else 0.0 })
        for (i in listOf(0, 1, 2, 5, 6, 10, 30, 90, 119, 135, 149, 250, 409, 480, 493, 494, 498, 499)) {
            assertClose(out[i][column("move_share_around_21")], mean(x, i - 10, i + 10), "around 21 at $i")
            assertClose(out[i][column("move_share_around_61")], mean(x, i - 30, i + 30), "around 61 at $i")
            assertClose(out[i][column("move_share_around_181")], mean(x, i - 90, i + 90), "around 181 at $i")
            assertClose(out[i][column("move_share_before_20")], if (i == 0) null else mean(x, i - 20, i - 1), "before at $i")
            assertClose(out[i][column("move_share_after_20")], if (i == 499) null else mean(x, i + 1, i + 20), "after at $i")
            assertClose(out[i][column("move_share_back_2")], if (i >= 2) x[i - 2] else null, "back 2 at $i")
            assertClose(out[i][column("move_share_ahead_2")], if (i + 2 < 500) x[i + 2] else null, "ahead 2 at $i")
            assertClose(out[i][column("move_share_back_6")], if (i >= 6) x[i - 6] else null, "back 6 at $i")
            assertClose(out[i][column("move_share_ahead_6")], if (i + 6 < 500) x[i + 6] else null, "ahead 6 at $i")
            val wide = mean(x, i - 90, i + 90)
            val v = x[i]
            assertClose(out[i][column("move_share_vs_181")], if (v != null && wide != null) v - wide else null, "vs at $i")
        }
    }

    /** A source without the measurement (a wrist with no beat intervals) has no surroundings either,
     *  and takes nothing away from the other sources. */
    @Test
    fun aMissingSourceIsMissingEverywhereAndAloneInIt() {
        val rr = SleepStageFeatures.names.indices.filter { SleepStageFeatures.names[it].startsWith("rr_") }
        val full = c.surroundingsOfValues(night(300) { i, col -> sin((i * (col + 1)).toDouble() / 40) })
        val bare = c.surroundingsOfValues(night(300) { i, col -> if (col in rr) null else sin((i * (col + 1)).toDouble() / 40) })
        for ((k, name) in c.names.withIndex()) {
            if (name.startsWith("rr_")) {
                assertTrue(name, bare.all { it[k] == null })
            } else {
                assertEquals(name, full.map { it[k] }, bare.map { it[k] })
            }
        }
    }

    /** The window is cut at the night's edge: the first epoch has nothing before it, the last nothing after. */
    @Test
    fun nothingIsReadFromOutsideTheNight() {
        val out = c.surroundingsOfValues(night(100) { i, _ -> i.toDouble() })
        assertNull(out[0][column("hr_z_before_20")])
        assertNull(out[99][column("hr_z_after_20")])
        assertClose(out[0][column("hr_z_around_21")], 5.0, "the first epoch's centred window holds epochs 0...10")
        assertClose(out[99][column("hr_z_around_21")], 94.0, "the last one's holds 89...99")
    }

    /** The two models' inputs are the named columns in the named order, with the stand-in for a missing value. */
    @Test
    fun modelInputsAreTheNamedColumnsInOrder() {
        val values = night(40) { i, col -> if (col == 0) null else i + col.toDouble() / 100 }
        val first = c.firstInputOfValues(values)
        assertTrue(first.all { it.size == c.firstNames.size })
        assertEquals(SleepStageFeatures.MISSING, first[7][0], 0.0)
        assertEquals(7.03, first[7][3], 0.0)
        val around = c.firstNames.indexOf("hr_z_around_21")
        val hr = SleepStageFeatures.names.indexOf("hr_z")
        assertEquals(20 + hr.toDouble() / 100, first[20][around], 1e-9)
        assertEquals(SleepStageFeatures.MISSING, first[0][c.firstNames.indexOf("hr_z_before_20")], 0.0)

        val p = (0 until 40).map { i -> if (i < 20) doubleArrayOf(0.7, 0.1, 0.1, 0.1) else doubleArrayOf(0.1, 0.1, 0.1, 0.7) }
        val second = c.secondInput(first, p)
        assertTrue(second.all { it.size == c.secondNames.size })
        assertArrayEquals(first[5], second[5].copyOf(first[5].size), 0.0)
        assertEquals(0.7, second[20][c.secondNames.indexOf("p_wake_back_1")], 0.0)
        assertEquals(0.7, second[20][c.secondNames.indexOf("p_rem_ahead_1")], 0.0)
        assertEquals(SleepStageFeatures.MISSING, second[0][c.secondNames.indexOf("p_wake_back_1")], 0.0)
    }

    // ── neighbours ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun neighboursAreTheEpochsTheirNamesSay() {
        val rng = SplitMix(9)
        val p = (0 until 300).map {
            val v = (0 until 4).map { 0.01 + rng.unit() }
            val total = v.sum()
            v.map { it / total }.toDoubleArray()
        }
        val out = c.neighbours(p)
        assertTrue(out.all { it.size == c.neighbourNames.size })
        fun col(name: String): Int = c.neighbourNames.indexOf(name).also { assertTrue(name, it >= 0) }
        for ((ci, stage) in SleepStageDecoder.stages.withIndex()) {
            val x: List<Double?> = p.map { it[ci] }
            for (i in listOf(0, 1, 3, 4, 7, 8, 150, 291, 292, 298, 299)) {
                for (d in listOf(1, 2, 4, 8)) {
                    assertClose(out[i][col("p_${stage}_back_$d")], if (i >= d) x[i - d] else null, "$stage back $d at $i")
                    assertClose(out[i][col("p_${stage}_ahead_$d")], if (i + d < 300) x[i + d] else null, "$stage ahead $d at $i")
                }
                for (w in listOf(5, 11, 21, 41, 81)) {
                    assertClose(out[i][col("p_${stage}_around_$w")], mean(x, i - w / 2, i + w / 2), "$stage around $w at $i")
                }
                assertClose(out[i][col("p_${stage}_so_far")], if (i == 0) null else mean(x, 0, i - 1), "$stage so far at $i")
                assertClose(out[i][col("p_${stage}_to_come")], if (i == 299) null else mean(x, i + 1, 299), "$stage to come at $i")
            }
        }
    }

    /** Probabilities sum to one in every row, so their window means do too. */
    @Test
    fun neighbourMeansStillSumToOne() {
        val p = (0 until 120).map { i ->
            if (i % 2 == 0) doubleArrayOf(0.1, 0.2, 0.3, 0.4) else doubleArrayOf(0.7, 0.1, 0.15, 0.05)
        }
        val out = c.neighbours(p)
        val cols = SleepStageDecoder.stages.map { c.neighbourNames.indexOf("p_${it}_around_21") }
        for (i in listOf(0, 60, 119)) {
            assertEquals(1.0, cols.sumOf { out[i][it] ?: 0.0 }, 1e-12)
        }
    }
}
