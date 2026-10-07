package com.noop.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SharedKeyValuePrefs]: what WHOOP 4.0 step auto-calibration and its raw-stream probe store must be on
 * disk before the next thing happens, so every write is a `commit()`. The probe's marker is what makes a
 * later connection switch the stream off, and the stored burst times hold the limit of bursts per day.
 */
class SharedKeyValuePrefsTest {

    @Test
    fun everyWriteIsCommittedNotQueued() {
        val backing = RecordingPreferences()
        val prefs = SharedKeyValuePrefs(backing)
        prefs.putBoolean("rawStreamProbe.armed", true)
        prefs.putString("stepAutoCalibration.burstTimes", "[1000]")
        assertEquals(2, backing.commits)
        assertEquals(0, backing.applies)
        assertTrue(prefs.getBoolean("rawStreamProbe.armed"))
        assertEquals("[1000]", prefs.getString("stepAutoCalibration.burstTimes"))
    }

    @Test
    fun aValueThatAlreadyReadsAsTheNewOneIsNotRewritten() {
        val backing = RecordingPreferences()
        val prefs = SharedKeyValuePrefs(backing)
        prefs.putBoolean("stepAutoCalibration.enabled", false)   // absent reads as false already
        prefs.putBoolean("rawStreamProbe.armed", true)
        prefs.putBoolean("rawStreamProbe.armed", true)
        prefs.putString("stepAutoCalibration.pending", "[]")
        prefs.putString("stepAutoCalibration.pending", "[]")
        assertEquals(2, backing.commits)
        prefs.putBoolean("rawStreamProbe.armed", false)
        assertEquals(3, backing.commits)
        assertFalse(prefs.getBoolean("rawStreamProbe.armed"))
    }

    @Test
    fun anAbsentKeyReadsAsFalseOrNull() {
        val prefs = SharedKeyValuePrefs(RecordingPreferences())
        assertFalse(prefs.getBoolean("stepAutoCalibration.enabled"))
        assertNull(prefs.getString("stepAutoCalibration.state"))
    }

    /** In-memory SharedPreferences that counts how each edit was written. */
    private class RecordingPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()
        var commits = 0
        var applies = 0
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = Editor()

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean { commits += 1; flush(); return true }
            override fun apply() { applies += 1; flush() }
            private fun flush() {
                for (key in removals) map.remove(key)
                map.putAll(pending)
                pending.clear(); removals.clear()
            }
        }
    }
}
