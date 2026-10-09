package com.noop.ui.trends

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.TrainingLoadEngine
import com.noop.data.DailyMetric
import com.noop.ui.AppViewModel
import com.noop.ui.ReportRange
import com.noop.ui.TrendsReportShare
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.metric.MetricDescriptor
import com.noop.ui.metric.metricUnits
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MARK: - Trends (twin of iOS TrendsView)
//
// Health's "Show All Health Trends" page: one card per metric whose recent readings have clearly moved
// (HealthTrendDetector), each opening that metric's page, then Training Load, which no other page shows.
// The share action exports the PDF trends report over one of five ranges. Pull to refresh re-reads.

/** The trends report's ranges as the share menu names them. */
private val ReportRange.titleRes: Int
    get() = when (this) {
        ReportRange.Days30 -> R.string.trends_report_30
        ReportRange.Days90 -> R.string.trends_report_90
        ReportRange.Days180 -> R.string.trends_report_180
        ReportRange.Days365 -> R.string.trends_report_365
        ReportRange.All -> R.string.trends_report_all
    }

/** Training load from the day history: NOOP's daily Effort, never TRIMP, feeding no score. */
internal fun trainingLoad(days: List<DailyMetric>): TrainingLoadEngine.Result =
    TrainingLoadEngine.evaluate(days.map { TrainingLoadEngine.DailyLoad(it.day, it.strain) })

/** A load figure with one decimal, signed ("+4.2") when [signed]. */
internal fun loadNumber(v: Double, locale: Locale, signed: Boolean = false): String {
    val f = NumberFormat.getNumberInstance(locale)
    f.minimumFractionDigits = 1
    f.maximumFractionDigits = 1
    if (signed && f is DecimalFormat) f.positivePrefix = "+"
    return f.format(v)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenMetric: (MetricDescriptor) -> Unit,
    onOpenTrainingLoad: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val units = remember { metricUnits(context) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val revision = remember(days) { days.size to days.lastOrNull() }
    var snapshot by remember { mutableStateOf<HealthTrendsSnapshot?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val pull = rememberPullToRefreshState()

    LaunchedEffect(revision, reloadTick) {
        snapshot = runCatching { HealthTrends.load(vm, context) }.getOrDefault(HealthTrendsSnapshot())
        if (pull.isRefreshing) pull.endRefresh()
    }
    LaunchedEffect(pull.isRefreshing) {
        if (pull.isRefreshing) reloadTick++
    }
    val load = remember(days) { trainingLoad(days) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(R.string.browse_trends),
            onBack = onBack,
            large = true,
            scrollBehavior = scrollBehavior,
            actions = { ReportMenu(vm, days) },
        )
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .nestedScroll(pull.nestedScrollConnection),
        ) {
            val snap = snapshot
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
            ) {
                when {
                    snap == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    snap.items.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            title = stringResource(if (snap.anyJudged) R.string.trends_none else R.string.trends_not_enough),
                            modifier = Modifier.padding(top = 48.dp),
                        )
                    }
                    else -> items(snap.items, key = { it.metric.id }) { item ->
                        HealthTrendCard(item, units, locale, onClick = { onOpenMetric(item.metric) })
                    }
                }
                val latest = load.points.lastOrNull()
                if (snap != null && load.isAvailable && latest != null) {
                    item(key = "training-load") {
                        if (snap.items.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        TrainingLoadRow(latest.balance, locale, onOpenTrainingLoad)
                    }
                }
            }
            if (pull.progress > 0f || pull.isRefreshing) {
                PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/** The share action: the PDF trends report over one of five ranges. */
@Composable
private fun ReportMenu(vm: AppViewModel, days: List<DailyMetric>) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // The stored daily stress series ("yyyy-MM-dd" → 0–3) the report's Stress row reads (#457).
    var stressByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(Unit) {
        stressByDay = withContext(Dispatchers.IO) {
            runCatching { vm.repo.metricSeries("my-whoop", "stress", "0000-01-01", "9999-12-31") }
                .getOrDefault(emptyList()).associate { it.day to it.value }
        }
    }
    Box {
        IconButton(onClick = { open = true }, enabled = !exporting && days.isNotEmpty()) {
            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.trends_share))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.trends_report_pdf),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            ReportRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(stringResource(range.titleRes)) },
                    onClick = {
                        open = false
                        exporting = true
                        TrendsReportShare.export(context, days, range, stressByDay)
                        exporting = false
                    },
                )
            }
        }
    }
}

/** Training load as one card under the trends: its name, today's form, and the page with the chart. */
@Composable
private fun TrainingLoadRow(balance: Double, locale: Locale, onClick: () -> Unit) {
    HealthCard(onClick = onClick, verticalSpacing = 10.dp) {
        CardTitleRow(icon = Icons.Filled.LocalFireDepartment, title = stringResource(R.string.trends_training_load), tint = Health.colors.activity)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                loadNumber(balance, locale, signed = true),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.trends_form),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
