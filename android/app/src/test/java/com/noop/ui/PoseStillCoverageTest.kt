package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #909 — every continuously-animating surface must consult [rememberPoseStill], so battery saver and
 * "Remove animations" both collapse it to its single posed frame.
 *
 * The Apple measurement behind this: an idle Today screen costs ~18% of a CPU core with the live gauges
 * running and 0.0% posed still, the same in Debug and Release because the per-frame work is canvas
 * drawing rather than arithmetic. A frame loop that forgets the gate keeps burning that in battery saver,
 * and nothing about the screen looks wrong — which is exactly why it needs a test rather than review.
 *
 * The census is `withFrameNanos` and `rememberInfiniteTransition`, the two ways this app runs an
 * unbounded frame loop. A file containing either must also read the gate. That is coarser than checking
 * each call site's control flow, deliberately: it cannot be fooled by a loop that is gated in a helper,
 * and it fails loudly the moment a NEW animating file appears ungated, which is the case worth catching.
 *
 * One-shot animations are out of scope on purpose: a tween that settles and stops is not the cost
 * battery saver is asking to avoid, and the Apple change this mirrors gated only its `TimelineView`s.
 * Those keep asking `rememberReduceMotion` alone.
 *
 * Fails rather than skips when it cannot find the source, for the reason
 * [com.noop.data.HrFrontierQueryShapeTest] documents: a guard whose absence reads as a pass is not a guard.
 */
class PoseStillCoverageTest {

    private fun uiDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rel = "src/main/java/com/noop/ui"
        val found = listOf(File(userDir, rel), File(userDir, "app/$rel"), File(userDir, "android/app/$rel"))
            .firstOrNull { it.isDirectory }
        assertNotNull(
            "com/noop/ui not found from user.dir=$userDir — this guard cannot run, and a skip reads as a " +
                "pass. Add this host's working dir to the list rather than letting it slide.",
            found,
        )
        return found!!
    }

    /** Source with `//` and block comments blanked, so prose about a frame loop is not a frame loop. */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    /**
     * Frame loops that are DIRECT MANIPULATION, not idle animation, and must keep running in battery saver.
     *
     * Empty today. Its one entry was the old Sleep screen's drag-reorder auto-scroll, a `withFrameNanos`
     * loop that ran only while the user's finger was down; that screen is gone and no file runs such a loop
     * now. Quieting one would not save idle power (there is no idle: the user is dragging); it would break
     * the interaction by stripping the auto-scroll out from under them.
     *
     * The distinction this file draws is idle-vs-driven, not animated-vs-still. Anything added here needs a
     * reason of that shape.
     */
    private val directManipulation = emptySet<String>()

    private fun animatingFiles(): List<File> =
        uiDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in directManipulation }
            .filter { f ->
                val code = stripComments(f.readText())
                code.contains("withFrameNanos") || code.contains("rememberInfiniteTransition")
            }
            .toList()

    @Test
    fun everyContinuouslyAnimatingFileConsultsThePoseStillGate() {
        val files = animatingFiles()
        assertTrue(
            "no frame-loop sites found at all — the census is looking in the wrong place",
            files.isNotEmpty(),
        )
        val ungated = files.filterNot { stripComments(it.readText()).contains("rememberPoseStill()") }
        assertEquals(
            "these run an unbounded frame loop without consulting rememberPoseStill(), so battery saver " +
                "will not quiet them: ${ungated.map { it.name }}",
            emptyList<String>(),
            ungated.map { it.name },
        )
    }

    /**
     * The gate combines the two live system signals. Losing one is silent, so pin their exact census here.
     * The in-app "Reduce motion in NOOP" switch is retired (iOS ST-4): the system setting applies, so a
     * stored value must not keep a screen still with no switch left to undo it.
     */
    @Test
    fun poseStillGateCombinesTheLiveSystemSignals() {
        val motion = File(uiDir(), "NoopMotion.kt")
        assertTrue("NoopMotion.kt missing", motion.isFile)
        val code = stripComments(motion.readText()).replace(Regex("\\s+"), " ")
        assertTrue(
            "rememberPoseStill must OR system motion and battery saver: $code",
            code.contains("fun rememberPoseStill(): Boolean = rememberReduceMotion() || rememberPowerSaveMode()"),
        )
        assertTrue(
            "battery saver must be read from PowerManager.isPowerSaveMode",
            code.contains("isPowerSaveMode"),
        )
        assertTrue(
            "and kept live — a read-once value would strand the screen animating after the user flips it",
            code.contains("ACTION_POWER_SAVE_MODE_CHANGED"),
        )
        assertFalse(
            "the retired in-app preference must not be read any more",
            code.contains("KEY_QUIET_MOTION"),
        )
    }
}
