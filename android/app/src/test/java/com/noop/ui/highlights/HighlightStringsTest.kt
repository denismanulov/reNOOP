package com.noop.ui.highlights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every Highlights sentence, in every language, carries only slots [HighlightCopy] fills (a slot nobody
 * fills would be printed raw), every language has every entry, and each counted sentence names its number.
 * Twin of the Swift catalogue test in StrandTests/HealthHighlightTests.swift.
 */
class HighlightStringsTest {

    private fun resDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(File(userDir, "src/main/res"), File(userDir, "android/app/src/main/res")).first { it.isDirectory }
    }

    private val entry = Regex("""<(string|plurals) name="(hl_[a-z0-9_]+)"[^>]*>([\s\S]*?)</\1>""")
    private val slot = Regex("@([A-Z_0-9]+)@")

    /** Name to text (a plural's items joined) for one strings.xml. */
    private fun entries(file: File): Map<String, String> =
        entry.findAll(file.readText()).associate { it.groupValues[2] to it.groupValues[3] }

    @Test
    fun everyLanguageCarriesOnlyKnownSlotsAndEveryEntry() {
        val folders = resDir().listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").isFile }!!
        val english = entries(File(resDir(), "values/strings.xml"))
        assertTrue("no Highlights strings found", english.size > 80)
        var checked = 0
        for (folder in folders) {
            val strings = entries(File(folder, "strings.xml"))
            if (strings.isEmpty()) continue
            assertEquals("${folder.name} is missing Highlights entries", english.keys, strings.keys)
            for ((name, text) in strings) {
                for (match in slot.findAll(text)) {
                    assertTrue("${folder.name}/$name names ${match.value}", match.groupValues[1] in HighlightCopy.TOKENS)
                    checked++
                }
                assertTrue("${folder.name}/$name has a stray @", text.replace(slot, "").replace("\\@", "").count { it == '@' } == 0)
                if (name.startsWith("hl_days_")) {
                    Regex("<item[^>]*>([^<]*)</item>").findAll(text).forEach {
                        assertTrue("${folder.name}/$name item lacks its number", "%d" in it.groupValues[1])
                    }
                }
                if (folder.name != "values") assertTrue("${folder.name}/$name kept English", !text.contains(" days "))
            }
        }
        assertTrue(checked > 300)
    }
}
