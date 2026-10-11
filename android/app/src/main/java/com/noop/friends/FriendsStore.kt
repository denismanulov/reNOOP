package com.noop.friends

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// MARK: - Friends: the account on this phone, what the tab shows, and the daily upload
//
// A reNOOP fork feature, and off until the wearer turns it on: until then no key exists, nothing is sent
// anywhere and the rest of the app behaves exactly as it did. The server address is configuration: the
// fork's own server until the wearer names another.
//
// There is no name to type and no password. Turning Friends on makes this phone's key ([FriendsKeyStore],
// in the Android Keystore, never in a preference and never in a `.noopbak` backup) and an account bound
// to the strap worn ([FriendsStrap]). The same strap on another phone asks to join that account; a new
// strap on this phone takes the account over by itself. Swift twin: `FriendsStore`.
//
// Everything that changes what the store holds runs on one thread at a time ([confined]), as the Swift
// store runs on the main actor: a rule that reads "one at a time" is a flag read and set with no
// suspension between. A step that must finish (an enrolment, an answer to a request, an upload) runs in
// the store's own scope, so a screen that goes away mid-request stops waiting and the step still ends.

/** Where Friends stands on this phone. */
sealed class FriendsPhase {
    /** Never turned on, or turned off: no request is made. */
    object Off : FriendsPhase()

    /** Turn On was tapped, and the strap worn has a serial this phone has not read yet. */
    object WaitingForStrap : FriendsPhase()

    /** The strap already has an account. The wearer says whether it is theirs. */
    object StrapBound : FriendsPhase()

    /** This phone asked to join the strap's account and waits for an answer. */
    data class Waiting(val claim: FriendsClaim) : FriendsPhase()

    object On : FriendsPhase()

    /** What is kept between launches; a claim in flight is kept beside it. */
    val stored: String
        get() = when (this) {
            Off -> "off"
            WaitingForStrap -> "waitingForStrap"
            StrapBound -> "strapBound"
            is Waiting -> "waiting"
            On -> "on"
        }
}

/** The wearer's name and photo as the app's own profile holds them: what a friends account shows. */
class FriendsLocalProfile(
    val name: String,
    /** The profile photo as a JPEG that fits the server's limit, or null with none (or none that fits). */
    val photoJpeg: ByteArray?,
)

/** A day's key ("yyyy-MM-dd", the app's own logical day) and what the app holds for it. */
class FriendsLocalDay(val key: String, val inputs: FriendsDayInputs)

/** What the store reads out of the app: nothing of it is derived here. */
interface FriendsLocalSource {
    suspend fun profile(): FriendsLocalProfile

    /** Today and yesterday, newest first. Only the sections [share] has on are read at all. */
    suspend fun recentDays(share: FriendShare): List<FriendsLocalDay>
}

/**
 * The last feed the server answered, kept so the tab opens at once and reads offline. A file in the
 * cache directory: it is the account's, goes with it when Friends goes off, and never enters a backup.
 */
class FriendsFeedCache(private val file: File) {
    fun read(): String? = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    fun write(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val staged = File(file.parentFile, file.name + ".new")
            staged.writeText(text, Charsets.UTF_8)
            if (!staged.renameTo(file)) {
                file.delete()
                staged.renameTo(file)
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}

/**
 * The codes of the invites made on this phone, by invite id, so a waiting invite can be shown again. A
 * code is all it takes to become the wearer's friend, so it is kept out of the preferences and out of
 * every backup: one file in the directory Android leaves out of its backups ([Context.getNoBackupFilesDir]),
 * which the app's own `.noopbak` export does not read either.
 */
class FriendsInviteCodes(private val file: File) {
    fun read(): Map<String, String> {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        val obj = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in obj.keys()) (obj.opt(key) as? String)?.let { out[key] = it }
        return out
    }

    /** Keeps [codes], and nothing at all when there are none. */
    fun write(codes: Map<String, String>) {
        if (codes.isEmpty()) return clear()
        runCatching {
            file.parentFile?.mkdirs()
            val obj = org.json.JSONObject()
            for ((id, code) in codes) obj.put(id, code)
            val staged = File(file.parentFile, file.name + ".new")
            staged.writeText(obj.toString(), Charsets.UTF_8)
            if (!staged.renameTo(file)) {
                file.delete()
                staged.renameTo(file)
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FriendsStore(
    private val prefs: FriendsPrefs,
    private val keys: FriendsKeyStore,
    private val source: FriendsLocalSource,
    /** Which strap is worn. Before the registry can be read, nothing is known: a strap not read yet. */
    @Volatile var strapIdentity: suspend () -> FriendsStrap.Identity = { FriendsStrap.Identity.Pending },
    private val transport: FriendsTransport = FriendsTransport.shared,
    private val cache: FriendsFeedCache? = null,
    /** Where the codes of this phone's invites are kept; null keeps them in memory only. */
    private val inviteFile: FriendsInviteCodes? = null,
    /** Deletes the kept pictures when the account is forgotten. */
    private val clearPictures: () -> Unit = {},
    /** Withdraws an upload queued for later, when Friends goes off. */
    private val cancelQueuedUpload: () -> Unit = {},
    /** The device clock, unix seconds. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000L },
    /** Tests pass their own key and keep the key store out of it. */
    signer: FriendsSigner? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val confined: CoroutineDispatcher = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)

    /**
     * This phone's key, once it is in hand. At launch the key store is asked only when what is kept says
     * Friends was on or waiting on a claim: a phone that never turned Friends on reads nothing, and the
     * first frame of the tab pays for no Keystore round trip. A key kept behind any other phase is read
     * by the step that first needs it ([checkKey]).
     */
    private var key: FriendsSigner? = signer ?: if (keptNeedsKey()) (keys.find(serverAddress) as? FriendsKeyStore.Kept.Key)?.signer else null

    private fun keptNeedsKey(): Boolean = prefs.string(PHASE_KEY).let { it == "on" || it == "waiting" }

    /**
     * Counts the accounts this process has seen: it moves whenever Friends goes on or off. Work that
     * began under one carries the value it started with, and what it brings back is kept only while that
     * value still stands. Without it, leaving and joining as someone else while a request was in flight
     * could file the old account's answer, or the mark of a day uploaded to it, under the new one.
     */
    @Volatile
    var epoch: Int = 0
        private set

    // A key that could not be read leaves Friends off for now with what is kept untouched: [recoverKey]
    // brings the phase back once the key can be read.
    private val _phase = MutableStateFlow(keptPhase(hasKey = key != null))
    val phase: StateFlow<FriendsPhase> = _phase.asStateFlow()

    private val _feed = MutableStateFlow<FriendsFeed?>(null)
    val feed: StateFlow<FriendsFeed?> = _feed.asStateFlow()

    private val _feedFetchedAt = MutableStateFlow(0L)

    /** Device clock (unix seconds) when [feed] arrived from the server; 0 before any did. */
    val feedFetchedAt: StateFlow<Long> = _feedFetchedAt.asStateFlow()

    private val _loading = MutableStateFlow(false)

    /** A step that turns Friends on is under way, or the first feed is being fetched. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<FriendsError?>(null)

    /** The last failure; null once a call succeeds. The screen puts it into words. */
    val error: StateFlow<FriendsError?> = _error.asStateFlow()

    private val _lastUploadAt = MutableStateFlow<Long?>(null)

    /** When a day last went up from this phone (device clock, unix seconds). */
    val lastUploadAt: StateFlow<Long?> = _lastUploadAt.asStateFlow()

    private val _devices = MutableStateFlow<List<FriendsDevice>?>(null)

    /** The account's phones, once a screen has asked for them. */
    val devices: StateFlow<List<FriendsDevice>?> = _devices.asStateFlow()

    private val _invites = MutableStateFlow<List<FriendsInvite>?>(null)

    /** The account's waiting invites, once a screen has asked for them. */
    val invites: StateFlow<List<FriendsInvite>?> = _invites.asStateFlow()

    private val _sharePhoto = MutableStateFlow(prefs.string(SHARE_PHOTO_KEY) != "off")

    /** Whether the profile photo is sent for friends to see. */
    val sharePhoto: StateFlow<Boolean> = _sharePhoto.asStateFlow()

    private val _serverAddress = MutableStateFlow(serverAddress)

    /** The server address in use, for a screen to show. */
    val serverAddressFlow: StateFlow<String> = _serverAddress.asStateFlow()

    /** Whether the last refresh failed, in which case coming back to the tab tries again at once. */
    private var lastRefreshFailed = false

    /** Those codes by invite id, once they have been asked for. Not read at launch. */
    private var heldInviteCodes: Map<String, String>? = null

    /** One upload run at a time. */
    private val uploadGate = Mutex()

    init {
        if (_phase.value == FriendsPhase.On) loadKeptFeed()
    }

    /** The phase kept between launches. On, and waiting on a claim, need this phone's key. */
    private fun keptPhase(hasKey: Boolean): FriendsPhase = when (prefs.string(PHASE_KEY)) {
        "on" -> if (hasKey) FriendsPhase.On else FriendsPhase.Off
        "waiting" ->
            if (!hasKey) FriendsPhase.Off
            else prefs.string(CLAIM_KEY)?.let(FriendsJson::claim)?.let { FriendsPhase.Waiting(it) } ?: FriendsPhase.StrapBound
        "strapBound" -> FriendsPhase.StrapBound
        "waitingForStrap" -> FriendsPhase.WaitingForStrap
        else -> FriendsPhase.Off
    }

    private fun loadKeptFeed() {
        _feed.value = cache?.read()?.let { runCatching { FriendsJson.feed(it) }.getOrNull() }
        _feedFetchedAt.value = prefs.long(FEED_AT_KEY) ?: 0L
        _lastUploadAt.value = prefs.long(UPLOADED_AT_KEY)
    }

    val isOn: Boolean get() = _phase.value == FriendsPhase.On

    /** The server address in use; always one [FriendsServerUrl] accepts. */
    val serverAddress: String
        get() = prefs.string(ADDRESS_KEY)?.let(FriendsServerUrl::normalised) ?: FriendsServerUrl.DEFAULT

    val me: FriendProfile? get() = _feed.value?.me?.profile

    /** The wearer's own account id; empty before the first answer. */
    val myId: String get() = _feed.value?.me?.id.orEmpty()

    val share: FriendShare get() = _feed.value?.me?.share ?: FriendShare.DEFAULT

    /**
     * The wearer's name as friends see it: the account's. On a phone that joined the account it can
     * differ from this phone's profile, which stands in ([fallback]) only before any answer.
     */
    fun nameFriendsSee(fallback: String): String = me?.name?.takeIf { it.isNotEmpty() } ?: fallback

    /** Requests waiting for this account's answer. */
    val claims: List<FriendsClaim> get() = _feed.value?.claims.orEmpty()

    /** The account's other phones that joined without confirmation: kept or removed from this one. */
    val unconfirmed: List<FriendsDevice> get() = _feed.value?.unconfirmed.orEmpty()

    /** Unix seconds until which this phone may only read and upload; null for a confirmed phone. */
    val probationUntil: Long? get() = me?.device?.probationUntil

    /** This phone's key id, which is its id among the account's phones. */
    val deviceId: String? get() = key?.keyId

    /** The server's "now" (unix seconds) for the device time [nowDevice]: what every "N min ago" counts from. */
    fun serverNow(nowDevice: Long = now()): Long {
        val feed = _feed.value ?: return nowDevice
        return FriendsClock.serverNow(feed.serverTime, _feedFetchedAt.value, nowDevice)
    }

    /** Puts a failure on the tab, or takes the last one off it. */
    fun setError(error: FriendsError?) {
        _error.value = error
    }

    /** A client signed with this phone's key, or why there is none. Without a key nothing is sent. */
    private fun client(): FriendsResult<FriendsApi> {
        val signer = key ?: return FriendsResult.Fail(FriendsError.NOT_ENROLLED)
        val api = FriendsApi.create(serverAddress, signer, transport) ?: return FriendsResult.Fail(FriendsError.NOT_CONFIGURED)
        return FriendsResult.Ok(api)
    }

    /**
     * The same, making the key first when there is none. The key exists from the first Turn On and never
     * before it. The store is read again first, and a key is made only when it says none is kept: one
     * that is kept but cannot be read just now is never replaced.
     */
    private fun clientMakingKey(): FriendsResult<FriendsApi> {
        if (key == null) {
            key = when (val kept = keys.find(serverAddress)) {
                is FriendsKeyStore.Kept.Key -> kept.signer
                FriendsKeyStore.Kept.Unreadable -> return FriendsResult.Fail(FriendsError.KEY_NOT_READ)
                FriendsKeyStore.Kept.Absent -> try {
                    keys.create(serverAddress)
                } catch (refused: FriendsKeyException) {
                    return FriendsResult.Fail(
                        if (refused.problem == FriendsKeyProblem.NOT_READ) FriendsError.KEY_NOT_READ else FriendsError.KEY_NOT_KEPT,
                    )
                }
            }
        }
        return client()
    }

    /** How this phone's key stands before a step that would use one or make one. */
    private enum class KeyCheck {
        /** A key is in hand, or none is kept and one may be made. */
        CLEAR,

        /** The key was read again and brought back the phase kept between launches. */
        RESUMED,

        /** A key may be kept but cannot be read just now: nothing may change until it can. */
        UNREADABLE,
    }

    /**
     * Reads the key again when this process holds none. A process that could not read it came up off
     * with the kept phase still saying on, or waiting on a claim: with the key back, so is that phase.
     */
    private fun checkKey(): KeyCheck {
        if (key != null) return KeyCheck.CLEAR
        return when (val kept = keys.find(serverAddress)) {
            FriendsKeyStore.Kept.Absent -> KeyCheck.CLEAR
            FriendsKeyStore.Kept.Unreadable -> KeyCheck.UNREADABLE
            is FriendsKeyStore.Kept.Key -> {
                key = kept.signer
                val phase = keptPhase(hasKey = true)
                if (phase == _phase.value) {
                    KeyCheck.CLEAR
                } else {
                    _phase.value = phase
                    if (phase == FriendsPhase.On) loadKeptFeed()
                    KeyCheck.RESUMED
                }
            }
        }
    }

    /**
     * Brings Friends back by itself after a launch that could not read the key. Asks the key store only
     * when what is kept says Friends was on or waiting on a claim, so a phone that never turned it on
     * reads nothing.
     */
    private fun recoverKey() {
        if (key == null && keptNeedsKey()) checkKey()
    }

    /**
     * Whether a step that turns Friends on may go on. It may not when the tap found Friends as it was
     * kept, and not while a kept key cannot be read: then nothing is made, sent or changed.
     */
    private fun mayEnter(): Boolean = when (checkKey()) {
        KeyCheck.CLEAR -> true
        KeyCheck.RESUMED -> {
            _error.value = null
            false
        }
        KeyCheck.UNREADABLE -> {
            _error.value = FriendsError.KEY_NOT_READ
            false
        }
    }

    /** What naming a server came to. */
    sealed class ServerChange {
        object Done : ServerChange()

        /** The address is not one the app will talk to; nothing was written. */
        data class Refused(val problem: FriendsServerUrl.Problem) : ServerChange()

        /** Friends is not off (or is on behind a key that cannot be read just now); nothing was written. */
        object NotOff : ServerChange()
    }

    /**
     * Points Friends at the server the wearer names, or back at the standard one when [text] is empty.
     * Only while Friends is off: a server is chosen before turning on. An address [FriendsServerUrl]
     * refuses is not written. Another server is another account, so the key in hand becomes the one kept
     * for that address (none is made here) and nothing remembered about the previous server stays.
     * Naming the address already in use changes nothing.
     */
    suspend fun setServerAddress(text: String): ServerChange = withContext(confined) {
        // Off behind a key that cannot be read just now is not off, and a key that has just come back
        // may have brought Friends back with it.
        if (!mayEnter() || _phase.value != FriendsPhase.Off || _loading.value) return@withContext ServerChange.NotOff
        val typed = FriendsText.trimmed(text)
        var named: String? = null
        if (typed.isNotEmpty()) {
            named = when (val result = FriendsServerUrl.validate(typed)) {
                is FriendsServerUrl.Result.Valid -> result.url
                is FriendsServerUrl.Result.Invalid -> return@withContext ServerChange.Refused(result.problem)
            }
        }
        if ((named ?: FriendsServerUrl.DEFAULT) == serverAddress) return@withContext ServerChange.Done
        if (named != null) prefs.put(ADDRESS_KEY, named) else prefs.remove(ADDRESS_KEY)
        forgetAccount()
        transport.offset.seconds = 0
        _error.value = null
        key = (keys.find(serverAddress) as? FriendsKeyStore.Kept.Key)?.signer
        epoch++
        setPhase(FriendsPhase.Off)
        _serverAddress.value = serverAddress
        ServerChange.Done
    }

    private fun setPhase(next: FriendsPhase) {
        _phase.value = next
        prefs.put(PHASE_KEY, next.stored)
        if (next is FriendsPhase.Waiting) prefs.put(CLAIM_KEY, FriendsJson.claimText(next.claim)) else prefs.remove(CLAIM_KEY)
    }

    /** Runs [block] in the store's own scope: the caller may stop waiting, the step still ends. */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    /** Waits until nothing the store started is still running. For tests, and for a worker that must not outlive its work. */
    suspend fun settle() {
        while (true) {
            val running = scope.coroutineContext.job.children.filter { it.isActive }.toList()
            if (running.isEmpty()) return
            running.forEach { it.join() }
        }
    }

    // MARK: - Turning on

    /**
     * Turn Friends on: the one step that makes a key and sends anything. With a strap whose serial is
     * known the account is bound to it; with a strap not read yet this waits for it. The name is the
     * profile's.
     */
    suspend fun turnOn() = detached { enter(source.profile().name, bind = true) }

    /** The same with the name given outright: a name typed on the page before the profile had one. */
    suspend fun turnOn(name: String) = detached { enter(name, bind = true) }

    /**
     * Turn Friends on with no strap to bind to, or beside a strap that belongs to someone else's account.
     * The strap, once known, is bound or asked for by the next upload.
     */
    suspend fun turnOnWithoutStrap() = detached { enter(source.profile().name, bind = false) }

    suspend fun turnOnWithoutStrap(name: String) = detached { enter(name, bind = false) }

    /**
     * One at a time, and never once Friends is on: the tab asks again by itself while a strap is waited
     * for, and a second enrolment with the same key would be answered as an account the key already had.
     */
    private suspend fun enter(name: String, bind: Boolean) {
        if (!mayEnter() || _loading.value || _phase.value == FriendsPhase.On) return
        val trimmed = FriendsText.trimmed(name)
        if (trimmed.isEmpty()) {
            _error.value = FriendsError.NO_NAME
            return
        }
        _loading.value = true
        try {
            var strap: String? = null
            if (bind) {
                when (val identity = strapIdentity()) {
                    FriendsStrap.Identity.Pending -> {
                        _error.value = null
                        setPhase(FriendsPhase.WaitingForStrap)
                        return
                    }
                    is FriendsStrap.Identity.Handle -> strap = identity.handle
                    FriendsStrap.Identity.None -> Unit
                }
            }
            val api = when (val made = clientMakingKey()) {
                is FriendsResult.Ok -> made.value
                is FriendsResult.Fail -> {
                    _error.value = made.error
                    return
                }
            }
            // An earlier enrolment that got no answer may have made the account all the same.
            val unanswered = prefs.boolean(ENROL_PENDING_KEY)
            prefs.put(ENROL_PENDING_KEY, true)
            when (val answer = api.enroll(trimmed, strap)) {
                is FriendsResult.Ok -> {
                    // The strap sent is not recorded as accepted: the upload run's own request is what
                    // says so. A key that was already a phone of an account joined that account as it
                    // stands, so this phone sends neither its name nor its picture on joining. An account
                    // that an unanswered enrolment of this phone's own made is not one it joined.
                    val own = answer.value.isNew || unanswered
                    becomeOn(pushedName = if (own) trimmed else null)
                    if (!own) prefs.put(ADOPT_PROFILE_KEY, true)
                    _loading.value = false
                    refreshNow()
                }
                is FriendsResult.Fail -> when {
                    answer.error == FriendsError.STRAP_BOUND -> {
                        prefs.remove(ENROL_PENDING_KEY)
                        _error.value = null
                        setPhase(FriendsPhase.StrapBound)
                    }
                    else -> {
                        // A refusal is an answer: no account was made. No answer leaves that in doubt.
                        if (!answer.error.isUnanswered) prefs.remove(ENROL_PENDING_KEY)
                        _error.value = answer.error
                    }
                }
            }
        } finally {
            _loading.value = false
        }
    }

    /**
     * "Not Now" on the two pages that wait on the strap: Friends is off again, as before Turn On.
     * Nothing is sent, and the strap being read later finishes nothing by itself. A key this phone holds
     * is kept, and the next Turn On uses it.
     */
    suspend fun notNow() = withContext(confined) {
        if (_phase.value != FriendsPhase.WaitingForStrap && _phase.value != FriendsPhase.StrapBound) return@withContext
        _error.value = null
        setPhase(FriendsPhase.Off)
    }

    /** "This is my account": ask to join the account the strap is bound to. */
    suspend fun claimAccount() = detached { claimNow { source.profile().name } }

    suspend fun claimAccount(name: String) = detached { claimNow { name } }

    private suspend fun claimNow(name: suspend () -> String) {
        if (!mayEnter() || _loading.value || _phase.value == FriendsPhase.On) return
        _loading.value = true
        try {
            // Without the strap's handle there is no account to ask for. The page stands: waiting for
            // the strap from here would end in an enrolment, a new account where the wearer asked for theirs.
            val strap = (strapIdentity() as? FriendsStrap.Identity.Handle)?.handle
            if (strap == null) {
                _error.value = FriendsError.NO_STRAP
                return
            }
            val api = when (val made = clientMakingKey()) {
                is FriendsResult.Ok -> made.value
                is FriendsResult.Fail -> {
                    _error.value = made.error
                    return
                }
            }
            when (val answer = api.fileClaim(strap)) {
                is FriendsResult.Ok -> {
                    _error.value = null
                    setPhase(FriendsPhase.Waiting(answer.value))
                }
                is FriendsResult.Fail -> when (answer.error) {
                    FriendsError.STRAP_FREE -> {
                        // Its owner let the strap go meanwhile: there is no account to join, so make one.
                        _loading.value = false
                        enter(name(), bind = true)
                    }
                    FriendsError.ALREADY_ENROLLED -> adopt()
                    else -> _error.value = answer.error
                }
            }
        } finally {
            _loading.value = false
        }
    }

    /**
     * Withdraws the request to join and goes back to the choice. A server that could not be reached
     * still holds the request, so the page stays and says why; any answer ends the wait, since a refusal
     * means nothing of this phone's waits there.
     */
    suspend fun cancelClaim() = detached {
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.withdrawClaim()
            is FriendsResult.Fail -> api
        }
        if (answer.errorOrNull?.isUnanswered == true) {
            _error.value = answer.errorOrNull
            return@detached
        }
        // Any other answer: nothing of this phone's waits on the server.
        if (_phase.value !is FriendsPhase.Waiting) return@detached
        _error.value = null
        setPhase(FriendsPhase.StrapBound)
    }

    /** Asks how the request to join stands. Called when the tab appears and while it stays open. */
    suspend fun pollClaim() = detached { pollNow() }

    private suspend fun pollNow() {
        if (_phase.value !is FriendsPhase.Waiting) return
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.myClaim()
            is FriendsResult.Fail -> api
        }
        when (answer) {
            is FriendsResult.Ok -> {
                if (_phase.value !is FriendsPhase.Waiting) return
                when (answer.value.state) {
                    FriendsClaim.State.PENDING -> setPhase(FriendsPhase.Waiting(answer.value))
                    FriendsClaim.State.APPROVED -> adopt()
                    FriendsClaim.State.DECLINED -> {
                        _error.value = FriendsError.CLAIM_WAS_DECLINED
                        setPhase(FriendsPhase.StrapBound)
                    }
                    FriendsClaim.State.EXPIRED -> {
                        _error.value = FriendsError.CLAIM_EXPIRED
                        setPhase(FriendsPhase.StrapBound)
                    }
                }
            }
            is FriendsResult.Fail -> when {
                // The claim is gone from the server: granted long ago and cleared away, or withdrawn.
                answer.error == FriendsError.NO_CLAIM || answer.error == FriendsError.UNKNOWN_KEY -> adopt()
                // No answer is not an answer: the screen keeps showing the code.
                answer.error.isUnanswered -> Unit
                else -> _error.value = answer.error
            }
        }
    }

    /**
     * The request was granted: this phone is one of the account's. It joins without sending its own
     * profile, since what the account already shows stands.
     */
    private suspend fun adopt() {
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.me()
            is FriendsResult.Fail -> api
        }
        when (answer) {
            is FriendsResult.Ok -> {
                becomeOn(pushedName = null)
                prefs.put(ADOPT_PROFILE_KEY, true)
                _loading.value = false
                refreshNow()
            }
            is FriendsResult.Fail ->
                if (answer.error == FriendsError.UNKNOWN_KEY) setPhase(FriendsPhase.StrapBound) else _error.value = answer.error
        }
    }

    private fun becomeOn(pushedName: String?) {
        // Nothing of an earlier account on this phone may stay under the new one.
        forgetAccount()
        // Nor what API version 1 left behind, removed here once rather than at launch.
        if (!prefs.boolean(V1_REMOVED_KEY)) {
            keys.removeObsoleteToken()
            for (name in prefs.keys()) {
                if (name in V1_KEYS || name.startsWith(V1_MARK_PREFIX)) prefs.remove(name)
            }
            prefs.put(V1_REMOVED_KEY, true)
        }
        if (pushedName != null) prefs.put(PUSHED_NAME_KEY, pushedName)
        epoch++
        _error.value = null
        setPhase(FriendsPhase.On)
    }

    // MARK: - Leaving

    /** Delete the account on the server and forget it here. The strap is free again. */
    suspend fun deleteAccount(): Boolean = detached {
        val session = epoch
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.deleteAccount()
            is FriendsResult.Fail -> api
        }
        when (answer) {
            is FriendsResult.Ok -> {
                if (session == epoch) leave(deleteKey = true)
                true
            }
            is FriendsResult.Fail -> {
                handle(answer.error, session = session)
                // An account that is already gone is a deletion that worked.
                !isOn
            }
        }
    }

    /**
     * Takes this phone off the account, which goes on living on the wearer's other phones. The server
     * refuses it for the account's only confirmed phone.
     */
    suspend fun removeThisPhone(): Boolean = detached {
        val id = deviceId ?: return@detached false
        val session = epoch
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.removeDevice(id)
            is FriendsResult.Fail -> api
        }
        when (answer) {
            is FriendsResult.Ok -> {
                if (session == epoch) leave(deleteKey = true)
                true
            }
            is FriendsResult.Fail -> {
                handle(answer.error, session = session)
                !isOn
            }
        }
    }

    /**
     * Friends goes off on this phone: the one way the kept feed, the pictures and the upload marks go
     * away together, and queued uploads with them. The key goes too when the phone left by its own doing;
     * when the server stopped knowing it the key stays, so turning Friends on again asks to rejoin as the
     * same phone.
     */
    private fun leave(deleteKey: Boolean) {
        runCatching { cancelQueuedUpload() }
        if (deleteKey) {
            keys.delete(serverAddress)
            key = null
        }
        forgetAccount()
        epoch++
        setPhase(FriendsPhase.Off)
    }

    /**
     * Everything kept about the account except the server address and the key. Whether the photo is
     * shared stays too: it is the wearer's choice about their picture, not a fact about one account.
     */
    private fun forgetAccount() {
        for (name in listOf(
            FEED_AT_KEY, UPLOADED_AT_KEY, PUSHED_NAME_KEY, PUSHED_PHOTO_KEY, ADOPT_PROFILE_KEY, ENROL_PENDING_KEY, BOUND_HANDLE_KEY,
        )) {
            prefs.remove(name)
        }
        for (name in prefs.keys()) {
            if (name.startsWith(MARK_PREFIX) || name.startsWith(PUT_PREFIX)) prefs.remove(name)
        }
        cache?.clear()
        inviteFile?.clear()
        heldInviteCodes = emptyMap()
        runCatching { clearPictures() }
        _feed.value = null
        _feedFetchedAt.value = 0L
        lastRefreshFailed = false
        _devices.value = null
        _invites.value = null
        _lastUploadAt.value = null
    }

    // MARK: - Reading

    /**
     * What opening the tab and a pull both do. Before Friends is on it moves the turning-on along: a
     * strap that has since been read is enrolled with, a request to join is asked about. Once on it sends
     * today and yesterday if they changed and reads the feed; an automatic call inside
     * [AUTO_REFRESH_EVERY_S] of the last good answer does nothing, so switching tabs back and forth costs
     * no request, and [force] (a pull, a change just made) always goes. A launch that could not read the
     * key is put right first, here and wherever else Friends is refreshed.
     */
    suspend fun sync(force: Boolean) {
        val phase = withContext(confined) {
            recoverKey()
            _phase.value
        }
        when (phase) {
            FriendsPhase.Off, FriendsPhase.StrapBound -> return
            FriendsPhase.WaitingForStrap -> if (strapIdentity() != FriendsStrap.Identity.Pending) turnOn()
            is FriendsPhase.Waiting -> pollClaim()
            FriendsPhase.On -> {
                val skip = withContext(confined) {
                    val age = now() - _feedFetchedAt.value
                    !force && _feed.value != null && !lastRefreshFailed && age in 0 until AUTO_REFRESH_EVERY_S
                }
                if (skip) return
                // The upload first, so the wearer's own card is what the server holds as of now. In the
                // store's own scope: a pull runs in a coroutine the screen owns and may cancel while the
                // server is still answering, and what the server answers is shown all the same.
                detached {
                    uploadNow()
                    refreshNow()
                }
            }
        }
    }

    /** Reads the feed. */
    suspend fun refresh() = detached { refreshNow() }

    private suspend fun refreshNow() {
        recoverKey()
        if (!isOn) return
        val session = epoch
        _loading.value = _feed.value == null
        _refreshing.value = true
        try {
            val answer = when (val api = client()) {
                is FriendsResult.Ok -> api.value.feed()
                is FriendsResult.Fail -> api
            }
            // The account this was asked under is gone meanwhile: its answer belongs to nobody on screen.
            if (session != epoch) return
            when (answer) {
                is FriendsResult.Ok -> {
                    _feed.value = answer.value.first
                    _feedFetchedAt.value = now()
                    prefs.put(FEED_AT_KEY, _feedFetchedAt.value)
                    cache?.write(answer.value.second)
                    lastRefreshFailed = false
                    _error.value = null
                }
                is FriendsResult.Fail -> {
                    lastRefreshFailed = true
                    handle(answer.error, session = session)
                }
            }
        } finally {
            _loading.value = false
            _refreshing.value = false
        }
    }

    /**
     * One person in full for their own page, or why it cannot be read. Runs with the page that asked: a
     * page left before the answer came ends the request, and nothing is reported.
     */
    suspend fun person(id: String): FriendsResult<FriendPerson> = withContext(confined) {
        val session = epoch
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.userDays(id)
            is FriendsResult.Fail -> api
        }
        if (answer is FriendsResult.Fail) handle(answer.error, quiet = true, session = session)
        answer
    }

    /**
     * A person's picture as the server holds it, or null when there is none to show. Quiet: a picture
     * that did not arrive is drawn as initials, and is no reason for a notice.
     */
    suspend fun avatarBytes(id: String): ByteArray? = withContext(confined) {
        val session = epoch
        if (!isOn) return@withContext null
        val bytes = (client() as? FriendsResult.Ok)?.value?.avatar(id)?.valueOrNull
        bytes.takeIf { session == epoch }
    }

    // MARK: - Friends

    suspend fun unfriend(id: String): Boolean = detached { act { it.unfriend(id) } != null }

    /** Makes a one-time invite and remembers its code, which the server says only now. */
    suspend fun createInvite(): FriendsInvite? = detached {
        val invite = act(refreshing = false) { it.createInvite() } ?: return@detached null
        invite.code?.let { code -> keepInviteCodes(inviteCodes() + (invite.id to code)) }
        loadInvitesNow()
        invite
    }

    /** The code of an invite made on this phone, or null for one made elsewhere. */
    suspend fun inviteCode(id: String): String? = withContext(confined) { inviteCodes()[id] }

    /** The codes of this phone's waiting invites, by invite id. */
    suspend fun keptInviteCodes(): Map<String, String> = withContext(confined) { inviteCodes() }

    /** The codes kept for this phone's invites. The first call reads the file. */
    private fun inviteCodes(): Map<String, String> =
        heldInviteCodes ?: (inviteFile?.read() ?: emptyMap()).also { heldInviteCodes = it }

    private fun keepInviteCodes(codes: Map<String, String>) {
        heldInviteCodes = codes
        inviteFile?.write(codes)
    }

    suspend fun loadInvites() = withContext(confined) { loadInvitesNow() }

    private suspend fun loadInvitesNow() {
        val session = epoch
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.invites()
            is FriendsResult.Fail -> api
        }
        when (answer) {
            is FriendsResult.Ok -> {
                if (session != epoch) return
                _invites.value = answer.value
                // A code whose invite is used, revoked or expired is of no further use.
                val live = answer.value.mapTo(HashSet()) { it.id }
                val held = inviteCodes()
                val kept = held.filterKeys { it in live }
                if (kept != held) keepInviteCodes(kept)
            }
            is FriendsResult.Fail -> handle(answer.error, session = session)
        }
    }

    /** Whether the revoke itself went through, apart from the reload of the list after it. */
    suspend fun revokeInvite(id: String): Boolean = detached {
        val went = act(refreshing = false) { it.revokeInvite(id) } != null
        loadInvitesNow()
        went
    }

    /**
     * Uses an invite: whatever was typed, pasted or opened is read for its code. Answers the new friend,
     * or null with [error] set.
     */
    suspend fun redeem(text: String): FriendProfile? = detached {
        val code = FriendsInviteCode.extract(text)
        if (code == null) {
            _error.value = FriendsError.NOT_A_CODE
            return@detached null
        }
        act { it.redeem(code) }
    }

    // MARK: - Requests on the strap

    /**
     * Confirms a waiting request: a new phone joins the account, or the strap goes to who asked for it.
     * The answers below return whether the call itself went through, whatever the refresh after it did.
     */
    suspend fun approve(claim: FriendsClaim): Boolean = detached { act { it.approve(claim.id) } != null }

    suspend fun decline(claim: FriendsClaim): Boolean = detached { act { it.decline(claim.id) } != null }

    // MARK: - Phones

    /**
     * Whether the list was loaded; a refusal or a lost connection leaves it as it was, with the reason in
     * [error]. Runs with the page that asked.
     */
    suspend fun loadDevices(): Boolean = withContext(confined) { loadDevicesNow() }

    private suspend fun loadDevicesNow(): Boolean {
        val session = epoch
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.devices()
            is FriendsResult.Fail -> api
        }
        return when (answer) {
            is FriendsResult.Ok -> {
                if (session != epoch) return false
                _devices.value = answer.value
                true
            }
            is FriendsResult.Fail -> {
                handle(answer.error, session = session)
                false
            }
        }
    }

    suspend fun removeDevice(id: String): Boolean = detached {
        val went = act { it.removeDevice(id) } != null
        loadDevicesNow()
        went
    }

    /** Keeps a phone that joined without confirmation: its probation ends now. */
    suspend fun trustDevice(id: String): Boolean = detached {
        val went = act { it.trustDevice(id) } != null
        loadDevicesNow()
        went
    }

    // MARK: - Profile and sharing

    /**
     * Change the sharing switches. Turning one off erases that section on the server from every day
     * already uploaded, so what this phone remembers having sent no longer describes the server: the
     * marks are dropped and today and yesterday go up again.
     */
    suspend fun setShare(share: FriendShare): Boolean = detached {
        if (share == this.share) return@detached true
        act(refreshing = false) { it.patchMe(share = share) } ?: return@detached false
        clearUploadMarks()
        uploadNow()
        refreshNow()
        true
    }

    /** Whether the profile photo is sent. Off removes the server's copy on the next run, which is now. */
    suspend fun setSharePhoto(on: Boolean) = detached {
        if (on == _sharePhoto.value) return@detached
        // Kept as a word, not a flag, so "never chosen" still reads as on.
        prefs.put(SHARE_PHOTO_KEY, if (on) "on" else "off")
        _sharePhoto.value = on
        uploadNow()
        refreshNow()
    }

    /**
     * Everything the server holds for the account, laid out to be read; null when it cannot be fetched.
     * The answer is indented as text and never parsed, so every figure is the one the server wrote.
     */
    suspend fun exportText(): String? = withContext(confined) {
        val session = epoch
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> api.value.export()
            is FriendsResult.Fail -> api
        }
        when (answer) {
            is FriendsResult.Ok -> FriendsWire.indented(answer.value)
            is FriendsResult.Fail -> {
                handle(answer.error, session = session)
                null
            }
        }
    }

    // MARK: - Uploading

    /** What an upload run came to, for whoever queued it. */
    enum class UploadOutcome {
        /** Friends is off, or went off while this ran: nothing more was sent. */
        OFF,

        /** Every day is on the server as the app holds it (sent now, or unchanged since it was last sent). */
        DONE,

        /** The server could not be reached; worth another try when the network is back. */
        OFFLINE,

        /** The server refused; trying again as is would not help. */
        FAILED,
    }

    /**
     * Build and send today and yesterday, one run at a time. Failure is silent by design: a day that did
     * not go up goes up on the next trigger. Does nothing while Friends is off, and the strap sync that
     * led here never waits on it or learns how it went.
     */
    suspend fun uploadRecentDays(): UploadOutcome = detached {
        recoverKey()
        uploadNow()
    }

    private suspend fun uploadNow(): UploadOutcome {
        if (!isOn) return UploadOutcome.OFF
        return uploadGate.withLock {
            try {
                uploadRun()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A read that threw is one upload not made, never a crash in a worker or under the tab.
                UploadOutcome.FAILED
            }
        }
    }

    private fun outcome(error: FriendsError): UploadOutcome = when {
        error.isUnanswered -> UploadOutcome.OFFLINE
        error == FriendsError.UNKNOWN_KEY || error == FriendsError.NOT_ENROLLED -> UploadOutcome.OFF
        else -> UploadOutcome.FAILED
    }

    private suspend fun uploadRun(): UploadOutcome {
        val session = epoch
        if (!isOn) return UploadOutcome.OFF
        val api = (client() as? FriendsResult.Ok)?.value ?: return UploadOutcome.OFF
        // The account is read from the server first, so a switch turned off on another phone is honoured
        // here before anything is built.
        val account = when (val answer = api.me()) {
            is FriendsResult.Ok -> answer.value
            is FriendsResult.Fail -> {
                handle(answer.error, quiet = true, session = session)
                return outcome(answer.error)
            }
        }
        if (session != epoch) return UploadOutcome.OFF
        val share = account.share ?: return UploadOutcome.FAILED
        val profile = source.profile()
        // A phone that joined records its profile as the one already sent at its first run, confirmed or
        // not: what the wearer changes after that is a change made here.
        recordAdoptedProfile(profile)
        // A phone that joined without confirmation reads and uploads its days; the strap and the profile
        // wait until it is confirmed.
        if (account.device?.probationUntil == null) {
            bindStrap(api, session)
            if (session != epoch) return UploadOutcome.OFF
            pushProfile(api, profile, account.avatarRev, session)
        }
        if (session != epoch) return UploadOutcome.OFF
        val days = source.recentDays(share)
        val keep = days.mapTo(HashSet()) { it.key }
        var result = UploadOutcome.DONE
        for (day in days) {
            // The account this run started under is gone: nothing more is sent with its key.
            if (session != epoch) return UploadOutcome.OFF
            val nowTs = now()
            val built = FriendsDayPayload.build(day.inputs, share, nowTs)
            val json = FriendsDayPayload.toJson(built)
            if (!FriendsUploadPolicy.shouldUpload(uploadMark(day.key), json, built.isEmpty, hasPut(day.key))) continue
            // Recorded before the request goes out: one whose answer is lost still reached the server.
            notePut(day.key, keep)
            when (val sent = api.putDay(day.key, json)) {
                is FriendsResult.Ok -> {
                    if (session != epoch) return UploadOutcome.OFF
                    recordUpload(day.key, FriendsDayPayload.fingerprint(json), keep, nowTs)
                }
                is FriendsResult.Fail -> {
                    handle(sent.error, quiet = true, session = session)
                    // A refused day is not worth stopping for; a phone the server no longer knows, or no network, is.
                    if (sent.error == FriendsError.UNKNOWN_KEY || sent.error.isUnanswered) return outcome(sent.error)
                    result = UploadOutcome.FAILED
                }
            }
        }
        return result
    }

    /**
     * Tells the server which strap is worn, when it is not the one the server last accepted. A new strap
     * on the same phone takes the account over this way, with nothing for the wearer to do. A strap that
     * belongs to another account is asked for: the feed carries that request, and every run asks again
     * until it is settled.
     */
    private suspend fun bindStrap(api: FriendsApi, session: Int) {
        val strap = (strapIdentity() as? FriendsStrap.Identity.Handle)?.handle ?: return
        if (strap == prefs.string(BOUND_HANDLE_KEY)) return
        when (val answer = api.putStrap(strap)) {
            is FriendsResult.Ok -> {
                if (session != epoch) return
                if (answer.value == FriendsStrapAnswer.Bound) prefs.put(BOUND_HANDLE_KEY, strap)
            }
            is FriendsResult.Fail -> handle(answer.error, quiet = true, session = session)
        }
    }

    /**
     * The profile as it would go to the server: the name, the photo when it is shared, and what stands
     * for that photo on record ("none" for no photo, "off" for Photo switched off).
     */
    private class ProfileToSend(val name: String, val photo: ByteArray?, val photoMark: String)

    private fun profileToSend(profile: FriendsLocalProfile): ProfileToSend {
        val shared = _sharePhoto.value
        val photo = if (shared) profile.photoJpeg else null
        val mark = photo?.let(FriendsKeys::sha256Hex) ?: if (shared) "none" else "off"
        return ProfileToSend(FriendsText.trimmed(profile.name), photo, mark)
    }

    /**
     * A phone that joined an account records its own name and photo as sent without sending them, once:
     * what the account already shows stands. Nothing is sent here, so it does not wait for the phone to
     * be confirmed. With Photo off nothing is recorded for the picture, so [pushProfile] still removes
     * the account's.
     */
    private fun recordAdoptedProfile(profile: FriendsLocalProfile) {
        if (!prefs.boolean(ADOPT_PROFILE_KEY)) return
        val toSend = profileToSend(profile)
        prefs.put(PUSHED_NAME_KEY, toSend.name)
        if (_sharePhoto.value) prefs.put(PUSHED_PHOTO_KEY, toSend.photoMark)
        prefs.remove(ADOPT_PROFILE_KEY)
    }

    /**
     * Sends the profile's name and photo when the wearer changed them on this phone since they were last
     * sent. Never because the server's differ: two phones of one account with different profiles would
     * otherwise overwrite each other forever. Photo switched off is the one exception: it is the wearer's
     * word about the account's picture, so the picture goes whoever sent it. [avatarRev] is the account's
     * as this run read it: 0 when it has no picture.
     */
    private suspend fun pushProfile(api: FriendsApi, profile: FriendsLocalProfile, avatarRev: Int, session: Int) {
        val toSend = profileToSend(profile)
        if (toSend.name.isNotEmpty() && toSend.name != prefs.string(PUSHED_NAME_KEY)) {
            when (val answer = api.patchMe(name = toSend.name)) {
                is FriendsResult.Ok -> {
                    if (session != epoch) return
                    prefs.put(PUSHED_NAME_KEY, toSend.name)
                }
                is FriendsResult.Fail -> return handle(answer.error, quiet = true, session = session)
            }
        }
        val sent = prefs.string(PUSHED_PHOTO_KEY)
        if (toSend.photoMark == sent) return
        val sentAPicture = sent != null && sent != "none" && sent != "off"
        val answer: FriendsResult<*> = when {
            toSend.photo != null -> api.putAvatar(toSend.photo)
            sentAPicture || (!_sharePhoto.value && avatarRev > 0) -> api.deleteAvatar()
            else -> FriendsResult.Ok(Unit)
        }
        if (answer is FriendsResult.Fail) return handle(answer.error, quiet = true, session = session)
        if (session != epoch) return
        prefs.put(PUSHED_PHOTO_KEY, toSend.photoMark)
    }

    /** The fingerprint of the text the server last accepted for [day], or null when none is on record. */
    fun uploadMark(day: String): String? = prefs.string(MARK_PREFIX + day)

    /** Records an accepted upload of [day] and forgets the marks of every day not in [keep]. */
    private fun recordUpload(day: String, fingerprint: String, keep: Set<String>, at: Long) {
        for (name in prefs.keys()) {
            if (!name.startsWith(MARK_PREFIX)) continue
            val marked = name.removePrefix(MARK_PREFIX)
            if (marked != day && marked !in keep) prefs.remove(name)
        }
        prefs.put(MARK_PREFIX + day, fingerprint)
        prefs.put(UPLOADED_AT_KEY, at)
        _lastUploadAt.value = at
    }

    /** Whether this phone has sent [day] to this account, with or without an answer. */
    private fun hasPut(day: String): Boolean = prefs.boolean(PUT_PREFIX + day)

    /**
     * Records that [day] is being sent and forgets the records of every day not in [keep]. Unlike the
     * fingerprints these outlive a change of the sharing switches; they go when the account does.
     */
    private fun notePut(day: String, keep: Set<String>) {
        for (name in prefs.keys()) {
            if (name.startsWith(PUT_PREFIX) && name.removePrefix(PUT_PREFIX) !in keep) prefs.remove(name)
        }
        prefs.put(PUT_PREFIX + day, true)
    }

    /**
     * Forgets every fingerprint, so the next upload sends each day again. Needed whenever the server may
     * no longer hold what was sent.
     */
    private fun clearUploadMarks() {
        for (name in prefs.keys()) if (name.startsWith(MARK_PREFIX)) prefs.remove(name)
    }

    // MARK: - Plumbing

    /** One call with this phone's key. The value, or null with [error] set. */
    private suspend fun <T> act(refreshing: Boolean = true, call: suspend (FriendsApi) -> FriendsResult<T>): T? {
        val session = epoch
        val answer = when (val api = client()) {
            is FriendsResult.Ok -> call(api.value)
            is FriendsResult.Fail -> api
        }
        return when (answer) {
            is FriendsResult.Ok -> {
                if (session != epoch) return null
                _error.value = null
                if (refreshing) refreshNow()
                answer.value
            }
            is FriendsResult.Fail -> {
                handle(answer.error, session = session)
                null
            }
        }
    }

    /**
     * A phone the server no longer knows ends here, once, for every screen. An answer for an earlier
     * account (Friends has since gone off, or on as someone else) says nothing about the one on screen.
     */
    private fun handle(error: FriendsError, quiet: Boolean = false, session: Int) {
        if (session != epoch) return
        // Nothing was sent and nothing ended: Friends is off, which the tab already shows.
        if (error == FriendsError.NOT_ENROLLED) return
        if (error == FriendsError.UNKNOWN_KEY) {
            // Removed from the account by another phone, or the account is gone.
            leave(deleteKey = false)
            _error.value = FriendsError.LEFT_ACCOUNT
            return
        }
        if (!quiet) _error.value = error
    }

    companion object {
        /** The server address when the wearer named their own. Plain configuration; the key is NOT here. */
        const val ADDRESS_KEY = "server_url"
        const val PHASE_KEY = "friends.phase"
        const val CLAIM_KEY = "friends.claim"

        /** When the kept feed arrived, and when a day last went up (device clock, unix seconds). */
        const val FEED_AT_KEY = "friends.feedAt"
        const val UPLOADED_AT_KEY = "friends.uploadedAt"

        /** One fingerprint per uploaded day, under this prefix and the day's key. */
        const val MARK_PREFIX = "friends.uploaded."

        /** Under this prefix and a day's key: this phone has sent that day, whether or not an answer came. */
        const val PUT_PREFIX = "friends.put."

        /** What this phone last sent of the wearer's profile, so it sends again only after a change made here. */
        const val PUSHED_NAME_KEY = "friends.pushedName"
        const val PUSHED_PHOTO_KEY = "friends.pushedPhoto"

        /**
         * Set when this phone joined an account that already had a profile: the next run records this
         * phone's profile as sent without sending it.
         */
        const val ADOPT_PROFILE_KEY = "friends.adoptProfile"

        /** Set while an enrolment has been sent and no answer to it has come: the account may exist. */
        const val ENROL_PENDING_KEY = "friends.enrolPending"
        const val SHARE_PHOTO_KEY = "friends.sharePhoto"

        /** The strap handle the server last accepted for the account. */
        const val BOUND_HANDLE_KEY = "friends.boundHandle"

        /** Set once what API version 1 left on this phone has been removed. */
        const val V1_REMOVED_KEY = "friends.v1Removed"

        /** What version 1 kept in this file: the nickname, the account, its feed and its upload marks. */
        internal val V1_KEYS = setOf("nick", "me", "feed", "feed_at", "upload_at")
        internal const val V1_MARK_PREFIX = "uploaded."

        /** Coming back to the tab inside this long of a good answer shows that answer. */
        const val AUTO_REFRESH_EVERY_S = 60L

        private const val FILE = "noop_friends"

        @Volatile private var instance: FriendsStore? = null

        /** The app's one store, built the first time anything asks for it. No request is made here. */
        fun get(context: Context): FriendsStore =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * Whether Friends was on when this phone last kept its phase: one read of an ordinary preference,
         * with no store built and no Keystore asked. What the strap-sync hook checks before it queues
         * anything, so with Friends off it costs nothing more.
         */
        fun isKeptOn(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(PHASE_KEY, null) == "on"

        private fun build(app: Context): FriendsStore {
            val strapPrefs = SharedFriendsPrefs.strap(app)
            return FriendsStore(
                prefs = SharedFriendsPrefs(app.getSharedPreferences(FILE, Context.MODE_PRIVATE)),
                keys = AndroidFriendsKeyStore(app),
                source = FriendsAppSource(app),
                strapIdentity = {
                    val registry = (app as? com.noop.NoopApplication)?.deviceRegistry
                    if (registry == null) FriendsStrap.Identity.Pending else FriendsStrap.identity(registry, strapPrefs)
                },
                cache = FriendsFeedCache(File(File(app.cacheDir, "friends"), "feed.json")),
                inviteFile = FriendsInviteCodes(File(File(app.noBackupFilesDir, "friends"), "invites.json")),
                clearPictures = { FriendsAvatars.clear(app) },
                cancelQueuedUpload = { FriendsUploadScheduler.cancel(app) },
            )
        }
    }
}
