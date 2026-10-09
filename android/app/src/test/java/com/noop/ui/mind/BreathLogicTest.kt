package com.noop.ui.mind

import com.noop.R
import com.noop.analytics.BreathPhase
import com.noop.analytics.BreathProtocolCatalog
import com.noop.analytics.BreathProtocolMode
import com.noop.analytics.HrDownPacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/**
 * The pure pieces behind Mindfulness: the session length a protocol recommends, the clock, the RMSSD a
 * session shows, the stages a pace walks, and Calm's gate (CR-8) and honest outcome. Mirrors the Swift
 * LiveBreathHelpersTests cases where the two share a helper.
 */
class BreathLogicTest {

    @Test fun recommendedLength() {
        val cases = listOf(
            0 to BreathLength.Five, 5 * 60_000 to BreathLength.Five, 7 * 60_000 - 1 to BreathLength.Five,
            7 * 60_000 to BreathLength.Ten, 10 * 60_000 to BreathLength.Ten,
            12 * 60_000 to BreathLength.Fifteen, 20 * 60_000 to BreathLength.Fifteen,
        )
        for ((ms, want) in cases) assertEquals("$ms", want, BreathLength.fromRecommended(ms))
        assertNull(BreathLength.Open.targetSeconds)
        assertEquals(900, BreathLength.Fifteen.targetSeconds)
    }

    @Test fun clock() {
        assertEquals("0:00", breathClock(0))
        assertEquals("1:05", breathClock(65))
        assertEquals("10:00", breathClock(600))
    }

    @Test fun rmssd() {
        assertNull(breathRmssd(emptyList()))
        assertNull(breathRmssd(listOf(800)))
        assertEquals(0.0, breathRmssd(listOf(800, 800))!!, 1e-9)
        assertEquals(sqrt((10.0 * 10 + 20.0 * 20) / 2), breathRmssd(listOf(800, 810, 790))!!, 1e-9)
    }

    @Test fun paceTextFollowsTheLocale() {
        assertEquals("5.5", breathPaceText(5.5, Locale.US))
        assertEquals("5,5", breathPaceText(5.5, Locale.forLanguageTag("ru")))
    }

    @Test fun resonancePaceIsFortySixtyInhaleToExhale() {
        val stages = resonanceStages(6.0)
        assertEquals(listOf(BreathPhase.INHALE, BreathPhase.EXHALE), stages.map { it.type })
        assertEquals(4_000, stages[0].durationMs)
        assertEquals(6_000, stages[1].durationMs)
        // No locked pace: the coherence fallback (5.5 br/min).
        val fallback = pacedPlan(BreathPace.Resonance, lockedBpm = null)
        assertFalse(fallback.guided)
        assertEquals(10_909, fallback.stages.sumOf { it.durationMs })
    }

    @Test fun catalogPacesDropZeroLengthStagesAndFlagGuidedOnes() {
        for (proto in BreathProtocolCatalog.pickerProtocols) {
            val plan = pacedPlan(BreathPace.Catalog(proto.id), lockedBpm = null)
            assertEquals(proto.id, proto.mode == BreathProtocolMode.GUIDED, plan.guided)
            assertTrue(proto.id, plan.stages.all { it.durationMs > 0 })
        }
        val unknown = pacedPlan(BreathPace.Catalog("nope"), lockedBpm = null)
        assertTrue(unknown.stages.isEmpty())
        assertFalse(unknown.guided)
    }

    @Test fun phaseWords() {
        assertEquals(R.string.mind_breathe_in, breathPhaseRes(BreathPhase.INHALE))
        assertEquals(R.string.mind_hold, breathPhaseRes(BreathPhase.HOLD))
        assertEquals(R.string.mind_breathe_out, breathPhaseRes(BreathPhase.EXHALE))
        assertEquals(R.string.mind_follow_cue, breathPhaseRes(BreathPhase.TEXT_ONLY))
    }

    @Test fun calmNeedsAStrapThenHapticsThenARestingPulse() {
        assertEquals(CalmGate.NeedsStrap, calmGate(canBuzz = false, breathingHaptics = false, bpm = null))
        // CR-8: with breathing haptics off Calm is unavailable rather than three silent minutes.
        assertEquals(CalmGate.NeedsHaptics, calmGate(canBuzz = true, breathingHaptics = false, bpm = 70))
        assertEquals(CalmGate.WaitingHeartRate, calmGate(canBuzz = true, breathingHaptics = true, bpm = null))
        assertEquals(CalmGate.WaitingHeartRate, calmGate(canBuzz = true, breathingHaptics = true, bpm = 54))
        assertEquals(CalmGate.WaitingHeartRate, calmGate(canBuzz = true, breathingHaptics = true, bpm = 121))
        assertEquals(CalmGate.Ready, calmGate(canBuzz = true, breathingHaptics = true, bpm = 55))
        assertEquals(CalmGate.Ready, calmGate(canBuzz = true, breathingHaptics = true, bpm = 120))
        assertEquals(R.string.mind_calm_needs_haptics, CalmGate.NeedsHaptics.detailRes)
    }

    @Test fun calmOutcomeIsHonest() {
        assertEquals(CalmOutcome.Settled(78, 69, 150), calmOutcome(HrDownPacer.StopReason.SETTLED, 78, 69, 150))
        assertEquals(CalmOutcome.Settled(null, null, 150), calmOutcome(HrDownPacer.StopReason.SETTLED, null, 69, 150))
        assertEquals(CalmOutcome.Eased(78, 74, 180), calmOutcome(HrDownPacer.StopReason.TIMEOUT, 78, 74, 180))
        assertEquals(CalmOutcome.Steady(78, 78), calmOutcome(HrDownPacer.StopReason.TIMEOUT, 78, 78, 180))
        assertEquals(CalmOutcome.Steady(78, 80), calmOutcome(null, 78, 80, 40))
        assertEquals(CalmOutcome.Ended, calmOutcome(HrDownPacer.StopReason.INVALID_HR, 78, null, 12))
    }
}
