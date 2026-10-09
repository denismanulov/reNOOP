package com.noop.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.data.DataBackup
import com.noop.data.LiveStoreReplacement
import com.noop.ingest.WhoopCsvExporter
import com.noop.ui.AppViewModel
import com.noop.ui.BackupSync
import com.noop.ui.BackupSyncPrefs
import com.noop.ui.ClockPrefs
import com.noop.ui.m3.ChoiceDialog
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.DialogParagraphs
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.SwitchRow
import com.noop.ui.sleep.ClockPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// MARK: - Backup (twin of iOS BackupSyncView)
//
// Laid out like Settings > Backup: the folder, then folder backup (back up now, restore a snapshot from
// the folder, daily, keep, the time it runs, the last one), then the whole-file export / import and the
// WHOOP-format CSV export. Snapshots are the `.noopbak` whole-database format (DataBackup). Every path that
// overwrites data asks first ("Replace all data"), and a successful restore restarts reNOOP so nothing
// keeps writing through a connection to the replaced database (#57).

private val KEEP_OPTIONS = listOf(1, 3, 5, 7, 10, 14)

/** The restore file fallback: the backup container types only (the importer validates the rest). */
private val RESTORE_MIME_TYPES = arrayOf("application/octet-stream", "application/zip", "application/x-sqlite3")

private enum class BackupDialog { KEEP, TIME, SNAPSHOTS }

/** A restore waiting for the reader's OK: what it is called in the question, and where it is. */
private data class PendingRestore(val label: String, val uri: Uri, val fromDate: Boolean)

@Composable
internal fun SettingsBackupScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf(BackupSyncPrefs.treeUri(context)) }
    var auto by remember { mutableStateOf(BackupSyncPrefs.autoEnabled(context)) }
    var lastMs by remember { mutableStateOf(BackupSyncPrefs.lastBackupMs(context)) }
    var keep by remember { mutableStateOf(BackupSyncPrefs.keepCount(context)) }
    var backupMinute by remember { mutableStateOf(BackupSyncPrefs.backupMinute(context)) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    var snapshots by remember { mutableStateOf<List<BackupSync.SnapshotDoc>>(emptyList()) }
    var pending by remember { mutableStateOf<PendingRestore?>(null) }
    var oversize by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    val backedUp = stringResource(R.string.settings_backed_up)
    val backupFailed = stringResource(R.string.settings_backup_failed_repick)
    val exported = stringResource(R.string.settings_backup_exported)
    val exportedLarge = stringResource(R.string.settings_backup_exported_large)
    val csvExported = stringResource(R.string.settings_csv_exported)
    val restarting = stringResource(R.string.settings_restored_restarting)

    /**
     * The restore itself, after the reader said yes. A successful one restarts reNOOP (#57); one that
     * failed after it had begun the swap restarts it when its message is dismissed (see the failure dialog).
     */
    fun runRestore(uri: Uri, allowOversize: Boolean = false) {
        busy = true
        scope.launch {
            try {
                // Not cancellable from the start. This scope is cancelled when the page leaves composition
                // (Back, another tab, a rotation), and a cancelled `withContext` discards its result: the
                // import ran to the end on its IO thread, the database file was swapped, and the restart
                // below never ran. `LiveStoreReplacement` keeps the strap's history safe meanwhile; this
                // makes the restart happen.
                withContext(NonCancellable) {
                    when (val r = withContext(Dispatchers.IO) { DataBackup.importFrom(context, uri, allowOversize) }) {
                        is DataBackup.ImportResult.NeedsRestart -> {
                            Toast.makeText(context, restarting, Toast.LENGTH_LONG).show()
                            delay(800)
                            restartApp(context)
                        }
                        is DataBackup.ImportResult.Failed -> failure = r.message
                        // #1807: refused only for size, which is recoverable: offer to go ahead, once.
                        is DataBackup.ImportResult.TooLarge -> if (allowOversize) failure = r.message else oversize = uri to r.message
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        BackupSyncPrefs.setTreeUri(context, uri)
        treeUri = uri
        runCatching { BackupSync.reschedule(context) }
    }
    val pickRestoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = PendingRestore(displayName(context, uri), uri, fromDate = false)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DataBackup.exportTo(context, uri) } }
            busy = false
            result.fold(
                // #1807: the file is written either way; past the restore ceiling, say so now.
                onSuccess = { Toast.makeText(context, if (it.overRestoreCeiling) exportedLarge else exported, Toast.LENGTH_LONG).show() },
                onFailure = { failure = it.message ?: it.toString() },
            )
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            // #458: the active strap's id, or a live-BLE install exports an empty zip.
            val result = withContext(Dispatchers.IO) {
                runCatching { WhoopCsvExporter.exportZip(context, uri, vm.repo, vm.activeStrapId) }
            }
            busy = false
            result.fold(
                onSuccess = { confirm(context, csvExported) },
                onFailure = { failure = it.message ?: it.toString() },
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = PendingRestore(displayName(context, uri), uri, fromDate = false)
    }

    val stale = auto && treeUri != null && lastMs > 0L && BackupSync.isBackupStale(lastMs, System.currentTimeMillis())
    val is24h = ClockPrefs.uses24Hour(context)
    val locale = context.resources.configuration.locales[0]

    SettingsPage(title = stringResource(R.string.settings_backup), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.settings_folder),
                        subtitle = treeUri?.let { folderLabel(it) } ?: stringResource(R.string.settings_not_set),
                    )
                }
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(if (treeUri == null) R.string.settings_choose_folder else R.string.settings_change_folder),
                        titleColor = MaterialTheme.colorScheme.primary,
                        enabled = !busy,
                        onClick = { pickFolder.launch(null) },
                    )
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(if (busy) R.string.settings_working else R.string.settings_back_up_now),
                        titleColor = MaterialTheme.colorScheme.primary,
                        enabled = treeUri != null && !busy,
                        trailing = if (busy) { { BusyTrailing() } } else null,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val ok = withContext(Dispatchers.IO) { BackupSync.backupNow(context) }
                                    lastMs = BackupSyncPrefs.lastBackupMs(context)
                                    if (ok) confirm(context, backedUp) else failure = backupFailed
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.settings_restore_ellipsis),
                        titleColor = MaterialTheme.colorScheme.primary,
                        enabled = !busy,
                        onClick = {
                            val tree = treeUri
                            if (tree == null) {
                                pickRestoreFile.launch(RESTORE_MIME_TYPES)
                            } else {
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) {
                                        runCatching { BackupSync.listSnapshotDocs(context, tree) }.getOrDefault(emptyList())
                                    }
                                    // A folder with none of our snapshots: restore a file from elsewhere.
                                    if (found.isEmpty()) {
                                        pickRestoreFile.launch(RESTORE_MIME_TYPES)
                                    } else {
                                        snapshots = found
                                        dialog = BackupDialog.SNAPSHOTS
                                    }
                                }
                            }
                        },
                    )
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_daily), auto, {
                        auto = it
                        BackupSyncPrefs.setAutoEnabled(context, it)
                        runCatching { BackupSync.reschedule(context) }
                    }, enabled = treeUri != null && !busy)
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.settings_keep_backups), keep.toString(), enabled = treeUri != null && !busy) {
                        dialog = BackupDialog.KEEP
                    }
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_backup_sync_screen_backup_time_81557aaa), clockLabel(backupMinute, is24h, locale),
                        enabled = treeUri != null && !busy) { dialog = BackupDialog.TIME }
                }
                if (lastMs > 0L) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.settings_last_backup),
                            subtitle = if (stale) {
                                stringResource(R.string.settings_last_backup_stale, DateUtils.getRelativeTimeSpanString(lastMs).toString())
                            } else {
                                DateUtils.getRelativeTimeSpanString(lastMs).toString()
                            },
                        )
                    }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_file), footer = stringResource(R.string.settings_backups_unencrypted)) {
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.settings_export_to_file), enabled = !busy, onClick = {
                        exportLauncher.launch("noop-backup-${LocalDate.now()}.noopbak")
                    })
                }
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.settings_import_from_file), enabled = !busy, onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    })
                }
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.settings_export_as_csv), enabled = !busy, onClick = {
                        csvLauncher.launch("noop-export-${LocalDate.now()}.zip")
                    })
                }
            }
        }
    }

    when (dialog) {
        BackupDialog.KEEP -> ChoiceDialog(
            title = stringResource(R.string.settings_keep_backups),
            options = KEEP_OPTIONS.map { it.toString() },
            selectedIndex = KEEP_OPTIONS.indexOf(keep),
            onPick = { i ->
                dialog = null
                keep = KEEP_OPTIONS[i]
                BackupSyncPrefs.setKeepCount(context, keep)
            },
            onDismiss = { dialog = null },
        )
        BackupDialog.TIME -> ClockPicker(
            title = stringResource(R.string.l10n_backup_sync_screen_backup_time_81557aaa),
            hour = backupMinute / 60,
            minute = backupMinute % 60,
            is24h = is24h,
            onPick = { h, m ->
                dialog = null
                backupMinute = h * 60 + m
                BackupSyncPrefs.setBackupMinute(context, backupMinute)
                runCatching { BackupSync.applyTimeChange(context) }
            },
            onDismiss = { dialog = null },
        )
        BackupDialog.SNAPSHOTS -> {
            // The folder's snapshots, newest first; a date when one resolved, else the file name.
            val labels = snapshots.map { snap ->
                if (snap.timeMs > 0L) DateUtils.formatDateTime(context, snap.timeMs,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                else snap.name
            }
            ChoiceDialog(
                title = stringResource(R.string.l10n_backup_sync_screen_choose_a_backup_2fbfb0d6),
                options = labels,
                selectedIndex = -1,
                onPick = { i ->
                    dialog = null
                    val snap = snapshots[i]
                    pending = PendingRestore(labels[i], snap.uri, fromDate = snap.timeMs > 0L)
                },
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }

    pending?.let { restore ->
        ConfirmDialog(
            title = stringResource(R.string.settings_restore_this_backup),
            message = stringResource(
                if (restore.fromDate) R.string.settings_restore_confirm_from_date else R.string.settings_restore_confirm_named,
                restore.label,
            ),
            confirmLabel = stringResource(R.string.settings_replace_all_data),
            destructive = true,
            onConfirm = {
                pending = null
                runRestore(restore.uri)
            },
            onDismiss = { pending = null },
        )
    }
    oversize?.let { (uri, message) ->
        ConfirmDialog(
            title = stringResource(R.string.settings_backup_problem),
            message = message,
            confirmLabel = stringResource(R.string.l10n_settings_screen_restore_3cbe6d6b),
            destructive = true,
            onConfirm = {
                oversize = null
                runRestore(uri, allowOversize = true)
            },
            onDismiss = { oversize = null },
        )
    }
    failure?.let { message ->
        // A restore that failed after it had begun the swap has replaced the live database file under
        // this process all the same (the backup went in, then the rollback put the old file back or
        // removed the damaged one), so the offload holds every ack until the process is gone
        // (`LiveStoreReplacement`). Say so under the failure, and restart when it is dismissed: every
        // way out of the dialog leaves the same process behind.
        val mustRestart = LiveStoreReplacement.happened
        BackupProblemDialog(
            message = message,
            note = if (mustRestart) stringResource(R.string.settings_restore_needs_restart) else null,
            confirmLabel = stringResource(if (mustRestart) R.string.settings_restart_renoop else R.string.summary_ok),
            onDismiss = {
                failure = null
                if (mustRestart) restartApp(context)
            },
        )
    }
}

/**
 * A backup or restore failure. These messages end on the part the reader can act on, which a toast would
 * cut off (#1014), so they get a dialog, with Copy so a corruption report carries SQLite's own words.
 * [note] is a paragraph of reNOOP's own under the failure; Copy takes the failure alone.
 */
@Composable
private fun BackupProblemDialog(message: String, note: String?, confirmLabel: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_problem)) },
        text = { DialogParagraphs(message, note) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clip?.setPrimaryClip(ClipData.newPlainText("reNOOP backup error", message))
                onDismiss()
            }) { Text(stringResource(R.string.l10n_components_copy_af74f7c5)) }
        },
    )
}

/** A short label for a SAF folder (the part after the volume colon). */
private fun folderLabel(treeUri: Uri): String {
    val seg = treeUri.lastPathSegment ?: return treeUri.toString()
    return seg.substringAfterLast(':').ifBlank { seg }
}

/** The picked file's display name, for the confirmation question. */
private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

/** Relaunch reNOOP in a fresh process, so Room reopens against the restored database file. */
private fun restartApp(context: Context) {
    val app = context.applicationContext
    app.packageManager.getLaunchIntentForPackage(app.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ?.let { app.startActivity(it) }
    Runtime.getRuntime().exit(0)
}
