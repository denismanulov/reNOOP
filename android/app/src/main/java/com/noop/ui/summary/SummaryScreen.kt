package com.noop.ui.summary

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.KeyMetric
import com.noop.ui.KeyMetricPrefs
import com.noop.ui.OnScrollToTop
import com.noop.ui.UnitPrefs
import com.noop.ui.ClockPrefs
import com.noop.ui.m3.LargeTitle
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.SyncFooter
import com.noop.ui.sleep.SleepSessionRows
import com.noop.ui.metric.metricUnits
import com.noop.ui.todayPullToSyncEnabled
import com.noop.ui.trends.HealthTrendCard
import com.noop.ui.trends.HealthTrendItem
import com.noop.ui.trends.HealthTrends
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

// MARK: - Summary (twin of iOS SummaryView, in Material 3)
//
// The home tab. Layout A ("Detailed") is Denis's iOS structure one to one: the large title with the profile
// circle (→ Settings), a raised health alert, the ‹ day › pager whose title opens a calendar, the rings card
// for Charge / Effort / Rest, two fitness tiles the user picks, "Pinned" (the night's Sleep card, the pinned
// metrics, Show All Metrics), "Trends" and "Highlights", and the quiet sync line. Layout B ("Compact") sets
// the same data and destinations Fitbit-style: three score dials, highlight banners, a two-column grid of
// pinned tiles, All Metrics, trends in a row. The layout is chosen in the Edit sheet (and in Settings).
// All data arrives in one [SummarySnapshot] from [SummaryLoader]; this file lays it out and routes taps.

/**
 * Process-lifetime guard for the launch snap to today (#860): the picked day is saveable so a trip to
 * another tab keeps it, but a fresh process always opens on today, as iOS's plain @State does.
 */
private var summarySnappedThisLaunch = false

/** Where the Summary's taps go; the shell resolves each to a tab and a screen. */
internal class SummaryActions(
    val openSettings: () -> Unit,
    /** A metric's page: catalogue key and, when it must be one, the source. */
    val openMetric: (String, String?) -> Unit,
    val openAllMetrics: () -> Unit,
    val openTrends: () -> Unit,
    /** The Sleep tab on the night that ended on this "yyyy-MM-dd" day. */
    val openSleepNight: (String) -> Unit,
    /** A nap's own page: the day it belongs to and the nap's start (unix seconds). */
    val openSleepNap: (String, Long) -> Unit,
)

/** Everything the two layouts draw, resolved once per composition. */
internal class SummaryUi(
    val locale: Locale,
    val dayOffset: Int,
    val selectedKey: String,
    /** The one today the pager, the day keys and every stamp count from. */
    val today: AppToday,
    val snapshot: SummarySnapshot,
    val sleepNight: SummarySleepNight?,
    val trends: List<HealthTrendItem>?,
    val tiles: List<KeyMetric>,
    val pinned: List<KeyMetric>,
    val alert: String?,
    val syncLine: SummarySyncLine,
) {
    /** A card's stamp on the picked day (only carried values are stamped on a past day). */
    fun stamp(dayKey: String?): SummaryStamp? = SummaryStamp.forCard(dayKey, dayOffset, selectedKey, today)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SummaryScreen(vm: AppViewModel, actions: SummaryActions) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val todayRow by vm.today.collectAsStateWithLifecycle()
    val cycle by vm.activeDayCycle.collectAsStateWithLifecycle()
    val spo2Candidates by vm.spo2CandidateByDay.collectAsStateWithLifecycle()
    val alert by vm.healthAlert.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    // The ~1 Hz heart-rate tick must not redraw the page: only the sync fields matter here.
    val sync by remember {
        derivedStateOf {
            val s = live
            SyncSnap(s.backfilling, s.lastSyncAt, s.connected, s.bonded, s.historyReady)
        }
    }

    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    if (!summarySnappedThisLaunch) {
        summarySnappedThisLaunch = true
        if (dayOffset != 0) dayOffset = 0
    }
    val layout = SummaryLayoutPrefs.layout(context)
    var tiles by remember { mutableStateOf(SummaryTilePrefs.tiles(context)) }
    var enabled by remember { mutableStateOf(KeyMetricPrefs.enabled(context)) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    // The sync line's "5 minutes ago" moves on by itself; the same tick carries today over the 04:00 roll.
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    // One clock read for the whole screen: the pager title, the day keys, the loader and every stamp take
    // this value, so they cannot name different days (a second read could land the other side of 04:00).
    val today = remember(todayRow?.day, nowSec) { AppToday.now(todayRow?.day) }
    val selectedKey = today.minusDays(dayOffset).toString()
    val maxOffset = SummaryDay.maxDayOffset(days.firstOrNull()?.day, today.key)

    var snapshot by remember { mutableStateOf(SummarySnapshot()) }
    LaunchedEffect(days, todayRow, cycle, spo2Candidates, dayOffset, reloadTick, today) {
        snapshot = SummaryLoader.load(vm, context, dayOffset, days, todayRow, cycle, spo2Candidates, today)
    }
    var sleepNight by remember { mutableStateOf<SummarySleepNight?>(null) }
    LaunchedEffect(days, selectedKey, reloadTick) {
        sleepNight = SummaryLoader.sleepNight(vm, days, selectedKey)
    }
    // Health's Trends as of now, not the picked day: the first few lead the section.
    var trends by remember { mutableStateOf<List<HealthTrendItem>?>(null) }
    LaunchedEffect(days, reloadTick) {
        trends = runCatching { HealthTrends.top(vm, context, limit = 3) }.getOrNull()
    }
    val ui = SummaryUi(
        locale = locale,
        dayOffset = dayOffset,
        selectedKey = selectedKey,
        today = today,
        snapshot = snapshot,
        sleepNight = sleepNight?.takeIf { it.wakeDayKey == selectedKey && it.stages.asleep > 0 },
        trends = trends,
        tiles = tiles,
        pinned = SummaryPins.pinned(enabled, tiles),
        alert = alert,
        syncLine = SummarySyncLine.resolve(sync.backfilling, sync.lastSyncAt, maxOf(nowSec, System.currentTimeMillis() / 1000)),
    )
    val onChangeTile: (Int, KeyMetric) -> Unit = { slot, metric -> tiles = SummaryTilePrefs.replace(context, slot, metric) }
    val pager = SummaryPager(
        title = dayTitle(dayOffset, today.minusDays(dayOffset), locale),
        canGoBack = dayOffset < maxOffset,
        canGoForward = dayOffset > 0,
        onBack = { dayOffset = (dayOffset + 1).coerceAtMost(maxOf(maxOffset, 0)) },
        onForward = { dayOffset = (dayOffset - 1).coerceAtLeast(0) },
        onPick = { showPicker = true },
    )

    // A pull asks the strap for its history (when the link can serve one), then re-reads everything.
    val pull = rememberPullToRefreshState()
    LaunchedEffect(pull.isRefreshing) {
        if (!pull.isRefreshing) return@LaunchedEffect
        if (todayPullToSyncEnabled(sync.connected, sync.bonded, sync.backfilling, sync.historyReady)) vm.syncNow()
        snapshot = SummaryLoader.load(vm, context, dayOffset, days, todayRow, cycle, spo2Candidates, today)
        reloadTick++
        pull.endRefresh()
    }

    val listState = rememberLazyListState()
    OnScrollToTop { listState.animateScrollToItem(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .nestedScroll(pull.nestedScrollConnection),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            when (layout) {
                SummaryLayout.DETAILED -> detailedLayout(ui, pager, actions, onChangeTile, onEdit = { showEdit = true })
                SummaryLayout.COMPACT -> compactLayout(ui, pager, actions, onChangeTile, onEdit = { showEdit = true })
            }
        }
        // The indicator only while a pull or a refresh is running (at rest it would draw a stray disc).
        if (pull.progress > 0f || pull.isRefreshing) {
            PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    if (showPicker) {
        SummaryDatePicker(
            selected = today.minusDays(dayOffset),
            earliest = today.minusDays(maxOffset),
            latest = today.date,
            onPick = { picked ->
                dayOffset = SummaryDay.pickedDayOffset(picked, today.date).coerceAtMost(maxOffset)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
    if (showEdit) {
        SummaryEditSheet(
            layout = layout,
            pinned = ui.pinned,
            unpinned = SummaryPins.unpinned(enabled, tiles),
            onLayout = { SummaryLayoutPrefs.setLayout(context, it) },
            onPinned = { enabled = SummaryPins.save(context, tiles, it) },
            onDismiss = { showEdit = false },
        )
    }
}

/** The live-state fields the Summary reads: whether it is syncing, when it last synced, and pull gating. */
private data class SyncSnap(
    val backfilling: Boolean,
    val lastSyncAt: Long?,
    val connected: Boolean,
    val bonded: Boolean,
    val historyReady: Boolean,
)

/** The ‹ day › pager's state. */
internal class SummaryPager(
    val title: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onPick: () -> Unit,
)

/** Padding for a list item that sits inside the page gutters. */
internal val gutter = Modifier.padding(horizontal = M3Dimens.screenPadding)

// MARK: - Layout A (Detailed)

private fun LazyListScope.detailedLayout(
    ui: SummaryUi,
    pager: SummaryPager,
    actions: SummaryActions,
    onChangeTile: (Int, KeyMetric) -> Unit,
    onEdit: () -> Unit,
) {
    item(key = "title") {
        LargeTitle(stringResource(R.string.nav_summary)) {
            AvatarButton(size = 36.dp, onClick = actions.openSettings)
        }
    }
    notices(ui, actions)
    item(key = "pager") { DayPagerRow(pager, Modifier.padding(horizontal = 4.dp)) }
    item(key = "rings") { Box(gutter) { SummaryRings(ui, actions) } }
    item(key = "tiles") { Box(gutter) { FitnessTilesRow(ui, actions, onChangeTile) } }
    item(key = "pinned-header") {
        SectionHeader(
            stringResource(R.string.summary_pinned),
            modifier = gutter,
            action = stringResource(R.string.summary_edit),
            onAction = onEdit,
        )
    }
    ui.sleepNight?.let { night ->
        item(key = "sleep") { Box(gutter) { SleepCard(night, ui.locale) { actions.openSleepNight(night.wakeDayKey) } } }
        if (night.naps.isNotEmpty()) item(key = "naps") { Box(gutter) { NapRows(night, ui.locale, actions) } }
    }
    val inputs = ui.snapshot.metrics
    if (ui.pinned.isEmpty()) {
        item(key = "pin-prompt") { Box(gutter) { PinPromptCard(onEdit) } }
    } else if (inputs != null) {
        ui.pinned.forEach { metric ->
            val reading = SummaryMetricReading.resolve(metric, inputs, ui.locale) ?: return@forEach
            item(key = "pin-${metric.raw}") {
                Box(gutter) {
                    PinnedMetricCard(
                        metric = metric,
                        reading = reading,
                        series = ui.snapshot.series(reading.seriesKey),
                        stamp = stampText(ui.stamp(reading.stampDay), ui.locale),
                        onClick = { actions.openMetric(reading.routeKey, reading.routeSource) },
                    )
                }
            }
        }
    }
    item(key = "all-metrics") { Box(gutter) { ShowAllMetricsRow(actions.openAllMetrics) } }
    ui.trends?.let { trends ->
        item(key = "trends-header") { SectionHeader(stringResource(R.string.summary_trends), modifier = gutter) }
        trends.forEach { t ->
            item(key = "trend-${t.metric.id}") {
                Box(gutter) {
                    HealthTrendCard(
                        item = t,
                        units = metricUnits(LocalContext.current),
                        locale = ui.locale,
                        onClick = { actions.openMetric(t.metric.key, t.metric.source) },
                    )
                }
            }
        }
        item(key = "all-trends") { Box(gutter) { ShowAllTrendsRow(actions.openTrends) } }
    }
    if (ui.snapshot.highlights.isNotEmpty()) {
        item(key = "highlights-header") { SectionHeader(stringResource(R.string.metric_highlights), modifier = gutter) }
        ui.snapshot.highlights.forEach { h ->
            item(key = "highlight-${h.key}") {
                Box(gutter) {
                    HighlightCard(h, ui.snapshot.series("effort"), ui.locale) { actions.openMetric(h.routeKey, null) }
                }
            }
        }
    }
    syncFooter(ui)
}

/** The raised alert, pinned above everything else in both layouts (a running workout is the mini-player). */
private fun LazyListScope.notices(ui: SummaryUi, actions: SummaryActions) {
    ui.alert?.let { message -> item(key = "alert") { Box(gutter) { HealthAlertNotice(message) } } }
}

private fun LazyListScope.syncFooter(ui: SummaryUi) {
    item(key = "sync") {
        val text = syncLineText(ui.syncLine, ui.locale)
        if (text != null) SyncFooter(text, syncing = ui.syncLine == SummarySyncLine.Syncing)
    }
}

/** ‹ title › — the one place the Summary names its day; the title opens the calendar. */
@Composable
private fun DayPagerRow(pager: SummaryPager, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = pager.onBack,
            enabled = pager.canGoBack,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.summary_previous_day))
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = pager.onPick,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Text(pager.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = pager.onForward,
            enabled = pager.canGoForward,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.summary_next_day))
        }
    }
}

/** The ring figures and their taps: Charge and Effort open their pages, Rest the Sleep tab on that night. */
@Composable
internal fun ringFigures(ui: SummaryUi, actions: SummaryActions): List<RingFigureData> {
    val context = LocalContext.current
    val scale = UnitPrefs.effortScale(context)
    val s = ui.snapshot
    val c = com.noop.ui.m3.Health.colors
    return listOf(
        RingFigureData(
            stringResource(R.string.today_metric_charge), percentTokens(s.charge.pct), chargeCaption(s.charge, ui.locale),
            c.charge,
        ) { actions.openMetric(HeroRingMetric.CHARGE, null) },
        RingFigureData(
            stringResource(R.string.today_metric_effort), effortTokens(s.effort, scale, ui.locale), null, c.effort,
        ) { actions.openMetric(HeroRingMetric.EFFORT, null) },
        RingFigureData(
            stringResource(R.string.today_metric_rest), percentTokens(s.rest), null, c.rest,
        ) { actions.openSleepNight(ui.selectedKey) },
    )
}

@Composable
private fun SummaryRings(ui: SummaryUi, actions: SummaryActions) {
    val s = ui.snapshot
    RingsCard(
        charge = s.charge.pct,
        effort = s.effort,
        rest = s.rest,
        figures = ringFigures(ui, actions),
        onRings = { actions.openMetric(HeroRingMetric.CHARGE, null) },
    )
}

/** Fitness's pair of square tiles under the rings, each a metric the user picks (long-press). */
@Composable
private fun FitnessTilesRow(ui: SummaryUi, actions: SummaryActions, onChangeTile: (Int, KeyMetric) -> Unit) {
    val inputs = ui.snapshot.metrics ?: return
    val letters = weekdayLetters(ui.snapshot.weekKeys, ui.locale)
    Row(horizontalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
        ui.tiles.forEachIndexed { slot, metric ->
            val reading = SummaryMetricReading.resolve(metric, inputs, ui.locale)
            if (reading == null) {
                Spacer(Modifier.weight(1f))
            } else {
                FitnessTile(
                    metric = metric,
                    reading = reading,
                    week = ui.snapshot.dailySeries[reading.seriesKey].orEmpty(),
                    weekLetters = letters,
                    stamp = stampText(SummaryStamp.resolve(reading.stampDay ?: ui.selectedKey, ui.today), ui.locale),
                    onClick = { actions.openMetric(reading.routeKey, reading.routeSource) },
                    onChange = { onChangeTile(slot, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// MARK: - Layout B (Compact)

private fun LazyListScope.compactLayout(
    ui: SummaryUi,
    pager: SummaryPager,
    actions: SummaryActions,
    onChangeTile: (Int, KeyMetric) -> Unit,
    onEdit: () -> Unit,
) {
    item(key = "c-title") { CompactTopRow(pager, actions) }
    notices(ui, actions)
    item(key = "c-dials") { Box(gutter) { ScoreDialsCard(ui, actions) } }
    ui.snapshot.highlights.forEachIndexed { i, h ->
        item(key = "c-highlight-${h.key}") {
            Box(gutter) {
                HighlightBanner(h, primary = i == 0) { actions.openMetric(h.routeKey, null) }
            }
        }
    }
    item(key = "c-pinned-header") {
        SectionHeader(
            stringResource(R.string.summary_pinned),
            modifier = gutter,
            action = stringResource(R.string.summary_edit),
            onAction = onEdit,
        )
    }
    item(key = "c-grid") { Box(gutter) { PinnedGrid(ui, actions, onChangeTile, onEdit) } }
    ui.sleepNight?.takeIf { it.naps.isNotEmpty() }?.let { night ->
        item(key = "c-naps") { Box(gutter) { NapRows(night, ui.locale, actions) } }
    }
    item(key = "c-all-metrics") { Box(gutter) { AllMetricsButton(actions.openAllMetrics) } }
    ui.trends?.let { trends ->
        item(key = "c-trends-header") {
            SectionHeader(
                stringResource(R.string.summary_trends),
                modifier = gutter,
                action = stringResource(R.string.summary_show_all),
                onAction = actions.openTrends,
            )
        }
        if (trends.isNotEmpty()) item(key = "c-trends") { TrendsCarousel(trends, ui, actions) }
    }
    syncFooter(ui)
}

// MARK: - Calendar

/** Material's date picker over the days on record: from the earliest banked day to today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryDatePicker(
    selected: LocalDate,
    earliest: LocalDate,
    latest: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    fun utc(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val first = utc(earliest)
    val last = utc(latest)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utc(selected),
        yearRange = earliest.year..latest.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in first..last
            override fun isSelectableYear(year: Int): Boolean = year in earliest.year..latest.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis == null) onDismiss()
                else onPick(java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text(stringResource(R.string.summary_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

/** The day's naps as rows, each opening its own page (the night itself is the Sleep card above them). */
@Composable
private fun NapRows(night: SummarySleepNight, locale: Locale, actions: SummaryActions) {
    val context = LocalContext.current
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    SleepSessionRows(
        night = null,
        naps = night.naps,
        is24h = is24h,
        locale = locale,
        onOpenNight = {},
        onOpenNap = { actions.openSleepNap(night.wakeDayKey, it.startTs) },
    )
}
