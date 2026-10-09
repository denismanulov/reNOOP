package com.noop.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.icu.text.ListFormatter
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Other apps on this phone that can connect to the same WHOOP.
 *
 * Two apps on one strap split its history: the strap drops a chunk as soon as ANY app acks it, so the
 * hours one app syncs first never reach the other ([ForeignOffloadDetector] is the runtime half of this).
 * From Android 11 an app can ask about another package only when its manifest lists it under `<queries>`,
 * so every id in [known] has an entry there.
 *
 * Unlike the Swift twin, which sees an app only as an installed URL scheme, Android also answers whether
 * that app holds the Bluetooth permission. So an app the user took the Nearby devices permission from
 * drops off this list, exactly as an uninstalled one does. Whether a listed app has ever paired with the
 * strap is not readable: "can connect" is all a name here means.
 */
object OtherStrapApps {
    /** One strap app by the name shown for it, and the application ids it installs under. */
    data class Known(val name: String, val packages: List<String>)

    val known: List<Known> = listOf(
        // Upstream NOOP: its release id, and the debug and staging builds that install beside it.
        Known("NOOP", listOf("com.noop.whoop", "com.noop.whoop.debug", "com.noop.whoop.staging")),
        Known("WHOOP", listOf("com.whoop.android")),
    )

    /** Names of the known strap apps that are installed and may use Bluetooth, in [known] order. */
    fun ableToSync(context: Context): List<String> {
        val packages = context.packageManager
        return ableToSync { id ->
            canConnect(
                installed = runCatching { packages.getPackageInfo(id, 0) }.isSuccess,
                sdkInt = Build.VERSION.SDK_INT,
                bluetoothGranted = {
                    packages.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, id) == PackageManager.PERMISSION_GRANTED
                },
            )
        }
    }

    /** [ableToSync] over any per-id answer, so the selection is testable without a device. */
    internal fun ableToSync(canConnect: (String) -> Boolean): List<String> =
        known.filter { app -> app.packages.any(canConnect) }.map { it.name }

    /**
     * Whether an app can reach a strap: it has to be installed, and from Android 12 it has to hold
     * BLUETOOTH_CONNECT, which the user sees and revokes as Nearby devices. Before Android 12 Bluetooth
     * was granted at install, so installed is the whole answer.
     */
    internal fun canConnect(installed: Boolean, sdkInt: Int, bluetoothGranted: () -> Boolean): Boolean =
        installed && (!nearbyDevicesRevocable(sdkInt) || bluetoothGranted())

    /**
     * Whether the user can take Bluetooth away from another app without uninstalling it. From Android 12
     * it is the Nearby devices permission, revocable in Settings; before that uninstalling is the only
     * advice that works.
     */
    fun nearbyDevicesRevocable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

    /** "NOOP", "NOOP and WHOOP": the names as one phrase in [locale], or null when there is none. */
    fun phrase(names: List<String>, locale: Locale): String? =
        phrase(names) { ListFormatter.getInstance(locale).format(it) }

    /** [phrase] with the list wording injected; one name needs none. */
    internal fun phrase(names: List<String>, join: (List<String>) -> String): String? = when (names.size) {
        0 -> null
        1 -> names[0]
        else -> join(names)
    }
}

/**
 * The in-app warning for a second app pulling this strap's history, raised by [WhoopBleClient] when
 * [ForeignOffloadDetector] has the evidence. Shown at most once per process, and never again once the
 * user says so: someone who deliberately runs two apps should not be nagged every launch. The mute is the
 * `noop.otherAppWarningMuted` preference, which the caller reads and the dialog writes. Twin of the Swift
 * `OtherStrapAppWarning`.
 */
class OtherStrapAppWarning {
    private val _presented = MutableStateFlow(false)

    /** True while the warning should be on screen. */
    val presented: StateFlow<Boolean> = _presented.asStateFlow()
    private val shownThisProcess = AtomicBoolean(false)

    fun reportForeignOffload(muted: Boolean) {
        if (muted) return
        if (!shownThisProcess.compareAndSet(false, true)) return
        _presented.value = true
    }

    fun dismiss() {
        _presented.value = false
    }

    companion object {
        val shared = OtherStrapAppWarning()
    }
}
