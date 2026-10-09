package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Charts (pure Compose Canvas — dark, instrument-grade, no external library)
//
// Every chart is null/empty-safe: with no usable data it renders nothing but a faint baseline, so
// layouts never collapse or crash.
//
//   BarChart       — vertical bars from a zero baseline
//   TimelineChart  — time-indexed line with zoom and pan

// MARK: - Accessibility summaries
//
// Each chart primitive contributes exactly ONE semantics node via `Modifier.clearAndSetSemantics`, so the
// Compose accessibility delegate never walks a per-bar/per-band/per-point subtree (the giant semantics
// tree the a11y walk re-copied on every scroll was a contributor to the #707 OOM). The node carries a
// concise spoken summary (count + latest/low/high) — O(1) instead of O(elements).
// These are pure helpers; they change NO drawing.

/** One-line spoken summary of a numeric series: count + latest + low/high. Empty → "No data". */
private fun seriesSummary(values: List<Double>, noun: String): String {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return "$noun, no data"
    val last = clean.last()
    val lo = clean.min()
    val hi = clean.max()
    return "$noun, ${clean.size} points, latest ${formatLineValue(last)}, " +
        "low ${formatLineValue(lo)}, high ${formatLineValue(hi)}"
}

/**
 * A dashed horizontal rule at [y], for a personal-baseline reference drawn UNDER the series.
 *
 * Dashed and faint on purpose: it is a reference the readings are judged against, not a second series. A
 * solid stroke at the line's own weight would read as data.
 */
private fun DrawScope.drawReferenceRule(y: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
    )
}

/** Draw the faint zero/empty baseline used when there is nothing to plot. */
private fun DrawScope.drawBaseline(color: Color = Palette.hairline) {
    val y = size.height / 2f
    drawLine(
        color = color.copy(alpha = StrandAlpha.subtleLine),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
        cap = StrokeCap.Round,
    )
}

/** The tap/drag pinpoint label: the caller's display formatter when supplied (#463), else the raw
 *  near-integer-collapsing default. A non-blank [pointLabel] prefixes the formatted value; otherwise
 *  [epochSec] prefixes its local clock time ("14:32 · 87 bpm"). Split out so each choice is
 *  JVM-testable. */
internal fun lineChartSelectionLabel(
    value: Double,
    formatValue: ((Double) -> String)?,
    epochSec: Long? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    pointLabel: String? = null,
): String {
    val base = formatValue?.invoke(value) ?: formatLineValue(value)
    pointLabel?.takeIf { it.isNotBlank() }?.let { return "$it · $base" }
    if (epochSec == null) return base
    val time = Instant.ofEpochSecond(epochSec).atZone(zone).format(chartTickTimeFormat)
    return "$time · $base"
}

private fun formatLineValue(value: Double): String {
    if (!value.isFinite()) return "-"
    val rounded = value.roundToInt().toDouble()
    return if (abs(value - rounded) < 0.05) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

private fun nearestBarIndexForX(count: Int, width: Float, x: Float): Int {
    if (count <= 1 || width <= 0f) return 0
    val slot = width / count
    val clampedX = x.coerceIn(0f, width)
    return (clampedX / slot).toInt().coerceIn(0, count - 1)
}

// MARK: - BarChart

/**
 * Vertical bars from a zero baseline. Bars are scaled to the maximum value so the
 * tallest fills the plot height. Negative/non-finite values are treated as zero.
 * Empty data renders a faint baseline.
 */
@Composable
fun BarChart(
    values: List<Double>,
    modifier: Modifier,
    color: Color = Palette.accent,
    selectionEnabled: Boolean = false,
    // Optional per-point display labels index-aligned with [values]; when supplied, a tap shows
    // "<label> · <value>" (e.g. "16 Jul · 87") instead of the bare value — parity with LineChart (#691).
    selectionLabels: List<String>? = null,
    // The caller's own value formatter, so the scrub read-out prints what the surrounding screen prints
    // (#1662). Without one the label falls back to [formatLineValue], which shows a decimal for any
    // non-integer — so a metric whose headline is rounded answered "72.4" on tap against a "72" beside
    // it. Same parameter, same default, same fallback as LineChart's.
    formatValue: ((Double) -> String)? = null,
    // Optional personal-baseline reference, drawn as a dashed rule under the bars. Mapped on the BAR
    // scale, which is zero-based (`v / maxV`) rather than the line chart's min..max: a rule placed by the
    // line chart's arithmetic would sit at a confidently wrong height here. Null draws nothing, and so
    // does a value outside 0..maxV, for the same reason `yForValue` refuses to clamp one to an edge.
    baselineValue: Double? = null,
    // Optional metric-owned zero-based axis, e.g. 5,000 steps. Other charts retain their natural max.
    axisStep: Double? = null,
    showValueLabels: Boolean = false,
    largeSelectionReadout: Boolean = false,
) {
    val cleanValues = remember(values) { values.map { if (it.isFinite() && it > 0.0) it else 0.0 } }
    // The cleaned list flattens a non-finite value to 0.0 so it draws nothing, which is right for the
    // GEOMETRY and wrong for the read-out: a caller passing NaN for "no reading that day" would have its
    // empty slots answer "0.0" on tap, asserting a measurement that does not exist.
    //
    // The raw list decides only WHETHER a slot can be labelled, never what the label says. Cleaning also
    // flattens NEGATIVES to zero, and at least one caller (sleep debt) may pass them, so reading the raw
    // value for the number itself would quietly change what those charts report on tap.

    // cleanValues ZEROES (never drops) non-finite bars, so indices stay aligned with [values] and the
    // labels only need a size match — null when absent/mismatched so selection falls back to value-only.
    val cleanSelectionLabels = remember(values, selectionLabels) {
        if (selectionLabels == null || selectionLabels.size != values.size) null else selectionLabels
    }
    // Selection survives release, but belongs to this dataset (including its dates), not a slot.
    var selectedIndex by remember(values, cleanSelectionLabels) { mutableIntStateOf(-1) }
    var holding by remember(values, cleanSelectionLabels) { mutableStateOf(false) }
    val density = LocalDensity.current
    val axisWidth = if (axisStep != null && axisStep > 0) with(density) { 54.dp.toPx() } else 0f
    // Pre-laid value-label Paint, remembered rather than allocated inside the draw block (the old code
    // built a fresh android.graphics.Paint every draw). Keyed on color so it tracks a tint change.
    val barLabelPaint = remember(color, density, largeSelectionReadout) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = if (largeSelectionReadout) with(density) { 22.sp.toPx() } else 30f
            this.color = color.copy(alpha = StrandAlpha.chartLabel).toArgb()
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD,
            )
        }
    }
    val unselectedColor = remember(color) { color.copy(alpha = StrandAlpha.unselectedBar) }
    val axisPaint = remember(density) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Palette.textSecondary.toArgb()
        textSize = with(density) { 10.sp.toPx() }
    } }
    val numberFormat = remember { java.text.NumberFormat.getIntegerInstance() }

    // ONE collapsed semantics node so the a11y delegate reads a single bar-series summary instead of
    // walking every bar. Summarises the (zeroed-non-finite) source values the bars are scaled from.
    val axSummary = seriesSummary(cleanValues, "Bars")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = axSummary }
            .then(
                if (selectionEnabled) {
                    Modifier.pointerInput(values, cleanSelectionLabels, axisWidth) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun select(x: Float) {
                                if (cleanValues.isNotEmpty() && size.width > axisWidth) selectedIndex = nearestBarIndexForX(
                                    cleanValues.size, size.width.toFloat() - axisWidth, x - axisWidth,
                                )
                            }
                            select(down.position.x)
                            holding = true
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                    select(pointer.position.x)
                                    pointer.consume()
                                } while (pointer.pressed)
                            } finally { holding = false }
                        }
                    }
                } else {
                    Modifier
                },
            )
            // PERF (#scroll-jank): the per-bar geometry (max, slot, bar width/height, x positions) was
            // recomputed inside the Canvas draw lambda every frame. Hoist the geometry into
            // drawWithCache (keyed on cleanValues + the implicit size) into a list of bar segments; the
            // onDrawBehind just replays them — and reads selectedIndex per-frame so a tap re-tints one
            // bar without rebuilding geometry. When values can exceed the pixel width the source is
            // mean-bucket-downsampled to ~one bar per horizontal pixel first (visually identical: a 0.64×
            // bar at sub-pixel slots was an unreadable smear; the bucket mean preserves the silhouette).
            .drawWithCache {
                val w = (size.width - axisWidth).coerceAtLeast(1f)
                val h = size.height - if (axisWidth > 0) 12.dp.toPx() else 0f
                // Mean-bucket-downsample so there is at most ~one bar per horizontal pixel. Above that the
                // 0.64×-slot bars overlap into a solid block anyway, so the bucket mean is pixel-identical
                // while cutting the bar count (and the per-frame work) to the visible resolution.
                // Only downsample when selection is OFF: an interactive BarChart maps the user's tapped
                // selectedIndex against the FULL-resolution cleanValues, so collapsing the drawn bars
                // would desync the highlight + label. Interactive charts carry small bounded counts
                // (days), so they never hit this path anyway; the downsample targets dense static bars.
                val maxBars = w.toInt().coerceAtLeast(1)
                val clean = if (!selectionEnabled && cleanValues.size > maxBars && maxBars >= 1) {
                    meanBucketDownsample(cleanValues, maxBars)
                } else {
                    cleanValues
                }
                val rawMax = clean.maxOrNull() ?: 0.0
                val maxV = axisStep?.takeIf { it.isFinite() && it > 0 }?.let {
                    (kotlin.math.ceil(rawMax / it) * it).coerceAtLeast(it)
                } ?: rawMax
                if (clean.isEmpty() || maxV <= 0.0 || w <= 0f || h <= 0f) {
                    onDrawBehind { drawBaseline() }
                } else {
                    val topPad = when {
                        largeSelectionReadout -> 82.dp.toPx()
                        showValueLabels -> 22.dp.toPx()
                        else -> 4f
                    }
                    val usableH = (h - topPad).coerceAtLeast(1f)
                    val baselineY = baselineValue
                        ?.takeIf { it.isFinite() && it >= 0.0 && it <= maxV }
                        ?.let { h - ((it / maxV).toFloat().coerceIn(0f, 1f) * usableH) }
                    val slot = w / clean.size
                    val barWidth = (slot * 0.64f).coerceAtLeast(1f)
                    val barCornerRadius = minOf(2.dp.toPx(), barWidth / 4f)
                    val gridDash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                    // Precompute each bar's x centre + top y once.
                    data class BarSeg(val index: Int, val cx: Float, val top: Float)
                    val bars = ArrayList<BarSeg>(clean.size)
                    clean.forEachIndexed { i, v ->
                        val norm = (v / maxV).toFloat().coerceIn(0f, 1f)
                        val barHeight = (norm * usableH).coerceAtLeast(if (v > 0.0) 1f else 0f)
                        val cx = axisWidth + slot * i + slot / 2f
                        val top = h - barHeight
                        bars.add(BarSeg(i, cx, top))
                    }
                    onDrawBehind {
                        // Under the bars, for the same reason as the line chart's: a reference, not data.
                        if (baselineY != null) drawReferenceRule(baselineY, Palette.hairlineStrong)
                        if (axisWidth > 0 && axisStep != null) {
                            for (tick in 0..(maxV / axisStep).toInt()) {
                                val v = tick * axisStep
                                val y = h - (v / maxV).toFloat() * usableH
                                if (v > 0 && v < maxV) drawLine(
                                    Palette.textSecondary.copy(alpha = 0.45f), Offset(axisWidth, y), Offset(size.width, y),
                                    strokeWidth = 1.5.dp.toPx(), pathEffect = gridDash,
                                )
                                drawContext.canvas.nativeCanvas.drawText(numberFormat.format(v), 0f, y + 3.dp.toPx(), axisPaint)
                            }
                        }
                        bars.forEach { seg ->
                            val i = seg.index
                            if (clean[i] > 0) drawRoundRect(
                                color = when {
                                    holding && i != selectedIndex -> color.copy(alpha = 0.22f)
                                    selectionEnabled && (i == selectedIndex || largeSelectionReadout) -> color
                                    else -> unselectedColor
                                },
                                topLeft = Offset(seg.cx - barWidth / 2f, seg.top),
                                size = androidx.compose.ui.geometry.Size(barWidth, h - seg.top),
                                cornerRadius = minOf(barCornerRadius, (h - seg.top) / 4f).let {
                                    androidx.compose.ui.geometry.CornerRadius(it, it)
                                },
                            )
                            if (selectionEnabled && i == selectedIndex && values.getOrNull(i)?.isFinite() == true) drawLine(
                                color, Offset(seg.cx, seg.top), Offset(seg.cx, if (largeSelectionReadout) 62.dp.toPx() else 0f),
                                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                            )
                            if (showValueLabels && values.getOrNull(i)?.isFinite() == true) {
                                val label = numberFormat.format(clean[i])
                                val oldSize = axisPaint.textSize
                                val measured = axisPaint.measureText(label)
                                if (measured > slot - 2.dp.toPx()) axisPaint.textSize *= ((slot - 2.dp.toPx()) / measured).coerceAtLeast(0.5f)
                                drawContext.canvas.nativeCanvas.drawText(label, seg.cx - axisPaint.measureText(label) / 2, seg.top - 4.dp.toPx(), axisPaint)
                                axisPaint.textSize = oldSize
                            }
                        }
                        val readoutIndex = if (selectedIndex < 0 && largeSelectionReadout) clean.lastIndex else selectedIndex
                        val selectedRaw = values.getOrNull(readoutIndex)
                        if (selectionEnabled && readoutIndex in clean.indices &&
                            selectedRaw != null && selectedRaw.isFinite()
                        ) {
                            drawContext.canvas.nativeCanvas.apply {
                                if (largeSelectionReadout) {
                                    drawText(cleanSelectionLabels?.getOrNull(readoutIndex).orEmpty(), 0f, 22.dp.toPx(), barLabelPaint)
                                    drawText(formatValue?.invoke(clean[readoutIndex]) ?: numberFormat.format(clean[readoutIndex]), 0f, 50.dp.toPx(), barLabelPaint)
                                } else drawText(
                                    lineChartSelectionLabel(
                                        // The CLEANED value, exactly as before, so no existing caller's
                                        // label changes. The raw value only decides WHETHER to label.
                                        value = clean[readoutIndex],
                                        formatValue = formatValue,
                                        pointLabel = cleanSelectionLabels?.getOrNull(readoutIndex),
                                    ),
                                    8f, 32f, barLabelPaint,
                                )
                            }
                        }
                    }
                }
            },
    )
}

/** Mean-bucket-downsample [values] to about [target] buckets, averaging each contiguous run. Used so a
 *  BarChart with more values than horizontal pixels collapses to ~one bar per pixel without changing the
 *  visible silhouette. Returns the input unchanged when it already fits. */
private fun meanBucketDownsample(values: List<Double>, target: Int): List<Double> {
    val n = values.size
    if (target < 1 || n <= target) return values
    val out = ArrayList<Double>(target)
    for (b in 0 until target) {
        val lo = (b.toLong() * n / target).toInt()
        val hi = (((b + 1).toLong() * n / target).toInt()).coerceAtMost(n)
        if (hi <= lo) { out.add(values[lo.coerceIn(0, n - 1)]); continue }
        var sum = 0.0
        for (i in lo until hi) sum += values[i]
        out.add(sum / (hi - lo))
    }
    return out
}

// MARK: - Deep Timeline chart (#575) — time-indexed, zoom + pan
//
// A time-aware line (each point carries its own unix-second timestamp, unlike the evenly-spaced
// LineChart) over a visible [windowStart, windowEnd] window, with pinch-to-zoom + drag-to-pan via
// detectTransformGestures. The Swift twin is OverviewHRChart's zoom binding. The point COUNT stays low
// because the read layer downsamples to the zoom window (TimelinePoint list is ~targetPoints) — the
// chart never receives ~86k points. Mirrors macOS OverviewHRChart's gesture-driven x-domain.

/** One timeline sample: a unix-second timestamp + a value (bpm, °C, ms, …). */
data class TimelinePoint(val ts: Long, val value: Double)

/**
 * Pure adaptive-resolution decision shared with the macOS `Repository.timelineBucketSeconds`: the bucket
 * width (seconds) to read for a `[from, to]` window that should yield ABOUT [targetPoints] points. A
 * bucket of 1 means "read raw per-second rows". A day-scale window picks a coarse bucket (never raw); a
 * few-minute zoom drops to 1. Kept in Charts.kt so it's unit-testable without Room or a clock.
 */
fun timelineBucketSeconds(spanSeconds: Long, targetPoints: Int): Long {
    val span = spanSeconds.coerceAtLeast(1L)
    val target = targetPoints.coerceAtLeast(1)
    val ideal = span / target
    if (ideal <= 1L) return 1L
    val steps = longArrayOf(2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)
    for (s in steps) if (s >= ideal) return s
    return steps.last()
}

/**
 * Scale [base] window about [anchorFraction] (0…1) by [scale] (>1 zooms in), clamped into [bounds] and
 * floored at [minSpan] seconds. Pure — the Compose twin of OverviewHRChart.zoomed.
 */
fun zoomedWindow(
    base: LongRange,
    scale: Float,
    anchorFraction: Float,
    bounds: LongRange,
    minSpan: Long = 60L,
): LongRange {
    val span = (base.last - base.first).coerceAtLeast(1L)
    if (scale <= 0f) return base
    val pivot = base.first + (span * anchorFraction.coerceIn(0f, 1f)).toLong()
    val boundsSpan = (bounds.last - bounds.first).coerceAtLeast(minSpan)
    val newSpan = (span / scale).toLong().coerceIn(minSpan, boundsSpan)
    var newLo = pivot - ((pivot - base.first).toDouble() * newSpan / span).toLong()
    var newHi = newLo + newSpan
    if (newLo < bounds.first) { newLo = bounds.first; newHi = newLo + newSpan }
    if (newHi > bounds.last) { newHi = bounds.last; newLo = newHi - newSpan }
    newLo = newLo.coerceAtLeast(bounds.first)
    return newLo..(newLo + newSpan).coerceAtLeast(newLo + 1)
}

// MARK: - Round-time x-axis ticks (prototype hr-chart-time-axis)

/** Shared "HH:mm" tick/readout clock format — one instance, DateTimeFormatter is thread-safe. */
private val chartTickTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/**
 * Round wall-clock x-axis ticks for a `[startEpochSec, endEpochSec]` window: (epochSec, "HH:mm")
 * pairs at fixed round intervals chosen by the visible span (a full day ticks every 6h, a 1h zoom
 * every 15min). Ticks step in LOCAL wall-clock time from the window's local midnight — a window
 * crossing midnight labels "00:00" and DST labels stay round; java.time resolves the spring-forward
 * gap to a valid time and the epoch-dedupe drops the resulting double tick. Pure and clock-free
 * (ChartTimeTicksTest).
 *
 * [deepZoom] opens the sub-hour tiers (5min/2min/1min) that the Deep Timeline's pinch-to-zoom wants.
 * It is OFF by default because the Today HR card calls this with the RENDERED extent of its banked
 * buckets rather than a nominal window: a morning holding ten minutes of HR would otherwise draw ten
 * 1-minute gridlines on a small card, and the gridlines have no overlap-skip of their own.
 */
fun chartTimeTicks(
    startEpochSec: Long,
    endEpochSec: Long,
    zone: ZoneId,
    deepZoom: Boolean = false,
): List<Pair<Long, String>> {
    if (endEpochSec <= startEpochSec) return emptyList()
    val spanMinutes = (endEpochSec - startEpochSec) / 60.0
    // Thresholds sit below the nominal Today-card windows (24h/12h/6h/3h/1h) so a window whose
    // banked data covers slightly less than nominal still lands on its intended interval. The
    // deep-zoom tiers (≤30min down to 1-min steps) serve the Deep Timeline's pinch-to-zoom, so
    // a user zoomed onto a 5-minute window sees per-minute ticks instead of 15-min gaps.
    val stepMinutes = when {
        spanMinutes >= 20 * 60 -> 360L   // 6h ticks above 20h
        spanMinutes >= 10 * 60 -> 180L   // 3h ticks above 10h
        spanMinutes >= 5 * 60 -> 120L    // 2h ticks above 5h
        spanMinutes >= 2 * 60 -> 60L     // 1h ticks above 2h
        // Below 2h the static cards stop at 15min; only the zooming surface goes finer.
        !deepZoom -> 15L
        spanMinutes >= 60 -> 15L         // 15min ticks above 1h
        spanMinutes >= 30 -> 5L          // 5min ticks above 30min
        spanMinutes >= 10 -> 2L          // 2min ticks above 10min
        else -> 1L                       // 1min ticks below 10min
    }
    var tick = Instant.ofEpochSecond(startEpochSec).atZone(zone).toLocalDate().atStartOfDay()
    val out = ArrayList<Pair<Long, String>>()
    var lastEpoch = Long.MIN_VALUE
    // Bounded walk: even a multi-day window at 15-min steps stays well under the guard. A deep-zoom
    // at 1-min steps over a 10-min window is ~10 iterations, still far below it.
    var guard = 0
    while (guard++ < 4096) {
        val zoned = tick.atZone(zone)
        val epoch = zoned.toEpochSecond()
        if (epoch > endEpochSec) break
        if (epoch in startEpochSec..endEpochSec && epoch > lastEpoch) {
            out.add(epoch to zoned.format(chartTickTimeFormat))
            lastEpoch = epoch
        }
        tick = tick.plusMinutes(stepMinutes)
    }
    return out
}

/** Pan [base] by [deltaSeconds], clamped into [bounds] (span preserved). Pure — twin of OverviewHRChart.panned. */
fun pannedWindow(base: LongRange, deltaSeconds: Long, bounds: LongRange): LongRange {
    val span = base.last - base.first
    var newLo = base.first + deltaSeconds
    newLo = newLo.coerceIn(bounds.first, (bounds.last - span).coerceAtLeast(bounds.first))
    return newLo..(newLo + span)
}

/**
 * The Deep Timeline chart: a line over [points] within the visible [windowStart, windowEnd], pinch to
 * zoom + drag to pan (both clamped to [bounds]). Reports the settled window via [onWindowChange] so the
 * host can re-read at the new resolution. Empty-safe: with no points it draws a faint baseline.
 *
 * [timeTicks] (epochSec, "HH:mm") are drawn as dotted vertical gridlines under the curve, matching the
 * Today HR chart's axis convention. The matching labels render OUTSIDE this composable by the host.
 */
@Composable
fun TimelineChart(
    points: List<TimelinePoint>,
    windowStart: Long,
    windowEnd: Long,
    bounds: LongRange,
    color: Color,
    modifier: Modifier,
    onWindowChange: (LongRange) -> Unit,
    // Round wall-clock (epochSec, "HH:mm") ticks, each drawn as a dotted gridline under the curve.
    // The matching labels render OUTSIDE this plot-height composable by the host. Empty = no gridlines.
    timeTicks: List<Pair<Long, String>> = emptyList(),
) {
    val span = (windowEnd - windowStart).coerceAtLeast(1L)
    val vis = remember(points, windowStart, windowEnd) {
        points.filter { it.ts in windowStart..windowEnd && it.value.isFinite() }
    }

    // #2368: rememberUpdatedState so the gesture handler always reads the LATEST window values
    // without being recreated. The old code captured windowStart/windowEnd in the closure, but when
    // onWindowChange triggered a recomposition with new values, the gesture handler still used the
    // stale captured values, causing the graph to snap back. rememberUpdatedState gives us a stable
    // reference that always points to the current value.
    val currentWindowStart by rememberUpdatedState(windowStart)
    val currentWindowEnd by rememberUpdatedState(windowEnd)

    // ONE collapsed semantics node (summary of the VISIBLE window) so the a11y delegate reads a single
    // line instead of walking the canvas; recomputes as the zoom/pan window changes. Changes no drawing.
    val axSummary = seriesSummary(vis.map { it.value }, "Timeline")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .clearAndSetSemantics { contentDescription = axSummary }
            .pointerInput(bounds) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    var window = currentWindowStart..currentWindowEnd
                    // Pinch zooms about the gesture centroid; pan shifts the window.
                    if (zoom != 1f) {
                        val frac = (centroid.x / width).coerceIn(0f, 1f)
                        window = zoomedWindow(window, zoom, frac, bounds)
                    }
                    if (pan.x != 0f) {
                        val curSpan = window.last - window.first
                        val secPerPx = curSpan.toDouble() / width
                        window = pannedWindow(window, (-pan.x * secPerPx).toLong(), bounds)
                    }
                    onWindowChange(window)
                }
            },
    ) {
        // Dotted round-time gridlines, FIRST so the curve reads over them (matching OverviewHRChart z-order).
        if (timeTicks.isNotEmpty()) {
            val gridDash = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f) }
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                timeTicks.forEach { (ts, _) ->
                    val x = ((ts - windowStart).toFloat() / span) * size.width
                    if (x in 0f..size.width) {
                        drawLine(
                            color = Palette.hairline,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f,
                            pathEffect = gridDash,
                        )
                    }
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 2.5f
            val topPad = strokePx + 4f
            val bottomPad = strokePx + 4f
            if (vis.size < 2 || size.width <= 0f || size.height <= 0f) {
                drawBaseline()
                return@Canvas
            }
            val minV = vis.minOf { it.value }
            val maxV = vis.maxOf { it.value }
            val range = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
            val usable = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            fun px(ts: Long): Float = ((ts - windowStart).toFloat() / span) * size.width
            fun py(v: Double): Float = topPad + ((maxV - v) / range).toFloat() * usable

            val linePath = Path().apply {
                moveTo(px(vis.first().ts), py(vis.first().value))
                for (i in 1 until vis.size) lineTo(px(vis[i].ts), py(vis[i].value))
            }
            // Soft gradient fill under the curve.
            val fillPath = Path().apply {
                moveTo(px(vis.first().ts), size.height)
                lineTo(px(vis.first().ts), py(vis.first().value))
                for (i in 1 until vis.size) lineTo(px(vis[i].ts), py(vis[i].value))
                lineTo(px(vis.last().ts), size.height)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = StrandAlpha.chartFillStrong),
                        color.copy(alpha = StrandAlpha.chartFillSoft),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
            )
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
