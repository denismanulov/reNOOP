package com.noop.ui.sleep

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.ble.PuffinExperiment
import com.noop.ui.AppViewModel
import com.noop.ui.ClockPrefs
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.SwitchRow
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// MARK: - Sleep Schedule (twin of iOS SleepScheduleView; replaces the old Alarms screen)
//
// The strap alarm switch and the phone backup on top, the warnings that apply, a card per schedule ("Edit"
// opens the editor with the 24-hour dial), "Add Schedule", the next wake with its countdown, then the bedtime
// reminder. Same stored settings and the same strap commands as before; every readout of the next wake comes
// from one gated resolver (SleepSchedule.nextWake) on one clock.

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SleepScheduleScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    val alarmOn by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val baseWake by vm.smartAlarmMinutes.collectAsStateWithLifecycle()
    val alarmDays by vm.smartAlarmWeekdays.collectAsStateWithLifecycle()
    val overrides by vm.smartAlarmDayOverrides.collectAsStateWithLifecycle()
    val sleepGoal by vm.sleepGoalMinutes.collectAsStateWithLifecycle()
    val backupOn by vm.phoneAlarmEnabled.collectAsStateWithLifecycle()
    val reminderOn by vm.windDownEnabled.collectAsStateWithLifecycle()
    val lead by vm.windDownLeadMinutes.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val whoop5 = live.whoop5Detected
    val experimental = remember { PuffinExperiment.from(context).isEnabled }
    // Exact alarms can be allowed in system settings while this screen is away: read again on every resume.
    var canSchedule by remember { mutableStateOf(vm.canScheduleExactAlarms()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        vm.reconcileSleepSchedule()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            canSchedule = vm.canScheduleExactAlarms()
            vm.syncSleepScheduleBackup()
        }
    }
    // One clock for the next wake, its date captions and its countdown, moving on every minute.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val inputs = SleepScheduleInputs(
        alarmOn = alarmOn,
        alarmWillArm = !(whoop5 && !experimental),
        baseWake = baseWake,
        alarmDays = alarmDays,
        overrides = overrides,
        sleepGoal = sleepGoal,
        backupArmed = backupOn && canSchedule,
    )
    var editing by remember { mutableStateOf<SleepScheduleEdit?>(null) }
    var notifDenied by remember { mutableStateOf(false) }
    var leadMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(stringResource(R.string.sleep_schedule_title), onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item {
                ListGroup {
                    item { shape ->
                        SwitchRow(
                            shape = shape,
                            title = stringResource(R.string.sleep_schedule_strap_alarm),
                            subtitle = stringResource(R.string.sleep_schedule_strap_alarm_note),
                            checked = alarmOn,
                            onCheckedChange = { on ->
                                if (on && !vm.canScheduleExactAlarms()) requestExactAlarms(context)
                                vm.setScheduleStrapAlarm(on)
                            },
                            leading = { Icon(Icons.Filled.Vibration, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape = shape,
                            title = stringResource(R.string.sleep_schedule_backup),
                            subtitle = stringResource(R.string.sleep_schedule_backup_note),
                            checked = backupOn && canSchedule,
                            onCheckedChange = { on -> if (!vm.setScheduleBackupAlarm(on)) requestExactAlarms(context) },
                            leading = { Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }
            }
            // #864: a WHOOP 5/MG keeps the time but arms nothing until Protocol probes are on. The body names
            // the switch where a reader finds it (#2464), not the "Experimental mode" the iOS copy still says.
            if (alarmOn && whoop5 && !experimental) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.sleep_schedule_not_armed_title),
                        message = stringResource(R.string.l10n_smart_alarm_screen_your_whoop_5_mg_won_t_75029bae),
                        error = true,
                    )
                }
            }
            // Armed on a 5/MG with the experimental command: wakes have been reported, never guaranteed (#864, #2464).
            if (alarmOn && whoop5 && experimental && live.bonded) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.Vibration,
                        title = stringResource(R.string.smart_alarm_5mg_armed_experimental),
                    )
                }
            }
            if ((alarmOn || backupOn) && !canSchedule) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.AlarmOff,
                        title = stringResource(R.string.sleep_schedule_exact_title),
                        message = stringResource(R.string.sleep_schedule_exact_body),
                        error = true,
                        action = stringResource(R.string.sleep_schedule_exact_action),
                        onAction = { requestExactAlarms(context) },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.sleep_schedule_full)) }
            SleepSchedule.entries(inputs).forEach { entry ->
                item(key = entry.id) {
                    ScheduleCard(entry, is24h, locale) { editing = SleepSchedule.editFor(entry, inputs) }
                }
            }
            item {
                ListGroup {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.sleep_schedule_add),
                            titleColor = MaterialTheme.colorScheme.primary,
                            leading = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { editing = SleepSchedule.newEdit(inputs) },
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.sleep_schedule_next)) }
            item { NextWakeCard(inputs, nowMs, is24h, locale) }
            item { SectionHeader(stringResource(R.string.sleep_schedule_details)) }
            item {
                ListGroup {
                    item { shape ->
                        SwitchRow(
                            shape = shape,
                            title = stringResource(R.string.sleep_schedule_reminder),
                            checked = reminderOn,
                            onCheckedChange = { on ->
                                if (on && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                                    notifDenied = true
                                } else {
                                    vm.setWindDownEnabled(on)
                                }
                            },
                            leading = { Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                    if (reminderOn) {
                        item { shape ->
                            Box {
                                ListRow(
                                    shape = shape,
                                    title = stringResource(R.string.sleep_schedule_wind_down),
                                    leading = { Icon(Icons.Filled.Bedtime, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    trailing = {
                                        Text(scheduleDuration(lead), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                                    },
                                    onClick = { leadMenu = true },
                                )
                                DropdownMenu(expanded = leadMenu, onDismissRequest = { leadMenu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                                    listOf(15, 30, 45, 60).forEach { m ->
                                        DropdownMenuItem(text = { Text(scheduleDuration(m)) }, onClick = {
                                            leadMenu = false
                                            vm.setWindDownLeadMinutes(m)
                                        })
                                    }
                                }
                            }
                        }
                    }
                    // #1706: asks the strap what alarm it has stored; the answer lands in the strap log. Not on a
                    // 5/MG, whose alarm readback is not decoded.
                    if (!whoop5) {
                        item { shape ->
                            ListRow(
                                shape = shape,
                                title = stringResource(R.string.sleep_schedule_check_strap),
                                titleColor = MaterialTheme.colorScheme.primary,
                                onClick = { vm.ble.getStrapAlarm() },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { edit ->
        SleepScheduleEditor(
            edit = edit,
            inputs = inputs,
            is24h = is24h,
            locale = locale,
            onDismiss = { editing = null },
            onSave = { stored ->
                editing = null
                vm.applySleepSchedule(stored.baseWake, stored.alarmDays, stored.overrides, stored.sleepGoal)
            },
        )
    }
    if (notifDenied) {
        AlertDialog(
            onDismissRequest = { notifDenied = false },
            title = { Text(stringResource(R.string.sleep_schedule_notif_title)) },
            text = { Text(stringResource(R.string.sleep_schedule_notif_body)) },
            confirmButton = {
                TextButton(onClick = {
                    notifDenied = false
                    openNotificationSettings(context)
                }) { Text(stringResource(R.string.sleep_schedule_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { notifDenied = false }) { Text(stringResource(R.string.sleep_schedule_not_now)) } },
        )
    }
}

/** One schedule: its days in the Sleep hue, bedtime and wake, then "Edit". */
@Composable
private fun ScheduleCard(entry: SleepScheduleEntry, is24h: Boolean, locale: Locale, onEdit: () -> Unit) {
    val days = daysText(SleepSchedule.daysSummary(entry.days), locale)
    HealthCard(verticalSpacing = 8.dp, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(days, style = MaterialTheme.typography.titleMedium, color = Health.colors.sleep)
        Row(Modifier.fillMaxWidth()) {
            TimeBlock(Icons.Filled.Bedtime, stringResource(R.string.sleep_schedule_bedtime), minuteClock(entry.bed, is24h, locale), true, Modifier.weight(1f))
            TimeBlock(
                if (entry.alarm) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
                stringResource(if (entry.alarm) R.string.sleep_schedule_wake else R.string.sleep_schedule_wake_no_alarm),
                minuteClock(entry.wake, is24h, locale),
                entry.alarm,
                Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val editLabel = stringResource(R.string.sleep_schedule_edit_named, days)
        TextButton(
            onClick = onEdit,
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.semantics { contentDescription = editLabel },
        ) {
            Text(stringResource(R.string.sleep_schedule_edit))
        }
    }
}

/** "BEDTIME / 22:30" with its glyph, an optional caption under the time. Muted for a wake with no alarm. */
@Composable
internal fun TimeBlock(
    icon: ImageVector,
    label: String,
    time: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    caption: String? = null,
    centered: Boolean = false,
) {
    val tint = if (active) Health.colors.sleep else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            time,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The next wake with the evening before it, and the countdown when it will sound. */
@Composable
private fun NextWakeCard(inputs: SleepScheduleInputs, nowMs: Long, is24h: Boolean, locale: Locale) {
    val next = SleepSchedule.nextWake(inputs, nowMs)
    HealthCard(verticalSpacing = 10.dp) {
        if (next == null) {
            Text(stringResource(R.string.sleep_schedule_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@HealthCard
        }
        Row(Modifier.fillMaxWidth()) {
            TimeBlock(
                Icons.Filled.Bedtime, stringResource(R.string.sleep_schedule_bedtime), clockLabel(next.bedTs, is24h, locale), true,
                Modifier.weight(1f), caption = dayCaption(next.bedTs, nowMs, evening = true, locale = locale),
            )
            TimeBlock(
                if (next.armed) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
                stringResource(if (next.armed) R.string.sleep_schedule_wake else R.string.sleep_schedule_wake_no_alarm),
                clockLabel(next.wakeTs, is24h, locale), next.armed,
                Modifier.weight(1f), caption = dayCaption(next.wakeTs, nowMs, evening = false, locale = locale),
            )
        }
        SleepSchedule.countdown(next, nowMs)?.let { c ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Alarm, null, tint = Health.colors.sleep, modifier = Modifier.size(20.dp))
                Text(countdownText(c), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** "Alarm in 7 hours 12 minutes". */
@Composable
internal fun countdownText(c: SleepCountdown): String = when (c) {
    SleepCountdown.UnderAMinute -> stringResource(R.string.sleep_schedule_alarm_under_minute)
    is SleepCountdown.Span -> {
        val parts = buildList {
            if (c.days > 0) add(pluralStringResource(R.plurals.alarm_countdown_days, c.days, c.days))
            if (c.hours > 0) add(pluralStringResource(R.plurals.alarm_countdown_hours, c.hours, c.hours))
            if (c.minutes > 0) add(pluralStringResource(R.plurals.alarm_countdown_minutes, c.minutes, c.minutes))
        }
        stringResource(R.string.sleep_schedule_alarm_in, parts.joinToString(" "))
    }
}

/** "Tonight" / "Today" / "Tomorrow", else the date. */
@Composable
private fun dayCaption(ts: Long, nowMs: Long, evening: Boolean, locale: Locale): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochSecond(ts).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (at.toLocalDate()) {
        today -> stringResource(if (evening && at.hour >= 17) R.string.sleep_schedule_tonight else R.string.sleep_schedule_today)
        today.plusDays(1) -> stringResource(R.string.sleep_schedule_tomorrow)
        else -> DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(at)
    }
}

/** "Every day", "Weekdays", "Weekends", "No Days" or "Mon, Wed". */
@Composable
internal fun daysText(days: SleepScheduleDays, locale: Locale): String = when (days) {
    SleepScheduleDays.EveryDay -> stringResource(R.string.sleep_schedule_every_day)
    SleepScheduleDays.Weekdays -> stringResource(R.string.sleep_schedule_weekdays)
    SleepScheduleDays.Weekends -> stringResource(R.string.sleep_schedule_weekends)
    SleepScheduleDays.NoDays -> stringResource(R.string.sleep_schedule_no_days)
    is SleepScheduleDays.Listed -> days.days.joinToString(", ") { dayOfWeek(it).getDisplayName(TextStyle.SHORT, locale) }
}

/** Calendar weekday (1 = Sun … 7 = Sat) as a java.time day. */
internal fun dayOfWeek(calendarDay: Int): DayOfWeek = DayOfWeek.of(((calendarDay + 5) % 7) + 1)

/** A minute of the day as the reader's clock shows it. */
internal fun minuteClock(minutes: Int, is24h: Boolean, locale: Locale): String {
    val m = SleepSchedule.wrap(minutes)
    return DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).format(LocalTime.of(m / 60, m % 60))
}

/** "8 h 30 min", "30 min". */
@Composable
internal fun scheduleDuration(minutes: Int): String = sleepDuration(minutes.toDouble())

/** The seven day circles, Monday first, each a toggle. */
@Composable
internal fun DayCircles(days: Set<Int>, locale: Locale, onToggle: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SleepSchedule.weekOrder.forEach { dow ->
            val on = dow in days
            val day = dayOfWeek(dow)
            val name = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            val letter = day.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)
            val shape = CircleShape
            Box(
                Modifier.size(44.dp).clip(shape)
                    .background(if (on) Health.colors.sleep else Color.Transparent)
                    .border(1.dp, if (on) Health.colors.sleep else MaterialTheme.colorScheme.outlineVariant, shape)
                    .clickable { onToggle(dow) }
                    .semantics { contentDescription = name; selected = on; role = Role.Checkbox },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The system page where exact alarms are allowed (API 31+): the phone backup needs them. */
private fun requestExactAlarms(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** The app's notification settings: after a denial, the only way back. */
private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
