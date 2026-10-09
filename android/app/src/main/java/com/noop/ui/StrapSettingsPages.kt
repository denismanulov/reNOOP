package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.ble.BackgroundHealth
import com.noop.ble.PuffinExperiment
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.LocalTonalIcons
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SwitchRow
import com.noop.ui.m3.TonalIcon
import com.noop.ui.m3.TonalPair

// MARK: - The strap's own settings, under Devices
//
// One group of rows, each opening a small page: Sync, Power saving, Double-tap, Haptics, Heart rate
// broadcast (Swift `StrapSettingsCard` + its pages). Same prefs, the same AppViewModel calls and the same
// BLE wiring the old Power saving, Automations, Data Sources and Settings cards made; nothing new is sent
// to the strap. On Android "Sync" is the background connection (the iOS page's screen-awake and Dynamic
// Island options have no twin here).

/** The pages the strap group opens. */
internal enum class StrapPage(val key: String) {
    SYNC("sync"), POWER_SAVING("power"), DOUBLE_TAP("doubletap"), HAPTICS("haptics"), HR_BROADCAST("broadcast");

    companion object {
        fun fromKey(key: String): StrapPage? = entries.firstOrNull { it.key == key }
    }
}

/** One row per strap setting, with its state in grey on the right. */
@Composable
internal fun StrapSettingsGroup(vm: AppViewModel, onOpen: (StrapPage) -> Unit) {
    val context = LocalContext.current
    val icons = LocalTonalIcons.current
    val doubleTap by vm.doubleTapAction.collectAsStateWithLifecycle()
    val phoneBroadcast by vm.hrBroadcast.collectAsStateWithLifecycle()
    val strapBroadcast = PuffinExperiment.from(context).broadcastHr
    val on = stringResource(R.string.devices_on)
    val off = stringResource(R.string.devices_off)
    ListGroup(header = stringResource(R.string.devices_strap_settings)) {
        item { shape ->
            StrapRow(shape, Icons.Filled.Sync, icons.green, stringResource(R.string.devices_sync),
                if (NoopPrefs.backgroundConnection(context)) on else off) { onOpen(StrapPage.SYNC) }
        }
        item { shape ->
            StrapRow(shape, Icons.Filled.BatterySaver, icons.green, stringResource(R.string.devices_power_saving),
                if (NoopPrefs.powerSaving(context)) on else off) { onOpen(StrapPage.POWER_SAVING) }
        }
        item { shape ->
            StrapRow(shape, Icons.Filled.TouchApp, icons.blue, stringResource(R.string.devices_double_tap),
                doubleTapLabel(doubleTap)) { onOpen(StrapPage.DOUBLE_TAP) }
        }
        item { shape ->
            StrapRow(shape, Icons.Filled.Vibration, icons.orange, stringResource(R.string.devices_haptics), null) {
                onOpen(StrapPage.HAPTICS)
            }
        }
        item { shape ->
            StrapRow(shape, Icons.Filled.Sensors, icons.red, stringResource(R.string.devices_hr_broadcast),
                if (phoneBroadcast || strapBroadcast) on else off) { onOpen(StrapPage.HR_BROADCAST) }
        }
    }
}

@Composable
private fun StrapRow(
    shape: Shape,
    icon: ImageVector,
    tint: TonalPair,
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    ListRow(
        shape = shape,
        title = title,
        subtitle = value,
        leading = { TonalIcon(icon, tint) },
        trailing = { ChevronRight() },
        onClick = onClick,
    )
}

/** The double-tap action in the app's language. */
@Composable
internal fun doubleTapLabel(action: DoubleTapAction): String = stringResource(
    when (action) {
        DoubleTapAction.NONE -> R.string.devices_tap_nothing
        DoubleTapAction.BUZZ_BACK -> R.string.devices_tap_buzz_back
        DoubleTapAction.MARK_MOMENT -> R.string.devices_tap_mark_moment
        DoubleTapAction.SLEEP_MARK -> R.string.devices_tap_sleep_mark
        DoubleTapAction.HAPTIC_CLOCK -> R.string.devices_tap_buzz_time
    },
)

/** The page [page] of the strap settings, with its own app bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StrapSettingsPage(page: StrapPage, vm: AppViewModel, onBack: () -> Unit) {
    val title = stringResource(
        when (page) {
            StrapPage.SYNC -> R.string.devices_sync
            StrapPage.POWER_SAVING -> R.string.devices_power_saving
            StrapPage.DOUBLE_TAP -> R.string.devices_double_tap
            StrapPage.HAPTICS -> R.string.devices_haptics
            StrapPage.HR_BROADCAST -> R.string.devices_hr_broadcast
        },
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title, onBack)
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding, end = M3Dimens.screenPadding,
                top = 8.dp, bottom = M3Dimens.bottomBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item {
                when (page) {
                    StrapPage.SYNC -> SyncSection(vm)
                    StrapPage.POWER_SAVING -> PowerSavingSection(vm)
                    StrapPage.DOUBLE_TAP -> DoubleTapSection(vm)
                    StrapPage.HAPTICS -> HapticsSection(vm)
                    StrapPage.HR_BROADCAST -> BroadcastSection(vm)
                }
            }
        }
    }
}

// MARK: - Sync

/**
 * The background link and its helpers. "Keep connected" holds the strap link with an ongoing notification once
 * the app is closed (default on). On phones whose vendor kills background apps, a one-tap battery exemption
 * (and the vendor auto-start page, a separate tap by choice) appears while that link is wanted and not yet
 * exempt. Then the two speed switches for history sync and the Bluetooth link.
 */
@Composable
private fun SyncSection(vm: AppViewModel) {
    val context = LocalContext.current
    var keepConnected by remember { mutableStateOf(NoopPrefs.backgroundConnection(context)) }
    var fastHistory by remember { mutableStateOf(NoopPrefs.fastHistorySync(context)) }
    var fastLink by remember { mutableStateOf(NoopPrefs.fastLinkPhy(context)) }
    val aggressiveVendor = remember { BackgroundHealth.isAggressiveVendor() }
    // Re-read on every resume, so the prompt goes the moment the grant lands (and returns if revoked).
    var exempt by remember { mutableStateOf(BackgroundHealth.isBatteryExempt(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { exempt = BackgroundHealth.isBatteryExempt(context) }
    }
    val oemAutostart = remember { BackgroundHealth.oemAutostartIntent(context) }
    Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
        ListGroup {
            item { shape ->
                SwitchRow(
                    shape = shape,
                    title = stringResource(R.string.devices_keep_connected),
                    subtitle = stringResource(R.string.devices_keep_connected_desc),
                    checked = keepConnected,
                    onCheckedChange = {
                        keepConnected = it
                        vm.setBackgroundConnection(it)
                    },
                )
            }
            if (keepConnected && aggressiveVendor && !exempt) {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_settings_screen_keep_noop_alive_overnight_e43b2fba),
                        subtitle = stringResource(R.string.keep_alive_needed_vendor, android.os.Build.MANUFACTURER),
                        trailing = {
                            Text(
                                stringResource(R.string.l10n_settings_screen_allow_3ad0e369),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        // One system dialog per tap; the app's battery page if a ROM lacks the dialog.
                        onClick = {
                            runCatching { context.startActivity(BackgroundHealth.batteryExemptionIntent(context)) }
                                .onFailure { runCatching { context.startActivity(BackgroundHealth.appBatterySettingsIntent(context)) } }
                        },
                    )
                }
                if (oemAutostart != null) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.l10n_settings_screen_some_phones_also_need_auto_start_79b7147b),
                            titleColor = MaterialTheme.colorScheme.primary,
                            onClick = { runCatching { context.startActivity(oemAutostart) } },
                        )
                    }
                }
            }
        }
        ListGroup {
            item { shape ->
                SwitchRow(shape = shape, title = stringResource(R.string.fast_history_sync), checked = fastHistory,
                    onCheckedChange = { fastHistory = it; vm.setFastHistorySync(it) })
            }
            item { shape ->
                SwitchRow(shape = shape, title = stringResource(R.string.fast_link_phy), checked = fastLink,
                    onCheckedChange = { fastLink = it; vm.setFastLinkPhy(it) })
            }
        }
    }
}

// MARK: - Power saving (#477)

/** The strap-battery levers. The master gates the sub-options: the threshold, "Pause HRV capture" and
 *  "Low refresh" only appear (and only apply) while Power saving is on. */
@Composable
private fun PowerSavingSection(vm: AppViewModel) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NoopPrefs.powerSaving(context)) }
    var pct by remember { mutableStateOf(NoopPrefs.powerSavingBatteryPct(context)) }
    var pauseHrv by remember { mutableStateOf(NoopPrefs.pauseHrvOnPowerSave(context)) }
    var lowRefresh by remember { mutableStateOf(NoopPrefs.lowRefresh(context)) }
    val percent = java.text.NumberFormat.getPercentInstance()
    ListGroup {
        item { shape ->
            SwitchRow(shape = shape, title = stringResource(R.string.devices_power_saving_mode), checked = enabled,
                onCheckedChange = { enabled = it; vm.setPowerSaving(it) })
        }
        if (enabled) {
            item { shape ->
                MenuValueRow(
                    shape = shape,
                    title = stringResource(R.string.devices_kick_in_at),
                    value = percent.format(pct / 100.0),
                    options = (10..35 step 5).map { it to percent.format(it / 100.0) },
                    onPick = { pct = it; vm.setPowerSavingBatteryPct(it) },
                )
            }
            item { shape ->
                SwitchRow(shape = shape, title = stringResource(R.string.devices_pause_hrv), checked = pauseHrv,
                    onCheckedChange = { pauseHrv = it; vm.setPauseHrvOnPowerSave(it) })
            }
            // Low refresh applies at ANY charge, not just below the threshold.
            item { shape ->
                SwitchRow(shape = shape, title = stringResource(R.string.devices_low_refresh), checked = lowRefresh,
                    onCheckedChange = { lowRefresh = it; vm.setLowRefresh(it) })
            }
        }
    }
}

// MARK: - Double-tap

/** What a strap double-tap does, and a Test button for it. Dispatched by the view model on a fresh
 *  DOUBLE_TAP event; this page only edits the choice. */
@Composable
private fun DoubleTapSection(vm: AppViewModel) {
    val action by vm.doubleTapAction.collectAsStateWithLifecycle()
    val labels = DoubleTapAction.entries.map { it to doubleTapLabel(it) }
    ListGroup {
        item { shape ->
            MenuValueRow(
                shape = shape,
                title = stringResource(R.string.devices_action),
                value = doubleTapLabel(action),
                options = labels,
                onPick = { vm.setDoubleTapAction(it) },
            )
        }
        if (action != DoubleTapAction.NONE) {
            item { shape ->
                ListRow(
                    shape = shape,
                    title = stringResource(R.string.devices_test),
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = { vm.testDoubleTapAction() },
                )
            }
        }
    }
}

// MARK: - Haptics (#1115)

/** Per-event toggles for reNOOP's in-session strap buzzes (default on, the keys [HapticPrefs] reads),
 *  plus the HR-zone coaching buzz. */
@Composable
private fun HapticsSection(vm: AppViewModel) {
    val context = LocalContext.current
    var breathing by remember { mutableStateOf(HapticPrefs.enabled(context, HapticPrefs.BREATHING)) }
    var intervals by remember { mutableStateOf(HapticPrefs.enabled(context, HapticPrefs.INTERVALS)) }
    var workout by remember { mutableStateOf(HapticPrefs.enabled(context, HapticPrefs.WORKOUT)) }
    val zoneCoaching by vm.zoneCoaching.collectAsStateWithLifecycle()
    val zoneRecovery by vm.zoneCoachRecovery.collectAsStateWithLifecycle()
    ListGroup {
        item { shape ->
            SwitchRow(shape = shape, title = stringResource(R.string.devices_breathing_pacer), checked = breathing,
                onCheckedChange = { breathing = it; HapticPrefs.setEnabled(context, HapticPrefs.BREATHING, it) })
        }
        item { shape ->
            SwitchRow(shape = shape, title = stringResource(R.string.devices_interval_timer), checked = intervals,
                onCheckedChange = { intervals = it; HapticPrefs.setEnabled(context, HapticPrefs.INTERVALS, it) })
        }
        item { shape ->
            SwitchRow(shape = shape, title = stringResource(R.string.devices_workout_start_end), checked = workout,
                onCheckedChange = { workout = it; HapticPrefs.setEnabled(context, HapticPrefs.WORKOUT, it) })
        }
        item { shape ->
            SwitchRow(shape = shape, title = stringResource(R.string.devices_hr_zones), checked = zoneCoaching,
                onCheckedChange = { vm.setZoneCoaching(it) })
        }
        if (zoneCoaching) {
            item { shape ->
                SwitchRow(shape = shape, title = stringResource(R.string.devices_recovery_buzz), checked = zoneRecovery,
                    onCheckedChange = { vm.setZoneCoachRecovery(it) })
            }
        }
    }
}

// MARK: - Heart-rate broadcast

/** Broadcast the live HR from this phone (a standard BLE sensor for a treadmill / Zwift / a watch), or
 *  ask the strap to advertise it itself. The phone toggle turns on only once the advertise permission is
 *  granted (Android 12+). */
@Composable
private fun BroadcastSection(vm: AppViewModel) {
    val context = LocalContext.current
    val puffin = remember { PuffinExperiment.from(context) }
    val live by vm.live.collectAsStateWithLifecycle()
    val phone by vm.hrBroadcast.collectAsStateWithLifecycle()
    val advertising by vm.hrBroadcastAdvertising.collectAsStateWithLifecycle()
    val subscribers by vm.hrBroadcastSubscribers.collectAsStateWithLifecycle()
    val note by vm.hrBroadcastStatus.collectAsStateWithLifecycle()
    var strap by remember { mutableStateOf(puffin.broadcastHr) }
    val requestAdvertise = rememberRequestAdvertise(onGranted = { vm.setHrBroadcast(true) })

    val phoneFooter = when {
        !phone -> null
        note != null -> note
        subscribers == 1 -> stringResource(R.string.devices_one_reader)
        subscribers > 1 -> stringResource(R.string.devices_n_readers, subscribers)
        live.heartRate != null -> stringResource(R.string.devices_sharing_bpm, live.heartRate ?: 0)
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
        ListGroup(footer = phoneFooter) {
            item { shape ->
                SwitchRow(
                    shape = shape,
                    title = stringResource(R.string.devices_from_phone),
                    checked = phone,
                    onCheckedChange = { on -> if (on) requestAdvertise() else vm.setHrBroadcast(false) },
                )
            }
            if (phone) {
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.devices_status), trailing = {
                        Text(
                            stringResource(if (advertising) R.string.devices_broadcasting else R.string.devices_starting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    })
                }
            }
        }
        ListGroup {
            item { shape ->
                SwitchRow(
                    shape = shape,
                    title = stringResource(R.string.devices_from_strap),
                    checked = strap,
                    onCheckedChange = { enabled ->
                        strap = enabled
                        puffin.broadcastHr = enabled
                        vm.ble.setBroadcastHr(enabled)
                    },
                )
            }
        }
    }
}

// MARK: - Pieces

/** A row that states its value and opens a menu to change it. */
@Composable
internal fun <T> MenuValueRow(
    shape: Shape,
    title: String,
    value: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(
            shape = shape,
            title = title,
            enabled = enabled,
            trailing = {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (option, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}
