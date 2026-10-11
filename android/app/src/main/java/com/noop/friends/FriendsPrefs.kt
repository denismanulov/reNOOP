package com.noop.friends

import android.content.Context
import android.content.SharedPreferences

// MARK: - Friends: small values kept between launches
//
// What Friends remembers that is not a secret: where it stands, what this phone last sent, which strap
// it has seen. In the app it is an ordinary preferences file; behind this interface so the rules that
// read and write it run on the JVM with a map. The key is NOT here (see `FriendsKey`), and neither are
// the codes of this phone's invites (see `FriendsInviteCodes`).

interface FriendsPrefs {
    fun string(key: String): String?

    /** False when nothing is kept under [key]. */
    fun boolean(key: String): Boolean

    fun long(key: String): Long?

    fun put(key: String, value: String)

    fun put(key: String, value: Boolean)

    fun put(key: String, value: Long)

    fun remove(key: String)

    /** Every key something is kept under. */
    fun keys(): Set<String>
}

/** [FriendsPrefs] over a preferences file. A value of another kind than asked for reads as not there. */
class SharedFriendsPrefs(private val prefs: SharedPreferences) : FriendsPrefs {
    override fun string(key: String): String? = prefs.all[key] as? String

    override fun boolean(key: String): Boolean = prefs.all[key] as? Boolean ?: false

    override fun long(key: String): Long? = prefs.all[key] as? Long

    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    override fun put(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun put(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    override fun remove(key: String) = prefs.edit().remove(key).apply()

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    companion object {
        /** Which strap this phone has seen: facts about the strap, kept whatever account Friends is on. */
        fun strap(context: Context): FriendsPrefs =
            SharedFriendsPrefs(context.applicationContext.getSharedPreferences("noop_friends_strap", Context.MODE_PRIVATE))
    }
}
