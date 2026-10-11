package com.noop.ui.friends

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noop.friends.FriendPerson
import com.noop.friends.FriendProfile
import com.noop.friends.FriendShare
import com.noop.friends.FriendsClaim
import com.noop.friends.FriendsDevice
import com.noop.friends.FriendsError
import com.noop.friends.FriendsFeed
import com.noop.friends.FriendsInvite
import com.noop.friends.FriendsPhase
import com.noop.friends.FriendsResult
import com.noop.friends.FriendsServerUrl
import com.noop.friends.FriendsStore
import com.noop.ui.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - Friends tab: what its screens read and ask for
//
// One instance for the activity, shared by the tab's screens, created the first time a Friends screen is
// shown. Everything that matters lives in [FriendsStore], which outlives every screen: this class hands
// the store's state to Compose and keeps what belongs to a screen alone (a friend's page as it loaded, the
// invite being shown). With Friends off it makes no request.

/** The feed on screen: the last answer, when it arrived, and how the last attempt to renew it went. */
internal data class FriendsFeedState(
    val feed: FriendsFeed? = null,
    /** Device clock (unix seconds) when [feed] arrived from the server. */
    val fetchedAt: Long = 0L,
    val refreshing: Boolean = false,
    /** The last failure; null after a good answer. [feed] is then the kept, older one. */
    val error: FriendsError? = null,
)

/** One friend's page. */
internal data class FriendPageState(
    val person: FriendPerson? = null,
    val loading: Boolean = false,
    val error: FriendsError? = null,
)

/** An invite and its code together: the code is known only on the phone that made the invite. */
internal data class FriendsShownInvite(
    val invite: FriendsInvite,
    /** As shown, "XXXXX-XXXXX". */
    val code: String,
)

/**
 * An invite that arrived by a link (`renoop://friends/add?c=…`) and waits for the wearer's yes. Nobody is
 * added by opening a link: the shell shows the Friends tab, and the tab asks first. An invite that
 * arrives while Friends is off waits here until it is on.
 */
internal object FriendsInviteLinks {
    private val _code = MutableStateFlow<String?>(null)

    /** The code waiting to be asked about. */
    val code: StateFlow<String?> = _code.asStateFlow()

    private val _arrived = MutableStateFlow(false)

    /** True from a link's arrival until the shell has shown the Friends tab for it. */
    val arrived: StateFlow<Boolean> = _arrived.asStateFlow()

    /** Hands over the invite an activity intent carries, if it carries one. */
    fun deliver(intent: Intent?) {
        val code = friendsInviteCodeOfLink(intent?.data?.toString()) ?: return
        _code.value = code
        _arrived.value = true
    }

    fun shown() {
        _arrived.value = false
    }

    /** The wearer answered (or the code was dropped). */
    fun take() {
        _code.value = null
    }
}

internal class FriendsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FriendsStore.get(app)

    /** Where Friends stands on this phone: anything but on shows the turning-on page. */
    val phase: StateFlow<FriendsPhase> = store.phase

    /** A step that turns Friends on is under way. */
    val loading: StateFlow<Boolean> = store.loading

    /** The last failure of any call, for the page that is on screen to put into words. */
    val error: StateFlow<FriendsError?> = store.error

    val feed: StateFlow<FriendsFeedState> =
        combine(store.feed, store.feedFetchedAt, store.refreshing, store.error) { feed, fetchedAt, refreshing, error ->
            FriendsFeedState(feed, fetchedAt, refreshing, error)
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            FriendsFeedState(store.feed.value, store.feedFetchedAt.value, store.refreshing.value, store.error.value),
        )

    /** The wearer's own account as the server last described it, with its sharing switches. */
    val me: StateFlow<FriendProfile?> = store.feed.map { feed -> feed?.me?.let { it.profile.copy(share = it.share) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.feed.value?.me?.let { it.profile.copy(share = it.share) })

    val serverUrl: StateFlow<String> = store.serverAddressFlow

    /** When a day last went up from this phone (device clock, unix seconds). */
    val lastUploadAt: StateFlow<Long?> = store.lastUploadAt

    val devices: StateFlow<List<FriendsDevice>?> = store.devices
    val invites: StateFlow<List<FriendsInvite>?> = store.invites
    val sharePhoto: StateFlow<Boolean> = store.sharePhoto

    private val _pages = MutableStateFlow<Map<String, FriendPageState>>(emptyMap())
    val pages: StateFlow<Map<String, FriendPageState>> = _pages.asStateFlow()

    private val _inviteCodes = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The codes of the waiting invites this phone made, by invite id. */
    val inviteCodes: StateFlow<Map<String, String>> = _inviteCodes.asStateFlow()

    private val _shownInvite = MutableStateFlow<FriendsShownInvite?>(null)

    /** The invite the invite page is showing. */
    val shownInvite: StateFlow<FriendsShownInvite?> = _shownInvite.asStateFlow()

    private val _profileName = MutableStateFlow(ProfileStore.from(app).displayName)

    /** The name in the app's own profile: what turning Friends on sends as the wearer's name. */
    val profileName: StateFlow<String> = _profileName.asStateFlow()

    init {
        // Friends can go off outside any screen (the background upload found the phone off the account):
        // what belonged to that account leaves the screens' own state then too.
        viewModelScope.launch {
            store.phase.collect { phase ->
                if (phase != FriendsPhase.On) {
                    _pages.value = emptyMap()
                    _inviteCodes.value = emptyMap()
                    _shownInvite.value = null
                }
            }
        }
    }

    // MARK: Clocks and what waits

    /** The server's "now" for [nowDevice] (unix seconds): what every "N min ago" on the tab counts from. */
    fun serverNow(nowDevice: Long): Long = store.serverNow(nowDevice)

    /** Unix seconds until which this phone may only read and upload; null for a confirmed phone. */
    val probationUntil: Long? get() = store.probationUntil

    /** The name friends see: the account's, and this phone's profile only before any answer. */
    fun nameFriendsSee(): String = store.nameFriendsSee(_profileName.value)

    fun reloadProfileName() {
        _profileName.value = ProfileStore.from(getApplication()).displayName
    }

    fun dismissError() = store.setError(null)

    // MARK: Turning on

    /** Turn Friends on. [typedName] is the name typed on the page when the profile had none: it becomes the profile's. */
    suspend fun turnOn(typedName: String?) {
        keepTypedName(typedName)
        store.turnOn()
    }

    suspend fun turnOnWithoutStrap() = store.turnOnWithoutStrap()

    suspend fun claimAccount() = store.claimAccount()

    suspend fun cancelClaim() = store.cancelClaim()

    suspend fun notNow() = store.notNow()

    private suspend fun keepTypedName(typedName: String?) {
        val name = typedName?.trim().orEmpty()
        if (name.isEmpty()) return
        withContext(Dispatchers.IO) { ProfileStore.from(getApplication()).displayName = name }
        _profileName.value = name
    }

    /** Points the tab at another server. Only while Friends is off: each server keeps its own account. */
    suspend fun setServerUrl(raw: String): FriendsStore.ServerChange = store.setServerAddress(raw)

    // MARK: The feed

    /**
     * What opening the tab does: before Friends is on it moves the turning-on along; once on it sends
     * today and yesterday if they changed and fetches the feed. Throttled inside the store, so switching
     * tabs back and forth costs no request; [force] (a pull, a change just made) always goes.
     */
    fun sync(force: Boolean = false) {
        viewModelScope.launch { store.sync(force) }
    }

    /** A pull to refresh: returns when the answer is on screen. */
    suspend fun syncNow() = store.sync(force = true)

    /** Asks again about whatever the turning-on page waits for. */
    suspend fun syncQuietly() = store.sync(force = false)

    // MARK: Friends

    /** Loads one friend's page in full (the day's heart-rate line included). */
    fun loadPage(id: String) {
        if (!store.isOn) return
        viewModelScope.launch {
            _pages.update { it + (id to (it[id] ?: FriendPageState()).copy(loading = true)) }
            when (val answer = store.person(id)) {
                is FriendsResult.Ok -> _pages.update { it + (id to FriendPageState(person = answer.value)) }
                is FriendsResult.Fail -> _pages.update {
                    it + (id to (it[id] ?: FriendPageState()).copy(loading = false, error = answer.error))
                }
            }
        }
    }

    /** Null when it went through, else why not. */
    suspend fun unfriend(id: String): FriendsError? = outcome { store.unfriend(id) }.also {
        if (it == null) _pages.update { pages -> pages - id }
    }

    /** Makes a one-time invite and shows it. Null when it was made, else why not. */
    suspend fun createInvite(): FriendsError? {
        store.setError(null)
        val made = store.createInvite()
        val code = made?.code
        if (made == null || code == null) return store.error.value ?: FriendsError.UNKNOWN
        _shownInvite.value = FriendsShownInvite(made, code)
        _inviteCodes.value = store.keptInviteCodes()
        return null
    }

    /** Shows a waiting invite again. Only one made on this phone has a code to show. */
    fun show(invite: FriendsInvite) {
        val code = _inviteCodes.value[invite.id] ?: return
        _shownInvite.value = FriendsShownInvite(invite, code)
    }

    fun loadInvites() {
        if (!store.isOn) return
        viewModelScope.launch {
            store.loadInvites()
            _inviteCodes.value = store.keptInviteCodes()
        }
    }

    suspend fun revokeInvite(id: String): FriendsError? = outcome { store.revokeInvite(id) }.also {
        _inviteCodes.value = store.keptInviteCodes()
    }

    /** Uses an invite: whatever was typed, pasted or opened. The new friend, or why not. */
    suspend fun redeem(text: String): FriendsResult<FriendProfile> {
        store.setError(null)
        val friend = store.redeem(text)
        return if (friend != null) FriendsResult.Ok(friend) else FriendsResult.Fail(store.error.value ?: FriendsError.UNKNOWN)
    }

    // MARK: Requests and phones

    suspend fun approve(claim: FriendsClaim): FriendsError? = outcome { store.approve(claim) }

    suspend fun decline(claim: FriendsClaim): FriendsError? = outcome { store.decline(claim) }

    suspend fun trustDevice(id: String): FriendsError? = outcome { store.trustDevice(id) }

    suspend fun removeDevice(id: String): FriendsError? = outcome { store.removeDevice(id) }

    suspend fun removeThisPhone(): FriendsError? = outcome { store.removeThisPhone() }

    /**
     * Loads the account's phones. Null when the list was loaded; else why not, or null too when the page
     * was left before the answer came (nothing failed then).
     */
    suspend fun loadDevices(): FriendsError? {
        store.setError(null)
        if (store.loadDevices()) {
            // Whether this phone is still unconfirmed is the feed's to say: asked again with the list,
            // so the row's label and what the page offers come from the same moment.
            store.refresh()
            return null
        }
        return store.error.value
    }

    /** Everything the server holds for the account, as text; or why it could not be fetched. */
    suspend fun exportText(): FriendsResult<String> {
        store.setError(null)
        val text = store.exportText()
        return if (text != null) FriendsResult.Ok(text) else FriendsResult.Fail(store.error.value ?: FriendsError.UNKNOWN)
    }

    // MARK: My sharing

    suspend fun setShare(share: FriendShare): FriendsError? = outcome { store.setShare(share) }

    suspend fun setSharePhoto(on: Boolean) = store.setSharePhoto(on)

    /** Deletes the account on the server, then forgets it here. Null on success. */
    suspend fun deleteAccount(): FriendsError? = outcome { store.deleteAccount() }

    /**
     * One answer the wearer gave. A call that failed leaves the store's failure as the reason; one that
     * went through leaves none, even when the refresh after it failed: the tab reports that, and the
     * thing asked for was done.
     */
    private suspend fun outcome(call: suspend () -> Boolean): FriendsError? {
        store.setError(null)
        return if (call()) null else store.error.value ?: FriendsError.UNKNOWN
    }

    /** The reason an address was not taken, for the server dialog. */
    fun problemOf(change: FriendsStore.ServerChange): FriendsServerUrl.Problem? =
        (change as? FriendsStore.ServerChange.Refused)?.problem
}
