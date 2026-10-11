package com.noop.analytics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * The learned stage model as Android runs it: the trees read from text. Twin of the reader cases of
 * Swift `SleepStageTreesTests`. The reader takes a well-formed file and nothing else; the whole night
 * against the committed oracle is [SleepStageOracleTest].
 */
class SleepStageTreesTest {

    /** Two columns, two classes, two trees: a branch with two leaves, and a lone leaf. */
    private val small = listOf(
        "renoop-sleep-stage-trees 1",
        "# a comment",
        "source Small.mlmodel sha256 00ff",
        "classes b a",
        "transform softmax",
        "base 0.5 0.5",
        "meta note two words",
        "columns 2",
        "x",
        "y",
        "trees 2",
        "tree 3",
        "b 1 2.5 1 2",
        "l 0 1.0",
        "l 1 -1.0",
        "tree 1",
        "l 0 0.25",
        "",
    ).joinToString("\n")

    private fun trees(): SleepStageTrees {
        val t = SleepStageTrees.parse(small)
        assertNotNull(t)
        return t!!
    }

    @Test
    fun aFileIsReadAsItsColumnsClassesAndSettings() {
        val trees = trees()
        assertEquals(listOf("x", "y"), trees.columns)
        assertEquals(listOf("b", "a"), trees.classes)
        assertEquals(mapOf("note" to "two words"), trees.meta)
        assertEquals("00ff", trees.source)
        assertEquals(2, trees.treeCount)
    }

    @Test
    fun aRowWalksEachTreeAndTheSumsBecomeProbabilities() {
        val trees = trees()
        // y below 2.5: class b gets 0.5 + 1.0 + 0.25, class a stays at 0.5.
        val below = trees.probabilities(doubleArrayOf(9.0, 2.4))
        assertEquals(1 / (1 + exp(0.5 - 1.75)), below[0], 1e-15)
        assertEquals(1.0, below[0] + below[1], 1e-15)
        // y not below it (the threshold itself is not below): b gets 0.5 + 0.25, a gets 0.5 - 1.0.
        val at = trees.probabilities(doubleArrayOf(9.0, 2.5))
        assertEquals(1 / (1 + exp(-0.5 - 0.75)), at[0], 1e-15)
        assertArrayEquals(at, trees.probabilities(doubleArrayOf(9.0, 7.0)), 0.0)
        // The missing-value mark is an ordinary number, and it is below every threshold.
        assertArrayEquals(below, trees.probabilities(doubleArrayOf(9.0, SleepStageFeatures.MISSING)), 0.0)
    }

    @Test
    fun probabilitiesAreHandedOverInTheOrderAsked() {
        val trees = trees()
        val p = trees.probabilities(doubleArrayOf(0.0, 0.0))
        val swapped = trees.probabilities(listOf(doubleArrayOf(0.0, 0.0)), order = listOf("a", "b"))
        assertNotNull(swapped)
        assertEquals(1, swapped!!.size)
        assertArrayEquals(doubleArrayOf(p[1], p[0]), swapped[0], 0.0)
        assertNull("a class the model does not have",
            trees.probabilities(listOf(doubleArrayOf(0.0, 0.0)), order = listOf("a", "c")))
        assertNull("a row of the wrong width",
            trees.probabilities(listOf(doubleArrayOf(0.0)), order = listOf("a", "b")))
    }

    @Test
    fun anythingMalformedIsRefused() {
        fun changed(from: String, to: String): String {
            assertTrue("the case must change something: $from", small.contains(from))
            return small.replace(from, to)
        }
        assertNull(SleepStageTrees.parse(""))
        assertNull("another format version", SleepStageTrees.parse(changed("trees 1\n# a", "trees 2\n# a")))
        assertNull(SleepStageTrees.parse(changed("transform softmax", "transform logistic")))
        assertNull("a base per class", SleepStageTrees.parse(changed("base 0.5 0.5", "base 0.5")))
        assertNull("a column that is not there", SleepStageTrees.parse(changed("b 1 2.5 1 2", "b 2 2.5 1 2")))
        assertNull("a branch back to itself", SleepStageTrees.parse(changed("b 1 2.5 1 2", "b 1 2.5 0 2")))
        assertNull("a branch out of its tree", SleepStageTrees.parse(changed("b 1 2.5 1 2", "b 1 2.5 1 3")))
        assertNull("a class that is not there", SleepStageTrees.parse(changed("l 1 -1.0", "l 2 -1.0")))
        assertNull(SleepStageTrees.parse(changed("l 1 -1.0", "l 1 nan")))
        assertNull("fewer nodes than promised", SleepStageTrees.parse(changed("tree 1\n", "tree 2\n")))
        assertNull("lines left over", SleepStageTrees.parse(small + "l 0 1.0\n"))
        assertNull("fewer trees than promised", SleepStageTrees.parse(changed("trees 2\ntree", "trees 3\ntree")))
        // Forms Java's own number parser would take and the Swift reader does not.
        assertNull(SleepStageTrees.parse(changed("l 1 -1.0", "l 1 NaN")))
        assertNull(SleepStageTrees.parse(changed("l 1 -1.0", "l 1 -1.0f")))
        assertNull(SleepStageTrees.parse(changed("l 1 -1.0", "l 1 Infinity")))
    }

    @Test
    fun theShippedFilesTakeThisBuildsColumnsAndCarryTheDecoderSettings() {
        val first = SleepStageTestFiles.shipped("SleepStageFirst")
        val second = SleepStageTestFiles.shipped("SleepStageSecond")
        assertEquals(SleepStageContext.firstNames, first.columns)
        assertEquals(SleepStageContext.secondNames, second.columns)
        assertEquals(SleepStageDecoder.stages.toSet(), first.classes.toSet())
        assertEquals(SleepStageDecoder.stages.toSet(), second.classes.toSet())
        assertEquals(64, first.source.length)
        assertEquals(64, second.source.length)
        assertEquals(1200, first.treeCount)
        assertEquals(1200, second.treeCount)
        val model = SleepStageModel.fromTrees(first, second, version = "trees")
        assertNotNull(model)
        assertEquals(4, model!!.prior?.size)
        assertEquals(1.0, model.prior!!.sum(), 1e-3)
        // The settings as the work list states them, in the order wake, light, deep, rem.
        assertEquals(listOf(0.048777, 0.556483, 0.190340, 0.204401), model.prior)
        assertEquals(0.15, model.weight, 0.0)
        assertEquals(0.0, model.smoothing, 0.0)
        // A pair handed over the wrong way round takes the wrong columns and is refused.
        assertNull(SleepStageModel.fromTrees(second, first, version = "trees"))
    }

    /** The attribution travels with the weights: both datasets are named in each file's comment lines. */
    @Test
    fun theShippedFilesNameTheDataTheyWereFitTo() {
        for (name in listOf("SleepStageFirst", "SleepStageSecond")) {
            val comments = SleepStageTestFiles.text(name).lineSequence().take(12).filter { it.startsWith("#") }.joinToString(" ")
            assertTrue("$name names Wearanize+", comments.contains("Wearanize+"))
            assertTrue("$name names sleep-accel", comments.contains("sleep-accel"))
            assertTrue("$name names CC BY 4.0", comments.contains("CC BY 4.0"))
            assertTrue("$name names ODC-By 1.0", comments.contains("ODC-By 1.0"))
        }
    }
}
