package com.noop.ui.friends

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noop.friends.FriendPerson
import com.noop.friends.FriendProfile
import com.noop.friends.FriendRequests
import com.noop.friends.FriendShare
import com.noop.friends.FriendsApi
import com.noop.friends.FriendsAvatars
import com.noop.friends.FriendsError
import com.noop.friends.FriendsFeed
import com.noop.friends.FriendsJson
import com.noop.friends.FriendsNick
import com.noop.friends.FriendsResult
import com.noop.friends.FriendsServerInfo
import com.noop.friends.FriendsServerUrl
import com.noop.friends.FriendsSessionEnd
import com.noop.friends.FriendsStore
import com.noop.friends.FriendsUploadScheduler
import com.noop.friends.FriendsUploader
import com.noop.ui.ProfileAvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - Friends tab: its state and every call it makes
//
// One instance for the activity, shared by the tab's five screens, created the first time a Friends screen
// is shown. Creating it reads two preferences and nothing else: with nobody signed in it makes no request,
// and the only calls it can make then are the ones the sign-in form asks for by name.

/** The feed on screen: the last answer, when it arrived, and how the last attempt to renew it went. */
internal data class FriendsFeedState(
    val feed: FriendsFeed? = null,
    /** Device clock (unix seconds) when [feed] arrived from the server. */
    val fetchedAt: Long = 0L,
    val refreshing: Boolean = false,
    /** Why the last refresh failed; null after a good one. [feed] is then the kept, older answer. */
    val error: FriendsError? = null,
    /** False until the saved feed has been read back, so an empty first frame is not taken for "no friends". */
    val restored: Boolean = false,
)

/** The pending requests, with the same two facts about their freshness. */
internal data class FriendsRequestsState(
    val requests: FriendRequests? = null,
    val loading: Boolean = false,
    val error: FriendsError? = null,
)

/** One friend's page. */
internal data class FriendPageState(
    val person: FriendPerson? = null,
    val loading: Boolean = false,
    val error: FriendsError? = null,
)

internal class FriendsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FriendsStore.get(app)

    /** The signed-in nickname; null shows the sign-in form. */
    val nick: StateFlow<String?> = store.nick

    private val _me = MutableStateFlow(store.cachedMe())
    val me: StateFlow<FriendProfile?> = _me.asStateFlow()

    private val _feed = MutableStateFlow(FriendsFeedState())
    val feed: StateFlow<FriendsFeedState> = _feed.asStateFlow()

    private val _requests = MutableStateFlow(FriendsRequestsState())
    val requests: StateFlow<FriendsRequestsState> = _requests.asStateFlow()

    private val _pages = MutableStateFlow<Map<String, FriendPageState>>(emptyMap())
    val pages: StateFlow<Map<String, FriendPageState>> = _pages.asStateFlow()

    private val _serverUrl = MutableStateFlow(store.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _lastUploadAt = MutableStateFlow(store.lastUploadAt)

    /** When a day last went up from this phone (device clock, unix seconds). */
    val lastUploadAt: StateFlow<Long?> = _lastUploadAt.asStateFlow()

    private val _sessionEnded = MutableStateFlow(false)

    /** True after the server ended the session under the wearer: the sign-in form says why it is back. */
    val sessionEnded: StateFlow<Boolean> = _sessionEnded.asStateFlow()

    private var refreshJob: Job? = null

    init {
        if (store.isSignedIn) {
            viewModelScope.launch {
                val restored = withContext(Dispatchers.IO) {
                    store.cachedFeed()?.let { cached ->
                        runCatching { FriendsJson.feed(cached.json) }.getOrNull()?.let { it to cached.fetchedAt }
                    }
                }
                _feed.update { state ->
                    // A fresh answer that beat the disk read stands.
                    if (state.feed != null) state.copy(restored = true)
                    else state.copy(feed = restored?.first, fetchedAt = restored?.second ?: 0L, restored = true)
                }
            }
        } else {
            _feed.value = FriendsFeedState(restored = true)
        }
    }

    // MARK: The server's clock

    /** The server's "now" for [nowDevice] (unix seconds): what every "N min ago" on the tab counts from. */
    fun serverNow(nowDevice: Long): Long {
        val state = _feed.value
        val feed = state.feed ?: return nowDevice
        return FriendsClock.serverNow(feed.serverTime, state.fetchedAt, nowDevice)
    }

    // MARK: Signing in

    /** Whether [nick] (already cleaned) is free. Asked only by the sign-up form, while it is being typed in. */
    suspend fun nickFree(nick: String): FriendsResult<Boolean> =
        anonymousApi()?.nickFree(nick) ?: FriendsResult.Fail(FriendsError.NOT_SIGNED_IN)

    /** What the server asks of a sign-up. Asked together with the first nickname check, never on its own. */
    suspend fun serverInfo(): FriendsResult<FriendsServerInfo> =
        anonymousApi()?.info() ?: FriendsResult.Fail(FriendsError.NOT_SIGNED_IN)

    /** Creates an account ([create]) or signs in to one. Null on success, else why not. */
    suspend fun signIn(create: Boolean, nickTyped: String, password: String, invite: String?): FriendsError? {
        val nick = FriendsNick.clean(nickTyped) ?: return FriendsError.BAD_NICK
        val api = anonymousApi() ?: return FriendsError.NOT_SIGNED_IN
        val answer = if (create) api.register(nick, password, invite?.trim()) else api.login(nick, password)
        return when (answer) {
            is FriendsResult.Fail -> answer.error
            is FriendsResult.Ok -> {
                val saved = withContext(Dispatchers.IO) { store.saveSession(answer.value) }
                if (!saved) return FriendsError.UNKNOWN
                _me.value = answer.value.me
                _sessionEnded.value = false
                _feed.value = FriendsFeedState(restored = true)
                refresh(force = true)
                null
            }
        }
    }

    /** Points the tab at another server. Only while signed out: a session belongs to the server that gave it. */
    fun setServerUrl(raw: String): FriendsServerUrl.Result {
        if (store.isSignedIn) return FriendsServerUrl.Result.Invalid(FriendsServerUrl.Problem.MALFORMED)
        val result = FriendsServerUrl.validate(raw)
        if (result is FriendsServerUrl.Result.Valid) {
            store.serverUrl = result.url
            _serverUrl.value = result.url
        }
        return result
    }

    // MARK: The feed

    /**
     * Sends today and yesterday if they changed, then fetches the feed. Called when the tab opens and on
     * a pull. An automatic call inside [AUTO_REFRESH_EVERY_S] of the last good answer does nothing, so
     * switching tabs back and forth costs no request; [force] (a pull, a change just made) always goes.
     */
    fun refresh(force: Boolean = false) {
        if (!store.isSignedIn) return
        if (refreshJob?.isActive == true) return
        val state = _feed.value
        val age = nowSec() - state.fetchedAt
        if (!force && state.feed != null && state.error == null && age in 0 until AUTO_REFRESH_EVERY_S) return
        refreshJob = viewModelScope.launch {
            _feed.update { it.copy(refreshing = true) }
            // The upload first, so the "you" on the tab is what the server holds as of now.
            withContext(Dispatchers.IO) { FriendsUploader.run(getApplication()) }
            _lastUploadAt.value = store.lastUploadAt
            if (!store.isSignedIn) {
                sessionWasEnded()
                return@launch
            }
            fetchFeed()
        }
    }

    /** A pull to refresh: starts a refresh (or joins the one running) and returns when it has finished. */
    suspend fun refreshNow() {
        refresh(force = true)
        refreshJob?.join()
    }

    /** Fetches the feed without the upload: after a change that touches friends, not this phone's days. */
    fun refreshFeedOnly() {
        if (!store.isSignedIn) return
        viewModelScope.launch {
            refreshJob?.join()
            fetchFeed()
        }
    }

    private suspend fun fetchFeed() {
        val api = sessionApi()
        if (api == null) {
            _feed.update { it.copy(refreshing = false, error = FriendsError.NOT_SIGNED_IN) }
            return
        }
        when (val answer = api.feed()) {
            is FriendsResult.Ok -> {
                val (feed, text) = answer.value
                val now = nowSec()
                val mine = feed.me.profile.copy(share = feed.me.share)
                withContext(Dispatchers.IO) {
                    store.saveFeed(text, now)
                    store.saveMe(mine)
                }
                _me.value = mine
                _feed.value = FriendsFeedState(feed = feed, fetchedAt = now, restored = true)
            }
            is FriendsResult.Fail ->
                if (answer.error == FriendsError.UNAUTHORIZED) endSessionLocally(ended = true)
                else _feed.update { it.copy(refreshing = false, error = answer.error, restored = true) }
        }
    }

    // MARK: Friends

    fun loadRequests() {
        if (!store.isSignedIn) return
        viewModelScope.launch {
            _requests.update { it.copy(loading = true) }
            val api = sessionApi()
            when (val answer = api?.requests() ?: FriendsResult.Fail(FriendsError.NOT_SIGNED_IN)) {
                is FriendsResult.Ok -> _requests.value = FriendsRequestsState(requests = answer.value)
                is FriendsResult.Fail ->
                    if (answer.error == FriendsError.UNAUTHORIZED) endSessionLocally(ended = true)
                    else _requests.update { it.copy(loading = false, error = answer.error) }
            }
        }
    }

    /** Looks a person up by exact nickname. */
    suspend fun lookup(nickTyped: String): FriendsResult<FriendProfile> {
        val nick = FriendsNick.clean(nickTyped) ?: return FriendsResult.Fail(FriendsError.BAD_NICK)
        return guarded { it.user(nick) }
    }

    suspend fun sendRequest(nick: String): FriendsResult<FriendProfile> =
        guarded { it.sendRequest(nick) }.also { afterFriendChange(it) }

    suspend fun acceptRequest(nick: String): FriendsResult<FriendProfile> =
        guarded { it.acceptRequest(nick) }.also { afterFriendChange(it) }

    /** Declines an incoming request or withdraws an outgoing one. */
    suspend fun dropRequest(nick: String): FriendsResult<Unit> =
        guarded { it.deleteRequest(nick) }.also { afterFriendChange(it) }

    suspend fun unfriend(nick: String): FriendsResult<Unit> =
        guarded { it.unfriend(nick) }.also {
            if (it is FriendsResult.Ok) _pages.update { pages -> pages - nick }
            afterFriendChange(it)
        }

    private fun afterFriendChange(result: FriendsResult<*>) {
        if (result !is FriendsResult.Ok) return
        loadRequests()
        refreshFeedOnly()
    }

    /** Loads one friend's page in full (the day's heart-rate line included). */
    fun loadPage(nick: String) {
        if (!store.isSignedIn) return
        viewModelScope.launch {
            _pages.update { it + (nick to (it[nick] ?: FriendPageState()).copy(loading = true)) }
            when (val answer = guarded { it.userDays(nick) }) {
                is FriendsResult.Ok -> _pages.update { it + (nick to FriendPageState(person = answer.value)) }
                is FriendsResult.Fail -> _pages.update {
                    it + (nick to (it[nick] ?: FriendPageState()).copy(loading = false, error = answer.error))
                }
            }
        }
    }

    // MARK: My profile

    suspend fun rename(name: String): FriendsError? = profileCall { it.patchMe(name = name.trim()) }

    /**
     * Changes the sharing switches. The server erases a switched-off section from every stored day, so
     * what this phone remembers having sent no longer describes the server: the marks are dropped and
     * today and yesterday go up again, at once and (should the app be closed first) from the queue.
     */
    suspend fun setShare(share: FriendShare): FriendsError? {
        val error = profileCall { it.patchMe(share = share) }
        if (error == null) {
            withContext(Dispatchers.IO) { store.clearUploadMarks() }
            FriendsUploadScheduler.enqueueNow(getApplication())
            refresh(force = true)
        }
        return error
    }

    /** Makes the on-device profile photo the picture friends see. */
    suspend fun uploadProfilePhoto(): FriendsError? {
        val jpeg = withContext(Dispatchers.IO) {
            ProfileAvatarStore.storedJpeg(getApplication())?.let { FriendsAvatars.fitForUpload(it) }
        } ?: return FriendsError.BAD_IMAGE
        return profileCall { it.putAvatar(jpeg) }
    }

    suspend fun removePicture(): FriendsError? {
        val answer = guarded { it.deleteAvatar() }
        if (answer is FriendsResult.Ok) {
            _me.value?.copy(avatarRev = 0)?.let { cleared ->
                _me.value = cleared
                withContext(Dispatchers.IO) { store.saveMe(cleared) }
            }
            refreshFeedOnly()
        }
        return answer.errorOrNull
    }

    suspend fun changePassword(old: String, new: String): FriendsError? =
        when (val answer = guarded { it.changePassword(old, new) }) {
            is FriendsResult.Ok -> {
                val kept = withContext(Dispatchers.IO) { store.replaceToken(answer.value) }
                if (kept) null else FriendsError.UNKNOWN
            }
            is FriendsResult.Fail -> answer.error
        }

    /** Signs out on this phone. The server is told when it can be reached; the phone forgets either way. */
    suspend fun signOut() {
        sessionApi()?.logout()
        endSessionLocally(ended = false)
    }

    /** Deletes the account on the server, then forgets it here. Null on success. */
    suspend fun deleteAccount(password: String): FriendsError? {
        // Not through [guarded]: a wrong password here is a 401 that must not sign the phone out.
        val api = sessionApi() ?: return FriendsError.NOT_SIGNED_IN
        return when (val answer = api.deleteAccount(password)) {
            is FriendsResult.Ok -> {
                endSessionLocally(ended = false)
                null
            }
            is FriendsResult.Fail -> {
                if (answer.error == FriendsError.UNAUTHORIZED) endSessionLocally(ended = true)
                answer.error
            }
        }
    }

    private suspend fun profileCall(call: suspend (FriendsApi) -> FriendsResult<FriendProfile>): FriendsError? =
        when (val answer = guarded(call)) {
            is FriendsResult.Ok -> {
                // A profile answer always carries the switches; keep the old ones if a server ever omits them.
                val mine = answer.value.let { p -> if (p.share == null) p.copy(share = _me.value?.share) else p }
                _me.value = mine
                withContext(Dispatchers.IO) { store.saveMe(mine) }
                refreshFeedOnly()
                null
            }
            is FriendsResult.Fail -> answer.error
        }

    // MARK: Plumbing

    /** A call with the session. A dead session ends it here, once, for every screen. */
    private suspend fun <T> guarded(call: suspend (FriendsApi) -> FriendsResult<T>): FriendsResult<T> {
        val api = sessionApi() ?: return FriendsResult.Fail(FriendsError.NOT_SIGNED_IN)
        val answer = call(api)
        if (answer.errorOrNull == FriendsError.UNAUTHORIZED) endSessionLocally(ended = true)
        return answer
    }

    private fun anonymousApi(): FriendsApi? = FriendsApi.create(store.serverUrl, null)

    private suspend fun sessionApi(): FriendsApi? {
        if (!store.isSignedIn) return null
        val token = withContext(Dispatchers.IO) { store.token() } ?: return null
        return FriendsApi.create(store.serverUrl, token)
    }

    private suspend fun endSessionLocally(ended: Boolean) {
        withContext(Dispatchers.IO) { FriendsSessionEnd.local(getApplication()) }
        if (ended) sessionWasEnded() else resetState()
    }

    private fun sessionWasEnded() {
        resetState()
        _sessionEnded.value = true
    }

    private fun resetState() {
        _me.value = null
        _feed.value = FriendsFeedState(restored = true)
        _requests.value = FriendsRequestsState()
        _pages.value = emptyMap()
        _lastUploadAt.value = null
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    private companion object {
        /** Coming back to the tab inside this long of a good answer shows that answer. */
        const val AUTO_REFRESH_EVERY_S = 60L
    }
}
