package com.noop.ui

import com.noop.data.WhoopRepository
import com.noop.ui.metric.MetricCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stress metric page's "Today" card (the part of the old Stress screen the page kept) reads today's
 * heart rate, R-R and wrist motion through the device-aware unions, never a hard-coded strap id; the
 * daily stress series stays under the canonical WHOOP source.
 */
class StressRawReadScopeTest {
    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(5) {
            val current = dir ?: return@repeat
            val candidate = File(current, "app/src/main/java/com/noop/ui/metric/MetricStressDay.kt")
            if (candidate.isFile) return candidate.readText()
            dir = current.parentFile
        }
        error("MetricStressDay.kt not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun intradayAndBaselineUseDeviceAwareRawReads() {
        val text = source().replace(Regex("\\s+"), " ")
        assertTrue(text.contains("hrSamplesUnion(vm.activeStrapId"))
        assertTrue(text.contains("rrIntervalsUnion(vm.activeStrapId"))
        assertTrue(text.contains("gravitySamplesUnion(vm.activeStrapId"))
        assertFalse(Regex("(hrSamples|rrIntervals|gravitySamples)\\(\\s*\"my-whoop\"").containsMatchIn(text))
    }

    @Test
    fun storedDailyStressRemainsCanonical() {
        // The page reads the stored 0-3 series as the WHOOP entry of the catalogue, whose loader always
        // unions the canonical "my-whoop" id with the active strap's.
        val stress = MetricCatalog.metric("stress", MetricCatalog.WHOOP)
        assertNotNull(stress)
        assertEquals("/3", stress!!.unit)
        assertTrue(WhoopRepository.WHOOP_SOURCE in WhoopRepository.importedSourceIdsFor("whoop-AA:BB"))
        assertTrue(WhoopRepository.WHOOP_SOURCE in WhoopRepository.importedSourceIdsFor(WhoopRepository.WHOOP_SOURCE))
    }
}
