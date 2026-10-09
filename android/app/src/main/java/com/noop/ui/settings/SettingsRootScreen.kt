package com.noop.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.BuildConfig
import com.noop.R
import com.noop.ui.AppViewModel
import com.noop.ui.BackupSyncPrefs
import com.noop.ui.CoachEnabledStore
import com.noop.ui.Destination
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.LocalTonalIcons
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.M3Switch

// MARK: - Settings root (twin of iOS SettingsView, drawn like Android 16 Settings)
//
// iOS order: the profile (photo, name → Health Details), the active strap → Devices, then App (General,
// Notifications, and Automations — Android's twin of the iOS Shortcuts page), Features (Workouts, Scores,
// the AI Coach and Hydration switches), Data (Health Connect, Import, Backup), About reNOOP, and Developer
// once the version in About has been tapped seven times.

@Composable
internal fun SettingsRootScreen(vm: AppViewModel, open: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val tones = LocalTonalIcons.current
    val live by vm.live.collectAsStateWithLifecycle()
    val deviceName by vm.activeDeviceName.collectAsStateWithLifecycle()
    val hcLastSync by vm.hcLastSync.collectAsStateWithLifecycle()
    // SharedPreferences are not observable; these pages are re-entered on every return from a sub-page, so
    // a plain read each composition is current.
    val profile = remember { ProfileStore.from(context) }
    var hydration by remember { mutableStateOf(NoopPrefs.hydrationTracking(context)) }
    val developer = DeveloperUnlock.isUnlocked(NoopPrefs.of(context))
    val lastBackupMs = BackupSyncPrefs.lastBackupMs(context)
    val backupFolderSet = BackupSyncPrefs.treeUri(context) != null

    SettingsPage(title = stringResource(R.string.l10n_settings_screen_settings_c7f73bb5), onBack = onBack) {
        item {
            ProfileHeader(
                name = profile.displayName,
                initials = profile.initials,
                onClick = { open(SettingsRoutes.HEALTH_DETAILS) },
            )
        }
        item {
            val link = strapLink(live.connected, live.scanning)
            val battery = strapBatteryPercent(live.connected, live.batteryPct)
            val status = when (link) {
                StrapLink.CONNECTED -> if (battery != null) {
                    stringResource(R.string.settings_strap_connected_battery, battery)
                } else {
                    stringResource(R.string.recording_chip_title_connected)
                }
                StrapLink.SEARCHING -> stringResource(R.string.settings_strap_searching)
                StrapLink.NOT_CONNECTED -> stringResource(R.string.today_not_connected)
            }
            ListGroup {
                item { shape ->
                    IconRow(
                        shape = shape,
                        icon = Icons.Filled.Watch,
                        pair = tones.blue,
                        title = deviceName ?: stringResource(R.string.settings_devices),
                        subtitle = status,
                        onClick = { open(Destination.Devices.route) },
                    )
                }
            }
        }
        item {
            // Browse left the bottom bar when Coach and Friends took a place there (a sixth tab would not
            // fit); every screen and metric outside the tabs is found from here.
            ListGroup {
                item { shape ->
                    IconRow(shape, Icons.Filled.Search, tones.grey, stringResource(R.string.nav_browse),
                        stringResource(R.string.settings_browse_summary)) { open(Destination.Browse.route) }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_group_app)) {
                item { shape ->
                    IconRow(shape, Icons.Filled.Settings, tones.grey, stringResource(R.string.settings_general),
                        stringResource(R.string.settings_general_summary)) { open(SettingsRoutes.GENERAL) }
                }
                item { shape ->
                    IconRow(shape, Icons.Filled.Notifications, tones.red, stringResource(R.string.nav_notifications),
                        stringResource(R.string.settings_notifications_summary)) { open(SettingsRoutes.NOTIFICATIONS) }
                }
                item { shape ->
                    IconRow(shape, Icons.Filled.Bolt, tones.purple, stringResource(R.string.nav_automations),
                        stringResource(R.string.settings_automations_summary_naps)) { open(Destination.Automations.route) }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_group_features)) {
                item { shape ->
                    IconRow(shape, Icons.AutoMirrored.Filled.DirectionsRun, tones.green, stringResource(R.string.settings_workouts),
                        stringResource(R.string.settings_workouts_summary)) { open(SettingsRoutes.WORKOUTS) }
                }
                item { shape ->
                    IconRow(shape, Icons.Filled.Speed, tones.red, stringResource(R.string.settings_scores),
                        stringResource(R.string.settings_scores_summary)) { open(SettingsRoutes.SCORES) }
                }
                item { shape ->
                    // The AI master switch: off hides Coach everywhere and cancels the daily brief
                    // (CoachEnabledStore.set reschedules it). The provider key is kept.
                    val on = CoachEnabledStore.enabled
                    SwitchIconRow(shape, Icons.Filled.AutoAwesome, tones.purple, stringResource(R.string.l10n_settings_screen_ai_coach_130c3eab), on) {
                        CoachEnabledStore.set(context, it)
                    }
                }
                item { shape ->
                    SwitchIconRow(shape, Icons.Filled.WaterDrop, tones.teal, stringResource(R.string.settings_hydration), hydration) {
                        hydration = it
                        NoopPrefs.setHydrationTracking(context, it)
                    }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_group_data)) {
                item { shape ->
                    IconRow(
                        shape, Icons.Filled.HealthAndSafety, tones.pink, stringResource(R.string.settings_health_connect),
                        if (hcLastSync > 0L) {
                            stringResource(R.string.settings_synced_relative, DateUtils.getRelativeTimeSpanString(hcLastSync).toString())
                        } else {
                            stringResource(R.string.settings_not_synced)
                        },
                    ) { open(SettingsRoutes.HEALTH_CONNECT) }
                }
                item { shape ->
                    IconRow(shape, Icons.Filled.Download, tones.blue, stringResource(R.string.settings_import),
                        stringResource(R.string.settings_import_summary)) { open(Destination.DataSources.route) }
                }
                item { shape ->
                    IconRow(
                        shape, Icons.Filled.SettingsBackupRestore, tones.teal, stringResource(R.string.settings_backup),
                        when {
                            lastBackupMs > 0L -> stringResource(
                                R.string.settings_last_backup_relative,
                                DateUtils.getRelativeTimeSpanString(lastBackupMs).toString(),
                            )
                            backupFolderSet -> stringResource(R.string.settings_no_backup_yet)
                            else -> stringResource(R.string.settings_backup_not_set_up)
                        },
                    ) { open(Destination.BackupSync.route) }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    IconRow(shape, Icons.Filled.Info, tones.grey, stringResource(R.string.settings_about_renoop),
                        stringResource(R.string.settings_version_value, BuildConfig.VERSION_NAME)) { open(SettingsRoutes.ABOUT) }
                }
                if (developer) {
                    item { shape ->
                        IconRow(shape, Icons.Filled.Build, tones.grey, stringResource(R.string.settings_developer),
                            stringResource(R.string.settings_developer_summary)) { open(SettingsRoutes.DEVELOPER) }
                    }
                }
            }
        }
    }
}

/** The profile card on top: the photo or monogram, the name ("Profile" when unset), "Health Details". */
@Composable
private fun ProfileHeader(name: String, initials: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(M3Dimens.heroRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileMonogram(initials = initials, size = 56.dp)
        Column(Modifier.weight(1f)) {
            Text(
                name.ifEmpty { stringResource(R.string.settings_profile) },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.settings_health_details),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChevronRight()
    }
}

/** A root row with a tonal icon and a switch; the whole row toggles (TalkBack reads one switch). */
@Composable
private fun SwitchIconRow(
    shape: androidx.compose.ui.graphics.Shape,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    pair: com.noop.ui.m3.TonalPair,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    com.noop.ui.m3.ListRow(
        shape = shape,
        title = title,
        leading = { com.noop.ui.m3.TonalIcon(icon, pair) },
        trailing = { M3Switch(checked = checked, onCheckedChange = null) },
        role = Role.Switch,
        onClick = { onCheckedChange(!checked) },
    )
}
