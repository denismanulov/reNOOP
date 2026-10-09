package com.noop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HrvAnalyzer
import com.noop.analytics.HrvAnalyzerTrace
import com.noop.analytics.SpotHrvReading
import com.noop.data.MetricSeriesRow
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Manual HRV snapshot — "Take an HRV reading" (#127). Kotlin parity twin of
 * Strand/Screens/HRVSnapshotView.swift.
 *
 * A short, deliberate seated capture: the user sits still and breathes normally while the strap's
 * live R-R intervals (the reliable 0x2A37 stream) accumulate for ~60 s. We then run the full
 * HrvAnalyzer cleaning pipeline (range filter → Malik ectopic rejection → ≥MIN_BEATS) and surface the
 * headline RMSSD plus SDNN, mean HR and the beats used. Saving banks the RMSSD as a single point in
 * the generic metric series ("hrv_snapshot", source "manual-hrv") so it sits beside every other
 * source for the explorer/trends.
 *
 * The live ingest uses the shared [rrPackets] stream; the capture buffer is uncapped (unlike
 * Breathe's rolling 30) because the analysis wants every clean beat. The window is a monotonic
 * 60-second deadline — countdown display and ingest cutoff both derive from it.
 */
@Composable
fun HrvSnapshotScreen(
    viewModel: AppViewModel,
    source: SpotHrvReading.Source = SpotHrvReading.Source.UNKNOWN,
    /** Opens Devices from the not-streaming notice. Null hides the notice's action. */
    onOpenDevices: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(HrvPhase.Idle) }
    // Every R-R interval (ms) collected during the active capture window — uncapped on purpose; the
    // analyzer wants the whole window.
    val captureBuffer = remember { mutableStateOf<List<Int>>(emptyList()) }
    var secondsRemaining by remember { mutableIntStateOf(HRV_CAPTURE_SECONDS) }
    // Monotonic start of the active capture — the single time base for the countdown display, the
    // ingest cutoff, and the finish deadline. Null outside a capture.
    var captureStart by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    // Live RMSSD over the beats gathered so far (a running indicator while capturing; the final figure
    // comes from the cleaned HrvAnalyzer.analyzeRaw).
    var runningRmssd by remember { mutableStateOf<Double?>(null) }
    // The completed analysis (null until Done).
    var result by remember { mutableStateOf<HrvAnalyzer.HrvResult?>(null) }
    // Whether the just-finished snapshot has been saved (drives the Save button → "Saved").
    var saved by remember { mutableStateOf(false) }
    // The last save did not reach the store: the button offers Try again instead of going back to Save.
    var saveFailed by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val bonded = live.bonded

    // Keep the live HR stream on for the duration of the reading (ref-counted with Live/Health/Breathe).
    DisposableEffect(Unit) {
        viewModel.requestRealtimeHr()
        onDispose { viewModel.releaseRealtimeHr() }
    }

    // Pull new R-R intervals into the capture buffer as they arrive. rrSeq-keyed: equal
    // consecutive packets both count (see LiveRrPackets.kt).
    LaunchedEffect(Unit) {
        viewModel.live
            .rrPackets()
            .collect { rr ->
                if (phase != HrvPhase.Capturing) return@collect
                // Deadline gate: intervals on or after the 60-second mark stay out.
                val start = captureStart ?: return@collect
                if (!captureWindowOpen(start.elapsedNow().inWholeMilliseconds)) return@collect
                val merged = captureBuffer.value + rr
                captureBuffer.value = merged
                runningRmssd = HrvAnalyzer.rmssdRaw(merged.map { it.toDouble() })
            }
    }

    // Capture countdown — only ticks while capturing; on the deadline, run the cleaning analysis.
    // Derived from the monotonic clock: a late resume jumps to the correct remaining value instead
    // of stretching the window (the old per-callback decrement did).
    LaunchedEffect(phase) {
        if (phase != HrvPhase.Capturing) return@LaunchedEffect
        val start = captureStart ?: return@LaunchedEffect
        while (true) {
            val elapsedMs = start.elapsedNow().inWholeMilliseconds
            secondsRemaining = remainingCaptureSeconds(elapsedMs)
            if (secondsRemaining <= 0) break
            delay(1000 - elapsedMs % 1000)   // sleep to the next whole-second boundary
        }
        // End the capture and run the full cleaning analysis over everything collected.
        val captureMs = start.elapsedNow().inWholeMilliseconds
        val raw = captureBuffer.value.map { it.toDouble() }
        // A capture whose collected beat time exceeds the wall clock it ran for held duplicated
        // beats (e.g. overlapping live sources) — refuse the number rather than publish it.
        if (HrvAnalyzer.spotCaptureOverCounted(raw.sum(), captureMs)) {
            result = HrvAnalyzer.HrvResult.empty(raw.size)
            phase = HrvPhase.Done
            return@LaunchedEffect
        }
        // HRV & Autonomic test mode (Test Centre Group G): when the mode is on, emit the cleaning trace
        // (nInput / nClean / rejected fraction, the range + Malik ectopic counts, the minBeats + spot
        // gates, RMSSD/SDNN/meanNN) tagged HRV. analyzeTrace returns the SAME HrvResult analyzeRaw would
        // (it reuses analyzeRaw verbatim), so the headline RMSSD is byte-identical with the trace on or off.
        // Zero cost when off: one SharedPreferences bool read and analyzeTrace is never called, so the plain
        // analyzeRaw path below runs untouched. Mirrors the macOS HRVSnapshotView wiring.
        result = if (com.noop.testcentre.TestCentre.from(context)
                .active(com.noop.testcentre.TestDomain.HRV)
        ) {
            val (traced, lines) = HrvAnalyzerTrace.analyzeTrace(
                raw, HrvAnalyzer.DEFAULT_SPOT_MAX_REJECTED_FRACTION, path = "spot",
            )
            for (line in lines) viewModel.ble.externalLog(line, com.noop.testcentre.TestDomain.HRV)
            traced
        } else {
            HrvAnalyzer.analyzeRaw(raw, HrvAnalyzer.DEFAULT_SPOT_MAX_REJECTED_FRACTION)
        }
        phase = HrvPhase.Done
    }

    // The hands are still for a minute: hold the screen awake through the capture.
    val view = LocalView.current
    DisposableEffect(phase == HrvPhase.Capturing) {
        if (phase == HrvPhase.Capturing) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // The minute ends with the eyes likely closed: say so by voice (TalkBack) as well as on screen.
    LaunchedEffect(phase) {
        if (phase != HrvPhase.Done) return@LaunchedEffect
        val rmssd = result?.rmssd
        view.announceForAccessibility(
            if (rmssd != null) context.getString(R.string.hrv_snapshot_a11y_result, rmssd.roundToInt())
            else context.getString(R.string.hrv_snapshot_not_enough),
        )
    }

    fun start() {
        if (!bonded) return
        captureBuffer.value = emptyList()
        secondsRemaining = HRV_CAPTURE_SECONDS
        runningRmssd = null
        result = null
        saved = false
        saveFailed = false
        captureStart = TimeSource.Monotonic.markNow()
        phase = HrvPhase.Capturing
    }

    fun cancel() {
        phase = HrvPhase.Idle
        captureStart = null
        secondsRemaining = HRV_CAPTURE_SECONDS
        runningRmssd = null
    }

    fun save(rmssd: Double) {
        val row = MetricSeriesRow(
            deviceId = HRV_SNAPSHOT_SOURCE_ID,
            day = hrvDayKey(Date()),
            key = HRV_SNAPSHOT_METRIC_KEY,
            value = rmssd,
        )
        saved = true // optimistic: the write is local and idempotent
        saveFailed = false
        scope.launch {
            runCatching { viewModel.repo.upsertMetricSeries(listOf(row)) }
                .onFailure { saved = false; saveFailed = true }
        }
    }

    if (showAbout) {
        // The same RMSSD maths the nightly HRV uses, and a source-aware caveat (a 5/MG's R-R is optical).
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.hrv_snapshot_how_measured)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.l10n_hrv_snapshot_screen_a_60_second_snapshot_of_your_35f03f7c))
                    // The wording of SpotHrvReading.caveatFor, from resources so it follows the app language
                    // (HrvSnapshotCaveatStringsTest pins the English to the analytics text).
                    Text(stringResource(R.string.hrv_snapshot_caveat))
                    if (source == SpotHrvReading.Source.OPTICAL_PPG) {
                        Text(stringResource(R.string.hrv_snapshot_caveat_optical))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.journal_done)) } },
        )
    }

    val tint = Health.colors.heart
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.l10n_hrv_snapshot_screen_close_hrv_reading_a29a99a1))
            }
            Text(
                stringResource(R.string.hrv_snapshot_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
            )
            IconButton(onClick = { showAbout = true }) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.hrv_snapshot_how_measured))
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (!bonded) {
                item(key = "notice") {
                    NoticeCard(
                        icon = Icons.Filled.SensorsOff,
                        title = stringResource(R.string.hrv_snapshot_not_streaming),
                        action = if (onOpenDevices != null) stringResource(R.string.heart_rate_open_devices) else null,
                        onAction = onOpenDevices,
                    )
                }
            }
            // Idle: a glyph and one line. Capturing and done: the ring, filling over the minute, around the RMSSD.
            item(key = "state") {
                if (phase == HrvPhase.Idle) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 12.dp).semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = tint, modifier = Modifier.size(64.dp))
                        Text(
                            stringResource(R.string.hrv_snapshot_take),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CaptureDial(
                            fraction = captureFraction(phase, secondsRemaining),
                            value = dialValue(phase, runningRmssd, result),
                            sub = if (phase == HrvPhase.Capturing) {
                                stringResource(R.string.hrv_snapshot_left, secondsRemaining, captureBuffer.value.size)
                            } else null,
                            a11y = when {
                                phase == HrvPhase.Capturing -> stringResource(R.string.hrv_snapshot_a11y_capturing, secondsRemaining, captureBuffer.value.size)
                                result?.rmssd != null -> stringResource(R.string.hrv_snapshot_a11y_result, result?.rmssd?.roundToInt() ?: 0)
                                else -> stringResource(R.string.hrv_snapshot_a11y_incomplete)
                            },
                        )
                        // How to sit while capturing, or why a finished capture has no number.
                        val line = when {
                            phase == HrvPhase.Capturing -> stringResource(R.string.hrv_snapshot_sit_still)
                            result != null && result?.rmssd == null -> stringResource(R.string.hrv_snapshot_not_enough)
                            else -> null
                        }
                        if (line != null) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val rmssd = result?.rmssd
                    if (phase == HrvPhase.Done && rmssd != null) {
                        FilledTonalButton(
                            onClick = { save(rmssd) },
                            enabled = !saved,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(
                                when {
                                    saved -> Icons.Filled.Check
                                    saveFailed -> Icons.Filled.Refresh
                                    else -> Icons.Filled.SaveAlt
                                },
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(20.dp),
                            )
                            Text(
                                stringResource(
                                    when {
                                        saved -> R.string.hrv_snapshot_saved
                                        saveFailed -> R.string.hrv_snapshot_try_again
                                        else -> R.string.hrv_snapshot_save
                                    },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Button(
                        onClick = { if (phase == HrvPhase.Capturing) cancel() else start() },
                        enabled = bonded || phase == HrvPhase.Capturing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = if (phase == HrvPhase.Capturing) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            )
                        } else ButtonDefaults.buttonColors(),
                    ) {
                        Text(
                            stringResource(
                                when (phase) {
                                    HrvPhase.Idle -> R.string.hrv_snapshot_start
                                    HrvPhase.Capturing -> R.string.hrv_snapshot_cancel
                                    HrvPhase.Done -> R.string.hrv_snapshot_again
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            val done = result
            if (phase == HrvPhase.Done && done != null && done.rmssd != null) {
                item(key = "result") { ResultCard(done) }
            }
        }
    }
}

// MARK: - Capture phase

private enum class HrvPhase { Idle, Capturing, Done }

// MARK: - Capture dial

/** The ring: a faint track, the minute's progress over it, and the RMSSD in the middle. */
@Composable
private fun CaptureDial(fraction: Float, value: String, sub: String?, a11y: String) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "hrv-dial",
    )
    val tint = Health.colors.heart
    Box(
        modifier = Modifier.size(240.dp).clearAndSetSemantics { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = tint.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 360f * animatedFraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.mind_ms) + " RMSSD",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// MARK: - Result

/** The figures beside the headline RMSSD: SDNN, the mean heart rate, and the clean beats used. */
@Composable
private fun ResultCard(result: HrvAnalyzer.HrvResult) {
    HealthCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ResultFigure(
                stringResource(R.string.l10n_hrv_snapshot_screen_sdnn_9ab9ee2a),
                formatHrv(result.sdnn, "%.0f"), stringResource(R.string.mind_ms), Modifier.weight(1f),
            )
            ResultFigure(
                stringResource(R.string.l10n_hrv_snapshot_screen_mean_hr_6c9272dd),
                formatHrv(meanHr(result.meanNN), "%.0f"), stringResource(R.string.mind_bpm), Modifier.weight(1f),
            )
            ResultFigure(
                stringResource(R.string.l10n_hrv_snapshot_screen_beats_12aafda0),
                "${result.nClean}", "", Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ResultFigure(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// MARK: - Pure view helpers (mirrors HRVSnapshotView)

/** Length of a capture in seconds. Mirrors HRVSnapshotView.captureSeconds. */
const val HRV_CAPTURE_SECONDS = 60

/** Generic metric-series key for a manual HRV reading (matches Swift `HRVSnapshot.metricKey`). */
const val HRV_SNAPSHOT_METRIC_KEY = "hrv_snapshot"

/**
 * Source id this manual reading is stored under — its own source so it sits beside WHOOP / Apple for
 * the per-source explorer (matches Swift `HRVSnapshot.sourceId`).
 */
const val HRV_SNAPSHOT_SOURCE_ID = "manual-hrv"

/**
 * Whole seconds left for a monotonic elapsed time, never negative. Mirrors
 * HRVSnapshotView.remainingSeconds.
 */
fun remainingCaptureSeconds(elapsedMs: Long): Int =
    (HRV_CAPTURE_SECONDS - elapsedMs / 1000).coerceAtLeast(0L).toInt()

/**
 * The ingest gate: intervals on or after the 60-second deadline stay out, however late the
 * countdown coroutine runs. Mirrors HRVSnapshotView.captureWindowOpen.
 */
fun captureWindowOpen(elapsedMs: Long): Boolean = elapsedMs < HRV_CAPTURE_SECONDS * 1000L

private fun captureFraction(phase: HrvPhase, secondsRemaining: Int): Float = when (phase) {
    HrvPhase.Idle -> 0f
    HrvPhase.Capturing -> (HRV_CAPTURE_SECONDS - secondsRemaining).toFloat() / HRV_CAPTURE_SECONDS.toFloat()
    HrvPhase.Done -> 1f
}

private fun dialValue(phase: HrvPhase, runningRmssd: Double?, result: HrvAnalyzer.HrvResult?): String =
    when (phase) {
        HrvPhase.Idle -> "—"
        HrvPhase.Capturing -> runningRmssd?.let { String.format(Locale.US, "%.0f", it) } ?: "…"
        HrvPhase.Done -> result?.rmssd?.let { String.format(Locale.US, "%.0f", it) } ?: "—"
    }

/** Format a nullable Double with a C-style format, em-dash for null. Shared with the tests. */
internal fun formatHrv(value: Double?, fmt: String): String =
    if (value == null) "—" else String.format(Locale.US, fmt, value)

/**
 * Mean heart rate (bpm) from the mean NN interval (ms): 60000 / meanNN. null when meanNN is missing
 * or non-positive. Mirrors HRVSnapshotView.meanHR.
 */
internal fun meanHr(meanNN: Double?): Double? =
    if (meanNN == null || meanNN <= 0) null else 60_000.0 / meanNN

/** The reading's local calendar day (yyyy-MM-dd) — the `day` of the metric store's natural key. */
private fun hrvDayKey(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(date)
