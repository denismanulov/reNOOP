package com.noop.friends

import android.content.Context
import com.noop.NoopApplication
import com.noop.ble.ExperimentalBrand
import com.noop.ble.SourceIdentity
import com.noop.data.DeviceRegistry
import com.noop.data.PairedDeviceRow
import com.noop.data.WhoopSerialIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// MARK: - Friends: the strap an account is bound to
//
// The friends account is tied to the strap, so the same strap on a new phone leads back to it and a new
// strap on this phone takes it over. What stands for the strap on the server is its handle: the SHA-256
// of the registry's serial-derived id (`whoop-<SERIAL>`, `oura-<SERIAL>`). The serial itself never leaves
// the phone. Swift twin: `FriendsStrap`.
//
// A legacy single-WHOOP install keeps the registry id `my-whoop` and is never re-pointed onto its serial,
// so the id alone does not say which strap it is. Where a WHOOP's serial is confirmed, its serial-derived
// id is therefore recorded against the registry id, and this file reads it back. The WHOOP 4.0's device
// key, which arrives beside its serial, is not involved: it is never read.

object FriendsStrap {
    /** What the active device gives Friends to bind the account to. */
    sealed class Identity {
        /** A strap whose serial is known: its handle. */
        data class Handle(val handle: String) : Identity()

        /** A strap that has a serial, not read on this phone yet. */
        object Pending : Identity()

        /** A device with no serial to bind to (a watch, a chest strap, an import). */
        object None : Identity()
    }

    /** Under this prefix and a registry id: the serial-derived id of the strap registered there. */
    const val ADOPTED_ID_PREFIX = "friends.strapAdoptedId."

    /** Under this prefix and a registry id: a serial-derived id seen once there and not trusted yet. */
    const val SIGHTED_ID_PREFIX = "friends.strapSightedId."

    /** The handle of a serial-derived id. Lower-case hex, 64 characters. */
    fun handle(adoptedId: String): String =
        FriendsKeys.sha256Hex(FriendsWire.strapHandleInput(adoptedId).toByteArray(Charsets.UTF_8))

    /**
     * Records that the strap registered as [deviceId] has the serial-derived id [adoptedId]. Called where
     * a serial is confirmed; writes only when the value changed.
     */
    fun note(adoptedId: String, deviceId: String, prefs: FriendsPrefs) {
        if (adoptedId.isEmpty() || deviceId.isEmpty()) return
        val key = ADOPTED_ID_PREFIX + deviceId
        if (prefs.string(key) != adoptedId) prefs.put(key, adoptedId)
    }

    fun adoptedId(deviceId: String, prefs: FriendsPrefs): String? =
        prefs.string(ADOPTED_ID_PREFIX + deviceId)?.takeIf { it.isNotEmpty() }

    /**
     * A WHOOP 4.0's serial is trusted once the same value has come from two connections
     * (`RepeatedSerialGate`, #1193). That gate counts within one run of the app, so a strap that stays
     * connected, or an app relaunched between its connections, never gets there. This keeps the first
     * sighting, so the second may come in a later run: the value is recorded for [deviceId] when it
     * repeats, and the answer says whether it is recorded now. Call it once per hello; the two sightings
     * are still two hellos, which is all the gate's rule asks for.
     */
    fun sight(adoptedId: String, deviceId: String, prefs: FriendsPrefs): Boolean {
        if (adoptedId.isEmpty() || deviceId.isEmpty()) return false
        val key = SIGHTED_ID_PREFIX + deviceId
        if (adoptedId(deviceId, prefs) == adoptedId) {
            prefs.remove(key)
            return true
        }
        if (prefs.string(key) != adoptedId) {
            prefs.put(key, adoptedId)
            return false
        }
        note(adoptedId, deviceId, prefs)
        prefs.remove(key)
        return true
    }

    /** Whether a device of this kind has a serial to bind to: a WHOOP strap or an Oura ring. */
    fun hasSerial(device: PairedDeviceRow): Boolean =
        SourceIdentity.isWhoop(device) || device.id.startsWith(ExperimentalBrand.OURA.idPrefix + "-")

    /**
     * [deviceId] itself when it is already serial-derived (`whoop-<SERIAL>`, `oura-<SERIAL>`), else null.
     * A remainder that names a pairing is refused, since such an id says which connection it was and not
     * which strap: the Bluetooth address the wizard mints an id from on Android before any serial is
     * read, and the UUID it mints one from on iOS, which a restored backup can carry. A WHOOP id must
     * also be the one [WhoopSerialIdentity] would produce from its own remainder. The legacy `my-whoop`
     * seed has neither prefix and relies on the value recorded where its serial is confirmed.
     */
    fun serialDerivedId(deviceId: String): String? {
        val whoopPrefix = WhoopSerialIdentity.ID_PREFIX + "-"
        val ouraPrefix = ExperimentalBrand.OURA.idPrefix + "-"
        val remainder = when {
            deviceId.startsWith(whoopPrefix) -> {
                val rest = deviceId.substring(whoopPrefix.length)
                if (WhoopSerialIdentity.adoptedId(rest) != deviceId) return null
                rest
            }
            deviceId.startsWith(ouraPrefix) -> deviceId.substring(ouraPrefix.length)
            else -> return null
        }
        return if (remainder.isEmpty() || UUID.matches(remainder) || ADDRESS.matches(remainder)) null else deviceId
    }

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val ADDRESS = Regex("^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$")

    fun identity(hasSerial: Boolean, adoptedId: String?): Identity = when {
        adoptedId != null -> Identity.Handle(handle(adoptedId))
        hasSerial -> Identity.Pending
        else -> Identity.None
    }

    /**
     * The identity of the registry's active device among [devices]: its own id when that is already
     * serial-derived, else the id recorded for it where its serial was confirmed. An id that names its
     * strap is never overruled by a recorded value, which may be left from another strap. With no
     * registry read yet ([devices] null) or nothing active, nothing is known yet: that reads as a strap
     * not read, never as no strap, so an account is not made unbound by a race with launch.
     */
    fun identity(devices: List<PairedDeviceRow>?, prefs: FriendsPrefs): Identity {
        val active = devices?.firstOrNull { it.status == ACTIVE } ?: return Identity.Pending
        val adopted = serialDerivedId(active.id) ?: adoptedId(active.id, prefs)
        return identity(hasSerial(active), adopted)
    }

    /** The same, read from the registry. A registry that cannot be read is a strap not read yet. */
    suspend fun identity(registry: DeviceRegistry, prefs: FriendsPrefs): Identity =
        identity(runCatching { registry.all() }.getOrNull(), prefs)

    /** The registry's word for the one device in use. */
    private const val ACTIVE = "active"
}

/**
 * Where a WHOOP's serial reaches Friends. Nothing here touches the connection: it reads the registry and
 * writes two preferences.
 */
object FriendsStrapWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One hello at a time: a sighting is a read and a write of the same preference. */
    private val gate = Mutex()

    /** What a hello's serial came to, with the line it leaves in the strap log (never any of the serial). */
    enum class Sighting(val logLine: String?) {
        /** Not a serial, or the active device is not a WHOOP: nothing was counted. */
        IGNORED(null),
        ONCE("Friends: the strap's serial was seen once - it counts when the same value is seen again"),
        RECORDED("Friends: the strap's serial was seen a second time - recorded for the account"),

        /** The value was on record already. */
        KNOWN(null),
    }

    /**
     * A WHOOP 4.0 hello carried [serial]. Counted toward the two sightings Friends asks for, in
     * preferences, so the count outlives the process: the BLE client's own gate counts within one run
     * and guards the re-pointing of history, while this one guards only the id Friends binds an account
     * to. Called for every hello, whether or not anything listens for the client's serial: the client
     * also runs with no screen alive, and a hello seen then is a sighting all the same.
     */
    fun onWhoop4Hello(context: Context, serial: String, log: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val sighting = gate.withLock { sight(serial, registry(app)?.all(), SharedFriendsPrefs.strap(app)) }
                sighting.logLine?.let(log)
            }
        }
    }

    /**
     * A WHOOP's serial was confirmed (a 5/MG's on its first read, a 4.0's on its second sighting within
     * one run). Kept against the id the active row has now and the id it will have once adopted.
     */
    fun onSerialConfirmed(context: Context, serial: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching { gate.withLock { confirm(serial, registry(app)?.all(), SharedFriendsPrefs.strap(app)) } }
        }
    }

    private fun registry(app: Context): DeviceRegistry? = (app as? NoopApplication)?.deviceRegistry

    /** The counting itself, apart from where the registry and the preferences come from. */
    internal fun sight(serial: String, devices: List<PairedDeviceRow>?, prefs: FriendsPrefs): Sighting {
        val adopted = WhoopSerialIdentity.adoptedId(serial) ?: return Sighting.IGNORED
        val active = devices?.firstOrNull { it.status == "active" }?.takeIf(SourceIdentity::isWhoop) ?: return Sighting.IGNORED
        val known = FriendsStrap.adoptedId(active.id, prefs) == adopted
        if (!FriendsStrap.sight(adopted, active.id, prefs)) return Sighting.ONCE
        FriendsStrap.note(adopted, adopted, prefs)
        return if (known) Sighting.KNOWN else Sighting.RECORDED
    }

    internal fun confirm(serial: String, devices: List<PairedDeviceRow>?, prefs: FriendsPrefs) {
        val adopted = WhoopSerialIdentity.adoptedId(serial) ?: return
        val active = devices?.firstOrNull { it.status == "active" }?.takeIf(SourceIdentity::isWhoop) ?: return
        FriendsStrap.note(adopted, active.id, prefs)
        FriendsStrap.note(adopted, adopted, prefs)
    }
}
