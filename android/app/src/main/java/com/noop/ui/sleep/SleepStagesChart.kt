package com.noop.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.ClockPrefs
import com.noop.ui.Stages
import com.noop.ui.m3.Health
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// MARK: - Stages chart (twin of iOS SleepStagesChart)
//
// Health's day chart, drawn the Material way: four rows (Awake, REM, Core, Deep) named at their top-left
// and closed by a hairline rule, a solid frame at both ends with dashed hour lines between, the clock under
// the plot. Rounded blocks sit in their row; a thin connector joins consecutive stages. [compact] draws the
// blocks only, the night filling the width. A night that stored only its stage TOTALS gets one bar per stage,
// sized by its minutes, and no clock: there is no timeline to draw, and inventing one would be fiction.

/** The hue of a stage row. */
@Composable
internal fun stageColor(row: SleepStageRow): Color = when (row) {
    SleepStageRow.AWAKE -> Health.colors.stageAwake
    SleepStageRow.REM -> Health.colors.stageRem
    SleepStageRow.CORE -> Health.colors.stageCore
    SleepStageRow.DEEP -> Health.colors.stageDeep
}

/** The row's name as the chart and the lists print it. */
@Composable
internal fun stageName(row: SleepStageRow): String = stringResource(
    when (row) {
        SleepStageRow.AWAKE -> R.string.sleep_stage_awake
        SleepStageRow.REM -> R.string.sleep_stage_rem
        SleepStageRow.CORE -> R.string.sleep_stage_core
        SleepStageRow.DEEP -> R.string.sleep_stage_deep
    },
)

/** "7 h 42 min", "8 h", "25 min". */
@Composable
internal fun sleepDuration(minutes: Double): String {
    val (h, m) = durationParts(minutes)
    return when {
        h > 0 && m > 0 -> stringResource(R.string.sleep_duration_hm, h, m)
        h > 0 -> stringResource(R.string.sleep_duration_h, h)
        else -> stringResource(R.string.sleep_duration_m, m)
    }
}

/** "Awake 14 min, REM 1 h 43 min, …": what TalkBack reads for a chart. */
@Composable
internal fun stagesSummary(stages: Stages): String =
    SleepStageRow.entries.filter { stages.minutes(it) > 0 }
        .map { "${stageName(it)} ${sleepDuration(stages.minutes(it))}" }
        .joinToString(", ")

/** A clock label for an instant, in the reader's clock. [hourOnly] drops the minutes on a 12-hour clock. */
internal fun clockLabel(ts: Long, is24h: Boolean, locale: Locale, hourOnly: Boolean = false): String {
    val pattern = when {
        is24h -> "HH:mm"
        hourOnly -> "h a"
        else -> "h:mm a"
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))
}

/**
 * The stages chart. [spans] are seconds from [onsetTs]; empty means the night stored totals only and
 * [stages] draws as one bar per stage.
 */
@Composable
internal fun SleepStagesChart(
    spans: List<SleepStageSpan>,
    onsetTs: Long,
    stages: Stages,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val summary = stagesSummary(stages)
    val described = modifier.clearAndSetSemantics { contentDescription = summary }
    if (spans.isEmpty()) {
        TotalsBars(stages, compact, described)
        return
    }
    val colors = SleepStageRow.entries.associateWith { stageColor(it) }
    val names = SleepStageRow.entries.associateWith { stageName(it) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val context = LocalContext.current
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    val locale = context.resources.configuration.locales[0]
    val nameStyle = MaterialTheme.typography.labelSmall.copy(color = label, fontWeight = FontWeight.Medium)
    val clockStyle = MaterialTheme.typography.labelSmall.copy(color = label, fontWeight = FontWeight.SemiBold)
    val spanSec = max(1.0, spans.maxOf { it.endSec })

    Canvas(described) {
        if (compact) {
            drawBlocks(spans, colors, 0.0, spanSec, size.width, size.height, 0.25f, 0.75f)
            return@Canvas
        }
        // Health's axis: the left edge is the night's start rounded down to its hour, then four equal
        // sections of whole hours, as few as reach the end.
        val zone = ZoneId.systemDefault()
        val onsetZ = Instant.ofEpochSecond(onsetTs).atZone(zone)
        val hourStart = onsetZ.withMinute(0).withSecond(0).withNano(0).toEpochSecond()
        val start = (hourStart - onsetTs).toDouble()
        val hours = max(1.0, ceil((spanSec - start) / (4 * 3600.0)))
        val length = 4 * hours * 3600.0
        val clockTexts = (0..3).map { k ->
            measurer.measure(clockLabel(hourStart + (k * length / 4).toLong(), is24h, locale, hourOnly = true), clockStyle)
        }
        val clockH = clockTexts.maxOf { it.size.height }.toFloat() + 4.dp.toPx()
        val plotH = size.height - clockH
        val rowH = plotH / 4f
        val hair = 1.dp.toPx()
        // Frame and hour lines, running down through the clock.
        for (k in 0..4) {
            val x = (k / 4f * size.width).coerceIn(hair / 2, size.width - hair / 2)
            val edge = k == 0 || k == 4
            drawLine(
                grid, Offset(x, 0f), Offset(x, size.height), hair,
                pathEffect = if (edge) null else PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
            )
        }
        for (i in 1..4) {
            val y = i * rowH - hair / 2
            drawLine(grid, Offset(0f, y), Offset(size.width, y), hair)
        }
        drawBlocks(spans, colors, start, length, size.width, plotH, 0.38f, 0.85f)
        SleepStageRow.entries.forEachIndexed { i, row ->
            drawText(measurer, names.getValue(row), Offset(4.dp.toPx(), i * rowH + 2.dp.toPx()), nameStyle)
        }
        clockTexts.forEachIndexed { k, t ->
            drawText(t, topLeft = Offset(k / 4f * size.width + 3.dp.toPx(), plotH + 2.dp.toPx()))
        }
    }
}

/** The blocks of the night, each in its row's band, joined by thin connectors at each stage change. */
private fun DrawScope.drawBlocks(
    spans: List<SleepStageSpan>,
    colors: Map<SleepStageRow, Color>,
    start: Double,
    length: Double,
    width: Float,
    height: Float,
    bandTop: Float,
    bandBottom: Float,
) {
    val rowH = height / 4f
    fun x(t: Double) = ((t - start) / length * width).toFloat()
    val radius = 4.dp.toPx()
    val blockH = (bandBottom - bandTop) * rowH
    // Connectors first, under the blocks: a pale line from one block's centre to the next one's.
    spans.zipWithNext().forEach { (a, b) ->
        if (a.row == b.row) return@forEach
        val cx = x(b.startSec)
        val y1 = (a.row.ordinal + (bandTop + bandBottom) / 2) * rowH
        val y2 = (b.row.ordinal + (bandTop + bandBottom) / 2) * rowH
        val c = colors.getValue(b.row).copy(alpha = 0.3f)
        drawLine(c, Offset(cx, min(y1, y2)), Offset(cx, max(y1, y2)), 1.5.dp.toPx())
    }
    for (s in spans) {
        val x1 = x(s.startSec)
        val w = max(1f, x(s.endSec) - x1)
        val top = (s.row.ordinal + bandTop) * rowH
        val r = min(radius, min(w / 2, blockH / 2))
        drawRoundRect(colors.getValue(s.row), Offset(x1, top), Size(w, blockH), CornerRadius(r, r))
    }
}

/** A totals-only night: one bar per stage, its length the stage's minutes against the longest stage. */
@Composable
private fun TotalsBars(stages: Stages, compact: Boolean, modifier: Modifier) {
    val longest = SleepStageRow.entries.maxOf { stages.minutes(it) }.coerceAtLeast(1.0)
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        SleepStageRow.entries.forEach { row ->
            val minutes = stages.minutes(row)
            val color = stageColor(row)
            Column(Modifier.fillMaxWidth()) {
                if (!compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stageName(row), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sleepDuration(minutes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Box(Modifier.fillMaxWidth().height(if (compact) 10.dp else 14.dp).padding(vertical = 1.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(if (compact) 8.dp else 12.dp)) {
                        val w = (minutes / longest * size.width).toFloat().coerceAtLeast(if (minutes > 0) size.height else 0f)
                        val r = size.height / 2
                        drawRoundRect(color, Offset.Zero, Size(w, size.height), CornerRadius(r, r))
                    }
                }
            }
        }
    }
}
