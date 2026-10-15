package com.noop.ui.highlights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.ClockPrefs
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.color
import com.noop.ui.metric.MetricFigure
import com.noop.ui.metric.MetricHealthStyle
import com.noop.ui.metric.MetricToken
import com.noop.ui.sleep.clockLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// MARK: - Highlight cards (twin of iOS HighlightBodies)
//
// What sits under a highlight's sentence, one composable per kind of card the Health app draws, set in this
// app's Material 3 look: the tonal card, the category row, a sentence in titleMedium, a hairline, then the
// body. The metric's fixed hue carries what the card is about; everything it is read against is quiet
// (outlineVariant / onSurfaceVariant). Presentation only: every figure arrives resolved in the item.

/** A highlight as a card. [onClick] makes the whole card open its page, with a chevron. */
@Composable
fun HighlightItemCard(item: HighlightItem, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tint = item.hue.color
    HealthCard(modifier = modifier, onClick = onClick, verticalSpacing = 10.dp) {
        CardTitleRow(icon = item.category.icon, title = item.title, tint = tint, chevron = onClick != null)
        Text(item.sentence, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HighlightBodyView(item, tint)
    }
}

@Composable
fun HighlightBodyView(item: HighlightItem, tint: Color) {
    when (val body = item.body) {
        is HighlightBody.Progress -> ProgressBody(body.progress, tint, item.figure)
        is HighlightBody.SevenDay -> SevenDayBody(body.week, item.weekLabel, tint, item.figure)
        is HighlightBody.Comparison -> ComparisonBody(body.comparison, tint, item.figure)
        is HighlightBody.Range -> RangeBody(body.range, tint, body.marksEnds)
        is HighlightBody.Recovery -> RecoveryBody(body.recovery, tint)
        is HighlightBody.Consistency -> ConsistencyBody(body.consistency, tint)
    }
}

// MARK: Shared pieces

private val figureNumber: TextStyle
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)

private val figureUnit: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

/** A figure as a highlight sets one: the number large, its unit small beside it. */
@Composable
private fun Figure(tokens: List<MetricToken>, color: Color = MaterialTheme.colorScheme.onSurface, unitColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    MetricFigure(tokens, figureNumber, figureUnit, numberColor = color, unitColor = unitColor)
}

@Composable
private fun clock(ts: Long): String {
    val context = LocalContext.current
    return clockLabel(ts, ClockPrefs.uses24Hour(context), LocalConfiguration.current.locales[0])
}

private fun line(points: List<Offset>): Path = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

// MARK: Today against a usual day

@Composable
private fun ProgressBody(progress: HighlightProgress, tint: Color, figure: (Double) -> List<MetricToken>) {
    val quiet = MaterialTheme.colorScheme.outlineVariant
    val card = MaterialTheme.colorScheme.surfaceContainerLow
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Legend(stringResource(R.string.hl_label_today), figure(progress.todayTotal), tint, tint, Modifier.weight(1f))
            Legend(stringResource(R.string.hl_label_average), figure(progress.typicalTotal), muted, muted, Modifier.weight(1f))
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp).clearAndSetSemantics {}) {
            val edge = 6.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 14.dp.toPx()
            val peak = maxOf(progress.todayTotal, progress.typical.lastOrNull()?.total ?: 0.0, 1e-9)
            fun point(p: HighlightDayPoint) = Offset(
                edge + (size.width - 2 * edge) * p.fraction.toFloat(),
                (bottom - (bottom - top) * p.total / peak).toFloat(),
            )
            val now = progress.nowFraction
            val typicalNow = HighlightDayPoint(now, progress.typicalTotal)
            val before = progress.typical.filter { it.fraction < now } + typicalNow
            val after = listOf(typicalNow) + progress.typical.filter { it.fraction > now }
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(line(after.map(::point)), quiet.copy(alpha = 0.45f), style = stroke)
            drawPath(line(before.map(::point)), quiet, style = stroke)
            val todayEnd = point(progress.today.lastOrNull() ?: HighlightDayPoint(now, 0.0))
            drawLine(quiet, Offset(todayEnd.x, min(todayEnd.y, point(typicalNow).y)), Offset(todayEnd.x, bottom), 1.3.dp.toPx())
            drawPath(line(progress.today.map(::point)), tint, style = stroke)
            dot(point(typicalNow), quiet, card)
            dot(todayEnd, tint, card)
            // The day's hours along the foot: a tick at each end and at now, a dot at every hour between.
            val tickTop = bottom + 5.dp.toPx()
            for (hour in 0..24) tick(edge + (size.width - 2 * edge) * hour / 24f, tickTop, hour == 0 || hour == 24, quiet)
            tick(todayEnd.x, tickTop, true, quiet)
        }
        ProgressAxis(progress.nowFraction)
    }
}

@Composable
private fun Legend(title: String, tokens: List<MetricToken>, dot: Color, color: Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(9.dp).background(dot, CircleShape))
            Text(title, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        }
        Figure(tokens, color, color)
    }
}

private fun DrawScope.dot(center: Offset, fill: Color, ring: Color) {
    drawCircle(ring, 5.5.dp.toPx(), center)
    drawCircle(fill, 3.5.dp.toPx(), center)
}

private fun DrawScope.tick(x: Float, top: Float, tall: Boolean, color: Color) {
    if (tall) drawRoundRect(color, Offset(x - 1.dp.toPx(), top), Size(2.dp.toPx(), 5.dp.toPx()), CornerRadius(1.dp.toPx()))
    else drawCircle(color, 0.8.dp.toPx(), Offset(x, top + 2.5.dp.toPx()))
}

/** Midnight, now and midnight under their ticks; a midnight the now label would run into is left out. */
@Composable
private fun ProgressAxis(nowFraction: Double) {
    val zone = ZoneId.systemDefault()
    val midnight = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val midnightText = clock(midnight)
    val nowText = clock(midnight + (nowFraction * 86_400).toLong())
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
        val x = maxWidth * nowFraction.toFloat()
        if (x > 84.dp) Text(midnightText, style = style, color = color, modifier = Modifier.align(Alignment.CenterStart))
        if (x < maxWidth - 84.dp) Text(midnightText, style = style, color = color, modifier = Modifier.align(Alignment.CenterEnd))
        Text(
            nowText, style = style, color = color, maxLines = 1,
            modifier = Modifier.width(64.dp).offset(x = (x - 32.dp).coerceIn(0.dp, maxWidth - 64.dp)),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// MARK: Seven days

@Composable
private fun SevenDayBody(week: HighlightSevenDay, label: String, tint: Color, figure: (Double) -> List<MetricToken>) {
    val quiet = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val letterStyle = MaterialTheme.typography.labelSmall.copy(color = muted, fontWeight = FontWeight.SemiBold)
    val letters = remember(week.days, locale) {
        week.days.map { it.dayOfWeek.getDisplayName(DateTextStyle.NARROW_STANDALONE, locale).uppercase(locale) }
    }
    // The top of the scale: the tallest day, and never so near the average that its label has no room.
    val peak = maxOf(week.values.filterNotNull().maxOrNull() ?: 0.0, week.average * 1.16, 1e-9)
    Row(Modifier.fillMaxWidth().height(170.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(0.42f).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Figure(figure(week.average), tint, tint)
        }
        Canvas(Modifier.weight(0.58f).fillMaxWidth().height(170.dp).clearAndSetSemantics {}) {
            val bottom = size.height - 18.dp.toPx()
            val plot = bottom - 8.dp.toPx()
            val slot = size.width / week.values.size
            val barWidth = min(14.dp.toPx(), slot * 0.6f)
            week.values.forEachIndexed { index, value ->
                val x = slot * index + (slot - barWidth) / 2
                if (value != null && value > 0) {
                    val h = max(3.dp.toPx(), (plot * value / peak).toFloat())
                    drawRoundRect(quiet, Offset(x, bottom - h), Size(barWidth, h), CornerRadius(3.dp.toPx()))
                }
                val text = measurer.measure(letters[index], letterStyle)
                drawText(text, topLeft = Offset(x + barWidth / 2 - text.size.width / 2, bottom + 3.dp.toPx()))
            }
            // The average across the bars, in the card's hue.
            val y = (bottom - plot * week.average / peak).toFloat()
            drawLine(tint, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

// MARK: This period against the last

/**
 * A figure over a bar that carries its name, as long as its share of the longer one: Health's way of setting
 * two figures side by side. The bar in [tint] is the one the card is about; null draws the quiet one.
 */
@Composable
fun HighlightBarRow(tokens: List<MetricToken>, name: String, share: Double, tint: Color?) {
    val fill = tint ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val onFill = if (tint == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerLow
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Figure(tokens)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .widthIn(min = 28.dp)
                    .width(maxWidth * share.coerceIn(0.0, 1.0).toFloat())
                    .heightIn(min = 28.dp)
                    .background(fill, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge, color = onFill, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ComparisonBody(comparison: HighlightComparison, tint: Color, figure: (Double) -> List<MetricToken>) {
    val locale = LocalConfiguration.current.locales[0]
    val peak = maxOf(comparison.current, comparison.previous, 1e-9)
    fun name(start: LocalDate): String = when (comparison.period) {
        HighlightPeriod.YEAR -> start.year.toString()
        HighlightPeriod.MONTH -> start.month.getDisplayName(DateTextStyle.FULL_STANDALONE, locale)
            .replaceFirstChar { it.titlecase(locale) }
    }
    Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HighlightBarRow(figure(comparison.current), name(comparison.currentStart), comparison.current / peak, tint)
        HighlightBarRow(figure(comparison.previous), name(comparison.previousStart), comparison.previous / peak, null)
    }
}

// MARK: A range over time

@Composable
private fun RangeBody(range: HighlightRange, tint: Color, marksEnds: Boolean) {
    val quiet = MaterialTheme.colorScheme.outlineVariant
    val bead = MaterialTheme.colorScheme.surfaceContainerLow
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = tint, fontWeight = FontWeight.SemiBold)
    val figureStyle = MaterialTheme.typography.titleLarge.copy(color = tint, fontWeight = FontWeight.SemiBold)
    val maxLabel = stringResource(R.string.hl_label_max).uppercase(locale)
    val minLabel = stringResource(R.string.hl_label_min).uppercase(locale)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(210.dp).clearAndSetSemantics {}) {
            val inset = 22.dp.toPx()
            val plotTop = 46.dp.toPx()
            val plotHeight = size.height - plotTop - 54.dp.toPx()
            val capsule = 9.dp.toPx()
            val count = range.slices.size
            val step = if (count > 1) (size.width - 2 * inset) / (count - 1) else 0f
            val span = max(range.maximum - range.minimum, 1e-9)
            fun x(index: Int) = inset + step * index
            fun y(value: Double) = (plotTop + plotHeight * (range.maximum - value) / span).toFloat()
            range.slices.forEachIndexed { index, slice ->
                if (slice == null) return@forEachIndexed
                // A slice with one reading is still a bead as tall as it is wide.
                val mid = (y(slice.high) + y(slice.low)) / 2
                val height = max(capsule, y(slice.low) - y(slice.high))
                val top = min(max(mid - height / 2, plotTop), plotTop + plotHeight - height)
                drawRoundRect(tint, Offset(x(index) - capsule / 2, top), Size(capsule, height), CornerRadius(capsule / 2))
                if (marksEnds) {
                    val r = 2.4.dp.toPx()
                    val ring = Stroke(1.6.dp.toPx())
                    if (index == range.maximumIndex) drawCircle(bead, r, Offset(x(index), top + capsule / 2), style = ring)
                    if (index == range.minimumIndex) drawCircle(bead, r, Offset(x(index), top + height - capsule / 2), style = ring)
                }
            }
            fun mark(label: String, value: Double, cx: Float, anchorY: Float, above: Boolean) {
                val name = measurer.measure(label, labelStyle)
                val figure = measurer.measure(MetricHealthStyle.number(value, 0, locale), figureStyle)
                val half = max(name.size.width, figure.size.width) / 2f
                val x = cx.coerceIn(half, size.width - half)
                val top = if (above) anchorY - name.size.height - figure.size.height else anchorY
                drawText(name, topLeft = Offset(x - name.size.width / 2, top))
                drawText(figure, topLeft = Offset(x - figure.size.width / 2, top + name.size.height - 2.dp.toPx()))
            }
            mark(maxLabel, range.maximum, x(range.maximumIndex), plotTop - 4.dp.toPx(), above = true)
            mark(minLabel, range.minimum, x(range.minimumIndex), plotTop + plotHeight + 4.dp.toPx(), above = false)
            val tickTop = size.height - 7.dp.toPx()
            for (index in 0 until count) {
                val end = index == 0 || index == count - 1
                if (end || index % 2 == 1) tick(x(index), tickTop, end, quiet)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            val style = MaterialTheme.typography.labelMedium
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Text(clock(range.start), style = style, color = color)
            Spacer(Modifier.weight(1f))
            Text(clock(range.end), style = style, color = color)
        }
    }
}

// MARK: After a workout

@Composable
private fun RecoveryBody(recovery: HighlightRecovery, tint: Color) {
    val quiet = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val unit = stringResource(R.string.metric_unit_bpm).uppercase(LocalConfiguration.current.locales[0])
    val columns = listOf(
        recovery.atEnd to clock(recovery.end),
        recovery.afterOneMinute to stringResource(R.string.hl_label_plus1),
        recovery.afterTwoMinutes to stringResource(R.string.hl_label_plus2),
        null to stringResource(R.string.hl_label_plus3),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp)) {
        val trailing = 70.dp
        val pitch = (maxWidth - trailing) / 3
        Canvas(Modifier.fillMaxWidth().height(220.dp).clearAndSetSemantics {}) {
            val p = pitch.toPx()
            for (index in 0..3) drawLine(quiet, Offset(p * index + 1, 0f), Offset(p * index + 1, size.height), 1.dp.toPx())
            val high = maxOf(recovery.readings.maxOfOrNull { it.bpm } ?: 0.0, recovery.atEnd.toDouble())
            val low = minOf(recovery.readings.minOfOrNull { it.bpm } ?: 0.0, recovery.afterThreeMinutes.toDouble())
            val span = max(high - low, 1e-9)
            val plotTop = 14.dp.toPx()
            val plot = size.height * 0.68f
            fun y(bpm: Double) = (plotTop + plot * (high - bpm) / span).toFloat()
            for (reading in recovery.readings) {
                drawCircle(tint, 3.3.dp.toPx(), Offset(1 + p * (reading.seconds / 60).toFloat(), y(reading.bpm)))
            }
            for ((bpm, at) in listOf(high to 6.dp.toPx(), low to y(low))) {
                val text = measurer.measure("${bpm.toInt()} $unit", axisStyle)
                drawText(text, topLeft = Offset(p * 3 + 6.dp.toPx(), at - text.size.height / 2))
            }
        }
        columns.forEachIndexed { index, (value, caption) ->
            Column(
                Modifier
                    .width(if (index == 3) trailing - 8.dp else pitch - 8.dp)
                    .offset(x = pitch * index + 8.dp)
                    .align(Alignment.BottomStart)
                    .semantics(mergeDescendants = true) {},
            ) {
                if (value != null) Text("$value", style = figureNumber, color = tint, maxLines = 1)
                Text(caption, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
            }
        }
    }
}

// MARK: Days that had one

@Composable
private fun ConsistencyBody(consistency: HighlightConsistency, tint: Color) {
    val locale = LocalConfiguration.current.locales[0]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val weeks = consistency.days.chunked(7)
    Column(Modifier.clearAndSetSemantics { contentDescription = "${consistency.count}" }) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            weeks.firstOrNull().orEmpty().forEachIndexed { index, day ->
                if (index > 0) Spacer(Modifier.weight(1f))
                Text(
                    day.date.dayOfWeek.getDisplayName(DateTextStyle.SHORT_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) },
                    style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1,
                    modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(6.dp))
        weeks.forEachIndexed { weekIndex, week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEachIndexed { index, day ->
                    if (index > 0) Spacer(Modifier.weight(1f))
                    DayCell(day, names = weekIndex == 0 && index == 0, isToday = day.date == consistency.today, tint = tint, locale = locale)
                }
            }
        }
    }
}

/** A day's circle, with its month named over it when the month starts there (or the grid does). */
@Composable
private fun DayCell(day: HighlightConsistency.Day, names: Boolean, isToday: Boolean, tint: Color, locale: Locale) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val number = day.date.dayOfMonth
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp)) {
        Text(
            if (names || number == 1) day.date.month.getDisplayName(DateTextStyle.SHORT_STANDALONE, locale).uppercase(locale) else " ",
            style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, softWrap = false,
            modifier = Modifier.height(18.dp),
        )
        val fill = when (day.mark) {
            HighlightConsistency.Mark.DONE -> tint
            HighlightConsistency.Mark.MISSED -> MaterialTheme.colorScheme.surfaceContainerHighest
            HighlightConsistency.Mark.AHEAD -> Color.Transparent
        }
        val text = when (day.mark) {
            HighlightConsistency.Mark.DONE -> MaterialTheme.colorScheme.surfaceContainerLow
            HighlightConsistency.Mark.MISSED -> MaterialTheme.colorScheme.onSurface
            HighlightConsistency.Mark.AHEAD -> muted
        }
        var circle = Modifier.size(32.dp).background(fill, CircleShape)
        if (isToday) circle = circle.border(2.5.dp, tint, CircleShape)
        Box(circle, contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = text)
        }
    }
}

