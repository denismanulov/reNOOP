package com.noop.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.SleepDebtLedger
import com.noop.ui.TemperatureUnit
import com.noop.ui.UnitFormatter
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.CloverShape
import com.noop.ui.m3.CookieShape
import com.noop.ui.m3.ExpressiveBar
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LevelBadge
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.LocalTonalIcons
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.SheetBackdropEffect
import com.noop.ui.m3.TonalIcon
import com.noop.ui.m3.labelBand
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - The cards of the Sleep tab
//
// The page answers in the order a reader asks. How did I sleep: the night card (score, its word, one
// sentence). What did I sleep: the day's sleeps as rows, the night and each nap, each opening its own page
// (SleepSessionScreen: the stages over the clock, heart rate, each stage against the reader's usual). Why the
// score: the three things it is made of, each with its figure, its level in a word and a bar. Was my body as
// usual: each overnight reading against its own range, in words. How does the night sit in my week. Every
// figure stands beside what it means; nothing is a bare number or an unlabelled mark.

@Composable
internal fun scoreWord(word: SleepScoreWord): String = stringResource(
    when (word) {
        SleepScoreWord.POOR -> R.string.sleep_score_poor
        SleepScoreWord.FAIR -> R.string.sleep_score_fair
        SleepScoreWord.GOOD -> R.string.sleep_score_good
        SleepScoreWord.OPTIMAL -> R.string.sleep_score_optimal
    },
)

/** The hue a level is written in: green for good and optimal, amber for fair, the error colour for poor. */
@Composable
internal fun levelColor(word: SleepScoreWord): Color = when (word) {
    SleepScoreWord.OPTIMAL, SleepScoreWord.GOOD -> Health.colors.positive
    SleepScoreWord.FAIR -> Health.colors.warning
    SleepScoreWord.POOR -> MaterialTheme.colorScheme.error
}

/** A share as the reader's locale writes a whole percent. */
internal fun percent(fraction: Double, locale: Locale): String =
    java.text.NumberFormat.getPercentInstance(locale).format(fraction)

/** "10:32 PM – 8:21 AM". */
@Composable
internal fun clockRange(startTs: Long, endTs: Long, is24h: Boolean, locale: Locale): String =
    stringResource(R.string.sleep_time_range, clockLabel(startTs, is24h, locale), clockLabel(endTs, is24h, locale))

// MARK: Night card

/**
 * The answer to "how did I sleep": the score in its ring, its word, and one sentence naming what shaped it.
 * The night's own figures (its times, its time asleep) are on the row under the card, which opens the night.
 */
@Composable
internal fun SleepNightCard(night: SleepNightDetail, score: SleepScore, onClick: () -> Unit) {
    val hue = Health.colors.rest
    val surface = MaterialTheme.colorScheme.surface
    HealthCard(
        onClick = onClick,
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        // The Rest hue as a wash over the page: the hero is the one tinted card of the tab.
        color = hue.copy(alpha = 0.16f).compositeOver(surface),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // The score on the Expressive cookie, in the Rest hue; the page colour is its ink in both themes.
            Box(Modifier.size(116.dp).clip(CookieShape).background(hue), contentAlignment = Alignment.Center) {
                Text(
                    score.value.toString(),
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = surface,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.sleep_score_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    scoreWord(score.word),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, hyphens = Hyphens.Auto),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            ChevronRight()
        }
        Text(verdictSentence(score, night), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The night card's sentence. A short night names its shortfall from the whole minutes the night's row shows,
 * so the sentence and the row cannot disagree by a rounding.
 */
@Composable
private fun verdictSentence(score: SleepScore, night: SleepNightDetail): String = when (score.verdict) {
    SleepVerdict.IMPORTED -> stringResource(R.string.sleep_verdict_imported)
    SleepVerdict.SOUND -> stringResource(R.string.sleep_verdict_sound)
    SleepVerdict.SHORT -> {
        val short = SleepScore.TARGET_MIN.roundToInt() - night.asleepMin.roundToInt()
        if (short >= 1) {
            stringResource(R.string.sleep_verdict_short, sleepDuration(short.toDouble()), sleepDuration(SleepScore.TARGET_MIN))
        } else stringResource(R.string.sleep_verdict_short_plain)
    }
    SleepVerdict.RESTLESS -> stringResource(R.string.sleep_verdict_restless)
    SleepVerdict.SHALLOW -> stringResource(R.string.sleep_verdict_shallow)
}

// MARK: The day's sleeps

/**
 * The day's sleeps as rows, the way a list of activities reads: the night first, then each nap, each with
 * its two times and its time asleep, each opening its own page. A nap that stored no stages has no time
 * asleep to show (the minutes between its times are time in bed), so its row carries the times alone.
 */
@Composable
internal fun SleepSessionRows(
    night: SleepNightDetail?,
    naps: List<SleepNap>,
    is24h: Boolean,
    locale: Locale,
    onOpenNight: () -> Unit,
    onOpenNap: (SleepNap) -> Unit,
) {
    ListGroup {
        if (night != null) {
            item { shape ->
                SessionRow(
                    shape, Icons.Filled.Bedtime, CookieShape, stringResource(R.string.nav_sleep),
                    clockRange(night.onsetTs, night.wakeTs, is24h, locale), night.asleepMin, onOpenNight,
                )
            }
        }
        naps.forEach { nap ->
            item { shape ->
                SessionRow(
                    shape, Icons.Filled.Hotel, CloverShape, stringResource(R.string.sleep_nap),
                    clockRange(nap.startTs, nap.endTs, is24h, locale), nap.asleepMin,
                ) { onOpenNap(nap) }
            }
        }
    }
}

@Composable
private fun SessionRow(
    shape: Shape,
    icon: ImageVector,
    iconShape: Shape,
    title: String,
    times: String,
    asleepMin: Double?,
    onClick: () -> Unit,
) {
    ListRow(
        shape = shape,
        title = title,
        subtitle = times,
        leading = { TonalIcon(icon, LocalTonalIcons.current.purple, size = 48.dp, shape = iconShape) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (asleepMin != null) {
                    Text(
                        sleepDuration(asleepMin),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                ChevronRight()
            }
        },
        onClick = onClick,
    )
}

// MARK: What the score is made of

/** The three things a night is measured on: each one's figure, what it is measured against, a level, a bar. */
@Composable
internal fun SleepFactorsCard(night: SleepNightDetail, score: SleepScore, locale: Locale) {
    HealthCard(verticalSpacing = 18.dp) {
        score.parts.forEach { part ->
            when (part.part) {
                SleepScorePart.DURATION -> FactorRow(
                    part,
                    title = stringResource(R.string.sleep_factor_duration),
                    detail = stringResource(R.string.sleep_factor_target, sleepDuration(SleepScore.TARGET_MIN)),
                    value = sleepDuration(night.asleepMin),
                )
                // The share is the score's own figure, so the number and the bar under it are one fact.
                SleepScorePart.INTERRUPTIONS -> FactorRow(
                    part,
                    title = stringResource(R.string.sleep_factor_continuity),
                    detail = stringResource(R.string.sleep_factor_in_bed_share),
                    value = percent(part.fraction, locale),
                )
                SleepScorePart.RESTORATIVE -> {
                    val restorative = night.stages.deep + night.stages.rem
                    FactorRow(
                        part,
                        title = stringResource(R.string.sleep_factor_restorative),
                        detail = stringResource(
                            R.string.sleep_factor_of_sleep,
                            percent(if (night.asleepMin > 0) restorative / night.asleepMin else 0.0, locale),
                        ),
                        value = sleepDuration(restorative),
                    )
                }
                SleepScorePart.REGULARITY -> Unit
            }
        }
    }
}

@Composable
private fun FactorRow(part: SleepScorePartScore, title: String, detail: String, value: String) {
    val color = levelColor(part.level)
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                LevelBadge(
                    scoreWord(part.level), color,
                    ink = if (part.level == SleepScoreWord.POOR) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.surface,
                )
            }
        }
        ExpressiveBar(part.fraction.toFloat(), color)
    }
}

// MARK: The body overnight

internal fun vitalIcon(metric: SleepVitals.Metric): ImageVector = when (metric) {
    SleepVitals.Metric.HEART_RATE -> Icons.Filled.Favorite
    SleepVitals.Metric.RESPIRATORY -> Icons.Filled.Air
    SleepVitals.Metric.TEMPERATURE -> Icons.Filled.Thermostat
    SleepVitals.Metric.OXYGEN -> Icons.Filled.WaterDrop
}

@Composable
internal fun vitalTitle(metric: SleepVitals.Metric): String = stringResource(
    when (metric) {
        SleepVitals.Metric.HEART_RATE -> R.string.sleep_vitals_rhr
        SleepVitals.Metric.RESPIRATORY -> R.string.sleep_vitals_resp
        SleepVitals.Metric.TEMPERATURE -> R.string.sleep_vitals_skin_temp
        SleepVitals.Metric.OXYGEN -> R.string.sleep_vitals_spo2
    },
)

@Composable
private fun vitalLevel(level: SleepVitals.Level): String = stringResource(
    when (level) {
        SleepVitals.Level.LOW -> R.string.sleep_body_lower
        SleepVitals.Level.TYPICAL -> R.string.sleep_vitals_typical
        SleepVitals.Level.HIGH -> R.string.sleep_body_higher
    },
)

/**
 * The overnight readings in words. While the ranges are being learned: how many nights are on record and how
 * many are left. Once they exist: one line saying whether anything is unusual, then each reading with its
 * usual range under its name and "typical", "higher than usual" or "lower than usual" under its value. A row
 * opens that metric's page.
 */
@Composable
internal fun SleepBodyCard(
    vitals: SleepVitals,
    tempUnit: TemperatureUnit,
    locale: Locale,
    onOpenMetric: (String) -> Unit,
) {
    val typical = Health.colors.vitalsTypical
    val outlier = Health.colors.vitalsOutlier
    if (vitals.nightsRemaining > 0) {
        HealthCard(verticalSpacing = 12.dp) {
            Text(stringResource(R.string.sleep_body_learning), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(SleepVitals.NIGHTS_NEEDED) { i ->
                    val shape = RoundedCornerShape(50)
                    Box(
                        Modifier.weight(1f).height(8.dp).clip(shape)
                            .background(if (i < vitals.nightsRecorded) typical else Color.Transparent)
                            .border(1.5.dp, if (i < vitals.nightsRecorded) typical else MaterialTheme.colorScheme.outlineVariant, shape),
                    )
                }
            }
            Text(
                pluralStringResource(R.plurals.sleep_body_nights_left, vitals.nightsRemaining, vitals.nightsRemaining),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (vitals.readings.isEmpty()) {
        HealthCard {
            Text(
                stringResource(R.string.sleep_body_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    ListGroup {
        item { shape ->
            val all = vitals.outliers == 0
            ListRow(
                shape = shape,
                title = if (all) stringResource(R.string.sleep_body_all_typical)
                    else pluralStringResource(R.plurals.sleep_vitals_outliers, vitals.outliers, vitals.outliers),
                leading = {
                    Icon(
                        if (all) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (all) typical else outlier,
                    )
                },
            )
        }
        vitals.readings.forEach { r ->
            item { shape ->
                val tint = if (r.isOutlier) outlier else typical
                ListRow(
                    shape = shape,
                    title = vitalTitle(r.metric),
                    subtitle = run {
                        // A signed range ("−0.4 – +0.4 °C") needs space round the dash to stay readable.
                        val low = bareVital(r.low, r.metric, tempUnit, locale)
                        val high = formatVital(r.high, r.metric, tempUnit, locale)
                        val spaced = r.low < 0 || low.contains(' ')
                        stringResource(
                            R.string.sleep_vitals_typical_range,
                            if (spaced) "$low " else low,
                            if (spaced) " $high" else high,
                        )
                    },
                    leading = { Icon(vitalIcon(r.metric), contentDescription = null, tint = tint) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatVital(r.value, r.metric, tempUnit, locale),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                                Text(vitalLevel(r.level), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                            }
                            ChevronRight()
                        }
                    },
                    onClick = { onOpenMetric(r.metric.catalogKey) },
                )
            }
        }
    }
}

/** A range's low end: the number alone, the high end carries the unit ("50–62 BPM"). */
@Composable
private fun bareVital(v: Double, metric: SleepVitals.Metric, unit: TemperatureUnit, locale: Locale): String = when (metric) {
    SleepVitals.Metric.HEART_RATE, SleepVitals.Metric.OXYGEN -> v.roundToInt().toString()
    SleepVitals.Metric.RESPIRATORY -> String.format(locale, "%.1f", v)
    SleepVitals.Metric.TEMPERATURE -> formatVital(v, metric, unit, locale).substringBefore(" ")
}

@Composable
internal fun formatVital(v: Double, metric: SleepVitals.Metric, unit: TemperatureUnit, locale: Locale): String = when (metric) {
    SleepVitals.Metric.HEART_RATE -> stringResource(R.string.sleep_bpm_value, v.roundToInt())
    SleepVitals.Metric.RESPIRATORY -> stringResource(R.string.sleep_br_min_value, String.format(locale, "%.1f", v))
    // A deviation is a small signed number; an absolute reading is a body temperature.
    SleepVitals.Metric.TEMPERATURE -> if (abs(v) < 10) signedDelta(v, unit) else UnitFormatter.temperatureFromCelsius(v, unit)
    SleepVitals.Metric.OXYGEN -> percent(v.roundToInt() / 100.0, locale)
}

/** A skin-temperature deviation with its sign ("+0.2 °C", "−0.3 °C", "0.0 °C"), never "-0.0". */
internal fun signedDelta(v: Double, unit: TemperatureUnit): String {
    val text = UnitFormatter.temperatureDeltaFromCelsius(abs(v), unit)
    val shown = text.substringBefore(" ").replace(',', '.').toDoubleOrNull() ?: 0.0
    return when {
        shown == 0.0 -> text
        v > 0 -> "+$text"
        else -> "−$text"
    }
}

// MARK: The week

/**
 * The seven nights ending on the one on the page: the average, a bar per night against the score's target,
 * then in sentences how the week compares with the one before and how the night's bedtime compares with the
 * reader's usual. [debt] is the sleep-debt ledger, shown only on the newest night because it is anchored to
 * the latest night on record, not to the one on the page.
 */
@Composable
internal fun SleepWeekCard(week: SleepWeek, debt: SleepDebtLedger?, is24h: Boolean, locale: Locale) {
    HealthCard(verticalSpacing = 12.dp) {
        val average = week.averageMin
        if (average != null) {
            Column {
                Text(
                    stringResource(R.string.sleep_week_average),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BigDuration(average)
            }
        } else {
            Text(
                stringResource(R.string.sleep_week_few),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SleepWeekBars(week, locale, Modifier.fillMaxWidth().height(140.dp))
        week.changeMin?.let { change ->
            val by = sleepDuration(abs(change).toDouble())
            Text(
                when {
                    abs(change) < 10 -> stringResource(R.string.sleep_week_same)
                    change > 0 -> stringResource(R.string.sleep_week_more, by)
                    else -> stringResource(R.string.sleep_week_less, by)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        week.bedtime?.let { bed ->
            val usual = nightClock(bed.usualMin, is24h, locale)
            val by = sleepDuration(abs(bed.diffMin).toDouble())
            Text(
                when {
                    abs(bed.diffMin) < 15 -> stringResource(R.string.sleep_week_bed_usual, usual)
                    bed.diffMin > 0 -> stringResource(R.string.sleep_week_bed_later, by, usual)
                    else -> stringResource(R.string.sleep_week_bed_earlier, by, usual)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (debt != null && debt.nights.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sleep_more_debt), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    if (debt.isDebt) sleepDuration(debt.magnitudeMin) else stringResource(R.string.sleep_more_no_debt),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (debt.isDebt) Health.colors.warning else Health.colors.positive,
                )
            }
        }
    }
}

/** "7 hr 42 min", the figures large and the units a size down. */
@Composable
internal fun BigDuration(minutes: Double, color: Color = MaterialTheme.colorScheme.onSurface) {
    val (h, m) = durationParts(minutes)
    val big = SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.displaySmall.fontSize)
    val small = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = MaterialTheme.typography.titleMedium.fontSize)
    val hr = stringResource(R.string.metric_unit_hr)
    val min = stringResource(R.string.metric_unit_min)
    Text(
        buildAnnotatedString {
            if (h > 0) {
                withStyle(big) { append(h.toString()) }
                withStyle(small) { append(" $hr ") }
            }
            if (m > 0 || h == 0) {
                withStyle(big) { append(m.toString()) }
                withStyle(small) { append(" $min") }
            }
        },
        color = color,
        maxLines = 1,
    )
}

/**
 * A bar per day of the week, as tall as the time asleep, against a dashed line at the score's target with
 * the target written at its end. The night on the page (the last bar) is in the Sleep hue, the others paler;
 * a day with no night keeps its letter and draws no bar. A bar is a pill while it is taller than it is wide;
 * a shorter night keeps its true height and rounds less, so an hour of sleep never draws as two.
 */
@Composable
private fun SleepWeekBars(week: SleepWeek, locale: Locale, modifier: Modifier) {
    val hue = Health.colors.sleep
    val rule = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val target = SleepScore.TARGET_MIN
    val targetText = sleepDuration(target)
    val letters = week.days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale) }
    val names = week.days.map { it.dayOfWeek.getDisplayName(TextStyle.FULL, locale) }
    val noData = stringResource(R.string.sleep_no_data)
    val spoken = week.asleepMin.mapIndexed { i, v -> names[i] + ": " + (v?.let { sleepDuration(it) } ?: noData) }.joinToString(", ")
    Canvas(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        val labelH = labelBand(measurer, labelStyle, floor = 16.dp, gap = 2.dp)
        val targetLabel = measurer.measure(targetText, labelStyle)
        val axisW = targetLabel.size.width + 6.dp.toPx()
        val plotW = size.width - axisW
        val plotH = size.height - labelH
        val top = max(target, week.asleepMin.filterNotNull().maxOrNull() ?: target) * 1.08
        fun y(v: Double) = (plotH * (1 - v / top)).toFloat()
        val n = week.days.size.coerceAtLeast(1)
        val slot = plotW / n
        val barW = min(slot * 0.6f, 30.dp.toPx())
        week.asleepMin.forEachIndexed { i, v ->
            val cx = slot * (i + 0.5f)
            if (v != null && v > 0) {
                val barH = max(3.dp.toPx(), plotH - y(v))
                val radius = min(barW, barH) / 2
                drawRoundRect(
                    if (i == week.asleepMin.lastIndex) hue else hue.copy(alpha = 0.4f),
                    Offset(cx - barW / 2, plotH - barH), Size(barW, barH), CornerRadius(radius, radius),
                )
            }
            val letter = measurer.measure(letters[i], labelStyle)
            drawText(letter, topLeft = Offset(cx - letter.size.width / 2f, plotH + 2.dp.toPx()))
        }
        val ty = y(target)
        drawLine(
            rule, Offset(0f, ty), Offset(plotW, ty), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        drawText(targetLabel, topLeft = Offset(plotW + 6.dp.toPx(), (ty - targetLabel.size.height / 2f).coerceAtLeast(0f)))
    }
}

// MARK: Explanations

/** The sections a reader can ask "what is this?" about. */
internal enum class SleepExplain { SCORE, STAGES, BODY, WEEK }

/**
 * What a section's figures mean, in a sheet: a short paragraph per figure. [debtNeedMin] is the need the
 * sleep-debt figure was measured against, when the page shows that figure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepExplainSheet(topic: SleepExplain, debtNeedMin: Double?, onDismiss: () -> Unit) {
    val target = sleepDuration(SleepScore.TARGET_MIN)
    val title: String
    val entries: List<Pair<String?, String>>
    when (topic) {
        SleepExplain.SCORE -> {
            title = stringResource(R.string.sleep_score_title)
            entries = listOf(
                null to stringResource(R.string.sleep_explain_score),
                stringResource(R.string.sleep_factor_duration) to stringResource(R.string.sleep_explain_duration, target),
                stringResource(R.string.sleep_factor_continuity) to stringResource(R.string.sleep_explain_continuity),
                stringResource(R.string.sleep_factor_restorative) to stringResource(R.string.sleep_explain_restorative),
            )
        }
        SleepExplain.STAGES -> {
            title = stringResource(R.string.sleep_section_stages)
            entries = listOf(
                stageName(SleepStageRow.DEEP) to stringResource(R.string.sleep_explain_deep),
                stageName(SleepStageRow.REM) to stringResource(R.string.sleep_explain_rem),
                stageName(SleepStageRow.CORE) to stringResource(R.string.sleep_explain_light),
                stageName(SleepStageRow.AWAKE) to stringResource(R.string.sleep_explain_awake),
                null to stringResource(R.string.sleep_explain_stages_note),
            )
        }
        SleepExplain.BODY -> {
            title = stringResource(R.string.sleep_section_body)
            entries = listOf(
                null to stringResource(R.string.sleep_explain_range),
                vitalTitle(SleepVitals.Metric.HEART_RATE) to stringResource(R.string.metric_about_rhr),
                vitalTitle(SleepVitals.Metric.RESPIRATORY) to stringResource(R.string.metric_about_resp),
                vitalTitle(SleepVitals.Metric.TEMPERATURE) to stringResource(R.string.metric_about_skin_temp),
                vitalTitle(SleepVitals.Metric.OXYGEN) to stringResource(R.string.metric_about_spo2),
            )
        }
        SleepExplain.WEEK -> {
            title = stringResource(R.string.sleep_section_week)
            entries = listOfNotNull(
                null to stringResource(R.string.sleep_explain_week, target),
                debtNeedMin?.let { stringResource(R.string.sleep_more_debt) to stringResource(R.string.sleep_explain_debt, sleepDuration(it)) },
            )
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetBackdropEffect()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            entries.forEach { (heading, body) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (heading != null) {
                        Text(heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    }
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// MARK: Shared pieces

/** A night-clock minute (minutes after 18:00 the evening before) as a clock time. */
internal fun nightClock(minutesOfNight: Double, is24h: Boolean, locale: Locale): String {
    val m = (((minutesOfNight.toInt() + 18 * 60) % (24 * 60)) + 24 * 60) % (24 * 60)
    val t = java.time.LocalTime.of(m / 60, m % 60)
    return java.time.format.DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).format(t)
}

/** A stage-coloured dot for the lists. */
@Composable
internal fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
