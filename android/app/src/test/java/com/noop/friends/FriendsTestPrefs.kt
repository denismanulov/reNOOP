package com.noop.friends

import java.util.concurrent.ConcurrentHashMap

/** [FriendsPrefs] held in memory: what a Friends test hands the store and the strap reader. */
class MemoryFriendsPrefs : FriendsPrefs {
    private val values = ConcurrentHashMap<String, Any>()

    override fun string(key: String): String? = values[key] as? String
    override fun boolean(key: String): Boolean = values[key] as? Boolean ?: false
    override fun long(key: String): Long? = values[key] as? Long
    override fun put(key: String, value: String) { values[key] = value }
    override fun put(key: String, value: Boolean) { values[key] = value }
    override fun put(key: String, value: Long) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun keys(): Set<String> = values.keys.toSet()

    /** Whether anything kept carries [text], in a key or in a value. */
    fun holds(text: String): Boolean = values.any { (key, value) -> key.contains(text) || value.toString().contains(text) }
}
