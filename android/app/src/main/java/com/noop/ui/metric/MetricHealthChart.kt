package com.noop.ui.metric

import com.noop.ui.m3.labelStride
import com.noop.ui.m3.labelBand
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.ui.m3.Health
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// MARK: - Metric page chart (twin of iOS MetricHealthChart)
//
// The chart in a metric page's first card, drawn the way Health draws a data type: solid hairlines with
// the values on a trailing axis, dashed verticals at the date ticks, and a press-and-drag that picks one
// mark for the header to read out while the others dim. What is plotted (bars, rings, a line or bars
// either side of zero, on which scale, with or without the period's average across it) is the metric's
// own [MetricChartSpec]. TalkBack reads the chart as one description of its marks.

/** How long an x offset needs to travel before a touch is a horizontal pick rather than a page scroll. */
private const val PICK_DIRECTION_RATIO = 1.0f

@Composable
internal fun MetricHealthChart(
    window: MetricWindow,
    spec: MetricChartSpec,
    tint: Color,
    locale: Locale,
    firstDay: DayOfWeek,
    axisLabel: (Double) -> String,
    valueText: (Double) -> String,
    description: String,
    selection: MetricPoint?,
    onSelect: (MetricPoint?) -> Unit,
    modifier: Modifier = Modifier,
    segments: Map<LocalDate, String> = emptyMap(),
    height: Dp = 240.dp,
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val health = Health.colors
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val cardColor = colors.surfaceContainerLow
    val values = remember(window) { window.points.map { it.value } }
    val domain = remember(values, spec) { MetricHealthSeries.yDomain(values, spec) }
    val yTicks = remember(domain, spec) { MetricHealthSeries.yTicks(domain, spec.thresholds) }
    val xTicks = remember(window, firstDay) { MetricHealthSeries.xTicks(window, firstDay) }
    val xLabels = remember(xTicks, locale) { xTicks.map { MetricDateLabels.xTick(it, window.range, locale) } }
    val yLabels = remember(yTicks, axisLabel) { yTicks.map(axisLabel) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val touchSlop = LocalViewConfiguration.current.touchSlop

    fun barColor(v: Double): Color = when (spec.barTint) {
        MetricBarTint.CHARGE_BANDS -> if (v < 50) health.bandLow else if (v < 70) health.bandMid else health.bandHigh
        MetricBarTint.SKIN_TEMP_SIGN -> if (v >= 0) health.temperature else health.oxygen
        null -> tint
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(window) {
                // Press to read a mark; drag sideways to move along the chart. A vertical move gives the
                // touch back to the page scroll. Letting go clears the pick, as Health's charts do.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val geometry = ChartGeometry.of(
                        size.width.toFloat(), size.height.toFloat(),
                        yLabelWidth(measurer, yLabels, labelStyle, this), labelBand(measurer, labelStyle, 22.dp, 6.dp), this,
                    )
                    currentOnSelect(nearest(window, geometry.dateAt(down.position.x, window)))
                    var total = Offset.Zero
                    var picking = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        total += change.positionChange()
                        if (!picking) {
                            if (abs(total.y) > touchSlop && abs(total.y) > abs(total.x) * PICK_DIRECTION_RATIO) break
                            if (abs(total.x) > touchSlop) picking = true
                        }
                        if (picking) change.consume()
                        currentOnSelect(nearest(window, geometry.dateAt(change.position.x, window)))
                    }
                    currentOnSelect(null)
                }
            },
    ) {
        val geometry = ChartGeometry.of(
            size.width, size.height,
            yLabelWidth(measurer, yLabels, labelStyle, this), labelBand(measurer, labelStyle, 22.dp, 6.dp), this,
        )
        val plot = geometry
        fun yOf(v: Double): Float {
            val span = (domain.endInclusive - domain.start).takeIf { it > 0 } ?: 1.0
            return (plot.top + (1 - (v - domain.start) / span) * plot.plotHeight).toFloat()
        }
        val totalDays = (window.end.toEpochDay() - window.start.toEpochDay()).coerceAtLeast(1).toFloat()
        fun xOfDay(epochDay: Double): Float = plot.left + ((epochDay - window.start.toEpochDay()) / totalDays * plot.plotWidth).toFloat()
        fun centre(p: MetricPoint): Float = xOfDay((p.start.toEpochDay() + p.end.toEpochDay()) / 2.0)
        val hair = 1.dp.toPx()

        // Horizontal grid + trailing labels.
        yTicks.forEachIndexed { i, v ->
            val y = yOf(v)
            drawLine(colors.outlineVariant, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = hair)
            drawLabel(measurer, yLabels[i], labelStyle, Offset(plot.right + 6.dp.toPx(), y), LabelAnchor.START_CENTRE)
        }
        // Dashed verticals at the date ticks + labels under the plot.
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
        // When the labels would touch (large text, long weekday names) every other one is drawn, evenly.
        val centred = window.range == MetricRange.WEEK
        val tickX = xTicks.map { d -> if (centred) xOfDay(d.toEpochDay() + 0.5) else xOfDay(d.toEpochDay().toDouble()) }
        val tickLayouts = xLabels.map { measurer.measure(it, labelStyle) }
        val stride = labelStride(
            widest = tickLayouts.maxOfOrNull { it.size.width.toFloat() } ?: 0f,
            spacing = if (tickX.size > 1) (tickX.last() - tickX.first()) / (tickX.size - 1) else 0f,
            gap = 4.dp.toPx(),
        )
        tickX.forEachIndexed { i, x ->
            drawLine(colors.outlineVariant, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = hair, pathEffect = dash)
            if (i % stride != 0) return@forEachIndexed
            val layout = tickLayouts[i]
            val w = layout.size.width.toFloat()
            val left = (if (centred) x - w / 2 else x + 3.dp.toPx()).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(left, plot.bottom + 4.dp.toPx()))
        }

        // Rules: the period's average, the band thresholds, zero for a diverging chart, the picked mark.
        val ruleDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        val average = window.average
        if (spec.showsAverage && average != null && window.points.size > 1) {
            val y = yOf(average)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.7f), Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.5.dp.toPx(), pathEffect = ruleDash)
        }
        spec.thresholds.forEach { t ->
            val y = yOf(t)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.7f), Offset(plot.left, y), Offset(plot.right, y), strokeWidth = hair, pathEffect = ruleDash)
        }
        if (spec.mark == MetricMark.DIVERGING) {
            val y = yOf(0.0)
            drawLine(colors.outline, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = hair)
        }
        selection?.let { sel ->
            val x = centre(sel)
            drawLine(colors.outline.copy(alpha = 0.6f), Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = hair)
        }

        // Marks.
        val points = window.points
        val bucketWidth = if (points.isEmpty()) 0f else {
            val p = points.first()
            ((p.end.toEpochDay() - p.start.toEpochDay()) / totalDays * plot.plotWidth)
        }
        val barWidth = (bucketWidth * 0.62f).coerceAtLeast(2.dp.toPx())
        val corner = CornerRadius(minOf(3.dp.toPx(), barWidth / 2), minOf(3.dp.toPx(), barWidth / 2))
        val ringDiameter = if (points.size > 20) 8.dp.toPx() else 10.dp.toPx()
        val ringStroke = if (points.size > 20) 2.dp.toPx() else 2.5.dp.toPx()
        fun alphaFor(p: MetricPoint): Float = if (selection != null && selection != p) 0.3f else 1f

        when (spec.mark) {
            MetricMark.BARS, MetricMark.DIVERGING -> points.forEach { p ->
                val base = if (spec.mark == MetricMark.DIVERGING) yOf(0.0) else yOf(max(domain.start, 0.0))
                val top = yOf(p.value)
                val y0 = minOf(base, top)
                val h = abs(base - top).coerceAtLeast(1.dp.toPx())
                drawRoundRect(
                    barColor(p.value).copy(alpha = alphaFor(p)),
                    topLeft = Offset(centre(p) - barWidth / 2, y0),
                    size = Size(barWidth, h),
                    cornerRadius = corner,
                )
            }
            MetricMark.DOTS -> points.forEach { p ->
                drawRing(Offset(centre(p), yOf(p.value)), ringDiameter, ringStroke, tint.copy(alpha = alphaFor(p)), cardColor)
            }
            MetricMark.LINE -> {
                // One path per estimator run (VO₂max), so two methods are never joined.
                val runs = ArrayList<MutableList<Offset>>()
                var runId: String? = null
                points.forEachIndexed { i, p ->
                    val id = segments[p.start] ?: "line"
                    if (i == 0 || id != runId) runs += mutableListOf<Offset>()
                    runId = id
                    runs.last() += Offset(centre(p), yOf(p.value))
                }
                runs.forEach { run -> drawPath(monotonePath(run), tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                points.forEach { p ->
                    drawRing(Offset(centre(p), yOf(p.value)), ringDiameter, ringStroke, tint.copy(alpha = alphaFor(p)), cardColor)
                }
            }
        }
    }
}

/** A reading as Health's Vitals charts draw it: a hollow ring in the metric's hue over the card colour. */
private fun DrawScope.drawRing(centre: Offset, diameter: Float, stroke: Float, color: Color, fill: Color) {
    drawCircle(fill, radius = diameter / 2, center = centre)
    drawCircle(color, radius = diameter / 2 - stroke / 2, center = centre, style = Stroke(stroke))
}

/** A monotone cubic through [pts] (Fritsch–Carlson), so the line never overshoots a reading. */
internal fun monotonePath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    val n = pts.size
    val dx = FloatArray(n - 1) { pts[it + 1].x - pts[it].x }
    val slope = FloatArray(n - 1) { if (dx[it] == 0f) 0f else (pts[it + 1].y - pts[it].y) / dx[it] }
    val m = FloatArray(n)
    m[0] = slope[0]
    m[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / slope[i]
        val b = m[i + 1] / slope[i]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / kotlin.math.sqrt(s)
            m[i] = t * a * slope[i]
            m[i + 1] = t * b * slope[i]
        }
    }
    for (i in 0 until n - 1) {
        val h = dx[i] / 3f
        path.cubicTo(pts[i].x + h, pts[i].y + m[i] * h, pts[i + 1].x - h, pts[i + 1].y - m[i + 1] * h, pts[i + 1].x, pts[i + 1].y)
    }
    return path
}

/** The mark a touch at [date] (epoch day, fractional) picks: the one whose bucket holds it, else the nearest. */
private fun nearest(window: MetricWindow, date: Double): MetricPoint? {
    val points = window.points
    points.firstOrNull { date >= it.start.toEpochDay() && date < it.end.toEpochDay() }?.let { return it }
    return points.minByOrNull { abs(it.start.toEpochDay() - date) }
}

/** The plot rectangle inside the canvas: the trailing labels take the right, the date labels the bottom. */
private class ChartGeometry(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val plotWidth: Float get() = right - left
    val plotHeight: Float get() = bottom - top

    /** The fractional epoch day under canvas x. */
    fun dateAt(x: Float, window: MetricWindow): Double {
        val total = (window.end.toEpochDay() - window.start.toEpochDay()).coerceAtLeast(1)
        val f = ((x - left) / plotWidth).coerceIn(0f, 0.9999f)
        return window.start.toEpochDay() + f.toDouble() * total
    }

    companion object {
        /** [xLabelBand] is the height kept under the plot for the date labels, at the reader's font size. */
        fun of(width: Float, height: Float, yLabelWidth: Float, xLabelBand: Float, density: androidx.compose.ui.unit.Density): ChartGeometry =
            with(density) {
                ChartGeometry(
                    left = 0f,
                    top = 8.dp.toPx(),
                    right = width - yLabelWidth - 10.dp.toPx(),
                    bottom = height - xLabelBand,
                )
            }
    }
}

private fun yLabelWidth(measurer: TextMeasurer, labels: List<String>, style: TextStyle, density: androidx.compose.ui.unit.Density): Float =
    with(density) { max(labels.maxOfOrNull { measurer.measure(it, style).size.width.toFloat() } ?: 0f, 16.dp.toPx()) }

private enum class LabelAnchor { START_CENTRE }

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset, anchor: LabelAnchor) {
    val layout = measurer.measure(text, style)
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val topLeft = when (anchor) {
        LabelAnchor.START_CENTRE -> Offset(at.x, at.y - h / 2)
    }
    val clamped = Offset(topLeft.x.coerceIn(0f, (size.width - w).coerceAtLeast(0f)), topLeft.y.coerceIn(0f, (size.height - h).coerceAtLeast(0f)))
    drawText(layout, topLeft = clamped)
}
