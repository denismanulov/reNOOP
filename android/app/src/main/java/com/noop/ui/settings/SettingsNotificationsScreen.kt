package com.noop.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.ui.AppViewModel
import com.noop.ui.BiofeedbackPrefs
import com.noop.ui.ClockPrefs
import com.noop.ui.Destination
import com.noop.ui.InactivityPrefs
import com.noop.ui.NoopPrefs
import com.noop.ui.NotifPrefs
import com.noop.ui.m3.ChoiceDialog
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.SwitchRow
import com.noop.ui.sleep.ClockPicker
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Notifications (twin of iOS NotificationsSettingsPage)
//
// Every alert reNOOP can raise, on the wrist or on the phone, with the same keys and side effects the old
// Automations and Settings rows had. Reminders: the movement reminder (it buzzes through the wrist-alerts
// gate, so the page says when that is off) and stress check-ins. Alerts: the illness early warning, the
// strain target, the two scheduled reports (Android's own, #517), and the strap battery alerts. When the
// system has reNOOP's notifications off, the phone alerts are disabled and the first row opens the
// system switch that can turn them back on (ST-7). The wrist half (which apps buzz, calls, quiet hours)
// is its own page behind "Wrist alerts".

private enum class NotifDialog { AFTER_SITTING, REPEAT, BUZZ, FROM, TO }

@Composable
internal fun SettingsNotificationsScreen(vm: AppViewModel, open: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val illness by vm.illnessWatchEnabled.collectAsStateWithLifecycle()
    val battery by vm.batteryAlertsEnabled.collectAsStateWithLifecycle()
    val forecast by vm.predictiveBatteryAlertsEnabled.collectAsStateWithLifecycle()

    // The system switch and the wrist master can change while this page is away: re-read on every resume.
    var systemOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var wristOn by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.MASTER, false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            systemOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
            wristOn = NotifPrefs.getBool(context, NotifPrefs.MASTER, false)
        }
    }

    var movement by remember { mutableStateOf(InactivityPrefs.enabled(context)) }
    var afterSitting by remember { mutableStateOf(InactivityPrefs.thresholdMinutes(context)) }
    var repeatEvery by remember { mutableStateOf(InactivityPrefs.reNudgeMinutes(context)) }
    var buzzLoops by remember { mutableStateOf(InactivityPrefs.buzzLoops(context)) }
    var activeHours by remember { mutableStateOf(InactivityPrefs.activeHoursEnabled(context)) }
    var activeStart by remember { mutableStateOf(InactivityPrefs.activeStartMinutes(context)) }
    var activeEnd by remember { mutableStateOf(InactivityPrefs.activeEndMinutes(context)) }

    var stress by remember { mutableStateOf(BiofeedbackPrefs.checkInEnabled(context)) }
    var autoNudge by remember { mutableStateOf(BiofeedbackPrefs.autoNudge(context)) }
    var quietHours by remember { mutableStateOf(BiofeedbackPrefs.quietHoursEnabled(context)) }
    var resonance by remember { mutableStateOf(BiofeedbackPrefs.useResonancePace(context)) }

    var strainTarget by remember { mutableStateOf(NoopPrefs.strainTargetEnabled(context)) }
    var morningRecap by remember { mutableStateOf(NoopPrefs.morningReportEnabled(context)) }
    var workoutSummary by remember { mutableStateOf(NoopPrefs.postWorkoutReportEnabled(context)) }

    var dialog by remember { mutableStateOf<NotifDialog?>(null) }
    val minUnit = stringResource(R.string.metric_unit_min)
    val is24h = ClockPrefs.uses24Hour(context)
    val locale = context.resources.configuration.locales[0]

    SettingsPage(title = stringResource(R.string.nav_notifications), onBack = onBack) {
        if (!systemOn) {
            item {
                ListGroup {
                    item { shape ->
                        ValueRow(shape, stringResource(R.string.nav_notifications), stringResource(R.string.settings_off)) {
                            openAppNotificationSettings(context)
                        }
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ValueRow(
                        shape,
                        stringResource(R.string.l10n_notifications_settings_screen_wrist_alerts_75581d51),
                        stringResource(if (wristOn) R.string.settings_on else R.string.settings_off),
                        chevron = true,
                    ) { open(Destination.Notifications.route) }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_reminders)) {
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_movement_reminder), movement, {
                        movement = it
                        InactivityPrefs.setBool(context, InactivityPrefs.ENABLED, it)
                    })
                }
                if (movement) {
                    if (!wristOn) {
                        item { shape ->
                            ListRow(shape = shape, title = stringResource(R.string.settings_wrist_alerts_are_off),
                                titleColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { open(Destination.Notifications.route) })
                        }
                    }
                    item { shape ->
                        ValueRow(shape, stringResource(R.string.settings_after_sitting), "$afterSitting $minUnit") { dialog = NotifDialog.AFTER_SITTING }
                    }
                    item { shape ->
                        ValueRow(shape, stringResource(R.string.settings_repeat_every), "$repeatEvery $minUnit") { dialog = NotifDialog.REPEAT }
                    }
                    item { shape ->
                        ValueRow(shape, stringResource(R.string.l10n_automations_screen_buzz_strength_e895f99e), "$buzzLoops×") { dialog = NotifDialog.BUZZ }
                    }
                    item { shape ->
                        SwitchRow(shape, stringResource(R.string.l10n_automations_screen_only_during_active_hours_29c53fc9), activeHours, {
                            activeHours = it
                            InactivityPrefs.setBool(context, InactivityPrefs.ACTIVE_HOURS_ENABLED, it)
                        })
                    }
                    if (activeHours) {
                        item { shape ->
                            ValueRow(shape, stringResource(R.string.l10n_automations_screen_from_3f66052a), clockLabel(activeStart, is24h, locale)) { dialog = NotifDialog.FROM }
                        }
                        item { shape ->
                            ValueRow(shape, stringResource(R.string.settings_to), clockLabel(activeEnd, is24h, locale)) { dialog = NotifDialog.TO }
                        }
                    }
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_stress_check_ins), stress, {
                        stress = it
                        BiofeedbackPrefs.setCheckInEnabled(context, it)
                        // Turning the master off also disarms the auto-nudge so it cannot fire.
                        if (!it) { autoNudge = false; BiofeedbackPrefs.setAutoNudge(context, false) }
                    })
                }
                if (stress) {
                    item { shape ->
                        SwitchRow(shape, stringResource(R.string.settings_auto_nudge), autoNudge, {
                            autoNudge = it
                            BiofeedbackPrefs.setAutoNudge(context, it)
                        })
                    }
                    item { shape ->
                        SwitchRow(shape, stringResource(R.string.settings_respect_quiet_hours), quietHours, {
                            quietHours = it
                            BiofeedbackPrefs.setQuietHoursEnabled(context, it)
                        })
                    }
                    item { shape ->
                        SwitchRow(shape, stringResource(R.string.settings_use_resonance_pace), resonance, {
                            resonance = it
                            BiofeedbackPrefs.setUseResonancePace(context, it)
                        })
                    }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_alerts)) {
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_illness_signs), illness, { vm.setIllnessWatchEnabled(it) }, enabled = systemOn)
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_strain_target), strainTarget, {
                        strainTarget = it
                        NoopPrefs.setStrainTargetEnabled(context, it)
                    }, enabled = systemOn)
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.l10n_notifications_settings_screen_morning_recap_45ec05c5), morningRecap, {
                        morningRecap = it
                        NoopPrefs.setMorningReportEnabled(context, it)
                    }, enabled = systemOn)
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.l10n_notifications_settings_screen_post_workout_summary_13e488f5), workoutSummary, {
                        workoutSummary = it
                        NoopPrefs.setPostWorkoutReportEnabled(context, it)
                        // Seed the frontier to the newest workout, so turning it on does not post a summary
                        // for a session already in history.
                        if (it) vm.seedWorkoutReportFrontier()
                    }, enabled = systemOn)
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_strap_battery), battery, { vm.setBatteryAlertsEnabled(it) }, enabled = systemOn)
                }
                if (battery) {
                    item { shape ->
                        SwitchRow(shape, stringResource(R.string.settings_battery_forecast), forecast, { vm.setPredictiveBatteryAlertsEnabled(it) }, enabled = systemOn)
                    }
                }
            }
        }
    }

    val minuteSteps = (15..120 step 15).toList()
    when (dialog) {
        NotifDialog.AFTER_SITTING -> ChoiceDialog(
            title = stringResource(R.string.settings_after_sitting),
            options = minuteSteps.map { "$it $minUnit" },
            selectedIndex = minuteSteps.indexOf(afterSitting),
            onPick = { i ->
                dialog = null
                afterSitting = minuteSteps[i]
                InactivityPrefs.setInt(context, InactivityPrefs.THRESHOLD_MIN, afterSitting)
            },
            onDismiss = { dialog = null },
        )
        NotifDialog.REPEAT -> ChoiceDialog(
            title = stringResource(R.string.settings_repeat_every),
            options = minuteSteps.map { "$it $minUnit" },
            selectedIndex = minuteSteps.indexOf(repeatEvery),
            onPick = { i ->
                dialog = null
                repeatEvery = minuteSteps[i]
                InactivityPrefs.setInt(context, InactivityPrefs.RENUDGE_MIN, repeatEvery)
            },
            onDismiss = { dialog = null },
        )
        NotifDialog.BUZZ -> ChoiceDialog(
            title = stringResource(R.string.l10n_automations_screen_buzz_strength_e895f99e),
            options = (1..4).map { "$it×" },
            selectedIndex = buzzLoops - 1,
            onPick = { i ->
                dialog = null
                buzzLoops = i + 1
                InactivityPrefs.setInt(context, InactivityPrefs.BUZZ_LOOPS, buzzLoops)
            },
            onDismiss = { dialog = null },
        )
        NotifDialog.FROM -> ClockPicker(
            title = stringResource(R.string.l10n_automations_screen_from_3f66052a),
            hour = activeStart / 60,
            minute = activeStart % 60,
            is24h = is24h,
            onPick = { h, m ->
                dialog = null
                activeStart = h * 60 + m
                InactivityPrefs.setInt(context, InactivityPrefs.ACTIVE_START_MIN, activeStart)
            },
            onDismiss = { dialog = null },
        )
        NotifDialog.TO -> ClockPicker(
            title = stringResource(R.string.settings_to),
            hour = activeEnd / 60,
            minute = activeEnd % 60,
            is24h = is24h,
            onPick = { h, m ->
                dialog = null
                activeEnd = h * 60 + m
                InactivityPrefs.setInt(context, InactivityPrefs.ACTIVE_END_MIN, activeEnd)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** "09:00" / "9:00 AM" for minutes since midnight, on the reader's clock. */
internal fun clockLabel(minutes: Int, is24h: Boolean, locale: Locale): String =
    LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))
        .format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale))

/** The system page for reNOOP's notifications (the app details page before Android 8). */
private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent) }
}
