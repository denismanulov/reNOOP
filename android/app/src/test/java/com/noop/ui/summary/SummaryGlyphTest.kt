package com.noop.ui.summary

import com.noop.ui.KeyMetric
import com.noop.ui.metric.AllMetricsCatalog
import com.noop.ui.metric.HealthCategory
import com.noop.ui.metric.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A metric carries one glyph everywhere: the glyph of the Health category it files under, on its
 * pinned Summary card, in a Summary highlight, on its own page and in All Metrics. The page and All
 * Metrics take [AllMetricsCatalog.category]; the Summary takes [keyMetricCategory]. These hold the two
 * together, so a pinned card cannot wear another glyph than the page it opens. Twin of the rule of
 * iOS `KeyMetric.healthCategory`.
 */
class SummaryGlyphTest {

    /** The page each pinned card opens, by the key the catalogue lists it under. */
    private val pageKey = mapOf(
        KeyMetric.CHARGE to HeroRingMetric.CHARGE,
        KeyMetric.EFFORT to HeroRingMetric.EFFORT,
        KeyMetric.REST to HeroRingMetric.REST,
        KeyMetric.HRV to "hrv",
        KeyMetric.RESTING_HR to "rhr",
        KeyMetric.BLOOD_OXYGEN to "spo2",
        KeyMetric.RESPIRATORY to "resp_rate",
        KeyMetric.STEPS to "steps",
        KeyMetric.WEIGHT to "weight",
        KeyMetric.CALORIES to "energy_kcal",
        KeyMetric.SKIN_TEMP to "skin_temp",
    )

    @Test
    fun aPinnedCardFilesUnderTheCategoryOfThePageItOpens() {
        assertEquals("every Key Metric is covered", KeyMetric.entries.toSet(), pageKey.keys)
        for ((metric, key) in pageKey) {
            val pages = MetricCatalog.byKey(key)
            assertTrue("the catalogue lists $key", pages.isNotEmpty())
            for (page in pages) {
                assertEquals("$metric against ${page.key} from ${page.source}",
                    AllMetricsCatalog.category(page), keyMetricCategory(metric))
            }
        }
    }

    /** The categories as iOS `KeyMetric.healthCategory` assigns them. */
    @Test
    fun theCategoriesAreTheOnesIosAssigns() {
        val expected = mapOf(
            KeyMetric.CHARGE to HealthCategory.HEART, KeyMetric.HRV to HealthCategory.HEART,
            KeyMetric.RESTING_HR to HealthCategory.HEART,
            KeyMetric.EFFORT to HealthCategory.ACTIVITY, KeyMetric.STEPS to HealthCategory.ACTIVITY,
            KeyMetric.CALORIES to HealthCategory.ACTIVITY,
            KeyMetric.REST to HealthCategory.SLEEP,
            KeyMetric.BLOOD_OXYGEN to HealthCategory.RESPIRATORY, KeyMetric.RESPIRATORY to HealthCategory.RESPIRATORY,
            KeyMetric.WEIGHT to HealthCategory.BODY_MEASUREMENTS, KeyMetric.SKIN_TEMP to HealthCategory.BODY_MEASUREMENTS,
        )
        for (metric in KeyMetric.entries) assertEquals(metric.name, expected[metric], keyMetricCategory(metric))
    }

    @Test
    fun aCardAndAHighlightTakeTheirCategorysGlyph() {
        for (metric in KeyMetric.entries) assertSame(keyMetricCategory(metric).icon, keyMetricHealthIcon(metric))
        // A highlight is about a pinned metric and opens that metric's page.
        for (signal in listOf("hrv", "rhr", "respRate", "effort")) {
            val metric = highlightMetric(signal)
            assertEquals(pageKey[metric], SummaryHighlight.routeKey(signal))
            assertSame(keyMetricHealthIcon(metric), highlightIcon(signal))
        }
    }
}
