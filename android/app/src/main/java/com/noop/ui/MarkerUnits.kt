package com.noop.ui

import com.noop.analytics.MarkerCatalog
import com.noop.ingest.LabMarkerCsvImport

// MARK: - Unit handling (transparent mmol/L ↔ mg/dL switcher) — twin of Swift MarkerUnits
//
// Only the markers with a well-known dual unit get a switcher; everything else keeps its single canonical
// unit. Conversions are exact and reversible. The stored value is always the canonical unit, so the daily
// projection + correlation stay consistent regardless of what the user typed in.

object MarkerUnits {
    /** mg/dL → canonical (mmol/L) factors for the dual-unit markers. Lipids 38.67, glucose 18.0,
     *  triglycerides 88.57. Byte-identical to the Swift MarkerUnits table. */
    private val mgdlToMmol: Map<String, Double> = mapOf(
        "total_cholesterol" to 1.0 / 38.67,
        "ldl" to 1.0 / 38.67,
        "hdl" to 1.0 / 38.67,
        "triglycerides" to 1.0 / 88.57,
        "fasting_glucose" to 1.0 / 18.0,
    )

    fun canonicalUnit(key: String, fallback: String): String =
        MarkerCatalog.definition(key)?.canonicalUnit ?: fallback

    /** Two entries (canonical + mg/dL) for dual-unit markers; otherwise the single canonical unit. */
    fun options(key: String, canonical: String): List<String> =
        if (mgdlToMmol.containsKey(key)) listOf(canonicalUnit(key, canonical), "mg/dL") else listOf(canonical)

    fun factorToCanonical(markerKey: String, from: String): Double? =
        if (from == "mg/dL") mgdlToMmol[markerKey] else null

    fun toCanonical(markerKey: String, value: Double, from: String): Double =
        factorToCanonical(markerKey, from)?.let { value * it } ?: value

    /** A short label for the conversion factor (5 significant figures), e.g. "0.02586". Twin of Swift `%.5g`. */
    fun factorLabel(f: Double): String =
        java.math.BigDecimal(f).round(java.math.MathContext(5)).stripTrailingZeros().toPlainString()

    /**
     * A lower-cased, underscored slug for a custom marker name → its stable key. Delegates to the CSV
     * importer's `customKey` so a hand-added custom marker and an imported one always share one key.
     */
    fun slug(name: String): String = LabMarkerCsvImport.customKey(name).ifEmpty { "custom_" }
}
