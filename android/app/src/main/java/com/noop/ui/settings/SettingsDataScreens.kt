package com.noop.ui.settings

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.DeviceStatus
import com.noop.data.ImportSummary
import com.noop.data.Metric
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.AppleHealthImporter
import com.noop.ingest.HealthConnectImporter
import com.noop.ingest.HealthConnectImporter.ImportCategory
import com.noop.ingest.HealthConnectWriter
import com.noop.ingest.LiftingImporter
import com.noop.ingest.NutritionCsvImporter
import com.noop.ingest.WearableExportImporter
import com.noop.ingest.WhoopCsvImporter
import com.noop.ingest.XiaomiBandImporter
import com.noop.ui.AppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.emitImportTrace
import com.noop.ui.m3.ChoiceDialog
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - Import (twin of iOS DataSourcesView, "Import") and Health Connect (twin of AppleHealthView)
//
// Import: the stored WHOOP history on top, then one row per importer; a row opens the file picker, shows a
// spinner while its import runs and what it holds once it has run. The destructive "Remove Apple Health
// data" sits last and asks first. Health Connect: the Android twin of the iOS Apple Health page, sync
// only: import now, the categories read, automatic sync and its interval, and sharing reNOOP's own
// metrics back. Same importers, the same import log lines (#421) and Test Centre trace as Data Sources.

/** The importers, in the iOS order. */
private enum class Importer { WHOOP, APPLE_HEALTH, MI_FITNESS, WEARABLES, WORKOUT_FILES, LIFTING, NUTRITION }

/** What is stored per source, counted with scalar queries (never full row reads). */
private data class SourceCounts(
    val whoopDays: Int = 0,
    val whoopWorkouts: Int = 0,
    val appleDays: Int = 0,
    val appleWorkouts: Int = 0,
    val hcDays: Int = 0,
    val hcWorkouts: Int = 0,
    val nutritionDays: Int = 0,
    val liftingWorkouts: Int = 0,
    val activityFiles: Int = 0,
    val xiaomiDays: Int = 0,
    val wearableDays: Int = 0,
)

private suspend fun loadCounts(vm: AppViewModel): SourceCounts {
    val nowS = System.currentTimeMillis() / 1000
    val repo = vm.repo
    // #1304/#512: the active strap's union, so a second strap's history is counted too.
    return SourceCounts(
        whoopDays = repo.daysMerged(vm.activeStrapId).size,
        whoopWorkouts = repo.workoutsUnion(vm.activeStrapId, 0L, nowS).size,
        appleDays = repo.appleDailyCount("apple-health", "0000-01-01", "9999-12-31"),
        appleWorkouts = repo.workoutsCount("apple-health", 0L, nowS),
        hcDays = repo.appleDailyCount("health-connect", "0000-01-01", "9999-12-31"),
        hcWorkouts = repo.workoutsCount("health-connect", 0L, nowS),
        nutritionDays = repo.metricSeriesKeyCount(NutritionCsvImporter.SOURCE_ID, "calories_in"),
        liftingWorkouts = repo.workoutsCount(LiftingImporter.SOURCE_ID, 0L, nowS),
        activityFiles = repo.workoutsCount(ActivityFileImporter.SOURCE_ID, 0L, nowS),
        xiaomiDays = repo.metricSeriesKeyCount(XiaomiBandImporter.DEFAULT_DEVICE_ID, "steps"),
        wearableDays = WearableExportImporter.Brand.values().sumOf {
            repo.metricSeriesKeyCount(it.sourceId, "rhr") + repo.metricSeriesKeyCount(it.sourceId, "sleep_total_min")
        },
    )
}

/**
 * Run one importer off the main thread, log it to the strap log (brand + per-table counts, never a file
 * name or a value, #421), emit the Test Centre trace, then toast the importer's own summary.
 */
private suspend fun runImporter(context: Context, vm: AppViewModel, block: suspend () -> ImportSummary) {
    val summary = withContext(Dispatchers.IO) {
        runCatching { block() }.getOrElse { ImportSummary.failure("Import", it.message ?: "failed") }
    }
    if (summary.totalRows > 0) {
        vm.ble.externalLog("Import ${summary.source}: " + summary.counts.entries.joinToString(", ") { "${it.key}=${it.value}" })
    } else {
        vm.ble.externalLog("Import ${summary.source} failed: ${summary.message}")
    }
    emitImportTrace(context, vm, summary)
    Toast.makeText(context, summary.message, Toast.LENGTH_LONG).show()
}

@Composable
internal fun SettingsImportScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var counts by remember { mutableStateOf<SourceCounts?>(null) }
    var running by remember { mutableStateOf<Importer?>(null) }
    var confirmRemoveApple by remember { mutableStateOf(false) }
    val removedApple = stringResource(R.string.settings_apple_health_removed)
    LaunchedEffect(Unit) { counts = loadCounts(vm) }

    fun launchImport(which: Importer, block: suspend () -> ImportSummary) {
        running = which
        scope.launch {
            try {
                runImporter(context, vm, block)
                counts = loadCounts(vm)
            } finally {
                running = null
            }
        }
    }

    @Composable
    fun picker(which: Importer, block: suspend (android.net.Uri) -> ImportSummary): ManagedActivityResultLauncher<Array<String>, android.net.Uri?> =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) launchImport(which) { block(uri) }
        }

    val whoop = picker(Importer.WHOOP) { WhoopCsvImporter.importZip(context, it, vm.repo) }
    val apple = picker(Importer.APPLE_HEALTH) { AppleHealthImporter.importExport(context, it, vm.repo) }
    val xiaomi = picker(Importer.MI_FITNESS) { XiaomiBandImporter.importExport(context, it, vm.repo) }
    val wearable = picker(Importer.WEARABLES) { WearableExportImporter.importExport(context, it, vm.repo) }
    val nutrition = picker(Importer.NUTRITION) { NutritionCsvImporter.importCsv(context, it, vm.repo) }
    val lifting = picker(Importer.LIFTING) { uri ->
        LiftingImporter.importExport(context, uri, vm.repo).also { vm.loadWorkouts() }
    }
    val workoutFile = picker(Importer.WORKOUT_FILES) { uri ->
        ActivityFileImporter.importExport(context, uri, vm.repo).also { summary ->
            vm.loadWorkouts()
            // #137 (B1): register `activity-file` as a paired (never active) device once it holds data, so
            // the per-day owner resolver can pick it on a strap-less day. Idempotent.
            if (summary.totalRows > 0) {
                val now = System.currentTimeMillis() / 1000
                vm.registerDevice(
                    PairedDeviceRow(
                        id = ActivityFileImporter.SOURCE_ID,
                        brand = "Workout files",
                        model = "",
                        nickname = null,
                        peripheralId = null,
                        sourceKind = SourceKind.activityFile.name,
                        capabilities = Metric.hr.name,
                        status = DeviceStatus.paired.name,
                        addedAt = now,
                        lastSeenAt = now,
                    ),
                    makeActive = false,
                )
            }
        }
    }

    val c = counts
    val notImported = stringResource(R.string.settings_not_imported)
    fun daysLine(days: Int, workouts: Int?, context: Context): String? = when {
        days <= 0 && (workouts ?: 0) <= 0 -> null
        workouts != null && workouts > 0 -> context.getString(R.string.settings_days_workouts, days, workouts)
        else -> context.getString(R.string.settings_days_count, days)
    }

    SettingsPage(title = stringResource(R.string.settings_import), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.settings_days_stored),
                        subtitle = c?.let { it.whoopDays.toString() } ?: "—",
                    )
                }
            }
        }
        item {
            ListGroup {
                fun row(which: Importer, @StringRes title: Int, value: String?, onClick: () -> Unit) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(title),
                            subtitle = if (c == null) "…" else value ?: notImported,
                            enabled = running == null,
                            trailing = if (running == which) { { BusyTrailing() } } else null,
                            onClick = onClick,
                        )
                    }
                }
                row(Importer.WHOOP, R.string.settings_import_whoop, c?.let { daysLine(it.whoopDays, it.whoopWorkouts, context) }) {
                    whoop.launch(arrayOf("*/*"))
                }
                row(Importer.APPLE_HEALTH, R.string.nav_apple_health, c?.let { daysLine(it.appleDays, it.appleWorkouts, context) }) {
                    apple.launch(arrayOf("*/*"))
                }
                row(Importer.MI_FITNESS, R.string.settings_import_mi_fitness, c?.let { daysLine(it.xiaomiDays, null, context) }) {
                    xiaomi.launch(arrayOf("*/*"))
                }
                row(Importer.WEARABLES, R.string.settings_import_wearables, c?.let { if (it.wearableDays > 0) context.getString(R.string.settings_day_metrics, it.wearableDays) else null }) {
                    wearable.launch(arrayOf("*/*"))
                }
                row(Importer.WORKOUT_FILES, R.string.settings_import_workout_files, c?.let { if (it.activityFiles > 0) context.getString(R.string.settings_workouts_count, it.activityFiles) else null }) {
                    workoutFile.launch(arrayOf("*/*"))
                }
                row(Importer.LIFTING, R.string.settings_import_lifting, c?.let { if (it.liftingWorkouts > 0) context.getString(R.string.settings_workouts_count, it.liftingWorkouts) else null }) {
                    lifting.launch(arrayOf("*/*"))
                }
                row(Importer.NUTRITION, R.string.settings_import_nutrition, c?.let { daysLine(it.nutritionDays, null, context) }) {
                    nutrition.launch(arrayOf("*/*"))
                }
            }
        }
        if (c != null && (c.appleDays > 0 || c.appleWorkouts > 0)) {
            item {
                ListGroup {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.settings_remove_apple_health_data),
                            titleColor = MaterialTheme.colorScheme.error,
                            enabled = running == null,
                            onClick = { confirmRemoveApple = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemoveApple) {
        // ah-delete (#616): deletes every Apple-Health-sourced row in one transaction via the registry.
        ConfirmDialog(
            title = stringResource(R.string.l10n_data_sources_screen_remove_apple_health_imported_data_5f878502),
            message = stringResource(R.string.l10n_data_sources_screen_this_permanently_deletes_everything_imported_from_f42e760e),
            confirmLabel = stringResource(R.string.l10n_data_sources_screen_remove_e963907d),
            destructive = true,
            onConfirm = {
                confirmRemoveApple = false
                running = Importer.APPLE_HEALTH
                scope.launch {
                    try {
                        runCatching { withContext(Dispatchers.IO) { vm.deletePairedDeviceData("apple-health") } }
                        vm.ble.externalLog("Import apple-health: imported data removed")
                        counts = loadCounts(vm)
                        vm.loadWorkouts()
                        confirm(context, removedApple)
                    } finally {
                        running = null
                    }
                }
            },
            onDismiss = { confirmRemoveApple = false },
        )
    }
}

// MARK: - Health Connect

private val SYNC_HOURS = listOf(6, 12, 24)

@Composable
internal fun SettingsHealthConnectScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoSync by vm.hcAutoSync.collectAsStateWithLifecycle()
    val syncHours by vm.hcSyncHours.collectAsStateWithLifecycle()
    val lastSync by vm.hcLastSync.collectAsStateWithLifecycle()
    val writeback by vm.hcWriteback.collectAsStateWithLifecycle()
    val wbStatus by vm.hcWritebackStatus.collectAsStateWithLifecycle()
    var categories by remember { mutableStateOf(HealthConnectImporter.selectedCategories(context)) }
    var busy by remember { mutableStateOf(false) }
    var hoursDialog by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<SourceCounts?>(null) }
    val available = remember { HealthConnectImporter.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }
    val notGranted = stringResource(R.string.settings_hc_not_granted)
    val writeNotGranted = stringResource(R.string.settings_hc_write_not_granted)
    // A background (strap-path) writeback updates prefs, not the flow: re-read on entry (#660).
    LaunchedEffect(Unit) {
        vm.refreshHcWritebackStatus()
        counts = loadCounts(vm)
    }

    fun importNow() {
        busy = true
        scope.launch {
            try {
                runImporter(context, vm) { HealthConnectImporter.import(context, vm.repo, ProfileStore.from(context).heightCm) }
                counts = loadCounts(vm)
            } finally {
                busy = false
            }
        }
    }

    val readPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.any { it in HealthConnectImporter.permissionsFor(categories) }) importNow()
        else Toast.makeText(context, notGranted, Toast.LENGTH_LONG).show()
    }

    /** Import now when the selected read permissions are granted; otherwise ask first (#150, #645, #949). */
    fun startImport() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            HealthConnectImporter.migrateSelectionFromGrants(context, granted)
            categories = HealthConnectImporter.selectedCategories(context)
            val wanted = HealthConnectImporter.permissionsFor(categories)
            if (granted.any { it in wanted } && !HealthConnectImporter.hasUnaskedPermissions(context, categories)) {
                importNow()
            } else {
                HealthConnectImporter.markPermissionsAsked(context, categories)
                readPermissions.launch(wanted)
            }
        }
    }

    val writePermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        // #1525: this prompt already asked for VO2 max; do not ask again through the one-shot below.
        NoopPrefs.setHcVo2MaxAsked(context, true)
        if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
            vm.writebackHealthConnectNow()
        } else {
            vm.setHcWriteback(false)
            Toast.makeText(context, writeNotGranted, Toast.LENGTH_LONG).show()
        }
    }
    val vo2Permission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { _ ->
        NoopPrefs.setHcVo2MaxAsked(context, true)
        vm.writebackHealthConnectNow()
    }

    /** Write now when the write permissions are granted; otherwise ask (VO2 max once, outside the gate, #1525). */
    fun startWriteback() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            if (granted.containsAll(HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS)) {
                if (!granted.containsAll(HealthConnectWriter.VO2MAX_PERMISSIONS) && !NoopPrefs.hcVo2MaxAsked(context)) {
                    vo2Permission.launch(HealthConnectWriter.VO2MAX_PERMISSIONS)
                } else {
                    vm.writebackHealthConnectNow()
                }
            } else {
                writePermissions.launch(
                    HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS + HealthConnectWriter.VO2MAX_PERMISSIONS,
                )
            }
        }
    }

    SettingsPage(title = stringResource(R.string.settings_health_connect), onBack = onBack) {
        if (!available) {
            item {
                NoticeCard(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.settings_hc_unavailable),
                )
            }
            return@SettingsPage
        }
        item {
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.settings_sync_now),
                        subtitle = if (lastSync > 0L) {
                            stringResource(R.string.settings_synced_relative, DateUtils.getRelativeTimeSpanString(lastSync).toString())
                        } else {
                            stringResource(R.string.settings_not_synced)
                        },
                        titleColor = MaterialTheme.colorScheme.primary,
                        enabled = !busy,
                        trailing = if (busy) { { BusyTrailing() } } else null,
                        onClick = { startImport() },
                    )
                }
                counts?.let { cs ->
                    if (cs.hcDays > 0 || cs.hcWorkouts > 0) {
                        item { shape ->
                            ListRow(
                                shape = shape,
                                title = stringResource(R.string.settings_imported),
                                subtitle = stringResource(R.string.settings_days_workouts, cs.hcDays, cs.hcWorkouts),
                            )
                        }
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_sync_automatically), autoSync, { on ->
                        vm.setHcAutoSync(on)
                        // Ask for the permissions (and run a first sync) when turning it on.
                        if (on) startImport()
                    })
                }
                if (autoSync) {
                    item { shape ->
                        ValueRow(shape, stringResource(R.string.settings_every), stringResource(R.string.settings_hours_n, syncHours)) {
                            hoursDialog = true
                        }
                    }
                }
            }
        }
        item {
            // #645: the last enabled category cannot be switched off; an empty request has no result.
            ListGroup(header = stringResource(R.string.health_connect_categories_title)) {
                ImportCategory.entries.forEach { category ->
                    item { shape ->
                        val checked = category in categories
                        ListRow(
                            shape = shape,
                            title = stringResource(category.titleRes()),
                            subtitle = stringResource(category.detailRes()),
                            enabled = !checked || categories.size > 1,
                            role = Role.Checkbox,
                            trailing = { Checkbox(checked = checked, onCheckedChange = null, enabled = !checked || categories.size > 1) },
                            onClick = {
                                val next = if (checked) categories - category else categories + category
                                if (next.isNotEmpty()) {
                                    categories = next
                                    HealthConnectImporter.setSelectedCategories(context, next)
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            val status = if (!writeback || wbStatus.code.isEmpty()) null else when (wbStatus.code) {
                NoopPrefs.HC_WB_PERMISSION_DENIED -> stringResource(R.string.l10n_data_sources_screen_sharing_paused_health_connect_permission_was_e8950315)
                NoopPrefs.HC_WB_REMOTE_ERROR -> stringResource(R.string.l10n_data_sources_screen_last_share_didn_t_finish_noop_0d6c27f0)
                else -> stringResource(R.string.l10n_data_sources_screen_last_shared_6bf8389c, DateUtils.getRelativeTimeSpanString(wbStatus.atMs).toString())
            }
            ListGroup {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_data_sources_screen_share_back_to_health_connect_1d578f4a),
                        subtitle = status,
                        checked = writeback,
                        onCheckedChange = { on ->
                            vm.setHcWriteback(on)
                            if (on) startWriteback()
                        },
                    )
                }
                // #660: a paused share is fixed by asking for the write permissions again.
                if (writeback && wbStatus.code == NoopPrefs.HC_WB_PERMISSION_DENIED) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.settings_hc_allow_again),
                            titleColor = MaterialTheme.colorScheme.primary,
                            onClick = { startWriteback() },
                        )
                    }
                }
            }
        }
    }

    if (hoursDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_every),
            options = SYNC_HOURS.map { context.getString(R.string.settings_hours_n, it) },
            selectedIndex = SYNC_HOURS.indexOf(syncHours),
            onPick = { i ->
                hoursDialog = false
                vm.setHcSyncHours(SYNC_HOURS[i])
            },
            onDismiss = { hoursDialog = false },
        )
    }
}

@StringRes
private fun ImportCategory.titleRes(): Int = when (this) {
    ImportCategory.RECOVERY -> R.string.health_connect_category_recovery
    ImportCategory.ACTIVITY -> R.string.health_connect_category_activity
    ImportCategory.BODY_COMPOSITION -> R.string.health_connect_category_body_composition
}

@StringRes
private fun ImportCategory.detailRes(): Int = when (this) {
    ImportCategory.RECOVERY -> R.string.health_connect_category_recovery_detail
    ImportCategory.ACTIVITY -> R.string.health_connect_category_activity_detail
    ImportCategory.BODY_COMPOSITION -> R.string.health_connect_category_body_composition_detail
}
