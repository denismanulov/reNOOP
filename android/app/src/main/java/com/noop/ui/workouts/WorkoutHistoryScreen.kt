package com.noop.ui.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.WorkoutRow
import com.noop.ui.AppViewModel
import com.noop.ui.WorkoutEditing
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.groupItemShape
import java.time.ZoneId

// MARK: - All Workouts (twin of iOS WorkoutHistoryView)
//
// Every workout, newest first, grouped by month, as the Fitness app's "All Workouts": filter chips ("All" and
// each activity the history holds, most frequent first), month sections of rows, a swipe to delete that the
// snackbar can undo, the row menu on a long press, and "No Workouts" when a filter empties the list.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutHistoryScreen(vm: AppViewModel, onBack: () -> Unit, openWorkout: (WorkoutRow) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val all by vm.workouts.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(all.isNotEmpty()) }
    LaunchedEffect(Unit) {
        vm.loadWorkouts()
        loaded = true
    }
    val rows = withoutPendingDelete(all)
    // null = every activity.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val sports = remember(rows) { workoutSportFilters(rows) }
    val visible = filter?.let { f -> rows.filter { WorkoutEditing.displaySport(it.sport) == f } } ?: rows
    val months = remember(visible) { workoutMonths(visible, ZoneId.systemDefault()) }

    var editing by remember { mutableStateOf<WorkoutEditTarget?>(null) }
    val actions = rememberWorkoutRowActions(vm, open = openWorkout, edit = { row, copy -> editing = WorkoutEditTarget(row, copy) })
    val snackbar = remember { SnackbarHostState() }
    WorkoutUndoSnackbar(snackbar)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            PushedTopBar(
                title = stringResource(R.string.workouts_all),
                onBack = onBack,
                large = true,
                scrollBehavior = scrollBehavior,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            ) {
                if (sports.size > 1) {
                    item(key = "chips") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = M3Dimens.screenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "all") {
                                FilterChip(
                                    selected = filter == null,
                                    onClick = { filter = null },
                                    label = { Text(stringResource(R.string.workouts_filter_all)) },
                                )
                            }
                            items(sports, key = { it }) { sport ->
                                FilterChip(
                                    selected = filter == sport,
                                    onClick = { filter = if (filter == sport) null else sport },
                                    label = { Text(sportLabel(sport)) },
                                )
                            }
                        }
                    }
                }
                months.forEach { (month, list) ->
                    item(key = "m-$month") {
                        Text(
                            workoutMonthTitle(month, locale),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(start = M3Dimens.screenPadding + 4.dp, top = M3Dimens.sectionTop, bottom = 8.dp)
                                .semantics { heading() },
                        )
                    }
                    list.forEachIndexed { i, row ->
                        item(key = "w-${workoutKey(row)}") {
                            SwipeableRow(
                                row = row,
                                shape = groupItemShape(i, list.size),
                                actions = actions,
                                modifier = Modifier
                                    .padding(horizontal = M3Dimens.screenPadding)
                                    .padding(top = if (i == 0) 0.dp else M3Dimens.groupGap),
                            )
                        }
                    }
                }
                if (loaded && visible.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.AutoMirrored.Filled.DirectionsRun,
                            title = stringResource(R.string.workouts_none),
                            action = if (filter != null) stringResource(R.string.workouts_show_all) else null,
                            onAction = if (filter != null) ({ filter = null }) else null,
                            modifier = Modifier.padding(top = 48.dp),
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp))
    }

    editing?.let { target ->
        ManualWorkoutSheet(
            target = target,
            onDismiss = { editing = null },
            onSave = { row, replacing ->
                vm.saveManualWorkout(row, replacing)
                editing = null
            },
        )
    }
}

/** A row that deletes on a swipe from the end (the snackbar then offers Undo). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(row: WorkoutRow, shape: Shape, actions: WorkoutRowActions, modifier: Modifier) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                actions.delete(row)
                true
            } else false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.workouts_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        WorkoutListRow(row, shape, actions, Modifier.fillMaxWidth())
    }
}
