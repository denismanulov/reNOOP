package com.noop.ui.metric

import com.noop.ui.m3.SearchField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.MiniBarChart
import com.noop.ui.m3.MiniLineChart
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.color
import com.noop.ui.m3.metricHue
import com.noop.ui.uiString
import java.time.LocalDate

// MARK: - All Metrics (twin of iOS AllMetricsView)
//
// The whole metric catalogue as Health lays out its data: one section per Health category, one card per
// data type (category glyph and the metric's name in its hue, when it was measured, the latest reading and
// a week's mini chart), each opening that metric's page. Metrics with no readings collapse into one row at
// the bottom. The search field stays under the title, as Settings and Health's search keep it.

/** A metric's card: its newest reading and the week it closes. */
internal data class AllMetricsReading(val day: String, val value: Double, val week: List<Double?>, val bars: Boolean)

/** Builds the card readings from loaded series: the newest reading and the week-range marks. */
internal fun allMetricsReadings(
    loaded: Map<String, MetricPageSeries>,
    metrics: List<MetricDescriptor>,
    today: LocalDate,
    firstDay: java.time.DayOfWeek,
): Map<String, AllMetricsReading> = metrics.mapNotNull { m ->
    val series = loaded[m.id]?.series?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val (day, value) = series.last()
    val week = MetricHealthSeries.window(series, MetricRange.WEEK, today, firstDay).points.map { it.value }
    val mark = MetricHealthStyle.chart(m.key, m.unit, series.map { it.second }).mark
    m.id to AllMetricsReading(day, value, week, mark == MetricMark.BARS)
}.toMap()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllMetricsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenMetric: (MetricDescriptor) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val focusManager = LocalFocusManager.current
    val firstDay = remember(locale) { MetricDateLabels.firstDayOfWeek(locale) }
    val units = remember { metricUnits(context) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val revision = remember(days) { days.size to days.lastOrNull() }
    // The list's one today: each card's week ends on it and each stamp counts from it, as on the Summary.
    val todayRow by vm.today.collectAsStateWithLifecycle()
    val today = remember(todayRow?.day, revision) { AppToday.now(todayRow?.day) }

    var readings by remember { mutableStateOf<Map<String, AllMetricsReading>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showsEmpty by rememberSaveable { mutableStateOf(false) }

    // Which metrics hold readings at all, then those only, together and off the main thread.
    LaunchedEffect(revision, today) {
        val ctx = MetricSeriesLoader.context(vm, context)
        val nonEmpty = MetricSeriesLoader.nonEmptyIds(MetricCatalog.all, ctx)
        val candidates = MetricCatalog.all.filter { it.id in nonEmpty }
        readings = allMetricsReadings(MetricSeriesLoader.loadAll(candidates, ctx), candidates, today.date, firstDay)
        loaded = true
    }

    val titleOf: (MetricDescriptor) -> String = { it.title }
    val categoryTitle: (HealthCategory) -> String = { uiString(it.titleRes) }
    fun matches(m: MetricDescriptor): Boolean = AllMetricsCatalog.matches(
        query, locale, m.title, AllMetricsCatalog.shortTitle(m), categoryTitle(AllMetricsCatalog.category(m)),
    )
    val withData = remember(readings) {
        AllMetricsCatalog.oneSourcePerKey(MetricCatalog.all.filter { readings[it.id] != null }, readings.mapValues { it.value.day })
    }
    val withoutData = remember(withData) {
        val shown = withData.map { it.key }.toSet()
        AllMetricsCatalog.oneSourcePerKey(MetricCatalog.all.filter { it.key !in shown }, emptyMap())
            .let { AllMetricsCatalog.sortedByTitle(it, locale, titleOf) }
    }
    val sections = AllMetricsCatalog.sections(withData.filter(::matches), locale, categoryTitle, titleOf)
    val empty = if (loaded) withoutData.filter(::matches) else emptyList()
    val trimmed = query.trim()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(R.string.browse_all_metrics),
            onBack = onBack,
            large = true,
            scrollBehavior = scrollBehavior,
        )
        SearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.metric_search),
            clearLabel = stringResource(R.string.l10n_workouts_screen_clear_search_67300d0f),
            modifier = Modifier.padding(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 8.dp),
            onSearch = { focusManager.clearFocus() },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (!loaded) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            sections.forEach { section ->
                item(key = "header-${section.category}") { SectionHeader(categoryTitle(section.category)) }
                items(section.metrics, key = { it.id }) { metric ->
                    val reading = readings[metric.id] ?: return@items
                    MetricCard(metric, reading, section.category, today, locale, units) { onOpenMetric(metric) }
                }
            }
            if (empty.isNotEmpty()) {
                item(key = "without-data") {
                    Spacer(Modifier.height(16.dp))
                    WithoutDataGroup(empty, expanded = showsEmpty || trimmed.isNotEmpty(), onToggle = { showsEmpty = !showsEmpty }, onOpen = onOpenMetric)
                }
            }
            if (loaded && sections.isEmpty() && empty.isEmpty() && trimmed.isNotEmpty()) {
                item(key = "no-results") {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.browse_no_results, trimmed),
                        message = stringResource(R.string.browse_no_results_hint),
                    )
                }
            }
        }
    }
}

/** The Health data-type card: category glyph and name in the metric's hue, stamp, figure, the week. */
@Composable
private fun MetricCard(
    metric: MetricDescriptor,
    reading: AllMetricsReading,
    category: HealthCategory,
    today: AppToday,
    locale: java.util.Locale,
    units: MetricUnits,
    onClick: () -> Unit,
) {
    val tint = metricHue(metric.key, metric.category).color
    HealthCard(onClick = onClick, verticalSpacing = 10.dp) {
        CardTitleRow(icon = category.icon, title = metric.title, tint = tint, stamp = MetricDateLabels.stamp(reading.day, today, locale))
        Row(verticalAlignment = Alignment.Bottom) {
            MetricFigure(
                metricTokens(metric, reading.value, units, locale),
                MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val chartModifier = Modifier.size(72.dp, 30.dp)
            if (reading.bars) MiniBarChart(reading.week, tint, chartModifier) else MiniLineChart(reading.week, tint, chartModifier)
        }
    }
}

/** One row that opens the plain list of metrics nothing has recorded yet. Always open while searching. */
@Composable
private fun WithoutDataGroup(
    metrics: List<MetricDescriptor>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (MetricDescriptor) -> Unit,
) {
    val state = stringResource(if (expanded) R.string.settings_disclosure_expanded else R.string.settings_disclosure_collapsed)
    val title = stringResource(R.string.metric_without_data)
    ListGroup {
        item { shape ->
            ListRow(
                shape = shape,
                title = title,
                modifier = Modifier.semantics { stateDescription = state },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(metrics.size.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f),
                        )
                    }
                },
                onClick = onToggle,
            )
        }
        if (expanded) metrics.forEach { m ->
            item { shape -> ListRow(shape, m.title, trailing = { ChevronRight() }, onClick = { onOpen(m) }) }
        }
    }
}
