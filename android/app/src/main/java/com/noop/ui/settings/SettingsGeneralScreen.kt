package com.noop.ui.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.analytics.DayCycleMode
import com.noop.analytics.SkinTempDisplay
import com.noop.ui.AppLanguage
import com.noop.ui.AppLanguagePrefs
import com.noop.ui.AppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.TemperatureUnit
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import com.noop.ui.m3.ChoiceDialog
import com.noop.ui.m3.ListGroup
import com.noop.ui.summary.SummaryLayout
import com.noop.ui.summary.SummaryLayoutPrefs

// MARK: - General (twin of iOS GeneralSettingsPage) and Units (UnitsSettingsPage)
//
// Display-only preferences: nothing stored changes, reNOOP keeps everything in SI. The language belongs to
// the system on Android 13+ (Settings > Apps > reNOOP > Language), as it does on iOS; older Android keeps
// the in-app picker, the only way there to run the app in another language than the phone's. The clock,
// reduce motion and the theme are the system's too and have no copy here (ST-4).

/** Which single-choice dialog of a page is open. */
private enum class GeneralDialog { LANGUAGE, DAY_START, APP_ICON, SUMMARY_LAYOUT }

@Composable
internal fun SettingsGeneralScreen(vm: AppViewModel, open: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<GeneralDialog?>(null) }
    var dayCycle by remember { mutableStateOf(NoopPrefs.dayCycleMode(context)) }
    var navyIcon by remember { mutableStateOf(NoopPrefs.appIconNavy(context)) }
    var summaryLayout by remember { mutableStateOf(SummaryLayoutPrefs.layout(context)) }
    val languageMode = languageRowMode(Build.VERSION.SDK_INT)

    val systemDefault = stringResource(R.string.settings_language_system)
    val languageValue = AppLanguagePrefs.currentLanguageName(context) ?: systemDefault
    val dayStartLabels = listOf(
        stringResource(R.string.settings_day_cycle_sleep),
        stringResource(R.string.settings_day_cycle_midnight),
    )
    val iconLabels = listOf(stringResource(R.string.settings_icon_default), stringResource(R.string.settings_icon_navy))
    val layoutLabels = listOf(stringResource(R.string.summary_layout_detailed), stringResource(R.string.summary_layout_compact))

    SettingsPage(title = stringResource(R.string.settings_general), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.settings_language), languageValue) {
                        when (languageMode) {
                            LanguageRowMode.SYSTEM_PER_APP -> {
                                AppLanguagePrefs.handOverToSystem(context)
                                openAppLanguageSettings(context)
                            }
                            LanguageRowMode.IN_APP_PICKER -> dialog = GeneralDialog.LANGUAGE
                        }
                    }
                }
                item { shape ->
                    ValueRow(
                        shape,
                        stringResource(R.string.settings_day_cycle_starts),
                        dayStartLabels[if (dayCycle == DayCycleMode.SLEEP_ONSET) 0 else 1],
                    ) { dialog = GeneralDialog.DAY_START }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_app_icon_abde7a74), iconLabels[if (navyIcon) 1 else 0]) {
                        dialog = GeneralDialog.APP_ICON
                    }
                }
                item { shape ->
                    ValueRow(
                        shape,
                        stringResource(R.string.summary_layout),
                        layoutLabels[if (summaryLayout == SummaryLayout.DETAILED) 0 else 1],
                    ) { dialog = GeneralDialog.SUMMARY_LAYOUT }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_units_12748281), null, chevron = true) {
                        open(SettingsRoutes.UNITS)
                    }
                }
            }
        }
    }

    when (dialog) {
        GeneralDialog.LANGUAGE -> {
            val languages = AppLanguage.entries
            val current = AppLanguagePrefs.selected(context)
            ChoiceDialog(
                title = stringResource(R.string.settings_language),
                options = languages.map { if (it == AppLanguage.SYSTEM) systemDefault else it.autonym },
                selectedIndex = languages.indexOf(current),
                onPick = { i ->
                    dialog = null
                    val picked = languages[i]
                    if (picked != current) {
                        AppLanguagePrefs.set(context, picked)
                        context.hostingActivity()?.recreate()
                    }
                },
                onDismiss = { dialog = null },
            )
        }
        GeneralDialog.DAY_START -> ChoiceDialog(
            title = stringResource(R.string.settings_day_cycle_starts),
            options = dayStartLabels,
            selectedIndex = if (dayCycle == DayCycleMode.SLEEP_ONSET) 0 else 1,
            onPick = { i ->
                dialog = null
                val mode = if (i == 0) DayCycleMode.SLEEP_ONSET else DayCycleMode.MIDNIGHT
                if (mode != dayCycle) {
                    dayCycle = mode
                    vm.setDayCycleMode(mode)
                }
            },
            onDismiss = { dialog = null },
        )
        GeneralDialog.APP_ICON -> ChoiceDialog(
            title = stringResource(R.string.l10n_settings_screen_app_icon_abde7a74),
            options = iconLabels,
            selectedIndex = if (navyIcon) 1 else 0,
            onPick = { i ->
                dialog = null
                val navy = i == 1
                if (navy != navyIcon) {
                    navyIcon = navy
                    setAppIcon(context, navy)
                }
            },
            onDismiss = { dialog = null },
        )
        GeneralDialog.SUMMARY_LAYOUT -> ChoiceDialog(
            title = stringResource(R.string.summary_layout),
            options = layoutLabels,
            selectedIndex = if (summaryLayout == SummaryLayout.DETAILED) 0 else 1,
            onPick = { i ->
                dialog = null
                val layout = if (i == 0) SummaryLayout.DETAILED else SummaryLayout.COMPACT
                summaryLayout = layout
                SummaryLayoutPrefs.setLayout(context, layout)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Which Units dialog is open. */
private enum class UnitsDialog { BODY, DISTANCE, TEMPERATURE, SKIN_TEMPERATURE }

@Composable
internal fun SettingsUnitsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<UnitsDialog?>(null) }
    var system by remember { mutableStateOf(UnitPrefs.system(context)) }
    var distanceRaw by remember {
        mutableStateOf(NoopPrefs.of(context).getString(NoopPrefs.KEY_DISTANCE_UNIT_SYSTEM, "") ?: "")
    }
    var temperatureRaw by remember {
        mutableStateOf(NoopPrefs.of(context).getString(NoopPrefs.KEY_TEMPERATURE_UNIT, "") ?: "")
    }
    var skinTemp by remember { mutableStateOf(UnitPrefs.skinTempPreferred(context)) }
    val distance = UnitPrefs.resolveDistance(system, distanceRaw)

    val bodyLabels = listOf(stringResource(R.string.settings_units_metric), stringResource(R.string.settings_units_imperial))
    val distanceLabels = listOf(stringResource(R.string.units_kilometres), stringResource(R.string.units_miles))
    val temperatureTags = listOf("", TemperatureUnit.CELSIUS.raw, TemperatureUnit.FAHRENHEIT.raw)
    val temperatureLabels = listOf(stringResource(R.string.units_follow_body), "°C", "°F")
    val skinLabels = listOf(stringResource(R.string.settings_skin_temp_absolute), stringResource(R.string.settings_skin_temp_deviation))
    val temperatureIndex = temperatureTags.indexOf(temperatureRaw).coerceAtLeast(0)

    SettingsPage(title = stringResource(R.string.l10n_settings_screen_units_12748281), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.units_body_measurements), bodyLabels[if (system == UnitSystem.METRIC) 0 else 1]) {
                        dialog = UnitsDialog.BODY
                    }
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.units_exercise_distance_pace), distanceLabels[if (distance == UnitSystem.METRIC) 0 else 1]) {
                        dialog = UnitsDialog.DISTANCE
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_temperature_0a9062a9), temperatureLabels[temperatureIndex]) {
                        dialog = UnitsDialog.TEMPERATURE
                    }
                }
                item { shape ->
                    ValueRow(
                        shape,
                        stringResource(R.string.l10n_settings_screen_skin_temperature_fc103030),
                        skinLabels[if (skinTemp == SkinTempDisplay.Kind.ABSOLUTE) 0 else 1],
                    ) { dialog = UnitsDialog.SKIN_TEMPERATURE }
                }
            }
        }
    }

    when (dialog) {
        UnitsDialog.BODY -> ChoiceDialog(
            title = stringResource(R.string.units_body_measurements),
            options = bodyLabels,
            selectedIndex = if (system == UnitSystem.METRIC) 0 else 1,
            onPick = { i ->
                dialog = null
                system = if (i == 0) UnitSystem.METRIC else UnitSystem.IMPERIAL
                NoopPrefs.setUnitSystem(context, system)
            },
            onDismiss = { dialog = null },
        )
        UnitsDialog.DISTANCE -> ChoiceDialog(
            title = stringResource(R.string.units_exercise_distance_pace),
            options = distanceLabels,
            selectedIndex = if (distance == UnitSystem.METRIC) 0 else 1,
            onPick = { i ->
                dialog = null
                val picked = if (i == 0) UnitSystem.METRIC else UnitSystem.IMPERIAL
                distanceRaw = picked.raw
                NoopPrefs.setDistanceUnitSystem(context, picked)
            },
            onDismiss = { dialog = null },
        )
        UnitsDialog.TEMPERATURE -> ChoiceDialog(
            title = stringResource(R.string.l10n_settings_screen_temperature_0a9062a9),
            options = temperatureLabels,
            selectedIndex = temperatureIndex,
            onPick = { i ->
                dialog = null
                temperatureRaw = temperatureTags[i]
                NoopPrefs.setTemperatureUnit(context, TemperatureUnit.fromRaw(temperatureTags[i]))
            },
            onDismiss = { dialog = null },
        )
        UnitsDialog.SKIN_TEMPERATURE -> ChoiceDialog(
            title = stringResource(R.string.l10n_settings_screen_skin_temperature_fc103030),
            options = skinLabels,
            selectedIndex = if (skinTemp == SkinTempDisplay.Kind.ABSOLUTE) 0 else 1,
            onPick = { i ->
                dialog = null
                skinTemp = if (i == 0) SkinTempDisplay.Kind.ABSOLUTE else SkinTempDisplay.Kind.DEVIATION
                NoopPrefs.setSkinTempDisplay(context, skinTemp)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Android 13+: reNOOP's page in the system's per-app language settings; the app's details page if a ROM lacks it. */
private fun openAppLanguageSettings(context: Context) {
    val pkg = Uri.fromParts("package", context.packageName, null)
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, pkg))
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
    }
}

/** Compose may hand out a themed ContextWrapper rather than the Activity itself. */
private fun Context.hostingActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// MARK: - App icon swap

/** The two launcher-icon aliases in AndroidManifest.xml; exactly one is enabled at a time. */
private const val ALIAS_DEFAULT = "com.noop.IconDefault"
private const val ALIAS_NAVY = "com.noop.IconNavy"

/**
 * Persist the chosen launcher icon and flip the manifest aliases so exactly one is enabled. DONT_KILL_APP
 * keeps our own process alive; the launcher may redraw the icon after a moment.
 */
private fun setAppIcon(context: Context, navy: Boolean) {
    NoopPrefs.setAppIconNavy(context, navy)
    val pm = context.packageManager
    pm.setComponentEnabledSetting(
        ComponentName(context, ALIAS_NAVY),
        if (navy) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
    pm.setComponentEnabledSetting(
        ComponentName(context, ALIAS_DEFAULT),
        if (navy) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}
