package com.noop.ui.trends

import android.content.Context
import com.noop.ui.AppViewModel
import com.noop.ui.metric.AllMetricsCatalog
import com.noop.ui.metric.MetricCatalog
import com.noop.ui.metric.MetricDescriptor
import com.noop.ui.metric.MetricLoadContext
import com.noop.ui.metric.MetricSeriesLoader
import java.time.LocalDate

// MARK: - Health trends (twin of iOS HealthTrendLoader)
//
// Every metric's trend, read from the same series its page draws (MetricSeriesLoader) and one source per
// metric as All Metrics picks it, so a trend card and the page it opens always agree.

/** One metric with a detected trend. */
data class HealthTrendItem(val metric: MetricDescriptor, val trend: HealthTrend)

/** The trends of every metric, and whether any metric had enough recent readings to be judged at all. */
data class HealthTrendsSnapshot(
    /** Only the metrics with a trend, worse news first (HealthTrendDetector.precedes). */
    val items: List<HealthTrendItem> = emptyList(),
    /** Tells "No Trends" apart from "Not Enough Data Yet". */
    val anyJudged: Boolean = false,
)

object HealthTrends {

    /** Detects every metric's trend as of [today], from the shared load context. */
    suspend fun load(ctx: MetricLoadContext, today: LocalDate = LocalDate.now()): HealthTrendsSnapshot {
        val nonEmpty = MetricSeriesLoader.nonEmptyIds(MetricCatalog.all, ctx)
        val candidates = MetricCatalog.all.filter { it.id in nonEmpty }
        val series = MetricSeriesLoader.loadAll(candidates, ctx)
        val chosen = AllMetricsCatalog.oneSourcePerKey(
            candidates.filter { it.id in series },
            series.mapValues { it.value.series.last().first },
        )
        val todayKey = today.toString()
        val items = ArrayList<HealthTrendItem>()
        var anyJudged = false
        for (metric in chosen) {
            when (val r = HealthTrendDetector.detect(series[metric.id]?.series.orEmpty(), todayKey, metric.higherIsBetter)) {
                is HealthTrendResult.Trend -> {
                    anyJudged = true
                    items += HealthTrendItem(metric, r.trend)
                }
                HealthTrendResult.Steady -> anyJudged = true
                HealthTrendResult.Insufficient -> Unit
            }
        }
        return HealthTrendsSnapshot(items.sortedWith { a, b -> HealthTrendDetector.order.compare(a.trend, b.trend) }, anyJudged)
    }

    /** Every metric's trend, reading the shared inputs first. */
    suspend fun load(vm: AppViewModel, context: Context): HealthTrendsSnapshot =
        load(MetricSeriesLoader.context(vm, context))

    /**
     * Up to [limit] trends in card order, for the Summary's "Trends" section (it shows the first three and
     * a "Show All Trends" row). Empty when nothing moved or there is not enough data yet.
     */
    suspend fun top(vm: AppViewModel, context: Context, limit: Int = 3): List<HealthTrendItem> =
        load(vm, context).items.take(limit)
}
