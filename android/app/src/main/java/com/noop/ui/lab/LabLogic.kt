package com.noop.ui.lab

import com.noop.ui.TypedNumber
import com.noop.R
import com.noop.analytics.LabMarkerCategory
import com.noop.analytics.MarkerCatalog
import com.noop.data.LabMarkerRow
import com.noop.ui.metric.MetricCatalog
import com.noop.ui.metric.MetricDescriptor
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

// MARK: - Lab Results (twin of iOS LabBookView's LabBookFormat / LabWindow / LabBookSignals)
//
// How the logbook names, orders and prints readings, and the restrained words of "Compare". NON-CLINICAL
// (load-bearing): nothing here asserts a clinical judgement; a range shown is exactly what the user typed
// from their own report; correlation copy says association, not cause.

internal object LabFormat {

    /** Categories in the display order of the spec; only the ones present in [rows]. */
    fun orderedCategories(rows: List<LabMarkerRow>): List<LabMarkerCategory> {
        val present = rows.map { LabMarkerCategory.fromRaw(it.category) }.toSet()
        return CATEGORY_ORDER.filter { it in present }
    }

    val CATEGORY_ORDER: List<LabMarkerCategory> = listOf(
        LabMarkerCategory.BLOOD_PANEL, LabMarkerCategory.BLOOD_PRESSURE, LabMarkerCategory.BODY_MEASUREMENT,
        LabMarkerCategory.IMAGING, LabMarkerCategory.APPOINTMENT_NOTE, LabMarkerCategory.OTHER,
    )

    /** The grouping header of a category. Organisational only, never a clinical panel name. */
    fun categoryRes(category: LabMarkerCategory): Int = when (category) {
        LabMarkerCategory.BLOOD_PANEL -> R.string.lab_category_blood_panel
        LabMarkerCategory.BLOOD_PRESSURE -> R.string.lab_category_blood_pressure
        LabMarkerCategory.BODY_MEASUREMENT -> R.string.lab_category_body
        LabMarkerCategory.IMAGING -> R.string.lab_category_imaging
        LabMarkerCategory.APPOINTMENT_NOTE -> R.string.lab_category_notes
        LabMarkerCategory.OTHER -> R.string.lab_category_custom
    }

    /** A catalog marker's name in the reader's language (the catalog itself is English), or null. */
    fun nameRes(key: String): Int? = MARKER_NAMES[key]

    /** A marker's display name: the catalog's (translated through [translate]), else the key humanised. */
    fun name(key: String, translate: (Int) -> String): String {
        nameRes(key)?.let { return translate(it) }
        MarkerCatalog.definition(key)?.let { return it.displayName }
        // As iOS `key.replacingOccurrences(of: "_", with: " ").capitalized`: "custom_apo_b" → "Custom Apo B".
        return key.replace('_', ' ').trim()
            .split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            .ifEmpty { key }
    }

    /**
     * A unit as the screen shows it (LB-1): a catalog unit in the reader's language ("mmol/L" → «ммоль/л»);
     * a unit the user typed that the catalogue does not carry reads as typed. The stored unit is never
     * rewritten.
     */
    fun unit(unit: String, translate: (Int) -> String): String {
        val t = unit.trim()
        return UNIT_NAMES[t]?.let(translate) ?: t
    }

    /**
     * The value as the screen shows it (LB-1): the catalog's decimals (a custom marker up to 3, trailing
     * zeros dropped) in the reader's number format ("14,0" in Russian). Display only: LabValueFormat stays
     * the POSIX string Coach and the cross-platform tests pin.
     */
    fun displayValue(v: Double, key: String, locale: Locale): String {
        if (!v.isFinite()) return "—"
        val decimals = MarkerCatalog.definition(key)?.decimals
        val scale = 10.0.pow(decimals ?: 3)
        val scaled = v * scale
        val rounded = (if (scaled >= 0) floor(scaled + 0.5) else ceil(scaled - 0.5)) / scale
        val shown = if (rounded == 0.0) 0.0 else rounded   // never "-0"
        val nf = NumberFormat.getNumberInstance(locale)
        if (decimals != null) {
            nf.minimumFractionDigits = decimals
            nf.maximumFractionDigits = decimals
        } else {
            nf.minimumFractionDigits = 0
            nf.maximumFractionDigits = 3
        }
        return nf.format(shown)
    }

    /**
     * "12 Jun 2026" from a stored `yyyy-MM-dd` day key, location-independently: the key is a calendar day,
     * so it never shifts with the device zone. Falls back to the raw string if it does not parse.
     */
    fun dayFromKey(day: String, locale: Locale): String =
        runCatching { DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(LocalDate.parse(day)) }.getOrDefault(day)

    /** Distinct marker keys of [category], alphabetised by display name. */
    fun markerKeys(rows: List<LabMarkerRow>, category: LabMarkerCategory, translate: (Int) -> String): List<String> =
        rows.filter { it.category == category.raw }.map { it.markerKey }.distinct().sortedBy { name(it, translate) }

    /** A typed number, through the app's one reader ([TypedNumber]): "3,1" and "3.1" are the same value. */
    fun parse(s: String): Double? = TypedNumber.parse(s)

    private val MARKER_NAMES: Map<String, Int> = mapOf(
        "total_cholesterol" to R.string.lab_marker_total_cholesterol,
        "ldl" to R.string.lab_marker_ldl,
        "hdl" to R.string.lab_marker_hdl,
        "triglycerides" to R.string.lab_marker_triglycerides,
        "fasting_glucose" to R.string.lab_marker_fasting_glucose,
        "hba1c" to R.string.lab_marker_hba1c,
        "ferritin" to R.string.lab_marker_ferritin,
        "iron" to R.string.lab_marker_iron,
        "transferrin_saturation" to R.string.lab_marker_transferrin_saturation,
        "haemoglobin" to R.string.lab_marker_haemoglobin,
        "vitamin_d" to R.string.lab_marker_vitamin_d,
        "vitamin_b12" to R.string.lab_marker_vitamin_b12,
        "folate" to R.string.lab_marker_folate,
        "tsh" to R.string.lab_marker_tsh,
        "free_t4" to R.string.lab_marker_free_t4,
        "crp" to R.string.lab_marker_crp,
        "egfr" to R.string.lab_marker_egfr,
        "creatinine" to R.string.lab_marker_creatinine,
        "alt" to R.string.lab_marker_alt,
        "ast" to R.string.lab_marker_ast,
        "ggt" to R.string.lab_marker_ggt,
        "sodium" to R.string.lab_marker_sodium,
        "potassium" to R.string.lab_marker_potassium,
        "bp_systolic" to R.string.lab_marker_bp_systolic,
        "bp_diastolic" to R.string.lab_marker_bp_diastolic,
        "resting_pulse" to R.string.lab_marker_resting_pulse,
        "weight" to R.string.lab_marker_weight,
        "body_fat" to R.string.lab_marker_body_fat,
        "waist" to R.string.lab_marker_waist,
        "height" to R.string.lab_marker_height,
    )

    private val UNIT_NAMES: Map<String, Int> = mapOf(
        "U/L" to R.string.lab_unit_u_l,
        "bpm" to R.string.lab_unit_bpm,
        "cm" to R.string.lab_unit_cm,
        "g/L" to R.string.lab_unit_g_l,
        "kg" to R.string.lab_unit_kg,
        "mIU/L" to R.string.lab_unit_miu_l,
        "mL/min/1.73m²" to R.string.lab_unit_ml_min_1_73m2,
        "mg/L" to R.string.lab_unit_mg_l,
        "mg/dL" to R.string.lab_unit_mg_dl,
        "mmHg" to R.string.lab_unit_mmhg,
        "mmol/L" to R.string.lab_unit_mmol_l,
        "mmol/mol" to R.string.lab_unit_mmol_mol,
        "ng/L" to R.string.lab_unit_ng_l,
        "nmol/L" to R.string.lab_unit_nmol_l,
        "pmol/L" to R.string.lab_unit_pmol_l,
        "µg/L" to R.string.lab_unit_ug_l,
        "µmol/L" to R.string.lab_unit_umol_l,
    )
}

/** The trailing window a wearable signal is averaged over before each reading. */
internal enum class LabWindow(val days: Int, val labelRes: Int, val phraseRes: Int) {
    Week(7, R.string.lab_window_7d, R.string.lab_window_7_days),
    Fortnight(14, R.string.lab_window_14d, R.string.lab_window_14_days),
    Month(30, R.string.lab_window_30d, R.string.lab_window_30_days),
}

/** The wearable signals a marker can be compared with, and the shared insight language. */
internal object LabSignals {
    /**
     * The reading-count floor below which NO conclusion sentence renders (LB-2). Eight, not four: a line
     * through four points is mostly chance. Display only; nothing stored depends on it.
     */
    const val FLOOR = 8

    private val KEYS = listOf("rhr", "hrv", "recovery", "sleep_performance", "sleep_total_min", "strain", "skin_temp", "steps", "weight")

    /** The metric catalogue's first descriptor of each offered key. */
    val options: List<MetricDescriptor> by lazy {
        KEYS.mapNotNull { key -> MetricCatalog.all.firstOrNull { it.key == key } }
    }

    /** Which sentence the comparison earns: independent below |r| 0.3, else the direction. */
    fun insightRes(r: Double): Int = when {
        abs(r) < 0.3 -> R.string.lab_insight_none
        r < 0 -> R.string.lab_insight_lower
        else -> R.string.lab_insight_higher
    }

    /** Pearson r over the pairs (null with fewer than 3, or no variance in either). */
    fun pearson(xy: List<Pair<Double, Double>>): Double? {
        val n = xy.size
        if (n < 3) return null
        val mx = xy.sumOf { it.first } / n
        val my = xy.sumOf { it.second } / n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for ((x, y) in xy) {
            val dx = x - mx
            val dy = y - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return null
        return (sxy / (sqrt(sxx) * sqrt(syy))).coerceIn(-1.0, 1.0)
    }
}

/** Which field of the blood-pressure pair is being typed into. */
internal enum class BpField { Systolic, Diastolic }

/**
 * Why a typed blood-pressure pair can't be a reading (LB-2), as a string resource; null while it can.
 * [focused] leaves out what the field being typed into may still grow out of (the "1" on the way to 120);
 * pass null to validate the pair as a whole (on save).
 */
internal fun bloodPressureErrorRes(sys: Double?, dia: Double?, focused: BpField?): Int? {
    val sysDone = focused != BpField.Systolic
    val diaDone = focused != BpField.Diastolic
    if (sys != null && (sys > 260 || (sysDone && sys < 50))) return R.string.lab_bp_systolic_range
    if (dia != null && (dia > 160 || (diaDone && dia < 30))) return R.string.lab_bp_diastolic_range
    if (sysDone && diaDone && sys != null && dia != null && sys <= dia) return R.string.lab_bp_order
    return null
}
