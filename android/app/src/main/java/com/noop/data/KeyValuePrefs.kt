package com.noop.data

import android.content.SharedPreferences

/**
 * The few preference operations the WHOOP 4.0 step auto-calibration and its raw-stream probe use:
 * `UserDefaults` on Apple, SharedPreferences here. An interface so their logic runs in plain-JVM tests
 * over a map, with no Android type in it.
 *
 * None of the keys read or written through this is in the `.noopbak` whitelist
 * ([BackupSettingsCodec.WHITELIST]): the learned factor is per strap and re-learned within days, and
 * the probe's marker describes the state of one strap's stream.
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
 * Every write is a `commit()`, not an `apply()`, which only queues the disk write. What is written
 * here has to survive the process dying right after: the raw-stream probe's "the stream may be on"
 * marker is what makes a later connection switch the stream off, and the stored burst times are what
 * hold the limit of bursts per day. There are a few such writes a day. A value that already reads as
 * the new one is not rewritten (an absent boolean reads as false).
 */
class SharedKeyValuePrefs(private val prefs: SharedPreferences) : KeyValuePrefs {
    override fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)

    override fun putBoolean(key: String, value: Boolean) {
        if (prefs.getBoolean(key, false) == value) return
        prefs.edit().putBoolean(key, value).commit()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        if (prefs.getString(key, null) == value) return
        prefs.edit().putString(key, value).commit()
    }
}
