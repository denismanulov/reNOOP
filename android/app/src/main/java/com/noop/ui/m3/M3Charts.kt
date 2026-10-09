package com.noop.ui.m3

import kotlin.math.ceil
import kotlin.math.max
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// MARK: - Small charts of the redesign
//
// The Summary and All Metrics cards carry a 7-day glance on the right (iOS `SummaryMiniChart`): a line
// with an end dot for levels (HRV, resting HR, SpO₂…), capsule bars with the latest one solid for totals
// (steps, calories). Fewer than three points draws nothing — two dots are not a trend. Plus the three
// concentric score rings and a single progress ring. All pure drawing: callers pass fractions/values.

/**
 * The band a chart leaves for one line of labels in [style], in px: the text's real height at the reader's
 * font size plus [gap], never under [floor]. A band fixed in dp clips the labels, or lets them run into the
 * plot, once the font scale grows (CR-1).
 */
fun Density.labelBand(measurer: TextMeasurer, style: TextStyle, floor: Dp, gap: Dp = 4.dp): Float =
    max(floor.toPx(), measurer.measure("0", style).size.height + gap.toPx())

/**
 * Every how-many-th of a row of evenly spaced labels a chart draws so that neighbours do not touch: 1 when
 * they all fit, 2 for every other one, and so on. [widest] is the widest label, [spacing] the distance
 * between two neighbouring label positions, [gap] the clear space wanted between two labels (all px).
 * Thinning evenly keeps the row regular ("Sun · Tue · Thu · Sat") where dropping only the labels that
 * collide would not.
 */
fun labelStride(widest: Float, spacing: Float, gap: Float): Int =
    if (spacing <= 0f || widest <= 0f) 1 else ceil((widest + gap) / spacing).toInt().coerceAtLeast(1)

/** The width a chart's trailing value axis needs for [labels] in [style], in px, never under [floor]. */
fun Density.axisBand(measurer: TextMeasurer, labels: List<String>, style: TextStyle, floor: Dp, gap: Dp = 8.dp): Float =
    max(floor.toPx(), (labels.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0) + gap.toPx())

/** Fraction of [max] a value fills, clamped to 0..1; null stays null (an empty ring, not a zero one). */
fun ringFraction(value: Double?, max: Double): Float? {
    if (value == null || value.isNaN() || max <= 0.0) return null
    return (value / max).coerceIn(0.0, 1.0).toFloat()
}

/** A sparkline with an end dot. Nothing is drawn for fewer than three non-null points. */
@Composable
fun MiniLineChart(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier.size(84.dp, 32.dp),
    strokeWidth: Dp = 2.5.dp,
) {
    val points = values.withIndex().filter { it.value != null && !it.value!!.isNaN() }
    Canvas(modifier) {
        if (points.size < 3) return@Canvas
        val min = points.minOf { it.value!! }
        val max = points.maxOf { it.value!! }
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val pad = 4.dp.toPx()
        val stepX = if (values.size > 1) (size.width - pad * 2) / (values.size - 1) else 0f
        fun at(i: Int, v: Double) = Offset(
            pad + i * stepX,
            pad + ((1 - (v - min) / span) * (size.height - pad * 2)).toFloat(),
        )
        val path = Path()
        points.forEachIndexed { n, p ->
            val o = at(p.index, p.value!!)
            if (n == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.last()
        drawCircle(color, radius = 3.5.dp.toPx(), center = at(last.index, last.value!!))
    }
}

/**
 * Capsule bars, the latest solid and the rest at 40 %. Nothing is drawn for fewer than three values. A bar
 * is never wider than [maxBarWidth], so a wide, short chart spreads thin bars across its width instead of
 * drawing a row of dots.
 */
@Composable
fun MiniBarChart(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier.size(84.dp, 32.dp),
    maxBarWidth: Dp = 8.dp,
) {
    Canvas(modifier) {
        if (values.count { it != null } < 3) return@Canvas
        val max = values.maxOf { it ?: 0.0 }.takeIf { it > 0 } ?: 1.0
        val n = values.size
        val minGap = 3.dp.toPx()
        val w = ((size.width - minGap * (n - 1)) / n).coerceIn(2f, maxBarWidth.toPx())
        val step = if (n > 1) (size.width - w) / (n - 1) else 0f
        values.forEachIndexed { i, v ->
            val h = ((v ?: 0.0) / max * size.height).toFloat().coerceAtLeast(if (v != null) w else 0f)
            if (h <= 0f) return@forEachIndexed
            drawRoundRect(
                color = color.copy(alpha = if (i == n - 1) 1f else 0.4f),
                topLeft = Offset(i * step, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/**
 * A 7-column week chart for the Summary's fitness tiles: thin column rules, bars (totals) or dots
 * (levels), and a one-letter weekday under each column.
 */
@Composable
fun WeekColumnsChart(
    values: List<Double?>,
    labels: List<String>,
    color: Color,
    bars: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Canvas(modifier.then(semantics)) {
        val n = values.size.coerceAtLeast(1)
        val labelH = labelBand(measurer, labelStyle, floor = 14.dp, gap = 0.dp)
        val plotH = size.height - labelH - 2.dp.toPx()
        val colW = size.width / n
        for (i in 1 until n) {
            val x = colW * i
            drawLine(ruleColor, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1.dp.toPx())
        }
        val present = values.filterNotNull()
        if (present.isNotEmpty()) {
            val max = present.max()
            val min = if (bars) 0.0 else present.min()
            val span = (max - min).takeIf { it > 0 } ?: 1.0
            val barW = (colW * 0.36f).coerceAtMost(8.dp.toPx())
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val cx = colW * i + colW / 2
                val alpha = if (i == values.lastIndex) 1f else 0.4f
                if (bars) {
                    val h = ((v - min) / span * plotH).toFloat().coerceAtLeast(barW)
                    drawRoundRect(
                        color.copy(alpha = alpha),
                        topLeft = Offset(cx - barW / 2, plotH - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2, barW / 2),
                    )
                } else {
                    val y = plotH - 4.dp.toPx() - ((v - min) / span * (plotH - 8.dp.toPx())).toFloat()
                    drawCircle(color.copy(alpha = alpha), radius = 3.5.dp.toPx(), center = Offset(cx, y))
                }
            }
        }
        labels.take(n).forEachIndexed { i, label ->
            drawCentredLabel(measurer, label, labelStyle, Offset(colW * i + colW / 2, size.height - labelH / 2))
        }
    }
}

private fun DrawScope.drawCentredLabel(
    measurer: TextMeasurer,
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    centre: Offset,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f))
}

/**
 * The Summary's three concentric score rings, outer → inner Charge, Effort, Rest. Each fraction fills
 * clockwise from 12 o'clock over a 20 % track of its own hue; a null fraction draws the track only.
 */
@Composable
fun ActivityRings(
    charge: Float?,
    effort: Float?,
    rest: Float?,
    modifier: Modifier = Modifier.size(148.dp),
    stroke: Dp = 16.dp,
    gap: Dp = 3.dp,
    description: String? = null,
) {
    val colors = Health.colors
    val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Canvas(modifier.then(semantics)) {
        val s = stroke.toPx()
        val step = s + gap.toPx()
        listOf(charge to colors.charge, effort to colors.effort, rest to colors.rest)
            .forEachIndexed { i, (fraction, color) ->
                val inset = s / 2 + step * i
                drawRingArc(color, fraction, inset, s)
            }
    }
}

/** One progress ring with [content] centred inside it (Summary B's score dials, device battery). */
@Composable
fun ProgressRing(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier.size(104.dp),
    stroke: Dp = 8.dp,
    trackColor: Color = color.copy(alpha = 0.2f),
    description: String? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Box(modifier.then(semantics), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = stroke.toPx()
            drawRingArc(color, fraction, s / 2, s, trackColor)
        }
        content()
    }
}

private fun DrawScope.drawRingArc(
    color: Color,
    fraction: Float?,
    inset: Float,
    strokePx: Float,
    track: Color = color.copy(alpha = 0.2f),
) {
    val arcSize = Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
    val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
    drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(strokePx))
    val f = fraction ?: return
    if (f <= 0f) return
    drawArc(
        color, -90f, 360f * f, useCenter = false, topLeft = topLeft, size = arcSize,
        style = Stroke(strokePx, cap = StrokeCap.Round),
    )
}
