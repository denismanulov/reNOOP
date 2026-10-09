package com.noop.ui

import android.bluetooth.BluetoothManager
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.ExperimentalBrand
import com.noop.ble.OuraLiveSource
import com.noop.ble.WhoopModel
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.data.WhoopLiveCapabilities
import com.noop.oura.OuraRingGen
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.SwitchRow
import kotlinx.coroutines.launch

// MARK: - Add Device — a guided, branching pairing flow (MW-4), as Android's own pairing pages
//
// Full-screen steps over Devices (twin of Swift `AddDeviceWizard`): the type list (WHOOP / Other / Beta),
// a pairing card for the chosen type (title, Beta tag, one line of what to do, its glyph, "Find"), the
// pick list ("Choose Your Device", signal bars, "Searching…", "Search Again", or "Bluetooth is off for
// reNOOP" + "Open Settings"), and the confirm card (Name + "Connect"). Different bands pair completely
// differently, so the type decides which scan/register path runs:
//
//   • WHOOP 4.0 / 5.0 (MG) → the WHOOP present-scan ([AppViewModel.presentWhoopScan]) targeted at the chosen
//     family, listing [AppViewModel.discoveredWhoops] (a present-only mode that never auto-connects).
//   • Heart-rate strap / Garmin broadcast → its own isolated [com.noop.ble.StandardHrSource] (0x180D).
//   • Gym equipment → FtmsSource; Amazfit / Mi Band → HuamiHrSource.
//   • Oura → the factory-reset-and-adopt sub-flow with its TWO irreversible gates (the consent switch and
//     the "Take over this ring?" alert), the Advanced own-key path, and the honest Adopting / Failed pages.
//
// Registration goes through [AppViewModel.registerDevice] → DeviceRegistry; the SourceCoordinator reacts
// and connects. The wizard never touches the BLE client beyond the AppViewModel pass-throughs, and every
// scan stops when its page is left or the wizard closes.

/** What the user is adding. Drives the prep copy AND which scan/register path runs. */
private enum class DeviceType {
    Whoop5MG, Whoop4, HrStrap, GymEquipment,
    // EXPERIMENTAL tier: best-effort, clean-room, can't be hardware-verified here. Each fails to an
    // honest message and never fabricates data.
    Oura, Amazfit, MiBand, Garmin;

    val isWhoop: Boolean get() = this == Whoop4 || this == Whoop5MG
    val whoopModel: WhoopModel?
        get() = when (this) {
            Whoop4 -> WhoopModel.WHOOP4
            Whoop5MG -> WhoopModel.WHOOP5_MG
            else -> null
        }

    /** True for the Beta tier. */
    val isExperimental: Boolean get() = this == Amazfit || this == MiBand || this == Garmin || this == Oura

    /** The experimental-tier brand this type registers as (the [com.noop.data.DeviceBrandCatalog] facts). */
    val experimentalBrand: ExperimentalBrand?
        get() = when (this) {
            Amazfit -> ExperimentalBrand.AMAZFIT
            MiBand -> ExperimentalBrand.MI_BAND
            Garmin -> ExperimentalBrand.GARMIN
            Oura -> ExperimentalBrand.OURA
            else -> null
        }

    val glyph: ImageVector
        get() = when (this) {
            Whoop5MG, Whoop4, Amazfit, MiBand, Garmin -> Icons.Filled.Watch
            HrStrap -> Icons.Filled.MonitorHeart
            GymEquipment -> Icons.Filled.FitnessCenter
            Oura -> Icons.Filled.RadioButtonUnchecked
        }
}

/** The type's name: model names verbatim, generic kinds in the app's language. */
@Composable
private fun typeTitle(t: DeviceType): String = when (t) {
    DeviceType.Whoop5MG -> "WHOOP 5.0 / MG"
    DeviceType.Whoop4 -> "WHOOP 4.0"
    DeviceType.HrStrap -> stringResource(R.string.wizard_hr_strap)
    DeviceType.GymEquipment -> stringResource(R.string.wizard_gym)
    DeviceType.Amazfit -> "Amazfit / Zepp"
    DeviceType.MiBand -> "Xiaomi Mi Band"
    DeviceType.Garmin -> stringResource(R.string.wizard_garmin)
    DeviceType.Oura -> stringResource(R.string.wizard_oura)
}

/** The one thing to do before a scan — the step most pairings fail on. */
private fun prepLine(t: DeviceType): Int = when (t) {
    DeviceType.Whoop4 -> R.string.wizard_prep_whoop4
    DeviceType.Whoop5MG -> R.string.wizard_prep_whoop5
    DeviceType.HrStrap -> R.string.wizard_prep_hr
    DeviceType.GymEquipment -> R.string.wizard_prep_gym
    DeviceType.Amazfit -> R.string.wizard_prep_amazfit
    DeviceType.MiBand -> R.string.wizard_prep_miband
    DeviceType.Garmin -> R.string.wizard_prep_garmin
    DeviceType.Oura -> R.string.wizard_prep_oura
}

/**
 * One page of the flow; the type list is its root. `Prep`, `Pick`, `Confirm` are the generic shape; the
 * `Oura…` pages are the factory-reset-and-adopt sub-flow (same set as Swift `AddDeviceWizard.Page`).
 */
private enum class WizardPage { Prep, Pick, Confirm, OuraGate, OuraKey, OuraPrep, OuraPick, OuraConfirm, OuraAdopting, OuraFailed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceWizard(
    viewModel: AppViewModel,
    onClose: () -> Unit,
    /** Routes to the non-destructive file-import lane: the Oura gate's and failure page's "Import a File". */
    onUseFileImport: () -> Unit = onClose,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var path by remember { mutableStateOf(listOf<WizardPage>()) }
    var type by remember { mutableStateOf<DeviceType?>(null) }

    // --- Oura factory-reset-and-adopt state (inert for every other type) ---
    /** The irreversible "this disconnects the ring from Oura" switch must be on to continue. */
    var ouraConsent by remember { mutableStateOf(false) }
    /** The Advanced path: the user supplies their own 16-byte key and keeps the Oura app. */
    var ouraAdvanced by remember { mutableStateOf(false) }
    var ouraKeyDraft by remember { mutableStateOf("") }
    var pickedOura by remember { mutableStateOf<OuraLiveSource.DiscoveredRing?>(null) }
    var ouraGen by remember { mutableStateOf(OuraRingGen.GEN3) }
    /** The SECOND irreversible gate before the key install. */
    var ouraConfirmAdopt by remember { mutableStateOf(false) }

    // The chosen device, in whichever shape its path produces.
    var pickedWhoop by remember { mutableStateOf<com.noop.ble.WhoopBleClient.DiscoveredWhoop?>(null) }
    var pickedStrap by remember { mutableStateOf<com.noop.ble.StandardHrSource.DiscoveredStrap?>(null) }
    var pickedMachine by remember { mutableStateOf<com.noop.ble.FtmsSource.DiscoveredMachine?>(null) }
    var pickedHuami by remember { mutableStateOf<com.noop.ble.HuamiHrSource.DiscoveredDevice?>(null) }
    var nameDraft by remember { mutableStateOf("") }

    // Discovery-only scanners: they never connect or persist; the wizard only reads their flows.
    val hrScanner = remember { viewModel.makeStrapScanner() }
    val ftmsScanner = remember { viewModel.makeFtmsScanner() }
    val huamiScanner = remember { viewModel.makeHuamiScanner() }
    val ouraScanner = remember { viewModel.makeOuraScanner() }

    fun startScan(t: DeviceType) {
        when {
            t.isWhoop -> viewModel.presentWhoopScan(t.whoopModel ?: WhoopModel.WHOOP4)
            t == DeviceType.GymEquipment -> ftmsScanner.scan()
            t == DeviceType.Amazfit || t == DeviceType.MiBand -> huamiScanner.scan()
            t == DeviceType.Oura -> ouraScanner.scan()
            else -> hrScanner.scan()   // HrStrap AND Garmin (Broadcast HR is the standard 0x180D path)
        }
    }

    fun stopAllScans() {
        viewModel.stopWhoopScan()
        hrScanner.stopScan()
        ftmsScanner.stopScan()
        huamiScanner.stopScan()
        ouraScanner.stop()
    }

    // Belt-and-braces: stop whichever scan is live whenever the wizard leaves composition.
    DisposableEffect(Unit) { onDispose { stopAllScans() } }

    fun close() { stopAllScans(); onClose() }
    fun push(p: WizardPage) { path = path + p }

    /** Undo what a page left running once Back takes it off the stack (Swift `didPop`). */
    fun didPop(p: WizardPage) {
        when (p) {
            WizardPage.Prep, WizardPage.OuraPrep -> Unit
            WizardPage.Pick -> stopAllScans()
            WizardPage.Confirm -> {
                // Back on the pick step: restart its scan so the user can choose a different device.
                type?.let { startScan(it) }
                pickedWhoop = null; pickedStrap = null; pickedMachine = null; pickedHuami = null
            }
            WizardPage.OuraGate -> ouraConsent = false
            WizardPage.OuraKey -> { ouraAdvanced = false; ouraKeyDraft = "" }
            WizardPage.OuraPick -> { ouraScanner.stop(); pickedOura = null }
            WizardPage.OuraConfirm, WizardPage.OuraAdopting, WizardPage.OuraFailed -> { ouraScanner.scan(); pickedOura = null }
        }
    }

    fun pop() {
        val top = path.lastOrNull() ?: return close()
        // A key install in flight has no way back.
        if (top == WizardPage.OuraAdopting) return
        path = path.dropLast(1)
        didPop(top)
    }

    /** Adoption failed: the honest dead-end replaces the confirm and progress pages, so Back (or Try
     *  Again) returns to the ring list, never to a key install that already ended. */
    fun showOuraFailed() {
        val i = path.lastIndexOf(WizardPage.OuraPick)
        path = (if (i >= 0) path.take(i + 1) else path) + WizardPage.OuraFailed
    }

    fun choose(t: DeviceType) {
        type = t
        nameDraft = ""
        if (t == DeviceType.Oura) {
            // The adopt gate is destructive, so every fresh entry re-requires the consent.
            ouraConsent = false; ouraAdvanced = false; ouraKeyDraft = ""; ouraConfirmAdopt = false; pickedOura = null
            push(WizardPage.OuraGate)
        } else {
            push(WizardPage.Prep)
        }
    }

    // A scan asks for the Bluetooth permissions first (once), then runs; the pick page says so if refused.
    var scanAfterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requestScan = rememberRequestScan { scanAfterGrant?.invoke(); scanAfterGrant = null }
    fun find(t: DeviceType, page: WizardPage) {
        scanAfterGrant = { startScan(t); push(page) }
        requestScan()
    }

    val defaultDevice = stringResource(R.string.wizard_device)
    val typeName = type?.let { typeTitle(it) } ?: defaultDevice
    val advertisedName: String = pickedWhoop?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: typeName }
        ?: pickedStrap?.name ?: pickedMachine?.name ?: pickedHuami?.name ?: pickedOura?.name ?: typeName
    val hrStrapLabel = stringResource(R.string.wizard_hr_strap)

    /** Build the right [PairedDeviceRow] for the chosen path, register it active, then close. */
    fun finishAdd() {
        stopAllScans()
        val now = System.currentTimeMillis() / 1000
        val confirmName = nameDraft.trim().ifEmpty { advertisedName }
        val pw = pickedWhoop
        val ps = pickedStrap
        val pm = pickedMachine
        val ph = pickedHuami
        val device: PairedDeviceRow? = when {
            pw != null && type?.whoopModel != null -> {
                // WHOOP: honest live capability set (no calibrated SpO₂ % — import-only; #548); id namespaced
                // by address; model "4.0" / "5.0 MG". Steps only on 5.0/MG.
                val modelLabel = if (type!!.whoopModel == WhoopModel.WHOOP4) "4.0" else "5.0 MG"
                PairedDeviceRow(
                    id = "whoop-${pw.address}", brand = "WHOOP", model = modelLabel, nickname = confirmName,
                    peripheralId = pw.address, sourceKind = SourceKind.liveBLE.name,
                    capabilities = WhoopLiveCapabilities.encoded(modelLabel),
                    status = DeviceStatus.paired.name, addedAt = now, lastSeenAt = now,
                )
            }
            ps != null -> {
                // A generic HR strap OR a Garmin broadcasting standard HR (still `liveBLE`): Garmin's brand and
                // id prefix come from the catalog; a strap keeps the advertised-name brand guess. HR + HRV.
                val garmin = if (type == DeviceType.Garmin) ExperimentalBrand.GARMIN else null
                PairedDeviceRow(
                    id = "${garmin?.idPrefix ?: "strap"}-${ps.address}",
                    brand = garmin?.displayBrand ?: brandGuess(ps.name).let { if (it == "Heart-rate strap") hrStrapLabel else it },
                    model = ps.name, nickname = if (confirmName == ps.name) null else confirmName,
                    peripheralId = ps.address, sourceKind = SourceKind.liveBLE.name, capabilities = "hr,hrv",
                    status = DeviceStatus.paired.name, addedAt = now, lastSeenAt = now,
                )
            }
            ph != null -> {
                // EXPERIMENTAL Amazfit / Zepp / Mi Band: brand, id prefix and "huami" routing from the catalog.
                // HR only (the Huami custom characteristic carries no R-R).
                val brand = type?.experimentalBrand ?: ExperimentalBrand.AMAZFIT
                PairedDeviceRow(
                    id = "${brand.idPrefix}-${ph.address}", brand = brand.displayBrand, model = ph.name,
                    nickname = if (confirmName == ph.name) null else confirmName, peripheralId = ph.address,
                    sourceKind = brand.sourceKind.name, capabilities = "hr",
                    status = DeviceStatus.paired.name, addedAt = now, lastSeenAt = now,
                )
            }
            pm != null -> {
                // FTMS gym machine: "ftms" routes the SourceCoordinator to the FtmsSource.
                PairedDeviceRow(
                    id = "ftms-${pm.address}", brand = "Gym equipment", model = pm.name,
                    nickname = if (confirmName == pm.name) null else confirmName, peripheralId = pm.address,
                    sourceKind = SourceKind.ftms.name, capabilities = "hr",
                    status = DeviceStatus.paired.name, addedAt = now, lastSeenAt = now,
                )
            }
            else -> null
        }
        if (device == null) { onClose(); return }
        // "Connect": the new device becomes the active one, as iOS connects on the confirm card.
        scope.launch { viewModel.registerDevice(device, makeActive = true) }
        onClose()
    }

    /**
     * Register an adopted (or Advanced-key) Oura ring active. Advanced: the user's key is stored first and
     * NO adopt-intent is armed, so the dangerous install opcode is never sent; the wizard closes. Standard
     * adopt: the one-shot adopt-intent is armed BEFORE registering (after both irreversible gates), and the
     * wizard stays on Adopting until the source streams (close) or reports a failure (Failed).
     */
    fun finishAddOura(closeAfter: Boolean) {
        stopAllScans()
        val ring = pickedOura ?: run { onClose(); return }
        val now = System.currentTimeMillis() / 1000
        val oura = ExperimentalBrand.OURA
        val deviceId = "${oura.idPrefix}-${ring.address}"
        if (ouraAdvanced) {
            val key = parseHexKey(ouraKeyDraft)
            if (key != null) viewModel.saveOuraInstallKey(deviceId, key)
        } else {
            viewModel.armOuraAdopt(deviceId)
        }
        val device = PairedDeviceRow(
            id = deviceId,
            brand = oura.displayBrand,
            model = ouraGen.displayName,
            nickname = nameDraft.trim().takeIf { it.isNotEmpty() && it != ring.name },
            peripheralId = ring.address,
            sourceKind = oura.sourceKind.name,
            capabilities = ouraGen.capabilities.joinToString(",") { it.raw },
            status = DeviceStatus.paired.name,
            addedAt = now,
            lastSeenAt = now,
        )
        scope.launch { viewModel.registerDevice(device, makeActive = true) }
        if (closeAfter) onClose()
    }

    // Drive the Adopting page to success (the active source reached streaming -> close) or to the honest
    // Failed page. Only while Adopting, so a later steady-state needs-pairing never reopens this.
    val adoptPhase by viewModel.ouraAdoptPhase.collectAsStateWithLifecycle()
    val adoptNeedsPairing by viewModel.ouraNeedsPairing.collectAsStateWithLifecycle()
    LaunchedEffect(type, path, adoptPhase, adoptNeedsPairing) {
        if (type != DeviceType.Oura || path.lastOrNull() != WizardPage.OuraAdopting) return@LaunchedEffect
        when {
            adoptPhase == OuraLiveSource.AdoptPhase.Streaming -> close()
            adoptPhase == OuraLiveSource.AdoptPhase.Failed -> showOuraFailed()
            adoptNeedsPairing != null -> showOuraFailed()
        }
    }

    // A full page over Devices: Back walks the steps, ✕ (or Back on the type list) closes the flow.
    BackHandler { pop() }
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                val top = path.lastOrNull()
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        if (top != null && top != WizardPage.OuraAdopting) {
                            IconButton(onClick = { pop() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { close() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.wizard_close))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val t = type
                    when (top) {
                        null -> TypeList(onPick = ::choose)
                        WizardPage.Prep -> if (t != null) PairingCard(typeTitle(t), stringResource(prepLine(t)), t.glyph, t.isExperimental) {
                            WideButton(stringResource(R.string.wizard_find)) { find(t, WizardPage.Pick) }
                        }
                        WizardPage.Pick -> if (t != null) GenericPick(t, viewModel, hrScanner, ftmsScanner, huamiScanner,
                            onRescan = { startScan(t) },
                            onWhoop = { strap ->
                                pickedWhoop = strap; pickedStrap = null; pickedMachine = null; pickedHuami = null
                                nameDraft = strap.name?.takeIf { it.isNotBlank() } ?: ""
                                viewModel.stopWhoopScan(); push(WizardPage.Confirm)
                            },
                            onStrap = { strap ->
                                pickedStrap = strap; pickedWhoop = null; pickedMachine = null; pickedHuami = null
                                nameDraft = strap.name; hrScanner.stopScan(); push(WizardPage.Confirm)
                            },
                            onMachine = { m ->
                                pickedMachine = m; pickedWhoop = null; pickedStrap = null; pickedHuami = null
                                nameDraft = m.name; ftmsScanner.stopScan(); push(WizardPage.Confirm)
                            },
                            onHuami = { d ->
                                pickedHuami = d; pickedWhoop = null; pickedStrap = null; pickedMachine = null
                                nameDraft = d.name; huamiScanner.stopScan(); push(WizardPage.Confirm)
                            },
                        )
                        WizardPage.Confirm -> PairingCard(advertisedName, null, t?.glyph ?: Icons.Filled.MonitorHeart, t?.isExperimental == true) {
                            NameField(nameDraft, advertisedName) { nameDraft = it }
                            WideButton(stringResource(R.string.devices_connect)) { finishAdd() }
                        }
                        WizardPage.OuraGate -> PairingCard(typeTitle(DeviceType.Oura), stringResource(R.string.wizard_oura_gate),
                            DeviceType.Oura.glyph, beta = true) {
                            ListGroup {
                                item { shape ->
                                    SwitchRow(shape = shape, title = stringResource(R.string.wizard_oura_consent),
                                        checked = ouraConsent, onCheckedChange = { ouraConsent = it })
                                }
                            }
                            WideButton(stringResource(R.string.wizard_continue), enabled = ouraConsent) { push(WizardPage.OuraPrep) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                // Keep the Oura app and import a file instead; or the user's own key.
                                TextButton(onClick = { stopAllScans(); onUseFileImport() }) { Text(stringResource(R.string.wizard_import_file)) }
                                TextButton(onClick = { ouraAdvanced = true; push(WizardPage.OuraKey) }) { Text(stringResource(R.string.wizard_have_key)) }
                            }
                        }
                        WizardPage.OuraKey -> OuraKeyPage(ouraKeyDraft, onDraft = { ouraKeyDraft = it }) { find(DeviceType.Oura, WizardPage.OuraPick) }
                        WizardPage.OuraPrep -> PairingCard(typeTitle(DeviceType.Oura), stringResource(R.string.wizard_prep_oura),
                            DeviceType.Oura.glyph, beta = true) {
                            WideButton(stringResource(R.string.wizard_find)) { find(DeviceType.Oura, WizardPage.OuraPick) }
                        }
                        WizardPage.OuraPick -> OuraPick(ouraScanner,
                            onPick = { ring ->
                                pickedOura = ring
                                // The generation from the ring's best-effort detection, gen3 when it carries none.
                                ouraGen = ring.detectedGen ?: OuraRingGen.GEN3
                                nameDraft = ""
                                ouraScanner.stopScan()
                                push(WizardPage.OuraConfirm)
                            },
                            onRescan = { ouraScanner.scan() },
                            onImport = { stopAllScans(); onUseFileImport() },
                        )
                        WizardPage.OuraConfirm -> PairingCard(ouraGen.displayName, null, DeviceType.Oura.glyph, beta = true) {
                            NameField(nameDraft, pickedOura?.name ?: typeTitle(DeviceType.Oura)) { nameDraft = it }
                            if (ouraAdvanced) {
                                // The own-key path never resets the ring: a plain Connect.
                                WideButton(stringResource(R.string.devices_connect)) { finishAddOura(closeAfter = true) }
                            } else {
                                WideButton(stringResource(R.string.wizard_take_over_ring), destructive = true) { ouraConfirmAdopt = true }
                            }
                        }
                        WizardPage.OuraAdopting -> PairingCard(typeTitle(DeviceType.Oura), stringResource(R.string.wizard_adopting),
                            DeviceType.Oura.glyph, beta = true) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        WizardPage.OuraFailed -> PairingCard(stringResource(R.string.wizard_failed_title), null, DeviceType.Oura.glyph, beta = true) {
                            // The source's own message, never a fabricated success.
                            Text(
                                adoptNeedsPairing ?: stringResource(R.string.wizard_failed_message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            WideButton(stringResource(R.string.wizard_try_again)) { pop() }
                            TextButton(onClick = { stopAllScans(); onUseFileImport() }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.wizard_import_file))
                            }
                        }
                    }
                }
            }
        }

        // The SECOND irreversible gate (after the consent switch): arms adopt-intent and registers the
        // ring active; the live source then runs the one-time key install.
        if (ouraConfirmAdopt) {
            AlertDialog(
                onDismissRequest = { ouraConfirmAdopt = false },
                title = { Text(stringResource(R.string.wizard_take_over_confirm)) },
                text = { Text(stringResource(R.string.wizard_take_over_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        ouraConfirmAdopt = false
                        push(WizardPage.OuraAdopting)
                        finishAddOura(closeAfter = false)
                    }) { Text(stringResource(R.string.wizard_take_over), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { ouraConfirmAdopt = false }) { Text(stringResource(R.string.devices_cancel)) } },
            )
        }
    }
}

// MARK: - Type list

@Composable
private fun TypeList(onPick: (DeviceType) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
    ) {
        item { PageTitle(stringResource(R.string.devices_add_device)) }
        item { TypeGroup("WHOOP", listOf(DeviceType.Whoop5MG, DeviceType.Whoop4), onPick) }
        item { TypeGroup(stringResource(R.string.wizard_other), listOf(DeviceType.HrStrap, DeviceType.GymEquipment), onPick) }
        item {
            TypeGroup(
                stringResource(R.string.wizard_beta),
                listOf(DeviceType.Oura, DeviceType.Amazfit, DeviceType.MiBand, DeviceType.Garmin),
                onPick,
            )
        }
    }
}

@Composable
private fun TypeGroup(header: String, types: List<DeviceType>, onPick: (DeviceType) -> Unit) {
    ListGroup(header = header) {
        types.forEach { t ->
            item { shape ->
                ListRow(
                    shape = shape,
                    title = typeTitle(t),
                    leading = {
                        Box(
                            Modifier.size(M3Dimens.iconCircle).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(t.glyph, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                        }
                    },
                    trailing = { ChevronRight() },
                    onClick = { onPick(t) },
                )
            }
        }
    }
}

@Composable
private fun PageTitle(text: String, detail: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// MARK: - Pairing card

/**
 * A step as a pairing page lays it out: a title with at most one line under it (and "Beta" for the
 * experimental tier), the device's glyph in the middle, the actions at the bottom. Scrolls when the
 * text is large rather than clipping.
 */
@Composable
private fun PairingCard(
    title: String,
    detail: String?,
    glyph: ImageVector,
    beta: Boolean,
    actions: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                if (beta) {
                    Text(
                        stringResource(R.string.wizard_beta),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Box(
                Modifier.padding(vertical = 32.dp).size(160.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(glyph, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(88.dp))
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { actions() }
        }
    }
}

@Composable
private fun WideButton(text: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = if (destructive) ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ) else ButtonDefaults.buttonColors(),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** The device's name, editable before it is added; empty falls back to the advertised one. */
@Composable
private fun NameField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(R.string.devices_name)) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: - Pick lists

/** Whether a scan can run at all: the Bluetooth permissions granted and the adapter on. */
private fun bluetoothUsable(context: android.content.Context): Pair<Boolean, Boolean> {
    val granted = blePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val on = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)
    return granted to on
}

/**
 * "Choose Your Device": a searching spinner until something answers, then one row per device found,
 * strongest signal first, and "Search Again". With Bluetooth refused or off, the scan can never answer,
 * so it says why and where to fix it instead of searching forever.
 */
@Composable
private fun PickScreen(
    found: List<Triple<String, Int, () -> Unit>>,
    hint: String,
    onRescan: () -> Unit,
) {
    val context = LocalContext.current
    val (granted, on) = remember { bluetoothUsable(context) }
    LazyColumn(
        contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
    ) {
        item { PageTitle(stringResource(R.string.wizard_choose_device)) }
        if (!granted || !on) {
            item {
                ListGroup {
                    item { shape -> ListRow(shape = shape, title = stringResource(R.string.wizard_bt_off)) }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.wizard_open_settings),
                            titleColor = MaterialTheme.colorScheme.primary,
                            onClick = {
                                val intent = if (!granted) {
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", context.packageName, null))
                                } else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                runCatching { context.startActivity(intent) }
                            },
                        )
                    }
                }
            }
            return@LazyColumn
        }
        item {
            ListGroup(footer = if (found.isEmpty()) hint else null) {
                if (found.isEmpty()) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.wizard_searching),
                            titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            leading = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
                        )
                    }
                } else {
                    found.forEach { (name, rssi, onTap) ->
                        item { shape ->
                            val a11y = stringResource(R.string.wizard_signal, name, SignalBars.level(rssi))
                            ListRow(
                                shape = shape,
                                title = name,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = a11y },
                                trailing = { SignalBarsGlyph(rssi) },
                                onClick = onTap,
                            )
                        }
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.wizard_search_again),
                        titleColor = MaterialTheme.colorScheme.primary,
                        onClick = onRescan,
                    )
                }
            }
        }
    }
}

/** Four Wi-Fi-style bars from RSSI (negative dBm; closer to 0 is stronger). Coarse on purpose. */
@Composable
private fun SignalBarsGlyph(rssi: Int) {
    val level = SignalBars.level(rssi)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(18.dp)) {
        for (i in 0 until 4) {
            Box(
                Modifier
                    .width(3.dp)
                    .height((6 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < level) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun GenericPick(
    t: DeviceType,
    viewModel: AppViewModel,
    hrScanner: com.noop.ble.StandardHrSource,
    ftmsScanner: com.noop.ble.FtmsSource,
    huamiScanner: com.noop.ble.HuamiHrSource,
    onRescan: () -> Unit,
    onWhoop: (com.noop.ble.WhoopBleClient.DiscoveredWhoop) -> Unit,
    onStrap: (com.noop.ble.StandardHrSource.DiscoveredStrap) -> Unit,
    onMachine: (com.noop.ble.FtmsSource.DiscoveredMachine) -> Unit,
    onHuami: (com.noop.ble.HuamiHrSource.DiscoveredDevice) -> Unit,
) {
    val defaultHint = stringResource(R.string.wizard_hint_default)
    when {
        t.isWhoop -> {
            val found by viewModel.discoveredWhoops.collectAsStateWithLifecycle()
            PickScreen(
                found.sortedByDescending { it.rssi }.map { s -> Triple(s.name?.takeIf { it.isNotBlank() } ?: "WHOOP", s.rssi) { onWhoop(s) } },
                hint = stringResource(R.string.wizard_hint_whoop),
                onRescan = onRescan,
            )
        }
        t == DeviceType.GymEquipment -> {
            val found by ftmsScanner.discovered.collectAsStateWithLifecycle()
            PickScreen(found.sortedByDescending { it.rssi }.map { m -> Triple(m.name, m.rssi) { onMachine(m) } }, defaultHint, onRescan)
        }
        t == DeviceType.Amazfit || t == DeviceType.MiBand -> {
            val found by huamiScanner.discovered.collectAsStateWithLifecycle()
            PickScreen(found.sortedByDescending { it.rssi }.map { d -> Triple(d.name, d.rssi) { onHuami(d) } }, defaultHint, onRescan)
        }
        else -> {
            val found by hrScanner.discovered.collectAsStateWithLifecycle()
            PickScreen(found.sortedByDescending { it.rssi }.map { s -> Triple(s.name, s.rssi) { onStrap(s) } }, defaultHint, onRescan)
        }
    }
}

/** The ring pick step. A ring that won't answer (still Oura-owned, not reset, key rejected) shows the
 *  source's honest message and the file-import lane instead of a list — never a fabricated reading. */
@Composable
private fun OuraPick(
    scanner: OuraLiveSource,
    onPick: (OuraLiveSource.DiscoveredRing) -> Unit,
    onRescan: () -> Unit,
    onImport: () -> Unit,
) {
    val needsPairing by scanner.needsPairing.collectAsStateWithLifecycle()
    val found by scanner.discovered.collectAsStateWithLifecycle()
    val msg = needsPairing
    if (msg != null) {
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item { PageTitle(stringResource(R.string.wizard_choose_device)) }
            item { ListGroup { item { shape -> ListRow(shape = shape, title = msg) } } }
            item {
                ListGroup {
                    item { shape ->
                        ListRow(shape = shape, title = stringResource(R.string.wizard_import_file),
                            titleColor = MaterialTheme.colorScheme.primary, onClick = onImport)
                    }
                }
            }
        }
    } else {
        PickScreen(
            found.sortedByDescending { it.rssi }.map { r -> Triple(r.name, r.rssi) { onPick(r) } },
            hint = stringResource(R.string.wizard_hint_oura),
            onRescan = onRescan,
        )
    }
}

/** The Advanced path: authenticate with the user's own 16-byte key, no reset (the Oura app keeps working).
 *  Validates 32 hex characters before Find. */
@Composable
private fun OuraKeyPage(draft: String, onDraft: (String) -> Unit, onFind: () -> Unit) {
    val parsed = parseHexKey(draft)
    val a11y = stringResource(R.string.wizard_ring_key_a11y)
    LazyColumn(
        contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
    ) {
        item { PageTitle(stringResource(R.string.wizard_ring_key), stringResource(R.string.wizard_ring_key_detail)) }
        item {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                singleLine = true,
                isError = draft.isNotBlank() && parsed == null,
                placeholder = { Text("0123456789abcdef0123456789abcdef") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false),
                supportingText = if (draft.isNotBlank() && parsed == null) {
                    { Text(stringResource(R.string.wizard_ring_key_invalid)) }
                } else null,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = a11y },
            )
        }
        item { WideButton(stringResource(R.string.wizard_find), enabled = parsed != null, onClick = onFind) }
    }
}

/**
 * Parse a 32-hex-character ring key string into 16 unsigned bytes (0..255), or null when it is not exactly
 * 32 hex chars. Whitespace is ignored so a pasted key with stray spaces still validates. Shared by the key
 * page's validation and finishAddOura's key store write. Mirrors the macOS 16-byte/32-hex check.
 */
private fun parseHexKey(input: String): IntArray? {
    val hex = input.filterNot { it.isWhitespace() }
    if (hex.length != 32) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return IntArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
}
