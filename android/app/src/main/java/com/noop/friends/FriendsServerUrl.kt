package com.noop.friends

import java.util.Locale

// MARK: - Friends: which server address the app will talk to
//
// Requests carry the wearer's days and a signature, so the address must be HTTPS. The one exception is
// for development: plain HTTP to this phone's own loopback (`adb reverse` points it at a server on the
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
     * credentials in the address are refused. The typed text is read as it stands, not through a URL
     * class: those accept and re-encode almost anything, so a name typed on the wrong keyboard would
     * pass as an address. Swift twin: `FriendsServerAddress.validate`; `FriendsWireTest` holds the two
     * to the same answers.
     */
    fun validate(raw: String): Result {
        val typed = FriendsText.trimmed(raw)
        if (typed.isEmpty()) return Result.Invalid(Problem.EMPTY)
        val text = if ("://" in typed) typed else "https://$typed"
        if (!text.all { it.code in 0x21..0x7E && it !in FORBIDDEN }) return Result.Invalid(Problem.MALFORMED)
        val separator = text.indexOf("://")
        val scheme = text.substring(0, separator).lowercase(Locale.ROOT)
        if (scheme.isEmpty() || scheme[0] !in 'a'..'z' || !scheme.all { isAlphanumeric(it) || it in "+.-" }) {
            return Result.Invalid(Problem.MALFORMED)
        }
        var rest = text.substring(separator + 3)
        var hasQuery = false
        val mark = rest.indexOfFirst { it == '?' || it == '#' }
        if (mark >= 0) {
            hasQuery = true
            rest = rest.substring(0, mark)
        }
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash)
        if (scheme != "https" && scheme != "http") return Result.Invalid(Problem.NOT_HTTPS)
        if ('@' in authority) return Result.Invalid(Problem.HAS_CREDENTIALS)
        if (hasQuery) return Result.Invalid(Problem.HAS_QUERY)

        var host = authority.lowercase(Locale.ROOT)
        var port: Int? = null
        val colon = host.lastIndexOf(':')
        if (colon >= 0 && !host.endsWith("]")) {
            val digits = host.substring(colon + 1)
            host = host.substring(0, colon)
            if (digits.isNotEmpty()) {
                if (!digits.all { it in '0'..'9' } || digits.length > 9) return Result.Invalid(Problem.MALFORMED)
                port = digits.toInt()
            }
        }
        val bracketed = host.startsWith("[") && host.endsWith("]") && host.length > 2
        val named = host.isNotEmpty() && host.all { isAlphanumeric(it) || it == '.' || it == '-' } &&
            host.any { isAlphanumeric(it) } && !host.startsWith("-") && !host.startsWith(".")
        if (!bracketed && !named) return Result.Invalid(Problem.MALFORMED)
        if (port != null && port !in 1..65535) return Result.Invalid(Problem.MALFORMED)
        if (scheme == "http" && host !in LOOPBACK_HOSTS) return Result.Invalid(Problem.NOT_HTTPS)

        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        val portText = if (port == null || defaultPort) "" else ":$port"
        return Result.Valid("$scheme://$host$portText${path.trimEnd('/')}")
    }

    /** Printable characters an address may not carry. */
    private const val FORBIDDEN = "\"<>\\^`{|}"

    private fun isAlphanumeric(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    /** The normalised address, or null when [raw] is not one the app will talk to. */
    fun normalised(raw: String): String? = (validate(raw) as? Result.Valid)?.url
}
