package com.noop.ui.workouts

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.WorkoutSport
import com.noop.data.WorkoutRow
import com.noop.ui.AppViewModel
import com.noop.ui.OnScrollToTop
import com.noop.ui.RecentSportsPrefs
import com.noop.ui.m3.Health
import com.noop.ui.m3.LargeTitle
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.groupItemShape
import com.noop.ui.sportIcon
import com.noop.ui.todayPullToSyncEnabled

// MARK: - Workouts tab (twin of iOS WorkoutsHomeView, set in Material 3)
//
// The Fitness app's Workout tab: a large title with + (add a workout by hand) and the overflow (Intervals;
// Android has no Lift Log), a two-column grid of start cards — the activities started most recently, then
// the most frequent, then Fitness's defaults — each in the fitness container colour with its play button,
// "Other Workout" for the full list, then "Recent" with the last three sessions and "Show All". Pull to refresh.

/** Where the Workouts tab's taps go; the shell resolves each to a route on the Workouts tab. */
internal class WorkoutsNav(
    val openHistory: () -> Unit,
    val openWorkout: (WorkoutRow) -> Unit,
    val openIntervals: () -> Unit,
)

/** How many sessions Recent lists before "Show All". */
private const val RECENT_COUNT = 3

/** Trailing days the start-card order reads, as iOS bounds it (#797). */
private const val QUICK_WINDOW_DAYS = 400L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutsHomeScreen(vm: AppViewModel, nav: WorkoutsNav) {
    val context = LocalContext.current
    val all by vm.workouts.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWorkouts() }

    val rows = withoutPendingDelete(all)
    val quickSports = remember(all) {
        val since = System.currentTimeMillis() / 1000 - QUICK_WINDOW_DAYS * 86_400
        WorkoutQuickStart.sports(
            recents = RecentSportsPrefs.recent(context),
            history = all.filter { it.startTs >= since }.map { it.sport },
            catalogue = WorkoutSport.all.map { it.name },
        )
    }
    val start = rememberWorkoutStarter(vm)
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkoutEditTarget?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val actions = rememberWorkoutRowActions(vm, open = nav.openWorkout, edit = { row, copy -> editing = WorkoutEditTarget(row, copy) })

    val snackbar = remember { SnackbarHostState() }
    WorkoutUndoSnackbar(snackbar)

    val pull = rememberPullToRefreshState()
    LaunchedEffect(pull.isRefreshing) {
        if (!pull.isRefreshing) return@LaunchedEffect
        if (todayPullToSyncEnabled(live.connected, live.bonded, live.backfilling, live.historyReady)) vm.syncNow()
        vm.loadWorkouts()
        pull.endRefresh()
    }
    val listState = rememberLazyListState()
    OnScrollToTop { listState.animateScrollToItem(0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .nestedScroll(pull.nestedScrollConnection),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "title") {
                LargeTitle(stringResource(R.string.nav_workouts)) {
                    IconButton(onClick = { editing = WorkoutEditTarget(null) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.workouts_add_cd))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.workouts_more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_intervals)) },
                                leadingIcon = { Icon(Icons.Filled.Timer, null) },
                                onClick = { menuOpen = false; nav.openIntervals() },
                            )
                        }
                    }
                }
            }
            // Start cards two to a row; an odd count closes the grid with "Other Workout" as its last cell,
            // an even one puts it on its own row below.
            val cells: List<String?> = if (quickSports.size % 2 == 1) quickSports + null else quickSports
            cells.chunked(2).forEachIndexed { i, pair ->
                item(key = "cards-$i") {
                    // Both cards of a row take the taller one's height: at least 150 dp, more when a
                    // two-line name needs it at a large font size.
                    Row(
                        Modifier.padding(horizontal = M3Dimens.screenPadding).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
                    ) {
                        pair.forEach { sport ->
                            if (sport != null) {
                                StartCard(sport, Modifier.weight(1f).fillMaxHeight()) { start(sport) }
                            } else {
                                OtherCard(Modifier.weight(1f).fillMaxHeight()) { picking = true }
                            }
                        }
                    }
                }
            }
            if (quickSports.size % 2 == 0) {
                item(key = "other") {
                    OtherRow(Modifier.padding(horizontal = M3Dimens.screenPadding)) { picking = true }
                }
            }
            if (rows.isNotEmpty()) {
                item(key = "recent-h") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = M3Dimens.screenPadding + 4.dp, end = 8.dp, top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.workouts_recent),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        TextButton(onClick = nav.openHistory) { Text(stringResource(R.string.workouts_show_all)) }
                    }
                }
                item(key = "recent") {
                    val recent = rows.take(RECENT_COUNT)
                    Column(
                        Modifier.padding(horizontal = M3Dimens.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(M3Dimens.groupGap),
                    ) {
                        recent.forEachIndexed { i, row ->
                            WorkoutListRow(row, groupItemShape(i, recent.size), actions)
                        }
                    }
                }
            }
        }
        // Only while a pull or a refresh is running: at rest the container draws a stray disc under the
        // status bar (it showed on this tab and through every full-screen dialog opened from it).
        if (pull.progress > 0f || pull.isRefreshing) {
            PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp))
    }

    if (picking) {
        StartWorkoutPicker(onDismiss = { picking = false }, onStart = { picking = false; start(it) })
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

/** One start card: the activity's glyph, the green play button, its name. */
@Composable
private fun StartCard(sport: String, modifier: Modifier, onStart: () -> Unit) {
    val c = Health.colors
    val name = sportLabel(sport)
    Column(
        modifier
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(M3Dimens.heroRadius))
            .background(c.fitnessContainer)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.workouts_start), onClick = onStart)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(sportIcon(sport), contentDescription = null, tint = c.fitness, modifier = Modifier.size(32.dp))
            Spacer(Modifier.weight(1f))
            PlayDisc()
        }
        Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = c.onFitnessContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Other Workout" as the grid's last cell (odd card count). */
@Composable
private fun OtherCard(modifier: Modifier, onClick: () -> Unit) {
    val c = Health.colors
    Column(
        modifier
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(M3Dimens.heroRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = c.fitness, modifier = Modifier.size(32.dp))
        Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
        Text(
            stringResource(R.string.workouts_other),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

/** "Other Workout" on its own row under an even grid. */
@Composable
private fun OtherRow(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(M3Dimens.heroRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = Health.colors.fitness)
        Text(
            stringResource(R.string.workouts_other),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One set of row actions wired to the view model; the delete waits out its undo window. */
@Composable
internal fun rememberWorkoutRowActions(
    vm: AppViewModel,
    open: (WorkoutRow) -> Unit,
    edit: (WorkoutRow, Boolean) -> Unit,
    afterDelete: () -> Unit = {},
): WorkoutRowActions = WorkoutRowActions(
    open = open,
    edit = edit,
    relabel = { row, sport -> vm.relabelDetected(row, sport) },
    notAWorkout = { row -> vm.dismissDetected(row) },
    delete = { row ->
        WorkoutDeletes.request(row) { vm.deleteWorkout(it) }
        afterDelete()
    },
)
