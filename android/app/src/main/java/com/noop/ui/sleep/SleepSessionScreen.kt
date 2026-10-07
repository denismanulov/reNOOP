package com.noop.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import com.noop.data.HrBucket
import com.noop.ui.AppViewModel
import com.noop.ui.ClockPrefs
import com.noop.ui.Stages
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LevelBadge
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - One sleep's page (a night, or a nap)
//
// Opened from a row of the day's sleeps, on the Sleep tab or on the Summary. Top to bottom: when it was; how
// long, and how much of it was deep and REM, each beside the reader's usual, with a sentence on that share;
// the stages over the clock; each stage as a bar of the sleep with what it is for, its time, its share and
// (for a night with enough history) the reader's usual range marked on the bar and named in a word; then
// heart rate through the sleep. A nap is read on its own figures: it has no usual to be held against.

/** The route argument that means "the day's main night" rather than one of its naps. */
internal const val SLEEP_SESSION_NIGHT = 0L

/** The route of one sleep's page: the day it ended on, and the nap's start or [SLEEP_SESSION_NIGHT]. */
internal fun sleepSessionRoute(day: String, napStartTs: Long): String =
    "sleep_session/" + android.net.Uri.encode(day) + "/" + napStartTs

/** What the page draws, whichever kind of sleep it is. */
private class SleepSession(
    val nap: Boolean,
    val day: java.time.LocalDate,
    val startTs: Long,
    val endTs: Long,
    /** Null for a nap that stored no stages. */
    val stages: Stages?,
    val spans: List<SleepStageSpan>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepSessionScreen(vm: AppViewModel, day: String, napStartTs: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf<SleepNights?>(null) }
    LaunchedEffect(days, SleepNightsLoader.revision, vm.activeStrapId) { data = SleepNightsLoader.load(vm, days) }
    val nights = data ?: SleepNights.EMPTY
    val isNap = napStartTs != SLEEP_SESSION_NIGHT

    val session = remember(nights, day, napStartTs) {
        val index = nights.index(day)
        if (index < 0) return@remember null
        if (isNap) {
            nights.heroNight(index)?.napBlocks?.firstOrNull { it.effectiveStartTs == napStartTs }
                ?.let { SleepNightsLoader.nap(it) }
                ?.let { SleepSession(true, localDate(it.endTs), it.startTs, it.endTs, it.stages, it.spans) }
        } else {
            nights.details.getOrNull(index)
                ?.let { SleepSession(false, it.day, it.onsetTs, it.wakeTs, it.stages, it.spans) }
        }
    }
    // The day row the night is scored from, resolved as the tab resolves it, so the two pages read one row.
    val rowKey = remember(nights, day, isNap) {
        if (isNap) null else nights.index(day).takeIf { it >= 0 }?.let { nights.heroNight(it)?.dayKey ?: nights.details.getOrNull(it)?.dayKey }
    }
    // A nap is held against nothing: the usual figures and ranges are the nights' own.
    val ranges = remember(nights, rowKey) {
        rowKey?.let { SleepStageRanges.make(nights.days, it) }.orEmpty()
    }
    val restorativeLevel = remember(nights, rowKey) {
        rowKey?.let { SleepScore.make(nights.dailyRow(it), nights.imported.performance[it]) }
            ?.parts?.firstOrNull { it.part == SleepScorePart.RESTORATIVE }?.level
    }
    val model = if (isNap) null else nights.model

    var heart by remember { mutableStateOf<List<HrBucket>>(emptyList()) }
    LaunchedEffect(session?.startTs, session?.endTs, vm.activeStrapId) {
        val s = session
        heart = if (s == null) emptyList() else runCatching {
            vm.repo.hrBucketsUnion(vm.activeStrapId, s.startTs, s.endTs, HEART_BUCKET_SEC)
        }.getOrDefault(emptyList()).filter { it.avgBpm > 0 && it.bucket in s.startTs..s.endTs }
    }
    var explain by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(stringResource(if (isNap) R.string.sleep_nap else R.string.nav_sleep), onBack)
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 4.dp, bottom = M3Dimens.bottomBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (session == null) {
                if (data != null) {
                    item {
                        HealthCard {
                            Text(
                                stringResource(R.string.sleep_no_data),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                return@LazyColumn
            }
            item(key = "when") {
                Column(Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {}) {
                    Text(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(session.day),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        clockRange(session.startTs, session.endTs, is24h, locale),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            val stages = session.stages
            if (stages == null) {
                item(key = "no-stages") {
                    HealthCard {
                        Text(
                            stringResource(R.string.sleep_nap_no_stages),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "figures") {
                    val usualRestorative = model?.typicalDeepMin?.let { deep -> model.typicalRemMin?.let { deep + it } }
                    SleepFiguresCard(stages, model?.typicalTotalMin, usualRestorative, restorativeLevel, locale)
                }
                item(key = "stages-header") {
                    SectionHeader(
                        stringResource(R.string.sleep_section_stages),
                        action = stringResource(R.string.sleep_explain_action),
                        onAction = { explain = true },
                    )
                }
                if (session.spans.isNotEmpty()) {
                    item(key = "chart") {
                        HealthCard {
                            SleepStagesChart(session.spans, session.startTs, stages, Modifier.fillMaxWidth().height(220.dp))
                        }
                    }
                }
                item(key = "stage-rows") { SleepStageBarsCard(stages, ranges, locale) }
            }
            if (heart.size >= 2) {
                item(key = "heart") { SleepHeartCard(heart, session.startTs, session.endTs, is24h, locale) }
            }
        }
    }
    if (explain) SleepExplainSheet(SleepExplain.STAGES, debtNeedMin = null, onDismiss = { explain = false })
}

/** The size of a heart-rate point on the page: three minutes, fine enough to show a night's shape. */
private const val HEART_BUCKET_SEC = 180L

// MARK: Figures

/**
 * The sleep's two headline figures side by side, each over the reader's usual when there is one; the time in
 * bed under them; then a sentence on the share of the sleep that was deep and REM. [restorativeLevel] is how
 * the night's score rated that share: with it the sentence says whether the share is a healthy one, without
 * it (a nap, a night with no score) the sentence states the share and nothing more.
 */
@Composable
private fun SleepFiguresCard(
    stages: Stages,
    usualAsleepMin: Double?,
    usualRestorativeMin: Double?,
    restorativeLevel: SleepScoreWord?,
    locale: Locale,
) {
    val restorative = stages.deep + stages.rem
    HealthCard(verticalSpacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure(stringResource(R.string.sleep_factor_duration), stages.asleep, usualAsleepMin, Modifier.weight(1f))
            Figure(stringResource(R.string.sleep_factor_restorative), restorative, usualRestorativeMin, Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.sleep_session_in_bed, sleepDuration(stages.total)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stages.asleep > 0) {
            val share = percent(restorative / stages.asleep, locale)
            Text(
                when (restorativeLevel) {
                    null -> stringResource(R.string.sleep_session_restorative, share)
                    SleepScoreWord.OPTIMAL, SleepScoreWord.GOOD -> stringResource(R.string.sleep_session_restorative_good, share)
                    SleepScoreWord.FAIR, SleepScoreWord.POOR -> stringResource(R.string.sleep_session_restorative_low, share)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Figure(label: String, minutes: Double, usualMin: Double?, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            sleepDuration(minutes),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (usualMin != null) {
            Text(
                stringResource(R.string.sleep_more_usually, sleepDuration(usualMin)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: Stage bars

@Composable
private fun stageNote(row: SleepStageRow): String = stringResource(
    when (row) {
        SleepStageRow.AWAKE -> R.string.sleep_stage_awake_note
        SleepStageRow.REM -> R.string.sleep_stage_rem_note
        SleepStageRow.CORE -> R.string.sleep_stage_light_note
        SleepStageRow.DEEP -> R.string.sleep_stage_deep_note
    },
)

@Composable
private fun stageLevel(level: SleepVitals.Level): String = stringResource(
    when (level) {
        SleepVitals.Level.LOW -> R.string.sleep_stage_less
        SleepVitals.Level.TYPICAL -> R.string.sleep_vitals_typical
        SleepVitals.Level.HIGH -> R.string.sleep_stage_more
    },
)

/**
 * Each stage in the chart's own order: its colour, name and share, what it is for, its time, and a bar as
 * long as its part of the sleep. Where the reader has a usual range for the stage ([ranges]), the range is
 * marked on the bar and the row says in a word whether the night fell inside it.
 */
@Composable
private fun SleepStageBarsCard(stages: Stages, ranges: Map<SleepStageRow, SleepStageRange>, locale: Locale) {
    val shares = remember(stages) {
        SleepPeriodSummary(1, stages.total, stages.asleep, SleepStageRow.entries.associateWith { stages.minutes(it) }, null, null)
    }
    val bandColor = MaterialTheme.colorScheme.onSurface
    HealthCard(verticalSpacing = 18.dp) {
        if (ranges.isNotEmpty()) {
            Row(
                Modifier.clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(Modifier.size(22.dp, 12.dp)) { drawUsualBand(0f, size.width, bandColor) }
                Text(
                    stringResource(R.string.sleep_stage_usual_range),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SleepStageRow.entries.forEach { row ->
            val minutes = stages.minutes(row)
            val range = ranges[row]
            val level = range?.level(minutes)
            val color = stageColor(row)
            val track = MaterialTheme.colorScheme.surfaceContainerHighest
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Dot(color, 12.dp)
                            Text(
                                stageName(row),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            shares.sharePercent(row)?.let {
                                Text(
                                    percent(it / 100.0, locale),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(stageNote(row), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            sleepDuration(minutes),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        if (level != null) {
                            LevelBadge(
                                stageLevel(level),
                                if (level == SleepVitals.Level.TYPICAL) Health.colors.vitalsTypical else Health.colors.vitalsOutlier,
                            )
                        }
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(18.dp).clearAndSetSemantics {}) {
                    val total = max(1.0, stages.total)
                    val barH = 12.dp.toPx()
                    val top = (size.height - barH) / 2
                    val r = CornerRadius(barH / 2, barH / 2)
                    drawRoundRect(track, Offset(0f, top), Size(size.width, barH), r)
                    val w = (minutes / total * size.width).toFloat().coerceIn(0f, size.width)
                    if (w > 0f) drawRoundRect(color, Offset(0f, top), Size(max(w, barH), barH), r)
                    if (range != null) {
                        val x1 = (range.low / total * size.width).toFloat().coerceIn(0f, size.width)
                        val x2 = (range.high / total * size.width).toFloat().coerceIn(0f, size.width)
                        drawUsualBand(x1, x2, bandColor)
                    }
                }
            }
        }
    }
}

/** The usual range on a bar: a pale band the full height of the canvas, closed by a dashed line at each end. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUsualBand(x1: Float, x2: Float, color: Color) {
    drawRect(color.copy(alpha = 0.14f), Offset(x1, 0f), Size(max(0f, x2 - x1), size.height))
    val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
    for (x in listOf(x1, x2)) {
        val cx = x.coerceIn(0.75.dp.toPx(), size.width - 0.75.dp.toPx())
        drawLine(color.copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, size.height), 1.5.dp.toPx(), pathEffect = dash)
    }
}

// MARK: Heart rate

/**
 * Heart rate through the sleep: the lowest and highest beat as figures, then the three-minute means as a
 * line between the sleep's two times. The figures are the samples' own extremes ([HrBucket.minBpm],
 * [HrBucket.maxBpm]), not the extremes of the means the line draws, and the chart's two rules are drawn
 * round those same extremes: an axis fitted to the means would print a narrower pair of numbers under a
 * headline that says otherwise.
 */
@Composable
private fun SleepHeartCard(points: List<HrBucket>, startTs: Long, endTs: Long, is24h: Boolean, locale: Locale) {
    val hue = Health.colors.heart
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lowest = points.minOf { if (it.minBpm > 0) it.minBpm else it.avgBpm }.roundToInt()
    val highest = points.maxOf { if (it.maxBpm > 0) it.maxBpm else it.avgBpm }.roundToInt()
    val range = if (lowest == highest) stringResource(R.string.sleep_bpm_value, lowest)
        else stringResource(R.string.sleep_bpm_range, lowest, highest)
    HealthCard(verticalSpacing = 10.dp) {
        CardTitleRow(icon = Icons.Filled.Favorite, title = stringResource(R.string.sleep_more_heart_rate), tint = hue, chevron = false)
        Text(
            range,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Canvas(Modifier.fillMaxWidth().height(120.dp).clearAndSetSemantics { contentDescription = range }) {
            val lo = floor(lowest / 5.0) * 5
            val hi = max(lo + 10, ceil(highest / 5.0) * 5)
            val labels = listOf(hi, lo).map { measurer.measure(it.roundToInt().toString(), labelStyle) }
            val axisW = labels.maxOf { it.size.width } + 6.dp.toPx()
            val plotW = size.width - axisW
            val pad = labels.first().size.height / 2f
            val plotH = size.height - 2 * pad
            fun y(v: Double) = pad + (plotH * (1 - (v - lo) / (hi - lo))).toFloat()
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            listOf(hi, lo).forEachIndexed { i, v ->
                drawLine(grid, Offset(0f, y(v)), Offset(plotW, y(v)), 1.dp.toPx(), pathEffect = dash)
                drawText(labels[i], topLeft = Offset(plotW + 6.dp.toPx(), y(v) - labels[i].size.height / 2f))
            }
            val span = max(1L, endTs - startTs).toFloat()
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = (p.bucket - startTs) / span * plotW
                if (i == 0) path.moveTo(x, y(p.avgBpm)) else path.lineTo(x, y(p.avgBpm))
            }
            drawPath(path, hue, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            Text(clockLabel(startTs, is24h, locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(clockLabel(endTs, is24h, locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
