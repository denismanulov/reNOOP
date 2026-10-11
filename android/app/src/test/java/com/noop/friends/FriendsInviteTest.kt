package com.noop.friends

import com.noop.ui.friends.FriendsQrCode
import com.noop.ui.friends.friendsInviteCodeOfLink
import com.noop.ui.friends.friendsMoment
import com.noop.ui.friends.spacedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.util.Locale

/**
 * Handing an invite over and taking one in: the QR, the message the app shares in every language, the
 * link that opens the app, and the words of the pages that wait. Nothing here needs a screen.
 */
class FriendsInviteTest {

    // MARK: - The QR

    @Test
    fun anInviteLinkBecomesASquareQrCode() {
        val qr = FriendsQrCode.of("https://renoop.duckdns.org/i/K7QM2-XRD4P")!!
        assertTrue("a QR code is 21 modules a side at the least", qr.side >= 21)
        assertEquals("21 plus a multiple of four", 1, qr.side % 4)
        // The three finder patterns: a dark 7 by 7 ring in three corners, and none in the fourth.
        for ((x, y) in listOf(0 to 0, qr.side - 7 to 0, 0 to qr.side - 7)) {
            for (i in 0 until 7) {
                assertTrue(qr.isDark(x + i, y))
                assertTrue(qr.isDark(x + i, y + 6))
                assertTrue(qr.isDark(x, y + i))
                assertTrue(qr.isDark(x + 6, y + i))
            }
            assertFalse("the ring's light inside", qr.isDark(x + 1, y + 1))
            assertTrue("and its dark middle", qr.isDark(x + 3, y + 3))
        }
        val same = FriendsQrCode.of("https://renoop.duckdns.org/i/K7QM2-XRD4P")!!
        val other = FriendsQrCode.of("https://renoop.duckdns.org/i/AAAAA-BBBBB")!!
        fun cells(code: FriendsQrCode) = (0 until code.side * code.side).map { code.isDark(it % code.side, it / code.side) }
        assertEquals("the same link is the same code", cells(qr), cells(same))
        assertTrue("another link is another code", qr.side != other.side || cells(qr) != cells(other))
        assertNull("text too long for any QR code is none", FriendsQrCode.of("x".repeat(5_000)))
    }

    // MARK: - The message the app shares

    /** `friends_invite_share_text` of one locale's strings file, with Android's escapes read. */
    private fun shareText(folder: String): String {
        val file = listOf(File("src/main/res/$folder/strings.xml"), File("app/src/main/res/$folder/strings.xml")).first { it.isFile }
        val raw = Regex("""<string name="friends_invite_share_text">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText(Charsets.UTF_8))!!.groupValues[1]
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 1 < raw.length) {
                out.append(if (raw[i + 1] == 'n') '\n' else raw[i + 1])
                i += 2
            } else {
                out.append(raw[i])
                i++
            }
        }
        return out.toString().replace("&amp;", "&").replace("&lt;", "<")
    }

    private val locales = listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-pl", "values-pt-rPT", "values-ru", "values-zh")

    /**
     * A friend copies the whole message and pastes it into the code field: whatever language it was
     * shared in, the code is found, by the link first and else by the code written beside it. The link
     * and the code must each stand as a word of their own, which a translation can break (a full stop of
     * another script set against the code is part of the word). The Swift twin reads the same nine
     * messages the same way: they are lines of [FriendsWireOracle].
     */
    @Test
    fun theSharedMessageOfEveryLanguageIsOneAPasteReadsBack() {
        val oracle = FriendsWireOracle.section("extract").associate {
            FriendsWireOracle.input(it) to it.substringAfterLast(" => ")
        }
        for (folder in locales) {
            val template = shareText(folder)
            assertTrue(folder, template.contains("%1\$s") && template.contains("%2\$s") && template.contains("\n"))
            val whole = template.replace("%1\$s", "https://renoop.duckdns.org/i/K7QM2-XRD4P").replace("%2\$s", "K7QM2-XRD4P")
            val codeOnly = template.substringAfter("\n").replace("%2\$s", "K7QM2-XRD4P")
            val mixed = template.replace("%1\$s", "https://renoop.duckdns.org/i/AAAAA-BBBBB").replace("%2\$s", "K7QM2-XRD4P")
            assertEquals(folder, "K7QM2XRD4P", FriendsInviteCode.extract(whole))
            assertEquals("$folder: the code alone, among the words around it", "K7QM2XRD4P", FriendsInviteCode.extract(codeOnly))
            assertEquals("$folder: the link is read before the code beside it", "AAAAABBBBB", FriendsInviteCode.extract(mixed))
            // What ships is what the Swift twin was asked about.
            assertEquals("$folder is not the message the oracle read", "K7QM2XRD4P", oracle[whole])
            assertEquals(folder, "K7QM2XRD4P", oracle[codeOnly])
            assertEquals(folder, "AAAAABBBBB", oracle[mixed])
        }
    }

    // MARK: - The link that opens the app

    @Test
    fun onlyAnInviteLinkOfTheAppsOwnCarriesACode() {
        assertEquals("K7QM2XRD4P", friendsInviteCodeOfLink("renoop://friends/add?c=K7QM2-XRD4P"))
        assertEquals("K7QM2XRD4P", friendsInviteCodeOfLink("RENOOP://friends/add/?c=k7qm2xrd4p"))
        assertNull(friendsInviteCodeOfLink("renoop://friends/add?c=nope"))
        assertNull(friendsInviteCodeOfLink("renoop://friends/add"))
        assertNull(friendsInviteCodeOfLink("renoop://today"))
        assertNull("another app's scheme is not ours, whatever it carries", friendsInviteCodeOfLink("noop://friends/add?c=K7QM2-XRD4P"))
        assertNull("nor is a web address: that one is opened in a browser", friendsInviteCodeOfLink("https://renoop.duckdns.org/i/K7QM2-XRD4P"))
        assertNull(friendsInviteCodeOfLink("K7QM2-XRD4P"))
        assertNull(friendsInviteCodeOfLink(null))
    }

    // MARK: - The pages that wait

    @Test
    fun aCodeIsReadOutInTwoGroups() {
        assertEquals("481 902", spacedCode("481902"))
        assertEquals("anything that is not six digits is left alone", "12", spacedCode("12"))
    }

    /**
     * A moment days away is named by its date. A weekday and a time alone would read as today for the
     * end of a seven-day wait, which falls on the weekday it began on.
     */
    @Test
    fun aMomentDaysAwayIsNamedByItsDate() {
        // 15 October 2026, 22:53:20 UTC, a Thursday.
        val english = friendsMoment(1_792_104_800L, Locale.UK, ZoneOffset.UTC)
        assertTrue(english, english.contains("15"))
        assertTrue(english, english.contains("October"))
        assertTrue(english, english.contains("22:53"))
        assertFalse(english, english.contains("Thursday"))
        val russian = friendsMoment(1_792_104_800L, Locale("ru", "RU"), ZoneOffset.UTC)
        assertTrue(russian, russian.contains("15 октября"))
        assertTrue(russian, russian.contains("22:53"))
        assertFalse(russian, russian.contains("четверг"))
        assertNotNull(friendsMoment(1_792_104_800L, Locale.SIMPLIFIED_CHINESE, ZoneOffset.UTC))
    }
}
