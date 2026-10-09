package com.noop.ui.settings

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Health Details values and wheels (pure, unit-tested)
//
// The profile is stored in SI; these turn it into the row values and the wheel options a reader sees in
// their own units and locale ("75,5 kg" in Russian, "5′ 10″" in imperial), and map a wheel index back to
// the SI value to store. Ranges match the iOS wheels (ProfileHeightPicker, ProfileWeightPicker, the waist
// and max-heart-rate pickers) and the ProfileStore clamps.

internal object HealthDetailsFormat {
    const val CM_PER_INCH = 2.54
    const val LB_PER_KG = 2.20462262185

    /** "12 Mar 1990 (36)" in the reader's date style. */
    fun birth(dob: LocalDate, age: Int, locale: Locale): String =
        "${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(dob)} ($age)"

    /** A weight with at most one decimal, in the reader's number format. */
    fun decimal(value: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }.format(value)

    fun height(cm: Double, imperial: Boolean, cmUnit: String): String {
        if (imperial) {
            val inches = (cm / CM_PER_INCH).roundToInt()
            return "${inches / 12}′ ${inches % 12}″"
        }
        return "${cm.roundToInt()} $cmUnit"
    }

    fun weight(kg: Double, imperial: Boolean, locale: Locale, kgUnit: String, lbUnit: String): String =
        if (imperial) "${(kg * LB_PER_KG).roundToInt()} $lbUnit"
        else "${decimal((kg * 2).roundToInt() / 2.0, locale)} $kgUnit"

    /** Null when no waist is set (0 is the "unset" sentinel). */
    fun waist(cm: Double, imperial: Boolean, cmUnit: String, inUnit: String): String? {
        if (cm <= 0.0) return null
        return if (imperial) "${(cm / CM_PER_INCH).roundToInt()} $inUnit" else "${cm.roundToInt()} $cmUnit"
    }

    // --- Wheels: options in display order, the index of the stored value, and the SI value of an index ---

    /** Metric: 120…230 cm. Imperial: 3′ 11″ (47 in) … 7′ 7″ (91 in). */
    fun heightOptions(imperial: Boolean, cmUnit: String): List<String> =
        if (imperial) (47..91).map { "${it / 12}′ ${it % 12}″" } else (120..230).map { "$it $cmUnit" }

    fun heightIndex(cm: Double, imperial: Boolean): Int =
        if (imperial) ((cm / CM_PER_INCH).roundToInt() - 47).coerceIn(0, 44) else (cm.roundToInt() - 120).coerceIn(0, 110)

    fun heightCm(index: Int, imperial: Boolean): Double =
        if (imperial) (47 + index) * CM_PER_INCH else (120 + index).toDouble()

    /** Metric: 30…250 kg in half kilograms. Imperial: 66…551 lb. */
    fun weightOptions(imperial: Boolean, locale: Locale, kgUnit: String, lbUnit: String): List<String> =
        if (imperial) (66..551).map { "$it $lbUnit" } else (60..500).map { "${decimal(it / 2.0, locale)} $kgUnit" }

    fun weightIndex(kg: Double, imperial: Boolean): Int =
        if (imperial) ((kg * LB_PER_KG).roundToInt() - 66).coerceIn(0, 485) else ((kg * 2).roundToInt() - 60).coerceIn(0, 440)

    fun weightKg(index: Int, imperial: Boolean): Double =
        if (imperial) (66 + index) / LB_PER_KG else (60 + index) / 2.0

    /** "Not set" first, then 60…160 cm or 24…63 in. Index 0 stores 0 (unset). */
    fun waistOptions(imperial: Boolean, notSet: String, cmUnit: String, inUnit: String): List<String> =
        listOf(notSet) + if (imperial) (24..63).map { "$it $inUnit" } else (60..160).map { "$it $cmUnit" }

    fun waistIndex(cm: Double, imperial: Boolean): Int {
        if (cm <= 0.0) return 0
        return if (imperial) ((cm / CM_PER_INCH).roundToInt() - 24).coerceIn(0, 39) + 1 else (cm.roundToInt() - 60).coerceIn(0, 100) + 1
    }

    fun waistCm(index: Int, imperial: Boolean): Double = when {
        index <= 0 -> 0.0
        imperial -> (24 + index - 1) * CM_PER_INCH
        else -> (60 + index - 1).toDouble()
    }

    /** "Auto · 185 bpm" first (stores 0), then 100…230 bpm. */
    fun hrMaxOptions(autoLabel: String, bpmUnit: String): List<String> = listOf(autoLabel) + (100..230).map { "$it $bpmUnit" }

    fun hrMaxIndex(override: Int): Int = if (override <= 0) 0 else (override - 100).coerceIn(0, 130) + 1

    fun hrMaxOverride(index: Int): Int = if (index <= 0) 0 else 100 + index - 1

    /** A zone's range: "134–144 bpm", the top zone "165+ bpm". [upper] is the next zone's start. */
    fun zoneRange(number: Int, lower: Double, upper: Double, bpmUnit: String): String {
        val lo = lower.roundToInt()
        if (number >= 5) return "$lo+ $bpmUnit"
        return "$lo–${upper.roundToInt() - 1} $bpmUnit"
    }
}
