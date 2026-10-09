package com.noop.ui.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * The metric page's windows, buckets, averages, Highlights and chart scale against the iOS
 * `MetricHealthSeries` (and the `yDomain` / `yTicks` of `MetricHealthChart`). [EXPECTED] is the stdout of
 * those Swift functions compiled standalone over the same fixtures (Gregorian, Monday weeks, today
 * 2026-09-30), pasted verbatim, so a page reads the same period, marks and average on both platforms.
 */
class MetricHealthSeriesTest {

    private val today = LocalDate.parse("2026-09-30")
    private val monday = DayOfWeek.MONDAY

    private fun build(count: Int, endBack: Int = 0, value: (Int) -> Double?): List<Pair<String, Double>> =
        (0 until count).mapNotNull { i ->
            val back = endBack + (count - 1 - i)
            value(i)?.let { today.minusDays(back.toLong()).toString() to it }
        }

    private fun show(v: Double) = String.format(Locale.US, "%.9f", v)

    private fun run(): List<String> {
        val out = ArrayList<String>()
        val series = listOf(
            "daily" to build(400) { i -> if (i % 9 == 4) null else 50 + ((i * 37) % 23) * 0.5 },
            "stale" to build(90, endBack = 50) { i -> 70.0 + (i * 13) % 7 },
            "weekly" to build(300) { i -> if (i % 7 == 0) 80 - i * 0.01 else null },
        )
        val ranges = listOf("W" to MetricRange.WEEK, "M" to MetricRange.MONTH, "6M" to MetricRange.SIX_MONTHS, "Y" to MetricRange.YEAR)
        for ((name, s) in series) {
            for ((rn, r) in ranges) {
                val w = MetricHealthSeries.window(s, r, today, monday)
                fun p(x: MetricPoint) = "${x.start}..${x.end}=${show(x.value)}/${x.count}@${x.lastDay}"
                val head = w.points.take(2).joinToString(" ") { p(it) }
                val tail = w.points.takeLast(2).joinToString(" ") { p(it) }
                out += "$name $rn ${w.start}..${w.end} anchor=${w.anchor} points=${w.points.size} days=${w.days.size} " +
                    "avg=${w.average?.let { show(it) } ?: "nil"} head=[$head] tail=[$tail]"
            }
            val h = MetricHealthSeries.highlight(s)
            out += if (h == null) "$name highlight nil" else
                "$name highlight latest=${show(h.latest)} avg=${show(h.average)} n=${h.values.size} ${h.firstDay}..${h.lastDay} ${h.direction.name.lowercase()}"
        }
        val cases = listOf(
            Triple(listOf(52.0, 61.5, 58.0), MetricChartSpec(MetricMark.DOTS), Unit),
            Triple(listOf(8200.0, 12000.0, 3100.0), MetricChartSpec(MetricMark.BARS), Unit),
            Triple(listOf(45.0, 88.0, 72.0), MetricChartSpec(MetricMark.BARS, domain = 0.0..100.0, thresholds = listOf(50.0, 70.0)), Unit),
            Triple(listOf(95.0, 97.0, 89.0), MetricChartSpec(MetricMark.DOTS, domain = 90.0..100.0), Unit),
            Triple(listOf(0.4, -0.2, 0.1), MetricChartSpec(MetricMark.DIVERGING), Unit),
            Triple(listOf(79.5, 79.1, 78.8), MetricChartSpec(MetricMark.LINE), Unit),
            Triple(emptyList(), MetricChartSpec(MetricMark.DOTS), Unit),
        )
        for ((values, spec, _) in cases) {
            val dom = MetricHealthSeries.yDomain(values, spec)
            out += "ticks ${show(dom.start)}..${show(dom.endInclusive)} " +
                MetricHealthSeries.yTicks(dom, spec.thresholds).joinToString(",") { show(it) }
        }
        return out
    }

    @Test
    fun `matches the Swift metric series on every fixture`() {
        assertEquals(EXPECTED.trim().lines(), run())
    }

    @Test
    fun `a highlight needs four earlier readings in the fortnight`() {
        val three = build(4) { 10.0 }
        assertNull(MetricHealthSeries.highlight(three))
        val five = build(5) { i -> if (i == 4) 20.0 else 10.0 }
        assertEquals(MetricHighlight.Direction.ABOVE, MetricHealthSeries.highlight(five)!!.direction)
    }

    @Test
    fun `month and year ticks fall on week and month starts`() {
        val w = MetricHealthSeries.window(emptyList(), MetricRange.MONTH, today, monday)
        assertEquals(listOf("2026-08-31", "2026-09-07", "2026-09-14", "2026-09-21", "2026-09-28"), MetricHealthSeries.xTicks(w, monday).map { it.toString() })
        val y = MetricHealthSeries.window(emptyList(), MetricRange.YEAR, today, monday)
        assertEquals(12, MetricHealthSeries.xTicks(y, monday).size)
    }

    private companion object {
        /** Verbatim stdout of the Swift oracle (see the class comment). */
        const val EXPECTED = """
daily W 2026-09-24..2026-10-01 anchor=2026-09-30 points=7 days=7 avg=55.428571429 head=[2026-09-24..2026-09-25=52.500000000/1@2026-09-24 2026-09-25..2026-09-26=59.500000000/1@2026-09-25] tail=[2026-09-29..2026-09-30=53.000000000/1@2026-09-29 2026-09-30..2026-10-01=60.000000000/1@2026-09-30]
daily M 2026-08-30..2026-10-01 anchor=2026-09-30 points=29 days=29 avg=55.689655172 head=[2026-08-30..2026-08-31=50.000000000/1@2026-08-30 2026-08-31..2026-09-01=57.000000000/1@2026-08-31] tail=[2026-09-29..2026-09-30=53.000000000/1@2026-09-29 2026-09-30..2026-10-01=60.000000000/1@2026-09-30]
daily 6M 2026-03-30..2026-10-05 anchor=2026-09-30 points=27 days=165 avg=55.557575758 head=[2026-03-30..2026-04-06=55.666666667/6@2026-04-05 2026-04-06..2026-04-13=56.071428571/7@2026-04-12] tail=[2026-09-21..2026-09-28=54.833333333/6@2026-09-27 2026-09-28..2026-10-05=56.833333333/3@2026-09-30]
daily Y 2025-10-01..2026-10-01 anchor=2026-09-30 points=12 days=325 avg=55.547692308 head=[2025-10-01..2025-11-01=55.750000000/28@2025-10-31 2025-11-01..2025-12-01=55.307692308/26@2025-11-30] tail=[2026-08-01..2026-09-01=55.571428571/28@2026-08-31 2026-09-01..2026-10-01=55.851851852/27@2026-09-30]
daily highlight latest=60.000000000 avg=55.666666667 n=13 2026-09-17..2026-09-30 above
stale W 2026-08-05..2026-08-12 anchor=2026-08-11 points=7 days=7 avg=73.000000000 head=[2026-08-05..2026-08-06=71.000000000/1@2026-08-05 2026-08-06..2026-08-07=70.000000000/1@2026-08-06] tail=[2026-08-10..2026-08-11=73.000000000/1@2026-08-10 2026-08-11..2026-08-12=72.000000000/1@2026-08-11]
stale M 2026-07-11..2026-08-12 anchor=2026-08-11 points=32 days=32 avg=73.062500000 head=[2026-07-11..2026-07-12=75.000000000/1@2026-07-11 2026-07-12..2026-07-13=74.000000000/1@2026-07-12] tail=[2026-08-10..2026-08-11=73.000000000/1@2026-08-10 2026-08-11..2026-08-12=72.000000000/1@2026-08-11]
stale 6M 2026-03-30..2026-10-05 anchor=2026-09-30 points=14 days=90 avg=73.022222222 head=[2026-05-11..2026-05-18=73.750000000/4@2026-05-17 2026-05-18..2026-05-25=73.000000000/7@2026-05-24] tail=[2026-08-03..2026-08-10=73.000000000/7@2026-08-09 2026-08-10..2026-08-17=72.500000000/2@2026-08-11]
stale Y 2025-10-01..2026-10-01 anchor=2026-09-30 points=4 days=90 avg=73.022222222 head=[2026-05-01..2026-06-01=73.166666667/18@2026-05-31 2026-06-01..2026-07-01=72.966666667/30@2026-06-30] tail=[2026-07-01..2026-08-01=72.935483871/31@2026-07-31 2026-08-01..2026-09-01=73.181818182/11@2026-08-11]
stale highlight latest=72.000000000 avg=73.076923077 n=14 2026-07-29..2026-08-11 close
weekly W 2026-09-24..2026-10-01 anchor=2026-09-30 points=1 days=1 avg=77.060000000 head=[2026-09-25..2026-09-26=77.060000000/1@2026-09-25] tail=[2026-09-25..2026-09-26=77.060000000/1@2026-09-25]
weekly M 2026-08-30..2026-10-01 anchor=2026-09-30 points=4 days=4 avg=77.165000000 head=[2026-09-04..2026-09-05=77.270000000/1@2026-09-04 2026-09-11..2026-09-12=77.200000000/1@2026-09-11] tail=[2026-09-18..2026-09-19=77.130000000/1@2026-09-18 2026-09-25..2026-09-26=77.060000000/1@2026-09-25]
weekly 6M 2026-03-30..2026-10-05 anchor=2026-09-30 points=26 days=26 avg=77.935000000 head=[2026-03-30..2026-04-06=78.810000000/1@2026-04-03 2026-04-06..2026-04-13=78.740000000/1@2026-04-10] tail=[2026-09-14..2026-09-21=77.130000000/1@2026-09-18 2026-09-21..2026-09-28=77.060000000/1@2026-09-25]
weekly Y 2025-10-01..2026-10-01 anchor=2026-09-30 points=10 days=43 avg=78.530000000 head=[2025-12-01..2026-01-01=79.895000000/4@2025-12-26 2026-01-01..2026-02-01=79.580000000/5@2026-01-30] tail=[2026-08-01..2026-09-01=77.445000000/4@2026-08-28 2026-09-01..2026-10-01=77.165000000/4@2026-09-25]
weekly highlight nil
ticks 49.540000000..63.960000000 50.000000000,55.000000000,60.000000000
ticks 0.000000000..13440.000000000 0.000000000,5000.000000000,10000.000000000
ticks 0.000000000..100.000000000 0.000000000,50.000000000,70.000000000,100.000000000
ticks 89.000000000..100.000000000 90.000000000,95.000000000,100.000000000
ticks -0.500000000..0.500000000 -0.500000000,0.000000000,0.500000000
ticks 75.620000000..82.680000000 76.000000000,78.000000000,80.000000000,82.000000000
ticks 0.000000000..4.000000000 0.000000000,1.000000000,2.000000000,3.000000000,4.000000000
"""
    }
}
