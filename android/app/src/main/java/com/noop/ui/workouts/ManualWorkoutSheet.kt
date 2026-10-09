package com.noop.ui.workouts

import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.WorkoutRow
import com.noop.ui.ClockPrefs
import com.noop.ui.RecentSportsPrefs
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import com.noop.ui.WorkoutEditing
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.sleep.ClockPicker
import com.noop.ui.sleep.DayPicker
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// MARK: - Add / edit a workout (twin of iOS ManualWorkoutSheet, as a Material full-screen dialog)
//
// ✕ and Save in the top bar over three groups: the type (opens the activity list in place), Starts / Ends /
// Duration, then Distance / Active Calories / Avg. Heart Rate ("Optional"). Validated by the same honest-row
// rules the engine uses, with one line saying what is wrong; numbers read in any locale (CR-11). An edit keeps
// what the form does not show (strain, zones, route, steps) via `preservingCaptured`, and closing with
// changes asks before throwing them away.

/** What the editor opens on: a row to edit (or duplicate), or nothing for a fresh add. */
internal class WorkoutEditTarget(val row: WorkoutRow?, val isCopy: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualWorkoutSheet(
    target: WorkoutEditTarget,
    onDismiss: () -> Unit,
    onSave: (row: WorkoutRow, replacing: WorkoutRow?) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val imperial = UnitPrefs.distanceSystem(context) == UnitSystem.IMPERIAL
    val editing = target.row
    val nowAtOpen = remember { System.currentTimeMillis() / 1000 }

    // The editable, locale-stable form of the sport ("Activity" for a detected bout), never the translation.
    var sport by rememberSaveable { mutableStateOf(editing?.let { WorkoutEditing.displaySport(it.sport) } ?: "") }
    // A fresh add opens on a valid 45-minute session ending now.
    var start by rememberSaveable { mutableLongStateOf(editing?.startTs ?: (nowAtOpen - 45 * 60)) }
    var end by rememberSaveable { mutableLongStateOf(editing?.endTs ?: nowAtOpen) }
    var avgHrText by rememberSaveable { mutableStateOf(editing?.avgHr?.toString() ?: "") }
    var kcalText by rememberSaveable { mutableStateOf(editing?.energyKcal?.let { WorkoutNumbers.entry(it, 0, locale) } ?: "") }
    var distanceText by rememberSaveable {
        mutableStateOf(
            editing?.distanceM?.let { m ->
                val v = m / 1000.0 * (if (imperial) WorkoutFormat.MILES_PER_KM else 1.0)
                WorkoutNumbers.entry(v, 2, locale)
            } ?: "",
        )
    }
    val initial = remember { listOf(sport, start.toString(), end.toString(), avgHrText, kcalText, distanceText) }
    val hasChanges = listOf(sport, start.toString(), end.toString(), avgHrText, kcalText, distanceText) != initial

    var pickingSport by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf<SpanEnd?>(null) }
    var pickingTime by remember { mutableStateOf<Pair<SpanEnd, LocalDate>?>(null) }

    val nowSeconds = System.currentTimeMillis() / 1000
    val form = ManualWorkoutForm(sport, start, end, avgHrText, kcalText, distanceText, imperial, nowSeconds)
    val built = form.row?.let { WorkoutEditing.preservingCaptured(it, editing) }

    fun close() {
        if (hasChanges) askDiscard = true else onDismiss()
    }

    fun moveStart(picked: Long) {
        // Moving the start keeps the length and carries the end with it, clamped so the end stays in the past.
        val span = end - start
        val newStart = minOf(picked, System.currentTimeMillis() / 1000 - span)
        end = WorkoutEditing.endAfterStartMove(start, end, newStart)
        start = newStart
    }

    FullScreenDialog(onDismiss = ::close) {
        if (pickingSport) {
            BackHandler { pickingSport = false }
            SportPickerContent(
                title = stringResource(R.string.manual_type),
                selected = sport.ifBlank { null },
                allowFreeText = true,
                closeIsBack = true,
                onClose = { pickingSport = false },
                onPick = { sport = it; pickingSport = false },
            )
            return@FullScreenDialog
        }
        BackHandler(onBack = ::close)
        TopAppBar(
            title = { Text(stringResource(if (editing == null || target.isCopy) R.string.manual_add_title else R.string.manual_edit_title)) },
            navigationIcon = {
                IconButton(onClick = ::close) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.manual_close))
                }
            },
            actions = {
                TextButton(
                    enabled = built != null,
                    onClick = {
                        val row = built ?: return@TextButton
                        // A confirmed save is a real selection: fold the sport into the recents (#297).
                        RecentSportsPrefs.record(context, row.sport)
                        onSave(row, WorkoutEditing.replacingRowFor(editing, target.isCopy))
                    },
                ) { Text(stringResource(R.string.manual_save)) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = M3Dimens.screenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.manual_type),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (sport.isBlank()) stringResource(R.string.manual_choose) else sportLabel(sport),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ChevronRight()
                            }
                        },
                        onClick = { pickingSport = true },
                    )
                }
            }
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.manual_starts),
                        trailing = { DateTimeValue(start) },
                        onClick = { pickingDate = SpanEnd.Start },
                    )
                }
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.manual_ends),
                        trailing = { DateTimeValue(end) },
                        onClick = { pickingDate = SpanEnd.End },
                    )
                }
                item { shape ->
                    val minutes = WorkoutEditing.spanDurationMin(start, end).coerceIn(1, 24 * 60)
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.manual_duration),
                        trailing = {
                            Text(
                                WorkoutFormat.durationWords(
                                    minutes * 60.0, stringResource(R.string.metric_unit_hr), stringResource(R.string.metric_unit_min),
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            ListGroup(footer = null) {
                item { shape ->
                    NumberRow(
                        shape, stringResource(R.string.workouts_distance), distanceText, { distanceText = it },
                        stringResource(if (imperial) R.string.workouts_unit_mi else R.string.workouts_unit_km), decimal = true,
                    )
                }
                item { shape ->
                    NumberRow(
                        shape, stringResource(R.string.workouts_active_calories), kcalText, { kcalText = it },
                        stringResource(R.string.metric_unit_kcal), decimal = true,
                    )
                }
                item { shape ->
                    NumberRow(
                        shape, stringResource(R.string.workouts_avg_hr), avgHrText, { avgHrText = it },
                        stringResource(R.string.metric_unit_bpm), decimal = false,
                    )
                }
            }
            val problem = form.problem
            if (sport.isNotBlank() && problem != null) {
                Text(
                    problemText(problem, imperial),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Health.colors.warning,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (built != null && WorkoutEditing.avgHrEdited(built, editing)) {
                Text(
                    stringResource(R.string.manual_hr_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    pickingDate?.let { which ->
        val current = if (which == SpanEnd.Start) start else end
        DayPicker(
            selected = Instant.ofEpochSecond(current).atZone(ZoneId.systemDefault()).toLocalDate(),
            onPick = { day -> pickingDate = null; pickingTime = which to day },
            onDismiss = { pickingDate = null },
        )
    }
    pickingTime?.let { (which, day) ->
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochSecond(if (which == SpanEnd.Start) start else end).atZone(zone)
        ClockPicker(
            title = stringResource(if (which == SpanEnd.Start) R.string.manual_starts else R.string.manual_ends),
            hour = current.hour,
            minute = current.minute,
            is24h = ClockPrefs.uses24Hour(context),
            onPick = { h, m ->
                pickingTime = null
                val picked = LocalDateTime.of(day.year, day.month, day.dayOfMonth, h, m).atZone(zone).toEpochSecond()
                    .coerceAtMost(System.currentTimeMillis() / 1000)
                if (which == SpanEnd.Start) moveStart(picked) else end = picked
            },
            onDismiss = { pickingTime = null },
        )
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.manual_discard_title)) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; onDismiss() }) {
                    Text(stringResource(R.string.manual_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.manual_keep)) } },
        )
    }
}

private enum class SpanEnd { Start, End }

/** "1 Oct 2026, 07:10" in the reader's format. */
@Composable
private fun DateTimeValue(seconds: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val text = remember(seconds, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One figure typed in by hand: the label, a trailing number field with an "Optional" hint, its unit. */
@Composable
private fun NumberRow(
    shape: androidx.compose.ui.graphics.Shape,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    unit: String,
    decimal: Boolean,
) {
    // The whole row focuses its field, as a tap on a Settings text row does.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    ListRow(
        shape = shape,
        title = label,
        onClick = { focus.requestFocus(); keyboard?.show() },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
                    // Wide enough for the placeholder and a few digits at the reader's font size.
                    modifier = Modifier
                        .width(120.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f))
                        .focusRequester(focus)
                        .semantics { contentDescription = label },
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                stringResource(R.string.manual_optional),
                                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        inner()
                    },
                )
                Text(
                    " $unit",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun problemText(problem: ManualProblem, imperial: Boolean): String = stringResource(
    when (problem) {
        ManualProblem.SPORT -> R.string.manual_err_sport
        ManualProblem.START_FUTURE -> R.string.manual_err_start_future
        ManualProblem.END_BEFORE_START -> R.string.manual_err_end_before
        ManualProblem.END_FUTURE -> R.string.manual_err_end_future
        ManualProblem.TOO_SHORT -> R.string.manual_err_min
        ManualProblem.HEART_RATE -> R.string.manual_err_hr
        ManualProblem.CALORIES -> R.string.manual_err_kcal
        ManualProblem.DISTANCE -> if (imperial) R.string.manual_err_dist_mi else R.string.manual_err_dist_km
        ManualProblem.CHECK -> R.string.manual_err_check
    },
)
