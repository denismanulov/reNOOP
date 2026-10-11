package com.noop.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The friends service's text rules. Two kinds of test: the cases of the Swift `FriendsWireTests`, with
 * the literals of `friends-server/README.md`, and every line of [FriendsWireOracle], which is the Swift
 * twin's own output over a wider spread (line ends, lower case, O / I / L, links with punctuation after
 * them, pasted messages, addresses that are not quite addresses).
 */
class FriendsWireTest {

    // MARK: - The contract's literals

    @Test
    fun theSigningStringIsTheContracts() {
        val text = FriendsWire.signingString(
            method = "put", target = "/v2/me/days/2026-10-10", time = 1_791_540_000L, nonce = "AAAAAAAAAAAAAAAAAAAAAA",
            bodyHashHex = "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445",
        )
        assertEquals(
            "renoop-friends-v2\nPUT\n/v2/me/days/2026-10-10\n1791540000\nAAAAAAAAAAAAAAAAAAAAAA\n" +
                "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445",
            text,
        )
        assertFalse(text.endsWith("\n"))
    }

    @Test
    fun theStrapHandleInputCarriesItsVersionAndTheAdoptedId() {
        assertEquals("renoop-friends-strap-v1\nwhoop-4A0123456", FriendsWire.strapHandleInput("whoop-4A0123456"))
    }

    /** The same cases the server's `normalize_code` is tested with. */
    @Test
    fun aCodeIsReadAsItIsTyped() {
        assertEquals("0123456789", FriendsInviteCode.normalized(" O12-3456 789 "))
        assertEquals("1100ABCDEF", FriendsInviteCode.normalized("ilooabcdef"))
        assertEquals("K7QM2XRD4P", FriendsInviteCode.normalized("K7QM2\r\nXRD4P"))
        assertNull("nine characters", FriendsInviteCode.normalized("ABCDE-1234"))
        assertNull("U is not in the alphabet", FriendsInviteCode.normalized("ABCDE-1234U"))
        assertNull(FriendsInviteCode.normalized(""))
        assertEquals(32, FriendsInviteCode.ALPHABET.length)
    }

    @Test
    fun aCodeIsShownInTwoHalves() {
        assertEquals("K7QM2-XRD4P", FriendsInviteCode.display("K7QM2XRD4P"))
        assertEquals(
            "https://renoop.duckdns.org/i/K7QM2-XRD4P",
            FriendsInviteCode.pageLink("https://renoop.duckdns.org", "K7QM2XRD4P"),
        )
    }

    @Test
    fun theCodeIsFoundInWhateverWasPastedOrOpened() {
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract(" k7qm2 xrd4p "))
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract("https://renoop.duckdns.org/i/K7QM2-XRD4P"))
        assertEquals("a server mounted under a prefix", "K7QM2XRD4P", FriendsInviteCode.extract("https://example.org/friends/i/k7qm2-xrd4p"))
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract("renoop://friends/add?c=k7qm2-xrd4p"))
        assertNull("not an invite page", FriendsInviteCode.extract("https://example.org/other/K7QM2-XRD4P"))
        assertNull("another link of the app's", FriendsInviteCode.extract("renoop://today"))
        assertNull(FriendsInviteCode.extract("renoop://friends/add?c=nope"))
        assertNull(FriendsInviteCode.extract("hello"))
    }

    /**
     * A friend copies the whole message the app shared and pastes it: the code is found among the words,
     * by its link first and else in the form codes are shown in.
     */
    @Test
    fun theCodeIsFoundInAPastedMessage() {
        val english = "Add me on reNOOP Friends: https://renoop.duckdns.org/i/K7QM2-XRD4P\nOr enter the code K7QM2-XRD4P on the Friends tab."
        val russian = "Добавь меня в друзья в reNOOP: https://renoop.duckdns.org/i/K7QM2-XRD4P\nИли введи код K7QM2-XRD4P на вкладке «Друзья»."
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract(english))
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract(russian))
        assertEquals("a link that ends a sentence", "K7QM2XRD4P", FriendsInviteCode.extract("https://renoop.duckdns.org/i/K7QM2-XRD4P."))
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract("see https://renoop.duckdns.org/i/K7QM2-XRD4P), thanks!"))
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract("my code is k7qm2-xrd4p."))
        assertEquals(
            "a link is taken before a bare code", "K7QM2XRD4P",
            FriendsInviteCode.extract("code AAAAA-BBBBB or https://example.org/i/K7QM2-XRD4P"),
        )
        assertEquals("a trailing slash", "K7QM2XRD4P", FriendsInviteCode.extract("https://renoop.duckdns.org/i/K7QM2-XRD4P/"))
        assertEquals("K7QM2XRD4P", FriendsInviteCode.extract("renoop://friends/add/?c=K7QM2-XRD4P"))
        assertEquals("a tab inside a code", "K7QM2XRD4P", FriendsInviteCode.extract("K7QM2\tXRD4P"))
    }

    /**
     * Words alone are not a code, though a ten-letter word may be spelled in the code's alphabet: among
     * other words a code is read only as it is shown, in two halves with a dash.
     */
    @Test
    fun proseAloneIsNotACode() {
        assertNull(FriendsInviteCode.extract("Add me on reNOOP Friends"))
        assertNull(FriendsInviteCode.extract("our friendship means everything"))
        assertNull(FriendsInviteCode.extract("Или введи код на вкладке «Друзья»."))
        assertNull(FriendsInviteCode.extract("call me - maybe"))
        assertNull(FriendsInviteCode.extract("see https://example.org/other/K7QM2XRD4P today"))
        assertEquals("typed alone it is what was typed", "FR1ENDSH1P", FriendsInviteCode.extract("friendship"))
    }

    /**
     * The server upper-cases before it reads a code, and so does this: a letter whose upper case is two
     * letters counts as both, on both sides.
     */
    @Test
    fun aLetterThatUpperCasesToTwoIsReadAsTheServerReadsIt() {
        assertEquals("K7QM2XRDSS", FriendsInviteCode.normalized("K7QM2XRDß"))
        assertNull("eleven characters once upper-cased", FriendsInviteCode.normalized("K7QM2XRD4ß"))
    }

    /** `text` with the whitespace outside its strings taken out: what the indenter may add and nothing else. */
    private fun withoutLayout(text: String): String {
        val out = StringBuilder()
        var inString = false
        var escaped = false
        text.codePoints().forEach { cp ->
            if (inString) {
                out.appendCodePoint(cp)
                when {
                    escaped -> escaped = false
                    cp == '\\'.code -> escaped = true
                    cp == '"'.code -> inString = false
                }
            } else if (cp == '"'.code) {
                inString = true
                out.appendCodePoint(cp)
            } else if (cp != ' '.code && cp != '\n'.code && cp != '\t'.code && cp != '\r'.code) {
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /**
     * An answer is laid out to be read without being parsed: every literal is copied as it came, so a
     * number keeps the digits the server wrote and the members keep the server's order.
     */
    @Test
    fun anAnswerIsIndentedWithEveryLiteralAsItCame() {
        val wire = """{"name":"a\"b{,}","strain":38.6,"e":1e-7,"days":[[1791539880,64]],"n":null}"""
        val laid = FriendsWire.indented(wire)
        assertEquals(
            "{\n  \"name\": \"a\\\"b{,}\",\n  \"strain\": 38.6,\n  \"e\": 1e-7,\n  \"days\": [\n    [\n" +
                "      1791539880,\n      64\n    ]\n  ],\n  \"n\": null\n}",
            laid,
        )
        assertEquals("byte for byte, layout aside", wire, withoutLayout(laid))
    }

    /** Text that is not an answer of the server's still comes out, with nothing lost. */
    @Test
    fun indentingWhatIsNotAnAnswerLosesNothing() {
        for (text in listOf("]}", "<html>busy</html>", "{\"open\":", "\"unterminated", "}{")) {
            assertEquals(text, withoutLayout(text), withoutLayout(FriendsWire.indented(text)))
        }
        assertEquals("38.6", FriendsWire.indented("38.6"))
        assertEquals("", FriendsWire.indented(""))
    }

    // MARK: - The Swift twin's own answers

    /** Fails with every line that differs, so one run shows the whole distance between the two sides. */
    private fun assertSection(name: String, answer: (String) -> String?) {
        val differing = FriendsWireOracle.section(name).mapNotNull { expected ->
            val input = FriendsWireOracle.input(expected)
            val actual = FriendsWireOracle.line(input, answer(input))
            if (actual == expected) null else "swift:  $expected\nkotlin: $actual"
        }
        assertTrue("$name differs from the Swift twin:\n" + differing.joinToString("\n"), differing.isEmpty())
        assertTrue("$name has lines", FriendsWireOracle.section(name).isNotEmpty())
    }

    @Test
    fun aTypedCodeIsReadAsTheSwiftTwinReadsIt() = assertSection("normalized", FriendsInviteCode::normalized)

    /**
     * Where the two sides are known to answer differently, and why. Each of these addresses has a host
     * with a character outside ASCII. Foundation maps such a name through the system's IDNA tables
     * (a full-width or circled letter becomes the plain one, a soft hyphen is dropped) and then reads
     * the address; this side carries no such tables and reads none of them. The app never makes such an
     * address: a server address is ASCII by `FriendsServerUrl`.
     */
    private val hostsOutsideAscii = setOf(
        "https://\\u{43F}\\u{440}\\u{438}\\u{43C}\\u{435}\\u{440}.\\u{440}\\u{444}/i/K7QM2-XRD4P",
        "https://ex\\u{E4}mple.org/i/K7QM2-XRD4P",
        "https://\\u{1F600}.example/i/K7QM2-XRD4P",
        "https://\\u{FF58}/i/K7QM2-XRD4P",
        "https://a\\u{301}/i/K7QM2-XRD4P",
        "https://a\\u{AD}b/i/K7QM2-XRD4P",
        "https://a\\u{2060}b/i/K7QM2-XRD4P",
        "https://a\\u{FEFF}b/i/K7QM2-XRD4P",
        "https://\\u{E9}/i/K7QM2-XRD4P",
        "https://\\u{DF}/i/K7QM2-XRD4P",
        "https://\\u{4E2D}\\u{6587}.\\u{4E2D}\\u{56FD}/i/K7QM2-XRD4P",
        "https://\\u{E9}/%69/K7QM2-XRD4P",
        "renoop://FRIEND\\u{17F}/add?c=K7QM2-XRD4P",
        "renoop://\\u{FF46}riends/add?c=K7QM2-XRD4P",
        "renoop://fri\\u{AD}ends/add?c=K7QM2-XRD4P",
        "renoop://\\u{24D5}riends/add?c=K7QM2-XRD4P",
    )

    @Test
    fun aCodeIsTakenOutOfTextAsTheSwiftTwinTakesIt() {
        val lines = FriendsWireOracle.section("extract")
        val differing = lines.filter { expected ->
            val input = FriendsWireOracle.input(expected)
            FriendsWireOracle.line(input, FriendsInviteCode.extract(input)) != expected
        }
        assertEquals(
            "the lines that differ from the Swift twin",
            hostsOutsideAscii.map { "$it => K7QM2XRD4P" }.toSet(),
            differing.toSet(),
        )
        // On those the Swift side finds the code and this side finds none: it never finds another one.
        for (line in differing) assertNull(line, FriendsInviteCode.extract(FriendsWireOracle.input(line)))
        assertTrue(lines.size > 700)
    }

    @Test
    fun aCodeIsShownAsTheSwiftTwinShowsIt() = assertSection("display", FriendsInviteCode::display)

    @Test
    fun anInvitePagesAddressIsTheSwiftTwins() = assertSection("pageLink") { input ->
        val (server, code) = input.split(" ")
        FriendsInviteCode.pageLink(server, code)
    }

    @Test
    fun whatIsSignedIsWhatTheSwiftTwinSigns() = assertSection("signingString") { input ->
        val (method, target, time, nonce, hash) = input.split(" ")
        FriendsWire.signingString(method, target, time.toLong(), nonce, hash)
    }

    /** The first line of each pair: the text a handle is the SHA-256 of. `FriendsStrapTest` reads the second. */
    @Test
    fun theTextAHandleIsMadeFromIsTheSwiftTwins() {
        val lines = FriendsWireOracle.section("strapHandle")
        assertTrue(lines.size >= 16 && lines.size % 2 == 0)
        for (line in lines.filterIndexed { index, _ -> index % 2 == 0 }) {
            val id = FriendsWireOracle.input(line)
            assertEquals(line, FriendsWireOracle.line(id, FriendsWire.strapHandleInput(id)))
        }
    }

    /** What tells an uploaded day from a changed one, and a request's body from another. */
    @Test
    fun aFingerprintIsTheSwiftTwins() = assertSection("sha256", FriendsDayPayload::fingerprint)

    @Test
    fun anAnswerIsLaidOutAsTheSwiftTwinLaysItOut() = assertSection("indented") { FriendsWire.indented(it) }

    /** The escaping the oracle is written in reads back what it wrote. */
    @Test
    fun theOraclesEscapingRoundTrips() {
        for (text in listOf("", " ", "  ", " a ", "a\\b", "line\nend\r\n", "\tАнна 🙂", "$", "\u0000\u007f")) {
            assertEquals(text, FriendsWireOracle.unescape(FriendsWireOracle.escape(text)))
        }
        assertEquals("\\u{20}a b\\u{20}", FriendsWireOracle.escape(" a b "))
    }
}
