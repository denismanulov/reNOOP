package com.noop.ui.workouts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ui.AppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.WheelPicker
import com.noop.ui.m3.Health
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader

// MARK: - Intervals (twin of iOS IntervalTimerView / IntervalRunView)
//
// The setup page is Fitness's custom workout: a start card ("8 × 0:30 / 0:15" and the green play button),
// then the "Workout" blocks — Work and Rest with − / + and a wheel that opens under the block, Rounds with
// − / + — and the planned total. Starting opens the same dark recording screen a workout uses: the round and
// phase, the phase countdown large in the phase's hue with its progress, the heart rate and the time left
// overall, then the panel with the session clock and Finish · pause / resume (Restart when done) · Skip.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IntervalsSetupScreen(onBack: () -> Unit) {
    val runner = IntervalTimerRunner.shared
    val c = Health.colors
    var wheel by remember { mutableStateOf<IntervalBlock?>(null) }
    LaunchedEffect(runner.running) { if (runner.running) wheel = null }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(stringResource(R.string.nav_intervals), onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "start") { IntervalsStartCard(runner) }
            item(key = "workout-h") { SectionHeader(stringResource(R.string.intervals_workout)) }
            item(key = "blocks") {
                val enabled = !runner.running
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(M3Dimens.groupGap),
                ) {
                    IntervalBlockRow(
                        title = stringResource(R.string.intervals_work),
                        icon = Icons.Filled.KeyboardDoubleArrowUp,
                        tint = c.fitness,
                        value = WorkoutFormat.phaseClock(runner.workSeconds),
                        enabled = enabled,
                        shape = RoundedCornerShape(M3Dimens.groupOuter, M3Dimens.groupOuter, M3Dimens.groupInner, M3Dimens.groupInner),
                        onMinus = { runner.updateWork(runner.workSeconds - 5) },
                        onPlus = { runner.updateWork(runner.workSeconds + 5) },
                        onToggleWheel = { wheel = if (wheel == IntervalBlock.Work) null else IntervalBlock.Work },
                        wheelOpen = wheel == IntervalBlock.Work,
                        seconds = runner.workSeconds,
                        onWheel = { runner.updateWork(it) },
                    )
                    IntervalBlockRow(
                        title = stringResource(R.string.intervals_rest),
                        icon = Icons.Filled.KeyboardDoubleArrowDown,
                        tint = c.rest,
                        value = WorkoutFormat.phaseClock(runner.restSeconds),
                        enabled = enabled,
                        shape = RoundedCornerShape(M3Dimens.groupInner),
                        onMinus = { runner.updateRest(runner.restSeconds - 5) },
                        onPlus = { runner.updateRest(runner.restSeconds + 5) },
                        onToggleWheel = { wheel = if (wheel == IntervalBlock.Rest) null else IntervalBlock.Rest },
                        wheelOpen = wheel == IntervalBlock.Rest,
                        seconds = runner.restSeconds,
                        onWheel = { runner.updateRest(it) },
                    )
                    IntervalBlockRow(
                        title = stringResource(R.string.intervals_rounds),
                        icon = Icons.Filled.Repeat,
                        tint = MaterialTheme.colorScheme.onSurface,
                        value = runner.rounds.toString(),
                        enabled = enabled,
                        shape = RoundedCornerShape(M3Dimens.groupInner, M3Dimens.groupInner, M3Dimens.groupOuter, M3Dimens.groupOuter),
                        onMinus = { runner.updateRounds(runner.rounds - 1) },
                        onPlus = { runner.updateRounds(runner.rounds + 1) },
                        onToggleWheel = null,
                        wheelOpen = false,
                        seconds = 0,
                        onWheel = {},
                    )
                }
            }
            item(key = "total") {
                Text(
                    stringResource(R.string.intervals_total, WorkoutFormat.phaseClock(runner.totalPlanned)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private enum class IntervalBlock { Work, Rest }

/** Fitness's start card: what will run, and the green play button. */
@Composable
private fun IntervalsStartCard(runner: IntervalTimerRunner) {
    val c = Health.colors
    val title = stringResource(if (runner.inProgress) R.string.intervals_resume else R.string.nav_intervals)
    val plan = "${runner.rounds} × ${WorkoutFormat.phaseClock(runner.workSeconds)} / ${WorkoutFormat.phaseClock(runner.restSeconds)}"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(M3Dimens.heroRadius))
            .background(c.fitnessContainer)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.workouts_start)) {
                if (!runner.inProgress) runner.start()
                NowRunning.expand(NowRunning.Kind.Intervals)
            }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = c.fitness, modifier = Modifier.size(32.dp))
            Spacer(Modifier.weight(1f))
            PlayDisc()
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = c.onFitnessContainer)
            Text(plan, style = MaterialTheme.typography.bodyLarge, color = c.fitness)
        }
    }
}

/** The green play button of a start card. */
@Composable
internal fun PlayDisc() {
    val c = Health.colors
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.fitness),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.surface)
    }
}

/**
 * One block: glyph, title over its value in the block's hue, − / +, and (Work, Rest) a wheel of minutes and
 * seconds that opens under it on a tap. One adjustable TalkBack element per block.
 */
@Composable
private fun IntervalBlockRow(
    title: String,
    icon: ImageVector,
    tint: Color,
    value: String,
    enabled: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onToggleWheel: (() -> Unit)?,
    wheelOpen: Boolean,
    seconds: Int,
    onWheel: (Int) -> Unit,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .let { m -> if (onToggleWheel != null && enabled) m.clickable(role = Role.Button, onClick = onToggleWheel) else m }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(24.dp))
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { stateDescription = value },
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = tint.copy(alpha = alpha),
                )
            }
            StepButton(Icons.Filled.Remove, stringResource(R.string.intervals_decrease, title), tint, enabled, onMinus)
            StepButton(Icons.Filled.Add, stringResource(R.string.intervals_increase, title), tint, enabled, onPlus)
        }
        AnimatedVisibility(visible = wheelOpen && enabled) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DurationWheel(seconds, onWheel)
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = tint.copy(alpha = 0.18f),
            contentColor = tint,
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

/** Clock's countdown wheel: minutes 0–59 and seconds in 5 s steps, each with its unit beside it. */
@Composable
private fun DurationWheel(total: Int, onChange: (Int) -> Unit) {
    val minutes = (0..59).map { it.toString() }
    val secs = (0 until 60 step 5).map { it.toString() }
    // The wheels report their centre once as they settle on the value they opened on; changes count from
    // there, so opening a wheel never rewrites the block.
    val m0 = remember { (total / 60).coerceIn(0, 59) }
    val s0 = remember { (total % 60) / 5 }
    var minutesArmed by remember { mutableStateOf(false) }
    var secondsArmed by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(minutes, m0, { i ->
                if (minutesArmed) onChange(i * 60 + (total % 60) / 5 * 5) else if (i == m0) minutesArmed = true
            }, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.intervals_min), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(secs, s0, { i ->
                if (secondsArmed) onChange((total / 60).coerceIn(0, 59) * 60 + i * 5) else if (i == s0) secondsArmed = true
            }, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.intervals_sec), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// MARK: - Running

/** The interval in progress, on the same dark recording screen a workout uses. */
@Composable
internal fun IntervalRunScreen(vm: AppViewModel, onMinimize: () -> Unit) {
    val runner = IntervalTimerRunner.shared
    val context = LocalContext.current
    val c = Health.colors
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    var confirmEnd by remember { mutableStateOf(false) }
    BackHandler(onBack = onMinimize)

    // Live heart rate while this screen is up, as the workout's recording screen arms it.
    DisposableEffect(Unit) {
        vm.requestRealtimeHr()
        onDispose { vm.releaseRealtimeHr() }
    }
    // Keep the screen awake while a session runs when Settings > Workouts says so; released on the way out.
    val view = LocalView.current
    val keepOn = remember { NoopPrefs.of(context).getBoolean("workoutKeepScreenOn", false) }
    LaunchedEffect(runner.running, keepOn) { view.keepScreenOn = keepOn && runner.running }
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }

    val phaseColor = if (runner.phase == IntervalTimerRunner.Phase.Rest) c.rest else c.fitness
    val phaseLabel = when (runner.phase) {
        IntervalTimerRunner.Phase.Work -> stringResource(R.string.intervals_work)
        IntervalTimerRunner.Phase.Rest -> stringResource(R.string.intervals_rest)
        IntervalTimerRunner.Phase.Done -> stringResource(R.string.intervals_done)
    }
    val progress by animateFloatAsState(
        targetValue = if (runner.isFinished) 1f else runner.phaseProgress,
        animationSpec = tween(900),
        label = "phase",
    )

    fun endIntervals() {
        runner.stopAndReset()
        onMinimize()
    }

    RecordingScaffold(
        title = stringResource(R.string.nav_intervals),
        onMinimize = onMinimize,
        panel = {
            RecordingPanel(
                glyph = Icons.Filled.Timer,
                clock = {
                    RecordingClock(
                        text = WorkoutFormat.phaseClock(runner.elapsed),
                        paused = runner.isPaused,
                        spoken = stringResource(R.string.workouts_elapsed) + ", " + WorkoutFormat.phaseClock(runner.elapsed),
                    )
                },
                leading = {
                    RecordingButton(Icons.Filled.Close, stringResource(R.string.workouts_finish), RecordingButtonKind.Destructive) {
                        // A session under way asks first; one not started or already finished has nothing to lose.
                        if (runner.elapsed > 0 && !runner.isFinished) confirmEnd = true else endIntervals()
                    }
                },
                center = {
                    if (runner.isFinished) {
                        RecordingButton(Icons.Filled.Replay, stringResource(R.string.intervals_restart), RecordingButtonKind.Prominent) {
                            runner.resetToStart()
                            runner.start()
                        }
                    } else {
                        RecordingButton(
                            if (runner.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (runner.running) R.string.workout_action_pause else R.string.workout_action_resume),
                            RecordingButtonKind.Prominent,
                        ) { runner.toggleRunning() }
                    }
                },
                right = {
                    RecordingButton(
                        Icons.Filled.SkipNext, stringResource(R.string.intervals_skip), RecordingButtonKind.Neutral,
                        enabled = !runner.isFinished,
                    ) { runner.skipPhase() }
                },
            )
        },
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    if (runner.isFinished) stringResource(R.string.intervals_rounds_count, runner.rounds)
                    else stringResource(R.string.intervals_round_of, minOf(runner.currentRound, runner.rounds), runner.rounds),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = phaseColor,
                )
                Text(
                    phaseLabel,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                WorkoutFormat.phaseClock(if (runner.isFinished) runner.elapsed else runner.remaining),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 112.sp, lineHeight = 120.sp, fontFeatureSettings = "tnum"),
                color = phaseColor,
                maxLines = 1,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clearAndSetSemantics {},
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(CircleShape).background(phaseColor))
            }
            LiveFigure(
                bpm?.toString() ?: "—",
                stringResource(R.string.workouts_live_heart_rate),
                trailing = {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = c.heart,
                        modifier = Modifier.size(36.dp),
                    )
                },
            )
            LiveFigure(
                WorkoutFormat.phaseClock((runner.totalPlanned - runner.elapsed).coerceAtLeast(0)),
                stringResource(R.string.intervals_total_left),
            )
        }
    }

    if (confirmEnd) {
        FinishDialog(
            title = stringResource(R.string.intervals_finish_question),
            message = null,
            finish = stringResource(R.string.intervals_finish),
            discard = null,
            onFinish = { confirmEnd = false; endIntervals() },
            onDiscard = {},
            onCancel = { confirmEnd = false },
        )
    }
}
