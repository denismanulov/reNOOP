package com.noop.ble

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepAutoCalibrator.isWalkingNow] against the Swift function's own answers, by ORACLE and not by eye:
 * `step_walking_now_oracle.txt` is the verbatim stdout of the Swift twin compiled standalone over the
 * cases of `StrandTests/StepAutoCalibratorTests.swift`, each limit of the rule (freshness, the 45 s
 * window and its 3 s of slack, the 2 s pause, the steady rate over one-second and longer gaps, the
 * 1 tick a second floor, a counter that wraps) and sixty invented histories.
 *
 * The oracle only guards this direction; `StepAutoCalibratorTests` on the Swift side is what stops
 * Swift drifting away from Kotlin.
 */
class StepWalkingNowOracleTest {

    private class Case(val name: String, val now: Long, val history: List<StepSample>, val answer: Boolean)

    private fun cases(): List<Case> {
        val stream = javaClass.classLoader!!.getResourceAsStream("step_walking_now_oracle.txt")
        assertNotNull("step_walking_now_oracle.txt is not on the test classpath", stream)
        return stream!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val f = line.split("|")
            assertEquals(line, 7, f.size)
            var ts = f[3].toLong(); var counter = f[4].toInt()
            val history = ArrayList<StepSample>()
            history.add(StepSample("test", ts, counter))
            for (step in f[5].split(" ").filter { it.isNotEmpty() }) {
                val (dt, ticks) = step.split(":").map { it.toInt() }
                ts += dt; counter = (counter + ticks) and 0xFFFF
                history.add(StepSample("test", ts, counter))
            }
            assertTrue(line, f[2] == "sorted" || f[2] == "reversed")
            Case(f[0], f[1].toLong(), if (f[2] == "reversed") history.reversed() else history, f[6].toBooleanStrict())
        }
    }

    @Test
    fun everyCaseAnswersAsTheSwiftFunctionDoes() {
        val cases = cases()
        assertEquals(100, cases.size)
        // An oracle of one answer would pin nothing.
        assertTrue(cases.count { it.answer } >= 30 && cases.count { !it.answer } >= 30)
        val wrong = cases.filter { StepAutoCalibrator.isWalkingNow(it.history, it.now) != it.answer }.map { it.name }
        assertEquals("cases Kotlin answers differently from Swift", emptyList<String>(), wrong)
    }
}
