package com.noop.ui.trends

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.color
import com.noop.ui.m3.metricHue
import com.noop.ui.metric.AllMetricsCatalog
import com.noop.ui.metric.MetricHealthStyle
import com.noop.ui.metric.MetricMark
import com.noop.ui.metric.MetricUnits
import com.noop.ui.metric.metricTokens
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// MARK: - Trend card (twin of iOS HealthTrendCard)
//
// One trend as Health draws it: the metric's name in its hue, one sentence ("Trending lower for 8 weeks"),
// then the whole span's readings in grey with the earlier period's average drawn across them in grey and
// the recent period's in the metric's hue, each labelled with its figure, and "18-week avg" / "8-week avg"
// underneath. The Summary shows the first three of these cards too.

/** "Trending higher for 5 days" / "Trending lower for 8 weeks". */
@Composable
fun trendSentence(trend: HealthTrend): String {
    val n = trend.recentCount
    val res = when (trend.direction to trend.unit) {
        HealthTrend.Direction.HIGHER to HealthTrend.Unit.DAY -> R.plurals.trends_higher_days
        HealthTrend.Direction.HIGHER to HealthTrend.Unit.WEEK -> R.plurals.trends_higher_weeks
        HealthTrend.Direction.LOWER to HealthTrend.Unit.DAY -> R.plurals.trends_lower_days
        else -> R.plurals.trends_lower_weeks
    }
    return pluralStringResource(res, n, n)
}

/** "14 day avg" / "8-week avg". */
@Composable
fun trendAverageLabel(n: Int, unit: HealthTrend.Unit): String =
    if (unit == HealthTrend.Unit.DAY) stringResource(R.string.trends_avg_days, n) else stringResource(R.string.trends_avg_weeks, n)

/** A trend card; [onClick] opens the metric's page. */
@Composable
fun HealthTrendCard(
    item: HealthTrendItem,
    units: MetricUnits,
    locale: Locale,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val metric = item.metric
    val trend = item.trend
    val tint = metricHue(metric.key, metric.category).color
    // The average's figure over its line: a duration keeps its units ("7 hr 6 min"), anything else is the
    // number alone, as Health labels these lines.
    fun figure(v: Double): String {
        val tokens = metricTokens(metric, v, units, locale)
        return (if (metric.unit == "min") tokens else tokens.filter { !it.isUnit }).joinToString(" ") { it.text }
    }
    val sentence = trendSentence(trend)
    val baselineLabel = trendAverageLabel(trend.baselineLength, trend.unit)
    val recentLabel = trendAverageLabel(trend.recentCount, trend.unit)
    val spoken = listOf(
        metric.title, sentence,
        listOf(baselineLabel, figure(trend.baselineAverage)).joinToString(": "),
        listOf(recentLabel, figure(trend.recentAverage)).joinToString(": "),
    ).joinToString(". ")
    HealthCard(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken }, onClick = onClick, verticalSpacing = 10.dp) {
        CardTitleRow(
            icon = AllMetricsCatalog.category(metric).icon,
            title = metric.title,
            tint = tint,
            chevron = onClick != null,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            sentence,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.clearAndSetSemantics {},
        )
        HealthTrendChart(
            trend = trend,
            mark = MetricHealthStyle.chart(metric.key, metric.unit, emptyList()).mark,
            tint = tint,
            baselineLabel = figure(trend.baselineAverage),
            recentLabel = figure(trend.recentAverage),
        )
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            Text(baselineLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f).width(8.dp))
            Text(recentLabel, style = MaterialTheme.typography.bodyMedium, color = tint)
        }
    }
}

/** The card's chart: every slot's reading in grey, the two averages as thick lines across their periods,
 *  each labelled at its outer end. */
@Composable
fun HealthTrendChart(trend: HealthTrend, mark: MetricMark, tint: Color, baselineLabel: String, recentLabel: String) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val grey = colors.onSurfaceVariant.copy(alpha = 0.35f)
    val card = colors.surfaceContainerLow
    // Room above the plot for a figure standing on its line, at the reader's font size: fixed in dp, the
    // figure of the higher line was pushed down across the line once the text grew.
    val labelRoomDp = with(LocalDensity.current) {
        max(26f, measurer.measure("0", labelStyle).size.height.toDp().value + 8f).dp
    }
    Canvas(Modifier.fillMaxWidth().height(66.dp + labelRoomDp).clearAndSetSemantics {}) {
        val values = trend.slots.filterNotNull()
        val lo0 = minOf(values.minOrNull() ?: 0.0, trend.baselineAverage, trend.recentAverage)
        val hi = maxOf(values.maxOrNull() ?: 1.0, trend.baselineAverage, trend.recentAverage)
        // Bars stand on a floor below the lowest reading so the movement shows.
        val lo = if (mark == MetricMark.BARS || mark == MetricMark.DIVERGING) lo0 - max(hi - lo0, 1e-9) * 0.5 else lo0
        val span = max(hi - lo, 1e-9)
        val count = trend.slots.size.coerceAtLeast(1)
        val slot = size.width / count
        val labelRoom = labelRoomDp.toPx()
        val plotHeight = size.height - labelRoom
        fun y(v: Double): Float = (labelRoom + plotHeight * (1 - (v - lo) / span)).toFloat()
        fun x(i: Int): Float = slot * (i + 0.5f)
        val split = slot * trend.baselineCount
        val firstIndex = trend.slots.indexOfFirst { it != null }.coerceAtLeast(0)
        val firstRecorded = slot * firstIndex
        val points = trend.slots.withIndex().mapNotNull { (i, v) -> v?.let { i to it } }

        when (mark) {
            MetricMark.BARS, MetricMark.DIVERGING -> {
                val w = min(8.dp.toPx(), slot * 0.6f)
                points.forEach { (i, v) ->
                    val top = y(v)
                    val h = max(3.dp.toPx(), size.height - top)
                    drawRoundRect(grey, Offset(x(i) - w / 2, size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
                }
            }
            MetricMark.LINE, MetricMark.DOTS -> {
                if (mark == MetricMark.LINE && points.size > 1) {
                    val path = Path()
                    points.forEachIndexed { n, (i, v) -> if (n == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                    drawPath(path, grey, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
                }
                val d = min(7.dp.toPx(), max(4.dp.toPx(), slot * 0.7f))
                points.forEach { (i, v) ->
                    drawCircle(card, d / 2, Offset(x(i), y(v)))
                    drawCircle(grey, d / 2 - 0.75.dp.toPx(), Offset(x(i), y(v)), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        val line = 4.dp.toPx()
        val yb = y(trend.baselineAverage)
        val yr = y(trend.recentAverage)
        drawRoundRect(colors.onSurfaceVariant, Offset(firstRecorded, yb - line / 2), Size(max(0f, split - slot * 0.25f - firstRecorded), line), CornerRadius(line / 2, line / 2))
        drawRoundRect(tint, Offset(split + slot * 0.25f, yr - line / 2), Size(max(0f, size.width - split - slot * 0.25f), line), CornerRadius(line / 2, line / 2))
        // Each figure stands on its line, clear above it, never across it.
        val gap = 6.dp.toPx()
        val b = measurer.measure(baselineLabel, labelStyle.copy(color = colors.onSurfaceVariant))
        drawText(b, topLeft = Offset(firstRecorded.coerceAtMost(size.width - b.size.width), (yb - gap - b.size.height).coerceAtLeast(0f)))
        val r = measurer.measure(recentLabel, labelStyle.copy(color = tint))
        drawText(r, topLeft = Offset(size.width - r.size.width, (yr - gap - r.size.height).coerceAtLeast(0f)))
    }
}
