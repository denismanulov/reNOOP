package com.noop.ui.m3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// MARK: - Health colours (Material 3 port of Denis's iOS 26 redesign)
//
// The Material You scheme (wallpaper-derived on Android 12+, the reNOOP mint seed below that) owns every
// surface, text and control colour. What it cannot own is DATA colour: a Charge ring, a heart-rate line
// or a deep-sleep block must read the same whatever the wallpaper is, so those hues are fixed here, in a
// light and a dark variant tuned for Material tonal surfaces (tone ~40 on light, ~80 on dark, so text in
// the hue keeps 4.5:1 against surfaceContainerLow). Twin of the iOS `healthHeart` / `summaryChargeRing`
// family in StrandPalette.swift by meaning, not by value: iOS copies Apple's system hues, Android uses
// the reNOOP ring palette the port settled on (Charge green, Effort blue, Rest violet).

/** Every fixed data hue the redesigned screens draw with. One instance per light / dark. */
@Immutable
data class HealthColors(
    // Score rings.
    val charge: Color,
    val effort: Color,
    val rest: Color,
    // Health categories (All Metrics sections, card titles, chart marks).
    val heart: Color,
    val respiratory: Color,
    val oxygen: Color,
    val temperature: Color,
    val body: Color,
    val mind: Color,
    val nutrition: Color,
    val activity: Color,
    val sleep: Color,
    // Sleep stages.
    val stageAwake: Color,
    val stageRem: Color,
    val stageCore: Color,
    val stageDeep: Color,
    // Sleep vitals.
    val vitalsTypical: Color,
    val vitalsOutlier: Color,
    // Fitness (workout cards, recording clock).
    val fitness: Color,
    val fitnessContainer: Color,
    val onFitnessContainer: Color,
    val paused: Color,
    // Heart-rate zones 1..5.
    val zones: List<Color>,
    // Trend direction and verdict colours that must not follow the wallpaper.
    val positive: Color,
    val warning: Color,
    // A banded score's three bands (Charge bars on its metric page: below 50, below 70, above).
    val bandLow: Color,
    val bandMid: Color,
    val bandHigh: Color,
) {
    /** Zone colour for a 1-based zone index, clamped. */
    fun zone(zone: Int): Color = zones[(zone - 1).coerceIn(0, zones.lastIndex)]
}

// CR-2: every hue that carries TEXT in the light set reads at 4.5:1 or better on the surfaces it sits on (the
// page, a card, and for the fitness / rest / paused clocks the mini-player), and every mark at 3:1; the dark
// set clears both with room to spare. HealthColorsContrastTest computes the ratios, so a new hue or a
// lightened one fails there rather than on a reader's screen.
val LightHealthColors = HealthColors(
    charge = Color(0xFF187E4F),
    effort = Color(0xFF286ADA),
    rest = Color(0xFF7848DA),
    heart = Color(0xFFC8356B),
    respiratory = Color(0xFF007C76),
    oxygen = Color(0xFF1476A4),
    temperature = Color(0xFFA25F00),
    body = Color(0xFF9C3BD0),
    mind = Color(0xFF00788A),
    nutrition = Color(0xFF2E7D32),
    activity = Color(0xFFC93C15),
    sleep = Color(0xFF5B4FD8),
    stageAwake = Color(0xFFE4572E),
    stageRem = Color(0xFF1D97C2),
    stageCore = Color(0xFF2F6FDB),
    stageDeep = Color(0xFF3F33A6),
    vitalsTypical = Color(0xFF2570C3),
    vitalsOutlier = Color(0xFFC329A5),
    fitness = Color(0xFF2B752F),
    fitnessContainer = Color(0xFFC8F0C4),
    onFitnessContainer = Color(0xFF00210A),
    paused = Color(0xFF836300),
    zones = listOf(
        Color(0xFF296FC5), Color(0xFF007D70), Color(0xFF8C6900), Color(0xFFAE5719), Color(0xFFC62828),
    ),
    positive = Color(0xFF187E4F),
    warning = Color(0xFF9C6125),
    bandLow = Color(0xFFC62828),
    bandMid = Color(0xFFB08400),
    bandHigh = Color(0xFF187E4F),
)

val DarkHealthColors = HealthColors(
    charge = Color(0xFF4FDC9A),
    effort = Color(0xFF8AB4FF),
    rest = Color(0xFFC5A6FF),
    heart = Color(0xFFFF8FB1),
    respiratory = Color(0xFF63E6E2),
    oxygen = Color(0xFF7DD3FF),
    temperature = Color(0xFFFFB951),
    body = Color(0xFFDDA0FF),
    mind = Color(0xFF5FD4E8),
    nutrition = Color(0xFF7EDC8A),
    activity = Color(0xFFFF8A65),
    sleep = Color(0xFFA9A2FF),
    stageAwake = Color(0xFFFF8A65),
    stageRem = Color(0xFF6FD4F5),
    stageCore = Color(0xFF6FA4FF),
    stageDeep = Color(0xFF8F84FF),
    vitalsTypical = Color(0xFF6DB6FF),
    vitalsOutlier = Color(0xFFFF7BE8),
    fitness = Color(0xFF8BDA86),
    fitnessContainer = Color(0xFF1E4D23),
    onFitnessContainer = Color(0xFFC8F0C4),
    paused = Color(0xFFFFD54F),
    zones = listOf(
        Color(0xFF8AB4FF), Color(0xFF3FD6C3), Color(0xFFFFD54F), Color(0xFFFFA15C), Color(0xFFFF6B6B),
    ),
    positive = Color(0xFF4FDC9A),
    warning = Color(0xFFF0A020),
    bandLow = Color(0xFFFF6B6B),
    bandMid = Color(0xFFFFD54F),
    bandHigh = Color(0xFF4FDC9A),
)

/** Provided by `NoopTheme`; defaults to the light set so a bare preview still draws. */
val LocalHealthColors = staticCompositionLocalOf { LightHealthColors }

/** Shorthand for the current [HealthColors]: `Health.colors.heart`. */
object Health {
    val colors: HealthColors
        @Composable @ReadOnlyComposable get() = LocalHealthColors.current
}

/**
 * A tonal container / content pair for a settings-style icon circle (Pixel Settings). Fixed hues for the
 * same reason as [HealthColors]: an icon's colour is part of how a row is found at a glance.
 */
@Immutable
data class TonalPair(val container: Color, val content: Color)

@Immutable
data class TonalIcons(
    val red: TonalPair,
    val blue: TonalPair,
    val grey: TonalPair,
    val purple: TonalPair,
    val green: TonalPair,
    val orange: TonalPair,
    val teal: TonalPair,
    val pink: TonalPair,
)

val LightTonalIcons = TonalIcons(
    red = TonalPair(Color(0xFFFFDAD6), Color(0xFF8C1D18)),
    blue = TonalPair(Color(0xFFD6E3FF), Color(0xFF0B3C8C)),
    grey = TonalPair(Color(0xFFE1E3E1), Color(0xFF3F4945)),
    purple = TonalPair(Color(0xFFEADDFF), Color(0xFF4F2D96)),
    green = TonalPair(Color(0xFFC8F0C4), Color(0xFF1E5A23)),
    orange = TonalPair(Color(0xFFFFDCC2), Color(0xFF7A3A00)),
    teal = TonalPair(Color(0xFFC2EEF0), Color(0xFF00555A)),
    pink = TonalPair(Color(0xFFFFD8EC), Color(0xFF8A1D5E)),
)

val DarkTonalIcons = TonalIcons(
    red = TonalPair(Color(0xFF5C1712), Color(0xFFFFB4AB)),
    blue = TonalPair(Color(0xFF15356B), Color(0xFFAFC6FF)),
    grey = TonalPair(Color(0xFF303632), Color(0xFFC8CFCA)),
    purple = TonalPair(Color(0xFF3B2472), Color(0xFFD5BBFF)),
    green = TonalPair(Color(0xFF1E4D23), Color(0xFFA4E59E)),
    orange = TonalPair(Color(0xFF5E2E00), Color(0xFFFFB77C)),
    teal = TonalPair(Color(0xFF0A4C50), Color(0xFF8BD7DC)),
    pink = TonalPair(Color(0xFF5E1041), Color(0xFFFFAFD8)),
)

val LocalTonalIcons = staticCompositionLocalOf { LightTonalIcons }
