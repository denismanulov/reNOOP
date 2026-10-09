package com.noop.ui.sleep

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.SleepDebtLedger
import com.noop.ui.AppViewModel
import com.noop.ui.ClockPrefs
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PeriodSegmented
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.labelBand
import com.noop.ui.m3.labelStride
import com.noop.ui.metric.MetricDateLabels
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// MARK: - Sleep History
//
// "W | M | 6M" over the period's average time asleep, then the chart (each night's, or for six months each
// week's average, bedtime to wake as a floating bar with its stages, time running down) with a line saying
// how to read it, the period's averages as one plain list (time asleep, time in bed, bedtime, wake-up, each
// stage), and the sleep-debt card with what it measures. A single night lives on the Sleep tab and on its own
// page, so there is no day view here.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepHistoryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf<SleepNights?>(null) }
    LaunchedEffect(days, SleepNightsLoader.revision, vm.activeStrapId) { data = SleepNightsLoader.load(vm, days) }
    val nights = data ?: SleepNights.EMPTY

    var range by rememberSaveable { mutableStateOf(SleepRange.WEEK) }
    val today = LocalDate.now()
    val shown = remember(nights, range) { SleepHistory.nightsIn(range, nights.nights, today) }
    val summary = remember(shown) { SleepPeriodSummary.of(shown) }
    val window = remember(nights, range) { SleepHistory.window(range, nights.entries, today) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(stringResource(R.string.sleep_history_title), onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item {
                PeriodSegmented(
                    options = listOf(
                        stringResource(R.string.sleep_range_w), stringResource(R.string.sleep_range_m),
                        stringResource(R.string.sleep_range_6m),
                    ),
                    selectedIndex = range.ordinal,
                    onSelect = { range = SleepRange.entries[it] },
                    contentDescriptions = listOf(
                        stringResource(R.string.sleep_range_week), stringResource(R.string.sleep_range_month),
                        stringResource(R.string.sleep_range_six_months),
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        stringResource(R.string.sleep_more_avg_asleep),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val asleep = summary.asleepMin
                    if (asleep != null && asleep > 0) BigDuration(asleep)
                    else Text(stringResource(R.string.sleep_no_data), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        periodLabel(range, window, locale),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                val stagesBySlot = if (range == SleepRange.SIX_MONTHS) emptyMap() else {
                    val byDay = nights.nights.associate { it.day to it.spans }
                    window.slotStarts.mapIndexedNotNull { slot, d -> byDay[d]?.let { slot to it } }.toMap()
                }
                HealthCard(verticalSpacing = 12.dp) {
                    SleepRangeChart(window, stagesBySlot, is24h, locale, Modifier.fillMaxWidth().height(283.dp))
                    Text(
                        stringResource(if (range == SleepRange.SIX_MONTHS) R.string.sleep_history_chart_note_weeks else R.string.sleep_history_chart_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (summary.nights > 0) {
                item { AverageRows(summary, is24h, locale) }
            }
            nights.model?.sleepDebtLedger?.takeIf { it.nights.isNotEmpty() }?.let { ledger ->
                item { SleepDebtCard(ledger, locale) }
            }
        }
    }
}

/** The period's averages, one row each: the amounts and times first, then each stage with its share. */
@Composable
private fun AverageRows(s: SleepPeriodSummary, is24h: Boolean, locale: Locale) {
    val asleep = s.asleepMin?.let { sleepDuration(it) }
    val inBed = s.inBedMin?.let { sleepDuration(it) }
    ListGroup {
        fun row(title: Int, value: String) = item { shape ->
            ListRow(shape = shape, title = stringResource(title), trailing = { ValueTrail(null, value) })
        }
        asleep?.let { row(R.string.sleep_more_avg_time_asleep, it) }
        inBed?.let { row(R.string.sleep_more_avg_time_in_bed, it) }
        s.bedtimeOfNightMin?.let { row(R.string.sleep_more_avg_bedtime, nightClock(it, is24h, locale)) }
        s.wakeOfNightMin?.let { row(R.string.sleep_more_avg_wake, nightClock(it, is24h, locale)) }
        SleepStageRow.entries.forEach { stage ->
            item { shape ->
                ListRow(
                    shape = shape,
                    title = stringResource(R.string.sleep_more_average, stageName(stage)),
                    leading = { Dot(stageColor(stage)) },
                    trailing = {
                        ValueTrail(
                            detail = s.sharePercent(stage)?.let { percent(it / 100.0, locale) },
                            value = s.stageMin[stage]?.let { sleepDuration(it) } ?: "–",
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ValueTrail(detail: String?, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
    }
}

/** The ledger's last 14 nights as diverging bars around need, with the balance at the top. */
@Composable
private fun SleepDebtCard(ledger: SleepDebtLedger, locale: Locale) {
    val nights = ledger.nights.takeLast(14)
    val peak = max(60.0, nights.maxOfOrNull { abs(it.deltaMin) } ?: 60.0)
    val surplus = Health.colors.stageCore
    val deficit = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    HealthCard(verticalSpacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sleep_more_debt), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (ledger.isDebt) sleepDuration(ledger.magnitudeMin) else stringResource(R.string.sleep_more_no_debt),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        Text(
            stringResource(R.string.sleep_explain_debt, sleepDuration(ledger.needMin)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val labels = nights.map { n -> runCatching { LocalDate.parse(n.day).dayOfMonth.toString() }.getOrDefault("") }
        // CR-3: each night's surplus or shortfall in words, so the bars are not silent.
        val spoken = nights.map { n ->
            val sign = if (n.deltaMin >= 0) "+" else "\u2212"
            "${MetricDateLabels.shortDate(n.day, locale)}: $sign${sleepDuration(abs(n.deltaMin))}"
        }.joinToString(", ")
        Canvas(Modifier.fillMaxWidth().height(80.dp).clearAndSetSemantics { contentDescription = spoken }) {
            val labelH = labelBand(measurer, labelStyle, floor = 14.dp, gap = 2.dp)
            val h = size.height - labelH
            val half = h / 2
            val slot = size.width / max(1, nights.size)
            // When the day numbers would touch (large text) every other one is drawn, counted back from
            // the latest night so that one always shows.
            val dayTexts = labels.map { measurer.measure(it, labelStyle) }
            val stride = labelStride(dayTexts.maxOfOrNull { it.size.width.toFloat() } ?: 0f, slot, 2.dp.toPx())
            drawLine(axis, Offset(0f, half), Offset(size.width, half), 1.dp.toPx())
            nights.forEachIndexed { i, n ->
                val bh = max(2.dp.toPx(), (half * min(1.0, abs(n.deltaMin) / peak)).toFloat())
                val w = min(slot * 0.6f, 10.dp.toPx())
                val x = i * slot + (slot - w) / 2
                val y = if (n.deltaMin >= 0) half - bh else half
                drawRoundRect(if (n.deltaMin >= 0) surplus else deficit, Offset(x, y), Size(w, bh), CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                if ((nights.lastIndex - i) % stride == 0) {
                    val t = dayTexts[i]
                    drawText(t, topLeft = Offset(i * slot + (slot - t.size.width) / 2, h + 2.dp.toPx()))
                }
            }
        }
    }
}

/** Week / month / 6-month chart: each slot's bedtime→wake as a floating bar, time running down. */
@Composable
private fun SleepRangeChart(
    window: SleepRangeWindow,
    stagesBySlot: Map<Int, List<SleepStageSpan>>,
    is24h: Boolean,
    locale: Locale,
    modifier: Modifier,
) {
    val colors = SleepStageRow.entries.associateWith { stageColor(it) }
    val plain = Health.colors.stageCore
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val (lo, hi) = SleepHistory.domain(window)
    val avg = window.averageAsleepMin
    // CR-3: a period with no nights says so, rather than reading out as an empty element.
    val spoken = avg?.let { stringResource(R.string.sleep_more_average, sleepDuration(it)) } ?: stringResource(R.string.sleep_no_data)
    val slotLabels = window.slotStarts.mapIndexed { slot, start -> slotLabel(window, slot, start, locale) }
    Canvas(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        val hourLabels = generateSequence(lo) { it + 120 }.takeWhile { it <= hi }.toList()
        val hourTexts = hourLabels.map { measurer.measure(nightClock(it, is24h, locale), labelStyle) }
        val axisW = (hourTexts.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx()
        val slotH = labelBand(measurer, labelStyle, floor = 18.dp)
        val w = size.width - axisW
        val h = size.height - slotH
        fun y(m: Double) = ((m - lo) / (hi - lo) * h).toFloat()
        val n = max(1, window.slotStarts.size)
        fun cx(slot: Int) = (slot + 0.5f) * w / n
        // An hour whose label would sit on the one above it is ruled but not labelled (large text).
        var hourEdge = Float.NEGATIVE_INFINITY
        hourLabels.forEachIndexed { i, m ->
            val yy = y(m)
            drawLine(grid, Offset(0f, yy), Offset(w, yy), 1.dp.toPx())
            val t = hourTexts[i]
            val top = (yy - t.size.height / 2).coerceIn(0f, (h - t.size.height).coerceAtLeast(0f))
            if (top >= hourEdge) {
                drawText(t, topLeft = Offset(w + 6.dp.toPx(), top))
                hourEdge = top + t.size.height
            }
        }
        val barW = max(3.dp.toPx(), min(22.dp.toPx(), w / n * 0.55f))
        for (bar in window.bars) {
            val x = cx(bar.slot) - barW / 2
            val top = y(bar.onsetMin)
            val bottom = y(bar.wakeMin)
            val rectH = max(barW, bottom - top)
            val spans = stagesBySlot[bar.slot]
            val radius = if (stagesBySlot.isEmpty()) barW / 2 else min(3.dp.toPx(), barW / 2)
            if (spans.isNullOrEmpty()) {
                drawRoundRect(plain, Offset(x, top), Size(barW, rectH), CornerRadius(radius, radius))
                continue
            }
            val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, top, x + barW, top + rectH, CornerRadius(radius, radius))) }
            clipPath(clip) {
                for (s in spans) {
                    val y1 = y(bar.onsetMin + s.startSec / 60)
                    val y2 = y(bar.onsetMin + s.endSec / 60)
                    drawRect(colors.getValue(s.row), Offset(x, y1), Size(barW, max(0.75f, y2 - y1)))
                }
            }
        }
        // When the slot labels would touch (large text) every other one is drawn, evenly.
        val labelled = slotLabels.mapIndexedNotNull { slot, text -> text?.let { slot to measurer.measure(it, labelStyle) } }
        val stride = labelStride(
            widest = labelled.maxOfOrNull { it.second.size.width.toFloat() } ?: 0f,
            spacing = if (labelled.size > 1) (cx(labelled.last().first) - cx(labelled.first().first)) / (labelled.size - 1) else 0f,
            gap = 4.dp.toPx(),
        )
        labelled.forEachIndexed { i, (slot, t) ->
            if (i % stride != 0) return@forEachIndexed
            val left = (cx(slot) - t.size.width / 2).coerceIn(0f, (w - t.size.width).coerceAtLeast(0f))
            drawText(t, topLeft = Offset(left, h + 3.dp.toPx()))
        }
    }
}

private fun slotLabel(window: SleepRangeWindow, slot: Int, start: LocalDate, locale: Locale): String? = when (window.range) {
    SleepRange.WEEK -> start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    SleepRange.MONTH -> if ((window.slotStarts.size - 1 - slot) % 7 == 0) start.dayOfMonth.toString() else null
    SleepRange.SIX_MONTHS -> {
        val opens = if (slot == 0) start.dayOfMonth <= 7 else start.monthValue != window.slotStarts[slot - 1].monthValue
        if (opens) start.month.getDisplayName(TextStyle.SHORT, locale) else null
    }
}

/** "20 Sep – 26 Sep 2026". */
private fun periodLabel(range: SleepRange, window: SleepRangeWindow, locale: Locale): String {
    val first = window.slotStarts.firstOrNull() ?: return ""
    val last = if (range == SleepRange.SIX_MONTHS) LocalDate.now() else window.slotStarts.last()
    return DateTimeFormatter.ofPattern("d MMM", locale).format(first) + " – " + DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(last)
}
