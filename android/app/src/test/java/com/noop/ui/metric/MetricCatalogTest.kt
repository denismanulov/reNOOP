package com.noop.ui.metric

import com.noop.R
import com.noop.ui.EffortScale
import com.noop.ui.KeyMetric
import com.noop.ui.TemperatureUnit
import com.noop.ui.UnitSystem
import com.noop.ui.m3.MetricHue
import com.noop.ui.m3.keyMetricHue
import com.noop.ui.m3.metricHue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The metric catalogue's pure rules: hue, category, one source per key, chart, figures, links. */
class MetricCatalogTest {

    private fun m(key: String, source: String) = MetricCatalog.metric(key, source)!!

    @Test
    fun `hues follow the iOS rule`() {
        assertEquals(MetricHue.Charge, metricHue("recovery", "Charge"))
        assertEquals(MetricHue.Effort, metricHue("strain", "Effort"))
        assertEquals(MetricHue.Rest, metricHue("sleep_performance", "Rest"))
        assertEquals(MetricHue.Heart, metricHue("hrv", "Charge"))
        assertEquals(MetricHue.Heart, metricHue("avg_hr", "Heart"))
        assertEquals(MetricHue.Respiratory, metricHue("resp_rate", "Charge"))
        assertEquals(MetricHue.Oxygen, metricHue("spo2", "Charge"))
        assertEquals(MetricHue.Temperature, metricHue("skin_temp", "Charge"))
        assertEquals(MetricHue.Body, metricHue("weight", "Health"))
        assertEquals(MetricHue.Mind, metricHue("stress", "Health"))
        assertEquals(MetricHue.Mind, metricHue("mood", "Mind"))
        assertEquals(MetricHue.Sleep, metricHue("sleep_total_min", "Rest"))
        assertEquals(MetricHue.Activity, metricHue("steps", "Effort"))
        assertEquals(MetricHue.Nutrition, metricHue("protein_g", "Nutrition"))
        assertEquals(MetricHue.Body, metricHue("bmi", "Health"))
        assertEquals(MetricHue.Body, metricHue("unknown", "Other"))
        // The pinned Summary card shares its page's hue.
        assertEquals(MetricHue.Activity, keyMetricHue(KeyMetric.CALORIES))
        assertEquals(MetricHue.Rest, keyMetricHue(KeyMetric.REST))
        for (km in KeyMetric.entries) {
            val key = MetricCatalog.all.firstOrNull { MetricHealthStyle.keyMetric(it.key) == km && it.source == MetricCatalog.WHOOP }
                ?: continue
            if (key.key == "energy_kcal") continue // Calories is a Heart-family key on its page, Activity on its card (as on iOS).
            assertEquals(km.name, keyMetricHue(km), metricHue(key.key, key.category))
        }
    }

    @Test
    fun `categories follow Health's grouping`() {
        assertEquals(HealthCategory.RESPIRATORY, AllMetricsCatalog.category(m("resp_rate", "my-whoop")))
        assertEquals(HealthCategory.BODY_MEASUREMENTS, AllMetricsCatalog.category(m("skin_temp", "my-whoop")))
        assertEquals(HealthCategory.MENTAL_WELLBEING, AllMetricsCatalog.category(m("stress", "my-whoop")))
        assertEquals(HealthCategory.ACTIVITY, AllMetricsCatalog.category(m("energy_kcal", "my-whoop")))
        assertEquals(HealthCategory.HEART, AllMetricsCatalog.category(m("hrv", "my-whoop")))
        assertEquals(HealthCategory.SLEEP, AllMetricsCatalog.category(m("sleep_total_min", "my-whoop")))
        assertEquals(HealthCategory.ACTIVITY, AllMetricsCatalog.category(m("steps", "health-connect")))
        assertEquals(HealthCategory.NUTRITION, AllMetricsCatalog.category(m("carbs_g", "nutrition-csv")))
        assertEquals(HealthCategory.MENTAL_WELLBEING, AllMetricsCatalog.category(m("mood", "noop-mood")))
    }

    @Test
    fun `one source per key keeps the freshest and breaks ties by WHOOP then Health Connect`() {
        val whoop = m("steps", "my-whoop")
        val phone = m("steps", "health-connect")
        val band = m("steps", "xiaomi-band")
        val all = listOf(phone, whoop, band)
        // Freshest wins.
        assertEquals(listOf(band), AllMetricsCatalog.oneSourcePerKey(all, mapOf(whoop.id to "2026-09-28", phone.id to "2026-09-29", band.id to "2026-09-30")))
        // A tie goes to WHOOP, then Health Connect.
        assertEquals(listOf(whoop), AllMetricsCatalog.oneSourcePerKey(all, mapOf(whoop.id to "2026-09-30", phone.id to "2026-09-30", band.id to "2026-09-30")))
        assertEquals(listOf(phone), AllMetricsCatalog.oneSourcePerKey(listOf(band, phone), mapOf(phone.id to "2026-09-30", band.id to "2026-09-30")))
        // No reading at all: priority alone, and key order is kept.
        val hrv = m("hrv", "my-whoop")
        assertEquals(listOf("steps", "hrv"), AllMetricsCatalog.oneSourcePerKey(all + hrv, emptyMap()).map { it.key })
        assertEquals(whoop, AllMetricsCatalog.oneSourcePerKey(all, emptyMap()).first())
    }

    @Test
    fun `every catalogue id is unique and every legacy Today key resolves`() {
        assertEquals(MetricCatalog.all.size, MetricCatalog.all.map { it.id }.toSet().size)
        for (legacy in listOf("recovery", "strain", "rest", "hrv", "rhr", "spo2", "resp", "skin", "fitness_age", "vitality", "vo2max_est", "steps_est", "active_kcal", "stress")) {
            assertTrue(legacy, MetricCatalog.byKey(MetricCatalog.keyForLegacy(legacy)).isNotEmpty())
        }
        assertEquals("steps_est", MetricCatalog.fallbackKey("steps"))
        assertEquals("energy_kcal", MetricCatalog.fallbackKey("active_kcal"))
        assertNull(MetricCatalog.fallbackKey("hrv"))
    }

    @Test
    fun `search matches name, short name or category, ignoring case and accents`() {
        val en = Locale.ENGLISH
        assertTrue(AllMetricsCatalog.matches("", en, "Weight"))
        assertTrue(AllMetricsCatalog.matches(" hrv ", en, "Heart Rate Variability", "HRV", "Heart"))
        assertTrue(AllMetricsCatalog.matches("frequencia", Locale.forLanguageTag("pt-PT"), "Frequência respiratória"))
        assertFalse(AllMetricsCatalog.matches("pulse", en, "Weight", "Weight", "Body Measurements"))
    }

    @Test
    fun `sections are alphabetical by category then by name`() {
        val metrics = listOf(m("weight", "health-connect"), m("hrv", "my-whoop"), m("rhr", "my-whoop"), m("steps", "health-connect"))
        val names = mapOf("weight" to "Weight", "hrv" to "Heart Rate Variability", "rhr" to "Resting Heart Rate", "steps" to "Steps")
        val cats = mapOf(HealthCategory.ACTIVITY to "Activity", HealthCategory.BODY_MEASUREMENTS to "Body Measurements", HealthCategory.HEART to "Heart")
        val sections = AllMetricsCatalog.sections(metrics, Locale.ENGLISH, { cats.getValue(it) }, { names.getValue(it.key) })
        assertEquals(listOf(HealthCategory.ACTIVITY, HealthCategory.BODY_MEASUREMENTS, HealthCategory.HEART), sections.map { it.category })
        assertEquals(listOf("hrv", "rhr"), sections.last().metrics.map { it.key })
    }

    @Test
    fun `charts follow the per-metric mark rules`() {
        val charge = MetricHealthStyle.chart("recovery", "%", emptyList())
        assertEquals(MetricMark.BARS, charge.mark)
        assertEquals(listOf(50.0, 70.0), charge.thresholds)
        assertEquals(MetricBarTint.CHARGE_BANDS, charge.barTint)
        assertEquals(MetricMark.DOTS, MetricHealthStyle.chart("spo2", "%", emptyList()).mark)
        assertTrue(MetricHealthStyle.chart("hrv", "ms", emptyList()).showsAverage)
        assertEquals(MetricMark.LINE, MetricHealthStyle.chart("rhr", "bpm", emptyList()).mark)
        assertEquals(MetricMark.DIVERGING, MetricHealthStyle.chart("skin_temp", "°C", listOf(0.2, -0.1)).mark)
        assertEquals(MetricMark.LINE, MetricHealthStyle.chart("skin_temp", "°C", listOf(34.1, 33.9)).mark)
        assertEquals(0.0..3.0, MetricHealthStyle.chart("stress", "/3", emptyList()).domain)
        assertEquals(0.0..100.0, MetricHealthStyle.chart("stress", "/100", emptyList()).domain)
        assertEquals(MetricMark.BARS, MetricHealthStyle.chart("steps", "", emptyList()).mark)
        assertEquals(MetricMark.BARS, MetricHealthStyle.chart("sleep_deep_min", "min", emptyList()).mark)
        assertEquals(MetricMark.DOTS, MetricHealthStyle.chart("mood", "/5", emptyList()).mark)
        assertEquals(R.string.metric_state_primed, MetricHealthStyle.chargeStateRes(72.0))
        assertEquals(R.string.metric_state_depleted, MetricHealthStyle.chargeStateRes(10.0))
    }

    @Test
    fun `figures split into numbers and units in the reader's units`() {
        val en = Locale.US
        val unit: (String) -> String = { it }
        fun t(key: String, unit_: String, decimals: Int, v: Double, units: MetricUnits = MetricUnits()) =
            MetricHealthStyle.tokens(key, unit_, decimals, v, units, en, unit).joinToString("|") { (if (it.isUnit) "u:" else "") + it.text }
        assertEquals("7|u:hr|12|u:min", t("sleep_total_min", "min", 0, 432.0))
        assertEquals("45|u:min", t("sleep_deep_min", "min", 0, 45.0))
        assertEquals("8|u:hr", t("sleep_need_min", "min", 0, 480.0))
        assertEquals("12,345|u:steps", t("steps", "", 0, 12345.0))
        assertEquals("52|u:ms", t("hrv", "ms", 0, 52.4))
        assertEquals("63.0|u:/100", t("strain", "/100", 1, 63.0))
        assertEquals("13.2|u:/21", t("strain", "/100", 1, 63.0, MetricUnits(effortScale = EffortScale.WHOOP)))
        assertEquals("175.3|u:lb", t("weight", "kg", 1, 79.5, MetricUnits(system = UnitSystem.IMPERIAL)))
        assertEquals("+0.4|u:°C", t("skin_temp", "°C", 1, 0.4))
        assertEquals("93.2|u:°F", t("skin_temp", "°C", 1, 34.0, MetricUnits(temperature = TemperatureUnit.FAHRENHEIT)))
        assertEquals("3|u:/5", t("mood", "/5", 0, 3.0))
        assertEquals("21.3", t("bmi", "", 1, 21.3))
        // Only the decimal separator follows the reader's locale.
        assertEquals("+0,4", MetricHealthStyle.tokens("skin_temp", "°C", 1, 0.4, MetricUnits(), Locale.GERMANY, unit)[0].text)
    }

    @Test
    fun `axis labels drop needless decimals and read long durations in hours`() {
        val en = Locale.US
        assertEquals("60", MetricHealthStyle.axisLabel("hrv", "ms", 60.0, MetricUnits(), 70.0, en, "hr"))
        assertEquals("92.5", MetricHealthStyle.axisLabel("spo2", "%", 92.5, MetricUnits(), 100.0, en, "hr"))
        assertEquals("8 hr", MetricHealthStyle.axisLabel("sleep_total_min", "min", 480.0, MetricUnits(), 500.0, en, "hr"))
        assertEquals("50", MetricHealthStyle.axisLabel("sleep_deep_min", "min", 50.0, MetricUnits(), 90.0, en, "hr"))
        assertEquals("10.5", MetricHealthStyle.axisLabel("strain", "/100", 50.0, MetricUnits(effortScale = EffortScale.WHOOP), 100.0, en, "hr"))
    }

    @Test
    fun `pinnable metrics exclude the rings`() {
        assertEquals(KeyMetric.HRV, MetricHealthStyle.pinnable("hrv"))
        assertEquals(KeyMetric.CALORIES, MetricHealthStyle.pinnable("active_kcal"))
        assertNull(MetricHealthStyle.pinnable("recovery"))
        assertNull(MetricHealthStyle.pinnable("strain"))
        assertNull(MetricHealthStyle.pinnable("sleep_performance"))
        assertNull(MetricHealthStyle.pinnable("mood"))
    }

    @Test
    fun `all data rows are newest first with their own source`() {
        val page = MetricPageSeries(listOf("2026-09-29" to 50.0, "2026-09-30" to 60.0), mapOf("2026-09-30" to "my-whoop-noop"))
        val rows = metricDataRows(page, "my-whoop", { "${it.toInt()} ms" }, { it }, { it })
        assertEquals(listOf(MetricDataRow("60 ms", "my-whoop-noop", "2026-09-30"), MetricDataRow("50 ms", "my-whoop", "2026-09-29")), rows)
    }
}
