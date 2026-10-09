package com.noop.ui

import com.noop.ui.metric.MetricCatalog
import com.noop.ui.summary.HeroRingMetric
import com.noop.ui.summary.SummaryHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1995: the Charge, Effort and Rest rings open their own metric page, so a ring and the page it opens can
 * never disagree. The Summary passes catalogue keys straight through (iOS `HeroRingMetric`), so each one
 * must name a catalogue entry, and Rest's is the series key, not the old "rest" detail alias.
 */
class HeroRingMetricKeyTest {

    @Test fun ringKeysAreTheIosOnes() {
        assertEquals(listOf("recovery", "strain", "sleep_performance"), HeroRingMetric.all)
    }

    @Test fun everyRingKeyHasACatalogueEntry() {
        for (key in HeroRingMetric.all) {
            assertTrue(key, MetricCatalog.byKey(key).isNotEmpty())
        }
    }

    @Test fun legacyAliasesStillLandOnTheRingPages() {
        assertEquals(HeroRingMetric.CHARGE, MetricCatalog.keyForLegacy("recovery"))
        assertEquals(HeroRingMetric.REST, MetricCatalog.keyForLegacy("rest"))
    }

    @Test fun trainingHighlightsOpenEffort() {
        assertEquals(HeroRingMetric.EFFORT, SummaryHighlight.routeKey("acwr"))
        assertEquals(HeroRingMetric.EFFORT, SummaryHighlight.routeKey("monotony"))
    }
}
