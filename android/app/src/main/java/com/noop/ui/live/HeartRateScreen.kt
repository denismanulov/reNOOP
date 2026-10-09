package com.noop.ui.live

import com.noop.ui.m3.labelBand
import com.noop.ui.m3.axisBand
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.SpotHrvReading
import com.noop.ble.OuraLiveSource
import com.noop.ble.WhoopModel
import com.noop.ui.ActiveWorkoutClock
import com.noop.ui.AppViewModel
import com.noop.ui.ClockPrefs
import com.noop.ui.HrvSnapshotScreen
import com.noop.ui.StartWorkoutSheet
import com.noop.ui.elapsedClock
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.rememberPoseStill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// MARK: - Heart Rate (twin of iOS LiveView, laid out as the watch's Heart Rate app)
//
// A glowing heart that beats at the live rate, "Now" over the figure in large type, then today's range as
// Health draws a heart-rate day (one min–max bar per hour) with the resting rate under it, and the button
// that starts a workout (or the workout already running). Nothing else unless something is wrong: then one
// notice says what, and opens Devices, where the strap's controls live. The ⋮ menu takes an HRV reading.
// The protocol console that used to fill this screen lives on in the Test Centre (Developer).

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun HeartRateScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenActiveWorkout: () -> Unit,
) {
    val live by vm.live.collectAsStateWithLifecycle()
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val activeIsWhoop by vm.activeIsWhoop.collectAsStateWithLifecycle()
    val activeIsOura by vm.activeIsOura.collectAsStateWithLifecycle()
    val ringPhase by vm.ouraLinkPhase.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val activeWorkout by vm.activeWorkout.collectAsStateWithLifecycle()
    val days by vm.recentDays.collectAsStateWithLifecycle()

    var day by remember { mutableStateOf<HeartRateDay?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showHrv by remember { mutableStateOf(false) }

    // One count on the realtime stream while the page is up (ref-counted in the view model).
    DisposableEffect(Unit) {
        vm.requestRealtimeHr()
        onDispose { vm.releaseRealtimeHr() }
    }
    // Gated on the active device being a WHOOP (#2075); a fresh bond refreshes the battery.
    val activeConnection = activeIsWhoop && live.connected && live.bonded
    LaunchedEffect(activeConnection) { if (activeConnection) vm.getBattery() }

    // Today's range: on arrival, whenever the cached days refresh, and once a minute while the page is up.
    LaunchedEffect(days) {
        while (isActive) {
            day = withContext(Dispatchers.IO) { runCatching { loadHeartRateDay(vm) }.getOrNull() } ?: day
            delay(60_000L)
        }
    }

    if (showStart) StartWorkoutSheet(vm = vm, onDismiss = { showStart = false })
    if (showHrv) {
        Dialog(
            onDismissRequest = { showHrv = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // A WHOOP 5/MG's R-R is optical (noisier), a WHOOP 4's electrical: the reading is told which (#537).
            val source = when (selectedModel) {
                WhoopModel.WHOOP5_MG -> SpotHrvReading.Source.OPTICAL_PPG
                WhoopModel.WHOOP4 -> SpotHrvReading.Source.CHEST_STRAP
            }
            HrvSnapshotScreen(
                viewModel = vm,
                source = source,
                onOpenDevices = { showHrv = false; onOpenDevices() },
                onClose = { showHrv = false },
            )
        }
    }

    val link = HeartRateLink(
        activeIsOura = activeIsOura,
        ringStreaming = live.connected && live.streamingLiveHR,
        connected = live.connected,
        bonded = live.bonded,
        encryptedBond = live.encryptedBond,
        worn = live.worn,
        lastSyncError = live.lastSyncError,
        batteryPct = live.batteryPct,
        charging = live.charging,
    )
    val problem = heartRateProblem(link)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(R.string.browse_heart_rate),
            onBack = onBack,
            actions = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sleep_menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.heart_rate_hrv_reading)) },
                            leadingIcon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                            onClick = { menuOpen = false; showHrv = true },
                        )
                    }
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding,
                end = M3Dimens.screenPadding,
                top = 8.dp,
                bottom = M3Dimens.bottomBarClearance + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (problem != null) {
                item(key = "problem") {
                    ProblemNotice(problem, live.lastSyncError, live.batteryPct, ringPhase, onOpenDevices)
                }
            }
            item(key = "heart") { HeartFigure(bpm = bpm, last = day?.last) }
            val d = day
            if (d != null && d.hours.isNotEmpty()) {
                item(key = "today-title") { SectionHeader(stringResource(R.string.heart_rate_today)) }
                item(key = "today") { TodayCard(d) }
            }
            item(key = "workout") {
                Box(Modifier.padding(top = 16.dp)) {
                    val w = activeWorkout
                    if (w != null) {
                        ActiveWorkoutRow(w, onOpenActiveWorkout)
                    } else {
                        Button(
                            onClick = { showStart = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Health.colors.fitness,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.heart_rate_start_workout), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Today's buckets, the latest raw sample of the last hour, and today's resting rate. */
private suspend fun loadHeartRateDay(vm: AppViewModel): HeartRateDay {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis() / 1000L
    val today = LocalDate.now(zone)
    val start = today.atStartOfDay(zone).toEpochSecond()
    val buckets = vm.repo.hrBucketsUnion(vm.activeStrapId, start, now, 300L)
    val recent = vm.repo.hrSamplesUnion(vm.activeStrapId, now - 3600, now, limit = 20_000).maxByOrNull { it.ts }
    val resting = vm.recentDays.value.firstOrNull { it.day == today.toString() }?.restingHr
    return HeartRateDay(
        hours = HeartRateDay.hours(buckets, start, zone),
        resting = resting,
        last = HeartRateDay.lastReading(recent?.bpm, recent?.ts, buckets, now),
    )
}

// MARK: - Problem notice

@Composable
private fun ProblemNotice(
    problem: HeartRateProblem,
    syncError: String?,
    batteryPct: Double?,
    ringPhase: OuraLiveSource.LinkPhase,
    onOpenDevices: () -> Unit,
) {
    val (icon: ImageVector, title: String) = when (problem) {
        HeartRateProblem.RingLink -> Icons.Filled.LinkOff to ringStatusCopy(ringPhase)
        HeartRateProblem.NotConnected -> Icons.Filled.LinkOff to stringResource(R.string.heart_rate_strap_not_connected)
        HeartRateProblem.Connecting -> Icons.Filled.MoreHoriz to stringResource(R.string.heart_rate_connecting)
        HeartRateProblem.NotFullyPaired -> Icons.Filled.Link to stringResource(R.string.heart_rate_not_fully_paired)
        HeartRateProblem.OffWrist -> Icons.Filled.PanTool to stringResource(R.string.heart_rate_off_wrist)
        HeartRateProblem.SyncError -> Icons.Filled.SyncProblem to stringResource(R.string.heart_rate_sync_stopped)
        HeartRateProblem.LowBattery -> Icons.Filled.Battery2Bar to
            stringResource(R.string.heart_rate_battery, (batteryPct ?: 0.0).let { kotlin.math.round(it).toInt() })
    }
    NoticeCard(
        icon = icon,
        title = title,
        message = if (problem == HeartRateProblem.SyncError) syncError else null,
        error = problem == HeartRateProblem.LowBattery,
        action = stringResource(R.string.heart_rate_open_devices),
        onAction = onOpenDevices,
    )
}

/** One line per ring link phase (#2305), the same copy the ring's other surfaces use. */
@Composable
private fun ringStatusCopy(phase: OuraLiveSource.LinkPhase): String = when (phase) {
    OuraLiveSource.LinkPhase.DISCONNECTED -> stringResource(R.string.l10n_live_screen_ring_not_connected_c2418ea8)
    OuraLiveSource.LinkPhase.CONNECTING -> stringResource(R.string.l10n_live_screen_connecting_to_the_ring_af2900ea)
    OuraLiveSource.LinkPhase.AUTHENTICATING -> stringResource(R.string.l10n_live_screen_connected_authenticating_fa9da040)
    OuraLiveSource.LinkPhase.AUTHENTICATED -> stringResource(R.string.l10n_live_screen_connected_waiting_for_live_heart_rate_01d216bb)
}

// MARK: - Heart and figure

/**
 * The Heart Rate app's first screen: the glowing heart, then "Now" (or when the last reading was taken)
 * over the figure in large type with a small BPM in the heart hue.
 */
@Composable
private fun HeartFigure(bpm: Int?, last: HeartRateReading?) {
    val shown = bpm ?: last?.bpm
    val label = stringResource(R.string.heart_rate_a11y_label)
    val value = shown?.let { stringResource(R.string.heart_rate_a11y_bpm, it) } ?: "–"
    Column(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = label
            stateDescription = value
        },
    ) {
        HeartGlow(bpm = if (rememberPoseStill()) null else bpm, modifier = Modifier.fillMaxWidth().height(230.dp))
        val caption = if (bpm != null || last == null) {
            stringResource(R.string.heart_rate_now)
        } else {
            android.text.format.DateUtils.getRelativeTimeSpanString(
                last.epochSec * 1000L,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        }
        Text(caption, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                shown?.toString() ?: "--",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.heart_rate_bpm_unit),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Health.colors.heart,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

/**
 * The watch's heart: a bright heart inside softer, larger copies of itself that fade into the page. Beats
 * at the live rate while a reading flows; still otherwise (and under the quiet-motion gate, which the
 * caller applies by passing null). At rest no frame loop runs.
 */
@Composable
private fun HeartGlow(bpm: Int?, modifier: Modifier = Modifier) {
    val clock = remember { HeartBeatClock(System.currentTimeMillis()) }
    var pulse by remember { mutableStateOf(0.0) }
    LaunchedEffect(bpm) {
        clock.retime(bpm, System.currentTimeMillis())
        if (bpm == null) {
            pulse = 0.0
            return@LaunchedEffect
        }
        while (isActive) {
            withFrameMillis { pulse = clock.pulse(System.currentTimeMillis()) }
        }
    }
    val tint = Health.colors.heart
    Box(modifier, contentAlignment = Alignment.Center) {
        // Concentric copies, each a step larger and fainter, as the watch draws its halo.
        for (ring in 4 downTo 1) {
            val scale = (1 + ring * 0.27f) * (1 + ring * 0.015f * pulse.toFloat())
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = tint.copy(alpha = 0.42f - ring * 0.08f),
                modifier = Modifier.size(104.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            )
        }
        val main = 1f + 0.05f * pulse.toFloat()
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(104.dp).graphicsLayer { scaleX = main; scaleY = main },
        )
    }
}

// MARK: - Today's range

/** RANGE over the figure, one min–max bar per hour, and the resting rate under a divider. */
@Composable
private fun TodayCard(day: HeartRateDay) {
    HealthCard(verticalSpacing = 0.dp) {
        Text(
            stringResource(R.string.heart_rate_range),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lo = day.low
        val hi = day.high
        if (lo != null && hi != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$lo–$hi",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.heart_rate_bpm_unit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        HourRangeChart(day.hours, Modifier.fillMaxWidth().height(170.dp).padding(top = 14.dp))
        val rhr = day.resting
        if (rhr != null) {
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.metric_title_rhr),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$rhr",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.heart_rate_bpm_unit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Hourly min–max bars over a 24-hour axis (00, 06, 12, 18), the y axis trailing. */
@Composable
private fun HourRangeChart(hours: List<HeartRateHour>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    val hourFormat = remember(locale, is24h) { DateTimeFormatter.ofPattern(if (is24h) "HH" else "h a", locale) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val tint = Health.colors.heart
    val domain = remember(hours) { heartRateYDomain(hours) }
    val ticks = remember(domain) { heartRateYTicks(domain) }
    val description = stringResource(R.string.heart_rate_chart_a11y)
    Canvas(modifier.semantics { contentDescription = description }) {
        val axisW = axisBand(measurer, ticks.map { "$it" }, labelStyle, floor = 30.dp)
        val labelH = labelBand(measurer, labelStyle, floor = 16.dp, gap = 2.dp)
        val plotW = size.width - axisW
        val plotH = size.height - labelH
        val span = (domain.endInclusive - domain.start).coerceAtLeast(1.0)
        fun y(v: Double): Float = (plotH - (v - domain.start) / span * plotH).toFloat()
        // Horizontal grid + trailing values.
        for (t in ticks) {
            val yy = y(t.toDouble())
            drawLine(grid, Offset(0f, yy), Offset(plotW, yy), strokeWidth = 0.5.dp.toPx())
            val layout = measurer.measure("$t", labelStyle)
            drawText(layout, topLeft = Offset(plotW + 6.dp.toPx(), yy - layout.size.height / 2f))
        }
        // Dashed verticals at 0, 6, 12, 18 with their hour under them.
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
        for (h in listOf(0, 6, 12, 18)) {
            val x = plotW * h / 24f
            drawLine(grid, Offset(x, 0f), Offset(x, plotH), strokeWidth = 0.5.dp.toPx(), pathEffect = dash)
            val layout = measurer.measure(hourFormat.format(LocalTime.of(h, 0)), labelStyle)
            drawText(layout, topLeft = Offset(x + 2.dp.toPx(), plotH + 2.dp.toPx()))
        }
        val barW = 6.dp.toPx()
        for (hr in hours) {
            val cx = plotW * (hr.hour + 0.5f) / 24f
            val top = y(maxOf(hr.high, hr.low + 1))
            val bottom = y(hr.low)
            drawRoundRect(
                tint,
                topLeft = Offset(cx - barW / 2, top),
                size = Size(barW, (bottom - top).coerceAtLeast(barW)),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}

// MARK: - Workout

/** The workout already running, with its clock; opens the in-exercise screen. */
@Composable
private fun ActiveWorkoutRow(w: AppViewModel.ActiveWorkout, onOpen: () -> Unit) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(w.startMs) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ActiveWorkoutClock.activeElapsedSeconds(w.startMs, w.pausedAtMs, w.pausedDurationMs, nowMs)
    val a11y = stringResource(R.string.heart_rate_view_active_workout)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(M3Dimens.cardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClickLabel = a11y, onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Health.colors.fitnessContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null, tint = Health.colors.onFitnessContainer, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(w.sport.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                elapsedClock(elapsed),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = Health.colors.fitness,
                maxLines = 1,
            )
        }
        ChevronRight()
    }
}
