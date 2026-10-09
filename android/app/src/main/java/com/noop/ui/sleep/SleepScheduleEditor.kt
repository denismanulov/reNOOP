package com.noop.ui.sleep

import com.noop.ui.m3.SheetBackdropEffect
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.SwitchRow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// MARK: - Schedule editor (twin of iOS SleepScheduleEditor + SleepScheduleDial), Google Clock's Bedtime look
//
// "Days Active" as seven circles, Monday first; "Bedtime and Wake Up" as two times (each opens the Material
// time picker) over the 24-hour dial: drag either end or the arc itself, five-minute steps, the span held
// between 5 and 11 hours; TalkBack moves each end by five minutes with its own actions. An own-time schedule
// adds "Alarm" and "Delete Schedule".

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepScheduleEditor(
    edit: SleepScheduleEdit,
    inputs: SleepScheduleInputs,
    is24h: Boolean,
    locale: Locale,
    onDismiss: () -> Unit,
    onSave: (SleepScheduleStored) -> Unit,
) {
    var draft by remember { mutableStateOf(edit) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = bedtime, false = wake
    val isNew = edit.original == null
    val result = SleepSchedule.applying(draft, inputs)
    val alarmToggleEnabled = !draft.alarm || SleepSchedule.applying(draft.copy(alarm = false), inputs) != null
    val alarm = if (draft.isBase) inputs.sounding else inputs.sounding && draft.alarm

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBackdropEffect()
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) }
                Text(
                    stringResource(if (isNew) R.string.sleep_schedule_new else R.string.sleep_schedule_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = result != null && draft.days.isNotEmpty(),
                    onClick = { result?.let(onSave) },
                ) { Text(stringResource(R.string.sleep_edit_save)) }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(stringResource(R.string.sleep_schedule_days_active))
                HealthCard {
                    DayCircles(draft.days, locale) { dow ->
                        draft = draft.copy(days = if (dow in draft.days) draft.days - dow else draft.days + dow)
                    }
                }
                SectionHeader(stringResource(R.string.sleep_schedule_bed_and_wake))
                HealthCard(verticalSpacing = 16.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        TimeBlock(
                            Icons.Filled.Bedtime, stringResource(R.string.sleep_schedule_bedtime),
                            minuteClock(draft.bed, is24h, locale), true,
                            Modifier.weight(1f).clickable { picking = true }, centered = true,
                        )
                        TimeBlock(
                            if (alarm) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
                            stringResource(if (alarm) R.string.sleep_schedule_wake else R.string.sleep_schedule_wake_no_alarm),
                            minuteClock(draft.wake, is24h, locale), alarm,
                            Modifier.weight(1f).clickable { picking = false }, centered = true,
                        )
                    }
                    SleepScheduleDial(
                        bed = draft.bed,
                        wake = draft.wake,
                        onChange = { b, w -> draft = draft.copy(bed = b, wake = w) },
                        is24h = is24h,
                        locale = locale,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                    Text(
                        scheduleDuration(SleepSchedule.goal(draft.bed, draft.wake)),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!draft.isBase) {
                    SectionHeader(stringResource(R.string.sleep_schedule_alarm_options))
                    ListGroup {
                        item { shape ->
                            SwitchRow(
                                shape = shape,
                                title = stringResource(R.string.sleep_schedule_alarm),
                                checked = draft.alarm,
                                onCheckedChange = { draft = draft.copy(alarm = it) },
                                enabled = alarmToggleEnabled,
                            )
                        }
                    }
                    if (!isNew) {
                        ListGroup {
                            item { shape ->
                                ListRow(
                                    shape = shape,
                                    title = stringResource(R.string.sleep_schedule_delete),
                                    titleColor = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        SleepSchedule.applying(draft.copy(delete = true), inputs)?.let(onSave) ?: onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { bedEnd ->
        val current = if (bedEnd) draft.bed else draft.wake
        ClockPicker(
            title = stringResource(if (bedEnd) R.string.sleep_schedule_bedtime else R.string.sleep_schedule_wake),
            hour = current / 60,
            minute = current % 60,
            is24h = is24h,
            onPick = { h, m ->
                val (b, w) = SleepSchedule.moving(bedEnd, SleepSchedule.snapped(h * 60 + m), draft.bed, draft.wake)
                draft = draft.copy(bed = b, wake = w)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

private sealed class Grab {
    object Bed : Grab()
    object Wake : Grab()
    data class Arc(val offset: Int) : Grab()
}

/**
 * The 24-hour dial: a track ring, the clock face (hour ticks, even hours named, the moon at midnight and the
 * sun at noon), the sleep arc from bedtime to wake with a knob at each end.
 */
@Composable
internal fun SleepScheduleDial(
    bed: Int,
    wake: Int,
    onChange: (Int, Int) -> Unit,
    is24h: Boolean,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val face = MaterialTheme.colorScheme.surfaceContainerLow
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val label = MaterialTheme.colorScheme.onSurface
    val arcColor = Health.colors.sleep
    val knobGlyph = MaterialTheme.colorScheme.surface
    val sun = Health.colors.charge
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val bedPainter = rememberVectorPainter(Icons.Filled.Bedtime)
    val alarmPainter = rememberVectorPainter(Icons.Filled.Alarm)
    val moonPainter = rememberVectorPainter(Icons.Filled.Bedtime)
    val sunPainter = rememberVectorPainter(Icons.Filled.WbSunny)
    val bedNow by rememberUpdatedState(bed)
    val wakeNow by rememberUpdatedState(wake)
    val change by rememberUpdatedState(onChange)

    val bedLabel = stringResource(R.string.sleep_schedule_bedtime)
    val wakeLabel = stringResource(R.string.sleep_schedule_wake)
    val described = stringResource(R.string.sleep_schedule_dial_a11y, minuteClock(bed, is24h, locale), minuteClock(wake, is24h, locale))
    val later = stringResource(R.string.sleep_schedule_later)
    val earlier = stringResource(R.string.sleep_schedule_earlier)
    fun step(bedEnd: Boolean, by: Int): Boolean {
        val from = if (bedEnd) bedNow else wakeNow
        val (b, w) = SleepSchedule.moving(bedEnd, SleepSchedule.snapped(from + by), bedNow, wakeNow)
        change(b, w)
        return true
    }

    Canvas(
        modifier.aspectRatio(1f)
            .clearAndSetSemantics {
                contentDescription = described
                customActions = listOf(
                    CustomAccessibilityAction("$bedLabel: $later") { step(true, SleepSchedule.STEP) },
                    CustomAccessibilityAction("$bedLabel: $earlier") { step(true, -SleepSchedule.STEP) },
                    CustomAccessibilityAction("$wakeLabel: $later") { step(false, SleepSchedule.STEP) },
                    CustomAccessibilityAction("$wakeLabel: $earlier") { step(false, -SleepSchedule.STEP) },
                )
            }
            .pointerInput(Unit) {
                val r = min(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcR = r * 0.8425f
                val arcW = r * 0.21f
                fun minutesAt(p: Offset): Int {
                    val a = atan2(p.y - center.y, p.x - center.x) + PI / 2
                    return SleepSchedule.wrap((a / (2 * PI) * SleepSchedule.DAY).roundToInt())
                }
                fun point(m: Int): Offset {
                    val a = m.toDouble() / SleepSchedule.DAY * 2 * PI - PI / 2
                    return Offset(center.x + arcR * cos(a).toFloat(), center.y + arcR * sin(a).toFloat())
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = down.position
                    val dBed = (p - point(bedNow)).getDistance()
                    val dWake = (p - point(wakeNow)).getDistance()
                    val grab: Grab? = when {
                        min(dBed, dWake) < arcW * 0.9f -> if (dBed <= dWake) Grab.Bed else Grab.Wake
                        kotlin.math.abs(hypot(p.x - center.x, p.y - center.y) - arcR) < arcW -> {
                            val offset = SleepSchedule.wrap(minutesAt(p) - bedNow)
                            if (offset <= SleepSchedule.wrap(wakeNow - bedNow)) Grab.Arc(offset) else null
                        }
                        else -> null
                    }
                    if (grab == null) return@awaitEachGesture
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        if (c.positionChange() != Offset.Zero) c.consume()
                        val at = minutesAt(c.position)
                        when (grab) {
                            Grab.Bed -> change(SleepSchedule.moving(true, SleepSchedule.snapped(at), bedNow, wakeNow).first, wakeNow)
                            Grab.Wake -> change(bedNow, SleepSchedule.moving(false, SleepSchedule.snapped(at), bedNow, wakeNow).second)
                            is Grab.Arc -> {
                                val span = SleepSchedule.wrap(wakeNow - bedNow)
                                val newBed = SleepSchedule.snapped(at - grab.offset)
                                change(newBed, SleepSchedule.wrap(newBed + span))
                            }
                        }
                    }
                }
            },
    ) {
        val r = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        val faceR = r * 0.685f
        val arcR = r * 0.8425f
        val arcW = r * 0.21f
        fun angle(m: Int) = (m.toDouble() / SleepSchedule.DAY * 2 * PI - PI / 2)
        fun point(m: Int, radius: Float) = Offset(center.x + radius * cos(angle(m)).toFloat(), center.y + radius * sin(angle(m)).toFloat())
        drawCircle(track, r, center)
        drawCircle(face, faceR, center)
        for (q in 0 until 96) {
            val hour = q % 4 == 0
            val outer = faceR - r * 0.035f
            val inner = outer - if (hour) r * 0.045f else r * 0.025f
            drawLine(
                tick.copy(alpha = if (hour) 0.9f else 0.5f), point(q * 15, inner), point(q * 15, outer),
                if (hour) 1.2.dp.toPx() else 0.8.dp.toPx(),
            )
        }
        for (h in 0 until 24 step 2) {
            val text = when {
                is24h -> "$h"
                h % 6 == 0 -> DateTimeFormatter.ofPattern("h a", locale).format(LocalTime.of(h, 0))
                else -> "${if (h > 12) h - 12 else h}"
            }
            // The face is a fixed-size picture: its hour names stop growing at 1.3x, where they still clear
            // each other (at 2x "10", "12 AM" and "2" ran together). The two times above the dial grow in
            // full, and TalkBack reads them.
            val t = measurer.measure(
                text,
                labelStyle.copy(color = if (h % 6 == 0) label else tick),
                density = Density(density, fontScale.coerceAtMost(1.3f)),
            )
            val p = point(h * 60, faceR * 0.74f)
            drawText(t, topLeft = Offset(p.x - t.size.width / 2, p.y - t.size.height / 2))
        }
        val glyph = r * 0.1f
        with(moonPainter) {
            val p = point(0, faceR * 0.5f)
            translate(p.x - glyph / 2, p.y - glyph / 2) { draw(Size(glyph, glyph), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(arcColor)) }
        }
        with(sunPainter) {
            val p = point(12 * 60, faceR * 0.5f)
            translate(p.x - glyph / 2, p.y - glyph / 2) { draw(Size(glyph, glyph), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(sun)) }
        }
        val span = SleepSchedule.wrap(wake - bed)
        val start = Math.toDegrees(angle(bed)).toFloat()
        val sweep = span.toFloat() / SleepSchedule.DAY * 360f
        drawArc(
            arcColor, start, sweep, false,
            Offset(center.x - arcR, center.y - arcR), Size(arcR * 2, arcR * 2),
            style = Stroke(arcW, cap = StrokeCap.Round),
        )
        val knob = arcW * 0.5f
        listOf(bed to bedPainter, wake to alarmPainter).forEach { (m, painter) ->
            val p = point(m, arcR)
            with(painter) {
                translate(p.x - knob / 2, p.y - knob / 2) {
                    draw(Size(knob, knob), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(knobGlyph))
                }
            }
        }
    }
}
