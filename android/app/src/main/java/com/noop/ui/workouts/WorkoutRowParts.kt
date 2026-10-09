package com.noop.ui.workouts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.WorkoutRow
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import com.noop.ui.WorkoutEditing
import com.noop.ui.WorkoutSource
import com.noop.ui.m3.Health
import com.noop.ui.sportIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// MARK: - Pieces every workout list shares
//
// One row (Fitness's: the activity glyph on a green disc, the name, the headline figure in the fitness
// colour, the date on the trailing edge), one set of row actions (the same menu on a row's long-press and on
// its page's overflow, as iOS `WorkoutRowMenu`), and one delete that can be undone.

/** What a workout's menu does; the screen resolves each to the view model or a sheet. */
internal class WorkoutRowActions(
    val open: (WorkoutRow) -> Unit,
    /** Opens the editor; `isCopy` duplicates an imported row as a manual one instead of replacing it. */
    val edit: (row: WorkoutRow, isCopy: Boolean) -> Unit,
    val relabel: (row: WorkoutRow, sport: String) -> Unit,
    val notAWorkout: (WorkoutRow) -> Unit,
    val delete: (WorkoutRow) -> Unit,
)

/** The sports a detected bout can be labelled as from its menu (iOS: the start defaults + four more). */
internal val relabelChoices: List<String> = WorkoutQuickStart.defaults + listOf("HIIT", "Yoga", "Hiking", "Tennis")

/** The activity glyph on Fitness's green disc. */
@Composable
internal fun SportBadge(sport: String, size: Dp = 44.dp, iconSize: Dp = 24.dp) {
    val c = Health.colors
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(c.fitnessContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(sportIcon(WorkoutEditing.displaySport(sport)), contentDescription = null, tint = c.fitness, modifier = Modifier.size(iconSize))
    }
}

/** The figure a row leads with, in the reader's units and number format: "5,21 km", "318 kcal", "42 min". */
@Composable
internal fun workoutHeadline(row: WorkoutRow): String {
    val locale = LocalConfiguration.current.locales[0]
    val imperial = UnitPrefs.distanceSystem(LocalContext.current) == UnitSystem.IMPERIAL
    return when (val h = WorkoutHeadline.of(row)) {
        is WorkoutHeadline.Distance -> WorkoutFormat.distanceValue(h.meters, imperial, locale) + " " +
            stringResource(if (imperial) R.string.workouts_unit_mi else R.string.workouts_unit_km)
        is WorkoutHeadline.Energy -> WorkoutFormat.grouped(h.kcal, locale) + " " + stringResource(R.string.metric_unit_kcal)
        is WorkoutHeadline.Duration -> WorkoutFormat.durationWords(
            h.seconds, stringResource(R.string.metric_unit_hr), stringResource(R.string.metric_unit_min),
        )
    }
}

/** "Today", "Yesterday", else the short date ("26 Sep"). */
@Composable
internal fun workoutDayLabel(startTs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (val d = WorkoutDay.of(startTs, LocalDate.now(), ZoneId.systemDefault())) {
        WorkoutDay.Today -> stringResource(R.string.metric_today)
        WorkoutDay.Yesterday -> stringResource(R.string.metric_yesterday)
        is WorkoutDay.Date -> DateTimeFormatter.ofPattern("d MMM", locale).format(d.date)
    }
}

/**
 * One session as Fitness lists it, on a [shape] of its list group. A tap opens it; a long press opens the
 * row's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkoutListRow(row: WorkoutRow, shape: Shape, actions: WorkoutRowActions, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = sportLabel(row.sport)
    val headline = workoutHeadline(row)
    val day = workoutDayLabel(row.startTs)
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .combinedClickable(
                    role = Role.Button,
                    onClick = { actions.open(row) },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SportBadge(row.sport)
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    headline,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = Health.colors.fitness,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                day,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Bottom),
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 16.dp)) {
            WorkoutRowMenu(row, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
        }
    }
}

/**
 * The actions one workout offers wherever it is shown (a row's long press, its page's overflow): a detected
 * bout is labelled or dismissed, a manual one edited, an imported one duplicated as manual; every one deletes.
 */
@Composable
internal fun WorkoutRowMenu(row: WorkoutRow, expanded: Boolean, onDismiss: () -> Unit, actions: WorkoutRowActions) {
    var labelling by remember { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        when (WorkoutEditing.classify(row.source)) {
            WorkoutSource.DETECTED -> {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workouts_label_as)) },
                    leadingIcon = { Icon(Icons.Filled.Label, null) },
                    onClick = { onDismiss(); labelling = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workouts_not_a_workout)) },
                    leadingIcon = { Icon(Icons.Filled.Cancel, null) },
                    onClick = { onDismiss(); actions.notAWorkout(row) },
                )
            }
            WorkoutSource.MANUAL -> DropdownMenuItem(
                text = { Text(stringResource(R.string.workouts_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { onDismiss(); actions.edit(row, false) },
            )
            else -> DropdownMenuItem(
                text = { Text(stringResource(R.string.workouts_duplicate)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { onDismiss(); actions.edit(WorkoutEditing.asManualCopy(row), true) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workouts_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); actions.delete(row) },
        )
    }
    DropdownMenu(expanded = labelling, onDismissRequest = { labelling = false }) {
        relabelChoices.forEach { sport ->
            DropdownMenuItem(
                text = { Text(sportLabel(sport)) },
                leadingIcon = { Icon(sportIcon(sport), null) },
                onClick = { labelling = false; actions.relabel(row, sport) },
            )
        }
    }
}

// MARK: - Delete with undo
//
// iOS deletes at once and puts the session (and its route) back on shake / ⌘Z. Android has no restore path
// in its data layer, so the delete is held instead: the row leaves the lists at once, a snackbar offers
// Undo, and only when that window closes (or another delete starts) does the view model delete it. Leaving
// the screen does not cancel the commit; a process death inside the window keeps the workout.

/** The one workout whose delete can still be undone, app-wide. */
internal object WorkoutDeletes {
    const val UNDO_WINDOW_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _pending = MutableStateFlow<WorkoutRow?>(null)
    val pending: StateFlow<WorkoutRow?> = _pending.asStateFlow()
    private var commit: ((WorkoutRow) -> Unit)? = null
    private var timer: Job? = null

    /** Hides [row] now and deletes it with [commit] when the undo window closes. */
    fun request(row: WorkoutRow, commit: (WorkoutRow) -> Unit) {
        flush()
        this.commit = commit
        _pending.value = row
        timer = scope.launch {
            delay(UNDO_WINDOW_MS)
            flush()
        }
    }

    /** Puts the pending row back. */
    fun undo() {
        timer?.cancel()
        timer = null
        commit = null
        _pending.value = null
    }

    /** Deletes the pending row now. */
    fun flush() {
        val row = _pending.value
        val c = commit
        _pending.value = null
        commit = null
        val t = timer
        timer = null
        if (row != null && c != null) c(row)
        t?.cancel()
    }
}

/** [rows] without the one whose delete is pending. */
@Composable
internal fun withoutPendingDelete(rows: List<WorkoutRow>): List<WorkoutRow> {
    val pending by WorkoutDeletes.pending.collectAsState()
    val key = pending?.let(::workoutKey) ?: return rows
    return rows.filter { workoutKey(it) != key }
}

/** Shows "Workout deleted · Undo" in [host] while a delete can be undone. */
@Composable
internal fun WorkoutUndoSnackbar(host: SnackbarHostState) {
    val pending by WorkoutDeletes.pending.collectAsState()
    val message = stringResource(R.string.workouts_deleted)
    val undo = stringResource(R.string.workouts_undo)
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        when (host.showSnackbar(message, actionLabel = undo, withDismissAction = true, duration = SnackbarDuration.Indefinite)) {
            SnackbarResult.ActionPerformed -> WorkoutDeletes.undo()
            SnackbarResult.Dismissed -> WorkoutDeletes.flush()
        }
    }
}
