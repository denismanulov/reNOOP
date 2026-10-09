package com.noop.ui.metric

import androidx.annotation.StringRes
import com.noop.R
import com.noop.analytics.SkinTempDisplay
import com.noop.analytics.VitalBands
import com.noop.ui.EffortScale
import com.noop.ui.KeyMetric
import com.noop.ui.TemperatureUnit
import com.noop.ui.UnitFormatter
import com.noop.ui.UnitSystem
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Metric page style (twin of iOS MetricHealthStyle)
//
// How one catalogue metric looks on its page: its chart (mark, scale, average line, bands), its figure
// split into number and unit, its axis labels, and the plain-language "About" text behind its ⓘ. Pure:
// every localized word comes in through a lookup, so the rules are unit-testable on the JVM.

/** How a metric's readings are drawn. */
enum class MetricMark {
    /** One bar per day (or per week / month average), from zero. */
    BARS,
    /** One hollow ring per reading, unjoined: nightly vitals. */
    DOTS,
    /** Rings joined by a line: slow-moving measures read as a trend. */
    LINE,
    /** Bars up or down from zero: a signed difference from the reader's baseline. */
    DIVERGING,
}

/** What colours a single bar when it is not simply the metric's hue. */
enum class MetricBarTint {
    /** Charge: below 50 low, below 70 mid, else high (the Charge state words' own thresholds). */
    CHARGE_BANDS,
    /** Skin-temperature deviation: warmer than baseline in the temperature hue, cooler in the oxygen hue. */
    SKIN_TEMP_SIGN,
}

/** A metric's chart: its mark, a fixed scale (widened only when a reading falls outside), the period's
 *  average across it, per-bar tint, dashed band thresholds and whether a picked mark names a state word. */
data class MetricChartSpec(
    val mark: MetricMark,
    val domain: ClosedFloatingPointRange<Double>? = null,
    val showsAverage: Boolean = false,
    val barTint: MetricBarTint? = null,
    val thresholds: List<Double> = emptyList(),
    val chargeStateWord: Boolean = false,
)

/** One run of a figure: a number (large) or a unit (small, secondary). "7 hr 12 min" is four. */
data class MetricToken(val text: String, val isUnit: Boolean)

/** The display units a reader chose. */
data class MetricUnits(
    val system: UnitSystem = UnitSystem.METRIC,
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val effortScale: EffortScale = EffortScale.HUNDRED,
)

object MetricHealthStyle {

    /** The Summary card a metric can be pinned as (rings included; the page offers only the others). */
    fun keyMetric(key: String): KeyMetric? = when (key) {
        "recovery" -> KeyMetric.CHARGE
        "strain" -> KeyMetric.EFFORT
        "sleep_performance" -> KeyMetric.REST
        "hrv" -> KeyMetric.HRV
        "rhr" -> KeyMetric.RESTING_HR
        "spo2" -> KeyMetric.BLOOD_OXYGEN
        "resp_rate" -> KeyMetric.RESPIRATORY
        "steps", "steps_est" -> KeyMetric.STEPS
        "weight" -> KeyMetric.WEIGHT
        "energy_kcal", "active_kcal" -> KeyMetric.CALORIES
        "skin_temp" -> KeyMetric.SKIN_TEMP
        else -> null
    }

    /** The Summary card the page's "Pin in Summary" toggles: pinnable, and not one of the three rings. */
    fun pinnable(key: String): KeyMetric? =
        keyMetric(key)?.takeIf { it != KeyMetric.CHARGE && it != KeyMetric.EFFORT && it != KeyMetric.REST }

    fun isSteps(key: String): Boolean = key == "steps" || key == "steps_est"

    /** A daily sum rather than a level: its picked day reads "TOTAL", not "AVERAGE". */
    fun isTotal(key: String, unit: String): Boolean = unit == "min" || unit == "kcal" || unit == "g" || isSteps(key)

    /** The chart a metric reads best as. [series] decides skin temperature's deviation-or-absolute form. */
    fun chart(key: String, unit: String, series: List<Double>): MetricChartSpec = when (key) {
        "recovery" -> MetricChartSpec(
            MetricMark.BARS, domain = 0.0..100.0, barTint = MetricBarTint.CHARGE_BANDS,
            thresholds = listOf(50.0, 70.0), chargeStateWord = true,
        )
        "strain" -> MetricChartSpec(MetricMark.BARS, domain = 0.0..100.0)
        "sleep_performance", "sleep_score", "hours_vs_needed_pct", "sleep_consistency", "restorative_pct",
        "sleep_efficiency" -> MetricChartSpec(MetricMark.BARS, domain = 0.0..100.0)
        "spo2" -> MetricChartSpec(MetricMark.DOTS, domain = 90.0..100.0)
        "hrv", "resp_rate" -> MetricChartSpec(MetricMark.DOTS, showsAverage = true)
        "rhr", "avg_hr", "max_hr" -> MetricChartSpec(MetricMark.LINE, showsAverage = true)
        "skin_temp" -> {
            // A deviation series reads as warmer / cooler than baseline; an absolute one as a trend.
            val deviation = series.isNotEmpty() && series.all { !VitalBands.isAbsoluteSkinTemp(it) }
            if (deviation) MetricChartSpec(MetricMark.DIVERGING, barTint = MetricBarTint.SKIN_TEMP_SIGN)
            else MetricChartSpec(MetricMark.LINE)
        }
        "weight", "body_fat", "lean_mass", "bmi", "vo2max", "vo2max_est", "fitness_age", "body_age", "vitality" ->
            MetricChartSpec(MetricMark.LINE)
        "mood" -> MetricChartSpec(MetricMark.DOTS, domain = 1.0..5.0)
        "stress" -> MetricChartSpec(MetricMark.BARS, domain = 0.0..(if (unit == "/3") 3.0 else 100.0))
        "steps", "steps_est", "energy_kcal", "active_kcal", "calories_in", "protein_g", "carbs_g", "fat_g" ->
            MetricChartSpec(MetricMark.BARS, showsAverage = true)
        else -> if (unit == "min") MetricChartSpec(MetricMark.BARS, showsAverage = true) else MetricChartSpec(MetricMark.DOTS)
    }

    /** The Charge state word a score carries (iOS `recoveryState`): depleted, low, moderate, primed, peak. */
    @StringRes
    fun chargeStateRes(score: Double): Int = when {
        score < 25 -> R.string.metric_state_depleted
        score < 50 -> R.string.metric_state_low
        score < 70 -> R.string.metric_state_moderate
        score < 88 -> R.string.metric_state_primed
        else -> R.string.metric_state_peak
    }

    // MARK: Figures

    /** A number with [decimals] fraction digits, grouped as [locale] groups thousands. */
    fun number(value: Double, decimals: Int, locale: Locale): String {
        val f = NumberFormat.getNumberInstance(locale)
        f.minimumFractionDigits = decimals
        f.maximumFractionDigits = decimals
        f.isGroupingUsed = true
        return f.format(value)
    }

    /**
     * [value] of metric [key] (stored in [unit] with [decimals]) as number and unit runs. [unitLabel] turns
     * a raw unit id ("ms", "bpm", "hr", "min", "steps", "kg", "lb"…) into the reader's word for it.
     */
    fun tokens(
        key: String,
        unit: String,
        decimals: Int,
        value: Double,
        units: MetricUnits,
        locale: Locale,
        unitLabel: (String) -> String,
    ): List<MetricToken> {
        if (unit == "min") {
            val total = value.roundToInt()
            val h = total / 60
            val m = total % 60
            val out = ArrayList<MetricToken>()
            if (h > 0) {
                out += MetricToken(h.toString(), false)
                out += MetricToken(unitLabel("hr"), true)
            }
            if (m > 0 || h == 0) {
                out += MetricToken(m.toString(), false)
                out += MetricToken(unitLabel("min"), true)
            }
            return out
        }
        if (key == "strain") {
            return listOf(
                MetricToken(number(UnitFormatter.effortValue(value, units.effortScale), 1, locale), false),
                MetricToken("/" + UnitFormatter.effortScaleMax(units.effortScale), true),
            )
        }
        if (unit == "°C") {
            // Skin temperature can be an absolute or a signed deviation; its formatter owns both (the sign,
            // the °F conversion), only the decimal separator follows the locale. A deviation reads as Health
            // writes one, "+0.4 °C": the sign marks the move and the header names it a deviation.
            val kind = SkinTempDisplay.kind(value)
            val fahrenheit = units.temperature == TemperatureUnit.FAHRENHEIT
            val n = SkinTempDisplay.numberString(value, kind, fahrenheit, decimals)
            val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
            return listOf(
                MetricToken(n.replace('.', separator), false),
                MetricToken(SkinTempDisplay.unitSymbol(SkinTempDisplay.Kind.ABSOLUTE, fahrenheit), true),
            )
        }
        if (isSteps(key)) {
            return listOf(MetricToken(number(value, 0, locale), false), MetricToken(unitLabel("steps"), true))
        }
        var shown = value
        var shownUnit = unit
        if (unit == "kg") {
            if (units.system == UnitSystem.IMPERIAL) shown = UnitFormatter.kgToPounds(value)
            shownUnit = UnitFormatter.massUnit(units.system)
        }
        val out = arrayListOf(MetricToken(number(shown, decimals, locale), false))
        if (shownUnit.isNotEmpty()) out += MetricToken(unitLabel(shownUnit), true)
        return out
    }

    /** The figure as one string, for lists and TalkBack. */
    fun text(tokens: List<MetricToken>): String = tokens.joinToString(" ") { it.text }

    /**
     * A y-axis tick: the number alone (whole numbers without a decimal), durations in hours once the chart
     * spans two or more, the Effort scale the reader chose, and converted mass / temperature.
     */
    fun axisLabel(
        key: String,
        unit: String,
        value: Double,
        units: MetricUnits,
        span: Double,
        locale: Locale,
        hourLabel: String,
    ): String {
        if (unit == "min" && span >= 120) return "${number(value / 60, 0, locale)} $hourLabel"
        if (key == "strain" && units.effortScale != EffortScale.HUNDRED) {
            return number(UnitFormatter.effortValue(value, units.effortScale), 1, locale)
        }
        var shown = value
        if (unit == "kg" && units.system == UnitSystem.IMPERIAL) shown = UnitFormatter.kgToPounds(value)
        if (unit == "°C" && units.temperature == TemperatureUnit.FAHRENHEIT) {
            shown = if (VitalBands.isAbsoluteSkinTemp(value)) value * 9 / 5 + 32 else value * 9 / 5
        }
        return number(shown, if (abs(shown - Math.round(shown).toDouble()) < 1e-6) 0 else 1, locale)
    }

    // MARK: About

    /** The page's "About" text: what the number is and what moves it. Null hides the ⓘ. */
    @StringRes
    fun aboutRes(key: String, descriptionRes: Int?): Int? = when (key) {
        "hrv" -> R.string.metric_about_hrv
        "rhr" -> R.string.metric_about_rhr
        "resp_rate" -> R.string.metric_about_resp
        "spo2" -> R.string.metric_about_spo2
        "skin_temp" -> R.string.metric_about_skin_temp
        "steps", "steps_est" -> R.string.metric_about_steps
        "vo2max", "vo2max_est" -> R.string.metric_about_vo2max
        "weight" -> R.string.metric_about_weight
        "energy_kcal", "active_kcal" -> R.string.metric_about_energy
        "sleep_total_min" -> R.string.metric_about_asleep
        else -> descriptionRes
    }
}
