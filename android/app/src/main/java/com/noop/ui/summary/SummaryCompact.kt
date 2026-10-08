package com.noop.ui.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.KeyMetric
import com.noop.ui.Stages
import com.noop.ui.UnitPrefs
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.MiniBarChart
import com.noop.ui.m3.MiniLineChart
import com.noop.ui.m3.ProgressRing
import com.noop.ui.m3.ValueWithUnit
import com.noop.ui.m3.ringFraction
import com.noop.ui.metric.MetricFigure
import com.noop.ui.metric.localizedUnit
import com.noop.ui.metric.metricUnits
import com.noop.ui.trends.HealthTrendCard
import com.noop.ui.trends.HealthTrendItem

// MARK: - Summary layout B (Compact)
//
// The same data and destinations as the Detailed layout, set the way Fitbit sets its Today: a top row with
// the day, a calendar and the profile circle; three score dials; the highlights as banners; the pinned
// metrics as a two-column grid of tiles; an All Metrics button; the trends as a row that scrolls sideways.

private val CompactTileMinHeight = 150.dp

/** The day's title with the calendar and the profile circle beside it. */
@Composable
internal fun CompactTopRow(pager: SummaryPager, actions: SummaryActions) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pager.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = pager.onPick) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = stringResource(R.string.summary_calendar),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AvatarButton(size = 32.dp, onClick = actions.openSettings)
    }
}

/** Charge, Effort and Rest as three dials, each with its glyph and figure inside and its name under it. */
@Composable
internal fun ScoreDialsCard(ui: SummaryUi, actions: SummaryActions) {
    val s = ui.snapshot
    val figures = ringFigures(ui, actions)
    val scale = UnitPrefs.effortScale(LocalContext.current)
    HealthCard(
        shape = RoundedCornerShape(32.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreDial(
                figure = figures[0], icon = Icons.Filled.Bolt, fraction = ringFraction(s.charge.pct, 100.0),
                number = percentTokens(s.charge.pct).first().text, modifier = Modifier.weight(1f),
            )
            ScoreDial(
                figure = figures[1], icon = Icons.Filled.LocalFireDepartment, fraction = ringFraction(s.effort, 100.0),
                number = effortTokens(s.effort, scale, ui.locale).first().text, modifier = Modifier.weight(1f),
            )
            ScoreDial(
                figure = figures[2], icon = Icons.Filled.Bedtime, fraction = ringFraction(s.rest, 100.0),
                number = percentTokens(s.rest).first().text, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScoreDial(
    figure: RingFigureData,
    icon: ImageVector,
    fraction: Float?,
    number: String,
    modifier: Modifier,
) {
    val spoken = listOfNotNull(figure.label, figure.tokens.joinToString("") { it.text }, figure.caption).joinToString(", ")
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = figure.onClick)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProgressRing(
            fraction = fraction,
            color = figure.color,
            modifier = Modifier.widthIn(max = 104.dp).fillMaxWidth().aspectRatio(1f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, tint = figure.color, modifier = Modifier.size(20.dp))
                Text(
                    number,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
        }
        Text(figure.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        figure.caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** A highlight as a tonal banner: the signal's name, its sentence, a chevron. The first is the loudest. */
@Composable
internal fun HighlightBanner(h: SummaryHighlight, primary: Boolean, onClick: () -> Unit) {
    val container = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(M3Dimens.cardRadius),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(SummaryHighlight.titleRes(h.key)),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(highlightSentence(h), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The night still being counted: how long so far, and the button that ends it. Shown instead of
 * leaving a growing total to read as a finished night. The button does not set the wake time; it
 * starts a sync and the detector finds the moment in the strap's data.
 */
@Composable
internal fun SleepInProgressCard(state: SummarySleepInProgress, locale: java.util.Locale, onAwake: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(M3Dimens.cardRadius),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.summary_sleep_in_progress_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(stringResource(R.string.summary_sleep_in_progress_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    MetricFigure(
                        durationTokens(state.asleepMinutes, locale),
                        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        MaterialTheme.typography.titleMedium,
                    )
                }
                androidx.compose.material3.Button(onClick = onAwake) {
                    Text(stringResource(R.string.summary_sleep_in_progress_awake))
                }
            }
        }
    }
}

/** One square of the pinned grid. */
private sealed interface GridTile {
    data class Sleep(val night: SummarySleepNight) : GridTile
    data class Fitness(val slot: Int, val metric: KeyMetric, val reading: SummaryMetricReading) : GridTile
    data class Pinned(val metric: KeyMetric, val reading: SummaryMetricReading) : GridTile
}

/** The night's Sleep, the two fitness tiles and the pinned metrics, two to a row. */
@Composable
internal fun PinnedGrid(
    ui: SummaryUi,
    actions: SummaryActions,
    onChangeTile: (Int, KeyMetric) -> Unit,
    onEdit: () -> Unit,
) {
    val inputs = ui.snapshot.metrics
    val tiles = buildList {
        ui.sleepNight?.let { add(GridTile.Sleep(it)) }
        if (inputs != null) {
            ui.tiles.forEachIndexed { slot, m ->
                SummaryMetricReading.resolve(m, inputs, ui.locale)?.let { add(GridTile.Fitness(slot, m, it)) }
            }
            ui.pinned.forEach { m -> SummaryMetricReading.resolve(m, inputs, ui.locale)?.let { add(GridTile.Pinned(m, it)) } }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
        tiles.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
            ) {
                row.forEach { tile ->
                    val m = Modifier.weight(1f).fillMaxHeight().heightIn(min = CompactTileMinHeight)
                    when (tile) {
                        is GridTile.Sleep -> SleepTile(tile.night, ui, m) { actions.openSleepNight(tile.night.wakeDayKey) }
                        is GridTile.Fitness -> ChangeableCard(
                            current = tile.metric,
                            onClick = { actions.openMetric(tile.reading.routeKey, tile.reading.routeSource) },
                            onChange = { onChangeTile(tile.slot, it) },
                            modifier = m,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) { MetricTileContent(tile.metric, tile.reading, ui) }
                        is GridTile.Pinned -> TileSurface(m, onClick = {
                            actions.openMetric(tile.reading.routeKey, tile.reading.routeSource)
                        }) { MetricTileContent(tile.metric, tile.reading, ui) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (ui.pinned.isEmpty()) PinPromptCard(onEdit)
    }
}

@Composable
private fun TileSurface(modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(M3Dimens.cardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(M3Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** A metric tile: name in its hue, the week, the figure; a carried value names its day. */
@Composable
private fun ColumnScope.MetricTileContent(metric: KeyMetric, reading: SummaryMetricReading, ui: SummaryUi) {
    val tint = keyMetricTint(metric)
    val series = ui.snapshot.series(reading.seriesKey)
    TileTitle(keyMetricIcon(metric), stringResource(metric.titleRes), tint)
    Spacer(Modifier.weight(1f))
    if (reading.chart == SummaryChart.LINE) {
        MiniLineChart(series, tint, Modifier.fillMaxWidth().height(36.dp))
    }
    ValueWithUnit(
        value = reading.value,
        unit = reading.unit.takeIf { it.isNotEmpty() }?.let { localizedUnit(it) },
        valueStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        unitStyle = MaterialTheme.typography.titleSmall,
    )
    if (reading.chart == SummaryChart.BARS) {
        MiniBarChart(series, tint, Modifier.fillMaxWidth().height(36.dp))
    }
    // "Today" goes without saying on this layout; a value from another day says whose it is.
    val stamp = ui.stamp(reading.stampDay).takeIf { it != SummaryStamp.Today }
    val note = listOfNotNull(captionText(reading.caption), stampText(stamp, ui.locale)).joinToString(" · ")
    if (note.isNotEmpty()) {
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun TileTitle(icon: ImageVector, title: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The night as a tile: time asleep and the share of each stage. */
@Composable
private fun SleepTile(night: SummarySleepNight, ui: SummaryUi, modifier: Modifier, onClick: () -> Unit) {
    TileSurface(modifier, onClick) {
        TileTitle(Icons.Filled.Bed, stringResource(R.string.nav_sleep), Health.colors.sleep)
        Spacer(Modifier.weight(1f))
        MetricFigure(
            durationTokens(night.stages.asleep, ui.locale),
            MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            MaterialTheme.typography.titleSmall,
        )
        StageShareBar(night.stages, Modifier.fillMaxWidth().height(8.dp))
    }
}

/** The four stages as one bar, each segment as wide as its share of the night. */
@Composable
private fun StageShareBar(stages: Stages, modifier: Modifier) {
    val c = Health.colors
    val parts = listOf(stages.awake to c.stageAwake, stages.rem to c.stageRem, stages.light to c.stageCore, stages.deep to c.stageDeep)
        .filter { it.first > 0 }
    Row(modifier = modifier.clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (minutes, color) ->
            Box(Modifier.weight(minutes.toFloat()).fillMaxHeight().background(color))
        }
    }
}

/** "All Metrics" as an outlined button, full width. */
@Composable
internal fun AllMetricsButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.browse_all_metrics))
    }
}

/** The leading trends as cards in a row that scrolls sideways, bleeding to the screen edge. */
@Composable
internal fun TrendsCarousel(trends: List<HealthTrendItem>, ui: SummaryUi, actions: SummaryActions) {
    val units = metricUnits(LocalContext.current)
    LazyRow(
        contentPadding = PaddingValues(horizontal = M3Dimens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
    ) {
        items(trends, key = { it.metric.id }) { t ->
            HealthTrendCard(
                item = t,
                units = units,
                locale = ui.locale,
                onClick = { actions.openMetric(t.metric.key, t.metric.source) },
                modifier = Modifier.width(280.dp),
            )
        }
    }
}
