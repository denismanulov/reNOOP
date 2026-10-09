package com.noop.ui.insights

import com.noop.R
import com.noop.analytics.DosedBehavior
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Moves You's shaping and wording: association only, no coefficients, no causal sentences (WM-2). */
class WhatMovesYouLogicTest {

    @Test fun pearsonNeedsThreePairsAndVariance() {
        assertNull(WhatMovesYou.pearson(listOf(1.0 to 2.0, 2.0 to 3.0)))
        assertNull(WhatMovesYou.pearson(listOf(1.0 to 5.0, 2.0 to 5.0, 3.0 to 5.0)))
        val (r, n) = WhatMovesYou.pearson(listOf(1.0 to 2.0, 2.0 to 4.0, 3.0 to 6.0))!!
        assertEquals(1.0, r, 1e-9)
        assertEquals(3, n)
    }

    @Test fun alignByDayInnerJoinsSortedByDay() {
        val a = listOf("2026-09-03" to 3.0, "2026-09-01" to 1.0, "2026-09-02" to 2.0)
        val b = listOf("2026-09-02" to 20.0, "2026-09-03" to 30.0, "2026-09-04" to 40.0)
        assertEquals(listOf(2.0 to 20.0, 3.0 to 30.0), WhatMovesYou.alignByDay(a, b))
    }

    @Test fun relationshipsNeedOverlappingSeries() {
        val days = (1..5).map { "2026-09-0$it" }
        val byKey = mapOf(
            "recovery" to days.mapIndexed { i, d -> d to 40.0 + i * 5 }.toMap(),
            "hrv" to days.mapIndexed { i, d -> d to 50.0 + i * 3 + (i % 2) }.toMap(),
        )
        val rels = WhatMovesYou.relationships(byKey)
        assertEquals(listOf("hrv-rec", "rec-lag"), rels.map { it.id })
        assertTrue(rels.first().r > 0.9)
    }

    @Test fun moodLinesNeedSevenDaysAndAModerateLink() {
        val days = (1..9).map { i ->
            DailyMetric(deviceId = "d", day = "2026-09-0$i", avgHrv = 40.0 + i * 2, recovery = 50.0 + (i % 3), totalSleepMin = null)
        }
        val fewMoods = (1..6).map { "2026-09-0$it" to it.toDouble() }
        assertTrue(WhatMovesYou.moodLines(days, fewMoods).isEmpty())
        val moods = (1..9).map { "2026-09-0$it" to (1.0 + it / 2) }
        val lines = WhatMovesYou.moodLines(days, moods)
        assertEquals("mind-hrv", lines.first().id)
        assertTrue(lines.all { kotlin.math.abs(it.r) >= WhatMovesYou.MOOD_MIN_ABS_R })
        assertTrue(lines.size <= 3)
    }

    @Test fun effectSentencesFollowDirectionAndLag() {
        assertEquals(R.string.wmy_effect_none, WhatMovesYou.effectSentenceRes(0.0, 1))
        assertEquals(R.string.wmy_effect_lower_same, WhatMovesYou.effectSentenceRes(-2.0, 0))
        assertEquals(R.string.wmy_effect_higher_next, WhatMovesYou.effectSentenceRes(2.0, 1))
        assertEquals(R.string.wmy_effect_lower_later, WhatMovesYou.effectSentenceRes(-0.5, 2))
    }

    @Test fun relationshipWordsByStrengthAndSign() {
        assertEquals(R.string.wmy_link_none, WhatMovesYou.relationshipSentenceRes(0.05))
        assertEquals(R.string.wmy_link_weak_down, WhatMovesYou.relationshipSentenceRes(-0.2))
        assertEquals(R.string.wmy_link_moderate_up, WhatMovesYou.relationshipSentenceRes(0.4))
        assertEquals(R.string.wmy_link_strong_down, WhatMovesYou.relationshipSentenceRes(-0.6))
        assertEquals(R.string.wmy_link_very_strong_up, WhatMovesYou.relationshipSentenceRes(0.9))
    }

    @Test fun doseSentenceNeverQuantifiesACause() {
        assertEquals(R.string.wmy_dose_flat, WhatMovesYou.doseSentenceRes(DosedBehavior.ALCOHOL, 0.04))
        assertEquals(R.string.wmy_dose_alcohol_lower, WhatMovesYou.doseSentenceRes(DosedBehavior.ALCOHOL, -3.0))
        assertEquals(R.string.wmy_dose_caffeine_higher, WhatMovesYou.doseSentenceRes(DosedBehavior.CAFFEINE, 1.0))
    }

    @Test fun wordsForSizeAndConfidence() {
        assertEquals(R.string.wmy_size_negligible, WhatMovesYou.effectSizeRes(-0.1))
        assertEquals(R.string.wmy_size_large, WhatMovesYou.effectSizeRes(-1.2))
        assertEquals(R.string.wmy_confidence_solid, WhatMovesYou.confidenceRes(ScoreConfidence.SOLID))
    }

    @Test fun doseMatchingAndOutcomeKeys() {
        assertTrue(WhatMovesYou.matches(DosedBehavior.ALCOHOL, "Did you drink any alcohol?"))
        assertTrue(WhatMovesYou.matches(DosedBehavior.CAFFEINE, "Caffeine after 4pm?"))
        assertFalse(WhatMovesYou.matches(DosedBehavior.CAFFEINE, "Did you nap?"))
        assertEquals("hrv", WhatMovesYou.outcomeKeyFor("HRV"))
        assertEquals("recovery", WhatMovesYou.outcomeKeyFor("Anything"))
    }

    @Test fun emptyInputsLoadToAnEmptyPage() {
        val s = WhatMovesYou.build(emptyList(), emptyList(), emptyMap(), emptyList())
        assertTrue(s.loaded)
        assertTrue(s.rank(WmyOutcome.Recovery).isEmpty())
        assertTrue(s.doseCards.isEmpty())
        assertTrue(s.relationships.isEmpty())
    }
}
