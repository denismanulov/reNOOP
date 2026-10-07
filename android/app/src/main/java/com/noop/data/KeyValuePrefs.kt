package com.noop.data

import android.content.SharedPreferences

/**
 * The few preference operations the WHOOP 4.0 step auto-calibration uses: `UserDefaults` on Apple,
 * SharedPreferences here. An interface so its logic runs in plain-JVM tests over a map, with no Android
 * type in it.
 *
 * None of the keys read or written through this is in the `.noopbak` whitelist
 * ([BackupSettingsCodec.WHITELIST]): the learned factor is per strap and re-learned within days.
 */
interface KeyValuePrefs {
    /** False when the key is absent, like `UserDefaults.bool(forKey:)`. */
    fun getBoolean(key: String): Boolean

    fun putBoolean(key: String, value: Boolean)

    /** Null when the key is absent. */
    fun getString(key: String): String?

    fun putString(key: String, value: String)
}

/**
 * [KeyValuePrefs] over a SharedPreferences file.
 *
 * A boolean is written with `commit()`, a string with `apply()`: a boolean here is a switch whose
 * value must not be lost with the process, while `apply()` only queues the disk write. A boolean that
 * already reads as the new value is not rewritten (an absent key reads as false).
 */
class SharedKeyValuePrefs(private val prefs: SharedPreferences) : KeyValuePrefs {
    override fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)

    override fun putBoolean(key: String, value: Boolean) {
        if (prefs.getBoolean(key, false) == value) return
        prefs.edit().putBoolean(key, value).commit()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
