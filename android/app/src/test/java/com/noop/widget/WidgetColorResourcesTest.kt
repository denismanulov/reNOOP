package com.noop.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.noop.ui.m3.DarkHealthColors
import com.noop.ui.m3.HealthColors
import com.noop.ui.m3.LightHealthColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The widgets' data hues are XML colour resources (the launcher resolves them, so a widget follows the
 * system theme without the app), while the app's own are `HealthColors`. That is the same hue written in
 * two places, and a Charge ring that is one green on the home screen and another in the Summary would be
 * two readouts of one fact disagreeing. This reads the resource files and pins each to its token, in
 * light and in dark.
 *
 * It also pins the contrast the audit asked of widget text (CR-9: at least 4.5:1) for the colours the
 * app ships below Android 12; on 12+ the pairs are the system's own tones.
 */
class WidgetColorResourcesTest {

    private fun colors(dir: String): Map<String, Int> {
        var root = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(4) {
            val f = File(root, "android/app/src/main/res/$dir/colors.xml")
            if (f.isFile) {
                return Regex("""<color name="([a-z0-9_]+)">#([0-9A-Fa-f]{8})</color>""")
                    .findAll(f.readText())
                    .associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }
            }
            root = root.parentFile ?: root
        }
        error("$dir/colors.xml not found: this test must not pass by default")
    }

    private fun assertHues(resources: Map<String, Int>, tokens: HealthColors) {
        fun check(name: String, token: Color) =
            assertEquals("$name must be the HealthColors token", token.toArgb(), resources.getValue(name))
        check("widget_charge", tokens.charge)
        check("widget_effort", tokens.effort)
        check("widget_rest", tokens.rest)
        check("widget_heart", tokens.heart)
        // Day Stress is a mental-wellbeing metric: its page draws in that category's hue.
        check("widget_stress", tokens.mind)
        check("notification_workout", tokens.fitness)
    }

    @Test
    fun lightHuesAreTheAppsLightTokens() = assertHues(colors("values"), LightHealthColors)

    @Test
    fun darkHuesAreTheAppsDarkTokens() = assertHues(colors("values-night"), DarkHealthColors)

    @Test
    fun everyWidgetColourHasADarkTwin() {
        val light = colors("values").keys.filter { it.startsWith("widget_") || it.startsWith("notification_") }
        assertTrue(light.isNotEmpty())
        assertEquals(light.sorted(), colors("values-night").keys.sorted())
    }

    // MARK: - Contrast (WCAG 2.x relative luminance)

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertReadable(c: Map<String, Int>) {
        val surface = c.getValue("widget_surface")
        for (text in listOf("widget_on_surface", "widget_on_surface_variant", "widget_primary")) {
            val ratio = contrast(c.getValue(text), surface)
            assertTrue("$text on the widget surface is $ratio:1, below 4.5:1", ratio >= 4.5)
        }
        val chip = contrast(c.getValue("widget_on_chip"), c.getValue("widget_chip"))
        assertTrue("chip text is $chip:1, below 4.5:1", chip >= 4.5)
        // Graphics (a ring's arc, a chart line) need 3:1 against the card.
        for (hue in listOf("widget_charge", "widget_effort", "widget_rest", "widget_heart", "widget_stress")) {
            val ratio = contrast(c.getValue(hue), surface)
            assertTrue("$hue on the widget surface is $ratio:1, below 3:1", ratio >= 3.0)
        }
    }

    @Test
    fun lightWidgetTextMeetsContrast() = assertReadable(colors("values"))

    @Test
    fun darkWidgetTextMeetsContrast() = assertReadable(colors("values-night"))
}
