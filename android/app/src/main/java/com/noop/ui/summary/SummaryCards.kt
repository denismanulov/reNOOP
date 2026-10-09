package com.noop.ui.summary

import android.icu.text.DateFormat
import android.icu.text.DateFormatSymbols
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.ReadinessEngine
import com.noop.ui.ClockPrefs
import com.noop.ui.EffortScale
import com.noop.ui.KeyMetric
import com.noop.ui.PersistedSegment
import com.noop.ui.ProfileAvatarStore
import com.noop.ui.canonicalStage
import com.noop.ui.m3.ActivityRings
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.MiniBarChart
import com.noop.ui.m3.MiniLineChart
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.RowIcon
import com.noop.ui.m3.ValueWithUnit
import com.noop.ui.m3.WeekColumnsChart
import com.noop.ui.m3.color
import com.noop.ui.m3.keyMetricHue
import com.noop.ui.m3.ringFraction
import com.noop.ui.metric.MetricDateLabels
import com.noop.ui.metric.MetricFigure
import com.noop.ui.metric.MetricHealthStyle
import com.noop.ui.metric.MetricToken
import com.noop.ui.metric.MetricUnits
import com.noop.ui.metric.localizedUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// MARK: - Summary cards (Material 3 twins of iOS SummaryCards / SummaryFitnessCards / SummarySleepCard)
//
// The pieces both Summary layouts are built from: the rings card, the fitness tiles, the pinned metric
// card, the Sleep card, the Highlights card, the rows that open All Metrics / Trends, the profile circle
// and the words (stamps, captions, the sync line). Colours are the Material You scheme plus the fixed
// data hues in [Health.colors]; nothing below draws a literal colour.

// MARK: Words

/** "Today", "Yesterday" or "24 Sep" for a stamp. */
@Composable
internal fun stampText(stamp: SummaryStamp?, locale: Locale): String? = when (stamp) {
    null -> null
    SummaryStamp.Today -> stringResource(R.string.metric_today)
    SummaryStamp.Yesterday -> stringResource(R.string.metric_yesterday)
    is SummaryStamp.OnDate -> MetricDateLabels.shortDate(stamp.date.toString(), locale)
}

/** The pager's title: "Today", "Yesterday", else "Thursday, 24 September". */
@Composable
internal fun dayTitle(offset: Int, day: LocalDate, locale: Locale): String = when (offset) {
    0 -> stringResource(R.string.metric_today)
    1 -> stringResource(R.string.metric_yesterday)
    else -> {
        val millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = DateFormat.getInstanceForSkeleton("EEEEMMMMd", locale).format(java.util.Date(millis))
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

/** The one-letter weekday of each "yyyy-MM-dd" key, standalone narrow, in the app language. */
internal fun weekdayLetters(keys: List<String>, locale: Locale): List<String> {
    val symbols = DateFormatSymbols.getInstance(ULocale.forLocale(locale))
        .getWeekdays(DateFormatSymbols.STANDALONE, DateFormatSymbols.NARROW)
    return keys.map { key ->
        val d = SummaryDay.parse(key) ?: return@map ""
        // java DayOfWeek: Monday = 1 … Sunday = 7; ICU: Sunday = 1 … Saturday = 7.
        symbols.getOrElse(d.dayOfWeek.value % 7 + 1) { "" }
    }
}

/** The Charge figure's caption: whose night a carried score is, or "Calibrating". */
@Composable
internal fun chargeCaption(charge: ChargeDisplay, locale: Locale): String? = when (charge) {
    is ChargeDisplay.Carried -> stringResource(
        if (charge.stale) R.string.score_state_title_latest_sleep else R.string.score_state_title_last_night,
        MetricDateLabels.shortDate(charge.priorDay, locale),
    )
    is ChargeDisplay.Calibrating -> stringResource(R.string.score_state_title_calibrating)
    else -> null
}

/** The sync footer's words, or null when it has nothing to say. */
@Composable
internal fun syncLineText(line: SummarySyncLine, locale: Locale): String? {
    fun ago(n: Long, unit: RelativeDateTimeFormatter.RelativeUnit): String =
        RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
            .format(n.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
    return when (line) {
        SummarySyncLine.Syncing -> stringResource(R.string.summary_syncing)
        SummarySyncLine.JustNow -> stringResource(R.string.summary_updated, stringResource(R.string.summary_just_now))
        is SummarySyncLine.MinutesAgo ->
            stringResource(R.string.summary_updated, ago(line.minutes, RelativeDateTimeFormatter.RelativeUnit.MINUTES))
        is SummarySyncLine.HoursAgo ->
            stringResource(R.string.summary_updated, ago(line.hours, RelativeDateTimeFormatter.RelativeUnit.HOURS))
        is SummarySyncLine.DaysAgo ->
            stringResource(R.string.summary_updated, ago(line.days, RelativeDateTimeFormatter.RelativeUnit.DAYS))
        SummarySyncLine.Hidden -> null
    }
}

/** A clock time in the reader's chosen 12/24-hour format. */
@Composable
internal fun clockTime(unixSec: Long, locale: Locale): String {
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)
    val millis = Instant.ofEpochSecond(unixSec).toEpochMilli()
    return DateFormat.getInstanceForSkeleton(if (is24h) "Hm" else "hm", locale).format(java.util.Date(millis))
}

/** The Effort figure on the reader's scale: "12.4" and "/21". */
internal fun effortTokens(effort: Double?, scale: EffortScale, locale: Locale): List<MetricToken> =
    if (effort == null) listOf(MetricToken(SummaryMetricReading.NO_VALUE, false))
    else MetricHealthStyle.tokens("strain", "/100", 1, effort, MetricUnits(effortScale = scale), locale, ::localizedUnit)

/** A 0–100 score as a figure and "%". */
internal fun percentTokens(value: Double?): List<MetricToken> =
    if (value == null) listOf(MetricToken(SummaryMetricReading.NO_VALUE, false))
    else listOf(MetricToken(SummaryNumbers(Locale.ROOT).int(value), false), MetricToken("%", true))

/** A duration in minutes as "7 hr 12 min" tokens. */
internal fun durationTokens(minutes: Double, locale: Locale): List<MetricToken> =
    MetricHealthStyle.tokens("sleep_total_min", "min", 0, minutes, MetricUnits(), locale, ::localizedUnit)

/** The words for a reading's caption. */
@Composable
internal fun captionText(caption: SummaryCaption?): String? = when (caption) {
    SummaryCaption.STRAP_ESTIMATE -> stringResource(R.string.spo2_strap_estimate_caption)
    SummaryCaption.FROM_PROFILE -> stringResource(R.string.today_weight_from_profile)
    null -> null
}

// MARK: Icons and hues

/** The glyph a Key Metric is drawn with (iOS `KeyMetric.customizationIcon`, Material equivalents). */
internal fun keyMetricIcon(metric: KeyMetric): ImageVector = when (metric) {
    KeyMetric.CHARGE -> Icons.Filled.Bolt
    KeyMetric.EFFORT -> Icons.AutoMirrored.Filled.DirectionsRun
    KeyMetric.REST -> Icons.Filled.Bedtime
    KeyMetric.HRV -> Icons.Filled.MonitorHeart
    KeyMetric.RESTING_HR -> Icons.Filled.Favorite
    KeyMetric.BLOOD_OXYGEN -> Icons.Filled.WaterDrop
    KeyMetric.RESPIRATORY -> Icons.Filled.Air
    KeyMetric.STEPS -> Icons.AutoMirrored.Filled.DirectionsWalk
    KeyMetric.WEIGHT -> Icons.Filled.MonitorWeight
    KeyMetric.CALORIES -> Icons.Filled.LocalFireDepartment
    KeyMetric.SKIN_TEMP -> Icons.Filled.Thermostat
}

/** A Key Metric's data hue, shared with the page it opens. */
@Composable
internal fun keyMetricTint(metric: KeyMetric): Color = keyMetricHue(metric).color

/** A highlight's glyph and hue, by readiness signal. */
internal fun highlightIcon(key: String): ImageVector = when (key) {
    "hrv" -> Icons.Filled.MonitorHeart
    "rhr" -> Icons.Filled.Favorite
    "respRate" -> Icons.Filled.Air
    else -> Icons.AutoMirrored.Filled.DirectionsRun
}

@Composable
internal fun highlightTint(key: String): Color = when (key) {
    "hrv", "rhr" -> Health.colors.heart
    "respRate" -> Health.colors.respiratory
    else -> Health.colors.effort
}

/** A highlight's sentence in the app language. */
@Composable
internal fun highlightSentence(h: SummaryHighlight): String {
    val res = SummaryHighlight.sentenceRes(h.key, h.flag, h.evidence)
    return if (res == SummaryHighlight.SENTENCE_NORMAL) {
        stringResource(res, stringResource(SummaryHighlight.titleRes(h.key)))
    } else stringResource(res)
}

// MARK: Profile circle

/** The profile circle: the user's photo, else a person glyph on the tertiary container. */
@Composable
internal fun SummaryAvatar(size: Dp, modifier: Modifier = Modifier) {
    val bitmap = ProfileAvatarStore.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

/** The avatar as the button that opens Settings ("Profile and settings"). */
@Composable
internal fun AvatarButton(size: Dp, onClick: () -> Unit) {
    val label = stringResource(R.string.summary_profile_and_settings)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        SummaryAvatar(size)
    }
}

// MARK: Notices

/** The health alert, when the illness watch raised one. */
@Composable
internal fun HealthAlertNotice(message: String) {
    NoticeCard(
        icon = Icons.Filled.Warning,
        title = stringResource(R.string.summary_signs_of_strain),
        message = message,
    )
}

// MARK: Rings card (layout A)

/** One of the three figures beside the rings. */
internal data class RingFigureData(
    val label: String,
    val tokens: List<MetricToken>,
    val caption: String?,
    val color: Color,
    val onClick: () -> Unit,
)

/**
 * Fitness's Activity Rings card for Charge / Effort / Rest, untitled: the three rings on the leading side,
 * each ring's name over its coloured figure beside them. The rings open Charge, each figure its own page.
 */
@Composable
internal fun RingsCard(
    charge: Double?,
    effort: Double?,
    rest: Double?,
    figures: List<RingFigureData>,
    onRings: () -> Unit,
) {
    val spoken = figures.joinToString(", ") { f -> f.label + " " + f.tokens.joinToString("") { it.text } }
    HealthCard(
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ActivityRings(
                charge = ringFraction(charge, 100.0),
                effort = ringFraction(effort, 100.0),
                rest = ringFraction(rest, 100.0),
                modifier = Modifier
                    .size(148.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRings),
                description = spoken,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                figures.forEach { RingFigure(it) }
            }
        }
    }
}

@Composable
private fun RingFigure(f: RingFigureData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = f.onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(f.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        MetricFigure(
            f.tokens,
            MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            numberColor = f.color,
            unitColor = f.color,
        )
        if (f.caption != null) {
            Text(
                f.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: Fitness tiles

/**
 * A card that opens its page on tap and, on long press, offers "Change Card" with every Key Metric a
 * tile can show (the fitness tiles of both layouts).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChangeableCard(
    current: KeyMetric,
    onClick: () -> Unit,
    onChange: (KeyMetric) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(M3Dimens.cardPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(M3Dimens.cardRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClickLabel = stringResource(R.string.summary_change_card),
                    onLongClick = { menu = true },
                )
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                stringResource(R.string.summary_change_card),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            SummaryTilePrefs.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(choice.titleRes)) },
                    leadingIcon = { Icon(keyMetricIcon(choice), contentDescription = null, tint = keyMetricTint(choice)) },
                    trailingIcon = if (choice == current) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        menu = false
                        onChange(choice)
                    },
                )
            }
        }
    }
}

/** Fitness's Summary tile: title and chevron, the stamp, the figure in the metric's hue, the week in columns. */
@Composable
internal fun FitnessTile(
    metric: KeyMetric,
    reading: SummaryMetricReading,
    week: List<Double?>,
    weekLetters: List<String>,
    stamp: String?,
    onClick: () -> Unit,
    onChange: (KeyMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = keyMetricTint(metric)
    val title = stringResource(metric.titleRes)
    ChangeableCard(current = metric, onClick = onClick, onChange = onChange, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ChevronRight()
        }
        Text(
            stamp ?: " ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ValueWithUnit(
            value = reading.value,
            unit = reading.unit.takeIf { it.isNotEmpty() }?.let { localizedUnit(it) },
            valueStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
            unitStyle = MaterialTheme.typography.titleMedium,
            color = if (reading.hasValue) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            unitColor = if (reading.hasValue) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        WeekColumnsChart(
            values = week,
            labels = weekLetters,
            color = tint,
            bars = reading.chart == SummaryChart.BARS,
            modifier = Modifier.fillMaxWidth().height(76.dp),
        )
    }
}

// MARK: Pinned metric card

/** A pinned Health card: icon and name in the hue, stamp, the value, a caption, a week's mini chart. */
@Composable
internal fun PinnedMetricCard(
    metric: KeyMetric,
    reading: SummaryMetricReading,
    series: List<Double>,
    stamp: String?,
    onClick: () -> Unit,
) {
    val tint = keyMetricTint(metric)
    HealthCard(onClick = onClick, verticalSpacing = 6.dp) {
        CardTitleRow(icon = keyMetricIcon(metric), title = stringResource(metric.titleRes), tint = tint, stamp = stamp)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                ValueWithUnit(
                    value = reading.value,
                    unit = reading.unit.takeIf { it.isNotEmpty() }?.let { localizedUnit(it) },
                    valueStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                captionText(reading.caption)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            MiniChart(series, reading.chart, tint, Modifier.size(84.dp, 32.dp))
        }
    }
}

/** The week at a glance: a line for levels, bars for totals; nothing under three points. */
@Composable
internal fun MiniChart(series: List<Double>, chart: SummaryChart, tint: Color, modifier: Modifier) {
    if (chart == SummaryChart.BARS) MiniBarChart(series, tint, modifier) else MiniLineChart(series, tint, modifier)
}

// MARK: Sleep card

/** Health's Sleep card: time asleep beside a thumbnail of the night's stages, stamped with the wake time. */
@Composable
internal fun SleepCard(night: SummarySleepNight, locale: Locale, onClick: () -> Unit) {
    HealthCard(onClick = onClick, verticalSpacing = 6.dp) {
        CardTitleRow(
            icon = Icons.Filled.Bed,
            title = stringResource(R.string.nav_sleep),
            tint = Health.colors.sleep,
            stamp = clockTime(night.wakeTs, locale),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.summary_time_asleep),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetricFigure(
                    durationTokens(night.stages.asleep, locale),
                    MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    MaterialTheme.typography.titleMedium,
                )
            }
            val segments = night.segments
            if (segments != null && segments.size >= 2) {
                Spacer(Modifier.width(8.dp))
                StagesThumbnail(segments, Modifier.size(132.dp, 44.dp))
            }
        }
    }
}

/** The night's stages as four rows (awake, REM, light, deep), each block where it fell in the night. */
@Composable
internal fun StagesThumbnail(segments: List<PersistedSegment>, modifier: Modifier) {
    val c = Health.colors
    val rows = listOf(c.stageAwake, c.stageRem, c.stageCore, c.stageDeep)
    Canvas(modifier.clearAndSetSemantics {}) {
        val start = segments.minOf { it.start }
        val end = segments.maxOf { it.end }
        val span = (end - start).coerceAtLeast(1).toFloat()
        val rowH = size.height / 4f
        val barH = rowH * 0.66f
        for (s in segments) {
            val row = when (canonicalStage(s.stage)) {
                "awake" -> 0
                "rem" -> 1
                "deep" -> 3
                else -> 2
            }
            val x = (s.start - start) / span * size.width
            val w = ((s.end - s.start) / span * size.width).coerceAtLeast(2f)
            val y = rowH * row + (rowH - barH) / 2
            drawRoundRect(rows[row], Offset(x, y), Size(w, barH), CornerRadius(barH / 3, barH / 3))
        }
    }
}

// MARK: Rows that open a whole page

/** A one-row card that opens a page: "Show All Metrics", "Show All Trends". */
@Composable
internal fun OpenAllRow(title: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    ListGroup {
        item { shape ->
            ListRow(
                shape = shape,
                title = title,
                leading = { RowIcon(icon, tint) },
                trailing = { ChevronRight() },
                onClick = onClick,
            )
        }
    }
}

@Composable
internal fun ShowAllMetricsRow(onClick: () -> Unit) =
    OpenAllRow(stringResource(R.string.summary_show_all_metrics), Icons.Filled.MonitorHeart, Health.colors.heart, onClick)

@Composable
internal fun ShowAllTrendsRow(onClick: () -> Unit) = OpenAllRow(
    stringResource(R.string.summary_show_all_trends),
    Icons.AutoMirrored.Filled.TrendingUp,
    MaterialTheme.colorScheme.primary,
    onClick,
)

/** The empty pinned state: a card that opens the editor. */
@Composable
internal fun PinPromptCard(onClick: () -> Unit) {
    HealthCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.summary_pin_prompt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// MARK: Highlights

/** A figure's number in the engine's decimals and the app language. */
internal fun highlightNumber(value: Double, decimals: Int, locale: Locale): String =
    if (decimals == 0) SummaryNumbers(locale).int(value)
    else String.format(locale, "%.${decimals}f", value)

/** The unit key of a readiness figure. */
internal fun highlightUnit(unit: ReadinessEngine.MetricUnit): String = when (unit) {
    ReadinessEngine.MetricUnit.MS -> "ms"
    ReadinessEngine.MetricUnit.BPM -> "bpm"
    ReadinessEngine.MetricUnit.RPM -> "rpm"
}

/**
 * The one Highlights card: the signal's row, one sentence, and under a hairline the evidence — the latest
 * reading against the normal it was read against (or the 7-day against the 28-day load) as two figures
 * with bars, or a week of Effort for a training-variety highlight.
 */
@Composable
internal fun HighlightCard(h: SummaryHighlight, effortWeek: List<Double>, locale: Locale, onClick: () -> Unit) {
    val tint = highlightTint(h.key)
    HealthCard(onClick = onClick, verticalSpacing = 10.dp) {
        CardTitleRow(icon = highlightIcon(h.key), title = stringResource(SummaryHighlight.titleRes(h.key)), tint = tint)
        Text(
            highlightSentence(h),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        when (val e = h.evidence) {
            is ReadinessEngine.Evidence.MetricVsBaseline -> {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val unit = localizedUnit(highlightUnit(e.unit))
                FigurePair(
                    stringResource(R.string.metric_latest_label), highlightNumber(e.value, e.decimals, locale) + " " + unit,
                    stringResource(R.string.summary_your_normal), highlightNumber(e.baseline, e.decimals, locale) + " " + unit,
                    e.value, e.baseline, tint,
                )
            }
            is ReadinessEngine.Evidence.TrainingLoad -> {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FigurePair(
                    stringResource(R.string.summary_last_7_days), highlightNumber(e.acute, 1, locale),
                    stringResource(R.string.summary_last_28_days), highlightNumber(e.chronic, 1, locale),
                    e.acute, e.chronic, tint,
                )
            }
            else -> if (h.key == "monotony" && effortWeek.size >= 2) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                WeekBars(effortWeek, tint, Modifier.fillMaxWidth().height(64.dp))
                Text(
                    stringResource(R.string.summary_effort_last_7_days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FigurePair(
    leftTitle: String,
    left: String,
    rightTitle: String,
    right: String,
    leftValue: Double,
    rightValue: Double,
    tint: Color,
) {
    val top = maxOf(leftValue, rightValue, 1e-6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FigureBar(leftTitle, left, (leftValue / top).toFloat(), tint, tint)
        FigureBar(
            rightTitle, right, (rightValue / top).toFloat(),
            MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun FigureBar(title: String, value: String, fraction: Float, textColor: Color, barColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = textColor,
                modifier = Modifier.widthIn(min = 72.dp),
                maxLines = 1,
            )
            Box(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
            }
        }
    }
}

/** A week of daily bars, the last solid and the rest faded. */
@Composable
internal fun WeekBars(values: List<Double>, tint: Color, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val top = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-6)
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = minOf(22.dp.toPx(), slot * 0.7f)
        values.forEachIndexed { i, v ->
            val h = (v / top * size.height).toFloat().coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                tint.copy(alpha = if (i == values.lastIndex) 1f else 0.4f),
                Offset(slot * i + (slot - w) / 2, size.height - h),
                Size(w, h),
                CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}
