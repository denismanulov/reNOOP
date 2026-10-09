package com.noop.ui.workouts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.NoopApplication
import com.noop.R
import com.noop.analytics.Calories
import com.noop.analytics.StrainScorer
import com.noop.ble.StandardHrSource
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.EffortScale
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.UnitFormatter
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import com.noop.ui.m3.ActivityRings
import com.noop.ui.m3.Health
import com.noop.ui.m3.ringFraction
import com.noop.ui.rememberPoseStill
import com.noop.ui.sportIcon
import com.noop.ui.summary.SummaryLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DecimalFormatSymbols
import kotlin.math.roundToInt

// MARK: - Live workout (twin of iOS LiveWorkoutView, set as Material's always-dark recording screen)
//
// The workout in progress, full screen and always dark: ⌄ minimises it to the mini-player; two pages swiped
// sideways — the live figures, then the heart-rate zones — over page dots; the panel at the bottom holds the
// activity, the running clock in the fitness green (paused yellow with "PAUSED"), today's rings, and Finish ·
// pause/resume · zones. Every figure comes from the same live feed and scorers as before (#238): heart rate is
// the smoothed bpm, Effort the running liveStrain, distance and pace the on-device GPS session (#1195),
// speed / cadence / power a connected fitness sensor, and active calories the same Keytel model the save uses.

private data class TodayRings(val charge: Float?, val effort: Float?, val rest: Float?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveWorkoutRecording(vm: AppViewModel, onMinimize: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val effortScale = UnitPrefs.effortScale(context)
    val imperial = UnitPrefs.distanceSystem(context) == UnitSystem.IMPERIAL
    val locale = LocalConfiguration.current.locales[0]
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val active by vm.activeWorkout.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val sensor by remember(context) {
        (context.applicationContext as NoopApplication).sourceCoordinator.sensorMetrics
    }.collectAsStateWithLifecycle()

    BackHandler(onBack = onMinimize)

    // Arm the realtime HR stream while this screen is up (ref-counted with Live / Heart Rate).
    DisposableEffect(Unit) {
        vm.requestRealtimeHr()
        onDispose { vm.releaseRealtimeHr() }
    }
    // Keep the screen awake while recording when Settings > Workouts says so (#703); always released.
    val view = LocalView.current
    DisposableEffect(Unit) {
        if (NoopPrefs.of(context).getBoolean("workoutKeepScreenOn", false)) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Today's Charge / Effort / Rest, the small rings beside the clock (read once, as iOS does).
    var rings by remember { mutableStateOf<TodayRings?>(null) }
    LaunchedEffect(Unit) {
        val snap = runCatching {
            val todayRow = vm.today.value
            SummaryLoader.load(vm, context, 0, vm.recentDays.value, todayRow, null, emptyMap(), AppToday.now(todayRow?.day))
        }.getOrNull()
        if (snap != null) {
            rings = TodayRings(ringFraction(snap.charge.pct, 100.0), ringFraction(snap.effort, 100.0), ringFraction(snap.rest, 100.0))
        }
    }

    val w = active
    // The workout ended elsewhere (a restart cleared it): close.
    LaunchedEffect(w == null) { if (w == null) onMinimize() }
    if (w == null) return

    val zoneSet = remember(profile.hrMax, profile.hrZoneThresholds) { profile.hrZoneSet }
    val zone = bpm?.let { zoneSet.zoneNumber(it.toDouble()) } ?: 0
    val paused = w.pausedAtMs != null
    val samples = w.samples
    val restingHr = today?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR
    val kcal = remember(samples.size, restingHr) {
        if (samples.size < 2) 0.0
        else runCatching {
            Calories.estimateBoutCalories(samples, profile.toUserProfile(), profile.hrMax.toDouble(), restingHr).first
        }.getOrDefault(0.0)
    }

    var confirmFinish by remember { mutableStateOf(false) }
    // The notification's Finish action opens this screen with the confirmation already up.
    val finishRequested by NowRunning.finishRequested.collectAsStateWithLifecycle()
    LaunchedEffect(finishRequested) {
        if (finishRequested) {
            confirmFinish = true
            NowRunning.consumeFinishRequest()
        }
    }
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    RecordingScaffold(
        title = sportLabel(w.sport.name),
        onMinimize = onMinimize,
        panel = {
            RecordingPanel(
                glyph = sportIcon(w.sport.name),
                clock = { WorkoutStopwatch(w, paused) },
                trailing = {
                    val r = rings
                    if (r != null) {
                        ActivityRings(r.charge, r.effort, r.rest, modifier = Modifier.size(44.dp), stroke = 5.dp, gap = 1.dp)
                    }
                },
                leading = {
                    RecordingButton(Icons.Filled.Close, stringResource(R.string.workouts_finish), RecordingButtonKind.Destructive) {
                        confirmFinish = true
                    }
                },
                center = {
                    RecordingButton(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        stringResource(if (paused) R.string.workout_action_resume else R.string.workout_action_pause),
                        RecordingButtonKind.Prominent,
                    ) { vm.toggleWorkoutPause() }
                },
                right = {
                    RecordingButton(Icons.Filled.MonitorHeart, stringResource(R.string.workouts_hr_zones), RecordingButtonKind.Neutral) {
                        scope.launch { pager.animateScrollToPage(if (pager.currentPage == 0) 1 else 0) }
                    }
                },
            )
        },
    ) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    LiveFigure(kcal.roundToInt().toString(), stringResource(R.string.workouts_live_active_kcal))
                    HeartRateFigure(bpm, zone)
                    if (w.gpsEnabled && w.track.isNotEmpty()) {
                        val unit = stringResource(if (imperial) R.string.workouts_unit_mi else R.string.workouts_unit_km)
                        LiveFigure(
                            WorkoutFormat.pace(w.paceSecPerKm, imperial) ?: "—",
                            stringResource(R.string.workouts_live_avg_pace),
                            unit = "/$unit",
                        )
                        LiveFigure(
                            WorkoutFormat.distanceValue(w.distanceM, imperial, locale),
                            stringResource(R.string.workouts_distance),
                            unit = unit,
                        )
                    } else {
                        val shown = UnitFormatter.effortValue(w.liveStrain, effortScale)
                        LiveFigure(
                            if (effortScale == EffortScale.WHOOP) "%.1f".format(locale, shown) else shown.roundToInt().toString(),
                            stringResource(R.string.workouts_live_effort),
                        )
                        LiveFigure(
                            if (w.avgHr > 0) "${w.avgHr}" else "—",
                            stringResource(R.string.workouts_live_avg_hr),
                        )
                    }
                    SensorFigures(sensor, imperial)
                }
            } else {
                ZonesPage(bpm, zone, zoneSet)
            }
        }
        PageDots(count = 2, selected = pager.currentPage, modifier = Modifier.padding(vertical = 12.dp))
    }

    if (confirmFinish) {
        FinishDialog(
            title = stringResource(R.string.l10n_live_workout_screen_end_this_workout_4869c76a),
            message = stringResource(R.string.l10n_live_workout_screen_this_stops_recording_and_saves_what_3e17a23e),
            finish = stringResource(R.string.workouts_finish_workout),
            discard = stringResource(R.string.workouts_discard_workout),
            onFinish = { confirmFinish = false; vm.endWorkout(); onMinimize() },
            onDiscard = { confirmFinish = false; vm.discardWorkout(); onMinimize() },
            onCancel = { confirmFinish = false },
        )
    }
}

/** The live heart rate with the heart beside it, and the five zone segments under it, the current one lit. */
@Composable
private fun HeartRateFigure(bpm: Int?, zone: Int) {
    val c = Health.colors
    Column {
        LiveFigure(
            bpm?.toString() ?: "—",
            stringResource(R.string.workouts_live_heart_rate),
            trailing = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = c.heart, modifier = Modifier.size(36.dp)) },
        )
        Row(
            Modifier.padding(top = 6.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            (1..5).forEach { z ->
                Box(
                    Modifier
                        .width(40.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.zone(z).copy(alpha = if (z == zone) 1f else 0.35f)),
                )
            }
        }
    }
}

/** The second page: the current zone named in its hue over five segments, the heart rate, and the band. */
@Composable
private fun ZonesPage(bpm: Int?, zone: Int, zoneSet: com.noop.analytics.HrZoneSet) {
    val c = Health.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Text(
            if (zone >= 1) stringResource(R.string.workouts_zone, zone) else stringResource(R.string.workouts_below_zone1),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (zone >= 1) c.zone(zone) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { z ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (z == zone) 14.dp else 8.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(c.zone(z).copy(alpha = if (z == zone) 1f else 0.25f)),
                )
            }
        }
        LiveFigure(
            bpm?.toString() ?: "—",
            label = "",
            trailing = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = c.heart, modifier = Modifier.size(36.dp)) },
        )
        val band = zoneSet.zones.firstOrNull { it.number == zone }
        if (band != null) {
            Text(
                "${band.lower.toInt()}–${band.upper.toInt()} ${stringResource(R.string.metric_unit_bpm)}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Speed / cadence / power from a connected fitness sensor; nothing when none is feeding. */
@Composable
private fun SensorFigures(sensor: StandardHrSource.SensorMetrics, imperial: Boolean) {
    val speed = UnitFormatter.speedFromKilometersPerHour(sensor.speedKmh, if (imperial) UnitSystem.IMPERIAL else UnitSystem.METRIC)
    val cadence = StandardHrSource.formatCadence(sensor.cadence)
    val power = StandardHrSource.formatPowerWatts(sensor.powerWatts)
    if (speed != null) {
        val parts = speed.split(' ', limit = 2)
        LiveFigure(parts[0], stringResource(R.string.workouts_live_speed), unit = parts.getOrNull(1))
    }
    if (cadence != null) LiveFigure(cadence, stringResource(R.string.workouts_live_cadence))
    if (power != null) LiveFigure(power, stringResource(R.string.workouts_live_power), unit = "W")
}

/**
 * The panel's stopwatch: hundredths at ~20 Hz as Fitness runs it; posed still (Reduce Motion, Battery Saver,
 * the app's quiet-motion setting) once a second without them; paused, drawn once.
 */
@Composable
private fun WorkoutStopwatch(w: AppViewModel.ActiveWorkout, paused: Boolean) {
    val still = rememberPoseStill()
    val locale = LocalConfiguration.current.locales[0]
    val mark = remember(locale) { DecimalFormatSymbols.getInstance(locale).decimalSeparator }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(paused, still, w.startMs) {
        if (paused) {
            now = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (true) {
            if (still) {
                now = System.currentTimeMillis()
                delay(1_000)
            } else {
                now = System.currentTimeMillis()
                delay(50)
            }
        }
    }
    val pausedAt = w.pausedAtMs
    val elapsedMs = ((pausedAt ?: now) - w.startMs - w.pausedDurationMs).coerceAtLeast(0L)
    val seconds = elapsedMs / 1000.0
    RecordingClock(
        text = WorkoutFormat.stopwatch(seconds, hundredths = !still, decimalMark = mark),
        paused = paused,
        spoken = stringResource(R.string.workouts_elapsed) + ", " + WorkoutFormat.clock(seconds),
    )
}

/** Finish confirms first (#517) and offers discarding there too, so a stray tap loses nothing. */
@Composable
internal fun FinishDialog(
    title: String,
    message: String?,
    finish: String,
    discard: String?,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onFinish) { Text(finish) }
                if (discard != null) {
                    TextButton(onClick = onDiscard) { Text(discard, color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.workouts_cancel)) }
            }
        },
    )
}
