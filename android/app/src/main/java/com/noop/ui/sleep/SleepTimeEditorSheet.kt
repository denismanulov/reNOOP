package com.noop.ui.sleep

import com.noop.ui.m3.SheetBackdropEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.SleepEditGuard
import com.noop.ui.ClockPrefs
import com.noop.ui.SleepTimeEditDraft
import com.noop.ui.sleepEndpointTs
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// MARK: - The sleep time editor (twin of iOS SleepTimeEditor), as a Material bottom sheet
//
// Two date-and-time rows ("Asleep" / "Woke", or "Nap started" / "Nap ended"), neither in the future. Both
// rows change a draft; only Save commits the pair, so a new bedtime is never written against the old wake
// (#515). The bedtime keeps the cross-midnight correction of [SleepTimeEditDraft]. A window that no longer
// touches the night's recorded data says so under the rows and asks before it moves the night (#940). The
// delete row asks first too.

/** What the editor is editing: a night or nap on record, or a nap being added. */
internal class SleepEditorRequest(
    val nap: Boolean,
    /** The night's recorded coverage, for the "no recorded data" guard; null for a new nap. */
    val coverage: Pair<Long, Long>?,
    val bedTs: Long,
    val wakeTs: Long,
    /** Shown (and Delete offered) only for a sleep on record. */
    val deletable: Boolean,
    /** A detected sleep: deleting it also keeps it from being detected again. */
    val suppressesReDetection: Boolean,
)

private enum class Picking { BED_DATE, BED_TIME, WAKE_DATE, WAKE_TIME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimeEditorSheet(
    request: SleepEditorRequest,
    adding: Boolean,
    onSave: (Long, Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    val locale = context.resources.configuration.locales[0]
    val zone = ZoneId.systemDefault()
    val nowTs = System.currentTimeMillis() / 1000L
    var draft by remember {
        mutableStateOf(SleepTimeEditDraft(minOf(request.bedTs, nowTs), request.wakeTs))
    }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var confirmMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val validated = draft.validatedWindow(nowTs)
    val disjoint = validated != null && request.coverage != null &&
        SleepEditGuard.isDisjoint(validated.first, validated.second, request.coverage.first, request.coverage.second)

    val title = stringResource(
        when {
            adding -> R.string.sleep_menu_add_nap
            else -> R.string.sleep_menu_edit_times
        },
    )
    val bedLabel = stringResource(if (request.nap) R.string.sleep_edit_nap_started else R.string.sleep_edit_asleep)
    val wakeLabel = stringResource(if (request.nap) R.string.sleep_edit_nap_ended else R.string.sleep_edit_woke)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBackdropEffect()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButton(
                    enabled = validated != null,
                    onClick = {
                        val w = validated ?: return@TextButton
                        if (disjoint) confirmMove = true else onSave(w.first, w.second)
                    },
                ) { Text(stringResource(R.string.sleep_edit_save)) }
            }
            ListGroup(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                footer = if (disjoint) null else stringResource(if (request.nap) R.string.sleep_edit_nap_blurb else R.string.sleep_edit_blurb),
            ) {
                item { shape ->
                    DateTimeRow(shape, bedLabel, draft.startTs, is24h, locale, zone,
                        onDate = { picking = Picking.BED_DATE }, onTime = { picking = Picking.BED_TIME })
                }
                item { shape ->
                    DateTimeRow(shape, wakeLabel, draft.endTs, is24h, locale, zone,
                        onDate = { picking = Picking.WAKE_DATE }, onTime = { picking = Picking.WAKE_TIME })
                }
            }
            if (disjoint) {
                Row(
                    Modifier.padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.sleep_edit_no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (request.deletable) {
                ListGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.sleep_edit_delete),
                            titleColor = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    when (picking) {
        Picking.BED_DATE, Picking.WAKE_DATE -> {
            val bed = picking == Picking.BED_DATE
            val ts = if (bed) draft.startTs else draft.endTs
            DayPicker(
                selected = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate(),
                onPick = { day ->
                    // #2470: the picked date with the endpoint's own time, through the one tested helper.
                    val time = Instant.ofEpochSecond(ts).atZone(zone).toLocalTime()
                    val newTs = sleepEndpointTs(ts, day.year, day.monthValue - 1, day.dayOfMonth, time.hour, time.minute)
                    draft = if (bed) draft.withBedCandidate(newTs, System.currentTimeMillis() / 1000L)
                    else draft.withWakeCandidate(newTs)
                    picking = null
                },
                onDismiss = { picking = null },
            )
        }
        Picking.BED_TIME, Picking.WAKE_TIME -> {
            val bed = picking == Picking.BED_TIME
            val ts = if (bed) draft.startTs else draft.endTs
            val at = Instant.ofEpochSecond(ts).atZone(zone)
            ClockPicker(
                title = if (bed) bedLabel else wakeLabel,
                hour = at.hour,
                minute = at.minute,
                is24h = is24h,
                onPick = { h, m ->
                    val newTs = sleepEndpointTs(ts, at.year, at.monthValue - 1, at.dayOfMonth, h, m)
                    draft = if (bed) draft.withBedCandidate(newTs, System.currentTimeMillis() / 1000L)
                    else draft.withWakeCandidate(newTs)
                    picking = null
                },
                onDismiss = { picking = null },
            )
        }
        null -> Unit
    }

    if (confirmMove) {
        AlertDialog(
            onDismissRequest = { confirmMove = false },
            title = { Text(stringResource(R.string.sleep_edit_move_title)) },
            text = { Text(stringResource(R.string.sleep_edit_move_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmMove = false
                    val w = draft.validatedWindow(System.currentTimeMillis() / 1000L) ?: return@TextButton
                    onSave(w.first, w.second)
                }) { Text(stringResource(R.string.sleep_edit_move_anyway)) }
            },
            dismissButton = { TextButton(onClick = { confirmMove = false }) { Text(stringResource(R.string.summary_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sleep_edit_delete_title)) },
            text = {
                Text(stringResource(if (request.suppressesReDetection) R.string.sleep_delete_recorded_body else R.string.sleep_delete_user_edited_body))
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.sleep_edit_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.summary_cancel)) } },
        )
    }
}

/** "Asleep   Tue 29 Sep   23:10": the label, then a date chip and a time chip. */
@Composable
private fun DateTimeRow(
    shape: androidx.compose.ui.graphics.Shape,
    label: String,
    ts: Long,
    is24h: Boolean,
    locale: java.util.Locale,
    zone: ZoneId,
    onDate: () -> Unit,
    onTime: () -> Unit,
) {
    val at = Instant.ofEpochSecond(ts).atZone(zone)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(at.toLocalDate())
    ListRow(
        shape = shape,
        title = label,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onDate, label = { Text(date) })
                AssistChip(onClick = onTime, label = { Text(clockLabel(ts, is24h, locale)) })
            }
        },
    )
}

/** A Material date picker up to today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPicker(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    earliest: LocalDate? = null,
    latest: LocalDate = LocalDate.now(),
    allowed: ((LocalDate) -> Boolean)? = null,
) {
    fun utc(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val last = utc(latest)
    val first = earliest?.let { utc(it) } ?: Long.MIN_VALUE
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utc(selected),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                if (utcTimeMillis !in first..last) return false
                val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return allowed?.invoke(day) ?: true
            }
            override fun isSelectableYear(year: Int): Boolean = year <= latest.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis == null) onDismiss() else onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text(stringResource(R.string.summary_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

/** A Material time picker in a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClockPicker(
    title: String,
    hour: Int,
    minute: Int,
    is24h: Boolean,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute) }) { Text(stringResource(R.string.summary_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}
