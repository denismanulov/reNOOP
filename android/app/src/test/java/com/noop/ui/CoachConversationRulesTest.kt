package com.noop.ui

import com.noop.ai.ChatMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pins the Messages-style Coach conversation rules ([CoachConversationRules], [CoachMessageMeta]):
 * day chips, bubble corners, which chips the composer offers (the brief only on an empty chat with data
 * access, never sent by itself: CR-10), Try Again, the failed question, and dictation that keeps the
 * typed text (CR-12). Twins of the rules in Swift `CoachView` / `AICoachEngine`.
 */
class CoachConversationRulesTest {

    private fun user(text: String, id: String = text) = ChatMsg(id = id, role = "user", text = text)
    private fun reply(text: String, id: String = text, brief: Boolean = false, interrupted: Boolean = false) =
        ChatMsg(id = id, role = "assistant", text = text, isBrief = brief, isInterrupted = interrupted)

    private val utc: ZoneId = ZoneOffset.UTC
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(utc).toInstant().toEpochMilli()

    // MARK: stamps

    @Test
    fun `each local day gets one chip, on its first dated message`() {
        val ids = listOf("a", "b", "c", "d")
        val times = mapOf(
            "a" to ms(2026, 10, 1, 9),
            "b" to ms(2026, 10, 1, 23, 30),
            "c" to ms(2026, 10, 2, 0, 5),
            "d" to ms(2026, 10, 2, 18),
        )
        assertEquals(times["a"], CoachConversationRules.dayChipBefore(0, ids, times, utc))
        assertNull(CoachConversationRules.dayChipBefore(1, ids, times, utc))
        assertEquals(times["c"], CoachConversationRules.dayChipBefore(2, ids, times, utc))
        assertNull(CoachConversationRules.dayChipBefore(3, ids, times, utc))
    }

    @Test
    fun `a message without a recorded time gets no chip and does not make its neighbours repeat the day`() {
        val ids = listOf("a", "old", "b", "next")
        val times = mapOf("a" to ms(2026, 10, 1, 9), "b" to ms(2026, 10, 1, 12), "next" to ms(2026, 10, 2, 7))
        assertNull(CoachConversationRules.dayChipBefore(1, ids, times, utc))
        assertNull(CoachConversationRules.dayChipBefore(2, ids, times, utc))
        assertEquals(times["next"], CoachConversationRules.dayChipBefore(3, ids, times, utc))
        assertNull(CoachConversationRules.dayChipBefore(9, ids, times, utc))
        // Undated messages ahead of the first dated one leave it the first of its day.
        assertEquals(times["b"], CoachConversationRules.dayChipBefore(1, listOf("old", "b"), times, utc))
    }

    @Test
    fun `day words read today, yesterday, a weekday within the week, then a date`() {
        val now = ms(2026, 10, 1, 12)
        assertEquals(CoachDayWord.TODAY, CoachConversationRules.dayWord(ms(2026, 10, 1, 0, 5), now, utc))
        assertEquals(CoachDayWord.YESTERDAY, CoachConversationRules.dayWord(ms(2026, 9, 30, 23), now, utc))
        assertEquals(CoachDayWord.WEEKDAY, CoachConversationRules.dayWord(ms(2026, 9, 25, 8), now, utc))
        assertEquals(CoachDayWord.DATE, CoachConversationRules.dayWord(ms(2026, 9, 24, 8), now, utc))
    }

    // MARK: bubbles

    @Test
    fun `a bubble tucks its sender-side bottom corner, and its top one inside a run`() {
        val L = CoachConversationRules.LARGE
        val S = CoachConversationRules.SMALL
        // (topStart, topEnd, bottomEnd, bottomStart)
        assertEquals(listOf(L, L, S, L), CoachConversationRules.bubbleCorners(outgoing = true, followsSameSide = false))
        assertEquals(listOf(L, S, S, L), CoachConversationRules.bubbleCorners(outgoing = true, followsSameSide = true))
        assertEquals(listOf(L, L, L, S), CoachConversationRules.bubbleCorners(outgoing = false, followsSameSide = false))
        assertEquals(listOf(S, L, L, S), CoachConversationRules.bubbleCorners(outgoing = false, followsSameSide = true))
    }

    @Test
    fun `an empty streaming placeholder is left to the typing bubble`() {
        val msgs = listOf(user("q"), reply("", id = "p"))
        assertEquals(listOf("q"), CoachConversationRules.shown(msgs).map { it.id })
        assertTrue(CoachConversationRules.showsTyping(msgs, sending = true))
        assertFalse(CoachConversationRules.showsTyping(listOf(user("q"), reply("Hi")), sending = true))
        assertFalse(CoachConversationRules.showsTyping(msgs, sending = false))
    }

    // MARK: suggestions (CR-10)

    private val contextual = listOf("Analyse my sleep", "Why am I run down?")
    private val followUps = listOf("Tell me more about that", "What should I do next?")

    @Test
    fun `the brief heads an empty chat only with a provider and data access`() {
        val with = CoachConversationRules.suggestions(emptyList(), configured = true, consent = true, contextual, followUps)
        assertEquals(CoachSuggestion.Brief, with.first())
        assertEquals(contextual, with.drop(1).map { (it as CoachSuggestion.Prompt).english })

        val noConsent = CoachConversationRules.suggestions(emptyList(), configured = true, consent = false, contextual, followUps)
        assertFalse(noConsent.contains(CoachSuggestion.Brief))
        val notConfigured = CoachConversationRules.suggestions(emptyList(), configured = false, consent = true, contextual, followUps)
        assertFalse(notConfigured.contains(CoachSuggestion.Brief))
    }

    @Test
    fun `after a reply the follow-ups replace the contextual chips, and the brief is gone`() {
        val after = CoachConversationRules.suggestions(listOf(user("q"), reply("a")), true, true, contextual, followUps)
        assertEquals(followUps, after.map { (it as CoachSuggestion.Prompt).english })
        // A conversation that is not empty never offers the brief, even when it ends on a question.
        val pending = CoachConversationRules.suggestions(listOf(user("q")), true, true, contextual, followUps)
        assertFalse(pending.contains(CoachSuggestion.Brief))
    }

    // MARK: Try Again

    @Test
    fun `try again needs an answer to the question before it, and every gate the resend meets`() {
        val answered = listOf(user("q"), reply("a"))
        assertTrue(CoachConversationRules.canRetryLastReply(answered, false, true, true, consent = false))
        assertFalse(CoachConversationRules.canRetryLastReply(answered, sending = true, configured = true, coachEnabled = true, consent = true))
        assertFalse(CoachConversationRules.canRetryLastReply(answered, false, configured = false, coachEnabled = true, consent = true))
        assertFalse(CoachConversationRules.canRetryLastReply(answered, false, true, coachEnabled = false, consent = true))
        assertFalse(CoachConversationRules.canRetryLastReply(listOf(user("q")), false, true, true, true))
        assertFalse(CoachConversationRules.canRetryLastReply(listOf(reply("a"), reply("b")), false, true, true, true))
    }

    @Test
    fun `the brief can be written again only when it stands alone and data may be read`() {
        val brief = listOf(reply("brief", brief = true))
        assertTrue(CoachConversationRules.canRetryLastReply(brief, false, true, true, consent = true))
        assertFalse(CoachConversationRules.canRetryLastReply(brief, false, true, true, consent = false))
        assertFalse(CoachConversationRules.canRetryLastReply(listOf(user("q"), reply("b", brief = true)), false, true, true, true))
    }

    @Test
    fun `only the last question of a failed send is undelivered`() {
        val msgs = listOf(reply("a"), user("q"))
        assertEquals(1, CoachConversationRules.failedQuestionIndex(msgs, sending = false, error = "boom"))
        assertNull(CoachConversationRules.failedQuestionIndex(msgs, sending = true, error = "boom"))
        assertNull(CoachConversationRules.failedQuestionIndex(msgs, sending = false, error = null))
        assertNull(CoachConversationRules.failedQuestionIndex(listOf(user("q"), reply("a")), false, "boom"))
    }

    // MARK: dictation (CR-12)

    @Test
    fun `dictation appends to the typed text and the final result settles it`() {
        val base = "Before my run,"
        assertEquals("Before my run, how", CoachConversationRules.dictationDraft(base, "how"))
        assertEquals("Before my run, how hard today", CoachConversationRules.dictationDraft(base, "how hard today"))
        // The final result re-derives from the same base, so it is not appended a second time.
        assertEquals("Before my run, how hard today", CoachConversationRules.dictationDraft(base, " how hard today "))
        assertEquals("how hard", CoachConversationRules.dictationDraft("  ", "how hard"))
        assertEquals("Before my run,", CoachConversationRules.dictationDraft(base, ""))
    }

    // MARK: per-message facts

    @Test
    fun `message flags use the iOS bits and the meta map round-trips`() {
        assertEquals(1, CoachMessageMeta.flagsOf(reply("x", brief = true)))
        assertEquals(2, CoachMessageMeta.flagsOf(reply("x", interrupted = true)))
        assertEquals(3, CoachMessageMeta.flagsOf(reply("x", brief = true, interrupted = true)))
        assertEquals(0, CoachMessageMeta.flagsOf(user("x")))

        val map = linkedMapOf("0f8fad5b-d9cb-469f-a165-70867728950e" to 1_727_766_000_000L, "b" to 2L)
        assertEquals(map, CoachMessageMeta.decode(CoachMessageMeta.encode(map)))
        assertEquals(emptyMap<String, Long>(), CoachMessageMeta.decode(null))
        assertEquals(mapOf("ok" to 5L), CoachMessageMeta.decode("ok=5;broken;=7;bad=x"))
    }
}
