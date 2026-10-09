package com.noop.data

/**
 * [KeyValuePrefs] over a map, for plain-JVM tests: the stand-in for the `UserDefaults(suiteName:)` the
 * Swift tests of the step auto-calibration use. [writes] counts every put, so a test can show that a
 * path wrote nothing.
 */
class MemoryKeyValuePrefs : KeyValuePrefs {
    val booleans = HashMap<String, Boolean>()
    val strings = HashMap<String, String>()
    var writes = 0
        private set

    override fun getBoolean(key: String): Boolean = booleans[key] ?: false

    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
        writes += 1
    }

    override fun getString(key: String): String? = strings[key]

    override fun putString(key: String, value: String) {
        strings[key] = value
        writes += 1
    }
}
