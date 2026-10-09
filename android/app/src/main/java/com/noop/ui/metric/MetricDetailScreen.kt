package com.noop.ui.metric

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.DaytimeStress
import com.noop.analytics.FitnessAgeEngine
import com.noop.analytics.SkinTempDisplay
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.DisplayText
import com.noop.ui.KeyMetricPrefs
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.UnitPrefs
import com.noop.ui.VitalReading
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PeriodSegmented
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.RowIcon
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.color
import com.noop.ui.m3.metricHue
import com.noop.ui.provenanceDisplayLabel
import com.noop.ui.spo2EmptyState
import com.noop.ui.uiString
import com.noop.ui.vo2MaxAttributionSource
import com.noop.ui.vo2MaxTrendHasBreak
import com.noop.ui.vo2MaxTrendSegmentIds
import java.time.LocalDate
import java.util.Locale

// MARK: - Metric page (twin of iOS MetricDetailView)
//
// The one page every metric opens on, laid out like a data type's page in Health: the W / M / 6M / Y
// picker, a card with "AVERAGE", the period's figure and dates (ⓘ explains the metric), the metric's own
// chart (press and drag to read one mark) and its latest reading; the Stress page's hour-by-hour "Today";
// a Highlights card (the latest reading against the two-week average); and Options (all data, the full day
// by the second, pin to Summary, data sources). Every figure comes from MetricHealthSeries over the series
// MetricSeriesLoader reads, the same read the All Metrics card and the trend card use.

/** The segmented control's labels and TalkBack words, in range order. */
@Composable
internal fun rangeLabels(): Pair<List<String>, List<String>> =
    listOf(
        stringResource(R.string.metric_range_w), stringResource(R.string.metric_range_m),
        stringResource(R.string.metric_range_6m), stringResource(R.string.metric_range_y),
    ) to listOf(
        stringResource(R.string.metric_range_week), stringResource(R.string.metric_range_month),
        stringResource(R.string.metric_range_six_months), stringResource(R.string.metric_range_year),
    )

/** A unit id ("ms", "bpm", "hr", "min", "steps", "kg"…) in the reader's language; symbols pass through. */
internal fun localizedUnit(unit: String): String = when (unit) {
    "ms" -> uiString(R.string.metric_unit_ms)
    "bpm" -> uiString(R.string.metric_unit_bpm)
    "rpm" -> uiString(R.string.metric_unit_rpm)
    "kcal" -> uiString(R.string.metric_unit_kcal)
    "g" -> uiString(R.string.metric_unit_g)
    "yrs" -> uiString(R.string.metric_unit_yrs)
    "kg" -> uiString(R.string.metric_unit_kg)
    "lb" -> uiString(R.string.metric_unit_lb)
    "hr" -> uiString(R.string.metric_unit_hr)
    "min" -> uiString(R.string.metric_unit_min)
    "steps" -> uiString(R.string.metric_unit_steps)
    else -> unit
}

/** [value] of [metric] as number and unit runs, in the reader's units and language. */
internal fun metricTokens(metric: MetricDescriptor, value: Double, units: MetricUnits, locale: Locale): List<MetricToken> =
    MetricHealthStyle.tokens(metric.key, metric.unit, metric.decimals, value, units, locale, ::localizedUnit)

/** The reader's display units. */
internal fun metricUnits(context: android.content.Context): MetricUnits =
    MetricUnits(UnitPrefs.system(context), UnitPrefs.temperature(context), UnitPrefs.effortScale(context))

/** Numbers large, units smaller and quieter, on one baseline. */
@Composable
internal fun MetricFigure(
    tokens: List<MetricToken>,
    numberStyle: TextStyle,
    unitStyle: TextStyle,
    modifier: Modifier = Modifier,
    numberColor: Color = MaterialTheme.colorScheme.onSurface,
    unitColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        tokens.forEach { t ->
            Text(
                t.text,
                style = if (t.isUnit) unitStyle else numberStyle.copy(fontFeatureSettings = "tnum"),
                color = if (t.isUnit) unitColor else numberColor,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    vm: AppViewModel,
    key: String,
    source: String?,
    onBack: () -> Unit,
    onOpenAllData: (MetricDescriptor) -> Unit,
    onOpenFullDay: () -> Unit,
    onOpenDataSources: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = remember(locale) { MetricDateLabels.firstDayOfWeek(locale) }
    val units = remember { metricUnits(context) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    // Reload when the day history moves (a sync, an import, a re-score).
    val revision = remember(days) { days.size to days.lastOrNull() }

    var metric by remember(key, source) { mutableStateOf(source?.let { MetricCatalog.metric(key, it) }) }
    var page by remember(key, source) { mutableStateOf<MetricPageSeries?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, source, revision, reloadTick) {
        val ctx = MetricSeriesLoader.context(vm, context)
        val resolved = metric ?: MetricSeriesLoader.resolve(key, ctx)
        metric = resolved
        page = resolved?.let { MetricSeriesLoader.load(it, ctx) } ?: MetricPageSeries()
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = metric?.title.orEmpty(), onBack = onBack, scrollBehavior = scrollBehavior)
        val d = metric
        if (d == null) {
            if (page != null) EmptyState(icon = Icons.Outlined.Info, title = stringResource(R.string.metric_no_data))
            return@Column
        }
        MetricPage(
            vm = vm,
            metric = d,
            page = page,
            units = units,
            locale = locale,
            firstDay = firstDay,
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            onReload = { reloadTick++ },
            onOpenAllData = { onOpenAllData(d) },
            onOpenFullDay = onOpenFullDay,
            onOpenDataSources = onOpenDataSources,
        )
    }
}

@Composable
private fun MetricPage(
    vm: AppViewModel,
    metric: MetricDescriptor,
    page: MetricPageSeries?,
    units: MetricUnits,
    locale: Locale,
    firstDay: java.time.DayOfWeek,
    modifier: Modifier,
    onReload: () -> Unit,
    onOpenAllData: () -> Unit,
    onOpenFullDay: () -> Unit,
    onOpenDataSources: () -> Unit,
) {
    val context = LocalContext.current
    val series = page?.series.orEmpty()
    val loaded = page != null
    val hue = metricHue(metric.key, metric.category)
    val tint = hue.color
    val category = AllMetricsCatalog.category(metric)
    var rangeIndex by rememberSaveable(metric.id) { mutableIntStateOf(0) }
    val range = MetricRange.entries[rangeIndex]
    // The page's one today: the chart's window ends on it and "Latest" is stamped against it, the same day
    // the Summary calls Today (not the calendar day, which is already tomorrow between midnight and 04:00).
    val todayRow by vm.today.collectAsStateWithLifecycle()
    val today = remember(todayRow?.day) { AppToday.now(todayRow?.day) }
    val window = remember(series, range, firstDay, today) { MetricHealthSeries.window(series, range, today.date, firstDay) }
    val spec = remember(metric, series) { MetricHealthStyle.chart(metric.key, metric.unit, series.map { it.second }) }
    var selection by remember(window) { mutableStateOf<MetricPoint?>(null) }
    val showsImportState = loaded && series.isEmpty() && metric.key != "fitness_age"

    // VO₂max: each mark's estimator run, so the line never joins two methods.
    val readings = remember(window, page) {
        window.days.map { (day, v) -> VitalReading(day, v, page?.sourceByDay?.get(day) ?: vo2MaxAttributionSource(null)) }
    }
    val segments = remember(readings, metric) {
        if (metric.key != "vo2max_est") emptyMap() else {
            val byDay = readings.map { it.day }.zip(vo2MaxTrendSegmentIds(readings)).toMap()
            window.points.associate { it.start to (byDay[it.lastDay] ?: "line") }
        }
    }
    val notes = buildList {
        when (page?.skinTempNote) {
            SkinTempNote.FALLBACK_TO_DEVIATION -> add(stringResource(R.string.metric_note_skin_fallback))
            SkinTempNote.SHORTENED_TO_ABSOLUTE -> add(stringResource(R.string.metric_note_skin_shortened))
            null -> Unit
        }
        if (metric.key == "vo2max_est" && vo2MaxTrendHasBreak(readings)) add(stringResource(R.string.vo2max_method_change_caption))
    }
    // Null while the read runs; a failed read lands as EMPTY so the loading card does not stay up forever.
    val stressDay by produceState<DaytimeStress.Result?>(null, metric.id) {
        value = if (stressDayApplies(metric)) {
            runCatching { loadStressDay(vm, NoopPrefs.stressPersonalBaseline(context)) }
                .getOrDefault(DaytimeStress.Result.EMPTY)
        } else null
    }
    // #1617: a strap that cannot fill Blood Oxygen says why rather than only "No Data".
    val strapFamily by produceState<com.noop.protocol.DeviceFamily?>(null, metric.key) {
        if (metric.key == "spo2") {
            val row = runCatching { vm.pairedDevices() }.getOrDefault(emptyList()).firstOrNull { it.id == vm.activeStrapId }
            value = com.noop.protocol.DeviceFamily.forRegistryDevice(row?.model, row?.brand)
        }
    }
    val spo2State = spo2EmptyState(metric.key, strapFamily, NoopPrefs.spo2CandidateDisplay(context))
    val spo2Explained = metric.key == "spo2" && spo2State != spo2EmptyState("", null, false)
    val emptyTitle = if (spo2Explained) stringResource(spo2State.titleRes) else stringResource(R.string.metric_no_data)
    val emptyMessage = if (spo2Explained) stringResource(spo2State.bodyRes) else null
    val (rangeShort, rangeSpoken) = rangeLabels()
    val tokensOf: (Double) -> List<MetricToken> = { metricTokens(metric, it, units, locale) }
    val textOf: (Double) -> String = { MetricHealthStyle.text(tokensOf(it)) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = M3Dimens.screenPadding)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeriodSegmented(
            options = rangeShort,
            selectedIndex = rangeIndex,
            onSelect = { rangeIndex = it },
            contentDescriptions = rangeSpoken,
        )
        ChartCard(
            metric = metric,
            series = series,
            loaded = loaded,
            window = window,
            spec = spec,
            tint = tint,
            categoryIcon = category.icon,
            selection = selection,
            onSelect = { selection = it },
            showsImportState = showsImportState,
            emptyTitle = emptyTitle,
            emptyMessage = emptyMessage,
            notes = notes,
            segments = segments,
            locale = locale,
            firstDay = firstDay,
            today = today,
            units = units,
            tokensOf = tokensOf,
            textOf = textOf,
            onOpenDataSources = onOpenDataSources,
        )
        if (stressDay == null && stressDayApplies(metric)) MetricStressDayLoading(today, locale)
        stressDay?.let { day ->
            MetricStressDayCard(
                day = day,
                figure = { v -> MetricFigure(tokensOf(v), MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold), MaterialTheme.typography.titleLarge) },
                tint = tint,
                locale = locale,
                today = today,
            )
        }
        if (loaded && series.isEmpty() && metric.key == "fitness_age") FitnessAgeEmptyCard(vm, days = vm.recentDays.value, onReload = onReload)
        MetricHealthSeries.highlight(series)?.let { h ->
            SectionHeader(stringResource(R.string.metric_highlights))
            HighlightCard(metric, h, tint, category.icon, tokensOf, locale)
        }
        SectionHeader(stringResource(R.string.metric_options))
        OptionsGroup(metric, page, onOpenAllData, onOpenFullDay, onOpenDataSources, vm.activeStrapId)
    }
}

@Composable
private fun ChartCard(
    metric: MetricDescriptor,
    series: List<Pair<String, Double>>,
    loaded: Boolean,
    window: MetricWindow,
    spec: MetricChartSpec,
    tint: Color,
    categoryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    selection: MetricPoint?,
    onSelect: (MetricPoint?) -> Unit,
    showsImportState: Boolean,
    emptyTitle: String,
    emptyMessage: String?,
    notes: List<String>,
    segments: Map<LocalDate, String>,
    locale: Locale,
    firstDay: java.time.DayOfWeek,
    today: AppToday,
    units: MetricUnits,
    tokensOf: (Double) -> List<MetricToken>,
    textOf: (Double) -> String,
    onOpenDataSources: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var showAbout by remember { mutableStateOf(false) }
    val aboutRes = MetricHealthStyle.aboutRes(metric.key, metric.descriptionRes)

    // "AVERAGE" · figure · dates, or while a mark is held that mark's figure and date.
    val picked = selection
    val stateWord = picked?.takeIf { spec.chargeStateWord }?.let { stringResource(MetricHealthStyle.chargeStateRes(it.value)) }
    val aggregate = if (picked != null && window.range.bucket == MetricBucket.DAY) {
        if (MetricHealthStyle.isTotal(metric.key, metric.unit)) stringResource(R.string.metric_caption_total) else null
    } else stringResource(R.string.metric_caption_average)
    val value = picked?.value ?: window.average
    val deviation = if (metric.unit == "°C" && value != null && SkinTempDisplay.kind(value) == SkinTempDisplay.Kind.DEVIATION) {
        stringResource(R.string.metric_caption_deviation).uppercase(locale)
    } else null
    val words = listOfNotNull(aggregate, deviation, stateWord)
    val caption = if (words.isEmpty() || showsImportState) " " else words.joinToString(" · ")
    val dates = picked?.let { MetricDateLabels.point(it, window.range, locale) } ?: MetricDateLabels.span(window, locale)

    HealthCard(
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 0.dp,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(caption, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurfaceVariant)
                if (value != null) {
                    MetricFigure(
                        tokensOf(value),
                        MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                        MaterialTheme.typography.titleLarge,
                    )
                } else {
                    Text(
                        if (loaded && !showsImportState) stringResource(R.string.metric_no_data) else " ",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                Text(dates, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
            if (aboutRes != null) {
                FilledTonalIconButton(onClick = { showAbout = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.metric_about_a11y, metric.title), modifier = Modifier.size(20.dp))
                }
            }
        }
        val chartBox = if (showsImportState) Modifier.heightIn(min = 240.dp) else Modifier.height(240.dp)
        Box(Modifier.fillMaxWidth().then(chartBox).padding(top = 16.dp), contentAlignment = Alignment.Center) {
            when {
                // Nothing recorded: one way to fill it, in place of an empty chart.
                showsImportState -> EmptyState(
                    icon = categoryIcon,
                    title = emptyTitle,
                    message = emptyMessage,
                    action = stringResource(R.string.metric_import_history),
                    onAction = onOpenDataSources,
                )
                loaded -> {
                    val average = window.average
                    val description = buildString {
                        append(metric.title)
                        if (average != null) append(". ").append(uiString(R.string.metric_chart_average, textOf(average)))
                        window.points.forEach { p ->
                            append(". ").append(MetricDateLabels.spoken(p, window.range, locale)).append(": ").append(textOf(p.value))
                            if (spec.chargeStateWord) append(", ").append(uiString(MetricHealthStyle.chargeStateRes(p.value)).lowercase(locale).replaceFirstChar { it.titlecase(locale) })
                        }
                    }
                    MetricHealthChart(
                        window = window,
                        spec = spec,
                        tint = tint,
                        locale = locale,
                        firstDay = firstDay,
                        axisLabel = { v ->
                            MetricHealthStyle.axisLabel(
                                metric.key, metric.unit, v, units, window.points.maxOfOrNull { it.value } ?: 0.0,
                                locale, localizedUnit("hr"),
                            )
                        },
                        valueText = textOf,
                        description = description,
                        selection = selection,
                        onSelect = onSelect,
                        segments = segments,
                        height = 224.dp,
                    )
                }
                else -> CircularProgressIndicator()
            }
        }
        notes.forEach { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        series.lastOrNull()?.let { (day, v) ->
            HorizontalDivider(Modifier.padding(top = 16.dp), color = colors.outlineVariant)
            val stamp = MetricDateLabels.stamp(day, today, locale)
            val latestText = stringResource(R.string.metric_latest, stamp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clearAndSetSemantics { contentDescription = listOf(latestText, textOf(v)).joinToString(", ") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(latestText, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                MetricFigure(
                    tokensOf(v),
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showAbout && aboutRes != null) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(metric.title) },
            text = { Text(stringResource(aboutRes)) },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.metric_done)) } },
        )
    }
}

/**
 * Health's highlight on a data type's page: the category line, one sentence, the two-week average and the
 * latest figure, then the fortnight's readings as bars with the latest in the metric's hue and the
 * average drawn across them. The scale starts below the lowest reading so day-to-day movement shows.
 */
@Composable
private fun HighlightCard(
    metric: MetricDescriptor,
    h: MetricHighlight,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tokensOf: (Double) -> List<MetricToken>,
    locale: Locale,
) {
    val colors = MaterialTheme.colorScheme
    val sentence = stringResource(
        when (h.direction) {
            MetricHighlight.Direction.ABOVE -> R.string.metric_highlight_above
            MetricHighlight.Direction.BELOW -> R.string.metric_highlight_below
            MetricHighlight.Direction.CLOSE -> R.string.metric_highlight_close
        },
    )
    val figureStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold)
    val unitStyle = MaterialTheme.typography.titleMedium
    HealthCard(verticalSpacing = 10.dp) {
        CardTitleRow(icon = icon, title = metric.title, tint = tint, chevron = false)
        Text(sentence, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.metric_two_week_average), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                MetricFigure(tokensOf(h.average), figureStyle, unitStyle, numberColor = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.metric_latest_label), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                MetricFigure(tokensOf(h.latest), figureStyle, unitStyle, numberColor = tint)
            }
        }
        val grey = colors.surfaceContainerHighest
        val averageColor = colors.onSurfaceVariant
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp).clearAndSetSemantics {}) {
            val lo0 = h.values.minOrNull() ?: 0.0
            val hi = maxOf(h.values.maxOrNull() ?: 1.0, h.average)
            val lo = if (hi > lo0) maxOf(0.0, lo0 - (hi - lo0) * 0.6) else hi * 0.5
            val span = maxOf(hi - lo, 1e-9)
            val slot = size.width / h.values.size.coerceAtLeast(1)
            val barW = minOf(18.dp.toPx(), slot * 0.55f)
            h.values.forEachIndexed { i, v ->
                val bh = maxOf(3.dp.toPx(), (size.height * ((v - lo) / span)).toFloat())
                drawRoundRect(
                    if (i == h.values.lastIndex) tint else grey,
                    topLeft = Offset(slot * i + (slot - barW) / 2, size.height - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
            val y = size.height - (size.height * ((h.average - lo) / span)).toFloat()
            drawRoundRect(averageColor, topLeft = Offset(0f, y - 1.5.dp.toPx()), size = Size(size.width, 3.dp.toPx()), cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(MetricDateLabels.shortDate(h.firstDay, locale), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(MetricDateLabels.shortDate(h.lastDay, locale), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Options: all data, the day by the second (average heart rate), pin to Summary, data sources. */
@Composable
private fun OptionsGroup(
    metric: MetricDescriptor,
    page: MetricPageSeries?,
    onOpenAllData: () -> Unit,
    onOpenFullDay: () -> Unit,
    onOpenDataSources: () -> Unit,
    strapId: String,
) {
    val context = LocalContext.current
    val pinnable = MetricHealthStyle.pinnable(metric.key)
    var pinned by remember(pinnable) { mutableStateOf(pinnable != null && pinnable in KeyMetricPrefs.enabled(context)) }
    val primary = MaterialTheme.colorScheme.primary
    // The sources that actually supplied a reading, as the All Data rows name them.
    val sources = remember(page, strapId) {
        page?.sourceByDay?.values?.distinct().orEmpty()
            .map { provenanceDisplayLabel(it, strapId) }
            .map { label -> when (label) { is DisplayText.Resource -> uiString(label.id, *label.args.toTypedArray()); is DisplayText.Dynamic -> label.value } }
            .distinct()
            .joinToString(", ")
            .ifEmpty { null }
    }
    val hasData = page?.series?.isNotEmpty() == true
    val showAllData = stringResource(R.string.metric_show_all_data)
    val fullDay = stringResource(R.string.metric_full_day)
    val pinTitle = stringResource(if (pinned) R.string.metric_unpin else R.string.metric_pin)
    val dataSources = stringResource(R.string.metric_data_sources)
    ListGroup {
        if (hasData) item { shape ->
            ListRow(shape, showAllData, leading = { RowIcon(Icons.Filled.TableRows) }, trailing = { ChevronRight() }, onClick = onOpenAllData)
        }
        if (metric.key == "avg_hr") item { shape ->
            ListRow(shape, fullDay, leading = { RowIcon(Icons.Filled.Timeline) }, trailing = { ChevronRight() }, onClick = onOpenFullDay)
        }
        if (pinnable != null) item { shape ->
            ListRow(
                shape, pinTitle,
                titleColor = primary,
                leading = { RowIcon(Icons.Outlined.PushPin, primary) },
                onClick = {
                    val list = KeyMetricPrefs.enabled(context).toMutableList()
                    if (pinned) list.remove(pinnable) else list.add(pinnable)
                    KeyMetricPrefs.setEnabled(context, list)
                    pinned = !pinned
                },
            )
        }
        item { shape ->
            ListRow(
                shape, dataSources, subtitle = sources,
                leading = { RowIcon(Icons.Outlined.Storage) }, trailing = { ChevronRight() }, onClick = onOpenDataSources,
            )
        }
    }
}

/**
 * Fitness Age is computed here from resting heart rate and activity, not imported: its empty page says
 * how many more nights it needs and offers to recompute now from what is stored.
 */
@Composable
private fun FitnessAgeEmptyCard(vm: AppViewModel, days: List<com.noop.data.DailyMetric>, onReload: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val rhrDays = days.takeLast(7).count { it.restingHr != null }
    val remaining = FitnessAgeEngine.nightsUntilReady(rhrDays)
    val needsBasics = profile.age <= 0 || profile.sex.isBlank()
    val lead = when {
        remaining == 0 && !needsBasics -> stringResource(R.string.metric_fitness_age_few_days)
        remaining == 0 -> stringResource(R.string.metric_fitness_age_add_basics)
        remaining == 1 && !needsBasics -> stringResource(R.string.metric_fitness_age_one_night)
        remaining == 1 -> stringResource(R.string.metric_fitness_age_basics_one_night)
        !needsBasics -> pluralStringResource(R.plurals.metric_fitness_age_nights, remaining, remaining)
        else -> pluralStringResource(R.plurals.metric_fitness_age_basics_nights, remaining, remaining)
    }
    var refreshing by remember { mutableStateOf(false) }
    HealthCard {
        Text(lead, style = MaterialTheme.typography.bodyLarge)
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(
                onClick = {
                    refreshing = true
                    vm.refreshFitnessAgeNow {
                        refreshing = false
                        onReload()
                    }
                },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.metric_refresh_fitness_age))
            }
        }
    }
}
