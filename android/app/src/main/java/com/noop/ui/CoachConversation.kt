package com.noop.ui

import android.content.Context
import com.noop.ai.ChatMsg
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// MARK: - Coach conversation rules (Messages-style transcript)
//
// The pure decisions behind the Coach conversation screen, kept out of Compose so a JVM test can pin
// them: which messages are headed by a day chip and how it reads, which corners a bubble rounds, which
// suggestion chips the composer offers, whether the last reply can be asked again, and how dictation
// merges into a draft. Twin of the rules in Swift `CoachView` / `AICoachEngine`.

/** A chip under the composer: a question to send, or the brief (which runs its own request). */
internal sealed interface CoachSuggestion {
    /** "Today's Brief": the first chip of an empty chat, sent only when tapped (CR-10). */
    data object Brief : CoachSuggestion

    /** An English prompt from the engine; the screen shows and sends it in the app's language. */
    data class Prompt(val english: String) : CoachSuggestion
}

/** What a day stamp names: today, yesterday, a weekday this week, or a date. */
internal enum class CoachDayWord { TODAY, YESTERDAY, WEEKDAY, DATE }

internal object CoachConversationRules {

    /**
     * The day chip shown above message [index] of [ids]: the message's own time when it is the first
     * dated message of its local day, else null. Every bubble carries its own clock time, so the chip
     * names the day alone and each day gets one, as Telegram dates a chat. A message with no recorded
     * time (one restored from before times were kept) gets no chip and is skipped when looking back for
     * the previous day, so one undated message never makes its neighbours repeat the day.
     */
    fun dayChipBefore(index: Int, ids: List<String>, times: Map<String, Long>, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val time = times[ids.getOrNull(index) ?: return null] ?: return null
        val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        for (i in index - 1 downTo 0) {
            val previous = times[ids[i]] ?: continue
            return if (Instant.ofEpochMilli(previous).atZone(zone).toLocalDate() == day) null else time
        }
        return time
    }

    /** How a stamp at [stampMs] names its day, seen at [nowMs]: Today, Yesterday, a weekday within the
     *  week, else a date. Twin of Swift `CoachView.dayWord(_:)`. */
    fun dayWord(stampMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): CoachDayWord {
        val day = Instant.ofEpochMilli(stampMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        if (day == today) return CoachDayWord.TODAY
        if (day == today.minusDays(1)) return CoachDayWord.YESTERDAY
        val days = ChronoUnit.DAYS.between(day, today)
        return if (days in 0..6) CoachDayWord.WEEKDAY else CoachDayWord.DATE
    }

    /**
     * The bubble's four corners as (topStart, topEnd, bottomEnd, bottomStart) radii in dp: large all
     * round, except that the corner on the sender's side tucks in to [SMALL] at the bottom (the tail
     * spot, as Google Messages draws a bubble) and at the top when the previous message came from the
     * same side, so a run of bubbles reads as one group.
     */
    fun bubbleCorners(outgoing: Boolean, followsSameSide: Boolean): List<Int> {
        val top = if (followsSameSide) SMALL else LARGE
        return if (outgoing) listOf(LARGE, top, SMALL, LARGE) else listOf(top, LARGE, LARGE, SMALL)
    }

    const val LARGE = 20
    const val SMALL = 4

    /**
     * The chips the composer offers. After a reply: the four follow-ups. Otherwise the contextual
     * questions, headed by "Today's Brief" on an empty chat that may use the wearer's data — the brief
     * runs only when its chip is tapped, never on opening the chat (CR-10). Twin of Swift
     * `CoachView.suggestions`.
     */
    fun suggestions(
        messages: List<ChatMsg>,
        configured: Boolean,
        consent: Boolean,
        contextual: List<String>,
        followUps: List<String>,
    ): List<CoachSuggestion> {
        if (messages.lastOrNull()?.role == "assistant") return followUps.map { CoachSuggestion.Prompt(it) }
        val brief = messages.isEmpty() && configured && consent
        return (if (brief) listOf(CoachSuggestion.Brief) else emptyList()) +
            contextual.map { CoachSuggestion.Prompt(it) }
    }

    /**
     * Whether the last reply can be asked for again: an answer to the question just before it, or the
     * brief standing alone at the head of the chat (and only while the data it needs may be read).
     * Checked against every gate the new request would meet, so Try Again never removes a reply it
     * cannot replace. Twin of Swift `AICoachEngine.canRetryLastReply`.
     */
    fun canRetryLastReply(
        messages: List<ChatMsg>,
        sending: Boolean,
        configured: Boolean,
        coachEnabled: Boolean,
        consent: Boolean,
    ): Boolean {
        if (sending || !configured || !coachEnabled) return false
        val last = messages.lastOrNull() ?: return false
        if (last.role != "assistant") return false
        if (last.isBrief) return messages.size == 1 && consent
        return messages.getOrNull(messages.size - 2)?.role == "user"
    }

    /**
     * The undelivered question: the last message is the wearer's, nothing is being written, and the
     * last send failed. Its bubble carries the red "!" and "Not Delivered".
     */
    fun failedQuestionIndex(messages: List<ChatMsg>, sending: Boolean, error: String?): Int? {
        if (sending || error.isNullOrEmpty()) return null
        val last = messages.lastOrNull() ?: return null
        return if (last.role == "user") messages.lastIndex else null
    }

    /** The typing bubble shows while a reply is on its way and none of its words are on screen yet. */
    fun showsTyping(messages: List<ChatMsg>, sending: Boolean): Boolean {
        if (!sending) return false
        val last = messages.lastOrNull() ?: return true
        return !(last.role == "assistant" && last.text.isNotBlank())
    }

    /** The messages the transcript draws: a streaming reply's empty placeholder is left to the typing
     *  bubble until its first words arrive. */
    fun shown(messages: List<ChatMsg>): List<ChatMsg> =
        messages.filter { it.role == "user" || it.text.isNotBlank() }

    /**
     * The draft while dictating: the spoken words APPENDED to what was typed before the mic was tapped,
     * never over it (CR-12). Each partial and the final result re-derive the draft from that same base,
     * so the final result settles the text instead of appending it a second time. Twin of Swift
     * `CoachView.dictate(_:)`.
     */
    fun dictationDraft(base: String, spoken: String): String {
        val words = spoken.trim()
        val typed = base.trim()
        return when {
            words.isEmpty() -> typed
            typed.isEmpty() -> words
            else -> "$typed $words"
        }
    }
}

// MARK: - Per-message facts across launches

/**
 * The per-message facts the stored transcript row does not carry: the brief / interrupted flags and
 * the time each message arrived (for the stamps). Kept beside the Room rows, keyed by message id,
 * written with every save of the transcript and read back when it is restored; a message with no entry
 * has neither flag and no time. Twin of Swift `CoachMessageFlags` + `CoachMessageTimes`, same bit values.
 */
internal object CoachMessageMeta {
    private const val PREFS = "noop_coach_meta"
    private const val KEY_FLAGS = "coach.messageFlags"
    private const val KEY_TIMES = "coach.messageTimes"

    const val FLAG_BRIEF = 1
    const val FLAG_INTERRUPTED = 2

    fun flagsOf(m: ChatMsg): Int = (if (m.isBrief) FLAG_BRIEF else 0) or (if (m.isInterrupted) FLAG_INTERRUPTED else 0)

    /** `id=value;id=value`: ids are UUID strings, so neither separator can occur inside one. */
    fun encode(map: Map<String, Long>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val value = part.substring(eq + 1).toLongOrNull() ?: continue
            out[part.substring(0, eq)] = value
        }
        return out
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadFlags(ctx: Context): Map<String, Int> =
        runCatching { decode(prefs(ctx).getString(KEY_FLAGS, null)).mapValues { it.value.toInt() } }
            .getOrDefault(emptyMap())

    fun loadTimes(ctx: Context): Map<String, Long> =
        runCatching { decode(prefs(ctx).getString(KEY_TIMES, null)) }.getOrDefault(emptyMap())

    /** Saves the flags of [messages] and the times of the ones still in it (the others are forgotten). */
    fun save(ctx: Context, messages: List<ChatMsg>, times: Map<String, Long>) {
        val flags = messages.associate { it.id to flagsOf(it).toLong() }.filterValues { it != 0L }
        val ids = messages.map { it.id }.toSet()
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_FLAGS, encode(flags))
                .putString(KEY_TIMES, encode(times.filterKeys { it in ids }))
                .apply()
        }
    }
}

/** The local day of epoch-millis [ms]; a convenience for the stamp formatter. */
internal fun coachLocalDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
