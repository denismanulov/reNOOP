package com.noop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.ble.LiveState
import com.noop.ble.SourceCoordinator
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind

// MARK: - What one registered device is doing right now
//
// Resolved once so the list row, the active card and the device page can never disagree (the
// "two readouts of one fact" rule): the link word, the charge, and the flags the page's controls are
// gated on. Twin of Swift `DeviceReadout` / `DevicePillState`.

/**
 * The link word, as a priority-ordered pure decision (#221): archived beats everything; on the active
 * device, reconnecting > bond-refused > live > not connected; any other device reads "Not connected".
 * Pinned by `DevicePillStateTest`, mirroring the Swift `DevicePillStateTests`.
 */
internal enum class DeviceLink { REMOVED, NOT_CONNECTED, RECONNECTING, NOT_PAIRED, CONNECTED }

internal fun deviceLink(
    isArchived: Boolean,
    isActive: Boolean,
    isReconnecting: Boolean,
    bondRefused: Boolean,
    isLiveConnected: Boolean,
): DeviceLink = when {
    isArchived -> DeviceLink.REMOVED
    !isActive -> DeviceLink.NOT_CONNECTED
    // Reboot window (#166): the user's Restart dropped the link and reNOOP is auto-reconnecting.
    isReconnecting -> DeviceLink.RECONNECTING
    // #221: BLE-connected but the encrypted bond was refused — no data flows, so not "Connected".
    bondRefused -> DeviceLink.NOT_PAIRED
    isLiveConnected -> DeviceLink.CONNECTED
    else -> DeviceLink.NOT_CONNECTED
}

/**
 * One device's live state, read off [LiveState] for THIS device only: the live link, its charge and its
 * bond belong to whichever device is active, never to the others.
 */
internal data class DeviceReadout(
    val isActive: Boolean,
    val isArchived: Boolean,
    val isWhoop: Boolean,
    val isOura: Boolean,
    /** An import partition (I-1): never an active-device candidate. */
    val isImportSource: Boolean,
    /** Active and linked. */
    val isLive: Boolean,
    /** #221: linked, but the encrypted bond was refused (#78) — no data flows. */
    val bondRefused: Boolean,
    /** A user restart is in flight and the link is down (#166). */
    val reconnecting: Boolean,
    /** The live charge: a WHOOP, a generic strap and an FTMS machine funnel into `batteryPct`, a ring
     *  reports its own (#2075). Null when not live. */
    val batteryPct: Int?,
    val link: DeviceLink,
) {
    /** The charge worth showing: none while the bond is refused, since nothing that link says is trusted. */
    val shownBattery: Int? get() = batteryPct?.takeIf { !bondRefused }

    companion object {
        fun make(d: PairedDeviceRow, live: LiveState, ringPct: Int?): DeviceReadout {
            val isActive = d.status == DeviceStatus.active.name
            val isArchived = d.status == DeviceStatus.archived.name
            val isWhoop = SourceCoordinator.isWhoop(d)
            val isLive = isActive && live.connected
            val bondRefused = isLive && live.pairingHint != null
            val reconnecting = isActive && live.rebootInProgress && !live.connected
            val pct = if (isLive) {
                LiveConsoleReadout.batteryPercent(activeIsWhoop = isWhoop, whoopPct = live.batteryPct, ringPct = ringPct)
            } else null
            return DeviceReadout(
                isActive = isActive,
                isArchived = isArchived,
                isWhoop = isWhoop,
                isOura = d.sourceKind == SourceKind.oura.name,
                isImportSource = isImportSource(d),
                isLive = isLive,
                bondRefused = bondRefused,
                reconnecting = reconnecting,
                batteryPct = pct,
                link = deviceLink(isArchived, isActive, reconnecting, bondRefused, isLive),
            )
        }

        /** Imported data partitions (a WHOOP export, a Health import, activity files) are registry rows
         *  but never a live source, so a tap never makes one active (I-1). */
        fun isImportSource(d: PairedDeviceRow): Boolean = d.sourceKind == SourceKind.cloudImport.name ||
            d.sourceKind == SourceKind.fileImport.name || d.sourceKind == SourceKind.activityFile.name
    }
}

/** The link word in the app's language. */
@Composable
internal fun deviceLinkLabel(link: DeviceLink): String = stringResource(
    when (link) {
        DeviceLink.REMOVED -> R.string.devices_link_removed
        DeviceLink.NOT_CONNECTED -> R.string.devices_link_not_connected
        DeviceLink.RECONNECTING -> R.string.devices_link_reconnecting
        DeviceLink.NOT_PAIRED -> R.string.devices_link_not_paired
        DeviceLink.CONNECTED -> R.string.devices_link_connected
    },
)

/** "Connected · 82 %", as Bluetooth and the Watch app caption a device. */
@Composable
internal fun deviceStatusLine(r: DeviceReadout): String {
    val word = deviceLinkLabel(r.link)
    val pct = r.shownBattery ?: return word
    return "$word · " + java.text.NumberFormat.getPercentInstance().format(pct / 100.0)
}

/** The glyph a device is drawn with: a strap, a ring, a heart-rate strap, a gym machine, an import. */
internal fun deviceGlyph(d: PairedDeviceRow): ImageVector = when {
    d.sourceKind == SourceKind.oura.name -> Icons.Filled.RadioButtonUnchecked
    d.sourceKind == SourceKind.ftms.name -> Icons.AutoMirrored.Filled.DirectionsRun
    d.sourceKind == SourceKind.huami.name -> Icons.Filled.Watch
    DeviceReadout.isImportSource(d) -> Icons.Filled.FileDownload
    SourceCoordinator.isWhoop(d) -> Icons.Filled.Watch
    else -> Icons.Filled.MonitorHeart
}

/**
 * Collapsed display name (mirrors Swift `PairedDevice.displayName`): the nickname if present, else the
 * model if it already contains the brand (so the seeded WHOOP/WHOOP reads "WHOOP", not "WHOOP WHOOP"),
 * else "brand model".
 */
internal fun displayName(device: PairedDeviceRow): String {
    device.nickname?.takeIf { it.isNotBlank() }?.let { return it }
    return if (device.model.contains(device.brand, ignoreCase = true)) device.model
    else "${device.brand} ${device.model}"
}

/** Best-effort brand from the advertised name. Falls back to a neutral label. Mirrors Swift brandGuess.
 *  Delegates to the pure [com.noop.data.DeviceBrandCatalog] (single source of truth) so the token table
 *  lives once. */
internal fun brandGuess(name: String): String =
    com.noop.data.DeviceBrandCatalog.specForAdvertisedName(name)?.brand ?: "Heart-rate strap"

/** RSSI (negative dBm) → 0..4 signal level, coarse buckets. Matches the Swift SignalBars.level. */
internal object SignalBars {
    fun level(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}
