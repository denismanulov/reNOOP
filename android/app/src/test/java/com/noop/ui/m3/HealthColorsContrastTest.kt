package com.noop.ui.m3

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * CR-2: a data hue used as TEXT reads at 4.5:1 or better on every surface it sits on, and a hue used only as
 * a mark (a ring, a bar, a dot) at 3:1, in light and in dark. The ratios are computed (WCAG 2 relative
 * luminance), not judged by eye: before this the light Charge green was 3.7:1 on a card and three of the
 * heart-rate zone labels were near 3:1.
 *
 * The surfaces: the reNOOP seed scheme (Android 8 to 11, `Theme.kt`) and Material You's neutral tones, which
 * are what a wallpaper scheme uses for the same roles whatever its hue (surface = tone 98 / 6, a card =
 * surfaceContainerLow = tone 96 / 10, the mini-player = surfaceContainerHigh = tone 92 / 17).
 */
class HealthColorsContrastTest {

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private class Surfaces(val page: List<Color>, val card: List<Color>, val high: List<Color>)

    private val lightSurfaces = Surfaces(
        page = listOf(Color(0xFFF5FBF5), Color(0xFFF9F9F9)),
        card = listOf(Color(0xFFEFF5EF), Color(0xFFF3F3F3)),
        high = listOf(Color(0xFFE4EAE3), Color(0xFFE8E8E8)),
    )
    private val darkSurfaces = Surfaces(
        page = listOf(Color(0xFF0F1511), Color(0xFF131313)),
        card = listOf(Color(0xFF171D1A), Color(0xFF1B1B1B)),
        high = listOf(Color(0xFF252B28), Color(0xFF2A2A2A)),
    )

    private fun check(mode: String, c: HealthColors, s: Surfaces) {
        val failures = ArrayList<String>()
        fun need(name: String, hue: Color, on: List<Color>, min: Double) {
            val worst = on.minOf { ratio(hue, it) }
            if (worst < min) failures += "$name %.2f < %.1f".format(worst, min)
        }
        val body = s.page + s.card
        // Text: card titles, figures, captions, zone labels.
        mapOf(
            "charge" to c.charge, "effort" to c.effort, "rest" to c.rest, "heart" to c.heart,
            "respiratory" to c.respiratory, "oxygen" to c.oxygen, "temperature" to c.temperature, "body" to c.body,
            "mind" to c.mind, "nutrition" to c.nutrition, "activity" to c.activity, "sleep" to c.sleep,
            "vitalsTypical" to c.vitalsTypical, "vitalsOutlier" to c.vitalsOutlier, "fitness" to c.fitness,
            "paused" to c.paused, "positive" to c.positive, "warning" to c.warning,
        ).forEach { (name, hue) -> need(name, hue, body, 4.5) }
        c.zones.forEachIndexed { i, hue -> need("zone${i + 1}", hue, body, 4.5) }
        // The mini-player's clock is Fitness, Rest or Paused on surfaceContainerHigh.
        need("fitness on the mini-player", c.fitness, s.high, 4.5)
        need("rest on the mini-player", c.rest, s.high, 4.5)
        need("paused on the mini-player", c.paused, s.high, 4.5)
        // Text on the fitness tile.
        need("onFitnessContainer", c.onFitnessContainer, listOf(c.fitnessContainer), 4.5)
        need("fitness on its container", c.fitness, listOf(c.fitnessContainer), 4.5)
        // Marks: stages, bands.
        mapOf(
            "stageAwake" to c.stageAwake, "stageRem" to c.stageRem, "stageCore" to c.stageCore, "stageDeep" to c.stageDeep,
            "bandLow" to c.bandLow, "bandMid" to c.bandMid, "bandHigh" to c.bandHigh,
        ).forEach { (name, hue) -> need(name, hue, body, 3.0) }
        assertTrue("$mode hues under their contrast floor: $failures", failures.isEmpty())
    }

    @Test fun lightHuesReadOnTheirSurfaces() = check("light", LightHealthColors, lightSurfaces)

    @Test fun darkHuesReadOnTheirSurfaces() = check("dark", DarkHealthColors, darkSurfaces)

    @Test fun tonalIconsReadOnTheirCircles() {
        val failures = ArrayList<String>()
        for ((mode, icons) in listOf("light" to LightTonalIcons, "dark" to DarkTonalIcons)) {
            listOf(icons.red, icons.blue, icons.grey, icons.purple, icons.green, icons.orange, icons.teal, icons.pink)
                .forEachIndexed { i, pair ->
                    val r = ratio(pair.content, pair.container)
                    if (r < 3.0) failures += "$mode icon $i %.2f".format(r)
                }
        }
        assertTrue("tonal icon glyphs under 3:1 on their circle: $failures", failures.isEmpty())
    }
}
