package com.noop.ui.trends

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Parity with the iOS `HealthTrendDetector` (Strand/Trends/HealthTrendDetector.swift). [EXPECTED] is the
 * stdout of that Swift file compiled standalone (`swiftc -O HealthTrendDetector.swift main.swift`) over the
 * same fixtures this test builds, pasted verbatim: the thresholds, windows and arithmetic are the Swift
 * detector's, so the two platforms call the same trends on the same readings.
 */
class HealthTrendDetectorTest {

    private val today = LocalDate.parse("2026-09-30")

    /** [count] days ending [endBack] days before today; [value] for i = 0 (oldest) …, null skips a day. */
    private fun build(count: Int, endBack: Int = 0, value: (Int) -> Double?): List<Pair<String, Double>> =
        (0 until count).mapNotNull { i ->
            val back = endBack + (count - 1 - i)
            value(i)?.let { today.minusDays(back.toLong()).toString() to it }
        }

    private val fixtures: List<Triple<String, List<Pair<String, Double>>, Boolean?>> = listOf(
        Triple("weekly_drop", build(182) { i -> if (i < 150) 60.0 + (i * 7) % 5 else 50.0 + (i * 3) % 4 }, true),
        Triple(
            "weekly_rise_sparse",
            build(180) { i -> if (i % 3 == 0) 80.0 - (if (i >= 120) 0 else 3) + ((i * 11) % 3) * 0.2 else null },
            false,
        ),
        Triple("daily_rise", build(28) { i -> if (i < 18) 14 + ((i * 5) % 3) * 0.2 else 16 + ((i * 5) % 3) * 0.2 }, null),
        Triple("steady", build(200) { i -> 50.0 + (i * 13) % 7 }, true),
        Triple("insufficient", build(6) { i -> 10.0 + i }, true),
        Triple("stale", build(100, endBack = 60) { i -> if (i < 70) 30.0 else 40.0 + i % 2 }, true),
        Triple("daily_drop_better_lower", build(40) { i -> if (i < 30) 62.0 + (i * 3) % 4 else 55.0 + (i * 5) % 3 }, false),
        Triple(
            "weekly_gap_start",
            build(120) { i -> if (i < 20) null else if (i < 95) 7.0 + ((i * 17) % 5) * 0.1 else 8.4 + ((i * 7) % 3) * 0.1 },
            null,
        ),
    )

    private fun show(v: Double) = String.format(Locale.US, "%.12f", v)

    private fun run(): List<String> {
        val out = ArrayList<String>()
        val trends = ArrayList<Pair<String, HealthTrend>>()
        for ((name, series, hib) in fixtures) {
            when (val r = HealthTrendDetector.detect(series, "2026-09-30", hib)) {
                is HealthTrendResult.Trend -> {
                    val t = r.trend
                    trends += name to t
                    val imp = t.isImprovement?.toString() ?: "nil"
                    val dir = if (t.direction == HealthTrend.Direction.HIGHER) "higher" else "lower"
                    val unit = if (t.unit == HealthTrend.Unit.WEEK) "week" else "day"
                    out += "$name trend $dir $unit recent=${t.recentCount} baseline=${t.baselineCount} " +
                        "baselineLength=${t.baselineLength} slots=${t.slots.size} recorded=${t.slots.count { it != null }} " +
                        "mb=${show(t.baselineAverage)} mr=${show(t.recentAverage)} t=${show(t.strength)} improvement=$imp"
                }
                HealthTrendResult.Steady -> out += "$name steady"
                HealthTrendResult.Insufficient -> out += "$name insufficient"
            }
        }
        out += "order " + trends.sortedWith { a, b -> HealthTrendDetector.order.compare(a.second, b.second) }
            .joinToString(",") { it.first }
        for (k in listOf("1970-01-01", "1969-12-31", "2000-02-29", "2026-09-30", "1600-03-01", "2026-13-01", "bad")) {
            out += "day $k ${HealthTrendDetector.dayNumber(k)?.toString() ?: "nil"}"
        }
        return out
    }

    @Test
    fun `matches the Swift detector on every fixture`() {
        assertEquals(EXPECTED.trim().lines(), run())
    }

    @Test
    fun `a week slot is the mean of its seven days`() {
        val byDay = mapOf(100 to 1.0, 101 to 3.0, 94 to 10.0)
        val slots = HealthTrendDetector.slots(byDay, today = 101, scale = HealthTrendDetector.weekly)
        assertEquals(26, slots.size)
        assertEquals(2.0, slots.last()!!, 0.0)
        assertEquals(10.0, slots[24]!!, 0.0)
    }

    private companion object {
        /** Verbatim stdout of the Swift oracle (see the class comment). */
        const val EXPECTED = """
weekly_drop trend lower week recent=4 baseline=22 baselineLength=22 slots=26 recorded=26 mb=61.727272727273 mr=51.500000000000 t=-36.717851955020 improvement=false
weekly_rise_sparse trend higher week recent=8 baseline=18 baselineLength=18 slots=26 recorded=26 mb=77.111111111111 mr=80.000000000000 t=26.000000000000 improvement=false
daily_rise trend higher day recent=10 baseline=18 baselineLength=18 slots=28 recorded=28 mb=14.200000000000 mr=16.180000000000 t=29.082152148768 improvement=nil
steady steady
insufficient insufficient
stale insufficient
daily_drop_better_lower trend lower day recent=10 baseline=18 baselineLength=18 slots=28 recorded=28 mb=63.500000000000 mr=55.900000000000 t=-19.197059824921 improvement=true
weekly_gap_start trend higher week recent=4 baseline=22 baselineLength=11 slots=26 recorded=15 mb=7.190909090909 mr=8.371428571429 t=9.139410216162 improvement=nil
order weekly_drop,weekly_rise_sparse,daily_drop_better_lower,daily_rise,weekly_gap_start
day 1970-01-01 0
day 1969-12-31 -1
day 2000-02-29 11016
day 2026-09-30 20726
day 1600-03-01 -135080
day 2026-13-01 nil
day bad nil
"""
    }
}
