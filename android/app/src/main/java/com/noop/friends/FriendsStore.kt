package com.noop.friends

import android.content.Context
import android.content.SharedPreferences
import com.noop.data.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// MARK: - Friends: the session and what the tab keeps between launches
//
// The session token is the only secret and lives in the encrypted preferences ([SecurePrefs]). Everything
// else is in an ordinary preferences file of its own: the server address, the signed-in account as last
// seen, the last feed (so the tab opens at once and reads offline), when a day last went up, and one
// fingerprint per uploaded day. Signing out clears all of it except the server address.
//
// "Signed in" is answered from the ordinary file (the nickname is there), so the strap-sync hook and the
// tab's first frame never pay for a Keystore round trip to learn that nobody is signed in.

class FriendsStore private constructor(private val app: Context) {
    private val prefs: SharedPreferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _nick = MutableStateFlow(prefs.getString(KEY_NICK, null))

    /** The signed-in nickname, or null with no account on this phone. */
    val nick: StateFlow<String?> = _nick.asStateFlow()

    val isSignedIn: Boolean get() = _nick.value != null

    /**
     * Counts the sessions this process has seen: it moves whenever one starts or ends. Work that began
     * under one session (a feed being fetched, a day being uploaded) carries the value it started with,
     * and what it brings back is written only while that value still stands. Without it, signing out and
     * in as someone else while a request was in flight could file the old account's answer, or the mark
     * of a day uploaded to the old account, under the new one.
     */
    @Volatile
    var epoch: Int = 0
        private set

    /** The server address in use; always one [FriendsServerUrl] accepts. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null)?.let(FriendsServerUrl::normalised) ?: FriendsServerUrl.DEFAULT
        set(value) {
            prefs.edit().putString(KEY_SERVER, value).apply()
        }

    /** The session token, or null when signed out or when the encrypted store cannot be opened. */
    fun token(): String? = runCatching { secure().getString(KEY_TOKEN, null) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Keeps a fresh session. False when the token could not be stored, in which case nothing changed. */
    fun saveSession(session: FriendsSession): Boolean {
        val stored = runCatching { secure().edit().putString(KEY_TOKEN, session.token).commit() }.getOrDefault(false)
        if (!stored) return false
        prefs.edit()
            .putString(KEY_NICK, session.me.nick)
            .putString(KEY_ME, FriendsJson.profileText(session.me))
            .apply()
        epoch++
        _nick.value = session.me.nick
        return true
    }

    /** Swaps the token after a password change. */
    fun replaceToken(token: String): Boolean =
        runCatching { secure().edit().putString(KEY_TOKEN, token).commit() }.getOrDefault(false)

    /** The signed-in account as the server last described it. */
    fun cachedMe(): FriendProfile? =
        prefs.getString(KEY_ME, null)?.let { runCatching { FriendsJson.profile(it) }.getOrNull() }

    fun saveMe(profile: FriendProfile, startedIn: Int = epoch) {
        if (!isSignedIn || startedIn != epoch) return
        prefs.edit().putString(KEY_ME, FriendsJson.profileText(profile)).apply()
    }

    /** The last feed the server answered, with the device time it arrived at (unix seconds). */
    class CachedFeed(val json: String, val fetchedAt: Long)

    fun cachedFeed(): CachedFeed? {
        val json = prefs.getString(KEY_FEED, null) ?: return null
        return CachedFeed(json, prefs.getLong(KEY_FEED_AT, 0L))
    }

    fun saveFeed(json: String, fetchedAt: Long, startedIn: Int = epoch) {
        if (!isSignedIn || startedIn != epoch) return
        prefs.edit().putString(KEY_FEED, json).putLong(KEY_FEED_AT, fetchedAt).apply()
    }

    /** When a day last went up (device clock, unix seconds), or null before the first upload. */
    val lastUploadAt: Long? get() = prefs.getLong(KEY_UPLOAD_AT, 0L).takeIf { it > 0L }

    /** The fingerprint of the text the server last accepted for [day], or null when none is on record. */
    fun uploadMark(day: String): String? = prefs.getString(MARK_PREFIX + day, null)

    /** Records an accepted upload of [day] and forgets the marks of every day not in [keep]. */
    fun recordUpload(day: String, fingerprint: String, keep: Set<String>, atSec: Long, startedIn: Int = epoch) {
        if (!isSignedIn || startedIn != epoch) return
        val edit = prefs.edit().putString(MARK_PREFIX + day, fingerprint).putLong(KEY_UPLOAD_AT, atSec)
        for (key in prefs.all.keys) {
            if (key.startsWith(MARK_PREFIX) && key.removePrefix(MARK_PREFIX) !in keep + day) edit.remove(key)
        }
        edit.apply()
    }

    /**
     * Forgets every fingerprint, so the next upload sends each day again. Needed whenever the server may
     * no longer hold what was sent: a sharing switch erases its section from every stored day.
     */
    fun clearUploadMarks() {
        val edit = prefs.edit()
        for (key in prefs.all.keys) if (key.startsWith(MARK_PREFIX)) edit.remove(key)
        edit.apply()
    }

    /** Signs this phone out locally: the token, the account, the feed and every upload mark go. */
    fun clearSession() {
        runCatching { secure().edit().remove(KEY_TOKEN).commit() }
        val edit = prefs.edit().remove(KEY_NICK).remove(KEY_ME).remove(KEY_FEED).remove(KEY_FEED_AT).remove(KEY_UPLOAD_AT)
        for (key in prefs.all.keys) if (key.startsWith(MARK_PREFIX)) edit.remove(key)
        edit.apply()
        epoch++
        _nick.value = null
    }

    private fun secure(): SharedPreferences = SecurePrefs.of(app, SECURE_FILE)

    companion object {
        private const val FILE = "noop_friends"
        private const val SECURE_FILE = "noop_friends_secure"
        private const val KEY_TOKEN = "token"
        private const val KEY_SERVER = "server_url"
        private const val KEY_NICK = "nick"
        private const val KEY_ME = "me"
        private const val KEY_FEED = "feed"
        private const val KEY_FEED_AT = "feed_at"
        private const val KEY_UPLOAD_AT = "upload_at"
        private const val MARK_PREFIX = "uploaded."

        @Volatile private var instance: FriendsStore? = null

        fun get(context: Context): FriendsStore =
            instance ?: synchronized(this) {
                instance ?: FriendsStore(context.applicationContext).also { instance = it }
            }
    }
}
