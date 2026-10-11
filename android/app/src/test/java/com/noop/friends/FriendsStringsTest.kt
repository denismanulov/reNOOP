package com.noop.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words of the Friends tab, read from the resource files themselves: every string the tab reads is
 * in all nine languages with the same arguments, nothing is kept that no screen reads, and the Russian
 * addresses the wearer as the rest of the app does.
 */
class FriendsStringsTest {
    private val locales = listOf("values-de", "values-es", "values-fr", "values-it", "values-pl", "values-pt-rPT", "values-ru", "values-zh")

    private fun module(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }

    private class Entry(val translatable: Boolean, val text: String)

    /** The Friends strings and plurals of one locale, by name. A plural's text is all its forms together. */
    private fun friends(folder: String): Map<String, Entry> {
        val text = module("src/main/res/$folder/strings.xml").readText(Charsets.UTF_8)
        val out = LinkedHashMap<String, Entry>()
        Regex("""<string name="((?:friends_|nav_friends)[a-z0-9_]*)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).forEach { out[it.groupValues[1]] = Entry("translatable=\"false\"" !in it.groupValues[2], it.groupValues[3]) }
        Regex("""<plurals name="(friends_[a-z0-9_]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).forEach { out[it.groupValues[1]] = Entry(true, it.groupValues[2]) }
        return out
    }

    /** The arguments a text takes, each once: "%1$s", "%2$d". */
    private fun arguments(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSortedSet()

    @Test
    fun everyFriendsStringIsInAllNineLanguagesWithTheSameArguments() {
        val english = friends("values").filterValues { it.translatable }
        assertTrue("the default locale's Friends strings were not found", english.size > 150)
        for (folder in locales) {
            val theirs = friends(folder)
            assertEquals("$folder lacks", emptyList<String>(), english.keys.filter { it !in theirs })
            for ((key, entry) in english) {
                assertEquals("$folder $key", arguments(entry.text), arguments(theirs.getValue(key).text))
            }
        }
    }

    @Test
    fun noFriendsStringIsKeptThatNoScreenReads() {
        val read = HashSet<String>()
        module("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            Regex("""R\.(?:string|plurals)\.(friends_[a-z0-9_]+)""").findAll(file.readText()).forEach { read += it.groupValues[1] }
        }
        for (folder in listOf("values") + locales) {
            val defined = friends(folder).keys.filter { it.startsWith("friends_") }
            assertEquals("$folder keeps", emptyList<String>(), defined.filter { it !in read })
        }
        assertEquals("read but not defined", emptyList<String>(), read.filter { it !in friends("values") }.sorted())
    }

    /**
     * The app says «вы» to the wearer. One Friends string says «ты», and is not addressed to the wearer:
     * the message a wearer sends to a friend with an invite.
     */
    @Test
    fun russianAddressesTheWearerFormally() {
        // The pronoun in its forms, a verb in the second person singular, and the imperatives the tab once used.
        val informal = Regex(
            """(?<![а-яё])(ты|тебя|тебе|тобой|твой|твоя|твоё|твои|твоего|твоей|твою|попробуй|меняй|включи|добавь|введи)(?![а-яё])""" +
                """|(?<![а-яё])(?!лишь)[а-яё]+(ешь|ёшь|ишь)(?![а-яё])""",
            RegexOption.IGNORE_CASE,
        )
        val found = friends("values-ru").filter { (key, entry) -> key != "friends_invite_share_text" && informal.containsMatchIn(entry.text) }
        assertEquals(emptyList<String>(), found.keys.toList())
        assertTrue(informal.containsMatchIn("Ты не делишься сном."))
        assertTrue(informal.containsMatchIn("Так тебя видят друзья"))
    }
}
