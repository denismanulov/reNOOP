package com.noop.ai

/**
 * One turn in the coach conversation.
 *
 * @param id stable identifier (UUID string). Used by K1 streaming to find and replace the
 *   placeholder assistant message as chunks arrive. Defaults to a new UUID so existing callers
 *   are unaffected.
 * @param role "user" or "assistant" — the only two roles the UI history carries. The
 *   system prompt is supplied separately by [AiCoach] and is never stored here.
 * @param text plain-text message body: what was said, and what goes back to the model as history.
 * @param isBrief the day's brief rather than an answer to a question (drawn as a "Today's Brief" label).
 * @param isInterrupted the reply stopped before it finished: the user stopped it, or the stream failed
 *   mid-way (drawn as "Stopped" under it). Like [isBrief], a fact ABOUT the message, never written into
 *   [text]. Twin of Swift `ChatMessage.isBrief` / `isInterrupted`.
 */
data class ChatMsg(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant"
    val text: String,
    val isBrief: Boolean = false,
    val isInterrupted: Boolean = false,
)
