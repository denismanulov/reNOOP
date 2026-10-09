package com.noop.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.PuffinExperiment
import com.noop.ingest.RawSensorExport
import com.noop.testcentre.TestCentre
import com.noop.testcentre.TestDomain
import com.noop.ui.AppViewModel
import com.noop.ui.ClockPrefs
import com.noop.ui.Destination
import com.noop.ui.LogExport
import com.noop.ui.NoopPrefs
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.SwitchRow
import kotlinx.coroutines.launch

// MARK: - Developer (twin of iOS DeveloperSettingsPage)
//
// Hidden until the version in About reNOOP is tapped seven times. The Test Centre (test modes, bug
// report, strap log, protocol tools, its own experiments) and the developer rows the old Settings carried:
// the strap log and raw-data exports, the haptic clock, the strap's advertising name, continuous HRV
// capture, and the default-off research switches. Same keys and the same BLE wiring as before.
//
// The strap's own settings (Sync, Power saving, Double-tap, Haptics, Heart rate broadcast) live on the Devices
// screen, and what each WHOOP model can be read for is the device page's "What reNOOP Reads": one home each.

@Composable
internal fun SettingsDeveloperScreen(vm: AppViewModel, open: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by vm.live.collectAsStateWithLifecycle()
    // #2338: the read-only advertising-name probe result, on its own flow like the other opcode probes.
    val advertisingNameProbe by vm.advertisingNameProbe.collectAsStateWithLifecycle()
    val puffin = remember { PuffinExperiment.from(context) }
    // #820: a strap FAMILY switch rewrites `noop_experiments` from the BLE side; re-read the switches below
    // when that happens (SharedPreferences listeners are held weakly, so keep a strong reference).
    var rev by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences(PuffinExperiment.PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in PuffinExperiment.FIVE_MG_GATED_KEYS) rev++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var strapLogBusy by remember { mutableStateOf(false) }
    var rawBusy by remember { mutableStateOf(false) }
    var continuousHrv by remember { mutableStateOf(NoopPrefs.continuousHrv(context)) }
    var overnightOnly by remember { mutableStateOf(NoopPrefs.continuousHrvOvernight(context)) }
    var sleepV2 by remember(rev) { mutableStateOf(puffin.experimentalSleepV2) }
    var motionWake by remember(rev) { mutableStateOf(puffin.motionAwareWake) }
    var spo2Estimate by remember { mutableStateOf(NoopPrefs.spo2CandidateDisplay(context)) }
    var stressBaseline by remember { mutableStateOf(NoopPrefs.stressPersonalBaseline(context)) }

    SettingsPage(title = stringResource(R.string.settings_developer), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.l10n_settings_screen_test_centre_37b36828), onClick = {
                        open(Destination.TestCentre.route)
                    })
                }
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.nav_self_hosted_push), onClick = {
                        open(Destination.SelfHostedPush.route)
                    })
                }
            }
        }
        // The strap's Bluetooth name, while a strap is connected.
        if (live.connected) {
            item { StrapNameSection(vm, advertisingNameProbe) }
        }
        item {
            ListGroup(header = stringResource(R.string.l10n_settings_screen_diagnostics_3af2279f)) {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_settings_screen_share_strap_log_for_bug_reports_b9802500),
                        enabled = !strapLogBusy,
                        trailing = if (strapLogBusy) { { BusyTrailing() } } else null,
                        onClick = {
                            strapLogBusy = true
                            scope.launch {
                                // The flag must clear on any exit, not just the happy path (#961).
                                try { LogExport.shareStrapLog(context, vm.ble.exportLogText()) } finally { strapLogBusy = false }
                            }
                        },
                    )
                }
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.settings_export_raw_csv),
                        enabled = !rawBusy,
                        trailing = if (rawBusy) { { BusyTrailing() } } else null,
                        onClick = {
                            rawBusy = true
                            scope.launch {
                                try { RawSensorExport.export(context, vm.repo, vm.activeStrapId) } finally { rawBusy = false }
                            }
                        },
                    )
                }
                item { shape ->
                    // #460/#1821: the time as buzzes, on the clock the phone uses. No-ops when disconnected.
                    ListRow(shape = shape, title = stringResource(R.string.l10n_settings_screen_buzz_the_time_on_your_strap_06fc879d), onClick = {
                        vm.ble.buzzTimeNow(is24h = ClockPrefs.uses24Hour(context))
                    })
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_hrv)) {
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.l10n_settings_screen_continuous_hrv_capture_1f0805d8), continuousHrv, {
                        continuousHrv = it
                        vm.setContinuousHrv(it)
                    })
                }
                if (continuousHrv) {
                    item { shape ->
                        SwitchRow(shape, stringResource(R.string.l10n_settings_screen_overnight_only_05747985), overnightOnly, {
                            overnightOnly = it
                            vm.setContinuousHrvOvernight(it)
                        })
                    }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_experiments)) {
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.l10n_settings_screen_sleep_staging_v2_a4176770), sleepV2, {
                        sleepV2 = it
                        puffin.experimentalSleepV2 = it
                    })
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.l10n_settings_screen_motion_aware_wake_refinement_67a91e47), motionWake, {
                        motionWake = it
                        puffin.motionAwareWake = it
                    })
                }
                item { shape ->
                    // Never fed into recovery or illness scoring; shown as an "estimate" (#103).
                    SwitchRow(shape, stringResource(R.string.settings_spo2_strap_estimate), spo2Estimate, {
                        spo2Estimate = it
                        vm.setSpo2CandidateDisplay(it)
                    })
                }
                item { shape ->
                    // #463: display-only, never fed into recovery or illness scoring.
                    SwitchRow(shape, stringResource(R.string.settings_stress_personal_baseline), stressBaseline, {
                        stressBaseline = it
                        NoopPrefs.setStressPersonalBaseline(context, it)
                    })
                }
            }
        }
    }

}

/**
 * The strap's Bluetooth advertising name (a second-hand band arrives with the previous owner's).
 *
 * WHOOP 4.0: SET_ADVERTISING_NAME_HARVARD(77); the strap reboots to apply, so Rename asks first (ST-8).
 *
 * WHOOP 5/MG (#2338): the SECTION renders for any connected 5/MG, so it can say why it can do nothing;
 * only the CONTROLS sit behind the Test Centre Connection mode, because opcode 140 has never been
 * confirmed on this family. The read-only check (GET_ADVERTISING_NAME, 141) is how you see whether a
 * write landed.
 */
@Composable
private fun StrapNameSection(vm: AppViewModel, advertisingNameProbe: String?) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    var confirmRename by remember { mutableStateOf(false) }
    val fiveMgRenameUnlocked = TestCentre.from(context).active(TestDomain.CONNECTION)
    var nameDraft by remember(live.advertisingName) { mutableStateOf(live.advertisingName ?: "") }

    HealthCard {
        Text(stringResource(R.string.l10n_settings_screen_strap_name_350de547), style = MaterialTheme.typography.titleMedium)
        if (live.connected && live.whoop5Detected) {
            Text(
                stringResource(
                    if (fiveMgRenameUnlocked) R.string.l10n_settings_screen_experimental_on_a_whoop_5_0_711d5341
                    else R.string.l10n_settings_screen_renaming_is_not_supported_on_a_02f7af2c,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fiveMgRenameUnlocked) {
                NameField(nameDraft) { nameDraft = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = live.bonded && nameDraft.isNotBlank(),
                        onClick = { vm.ble.renameStrap(nameDraft) },
                    ) { Text(stringResource(R.string.l10n_settings_screen_rename_d3f4cb89)) }
                    // Read-only: nothing is written. This is how you check whether the write did anything.
                    OutlinedButton(enabled = live.bonded, onClick = { vm.ble.probeAdvertisingName() }) {
                        Text(stringResource(R.string.settings_check_name))
                    }
                }
                (live.renameStatus ?: advertisingNameProbe)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (live.connected && !live.whoop5Detected) {
            Text(
                live.advertisingName ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NameField(nameDraft) { nameDraft = it }
            FilledTonalButton(
                enabled = live.bonded && nameDraft.isNotBlank(),
                onClick = { confirmRename = true },
            ) { Text(stringResource(R.string.l10n_settings_screen_rename_d3f4cb89)) }
            live.renameStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmRename) {
        ConfirmDialog(
            title = stringResource(R.string.settings_rename_restart_title),
            message = null,
            confirmLabel = stringResource(R.string.l10n_settings_screen_rename_d3f4cb89),
            onConfirm = {
                confirmRename = false
                vm.ble.renameStrap(nameDraft)
            },
            onDismiss = { confirmRename = false },
        )
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.take(24)) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.settings_new_strap_name)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
