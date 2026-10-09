package com.noop.ui

import com.noop.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.notif.CallAlertController
import com.noop.notif.CallAlertSource
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Switch
import com.noop.ui.m3.RowIcon
import com.noop.ui.m3.SwitchRow
import com.noop.ui.settings.SettingsPage
import com.noop.ui.settings.ValueRow
import com.noop.ui.settings.clockLabel
import com.noop.ui.sleep.ClockPicker
import java.util.Calendar

// MARK: - NotificationsSettingsScreen (Settings > Notifications > Wrist alerts)
//
// Which apps tap the wrist and how (a buzz pattern per app), calls, the phone's timers and alarms, and
// when not to buzz. Android restricts package visibility (API 30+) and has no "notification-capable app"
// query, so the page lists a curated catalogue of common apps plus an "All other apps" switch. The
// preferences persist in SharedPreferences and are read by the notification listener; delivery needs
// Notification Access, which the first group opens. Drawn like every other Settings page (a large
// collapsing bar, segmented list groups); an app or the calls row is a two-target row, as Pixel Settings
// draws one: its switch turns it on or off, and the row opens its buzz pattern while it is on.

// MARK: - Domain model (mirrors NotificationSettingsStore.swift)

/** Haptic pattern fired on the strap; only the repeat count varies. */
internal enum class BuzzPattern(@StringRes val labelRes: Int, val loops: Int) {
    Single(R.string.settings_wrist_pattern_single, 1),
    Double(R.string.settings_wrist_pattern_double, 2),
    Triple(R.string.settings_wrist_pattern_triple, 3),
    Long(R.string.settings_wrist_pattern_long, 5),
}

/** Grouping for the settings screen, with its header icon + default pattern. */
internal enum class NotifCategory(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val defaultPattern: BuzzPattern,
) {
    Email(R.string.settings_wrist_cat_email, Icons.Filled.Email, BuzzPattern.Double),
    Messaging(R.string.settings_wrist_cat_messaging, Icons.AutoMirrored.Filled.Chat, BuzzPattern.Single),
    Meetings(R.string.settings_wrist_cat_meetings, Icons.Filled.Videocam, BuzzPattern.Triple),
    Calendar(R.string.settings_wrist_cat_calendar, Icons.Filled.CalendarMonth, BuzzPattern.Double),
}

/** A notification-capable app NOOP can mirror to the wrist. `id` is the persistence key. */
internal data class NotifApp(
    val id: String,
    val name: String,
    val category: NotifCategory,
    val glyph: ImageVector,
)

/**
 * Curated catalog of common Android notification apps, grouped to match the Mac screen.
 * Unlike macOS we cannot enumerate which are actually installed (restricted package
 * visibility), so we present the full set as configurable examples.
 */
private val notifCatalog: List<NotifApp> = listOf(
    NotifApp("com.google.android.gm", "Gmail", NotifCategory.Email, Icons.Filled.Email),
    NotifApp("com.microsoft.office.outlook", "Outlook", NotifCategory.Email, Icons.Filled.Email),
    NotifApp("com.whatsapp", "WhatsApp", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("com.google.android.apps.messaging", "Messages", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("com.Slack", "Slack", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("org.telegram.messenger", "Telegram", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    // Teams' ringing-call notifications are handled by the Calls card below (VoIP path). This
    // per-app row covers everything else Teams sends to the shade (chats, @-mentions, channel
    // posts), which read as messages, so it lives under Messaging with the chat glyph.
    NotifApp("com.microsoft.teams", "Microsoft Teams", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("us.zoom.videomeetings", "Zoom", NotifCategory.Meetings, Icons.Filled.Videocam),
    NotifApp("com.google.android.calendar", "Calendar", NotifCategory.Calendar, Icons.Filled.CalendarMonth),
)

private fun appsIn(category: NotifCategory): List<NotifApp> =
    notifCatalog.filter { it.category == category }

private val activeCategories: List<NotifCategory> =
    NotifCategory.entries.filter { appsIn(it).isNotEmpty() }

// MARK: - SharedPreferences store (mirrors the UserDefaults-backed Swift store)

/**
 * Plain-prefs store for wrist-alert settings (the AI key uses encrypted prefs; these are
 * non-secret toggles). Per-app prefs are flattened to `app.<id>.enabled` / `app.<id>.pattern`
 * keys so no JSON dependency is needed.
 */
internal object NotifPrefs {
    private const val FILE = "noop_notif_prefs"
    const val MASTER = "notif.masterEnabled"
    /** Catch-all: buzz for any app NOT in the curated catalog (Android can't enumerate installed
     *  apps, so this is how a user covers BeReal/etc. that aren't listed). Opt-in, default OFF. (#168) */
    const val ALL_OTHER = "notif.allOtherApps"
    const val WORN = "notif.onlyWhenWorn"
    const val QUIET = "notif.quietHoursEnabled"
    const val QUIET_START = "notif.quietStartMinutes"
    const val QUIET_END = "notif.quietEndMinutes"
    const val CALLS_MASTER = "notif.calls.masterEnabled"
    const val CALLS_PHONE = "notif.calls.phoneEnabled"
    const val CALLS_VOIP = "notif.calls.voipEnabled"
    const val CALLS_PATTERN = "notif.calls.pattern"
    /** Buzz the strap when the phone's native Clock fires a timer/alarm (CATEGORY_ALARM). Android-only
     *  (iOS can't observe another app's notifications). Default OFF. */
    const val ALARM_TIMER = "notif.alarmTimer"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String, default: Boolean) =
        prefs(ctx).getBoolean(key, default)

    fun setBool(ctx: Context, key: String, value: Boolean) =
        prefs(ctx).edit().putBoolean(key, value).apply()

    fun getInt(ctx: Context, key: String, default: Int) =
        prefs(ctx).getInt(key, default)

    fun setInt(ctx: Context, key: String, value: Int) =
        prefs(ctx).edit().putInt(key, value).apply()

    fun appEnabled(ctx: Context, id: String): Boolean =
        prefs(ctx).getBoolean("app.$id.enabled", false) // opt-in, default OFF

    fun setAppEnabled(ctx: Context, id: String, value: Boolean) =
        prefs(ctx).edit().putBoolean("app.$id.enabled", value).apply()

    fun appPattern(ctx: Context, app: NotifApp): BuzzPattern {
        val name = prefs(ctx).getString("app.${app.id}.pattern", null)
        return BuzzPattern.entries.firstOrNull { it.name == name } ?: app.category.defaultPattern
    }

    fun setAppPattern(ctx: Context, id: String, pattern: BuzzPattern) =
        prefs(ctx).edit().putString("app.$id.pattern", pattern.name).apply()

    /** Buzz loop-count for [pkg] (for the notification listener; no NotifApp needed). Defaults to
     *  Double if no per-app pattern was chosen. */
    fun appLoops(ctx: Context, pkg: String): Int {
        val name = prefs(ctx).getString("app.$pkg.pattern", null)
        return BuzzPattern.entries.firstOrNull { it.name == name }?.loops ?: BuzzPattern.Double.loops
    }

    fun callPattern(ctx: Context): BuzzPattern {
        val name = prefs(ctx).getString(CALLS_PATTERN, null)
        return BuzzPattern.entries.firstOrNull { it.name == name } ?: BuzzPattern.Triple
    }

    fun setCallPattern(ctx: Context, pattern: BuzzPattern) =
        prefs(ctx).edit().putString(CALLS_PATTERN, pattern.name).apply()

    fun callLoops(ctx: Context): Int = callPattern(ctx).loops

    fun inQuietHours(ctx: Context): Boolean {
        if (!getBool(ctx, QUIET, false)) return false
        val start = getInt(ctx, QUIET_START, 22 * 60)
        val end = getInt(ctx, QUIET_END, 7 * 60)
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Quiet window may wrap midnight (e.g. 22:00 -> 07:00).
        return if (start <= end) now in start until end else (now >= start || now < end)
    }
}

// MARK: - Screen

private enum class QuietDialog { FROM, TO }

@Composable
fun NotificationsSettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24h = ClockPrefs.uses24Hour(context)
    val live by vm.live.collectAsStateWithLifecycle()

    // Seeded from prefs once and written through on change.
    var masterEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.MASTER, false)) }
    var onlyWhenWorn by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.WORN, true)) }
    var allOtherApps by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.ALL_OTHER, false)) }
    var quietHoursEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.QUIET, false)) }
    var quietStartMinutes by remember { mutableStateOf(NotifPrefs.getInt(context, NotifPrefs.QUIET_START, 22 * 60)) }
    var quietEndMinutes by remember { mutableStateOf(NotifPrefs.getInt(context, NotifPrefs.QUIET_END, 7 * 60)) }
    var callsEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.CALLS_MASTER, false)) }
    var phoneCallsEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.CALLS_PHONE, false)) }
    var voipCallsEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.CALLS_VOIP, false)) }
    var alarmTimerEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.ALARM_TIMER, false)) }
    var callsPattern by remember { mutableStateOf(NotifPrefs.callPattern(context)) }
    var phonePermissionDenied by remember { mutableStateOf(false) }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        phoneCallsEnabled = granted
        phonePermissionDenied = !granted
        NotifPrefs.setBool(context, NotifPrefs.CALLS_PHONE, granted)
    }
    var dialog by remember { mutableStateOf<QuietDialog?>(null) }

    // Per-app state, seeded from prefs so the page is reactive within the session.
    val enabledState: SnapshotStateMap<String, Boolean> = remember {
        mutableStateMapOf<String, Boolean>().apply {
            notifCatalog.forEach { put(it.id, NotifPrefs.appEnabled(context, it.id)) }
        }
    }
    val patternState: SnapshotStateMap<String, BuzzPattern> = remember {
        mutableStateMapOf<String, BuzzPattern>().apply {
            notifCatalog.forEach { put(it.id, NotifPrefs.appPattern(context, it)) }
        }
    }

    SettingsPage(title = stringResource(R.string.l10n_notifications_settings_screen_wrist_alerts_75581d51), onBack = onBack) {
        // The master switch, the system page that grants Notification Access, and a test buzz.
        item {
            ListGroup(
                footer = stringResource(R.string.l10n_notifications_settings_screen_wrist_delivery_needs_notification_access_so_2a14e784),
            ) {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_notifications_settings_screen_enable_wrist_alerts_462b9e0f),
                        checked = masterEnabled,
                        onCheckedChange = {
                            masterEnabled = it
                            NotifPrefs.setBool(context, NotifPrefs.MASTER, it)
                        },
                    )
                }
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_notifications_settings_screen_open_notification_access_658fd30f),
                        titleColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_notifications_settings_screen_test_buzz_deeab5ae),
                        titleColor = MaterialTheme.colorScheme.primary,
                        enabled = live.bonded,
                        onClick = { vm.buzz(loops = 2) },
                    )
                }
            }
        }

        // Calls (the phone and, best effort, the calling apps), and the phone clock.
        item {
            ListGroup(header = stringResource(R.string.l10n_notifications_settings_screen_calls_0a19b7e2)) {
                item { shape ->
                    PatternSwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_notifications_settings_screen_buzz_on_incoming_calls_625804a1),
                        switchLabel = stringResource(R.string.l10n_notifications_settings_screen_buzz_on_incoming_calls_625804a1),
                        checked = callsEnabled,
                        enabled = masterEnabled,
                        pattern = callsPattern,
                        canTest = masterEnabled && live.bonded,
                        onToggle = {
                            callsEnabled = it
                            NotifPrefs.setBool(context, NotifPrefs.CALLS_MASTER, it)
                            if (!it) CallAlertController.stopAll()
                        },
                        onPattern = {
                            callsPattern = it
                            NotifPrefs.setCallPattern(context, it)
                        },
                        onTest = { vm.buzz(loops = callsPattern.loops) },
                    )
                }
                if (callsEnabled) {
                    item { shape ->
                        SwitchRow(
                            shape = shape,
                            title = stringResource(R.string.l10n_notifications_settings_screen_phone_calls_b79420d9),
                            subtitle = stringResource(
                                if (phonePermissionDenied) {
                                    R.string.l10n_notifications_settings_screen_phone_permission_was_denied_so_phone_db0ebdd8
                                } else {
                                    R.string.settings_wrist_phone_calls_desc
                                },
                            ),
                            checked = phoneCallsEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = { value ->
                                if (!value) {
                                    phoneCallsEnabled = false
                                    phonePermissionDenied = false
                                    NotifPrefs.setBool(context, NotifPrefs.CALLS_PHONE, false)
                                    CallAlertController.stopSource(CallAlertSource.PHONE)
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.READ_PHONE_STATE,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        phoneCallsEnabled = true
                                        phonePermissionDenied = false
                                        NotifPrefs.setBool(context, NotifPrefs.CALLS_PHONE, true)
                                    } else {
                                        phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                                    }
                                }
                            },
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape = shape,
                            title = stringResource(R.string.l10n_notifications_settings_screen_voip_calls_96c5a102),
                            checked = voipCallsEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = {
                                voipCallsEnabled = it
                                NotifPrefs.setBool(context, NotifPrefs.CALLS_VOIP, it)
                                if (!it) CallAlertController.stopSource(CallAlertSource.VOIP)
                            },
                        )
                    }
                }
                // #1115: the phone clock finishing a timer or ringing an alarm (a CATEGORY_ALARM
                // notification from any clock app). Android only.
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.notif_timer_alarm_title),
                        subtitle = stringResource(R.string.settings_wrist_timer_alarm_desc),
                        checked = alarmTimerEnabled,
                        enabled = masterEnabled,
                        onCheckedChange = {
                            alarmTimerEnabled = it
                            NotifPrefs.setBool(context, NotifPrefs.ALARM_TIMER, it)
                        },
                    )
                }
            }
        }

        // The apps, by kind.
        activeCategories.forEach { category ->
            item {
                ListGroup(header = stringResource(category.titleRes)) {
                    appsIn(category).forEach { app ->
                        item { shape ->
                            val pattern = patternState[app.id] ?: app.category.defaultPattern
                            PatternSwitchRow(
                                shape = shape,
                                title = app.name,
                                switchLabel = stringResource(R.string.l10n_notifications_settings_screen_app_name_wrist_alerts_dd3540fa, app.name),
                                leading = { RowIcon(app.glyph) },
                                checked = enabledState[app.id] ?: false,
                                enabled = masterEnabled,
                                pattern = pattern,
                                canTest = masterEnabled && live.bonded,
                                onToggle = {
                                    enabledState[app.id] = it
                                    NotifPrefs.setAppEnabled(context, app.id, it)
                                },
                                onPattern = {
                                    patternState[app.id] = it
                                    NotifPrefs.setAppPattern(context, app.id, it)
                                },
                                onTest = { vm.buzz(loops = pattern.loops) },
                            )
                        }
                    }
                }
            }
        }

        // When to buzz and when not to.
        item {
            ListGroup(header = stringResource(R.string.l10n_notifications_settings_screen_behaviour_171ca038)) {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_notifications_settings_screen_only_buzz_when_worn_6211cee3),
                        checked = onlyWhenWorn,
                        onCheckedChange = {
                            onlyWhenWorn = it
                            NotifPrefs.setBool(context, NotifPrefs.WORN, it)
                        },
                    )
                }
                // #168: Android cannot list every installed app, so this is how the rest are covered.
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_notifications_settings_screen_all_other_apps_51a8af2c),
                        subtitle = stringResource(R.string.settings_wrist_other_apps_desc),
                        checked = allOtherApps,
                        onCheckedChange = {
                            allOtherApps = it
                            NotifPrefs.setBool(context, NotifPrefs.ALL_OTHER, it)
                        },
                    )
                }
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_notifications_settings_screen_quiet_hours_706b24d0),
                        checked = quietHoursEnabled,
                        onCheckedChange = {
                            quietHoursEnabled = it
                            NotifPrefs.setBool(context, NotifPrefs.QUIET, it)
                        },
                    )
                }
                if (quietHoursEnabled) {
                    item { shape ->
                        ValueRow(
                            shape,
                            stringResource(R.string.l10n_notifications_settings_screen_from_3f66052a),
                            clockLabel(quietStartMinutes, is24h, locale),
                        ) { dialog = QuietDialog.FROM }
                    }
                    item { shape ->
                        ValueRow(shape, stringResource(R.string.settings_to), clockLabel(quietEndMinutes, is24h, locale)) {
                            dialog = QuietDialog.TO
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        QuietDialog.FROM -> ClockPicker(
            title = stringResource(R.string.l10n_notifications_settings_screen_from_3f66052a),
            hour = quietStartMinutes / 60,
            minute = quietStartMinutes % 60,
            is24h = is24h,
            onPick = { h, m ->
                dialog = null
                quietStartMinutes = h * 60 + m
                NotifPrefs.setInt(context, NotifPrefs.QUIET_START, quietStartMinutes)
            },
            onDismiss = { dialog = null },
        )
        QuietDialog.TO -> ClockPicker(
            title = stringResource(R.string.settings_to),
            hour = quietEndMinutes / 60,
            minute = quietEndMinutes % 60,
            is24h = is24h,
            onPick = { h, m ->
                dialog = null
                quietEndMinutes = h * 60 + m
                NotifPrefs.setInt(context, NotifPrefs.QUIET_END, quietEndMinutes)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/**
 * A two-target row: the switch turns the alert on or off; the row itself turns it on while it is off, and
 * while it is on opens the buzz pattern (the four patterns, the current one ticked, then a test buzz). The
 * supporting line names the pattern in words, so TalkBack reads what the row opens.
 */
@Composable
private fun PatternSwitchRow(
    shape: Shape,
    title: String,
    switchLabel: String,
    checked: Boolean,
    enabled: Boolean,
    pattern: BuzzPattern,
    canTest: Boolean,
    onToggle: (Boolean) -> Unit,
    onPattern: (BuzzPattern) -> Unit,
    onTest: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            shape = shape,
            title = title,
            subtitle = if (checked) {
                stringResource(R.string.settings_wrist_pattern_value, stringResource(pattern.labelRes))
            } else null,
            leading = leading,
            enabled = enabled,
            onClick = { if (checked) menu = true else onToggle(true) },
            trailing = {
                M3Switch(
                    checked = checked,
                    onCheckedChange = onToggle,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            BuzzPattern.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(stringResource(p.labelRes)) },
                    leadingIcon = {
                        if (p == pattern) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                    },
                    onClick = { menu = false; onPattern(p) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.l10n_notifications_settings_screen_test_buzz_deeab5ae)) },
                leadingIcon = { Spacer(Modifier.size(24.dp)) },
                enabled = canTest,
                onClick = { menu = false; onTest() },
            )
        }
    }
}

// MARK: - Time chip (TimePickerDialog → HH:mm), the legacy-styled chip the Test Centre still uses.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeChip(
    minutes: Int,
    accessibilityLabel: String,
    onPicked: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val hour = minutes / 60
    val minute = minutes % 60
    Text(
        text = uiString(R.string.l10n_notifications_settings_screen_02d_02d_ce23a78c, hour, minute),
        style = NoopType.number(15f),
        color = Palette.accent,
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, shape)
            .clickable { showPicker = true }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = accessibilityLabel },
    )

    if (showPicker) {
        // Material3 1.2.x has TimePicker + rememberTimePickerState but not a packaged
        // TimePickerDialog, so we wrap the picker in a plain Dialog ourselves.
        val state = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showPicker = false }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.surfaceOverlay)
                    .border(1.dp, Palette.hairline, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(accessibilityLabel, style = NoopType.headline, color = Palette.textPrimary)
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Palette.surfaceInset,
                        clockDialSelectedContentColor = Palette.surfaceBase,
                        clockDialUnselectedContentColor = Palette.textPrimary,
                        selectorColor = Palette.accent,
                        periodSelectorBorderColor = Palette.hairline,
                        timeSelectorSelectedContainerColor = Palette.accentMuted,
                        timeSelectorUnselectedContainerColor = Palette.surfaceInset,
                        timeSelectorSelectedContentColor = Palette.accent,
                        timeSelectorUnselectedContentColor = Palette.textPrimary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Text(
                        uiString(R.string.l10n_notifications_settings_screen_cancel_77dfd213),
                        style = NoopType.body,
                        color = Palette.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showPicker = false }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        uiString(R.string.l10n_notifications_settings_screen_set_448ab73b),
                        style = NoopType.body,
                        color = Palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                onPicked(state.hour * 60 + state.minute)
                                showPicker = false
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
