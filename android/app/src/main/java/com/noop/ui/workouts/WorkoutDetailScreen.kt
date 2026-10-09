package com.noop.ui.workouts

import com.noop.ui.m3.labelBand
import com.noop.ui.m3.axisBand
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HeartRateRecovery
import com.noop.analytics.RouteMath
import com.noop.analytics.WorkoutSport
import com.noop.data.WorkoutRow
import com.noop.ingest.RouteExport
import com.noop.ui.AppViewModel
import com.noop.ui.ClockPrefs
import com.noop.ui.EffortScale
import com.noop.ui.ProfileStore
import com.noop.ui.RouteExportShare
import com.noop.ui.UnitFormatter
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import com.noop.ui.WorkoutEditing
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.sportIcon
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - One workout (twin of iOS WorkoutDetailView, set in Material 3)
//
// The date as the title, export (GPX / FIT, when a route was recorded) and the row's menu in the top bar; the
// activity glyph on a large green disc with the time range and where it came from; "Workout Details" as a
// two-column grid of coloured figures, then Effort; "Heart Rate" (the average, the curve, five zone rows);
// "Heart Rate Recovery"; and "Map", a still preview of the route that opens full size.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutDetailScreen(vm: AppViewModel, key: String, onBack: () -> Unit) {
    val all by vm.workouts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (all.isEmpty()) vm.loadWorkouts() }
    val row = all.firstOrNull { workoutKey(it) == key }
    if (row == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            PushedTopBar("", onBack)
        }
        return
    }
    WorkoutDetailContent(vm, row, onBack)
}

private class Figure(val label: String, val value: String, val unit: String, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutDetailContent(vm: AppViewModel, row: WorkoutRow, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val imperial = UnitPrefs.distanceSystem(context) == UnitSystem.IMPERIAL
    val effortScale = UnitPrefs.effortScale(context)
    val scope = rememberCoroutineScope()

    var hr by remember(row) { mutableStateOf<List<Pair<Long, Double>>>(emptyList()) }
    var zoneMinutes by remember(row) { mutableStateOf<List<Double>?>(null) }
    var zonesFromImport by remember(row) { mutableStateOf(false) }
    var recovery by remember(row) { mutableStateOf<HeartRateRecovery.Result?>(null) }
    var steps by remember(row) { mutableStateOf<Int?>(null) }
    LaunchedEffect(row) {
        hr = vm.workoutHrBuckets(row.startTs, row.endTs, row.source, row.deviceId).map { it.bucket to it.avgBpm }
        // Prefer the imported per-workout split; derive from the strap's raw HR only when there is none.
        val pct = parseZonePercents(row.zonesJSON)
        val durMin = activeSeconds(row) / 60.0
        if (pct != null && durMin > 0) {
            zoneMinutes = pct.map { durMin * it / 100.0 }
            zonesFromImport = true
        } else {
            zoneMinutes = vm.workoutZoneMinutes(row.startTs, row.endTs, row.source, row.deviceId)
            zonesFromImport = false
        }
        recovery = vm.workoutHeartRateRecovery(row.startTs, row.endTs, row.source, row.deviceId)
        steps = if (WorkoutSport.isOnFoot(WorkoutEditing.displaySport(row.sport))) vm.workoutSteps(row.startTs, row.endTs) else null
    }
    val route = remember(row.routePolyline) {
        row.routePolyline?.let { RouteMath.decode(it) }?.takeIf { it.size >= 2 }.orEmpty()
    }

    var menuOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkoutEditTarget?>(null) }
    val actions = rememberWorkoutRowActions(
        vm,
        open = {},
        edit = { r, copy -> editing = WorkoutEditTarget(r, copy) },
        afterDelete = onBack,
    )

    val title = DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(Instant.ofEpochSecond(row.startTs).atZone(zone))
        .replaceFirstChar { it.titlecase(locale) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title, onBack) {
            if (route.size >= 2) {
                IconButton(onClick = { exporting = true }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.workouts_export_route))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.workouts_more_options))
                }
                WorkoutRowMenu(row, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "header") { Header(row, locale, zone) }
            item(key = "details") {
                DetailsSection(row, steps, imperial, locale)
            }
            row.strain?.let { strain ->
                item(key = "effort") { EffortCard(strain, effortScale, locale) }
            }
            if (hr.size > 1 || zoneMinutes != null) {
                item(key = "hr-h") { DetailSectionTitle(stringResource(R.string.workouts_heart_rate)) }
                item(key = "hr") {
                    HealthCard(verticalSpacing = 14.dp) {
                        if (hr.size > 1) HeartRateChart(row, hr, locale, zone)
                        if (hr.size > 1 && zoneMinutes != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        zoneMinutes?.let { ZoneRows(it, zonesFromImport, profile) }
                    }
                }
            }
            recovery?.takeIf { it.after1Minute != null || it.after2Minutes != null || it.after5Minutes != null }?.let { r ->
                item(key = "hrr-h") { DetailSectionTitle(stringResource(R.string.workouts_hrr)) }
                item(key = "hrr") {
                    HealthCard {
                        Row(Modifier.fillMaxWidth()) {
                            RecoveryCell(stringResource(R.string.workouts_hrr_1), r.after1Minute, Modifier.weight(1f))
                            RecoveryCell(stringResource(R.string.workouts_hrr_2), r.after2Minutes, Modifier.weight(1f))
                            RecoveryCell(stringResource(R.string.workouts_hrr_5), r.after5Minutes, Modifier.weight(1f))
                        }
                    }
                }
            }
            if (route.size >= 2) {
                item(key = "map-h") { DetailSectionTitle(stringResource(R.string.workouts_map)) }
                item(key = "map") {
                    val label = stringResource(R.string.workouts_map_cd, sportLabel(row.sport))
                    RouteView(
                        route,
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(M3Dimens.cardRadius))
                            .clickable(role = Role.Button, onClickLabel = label) { showMap = true }
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }

    if (exporting) {
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text(stringResource(R.string.workouts_export_route)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        exporting = false
                        scope.launch { RouteExportShare.share(context, RouteExport.Format.GPX, route, row) }
                    }) { Text(stringResource(R.string.workouts_export_gpx)) }
                    TextButton(onClick = {
                        exporting = false
                        scope.launch { RouteExportShare.share(context, RouteExport.Format.FIT, route, row) }
                    }) { Text(stringResource(R.string.workouts_export_fit)) }
                    TextButton(onClick = { exporting = false }) { Text(stringResource(R.string.workouts_cancel)) }
                }
            },
        )
    }
    if (showMap) {
        FullScreenDialog(onDismiss = { showMap = false }) {
            TopAppBar(
                title = { Text(sportLabel(row.sport)) },
                navigationIcon = {
                    IconButton(onClick = { showMap = false }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.manual_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
            RouteView(route, Modifier.fillMaxSize())
        }
    }
    editing?.let { target ->
        ManualWorkoutSheet(
            target = target,
            onDismiss = { editing = null },
            onSave = { saved, replacing ->
                vm.saveManualWorkout(saved, replacing)
                editing = null
                // Saving replaces this row, so the page steps back to the list that shows the new one.
                onBack()
            },
        )
    }
}

@Composable
private fun DetailSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
    )
}

/** The large glyph, the activity, "07:10–08:02" and where it came from. */
@Composable
private fun Header(row: WorkoutRow, locale: Locale, zone: ZoneId) {
    val context = LocalContext.current
    val c = Health.colors
    val clock = remember(locale) {
        DateTimeFormatter.ofPattern(if (ClockPrefs.uses24Hour(context)) "HH:mm" else "h:mm a", locale)
    }
    val start = clock.format(Instant.ofEpochSecond(row.startTs).atZone(zone))
    val range = if (row.endTs > row.startTs) "$start–${clock.format(Instant.ofEpochSecond(row.endTs).atZone(zone))}" else start
    val origin = stringResource(
        when (WorkoutOrigin.of(row)) {
            WorkoutOrigin.WHOOP -> R.string.workouts_source_whoop
            WorkoutOrigin.APPLE_HEALTH -> R.string.workouts_source_apple
            WorkoutOrigin.HEALTH_CONNECT -> R.string.workouts_source_hc
            WorkoutOrigin.IMPORTED -> R.string.workouts_source_imported
            WorkoutOrigin.DEVICE -> R.string.workouts_source_device
        },
    )
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(c.fitnessContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(sportIcon(WorkoutEditing.displaySport(row.sport)), contentDescription = null, tint = c.fitness, modifier = Modifier.size(44.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(sportLabel(row.sport), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(range, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(origin, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The font scale from which a row of two columns becomes two rows (Android's "large text" and up). */
private const val LARGE_TEXT_SCALE = 1.3f

/** "Workout Details": the figures two to a row, in their colours. */
@Composable
private fun DetailsSection(row: WorkoutRow, steps: Int?, imperial: Boolean, locale: Locale) {
    val c = Health.colors
    val active = activeSeconds(row)
    val elapsed = (row.endTs - row.startTs).toDouble()
    val km = stringResource(if (imperial) R.string.workouts_unit_mi else R.string.workouts_unit_km)
    val figures = buildList {
        add(Figure(stringResource(R.string.workouts_workout_time), WorkoutFormat.clock(active), "", c.paused))
        if (elapsed - active >= 60) add(Figure(stringResource(R.string.workouts_elapsed_time), WorkoutFormat.clock(elapsed), "", c.paused))
        row.distanceM?.takeIf { it > 0 }?.let { m ->
            add(Figure(stringResource(R.string.workouts_distance), WorkoutFormat.distanceValue(m, imperial, locale), km, c.oxygen))
            if (active > 0) {
                WorkoutFormat.pace(active / (m / 1000.0), imperial)?.let { p ->
                    add(Figure(stringResource(R.string.workouts_avg_pace), p, "/$km", c.oxygen))
                }
            }
        }
        row.energyKcal?.takeIf { it > 0 }?.let {
            add(Figure(stringResource(R.string.workouts_active_calories), WorkoutFormat.grouped(it, locale), stringResource(R.string.metric_unit_kcal), c.activity))
        }
        val bpm = stringResource(R.string.metric_unit_bpm)
        row.avgHr?.let { add(Figure(stringResource(R.string.workouts_avg_hr), "$it", bpm, c.heart)) }
        row.maxHr?.let { add(Figure(stringResource(R.string.workouts_max_hr), "$it", bpm, c.heart)) }
        steps?.let { add(Figure(stringResource(R.string.workouts_steps), WorkoutFormat.grouped(it.toDouble(), locale), "", c.oxygen)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailSectionTitle(stringResource(R.string.workouts_details))
        HealthCard(verticalSpacing = 0.dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            // Two to a row; one to a row once the text is large, so no label is cut short (CR-1).
            val pairs = figures.chunked(if (LocalDensity.current.fontScale >= LARGE_TEXT_SCALE) 1 else 2)
            pairs.forEachIndexed { i, pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    pair.forEach { FigureCell(it, Modifier.weight(1f)) }
                    if (pair.size == 1 && figures.size > pairs.size) Spacer(Modifier.weight(1f))
                }
                if (i < pairs.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun FigureCell(f: Figure, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(f.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                f.value,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = f.color,
                maxLines = 1,
            )
            if (f.unit.isNotEmpty()) {
                Text(
                    f.unit.uppercase(locale),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = f.color,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }
    }
}

/** Effort: the value on a tinted capsule and the load word, on the user's scale (#268). */
@Composable
private fun EffortCard(strain: Double, scale: EffortScale, locale: Locale) {
    val c = Health.colors
    val shown = UnitFormatter.effortValue(strain, scale)
    val fraction = shown / (if (scale == EffortScale.WHOOP) 21.0 else 100.0)
    val word = stringResource(
        when (effortLoadIndex(fraction)) {
            0 -> R.string.workouts_load_light
            1 -> R.string.workouts_load_moderate
            2 -> R.string.workouts_load_strenuous
            3 -> R.string.workouts_load_high
            else -> R.string.workouts_load_all_out
        },
    )
    HealthCard(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(stringResource(R.string.workouts_effort), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.effort.copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    if (scale == EffortScale.WHOOP) "%.1f".format(locale, shown) else shown.roundToInt().toString(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    // On the tinted capsule the hue itself reads at 3.5:1; the text colour does at any tint.
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(word, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold), color = c.effort)
        }
    }
}

/** "Average Heart Rate" and the curve over the session, the y axis on the trailing edge, start and end below. */
@Composable
private fun HeartRateChart(row: WorkoutRow, points: List<Pair<Long, Double>>, locale: Locale, zone: ZoneId) {
    val context = LocalContext.current
    val c = Health.colors
    val values = points.map { it.second }
    val avg = row.avgHr?.toDouble() ?: values.average()
    val bpm = stringResource(R.string.metric_unit_bpm)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(stringResource(R.string.workouts_average_hr), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${avg.roundToInt()}",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.heart,
                )
                Text(
                    bpm.uppercase(locale),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.heart,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val grid = MaterialTheme.colorScheme.outlineVariant
        val lo = ((values.minOrNull() ?: 60.0) - 10).coerceAtLeast(0.0)
        val hi = (values.maxOrNull() ?: 160.0) + 10
        val x0 = row.startTs
        val x1 = maxOf(row.endTs, row.startTs + 60)
        val clock = DateTimeFormatter.ofPattern(if (ClockPrefs.uses24Hour(context)) "HH:mm" else "h:mm a", locale)
        val startLabel = clock.format(Instant.ofEpochSecond(row.startTs).atZone(zone))
        val endLabel = clock.format(Instant.ofEpochSecond(row.endTs).atZone(zone))
        // CR-3: the curve says when it ran and between which values, so it is not silent.
        val spoken = listOfNotNull(
            stringResource(R.string.workouts_heart_rate),
            "$startLabel\u2013$endLabel",
            values.takeIf { it.isNotEmpty() }?.let { "${it.min().roundToInt()}\u2013${it.max().roundToInt()} $bpm" },
        ).joinToString(", ")
        Canvas(Modifier.fillMaxWidth().height(150.dp).clearAndSetSemantics { contentDescription = spoken }) {
            val axisW = axisBand(measurer, listOf(hi.roundToInt().toString()), labelStyle, floor = 36.dp)
            val labelH = labelBand(measurer, labelStyle, floor = 16.dp, gap = 2.dp)
            val plotW = size.width - axisW
            val plotH = size.height - labelH
            fun y(v: Double) = (plotH - (v - lo) / (hi - lo) * plotH).toFloat()
            fun x(t: Long) = ((t - x0).toDouble() / (x1 - x0) * plotW).toFloat()
            val step = ((hi - lo) / 3).coerceAtLeast(1.0)
            for (k in 0..3) {
                val v = lo + step * k
                val yy = y(v)
                drawLine(grid, Offset(0f, yy), Offset(plotW, yy), strokeWidth = 1.dp.toPx())
                val layout = measurer.measure(v.roundToInt().toString(), labelStyle)
                drawText(layout, topLeft = Offset(plotW + 6.dp.toPx(), yy - layout.size.height / 2f))
            }
            val path = Path()
            points.forEachIndexed { i, (t, v) ->
                val o = Offset(x(t).coerceIn(0f, plotW), y(v))
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, c.heart, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val s = measurer.measure(startLabel, labelStyle)
            drawText(s, topLeft = Offset(0f, size.height - s.size.height))
            val e = measurer.measure(endLabel, labelStyle)
            drawText(e, topLeft = Offset(plotW - e.size.width, size.height - e.size.height))
        }
    }
}

/** Five zone rows: "Zone N" in its hue, a bar for its share, mm:ss, and the band in bpm (strap-derived only). */
@Composable
private fun ZoneRows(minutes: List<Double>, fromImport: Boolean, profile: ProfileStore) {
    val c = Health.colors
    val longest = (minutes.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val zones = remember(profile.hrMax, profile.hrZoneThresholds) { profile.hrZoneSet.zones }
    val bpm = stringResource(R.string.metric_unit_bpm)
    // At large text the fixed label columns no longer hold their words ("Zone" / "1" on two lines), so the
    // words take a line of their own and the bar the next (CR-1).
    val largeText = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        minutes.take(5).forEachIndexed { i, m ->
            val z = i + 1
            val name: @Composable (Modifier) -> Unit = { modifier ->
                Text(
                    stringResource(R.string.workouts_zone, z),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = c.zone(z),
                    modifier = modifier,
                )
            }
            val bar: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    Box(
                        Modifier
                            .fillMaxWidth((m / longest).toFloat().coerceIn(0.03f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(c.zone(z)),
                    )
                }
            }
            val time: @Composable () -> Unit = {
                Text(
                    WorkoutFormat.clock(m * 60),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val band: @Composable (Modifier) -> Unit = { modifier ->
                if (!fromImport && zones.size >= 5) {
                    Text(
                        zoneBandLabel(i, zones.map { it.lower }, zones.map { it.upper }, bpm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = modifier,
                        maxLines = 1,
                    )
                }
            }
            if (largeText) {
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        name(Modifier.weight(1f))
                        time()
                        band(Modifier)
                    }
                    bar(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    name(Modifier.width(64.dp))
                    bar(Modifier.weight(1f))
                    time()
                    band(Modifier.width(76.dp))
                }
            }
        }
    }
}

/** "1 min  −24 BPM": the drop after the session, or a dash when that minute was not covered. */
@Composable
private fun RecoveryCell(label: String, drop: Int?, modifier: Modifier) {
    val c = Health.colors
    val locale = LocalConfiguration.current.locales[0]
    val color = if (drop == null) MaterialTheme.colorScheme.onSurfaceVariant else c.heart
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                drop?.let { if (it >= 0) "−$it" else "+${-it}" } ?: "—",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = color,
            )
            if (drop != null) {
                Text(
                    stringResource(R.string.metric_unit_bpm).uppercase(locale),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }
    }
}

/** The route drawn offline (no map tiles): the line, a green start pin and a red end pin. */
@Composable
internal fun RouteView(points: List<RouteMath.LatLng>, modifier: Modifier) {
    val c = Health.colors
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val ring = MaterialTheme.colorScheme.surface
    Canvas(modifier.background(bg)) {
        val pad = 24.dp.toPx()
        val screen = RouteMath.normalizeToBox(points, size.width, size.height, pad)
        if (screen.size < 2) return@Canvas
        val path = Path().apply {
            moveTo(screen.first().first, screen.first().second)
            screen.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        drawPath(path, c.effort, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val start = Offset(screen.first().first, screen.first().second)
        val end = Offset(screen.last().first, screen.last().second)
        drawCircle(ring, 8.dp.toPx(), start)
        drawCircle(c.positive, 6.dp.toPx(), start)
        drawCircle(ring, 8.dp.toPx(), end)
        drawCircle(c.bandLow, 6.dp.toPx(), end)
    }
}
