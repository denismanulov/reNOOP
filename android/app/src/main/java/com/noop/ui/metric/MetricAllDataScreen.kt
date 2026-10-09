package com.noop.ui.metric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.DisplayText
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.groupItemShape
import com.noop.ui.provenanceDisplayLabel
import com.noop.ui.uiString

// MARK: - All Data (twin of iOS MetricAllDataView)
//
// "Show All Data" on a metric page: every reading, newest first, with the source that supplied it, as
// Health's All Recorded Data. Rows come from the same series the page drew, so the two always agree.

/** One All Data row: the reading, the source that supplied it, and its day. */
internal data class MetricDataRow(val value: String, val source: String, val time: String)

/** The page's rows, newest first. [label] names a raw source id, [format] prints a value, [time] a day. */
internal fun metricDataRows(
    page: MetricPageSeries,
    fallbackSource: String,
    format: (Double) -> String,
    label: (String) -> String,
    time: (String) -> String,
): List<MetricDataRow> = page.series.asReversed().map { (day, value) ->
    MetricDataRow(format(value), label(page.sourceByDay[day] ?: fallbackSource), time(day))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricAllDataScreen(vm: AppViewModel, key: String, source: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val units = remember { metricUnits(context) }
    val metric = remember(key, source) { source?.let { MetricCatalog.metric(key, it) } ?: MetricCatalog.byKey(key).firstOrNull() }
    val rows by produceState<List<MetricDataRow>?>(null, metric) {
        val m = metric ?: run { value = emptyList(); return@produceState }
        val ctx = MetricSeriesLoader.context(vm, context)
        val page = MetricSeriesLoader.load(m, ctx)
        val today = AppToday.now(vm.today.value?.day)
        value = metricDataRows(
            page = page,
            fallbackSource = m.source,
            format = { MetricHealthStyle.text(metricTokens(m, it, units, locale)) },
            label = { raw ->
                when (val l = provenanceDisplayLabel(raw, vm.activeStrapId)) {
                    is DisplayText.Resource -> uiString(l.id, *l.args.toTypedArray())
                    is DisplayText.Dynamic -> l.value
                }
            },
            time = { MetricDateLabels.stamp(it, today, locale) },
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.metric_all_data), onBack = onBack, scrollBehavior = scrollBehavior)
        val list = rows
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.groupGap),
        ) {
            itemsIndexed(list) { i, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = M3Dimens.rowTwoLineHeight)
                        .clip(groupItemShape(i, list.size))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clearAndSetSemantics { contentDescription = listOf(row.value, row.source, row.time).joinToString(", ") }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text(row.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(row.time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
