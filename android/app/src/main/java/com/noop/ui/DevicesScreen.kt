package com.noop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.LiveState
import com.noop.ble.WhoopBleClient
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.ProgressRing
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.summary.SummarySyncLine
import com.noop.ui.summary.syncLineText
import kotlinx.coroutines.launch

// MARK: - Devices
//
// The bands reNOOP reads from, as Android's Connected devices and Fitbit's device page list them (twin of
// Swift `DevicesView`, mockup 11): the active device on a card (glyph in its battery ring, name, link and
// charge, Buzz / Sync Now), "My Devices" (✓ on the active one, a tap makes a device active, ⓘ opens its
// page), "Add Device", the strap's own settings, and "Removed" dimmed. A thin UI over
// [com.noop.data.DeviceRegistry]: every mutation is an [AppViewModel] registry op, and the
// [com.noop.ble.SourceCoordinator] reacts to the active-device change. Every control calls exactly what
// the old Devices and Live screens called; no new BLE command or path.
//
// The device page, the strap pages and "What reNOOP Reads" open in place (one back stack inside this
// destination), so Back walks them before leaving Devices.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit = {},
    /** Routes to the non-destructive file-import lane (Data Sources): the wizard's "Import a File". */
    onUseFileImport: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val live by viewModel.live.collectAsStateWithLifecycle()
    // #2075: a ring reports its OWN charge and does not funnel into live.batteryPct.
    val ringPct by viewModel.ouraBatteryPct.collectAsStateWithLifecycle()

    // The registry's reads are one-shot suspend (not a Flow): the list is re-read after every op.
    var devices by remember { mutableStateOf<List<PairedDeviceRow>?>(null) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(revision) { devices = viewModel.pairedDevices() }
    fun reload() { revision++ }

    // In-place pages: "device:<id>", "reads:<id>", "strap:<page>"; null is the list.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddWizard by rememberSaveable { mutableStateOf(false) }
    var pickNewActive by remember { mutableStateOf(false) }

    val all = devices.orEmpty()
    val current = all.filter { it.status != DeviceStatus.archived.name }
        .sortedBy { if (it.status == DeviceStatus.active.name) 0 else 1 }
    val removed = all.filter { it.status == DeviceStatus.archived.name }
    val activatable = current.filter { !DeviceReadout.isImportSource(it) }

    fun makeActive(d: PairedDeviceRow) {
        scope.launch { viewModel.setActiveDevice(d.id); reload() }
    }

    // Kept above the in-place pages, so the list comes back where it was left.
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    BackHandler(enabled = page != null) {
        page = page?.let { p -> if (p.startsWith("reads:")) "device:" + p.removePrefix("reads:") else null }
    }

    if (showAddWizard) {
        AddDeviceWizard(
            viewModel = viewModel,
            onClose = { showAddWizard = false; reload() },
            // "Import a File" closes the flow and opens the import page, so the non-destructive lane is
            // always one tap away.
            onUseFileImport = { showAddWizard = false; reload(); onUseFileImport() },
        )
        return
    }

    when {
        page?.startsWith("strap:") == true -> {
            val strapPage = StrapPage.fromKey(page!!.removePrefix("strap:"))
            if (strapPage != null) {
                StrapSettingsPage(strapPage, viewModel, onBack = { page = null })
                return
            }
        }
        page?.startsWith("reads:") == true -> {
            val d = all.firstOrNull { it.id == page!!.removePrefix("reads:") }
            DeviceReadsPage(d, onBack = { page = d?.let { "device:${it.id}" } })
            return
        }
        page?.startsWith("device:") == true -> {
            val id = page!!.removePrefix("device:")
            DeviceDetailScreen(
                viewModel = viewModel,
                deviceId = id,
                devices = all,
                onBack = { page = null },
                onChanged = ::reload,
                onOpenReads = { page = "reads:$id" },
                onRemoved = { wasActive ->
                    page = null
                    scope.launch {
                        val fresh = viewModel.pairedDevices()
                        devices = fresh
                        // After removing the ACTIVE device with others still paired, ask for a new one.
                        if (wasActive && fresh.any { it.status != DeviceStatus.archived.name && !DeviceReadout.isImportSource(it) }) {
                            pickNewActive = true
                        }
                    }
                },
            )
            return
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(R.string.devices_title),
            onBack = onBack,
            large = true,
            scrollBehavior = scrollBehavior,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding, end = M3Dimens.screenPadding,
                top = 8.dp, bottom = M3Dimens.bottomBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            // #802: a strap whose bond the strap wiped can't connect until it's re-paired; say so where
            // devices are fixed, with the full steps behind "How to Fix".
            live.reconnectGuide?.let { guide ->
                item(key = "guide") {
                    DeviceWarning(
                        title = stringResource(R.string.devices_pairing_reset_title),
                        message = stringResource(R.string.devices_pairing_reset_message),
                        detail = guide,
                    )
                }
            }

            if (devices == null) {
                // The registry resolves a beat after launch.
                item(key = "pending") {
                    Text(
                        stringResource(R.string.devices_getting_ready),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                return@LazyColumn
            }

            current.firstOrNull { it.status == DeviceStatus.active.name }?.let { active ->
                item(key = "active") {
                    ActiveDeviceCard(
                        device = active,
                        readout = DeviceReadout.make(active, live, ringPct),
                        live = live,
                        onOpen = { page = "device:${active.id}" },
                        onBuzz = { viewModel.buzzStrapOnce() },
                        onSync = { viewModel.syncNow() },
                        onReconnectRing = { viewModel.reconnectOuraRing() },
                    )
                }
            }

            item(key = "mine") {
                ListGroup(header = if (current.isEmpty()) null else stringResource(R.string.devices_my_devices)) {
                    current.forEach { d ->
                        item { shape ->
                            val r = DeviceReadout.make(d, live, ringPct)
                            DeviceRow(
                                shape = shape,
                                device = d,
                                readout = r,
                                onSelect = {
                                    // A tap makes a device active, as Bluetooth and the Watch app switch on a
                                    // tap; the active one and an import partition open their page instead.
                                    if (r.isActive || r.isImportSource) page = "device:${d.id}" else makeActive(d)
                                },
                                onDetails = { page = "device:${d.id}" },
                            )
                        }
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.devices_add_device),
                            titleColor = MaterialTheme.colorScheme.primary,
                            leading = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { showAddWizard = true },
                        )
                    }
                }
            }

            item(key = "strap") { StrapSettingsGroup(viewModel, onOpen = { page = "strap:${it.key}" }) }

            if (removed.isNotEmpty()) {
                item(key = "removed") {
                    ListGroup(header = stringResource(R.string.devices_removed)) {
                        removed.forEach { d ->
                            item { shape ->
                                DeviceRow(
                                    shape = shape,
                                    device = d,
                                    readout = DeviceReadout.make(d, live, ringPct),
                                    dimmed = true,
                                    onSelect = { page = "device:${d.id}" },
                                    onDetails = { page = "device:${d.id}" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickNewActive) {
        AlertDialog(
            onDismissRequest = { pickNewActive = false },
            title = { Text(stringResource(R.string.devices_pick_new_active)) },
            text = {
                Column {
                    activatable.forEach { d ->
                        Text(
                            displayName(d),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { pickNewActive = false; makeActive(d) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickNewActive = false }) { Text(stringResource(R.string.devices_cancel)) }
            },
        )
    }
}

// MARK: - Active device card

/**
 * The active device as the screen's hero: its glyph inside the battery ring, the name, the link word and
 * charge, the last sync, and the two things done to a strap most (Buzz, Sync Now); a ring gets
 * Reconnect Ring. Tapping the card opens the device's page.
 */
@Composable
private fun ActiveDeviceCard(
    device: PairedDeviceRow,
    readout: DeviceReadout,
    live: LiveState,
    onOpen: () -> Unit,
    onBuzz: () -> Unit,
    onSync: () -> Unit,
    onReconnectRing: () -> Unit,
) {
    val pct = readout.shownBattery
    val ringColor = if (pct != null && pct < 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val locale = LocalConfiguration.current.locales[0]
    val syncLine = if (readout.isWhoop) {
        SummarySyncLine.resolve(live.backfilling, live.lastSyncAt, System.currentTimeMillis() / 1000)
    } else SummarySyncLine.Hidden
    val syncText = syncLineText(syncLine, locale)
    val bondedLink = readout.isWhoop && live.connected && live.bonded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(M3Dimens.heroRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProgressRing(
            fraction = pct?.let { it / 100f },
            color = ringColor,
            modifier = Modifier.size(132.dp),
            stroke = 8.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Icon(deviceGlyph(device), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(64.dp))
        }
        Text(displayName(device), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = if (readout.link == DeviceLink.CONNECTED) Health.colors.positive else MaterialTheme.colorScheme.outline
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(6.dp))
            Text(
                deviceStatusLine(readout),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (syncText != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (syncLine == SummarySyncLine.Syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(syncText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (readout.isWhoop || readout.isOura) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (readout.isWhoop) {
                    // #921: the confirmed one-shot buzz sequence, over a bonded link only.
                    FilledTonalButton(onClick = onBuzz, enabled = bondedLink, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Filled.Vibration, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.devices_buzz))
                    }
                    // The gated user "Sync now" (#93): a safe no-op unless connected, bonded and idle.
                    FilledTonalButton(
                        onClick = onSync,
                        enabled = bondedLink && !live.backfilling,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.Sync, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.devices_sync_now))
                    }
                }
                if (readout.isOura) {
                    // #2305: drops the ring link, if any, and connects again.
                    FilledTonalButton(onClick = onReconnectRing) { Text(stringResource(R.string.devices_reconnect_ring)) }
                }
            }
        }
    }
}

// MARK: - Device row

/** As Bluetooth lists a device: ✓ on the active one, the name over "Connected · 82 %", and ⓘ. */
@Composable
private fun DeviceRow(
    shape: androidx.compose.ui.graphics.Shape,
    device: PairedDeviceRow,
    readout: DeviceReadout,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
    dimmed: Boolean = false,
) {
    Box(Modifier.alpha(if (dimmed) 0.6f else 1f).semantics { selected = readout.isActive }) {
        ListRow(
            shape = shape,
            title = displayName(device),
            subtitle = deviceStatusLine(readout),
            leading = {
                if (readout.isActive) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(deviceGlyph(device), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
            },
            trailing = {
                IconButton(onClick = onDetails) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.devices_details), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            onClick = onSelect,
        )
    }
}

// MARK: - Warnings

/** A problem that stops the device working: one line on a notice, the full step-by-step fix behind
 *  "How to Fix". Twin of Swift `DeviceWarning`. */
@Composable
internal fun DeviceWarning(title: String, message: String, detail: String) {
    var showFix by remember { mutableStateOf(false) }
    NoticeCard(
        icon = Icons.Filled.Warning,
        title = title,
        message = message,
        error = true,
        action = stringResource(R.string.devices_how_to_fix),
        onAction = { showFix = true },
    )
    if (showFix) {
        AlertDialog(
            onDismissRequest = { showFix = false },
            title = { Text(title) },
            text = {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showFix = false }) { Text(stringResource(R.string.devices_done)) } },
        )
    }
}

/** Whether a stop of the history sync is offered: only while one runs on the live WHOOP. */
internal fun canStopSync(live: LiveState, readout: DeviceReadout): Boolean =
    readout.isLive && readout.isWhoop && WhoopBleClient.canAbortSync(live.backfilling)
