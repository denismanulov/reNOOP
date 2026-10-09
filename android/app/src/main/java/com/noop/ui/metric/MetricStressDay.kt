package com.noop.ui.metric

import com.noop.ui.m3.labelBand
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.DaytimeStress
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.SectionHeader
import com.noop.ui.selectedDaytimeStressMode
import com.noop.ui.stressLocalDayWindowContaining
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MARK: - Stress metric page: "Today" (twin of iOS MetricStressDayCard)
//
// Health draws a day's readings as one bar per hour under the day's figure. This is that card for the 0–3
// daily stress score: each waking hour scored with the daily score's own proxy (DaytimeStress), from today's
// banked heart rate, R-R and wrist motion, against the lens Settings chose. The Stress screen folded into
// the Stress metric page (Denis 309e5a6c); this is the part of it the page keeps. Hidden when today has
// nothing scored.

/** Only the strap's 0–3 score has an intraday read; an imported 0–100 series does not. */
internal fun stressDayApplies(metric: MetricDescriptor): Boolean =
    metric.key == "stress" && metric.source == MetricCatalog.WHOOP

/**
 * Today's hour-by-hour stress: the local day [midnight, now] of heart rate, R-R and wrist motion across
 * the device-aware unions, scored off the main thread. [DaytimeStress.Result.EMPTY] with too little heart
 * rate to score an hour.
 */
internal suspend fun loadStressDay(vm: AppViewModel, personalBaseline: Boolean): DaytimeStress.Result =
    withContext(Dispatchers.Default) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val zone = ZoneId.systemDefault()
        val todayWindow = stressLocalDayWindowContaining(nowSeconds, zone)
        val from = todayWindow.fromEpochSecond
        val tzOffsetSeconds = zone.rules.getOffset(Instant.ofEpochSecond(nowSeconds)).totalSeconds.toLong()
        val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, nowSeconds, limit = 200_000)
        if (hr.size < DaytimeStress.minHourHrSamples) return@withContext DaytimeStress.Result.EMPTY
        val rr = vm.repo.rrIntervalsUnion(vm.activeStrapId, from, nowSeconds, limit = 200_000)
        // An ambulatory hour is exertion, not stress: the wrist accelerometer masks it.
        val gravity = vm.repo.gravitySamplesUnion(vm.activeStrapId, from, nowSeconds, limit = 200_000)
        // The personal cross-day baseline only when the reader opted in (#463) and history allows it.
        val mode = selectedDaytimeStressMode(
            vm.repo, vm.activeStrapId, todayWindow.day, zone, personalBaseline,
        )
        DaytimeStress.analyze(hr, rr, gravity, tzOffsetSeconds, mode)
    }

/**
 * The header of the hours card. The hours are the CALENDAR day's ([loadStressDay] scores midnight to now),
 * which between midnight and 04:00 is a day ahead of the app's today, so the header names it through the
 * same [AppToday] as "Latest": "Today" only when the two are one day, else that day's date.
 */
private fun stressDayHeader(today: AppToday, locale: Locale): String =
    MetricDateLabels.stamp(today.calendarKey, today, locale)

/** The "Today" section: the day's average stress and its hours as bars on a 0–3 scale. */
@Composable
internal fun MetricStressDayCard(
    day: DaytimeStress.Result,
    figure: @Composable (Double) -> Unit,
    tint: Color,
    locale: Locale,
    today: AppToday,
) {
    if (day.scored.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    // Hour ticks in the reader's clock: "00, 06, 12, 18" or "12 AM, 6 AM…".
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val hourFormat = DateTimeFormatter.ofPattern(if (is24) "HH" else "h a", locale)
    fun hourLabel(h: Int): String = LocalTime.of(h, 0).format(hourFormat)
    val description = day.scored.joinToString(", ") { "${hourLabel(it.hour)} ${MetricHealthStyle.number(it.level ?: 0.0, 1, locale)}" }

    Column {
        SectionHeader(stressDayHeader(today, locale))
        HealthCard(verticalSpacing = 2.dp) {
            Text(
                stringResource(R.string.metric_caption_average),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            day.dayMean?.let { figure(it) }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(160.dp)
                    .semantics { contentDescription = description },
            ) {
                val labelW = measurer.measure("3", labelStyle).size.width + 10.dp.toPx()
                val right = size.width - labelW
                val top = 6.dp.toPx()
                val bottom = size.height - labelBand(measurer, labelStyle, 20.dp)
                fun yOf(v: Double) = (top + (1 - v / 3.0) * (bottom - top)).toFloat()
                for (v in 0..3) {
                    val y = yOf(v.toDouble())
                    drawLine(colors.outlineVariant, Offset(0f, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                    val l = measurer.measure(v.toString(), labelStyle)
                    drawText(l, topLeft = Offset(right + 6.dp.toPx(), y - l.size.height / 2f))
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                for (h in listOf(0, 6, 12, 18)) {
                    val x = right * h / 24f
                    drawLine(colors.outlineVariant, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                    val l = measurer.measure(hourLabel(h), labelStyle)
                    drawText(l, topLeft = Offset(x + 3.dp.toPx(), bottom + 4.dp.toPx()))
                }
                val barW = 7.dp.toPx()
                day.hours.forEach { h ->
                    val level = h.level ?: return@forEach
                    val cx = right * (h.hour + 0.5f) / 24f
                    val y = yOf(maxOf(level, 0.05))
                    drawRoundRect(tint, Offset(cx - barW / 2, y), Size(barW, bottom - y), CornerRadius(barW / 2, barW / 2))
                }
            }
            // #2125: an hour the wrist was moving is exertion, not stress, so it has no bar; say so, or the gap
            // reads as missing data.
            if (day.activityMaskedHours > 0) {
                Text(
                    pluralStringResource(R.plurals.stress_hours_excluded_moving, day.activityMaskedHours, day.activityMaskedHours),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * The "Today" section while [loadStressDay] is still reading (#2535). The read folds a day of heart rate, R-R
 * and motion and can take seconds; without this the page showed nothing and then the card popped in, which
 * looked like a day with no data. Same header and card as [MetricStressDayCard] so the swap is quiet.
 */
@Composable
internal fun MetricStressDayLoading(today: AppToday, locale: Locale) {
    Column {
        SectionHeader(stressDayHeader(today, locale))
        HealthCard {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.l10n_stress_screen_reading_today_s_heart_rate_7491835a),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
