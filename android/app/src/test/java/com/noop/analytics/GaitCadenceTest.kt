package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GaitCadence], case for case the Swift `GaitCadenceTests`: the same signals ([GaitTestSignals]), the
 * same recordings ([GaitFixtures]), the same expected values and tolerances.
 * `GaitCadenceParityOracleTest` is the check against the Swift build's own numbers.
 */
class GaitCadenceTest {

    private fun stepHz(s: GaitFixtures.Axes, sampleRate: Double = 100.0): Double =
        GaitCadence.estimate(s.x, s.y, s.z, sampleRate = sampleRate)?.stepHz ?: 0.0

    @Test
    fun recoversSeveralInjectedCadences() {
        // More than one value, so a peak the method manufactures by itself would not pass.
        for (stepHz in listOf(1.20, 1.55, 1.90, 2.35, 2.90)) {
            assertEquals("stepHz $stepHz", stepHz, stepHz(GaitTestSignals.gait(stepHz = stepHz)), 0.02)
        }
    }

    @Test
    fun aStrongStepHarmonicDoesNotMoveTheAnswerAnOctaveUp() {
        // The hand-in-pocket shape: the step's second harmonic nearly as tall as the step line.
        val s = GaitTestSignals.gait(stepHz = 1.3, armG = 0.12, stepG = 0.2, harmonicG = 0.15)
        assertEquals(1.3, stepHz(s), 0.02)
    }

    @Test
    fun withoutAStrideLineTheAnswerIsAnOctaveHigh() {
        // No arm swing at all: only the step line and its harmonic remain, which is the same picture as
        // stride and step an octave up. The detector does not guess; StepCalibration's ratio band is
        // what rejects this reading (see StepCalibrationTest).
        assertEquals(2.6, stepHz(GaitTestSignals.noStrideLine()), 0.03)
    }

    @Test
    fun noGaitIsNull() {
        val still = GaitTestSignals.gait(stepHz = 1.5, armG = 0.0, stepG = 0.0, noiseG = 0.005)
        assertNull(GaitCadence.estimate(still.x, still.y, still.z, sampleRate = 100.0))

        val fidget = GaitTestSignals.fidget()
        assertNull(GaitCadence.estimate(fidget.x, fidget.y, fidget.z, sampleRate = 100.0))
    }

    @Test
    fun aShortWindowIsNull() {
        val s = GaitTestSignals.gait(stepHz = 1.6, seconds = 12.0)
        assertNull(GaitCadence.estimate(s.x, s.y, s.z, sampleRate = 100.0))
    }

    // MARK: - Real recordings

    @Test
    fun armSwingingRecording() {
        // The wearer counted 100 steps over the whole stretch this window is cut from, which took
        // between 75 and 80 seconds: 1.25 to 1.33 steps per second.
        val s = GaitFixtures.axes(GaitFixtures.ARM_SWINGING)
        assertEquals(1.34, stepHz(s, GaitFixtures.SAMPLE_RATE), 0.02)
    }

    @Test
    fun handInPocketRecording() {
        // Same wearer, same pace, strap hand in a pocket: again 100 counted steps in about 80 seconds.
        // A time-domain peak count over this window reads 69 to 75 steps where the line gives 63.
        val s = GaitFixtures.axes(GaitFixtures.HAND_IN_POCKET)
        assertEquals(1.31, stepHz(s, GaitFixtures.SAMPLE_RATE), 0.02)
    }

    @Test
    fun walkRecording() {
        val s = GaitFixtures.axes(GaitFixtures.WALK)
        assertEquals(1.50, stepHz(s, GaitFixtures.SAMPLE_RATE), 0.02)
    }
}
