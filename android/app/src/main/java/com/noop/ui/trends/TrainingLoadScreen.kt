package com.noop.ui.trends

import com.noop.ui.m3.labelStride
import com.noop.ui.m3.labelBand
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.TrainingLoadEngine
import com.noop.ui.AppViewModel
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PeriodSegmented
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.metric.MetricDateLabels
import com.noop.ui.metric.MetricHealthSeries
import com.noop.ui.metric.MetricRange
import com.noop.ui.metric.MetricWindow
import com.noop.ui.metric.monotonePath
import com.noop.ui.metric.rangeLabels
import java.time.LocalDate
import java.util.Locale

// MARK: - Training Load (twin of iOS TrainingLoadView)
//
// Trends → Training Load: the long-horizon load model (fitness 42 days, fatigue 7 days, form = fitness −
// fatigue) on a page laid out like a metric's: the W / M / 6M / Y picker, one card with today's form, the
// period's dates and the two load lines, and the latest fitness and fatigue under a hairline. Descriptive
// only: the loads are NOOP's daily Effort, never TRIMP, and feed no score.

private data class LoadRow(val date: LocalDate, val fitness: Double, val fatigue: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingLoadScreen(vm: AppViewModel, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val result = remember(days) { trainingLoad(days) }
    var rangeIndex by rememberSaveable { mutableIntStateOf(MetricRange.MONTH.ordinal) }
    val (rangeShort, rangeSpoken) = rangeLabels()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.trends_training_load), onBack = onBack, scrollBehavior = scrollBehavior)
        Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = M3Dimens.screenPadding)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeriodSegmented(rangeShort, rangeIndex, { rangeIndex = it }, contentDescriptions = rangeSpoken)
            if (result.isAvailable) {
                LoadCard(result, MetricRange.entries[rangeIndex], locale)
            } else {
                HealthCard {
                    Text(
                        stringResource(R.string.trends_tl_needs, TrainingLoadEngine.standard.minimumDays, result.contiguousDays),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadCard(result: TrainingLoadEngine.Result, range: MetricRange, locale: Locale) {
    val colors = MaterialTheme.colorScheme
    val fitnessTint = Health.colors.activity
    val fatigueTint = Health.colors.respiratory
    val firstDay = remember(locale) { MetricDateLabels.firstDayOfWeek(locale) }
    val all = remember(result) {
        result.points.mapNotNull { p -> MetricHealthSeries.date(p.day)?.let { LoadRow(it, p.chronicLoad, p.acuteLoad) } }
    }
    val anchor = all.lastOrNull()?.date ?: LocalDate.now()
    val span = MetricHealthSeries.bounds(range, anchor, firstDay)
    val rows = all.filter { it.date >= span.first && it.date < span.second }
    val latest = result.points.lastOrNull()
    HealthCard(shape = RoundedCornerShape(M3Dimens.heroRadius), contentPadding = PaddingValues(20.dp), verticalSpacing = 0.dp) {
        Text(stringResource(R.string.trends_form_caps), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurfaceVariant)
        Text(
            latest?.let { loadNumber(it.balance, locale, signed = true) } ?: "—",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
        if (rows.isNotEmpty()) {
            Text(
                MetricDateLabels.between(rows.first().date, rows.last().date, range == MetricRange.YEAR, locale),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
            )
        }
        LoadChart(rows, MetricWindow(range, span.first, span.second, anchor, emptyList(), emptyList()), firstDay, fitnessTint, fatigueTint, locale)
        HorizontalDivider(Modifier.padding(top = 16.dp), color = colors.outlineVariant)
        LegendRow(stringResource(R.string.trends_fitness_42), latest?.chronicLoad, fitnessTint, dashed = false, locale = locale, modifier = Modifier.padding(top = 12.dp))
        LegendRow(stringResource(R.string.trends_fatigue_7), latest?.acuteLoad, fatigueTint, dashed = true, locale = locale, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The two load lines on one scale: fitness solid, fatigue dashed as well as a second hue. */
@Composable
private fun LoadChart(
    rows: List<LoadRow>,
    window: MetricWindow,
    firstDay: java.time.DayOfWeek,
    fitnessTint: Color,
    fatigueTint: Color,
    locale: Locale,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val top = maxOf(rows.maxOfOrNull { maxOf(it.fitness, it.fatigue) } ?: 1.0, 1.0) * 1.1
    val ticks = MetricHealthSeries.yTicks(0.0..top, emptyList())
    val xTicks = MetricHealthSeries.xTicks(window, firstDay)
    val description = stringResource(R.string.trends_fitness_fatigue)
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(240.dp)
            .semantics { contentDescription = description },
    ) {
        val labelW = (ticks.maxOfOrNull { measurer.measure(loadAxis(it, locale), labelStyle).size.width } ?: 0) + 10.dp.toPx()
        val right = size.width - labelW
        val plotTop = 8.dp.toPx()
        val bottom = size.height - labelBand(measurer, labelStyle, 22.dp, 6.dp)
        fun y(v: Double) = (plotTop + (1 - v / top) * (bottom - plotTop)).toFloat()
        val totalDays = (window.end.toEpochDay() - window.start.toEpochDay()).coerceAtLeast(1)
        fun x(d: LocalDate, centre: Boolean = true) =
            right * ((d.toEpochDay() - window.start.toEpochDay() + if (centre) 0.5 else 0.0) / totalDays).toFloat()
        ticks.forEach { v ->
            drawLine(colors.outlineVariant, Offset(0f, y(v)), Offset(right, y(v)), strokeWidth = 1.dp.toPx())
            val l = measurer.measure(loadAxis(v, locale), labelStyle)
            drawText(l, topLeft = Offset(right + 6.dp.toPx(), y(v) - l.size.height / 2f))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
        // When the labels would touch (large text) every other one is drawn, evenly.
        val centred = window.range == MetricRange.WEEK
        val tickX = xTicks.map { x(it, centre = centred) }
        val tickLayouts = xTicks.map { measurer.measure(MetricDateLabels.xTick(it, window.range, locale), labelStyle) }
        val stride = labelStride(
            widest = tickLayouts.maxOfOrNull { it.size.width.toFloat() } ?: 0f,
            spacing = if (tickX.size > 1) (tickX.last() - tickX.first()) / (tickX.size - 1) else 0f,
            gap = 4.dp.toPx(),
        )
        tickX.forEachIndexed { i, xx ->
            drawLine(colors.outlineVariant, Offset(xx, plotTop), Offset(xx, bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            if (i % stride != 0) return@forEachIndexed
            val l = tickLayouts[i]
            val lx = (if (centred) xx - l.size.width / 2f else xx + 3.dp.toPx()).coerceIn(0f, (size.width - l.size.width).coerceAtLeast(0f))
            drawText(l, topLeft = Offset(lx, bottom + 4.dp.toPx()))
        }
        if (rows.size > 1) {
            val fit: Path = monotonePath(rows.map { Offset(x(it.date), y(it.fitness)) })
            drawPath(fit, fitnessTint, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            val fat: Path = monotonePath(rows.map { Offset(x(it.date), y(it.fatigue)) })
            drawPath(fat, fatigueTint, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
        }
    }
}

private fun loadAxis(v: Double, locale: Locale): String {
    val whole = kotlin.math.abs(v - Math.round(v)) < 1e-6
    return if (whole) Math.round(v).toString() else loadNumber(v, locale)
}

/** A legend line: a swatch drawn in the line's own stroke (solid or dashed), its name, its latest value. */
@Composable
private fun LegendRow(title: String, value: Double?, tint: Color, dashed: Boolean, locale: Locale, modifier: Modifier) {
    val text = value?.let { loadNumber(it, locale) } ?: "—"
    Row(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = listOf(title, text).joinToString(", ") }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp, 4.dp)) {
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null
            drawLine(tint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = effect)
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
    }
}
