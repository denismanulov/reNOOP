package com.noop.friends

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

// MARK: - Friends: the text rules every client applies identically
//
// API version 2 of the friends service (friends-server/README.md). No hashing and no I/O: what a phone
// signs, how an invite code is read as typed, and how a code is taken out of a link or a pasted message.
// Swift twin: `FriendsWire` and `FriendsInviteCode` in Packages/StrandAnalytics. The two are held
// together by an oracle: `FriendsWireTest` asserts the stdout of the Swift file compiled standalone.

object FriendsWire {
    const val SIGNING_PREFIX = "renoop-friends-v2"
    const val STRAP_HANDLE_PREFIX = "renoop-friends-strap-v1\n"

    /**
     * What a phone signs for one request: six lines joined by "\n", with no trailing newline. [target] is
     * the path and query exactly as sent, beginning "/v2/".
     */
    fun signingString(method: String, target: String, time: Long, nonce: String, bodyHashHex: String): String =
        listOf(SIGNING_PREFIX, method.uppercase(Locale.ROOT), target, time.toString(), nonce, bodyHashHex)
            .joinToString("\n")

    /**
     * The text whose SHA-256 is a strap's handle. [adoptedId] is the registry's serial-derived id, such
     * as "whoop-4A0123456".
     */
    fun strapHandleInput(adoptedId: String): String = STRAP_HANDLE_PREFIX + adoptedId

    /**
     * A JSON text laid out to be read: one member or element to a line, indented by [step] for each
     * level, a space after each colon. The text is walked, not parsed: every string, number and literal
     * is copied as it came, so a number keeps the digits the server wrote and members keep their order.
     * Only whitespace outside strings is replaced. Text that is not JSON comes out with nothing lost.
     */
    fun indented(json: String, step: String = "  "): String {
        val scalars = json.codePoints().toArray()
        val out = StringBuilder(json.length * 2)
        var depth = 0
        var inString = false
        var escaped = false
        fun isLayout(cp: Int) = cp == ' '.code || cp == '\n'.code || cp == '\t'.code || cp == '\r'.code
        fun newLine() {
            out.append('\n')
            repeat(depth) { out.append(step) }
        }
        var index = 0
        while (index < scalars.size) {
            val scalar = scalars[index]
            index++
            if (inString) {
                out.appendCodePoint(scalar)
                when {
                    escaped -> escaped = false
                    scalar == '\\'.code -> escaped = true
                    scalar == '"'.code -> inString = false
                }
                continue
            }
            when {
                isLayout(scalar) -> Unit
                scalar == '"'.code -> {
                    inString = true
                    out.appendCodePoint(scalar)
                }
                scalar == '{'.code || scalar == '['.code -> {
                    out.appendCodePoint(scalar)
                    var next = index
                    while (next < scalars.size && isLayout(scalars[next])) next++
                    val closer = if (scalar == '{'.code) '}'.code else ']'.code
                    if (next < scalars.size && scalars[next] == closer) {
                        // An empty object or array stays on its line.
                        out.appendCodePoint(scalars[next])
                        index = next + 1
                    } else {
                        depth++
                        newLine()
                    }
                }
                scalar == '}'.code || scalar == ']'.code -> {
                    depth = maxOf(0, depth - 1)
                    newLine()
                    out.appendCodePoint(scalar)
                }
                scalar == ','.code -> {
                    out.appendCodePoint(scalar)
                    newLine()
                }
                scalar == ':'.code -> {
                    out.appendCodePoint(scalar)
                    out.append(' ')
                }
                else -> out.appendCodePoint(scalar)
            }
        }
        return out.toString()
    }
}

/** An invite code: ten characters of a 32-letter alphabet that leaves out the letters read as digits. */
object FriendsInviteCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 10

    /**
     * The stored form of typed input, or null when it is not a code. Forgives case, the dash, spaces,
     * and O / I / L typed for 0 / 1 / 1, exactly as the server does. Walks code points: the server
     * upper-cases before it reads, so a letter whose upper case is two letters counts as both.
     */
    fun normalized(raw: String): String? {
        val out = StringBuilder(LENGTH)
        var count = 0
        val upper = raw.uppercase(Locale.ROOT)
        var index = 0
        while (index < upper.length) {
            val scalar = upper.codePointAt(index)
            index += Character.charCount(scalar)
            when (scalar) {
                '-'.code, ' '.code, '\t'.code, '\r'.code, '\n'.code -> continue
                'O'.code -> out.append('0')
                'I'.code, 'L'.code -> out.append('1')
                else -> {
                    if (scalar > 0x7F || ALPHABET.indexOf(scalar.toChar()) < 0) return null
                    out.append(scalar.toChar())
                }
            }
            count++
            if (count > LENGTH) return null
        }
        return if (count == LENGTH) out.toString() else null
    }

    /** A stored code as it is shown and shared: "XXXXX-XXXXX". */
    fun display(code: String): String {
        if (code.codePointCount(0, code.length) != LENGTH || code.length != LENGTH) return code
        return code.substring(0, 5) + "-" + code.substring(5)
    }

    /**
     * The code in whatever was typed, pasted or opened: a bare code, an invite page's address
     * (".../i/<code>") or the app's own link ("renoop://friends/add?c=<code>"). Null when there is none.
     * A whole message may be pasted, such as the one the app shares: when the text as a whole is
     * neither, it is read word by word, each without the punctuation that may end it, for a link first
     * and else for a code written as codes are shown ("XXXXX-XXXXX"). A word among others is not read as
     * a code in any looser form, since a ten-letter word can be spelled in the alphabet.
     */
    fun extract(text: String): String? {
        val trimmed = trim(text)
        normalized(trimmed)?.let { return it }
        fromLink(trimmed)?.let { return it }
        val words = words(trimmed)
        if (!(words.size > 1 || words.firstOrNull() != trimmed)) return null
        words.firstNotNullOfOrNull(::fromLink)?.let { return it }
        return words.firstNotNullOfOrNull(::shown)
    }

    /** The invite page's address on [server] (a normalised server address, no trailing slash). */
    fun pageLink(server: String, code: String): String = server + "/i/" + display(code)

    // MARK: Words

    private fun trim(text: String): String = FriendsText.trimmed(text)

    /** The words of [text], each without the punctuation that may end it. */
    private fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val word = ArrayList<Int>()
        fun flush() {
            if (word.isEmpty()) return
            var end = word.size
            while (end > 0 && word[end - 1] <= 0x7F && word[end - 1].toChar() in ENDING) end--
            out.add(String(word.subList(0, end).toIntArray(), 0, end))
            word.clear()
        }
        text.codePoints().forEach { cp -> if (FriendsText.isSpace(cp)) flush() else word.add(cp) }
        flush()
        return out
    }

    private const val ENDING = ".,;:!?)"

    /** The code in a word written exactly as codes are shown, two halves of five with a dash, or null. */
    private fun shown(word: String): String? {
        val scalars = word.codePoints().toArray()
        if (scalars.size != LENGTH + 1 || scalars[LENGTH / 2] != '-'.code) return null
        return normalized(word)
    }

    // MARK: Links

    /** The code an invite page's address or the app's own link carries, or null. */
    private fun fromLink(text: String): String? {
        val link = Link.parse(text) ?: return null
        if (link.scheme?.lowercase(Locale.ROOT) == "renoop") {
            val host = link.host ?: return null
            if (host.lowercase(Locale.ROOT) != "friends") return null
            val path = link.path
            if (path != "/add" && path != "/add/") return null
            val item = link.queryItems().firstOrNull { it.first == "c" } ?: return null
            return item.second?.let(::normalized)
        }
        val segments = link.path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[segments.size - 2] != "i") return null
        return normalized(segments[segments.size - 1])
    }

    /**
     * An address taken apart the way Foundation's `URLComponents(string:)` takes it apart, for the four
     * parts a code is read from. The Swift side asks the system; this side has no such parser to ask
     * (java.net.URI and android.net.Uri each read the edge cases differently), so the rules are written
     * out here and held to the Swift answers by the oracle in `FriendsWireTest`:
     *
     *  - a scheme is what stands before the first ":" unless one of "/ ? # [ ] @" comes first; text
     *    there that is not a scheme makes the whole address unreadable;
     *  - after "//" comes the authority: anything up to the last "@" is the user, a port is digits, and
     *    a host that is not a name (or a bracketed literal) makes the address unreadable;
     *  - the path and the query are each percent-decoded only when everything in them is a character an
     *    address may carry; otherwise they are taken as written, percent signs included.
     *
     * One difference is deliberate: a host with a character outside ASCII is refused here. Foundation
     * maps such a name through the system's IDNA tables, which this code does not carry. No address the
     * app makes has one (the server address is ASCII by `FriendsServerUrl`).
     */
    private class Link(val scheme: String?, private val rawHost: String?, private val rawPath: String, private val rawQuery: String?) {
        /** The host with its percent-encoding removed; null with no authority or when it does not decode. */
        val host: String? get() = rawHost?.let(::percentDecoded)

        /** The path as it reads; empty when its percent-encoding is not text. */
        val path: String
            get() = if (isClean(rawPath, PATH)) percentDecoded(rawPath).orEmpty() else rawPath

        /** The query's members in order: a name, and a value when the member has "=". */
        fun queryItems(): List<Pair<String, String?>> {
            val query = rawQuery ?: return emptyList()
            val clean = isClean(query, QUERY)
            return query.split('&').map { item ->
                val equals = item.indexOf('=')
                val name = if (equals < 0) item else item.substring(0, equals)
                val value = if (equals < 0) null else item.substring(equals + 1)
                if (clean) (percentDecoded(name) ?: "") to value?.let { percentDecoded(it) } else name to value
            }
        }

        companion object {
            private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            private const val SUB_DELIMS = "!\$&'()*+,;="
            private const val NAME = UNRESERVED + SUB_DELIMS
            private const val LITERAL = NAME + ":%"
            private const val PATH = NAME + ":@/"
            private const val QUERY = PATH + "?"
            private const val SCHEME_REST = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+.-"

            fun parse(text: String): Link? {
                var rest = text
                var scheme: String? = null
                val colon = text.indexOf(':')
                if (colon > 0) {
                    val head = text.substring(0, colon)
                    if (head.none { it in "/?#[]@" }) {
                        if (!isScheme(head)) return null
                        scheme = head
                        rest = text.substring(colon + 1)
                    }
                }
                var host: String? = null
                if (rest.startsWith("//")) {
                    var end = rest.length
                    for (i in 2 until rest.length) {
                        if (rest[i] == '/' || rest[i] == '?' || rest[i] == '#') {
                            end = i
                            break
                        }
                    }
                    host = hostOf(rest.substring(2, end).substringAfterLast('@')) ?: return null
                    rest = rest.substring(end)
                }
                val hash = rest.indexOf('#')
                if (hash >= 0) rest = rest.substring(0, hash)
                val mark = rest.indexOf('?')
                return if (mark < 0) Link(scheme, host, rest, null)
                else Link(scheme, host, rest.substring(0, mark), rest.substring(mark + 1))
            }

            private fun isScheme(text: String): Boolean =
                text.isNotEmpty() && (text[0] in 'a'..'z' || text[0] in 'A'..'Z') && text.all { it in SCHEME_REST }

            private fun isDigits(text: String): Boolean = text.all { it in '0'..'9' }

            /** The host of "host", "host:port" or "[literal]:port" as written, or null when it is not one. */
            private fun hostOf(hostPort: String): String? {
                if (hostPort.startsWith("[")) {
                    val close = hostPort.indexOf(']')
                    if (close < 0) return null
                    if (!hostPort.substring(1, close).all { it in LITERAL }) return null
                    val tail = hostPort.substring(close + 1)
                    if (tail.isNotEmpty() && !(tail[0] == ':' && isDigits(tail.substring(1)))) return null
                    return hostPort.substring(0, close + 1)
                }
                val colon = hostPort.indexOf(':')
                val name = if (colon < 0) hostPort else hostPort.substring(0, colon)
                if (colon >= 0 && !isDigits(hostPort.substring(colon + 1))) return null
                return name.takeIf { isClean(it, NAME) }
            }

            private fun isHex(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

            /** Whether [text] holds only characters of [allowed] and whole percent-escapes. */
            private fun isClean(text: String, allowed: String): Boolean {
                var index = 0
                while (index < text.length) {
                    val ch = text[index]
                    if (ch == '%') {
                        if (index + 2 >= text.length || !isHex(text[index + 1]) || !isHex(text[index + 2])) return false
                        index += 3
                    } else {
                        if (ch !in allowed) return false
                        index++
                    }
                }
                return true
            }

            /** [text] with its percent-escapes read as UTF-8, or null when they do not spell text. */
            private fun percentDecoded(text: String): String? {
                if ('%' !in text) return text
                val bytes = java.io.ByteArrayOutputStream(text.length)
                var index = 0
                while (index < text.length) {
                    val ch = text[index]
                    if (ch == '%' && index + 2 < text.length && isHex(text[index + 1]) && isHex(text[index + 2])) {
                        bytes.write(text.substring(index + 1, index + 3).toInt(16))
                        index += 3
                    } else {
                        val end = index + if (Character.isHighSurrogate(ch) && index + 1 < text.length) 2 else 1
                        bytes.write(text.substring(index, end).toByteArray(Charsets.UTF_8))
                        index = end
                    }
                }
                return try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray()))
                        .toString()
                } catch (_: CharacterCodingException) {
                    null
                }
            }
        }
    }
}

/** Reading typed text the way the Swift twin's Foundation calls read it. */
internal object FriendsText {
    /**
     * What separates words and is trimmed from the ends: the code points Foundation's
     * `CharacterSet.whitespacesAndNewlines` holds, named one by one so the set is the same on every
     * runtime (it counts the zero-width space, and not the separators U+001C to U+001F).
     */
    fun isSpace(cp: Int): Boolean = when (cp) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200B, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** [text] without the spaces and line ends around it. */
    fun trimmed(text: String): String {
        val scalars = text.codePoints().toArray()
        var start = 0
        var end = scalars.size
        while (start < end && isSpace(scalars[start])) start++
        while (end > start && isSpace(scalars[end - 1])) end--
        return String(scalars, start, end - start)
    }
}
