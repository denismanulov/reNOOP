package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.LiveState
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.protocol.DeviceFamily
import com.noop.protocol.RebootProbeVariant
import com.noop.testcentre.TestCentre
import com.noop.testcentre.TestDomain
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.ProgressRing
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.ChevronRight
import com.noop.ui.summary.SummarySyncLine
import com.noop.ui.summary.syncLineText
import kotlinx.coroutines.launch

// MARK: - One device's page
//
// As Android's Bluetooth device details (and iOS Settings' device ⓘ) lay a device out: the glyph and
// charge on top, then the name, the controls (Connect / Re-scan, Buzz, Stop Sync, Restart, Reconnect Ring,
// Make Active), "About This Device" (Sync, Model, Firmware, What reNOOP Reads), the Test Centre probes when
// Test Centre → Connection is on, and Disconnect / Forget at the bottom. Twin of Swift `DeviceDetailView`.
// Every control calls exactly what the old Devices card menu called; the Restart, Forget, Delete and Remove
// actions stay behind their confirmations.

private enum class DeviceConfirm { RESTART, FORGET, DELETE_DATA, PURGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceDetailScreen(
    viewModel: AppViewModel,
    deviceId: String,
    devices: List<PairedDeviceRow>,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenReads: () -> Unit,
    /** The device was forgotten or removed; `true` when it was the active one. */
    onRemoved: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val ringPct by viewModel.ouraBatteryPct.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == deviceId }
    if (device == null) {
        // Forgotten from under the page.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val r = DeviceReadout.make(device, live, ringPct)
    val testing = remember { TestCentre.from(context).active(TestDomain.CONNECTION) }
    // The explicit user connect, through the same permission gate Live and Settings use.
    val requestConnect = rememberRequestScan { viewModel.connect() }

    var confirm by remember { mutableStateOf<DeviceConfirm?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<String?>(null) }

    fun perform(c: DeviceConfirm) {
        when (c) {
            DeviceConfirm.RESTART -> viewModel.rebootStrap()
            // Archiving a WHOOP also releases its BLE link (#520/#78), so the strap can enter pairing mode.
            DeviceConfirm.FORGET -> scope.launch {
                val wasActive = r.isActive
                viewModel.archivePairedDevice(device.id)
                onRemoved(wasActive)
            }
            DeviceConfirm.DELETE_DATA -> scope.launch { viewModel.deletePairedDeviceData(device.id); onChanged() }
            // #1193: the only way to get a duplicate/stale strap out of the list for good.
            DeviceConfirm.PURGE -> scope.launch {
                viewModel.forgetPairedDevice(device.id)
                onRemoved(false)
            }
        }
    }

    fun makeActive() {
        scope.launch { viewModel.setActiveDevice(device.id); onChanged() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(displayName(device), onBack)
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding, end = M3Dimens.screenPadding,
                top = 8.dp, bottom = M3Dimens.bottomBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "hero") { DeviceHero(device, r) }

            // #221: linked, but the strap refused the bond — the self-service fix right here.
            val hint = live.pairingHint
            if (r.bondRefused && hint != null) {
                item(key = "bond") {
                    DeviceWarning(
                        title = stringResource(R.string.devices_not_paired_title),
                        message = stringResource(R.string.devices_not_paired_message),
                        detail = hint,
                    )
                }
            }

            item(key = "name") {
                ListGroup {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.devices_name),
                            trailing = { ValueLabel(displayName(device)) },
                            onClick = { renaming = true },
                        )
                    }
                }
            }

            if (r.isActive && (r.isWhoop || r.isOura)) {
                item(key = "controls") {
                    val bondedLink = r.isWhoop && live.connected && live.bonded
                    ListGroup {
                        if (r.isWhoop) {
                            item { shape ->
                                ActionRow(shape, stringResource(if (live.connected) R.string.devices_rescan else R.string.devices_connect),
                                    onClick = requestConnect)
                            }
                            // #921: the confirmed one-shot buzz sequence.
                            item { shape ->
                                ActionRow(shape, stringResource(R.string.devices_buzz), enabled = bondedLink,
                                    onClick = { viewModel.buzzStrapOnce() })
                            }
                            // Stop an offload in flight. Nothing is lost: unacked records stay on the strap.
                            if (canStopSync(live, r)) {
                                item { shape ->
                                    ActionRow(shape, stringResource(R.string.devices_stop_sync), onClick = { viewModel.abortBackfill() })
                                }
                            }
                            // #275: no safe frame reboots a 4.0; a 5.0/MG reboots on the production frame.
                            if (r.isLive && live.whoop5Detected) {
                                item { shape ->
                                    ActionRow(shape, stringResource(R.string.devices_restart), onClick = { confirm = DeviceConfirm.RESTART })
                                }
                            }
                        }
                        if (r.isOura) {
                            // #2305: drops the ring link, if any, and connects again.
                            item { shape ->
                                ActionRow(shape, stringResource(R.string.devices_reconnect_ring), onClick = { viewModel.reconnectOuraRing() })
                            }
                        }
                    }
                }
            }

            if (device.status == DeviceStatus.paired.name && !r.isImportSource) {
                // Reversible, so no confirmation, as a tap in Bluetooth switches the device.
                item(key = "activate") {
                    ListGroup { item { shape -> ActionRow(shape, stringResource(R.string.devices_make_active), onClick = ::makeActive) } }
                }
            }

            item(key = "about") { AboutDevice(viewModel, device, r, live, devices.size, testing, selectedModel, onOpenReads) }

            if (r.isActive && r.isWhoop && r.isLive && testing) {
                item(key = "probes") {
                    ListGroup(header = stringResource(R.string.devices_test_centre)) {
                        if (!live.whoop5Detected) {
                            item { shape -> ActionRow(shape, stringResource(R.string.l10n_devices_screen_reboot_probe_4_0_re_828b3916), onClick = { probe = "reboot" }) }
                        }
                        item { shape -> ActionRow(shape, stringResource(R.string.l10n_devices_screen_battery_info_probe_592_re_1dbd4c0f), onClick = { probe = "battery" }) }
                        item { shape -> ActionRow(shape, stringResource(R.string.l10n_devices_screen_body_location_probe_690_re_7def8c39), onClick = { probe = "body" }) }
                        item { shape -> ActionRow(shape, stringResource(R.string.l10n_devices_screen_battery_pack_probe_151_re_d722af00), onClick = { probe = "pack" }) }
                        item { shape -> ActionRow(shape, stringResource(R.string.l10n_devices_screen_feature_flag_probe_761_re_21241d68), onClick = { probe = "flags" }) }
                        item { shape -> ActionRow(shape, stringResource(R.string.l10n_devices_screen_device_config_read_probe_103_re_837f46de), onClick = { probe = "config" }) }
                    }
                }
            }

            item(key = "remove") {
                ListGroup {
                    if (r.isArchived) {
                        if (!r.isImportSource) {
                            item { shape -> ActionRow(shape, stringResource(R.string.devices_make_active), onClick = ::makeActive) }
                        }
                        item { shape -> ActionRow(shape, stringResource(R.string.devices_delete_data), destructive = true, onClick = { confirm = DeviceConfirm.DELETE_DATA }) }
                        item { shape -> ActionRow(shape, stringResource(R.string.devices_purge), destructive = true, onClick = { confirm = DeviceConfirm.PURGE }) }
                    } else {
                        if (r.isActive && r.isWhoop && (live.connected || live.bonded)) {
                            item { shape -> ActionRow(shape, stringResource(R.string.devices_disconnect), onClick = { viewModel.disconnect() }) }
                        }
                        item { shape -> ActionRow(shape, stringResource(R.string.devices_forget), destructive = true, onClick = { confirm = DeviceConfirm.FORGET }) }
                    }
                }
            }
        }
    }

    confirm?.let { c ->
        val (title, message, action) = when (c) {
            DeviceConfirm.RESTART -> Triple(R.string.devices_restart_confirm, R.string.devices_restart_message, R.string.devices_restart)
            DeviceConfirm.FORGET -> Triple(R.string.devices_forget_confirm, R.string.devices_forget_message, R.string.devices_forget)
            DeviceConfirm.DELETE_DATA -> Triple(R.string.devices_delete_confirm, R.string.devices_delete_message, R.string.devices_delete_data)
            DeviceConfirm.PURGE -> Triple(R.string.devices_purge_confirm, R.string.devices_purge_message, R.string.devices_purge)
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = { confirm = null; perform(c) }) {
                    Text(
                        stringResource(action),
                        color = if (c == DeviceConfirm.RESTART) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }

    if (renaming) {
        RenameDialog(device, onSave = { name ->
            renaming = false
            // An empty name puts the old one back rather than saving nothing.
            if (name.isNotBlank() && name != displayName(device)) {
                scope.launch { viewModel.renamePairedDevice(device.id, name.trim()); onChanged() }
            }
        }, onDismiss = { renaming = false })
    }

    ProbeDialogs(viewModel, probe, onDismiss = { probe = null })
}

// MARK: - Hero

/** The glyph, and under it the charge as a ring and a figure; the link word when there is no charge. */
@Composable
private fun DeviceHero(device: PairedDeviceRow, r: DeviceReadout) {
    val pct = r.shownBattery
    val status = deviceStatusLine(r)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clearAndSetSemantics { contentDescription = status },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(deviceGlyph(device), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(96.dp))
        if (pct != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressRing(
                    fraction = pct / 100f,
                    color = if (pct < 15) MaterialTheme.colorScheme.error else Health.colors.positive,
                    modifier = Modifier.size(32.dp),
                    stroke = 4.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Text(java.text.NumberFormat.getPercentInstance().format(pct / 100.0), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text(deviceLinkLabel(r.link), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// MARK: - About this device

@Composable
private fun AboutDevice(
    viewModel: AppViewModel,
    device: PairedDeviceRow,
    r: DeviceReadout,
    live: LiveState,
    pairedCount: Int,
    testing: Boolean,
    selectedModel: WhoopModel,
    onOpenReads: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    // A STABLE property of the strap: the live handshake value, else the last one persisted for THIS
    // device (#1633), else the legacy global key when only one device is paired. WHOOP only.
    val firmware = com.noop.ble.resolveFirmware(
        live = if (r.isActive) live.strapFirmware else null,
        perDevice = if (r.isWhoop) NoopPrefs.firmwareFor(context, device.peripheralId) else null,
        legacyGlobal = if (r.isActive && r.isWhoop) NoopPrefs.lastFirmware(context) else null,
        pairedCount = pairedCount,
    )
    val syncText = if (r.isActive && r.isWhoop) {
        syncLineText(SummarySyncLine.resolve(live.backfilling, live.lastSyncAt, System.currentTimeMillis() / 1000), locale)
    } else null
    ListGroup(header = stringResource(R.string.devices_about_device)) {
        // #592: pack voltage, for protocol work only.
        val mv = live.batteryMv
        if (testing && r.isLive && mv != null) {
            item { shape -> InfoRow(shape, stringResource(R.string.devices_voltage), "%.2f V".format(mv / 1000.0)) }
        }
        if (syncText != null) {
            item { shape -> InfoRow(shape, stringResource(R.string.devices_sync), syncText) }
        }
        if (r.isActive && r.isWhoop) {
            item { shape ->
                // Switching family while a strap streams would drop it; the picker waits until it is not.
                MenuValueRow(
                    shape = shape,
                    title = stringResource(R.string.devices_model),
                    value = selectedModel.displayName,
                    options = WhoopModel.entries.map { it to it.displayName },
                    onPick = { viewModel.setSelectedModel(it) },
                    enabled = !(r.isLive && live.bonded),
                )
            }
        } else if (!r.isWhoop) {
            item { shape -> InfoRow(shape, stringResource(R.string.devices_model), device.model) }
        }
        if (firmware != null) item { shape -> InfoRow(shape, stringResource(R.string.devices_firmware), firmware) }
        val layout = if (r.isLive) live.historyLayoutVersion else null
        if (testing && layout != null) {
            item { shape -> InfoRow(shape, stringResource(R.string.devices_history_layout), "v$layout") }
        }
        if (r.isWhoop) {
            item { shape ->
                ListRow(
                    shape = shape,
                    title = stringResource(R.string.devices_what_reads),
                    trailing = { ChevronRight() },
                    onClick = onOpenReads,
                )
            }
        }
    }
}

// MARK: - What reNOOP reads

private enum class ReadsSupport { FULL, PARTIAL, NONE }

private data class ReadsRow(val label: Int, val whoop4: ReadsSupport, val whoop5: ReadsSupport)

/** The same table as Swift `DeviceReadsView`, row for row. */
private val READS: List<ReadsRow> = listOf(
    ReadsRow(R.string.devices_reads_live_hr, ReadsSupport.FULL, ReadsSupport.FULL),
    ReadsRow(R.string.devices_reads_hrv, ReadsSupport.FULL, ReadsSupport.FULL),
    ReadsRow(R.string.devices_reads_sleep, ReadsSupport.FULL, ReadsSupport.FULL),
    ReadsRow(R.string.devices_reads_recovery, ReadsSupport.FULL, ReadsSupport.FULL),
    ReadsRow(R.string.devices_reads_resp, ReadsSupport.PARTIAL, ReadsSupport.PARTIAL),
    ReadsRow(R.string.devices_reads_stress, ReadsSupport.FULL, ReadsSupport.FULL),
    ReadsRow(R.string.devices_reads_workouts, ReadsSupport.FULL, ReadsSupport.FULL),
    ReadsRow(R.string.devices_reads_skin_temp, ReadsSupport.PARTIAL, ReadsSupport.FULL),
    // Full on a 4.0 as well: its 104-byte v24 record carries the firmware's own cumulative step counter
    // (`step_counter@92`), counted the same way as the 5/MG counter. The motion-volume estimate only fills
    // days that have no counter rows.
    ReadsRow(R.string.devices_reads_steps, ReadsSupport.FULL, ReadsSupport.FULL),
    ReadsRow(R.string.devices_reads_spo2, ReadsSupport.NONE, ReadsSupport.NONE),
    ReadsRow(R.string.devices_reads_ecg, ReadsSupport.NONE, ReadsSupport.PARTIAL),
    ReadsRow(R.string.devices_reads_bp, ReadsSupport.NONE, ReadsSupport.NONE),
)

/** What reNOOP reads off a WHOOP: one column for the device's family, both when it is unknown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceReadsPage(device: PairedDeviceRow?, onBack: () -> Unit) {
    val family = device?.let { DeviceFamily.confirmedRegistryFamily(model = it.model, brand = it.brand) }
    val spoken = mapOf(
        ReadsSupport.FULL to stringResource(R.string.devices_yes),
        ReadsSupport.PARTIAL to stringResource(R.string.devices_partly),
        ReadsSupport.NONE to stringResource(R.string.devices_no),
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(stringResource(R.string.devices_what_reads), onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = M3Dimens.bottomBarClearance),
        ) {
            if (family == null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).clearAndSetSemantics { }) {
                        Box(Modifier.weight(1f))
                        Text("4.0", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                        Text("5.0/MG", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                    }
                }
            }
            item {
                ListGroup {
                    READS.forEach { row ->
                        item { shape ->
                            val label = stringResource(row.label)
                            val cells = when (family) {
                                DeviceFamily.WHOOP4 -> listOf(row.whoop4)
                                DeviceFamily.WHOOP5 -> listOf(row.whoop5)
                                else -> listOf(row.whoop4, row.whoop5)
                            }
                            val a11y = label + ": " + cells.joinToString(", ") { spoken.getValue(it) }
                            ListRow(
                                shape = shape,
                                title = label,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = a11y },
                                trailing = { Row { cells.forEach { SupportCell(it) } } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportCell(s: ReadsSupport) {
    val (icon, tint) = when (s) {
        ReadsSupport.FULL -> Icons.Filled.Check to Health.colors.positive
        ReadsSupport.PARTIAL -> Icons.Filled.Remove to Health.colors.warning
        ReadsSupport.NONE -> Icons.Filled.Close to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// MARK: - Pieces

@Composable
private fun ValueLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 200.dp),
    )
}

@Composable
private fun InfoRow(shape: Shape, title: String, value: String) {
    ListRow(shape = shape, title = title, trailing = {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    })
}

@Composable
private fun ActionRow(
    shape: Shape,
    title: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListRow(
        shape = shape,
        title = title,
        titleColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun RenameDialog(device: PairedDeviceRow, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(device.nickname ?: displayName(device)) }
    val label = stringResource(R.string.devices_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.devices_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_cancel)) } },
    )
}

// MARK: - Test Centre probes (#235, #592, #690, cmd 151, #761, #103)
//
// Only reachable with Test Centre → Connection on and a live WHOOP. Each confirm sends ONE read-only
// probe (the reboot probe's candidates are each confirmed separately); each result is readable and
// copyable in place. Same AppViewModel calls as before.

@Composable
private fun ProbeDialogs(viewModel: AppViewModel, probe: String?, onDismiss: () -> Unit) {
    val battery by viewModel.extendedBatteryProbe.collectAsStateWithLifecycle()
    val body by viewModel.bodyLocationProbe.collectAsStateWithLifecycle()
    val pack by viewModel.batteryPackProbe.collectAsStateWithLifecycle()
    val flags by viewModel.featureFlagProbe.collectAsStateWithLifecycle()
    val config by viewModel.deviceConfigProbe.collectAsStateWithLifecycle()

    when (probe) {
        "reboot" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.l10n_devices_screen_whoop_4_0_reboot_probe_b51fb50f)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.l10n_devices_screen_the_whoop_4_0_reboot_frame_690a8ff2))
                    RebootProbeVariant.entries.forEach { variant ->
                        TextButton(onClick = { viewModel.rebootProbe(variant); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text(variant.menuLabel, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_cancel)) } },
        )
        "battery" -> ProbeConfirm(R.string.l10n_devices_screen_battery_info_probe_592_re_1dbd4c0f,
            R.string.l10n_devices_screen_battery_probe_explainer_2858bb6a, { viewModel.probeExtendedBatteryInfo(); onDismiss() }, onDismiss)
        "body" -> ProbeConfirm(R.string.l10n_devices_screen_body_location_probe_690_re_7def8c39,
            R.string.l10n_devices_screen_body_location_probe_explainer_a9363239, { viewModel.probeBodyLocationAndStatus(); onDismiss() }, onDismiss)
        "pack" -> ProbeConfirm(R.string.l10n_devices_screen_battery_pack_probe_151_re_d722af00,
            R.string.l10n_devices_screen_battery_pack_probe_explainer_40b3470d, { viewModel.probeBatteryPackInfo(); onDismiss() }, onDismiss)
        "flags" -> ProbeConfirm(R.string.l10n_devices_screen_feature_flag_probe_761_re_21241d68,
            R.string.l10n_devices_screen_feature_flag_probe_explainer_58eec30f, { viewModel.probeFeatureFlags(); onDismiss() }, onDismiss)
        "config" -> ProbeConfirm(R.string.l10n_devices_screen_device_config_read_probe_103_re_837f46de,
            R.string.l10n_devices_screen_device_config_probe_explainer_dd23169f, { viewModel.probeDeviceConfigValues(); onDismiss() }, onDismiss)
    }

    battery?.let { ProbeResult(R.string.l10n_devices_screen_battery_info_probe_result_592_b97c0bb8, it, WhoopBleClient.WAITING_EXTENDED_BATTERY_PROBE) { viewModel.clearExtendedBatteryProbe() } }
    body?.let { ProbeResult(R.string.l10n_devices_screen_body_location_probe_result_690_60c5ee79, it, WhoopBleClient.WAITING_BODY_LOCATION_PROBE) { viewModel.clearBodyLocationProbe() } }
    pack?.let { ProbeResult(R.string.l10n_devices_screen_battery_pack_probe_result_151_df43dff2, it, WhoopBleClient.WAITING_BATTERY_PACK_PROBE) { viewModel.clearBatteryPackProbe() } }
    flags?.let { ProbeResult(R.string.l10n_devices_screen_feature_flag_probe_result_761_c50ef4d4, it, WhoopBleClient.WAITING_FEATURE_FLAG_PROBE) { viewModel.clearFeatureFlagProbe() } }
    config?.let { ProbeResult(R.string.l10n_devices_screen_device_config_read_probe_result_103_67d02ec9, it, WhoopBleClient.WAITING_DEVICE_CONFIG_PROBE) { viewModel.clearDeviceConfigProbe() } }
}

@Composable
private fun ProbeConfirm(title: Int, explainer: Int, onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(explainer)) },
        confirmButton = { TextButton(onClick = onSend) { Text(stringResource(R.string.l10n_devices_screen_send_probe_read_only_36b318bc)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_cancel)) } },
    )
}

/** A probe's reply (or "waiting…" while in flight), readable and copyable; dismiss clears it. */
@Composable
private fun ProbeResult(title: Int, text: String, waitingSentinel: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val waiting = text == waitingSentinel
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        if (waiting) stringResource(R.string.l10n_devices_screen_waiting_for_the_straps_reply_5a06e7ac) else text,
                        style = if (waiting) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            if (!waiting) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Text(stringResource(R.string.l10n_devices_screen_copy_af74f7c5))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.l10n_devices_screen_close_bbfa773e)) } },
    )
}
