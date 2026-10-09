package com.noop.ui

import com.noop.analytics.SpotHrvReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * The HRV reading's "How this is measured" sheet shows the spot-reading caveat from string resources, so
 * it follows the app language. The wording itself is owned by [SpotHrvReading.caveatFor] (the twin of the
 * Swift text), so the English resources must say exactly what the analytics layer says: one fact, one
 * wording. A JVM test cannot resolve Android resources, so it reads `values/strings.xml` as text.
 */
class HrvSnapshotCaveatStringsTest {

    private fun englishStrings(): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rel = "src/main/res/values/strings.xml"
        val found = listOf(File(userDir, rel), File(userDir, "app/$rel"), File(userDir, "android/app/$rel"))
            .firstOrNull { it.isFile }
        assertNotNull("values/strings.xml not found from user.dir=$userDir; a skip would read as a pass", found)
        return found!!.readText()
    }

    private fun resource(xml: String, name: String): String {
        val raw = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
        assertNotNull("values/strings.xml has no $name", raw)
        return raw!!.replace("\\'", "'").replace("\\\"", "\"")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }

    @Test
    fun theEnglishResourcesSayWhatTheAnalyticsLayerSays() {
        val xml = englishStrings()
        val base = resource(xml, "hrv_snapshot_caveat")
        val optical = resource(xml, "hrv_snapshot_caveat_optical")
        assertEquals(SpotHrvReading.caveatFor(SpotHrvReading.Source.CHEST_STRAP), base)
        assertEquals(SpotHrvReading.caveatFor(SpotHrvReading.Source.UNKNOWN), base)
        assertEquals(SpotHrvReading.caveatFor(SpotHrvReading.Source.OPTICAL_PPG), "$base $optical")
    }
}
