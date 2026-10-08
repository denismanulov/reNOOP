package com.noop.friends

import java.net.URI
import java.util.Locale

// MARK: - Friends: which server address the app will talk to
//
// Passwords and the session token travel in requests, so the address must be HTTPS. The one exception
// is for development: plain HTTP to this phone's own loopback (`adb reverse` points it at a server on the
// developer's machine), where nothing crosses a network. Pure, so the rule is pinned by a JVM test.

object FriendsServerUrl {
    /** The fork's own server, used until the wearer names another. */
    const val DEFAULT = "https://renoop.duckdns.org"

    /** The only hosts plain HTTP is accepted for. */
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")

    enum class Problem { EMPTY, MALFORMED, NOT_HTTPS, HAS_CREDENTIALS, HAS_QUERY }

    sealed class Result {
        /** [url] is the normalised base address, with no trailing slash. */
        data class Valid(val url: String) : Result()
        data class Invalid(val problem: Problem) : Result()
    }

    /**
     * Checks an address as typed. An address without a scheme is read as HTTPS ("renoop.example.org").
     * A path is kept, so a server mounted under a prefix by its proxy works; a query, a fragment or
     * credentials in the address are refused.
     */
    fun validate(raw: String): Result {
        val typed = raw.trim()
        if (typed.isEmpty()) return Result.Invalid(Problem.EMPTY)
        val withScheme = if ("://" in typed) typed else "https://$typed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return Result.Invalid(Problem.MALFORMED)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Result.Invalid(Problem.MALFORMED)
        if (scheme != "https" && scheme != "http") return Result.Invalid(Problem.NOT_HTTPS)
        if (uri.rawUserInfo != null) return Result.Invalid(Problem.HAS_CREDENTIALS)
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid(Problem.HAS_QUERY)
        // A host java.net.URI cannot read as a host name (spaces, underscores, non-ASCII letters) has none.
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: return Result.Invalid(Problem.MALFORMED)
        if (uri.port != -1 && uri.port !in 1..65535) return Result.Invalid(Problem.MALFORMED)
        if (scheme == "http" && host !in LOOPBACK_HOSTS) return Result.Invalid(Problem.NOT_HTTPS)

        val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
        val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return Result.Valid("$scheme://$host$port$path")
    }

    /** The normalised address, or null when [raw] is not one the app will talk to. */
    fun normalised(raw: String): String? = (validate(raw) as? Result.Valid)?.url
}

/** Nicknames as the server keeps them: 3 to 20 of a-z, 0-9 and _, lower-case, a leading @ ignored. */
object FriendsNick {
    private val VALID = Regex("^[a-z0-9_]{3,20}$")
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 128
    const val NAME_MAX = 40

    /** The nickname as the server would store it, or null when it could not be one. */
    fun clean(typed: String): String? {
        val nick = typed.trim().trimStart('@').lowercase(Locale.ROOT)
        return nick.takeIf { VALID.matches(it) }
    }

    /** What is typed into a nickname field as it may stand there: lower-case, no @, no other characters. */
    fun filterTyping(typed: String): String =
        typed.trimStart('@').lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(MAX_LENGTH)

    fun passwordOk(password: String): Boolean = password.length in PASSWORD_MIN..PASSWORD_MAX
}
