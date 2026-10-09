package com.noop.ui.lab

import com.noop.R
import com.noop.analytics.LabMarkerCategory
import com.noop.data.LabMarkerRow
import com.noop.ui.MarkerUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Lab Results' formatting and words: locale decimals and units (LB-1), the floor and BP validation (LB-2). */
class LabLogicTest {

    private val t: (Int) -> String = { id -> "T$id" }

    private fun row(key: String, category: LabMarkerCategory, takenAt: Long = 0) = LabMarkerRow(
        id = "$key-$takenAt", deviceId = "my-whoop", markerKey = key, category = category.raw,
        day = "2026-09-30", takenAt = takenAt, value = 1.0, unit = "mmol/L", source = "manual",
    )

    @Test fun displayValueUsesCatalogDecimalsInTheReadersLocale() {
        assertEquals("3.40", LabFormat.displayValue(3.4, "ldl", Locale.US))
        assertEquals("3,40", LabFormat.displayValue(3.4, "ldl", Locale.forLanguageTag("ru")))
        assertEquals("14,0", LabFormat.displayValue(14.0, "iron", Locale.forLanguageTag("ru")))
        assertEquals("52", LabFormat.displayValue(51.6, "ferritin", Locale.US))
    }

    @Test fun customMarkersKeepTheirOwnPrecisionUpToThreeDecimals() {
        assertEquals("0.27", LabFormat.displayValue(0.27, "custom_plateletcrit", Locale.US))
        assertEquals("1.02", LabFormat.displayValue(1.020, "custom_sg", Locale.US))
        assertEquals("0", LabFormat.displayValue(-0.0001, "custom_x", Locale.US))
        assertEquals("—", LabFormat.displayValue(Double.NaN, "ldl", Locale.US))
    }

    @Test fun namesAndUnitsTranslateCatalogEntriesAndPassOthersThrough() {
        assertEquals("T${R.string.lab_marker_ldl}", LabFormat.name("ldl", t))
        assertEquals("Custom Apo B", LabFormat.name("custom_apo_b", t))
        assertEquals("T${R.string.lab_unit_mmol_l}", LabFormat.unit(" mmol/L ", t))
        assertEquals("%", LabFormat.unit("%", t))
        assertEquals("furlongs", LabFormat.unit("furlongs", t))
    }

    @Test fun dayKeysRenderAsCalendarDaysWhateverTheZone() {
        assertEquals("12 Jun 2026", LabFormat.dayFromKey("2026-06-12", Locale.US))
        assertEquals("not-a-day", LabFormat.dayFromKey("not-a-day", Locale.US))
    }

    @Test fun categoriesFollowTheSpecOrderAndKeysSortByName() {
        val rows = listOf(
            row("weight", LabMarkerCategory.BODY_MEASUREMENT),
            row("ldl", LabMarkerCategory.BLOOD_PANEL),
            row("hdl", LabMarkerCategory.BLOOD_PANEL, 1),
            row("ldl", LabMarkerCategory.BLOOD_PANEL, 2),
        )
        assertEquals(
            listOf(LabMarkerCategory.BLOOD_PANEL, LabMarkerCategory.BODY_MEASUREMENT),
            LabFormat.orderedCategories(rows),
        )
        val english: (Int) -> String = { id -> if (id == R.string.lab_marker_hdl) "HDL cholesterol" else "LDL cholesterol" }
        assertEquals(listOf("hdl", "ldl"), LabFormat.markerKeys(rows, LabMarkerCategory.BLOOD_PANEL, english))
    }

    @Test fun parseAcceptsACommaDecimal() {
        assertEquals(3.1, LabFormat.parse(" 3,1 ")!!, 1e-9)
        assertEquals(120.0, LabFormat.parse("120")!!, 1e-9)
        assertNull(LabFormat.parse("abc"))
        assertNull(LabFormat.parse(""))
    }

    @Test fun conclusionsNeedEightReadings() {
        assertEquals(8, LabSignals.FLOOR)
        assertEquals(R.string.lab_insight_none, LabSignals.insightRes(0.29))
        assertEquals(R.string.lab_insight_lower, LabSignals.insightRes(-0.5))
        assertEquals(R.string.lab_insight_higher, LabSignals.insightRes(0.5))
        assertNull(LabSignals.pearson(listOf(1.0 to 1.0, 2.0 to 2.0)))
        assertEquals(-1.0, LabSignals.pearson(listOf(1.0 to 3.0, 2.0 to 2.0, 3.0 to 1.0))!!, 1e-9)
    }

    @Test fun signalsComeFromTheMetricCatalogue() {
        assertEquals(
            listOf("rhr", "hrv", "recovery", "sleep_performance", "sleep_total_min", "strain", "skin_temp", "steps", "weight"),
            LabSignals.options.map { it.key },
        )
    }

    @Test fun bloodPressureIsValidatedAsItIsTyped() {
        // Typing "1" on the way to 120 is not yet an error in the focused field…
        assertNull(bloodPressureErrorRes(1.0, null, BpField.Systolic))
        // …but it is once the field is left.
        assertEquals(R.string.lab_bp_systolic_range, bloodPressureErrorRes(1.0, null, null))
        assertEquals(R.string.lab_bp_systolic_range, bloodPressureErrorRes(300.0, 80.0, BpField.Systolic))
        assertEquals(R.string.lab_bp_diastolic_range, bloodPressureErrorRes(120.0, 170.0, BpField.Diastolic))
        assertEquals(R.string.lab_bp_diastolic_range, bloodPressureErrorRes(120.0, 20.0, null))
        assertEquals(R.string.lab_bp_order, bloodPressureErrorRes(120.0, 130.0, null))
        assertNull(bloodPressureErrorRes(120.0, 130.0, BpField.Diastolic))
        assertNull(bloodPressureErrorRes(120.0, 80.0, null))
    }

    @Test fun conversionFactorLabelHasFiveSignificantFigures() {
        assertEquals("0.02586", MarkerUnits.factorLabel(1.0 / 38.67))
        assertEquals("0.011291", MarkerUnits.factorLabel(1.0 / 88.57))
        assertEquals("0.055556", MarkerUnits.factorLabel(1.0 / 18.0))
    }
}
