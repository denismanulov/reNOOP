package com.noop.ui.journal

import com.noop.R
import com.noop.data.JournalEntry
import com.noop.ui.JournalCatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The Journal page's pure parts: strip, fill, numbers, moods and translated question names (WM-1). */
class JournalLogicTest {

    @Test fun stripRunsSixDaysBackToTomorrowLeftToRight() {
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L, 0L, -1L), JOURNAL_STRIP_OFFSETS)
    }

    @Test fun dayFractionCountsHabitsPlusMoodOverItemsPlusOne() {
        assertEquals(0f, journalDayFraction(0, hasMood = false, itemCount = 10), 0f)
        assertEquals(4f / 11f, journalDayFraction(3, hasMood = true, itemCount = 10), 1e-6f)
        assertEquals(1f, journalDayFraction(20, hasMood = true, itemCount = 3), 0f)
        assertEquals(1f, journalDayFraction(0, hasMood = true, itemCount = 0), 0f)
    }

    @Test fun answeredCountDedupesByQuestionKey() {
        val entries = listOf(
            JournalEntry("my-whoop", "2026-09-30", "Did you take magnesium?\n", true),
            JournalEntry("noop-journal", "2026-09-30", "Did you take magnesium?", false),
            JournalEntry("noop-journal", "2026-09-30", "Did you nap?", true),
            JournalEntry("noop-journal", "2026-09-29", "Did you nap?", true),
        )
        assertEquals(2, journalAnsweredCount(entries, "2026-09-30"))
        assertEquals(0, journalAnsweredCount(entries, "2026-09-28"))
    }

    @Test fun numbersPrintWholeOrOneDecimal() {
        assertEquals("200", journalNumber(200.0))
        assertEquals("2.5", journalNumber(2.5))
        assertEquals("0.3", journalNumber(0.33))
    }

    @Test fun moodStepsClampOntoTheScale() {
        assertEquals(1, moodStep(0.2))
        assertEquals(4, moodStep(3.6))
        assertEquals(5, moodStep(9.0))
        assertEquals(R.string.journal_mood_rough, moodLabelRes(1))
        assertEquals(R.string.journal_mood_great, moodLabelRes(5))
    }

    @Test fun caffeineDayAndNoonUseTheLocalZone() {
        val zone = ZoneId.of("Europe/Moscow")
        val noon = journalNoonEpoch(LocalDate.of(2026, 9, 28), zone)
        assertEquals("2026-09-28", journalLocalDay(noon, zone))
        assertEquals(LocalDate.of(2026, 9, 28).atTime(12, 0).atZone(zone).toEpochSecond(), noon)
    }

    @Test fun builtInQuestionsAreTranslatedRenamesAndCustomsAreNot() {
        val t: (Int) -> String = { id -> "T$id" }
        val starter = JournalCatalogItem(canonical = "Did you take magnesium?")
        assertEquals("T${R.string.journal_q_magnesium}", journalItemLabel(starter, t))
        // Whitespace differences fold onto the same key (#224).
        assertEquals(R.string.journal_q_magnesium, journalQuestionLabelRes(" did you  take magnesium? \n"))
        val renamed = starter.copy(displayName = "Magnesium")
        assertEquals("Magnesium", journalItemLabel(renamed, t))
        val custom = JournalCatalogItem(canonical = "Did you nap?", custom = true)
        assertEquals("Did you nap?", journalItemLabel(custom, t))
        assertNull(journalQuestionLabelRes("Cold plunge?"))
        assertEquals("Cold plunge?", journalQuestionLabel("Cold plunge? ", emptyList(), t))
        assertEquals("Magnesium", journalQuestionLabel("Did you take magnesium?", listOf(renamed), t))
    }
}
