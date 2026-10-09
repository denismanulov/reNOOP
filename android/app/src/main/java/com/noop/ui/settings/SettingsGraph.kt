package com.noop.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noop.ui.AppViewModel
import com.noop.ui.Destination

// MARK: - The Settings tree's routes
//
// Settings pushes on the Summary tab from its avatar. Its own pages register here so the shell (AppRoot)
// only calls [settingsGraph]; rows that open screens living elsewhere (Devices, Automations, the wrist
// alerts page, Test Centre…) push the shell's own `Destination` routes.

internal object SettingsRoutes {
    const val GENERAL = "settings/general"
    const val UNITS = "settings/units"
    const val NOTIFICATIONS = "settings/notifications"
    const val WORKOUTS = "settings/workouts"
    const val SCORES = "settings/scores"
    const val HEALTH_DETAILS = "settings/health_details"
    const val HR_ZONES = "settings/hr_zones"
    const val HEALTH_CONNECT = "settings/health_connect"
    const val ABOUT = "settings/about"
    const val DEVELOPER = "settings/developer"
}

/**
 * Registers the Settings root ([Destination.Settings]), its sub-pages, and the Import
 * ([Destination.DataSources]) and Backup ([Destination.BackupSync]) pages other screens also open.
 * [open] pushes a route on the current tab; [back] pops one.
 */
internal fun NavGraphBuilder.settingsGraph(vm: AppViewModel, open: (String) -> Unit, back: () -> Unit) {
    composable(Destination.Settings.route) { SettingsRootScreen(vm, open, back) }
    composable(SettingsRoutes.HEALTH_DETAILS) { HealthDetailsScreen(open, back) }
    composable(SettingsRoutes.HR_ZONES) { HeartRateZonesScreen(back) }
    composable(SettingsRoutes.GENERAL) { SettingsGeneralScreen(vm, open, back) }
    composable(SettingsRoutes.UNITS) { SettingsUnitsScreen(back) }
    composable(SettingsRoutes.NOTIFICATIONS) { SettingsNotificationsScreen(vm, open, back) }
    composable(SettingsRoutes.WORKOUTS) { SettingsWorkoutsScreen(back) }
    composable(SettingsRoutes.SCORES) { SettingsScoresScreen(vm, open, back) }
    composable(SettingsRoutes.HEALTH_CONNECT) { SettingsHealthConnectScreen(vm, back) }
    composable(Destination.DataSources.route) { SettingsImportScreen(vm, back) }
    composable(Destination.BackupSync.route) { SettingsBackupScreen(vm, back) }
    composable(SettingsRoutes.ABOUT) { SettingsAboutScreen(back) }
    composable(SettingsRoutes.DEVELOPER) { SettingsDeveloperScreen(vm, open, back) }
}
