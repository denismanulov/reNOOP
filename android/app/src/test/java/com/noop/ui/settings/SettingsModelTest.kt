package com.noop.ui.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings tree's pure logic: the seven-tap Developer unlock (iOS ST-9), the strap row's wording, and
 * which Language row Android gets. The project ships no Robolectric, so the real [DeveloperUnlock] runs
 * over an in-memory SharedPreferences that keeps the read/write contract.
 */
class SettingsModelTest {

    @Test
    fun `the developer page is hidden until the seventh tap, which stores the unlock`() {
        val prefs = FakeSharedPreferences()
        assertFalse(DeveloperUnlock.isUnlocked(prefs))
        var count = 0
        repeat(DeveloperUnlock.TAPS - 1) {
            val tap = DeveloperUnlock.tap(prefs, count)
            count = tap.count
            assertFalse("tap ${it + 1} must not unlock", tap.unlockedNow)
            assertFalse(DeveloperUnlock.isUnlocked(prefs))
        }
        val seventh = DeveloperUnlock.tap(prefs, count)
        assertEquals(7, seventh.count)
        assertTrue(seventh.unlockedNow)
        assertTrue(DeveloperUnlock.isUnlocked(prefs))
    }

    @Test
    fun `the unlock persists and later taps change nothing`() {
        val prefs = FakeSharedPreferences()
        var count = 0
        repeat(DeveloperUnlock.TAPS) { count = DeveloperUnlock.tap(prefs, count).count }
        assertTrue(DeveloperUnlock.isUnlocked(prefs))
        // A new About visit starts its counter at 0; tapping again never "unlocks" a second time.
        val again = DeveloperUnlock.tap(prefs, 0)
        assertFalse(again.unlockedNow)
        assertEquals(0, again.count)
        assertTrue(DeveloperUnlock.isUnlocked(prefs))
    }

    @Test
    fun `the counter is per visit, so six taps then a fresh visit does not unlock`() {
        val prefs = FakeSharedPreferences()
        var count = 0
        repeat(6) { count = DeveloperUnlock.tap(prefs, count).count }
        val freshVisit = DeveloperUnlock.tap(prefs, 0)
        assertEquals(1, freshVisit.count)
        assertFalse(DeveloperUnlock.isUnlocked(prefs))
    }

    @Test
    fun `the unlock uses the iOS key and seven taps`() {
        assertEquals("noop.developerUnlocked", DeveloperUnlock.KEY)
        assertEquals(7, DeveloperUnlock.TAPS)
    }

    @Test
    fun `the strap row prefers a live link over a scan in flight`() {
        assertEquals(StrapLink.CONNECTED, strapLink(connected = true, scanning = true))
        assertEquals(StrapLink.SEARCHING, strapLink(connected = false, scanning = true))
        assertEquals(StrapLink.NOT_CONNECTED, strapLink(connected = false, scanning = false))
    }

    @Test
    fun `the battery shows only while connected, rounded and clamped`() {
        assertEquals(82, strapBatteryPercent(connected = true, batteryPct = 81.6))
        assertEquals(100, strapBatteryPercent(connected = true, batteryPct = 101.0))
        assertNull(strapBatteryPercent(connected = false, batteryPct = 82.0))
        assertNull(strapBatteryPercent(connected = true, batteryPct = null))
    }

    @Test
    fun `Android 13 and later hand the language to the system`() {
        assertEquals(LanguageRowMode.IN_APP_PICKER, languageRowMode(26))
        assertEquals(LanguageRowMode.IN_APP_PICKER, languageRowMode(32))
        assertEquals(LanguageRowMode.SYSTEM_PER_APP, languageRowMode(33))
        assertEquals(LanguageRowMode.SYSTEM_PER_APP, languageRowMode(37))
    }

    /** Minimal in-memory SharedPreferences: values written through an editor are visible after apply(). */
    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { removed += key; return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                removed.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }
    }
}
