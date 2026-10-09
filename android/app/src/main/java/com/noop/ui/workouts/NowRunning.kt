package com.noop.ui.workouts

import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.Sport
import com.noop.analytics.WorkoutSport
import com.noop.ui.ActiveWorkoutClock
import com.noop.ui.AppViewModel
import com.noop.ui.RecentSportsPrefs
import com.noop.ui.elapsedClock
import com.noop.ui.m3.Health
import com.noop.ui.rememberRequestLocation
import com.noop.ui.sportIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// MARK: - Now running (twin of iOS NowRunning.swift)
//
// Whatever is running while its screen is put away — a workout or the interval timer (Android has no gym
// session) — resolved once, and drawn as Google's own media mini-player: a tonal bar docked above the
// navigation bar on every tab. Tapping it opens the recording screen again; its one button pauses or resumes.

/** Which recording screen is open over the app, if any. */
internal object NowRunning {
    enum class Kind { Workout, Intervals }

    private val _expanded = MutableStateFlow<Kind?>(null)
    val expanded: StateFlow<Kind?> = _expanded.asStateFlow()

    fun expand(kind: Kind) {
        _expanded.value = kind
    }

    fun collapse() {
        _expanded.value = null
    }

    private val _finishRequested = MutableStateFlow(false)

    /** The workout's recording screen should raise its Finish confirmation: the live-workout
     *  notification's Finish action, which confirms on that screen as its own button does. */
    val finishRequested: StateFlow<Boolean> = _finishRequested.asStateFlow()

    fun requestFinish() {
        _finishRequested.value = true
    }

    fun consumeFinishRequest() {
        _finishRequested.value = false
    }

    /**
     * What the mini-player shows. When both run, the interval timer wins (it runs on its own clock); a
     * workout records whatever else happens. Twin of the iOS priority (gym session, intervals, workout).
     */
    fun kind(workoutActive: Boolean, intervalsInProgress: Boolean): Kind? = when {
        intervalsInProgress -> Kind.Intervals
        workoutActive -> Kind.Workout
        else -> null
    }
}

/**
 * Starts a live workout for a catalogue sport and opens its recording screen. A distance sport asks for
 * location first and records a route when it is granted (the session starts route-less otherwise, #101);
 * with a workout already running it only reopens that one.
 */
@Composable
internal fun rememberWorkoutStarter(vm: AppViewModel): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Sport?>(null) }
    val requestLocation = rememberRequestLocation { granted ->
        val sport = pending ?: return@rememberRequestLocation
        pending = null
        vm.startWorkout(sport, gpsEnabled = granted)
        NowRunning.expand(NowRunning.Kind.Workout)
    }
    return remember(vm) {
        { name: String ->
            if (vm.activeWorkout.value != null) {
                NowRunning.expand(NowRunning.Kind.Workout)
            } else {
                val sport = WorkoutSport.all.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                if (sport != null) {
                    RecentSportsPrefs.record(context, sport.name)
                    if (sport.isDistanceSport) {
                        pending = sport
                        requestLocation()
                    } else {
                        vm.startWorkout(sport, gpsEnabled = false)
                        NowRunning.expand(NowRunning.Kind.Workout)
                    }
                }
            }
        }
    }
}

/**
 * The mini-player, docked above the navigation bar while something runs and its screen is put away. Shown
 * by the shell on every tab; it hides while the recording screen itself is open.
 */
@Composable
internal fun NowRunningBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    val active by vm.activeWorkout.collectAsStateWithLifecycle()
    val runner = IntervalTimerRunner.shared
    val expanded by NowRunning.expanded.collectAsStateWithLifecycle()
    val kind = NowRunning.kind(active != null, runner.inProgress)
    AnimatedVisibility(
        visible = kind != null && expanded == null,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier,
    ) {
        when (kind) {
            NowRunning.Kind.Intervals -> IntervalsBar(runner)
            NowRunning.Kind.Workout -> WorkoutBar(vm)
            null -> Box(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun WorkoutBar(vm: AppViewModel) {
    val w = vm.activeWorkout.collectAsStateWithLifecycle().value ?: return
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val paused = w.pausedAtMs != null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(w.startMs, paused) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = ActiveWorkoutClock.activeElapsedSeconds(w.startMs, w.pausedAtMs, w.pausedDurationMs, now)
    val status = when {
        paused -> stringResource(R.string.workout_action_paused)
        bpm != null -> "$bpm ${stringResource(R.string.metric_unit_bpm)}"
        else -> null
    }
    RunningBarRow(
        glyph = sportIcon(w.sport.name),
        title = sportLabel(w.sport.name),
        clock = elapsedClock(elapsed),
        clockColor = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else Health.colors.fitness,
        status = status,
        controlIcon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
        controlLabel = stringResource(if (paused) R.string.workout_action_resume else R.string.workout_action_pause),
        onControl = { vm.toggleWorkoutPause() },
        onOpen = { NowRunning.expand(NowRunning.Kind.Workout) },
    )
}

@Composable
private fun IntervalsBar(runner: IntervalTimerRunner) {
    val phase = when (runner.phase) {
        IntervalTimerRunner.Phase.Work -> stringResource(R.string.intervals_work)
        IntervalTimerRunner.Phase.Rest -> stringResource(R.string.intervals_rest)
        IntervalTimerRunner.Phase.Done -> stringResource(R.string.intervals_done)
    }
    val c = Health.colors
    RunningBarRow(
        glyph = Icons.Filled.Timer,
        title = stringResource(R.string.nav_intervals),
        clock = WorkoutFormat.phaseClock(runner.remaining),
        clockColor = when {
            !runner.running -> MaterialTheme.colorScheme.onSurfaceVariant
            runner.phase == IntervalTimerRunner.Phase.Rest -> c.rest
            else -> c.fitness
        },
        status = "$phase · ${minOf(runner.currentRound, runner.rounds)}/${runner.rounds}",
        controlIcon = if (runner.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        controlLabel = stringResource(if (runner.running) R.string.workout_action_pause else R.string.workout_action_resume),
        onControl = { runner.toggleRunning() },
        onOpen = { NowRunning.expand(NowRunning.Kind.Intervals) },
    )
}

/**
 * One mini-player row: the activity's glyph on Fitness's green disc, the title over "clock · status", then a
 * bare control. One TalkBack element whose click opens the recording screen; the control is a named action.
 */
@Composable
private fun RunningBarRow(
    glyph: ImageVector,
    title: String,
    clock: String,
    clockColor: Color,
    status: String?,
    controlIcon: ImageVector,
    controlLabel: String,
    onControl: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = Health.colors
    val openLabel = stringResource(R.string.workouts_open)
    val spoken = listOfNotNull(title, clock, status).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // At least 64 dp, taller when the two lines need it at the reader's font size (CR-1).
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen, onClickLabel = openLabel, role = Role.Button)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = openLabel) { onOpen(); true }
                customActions = listOf(CustomAccessibilityAction(controlLabel) { onControl(); true })
            }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.fitnessContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, contentDescription = null, tint = c.fitness, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    clock,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = clockColor,
                    maxLines = 1,
                )
                if (status != null) {
                    Text(
                        " · $status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = onControl) {
            Icon(controlIcon, contentDescription = controlLabel, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
