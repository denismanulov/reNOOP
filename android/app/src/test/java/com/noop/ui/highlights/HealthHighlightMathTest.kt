package com.noop.ui.highlights

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * The figures behind the Highlights cards against the Swift twin (`HealthHighlightMath`). [ORACLE] is the
 * stdout of the Swift file compiled standalone (`swiftc -O HealthHighlight.swift main.swift`) over the same
 * seeded cases this test generates, pasted verbatim: every line here must print the same.
 */
class HealthHighlightMathTest {

    private var rng = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

    private fun next(m: Int): Int {
        rng = rng * 6364136223846793005L + 1442695040888963407L
        return ((rng ushr 33) % m).toInt()
    }

    private fun f(v: Double) = String.format(Locale.US, "%.4f", v)

    private fun dir(d: HighlightDirection) = d.name.lowercase()

    private fun ts(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    private fun oracleOutput(): String {
        val out = ArrayList<String>()
        val line = ArrayList<String>()
        for (v in listOf(0.0, 94.0, 95.0, 96.0, 104.0, 105.0, 106.0, 110.0, 111.0)) {
            for (r in listOf(0.0, 100.0, 1e-12)) line += dir(HighlightDirection.of(v, r))
            line += dir(HighlightDirection.of(v, 100.0, tolerance = 0.1))
        }
        out += "dir " + line.joinToString(",")

        for (c in 0 until 12) {
            val today = (0 until 24).map { (if (next(4) == 0) 0 else next(900)).toDouble() }
            val days = next(6) + 1
            val prior = (0 until days).map { (0 until 24).map { (if (next(3) == 0) 0 else next(700)).toDouble() } }
            val hour = next(25)
            val minute = if (hour == 24) 0 else next(60)
            val now = ts(LocalDate.of(2026, 10, 11)) + hour * 3600L + minute * 60L
            val p = HealthHighlightMath.progress(today, prior, now, ZoneOffset.UTC)
            out += if (p == null) "progress $c nil" else
                "progress $c ${f(p.nowFraction)} ${f(p.todayTotal)} ${f(p.typicalTotal)} ${dir(p.direction)} ${p.today.size} " +
                    "${f(p.today.last().fraction)} ${f(p.typical[7].total)} ${f(p.typical.last().total)}"
        }

        for (c in 0 until 10) {
            val last = LocalDate.of(2026, 10, 1 + next(28))
            val rows = ArrayList<Pair<String, Double>>()
            for (back in 0 until 20) if (next(3) != 0) rows += last.plusDays((-back + 3).toLong()).toString() to next(12000).toDouble()
            val w = HealthHighlightMath.sevenDay(rows, last)
            out += if (w == null) "week $c nil" else
                "week $c ${f(w.average)} " + w.values.joinToString(",") { it?.let(::f) ?: "-" } + " " + w.days.first()
        }

        for (c in 0 until 16) {
            val today = LocalDate.of(2025 + next(2), 1 + next(12), 1 + next(28))
            var rows: List<Pair<String, Double>> = ArrayList<Pair<String, Double>>().also { list ->
                for (back in 0 until 500) if (next(4) != 0) list += today.plusDays((-back + 10).toLong()).toString() to next(9000).toDouble()
            }
            if (c % 4 == 3) rows = rows.take(next(40))
            for ((period, name) in listOf(HighlightPeriod.MONTH to "month", HighlightPeriod.YEAR to "year")) {
                for (total in listOf(false, true)) {
                    val k = HealthHighlightMath.comparison(rows, period, today, total)
                    out += if (k == null) "cmp $c $name $total nil" else
                        "cmp $c $name $total ${f(k.current)} ${f(k.previous)} ${k.currentStart} ${k.previousStart} ${dir(k.direction)}"
                }
            }
        }

        for (c in 0 until 12) {
            val spans = listOf(300, 1500, 3240, 3600, 5400, 8 * 3600, 30 * 3600, 600)
            val span = spans[c % spans.size] + next(200)
            val start = 1_700_000_000L + next(10000)
            val count = next(60) + 2
            val samples = (0 until count).map {
                val low = (40 + next(120)).toDouble()
                val t = start - 50 + next(span + 100)
                val high = if (next(10) == 0) 0.0 else low + next(30)
                HealthHighlightMath.RangeSample(t, low, high)
            }
            val r = HealthHighlightMath.range(samples, start, start + span)
            out += if (r == null) "range $c nil" else
                "range $c ${HealthHighlightMath.sliceLength(span.toLong())} ${r.slices.size} ${f(r.minimum)} ${f(r.maximum)} " +
                    "${r.minimumIndex} ${r.maximumIndex} " + r.slices.joinToString(",") { s -> s?.let { "${f(it.low)}/${f(it.high)}" } ?: "-" }
        }

        for (c in 0 until 12) {
            val end = 2_000_000L
            val step = listOf(1, 5, 7, 15, 30)[c % 5]
            val base = (120 + next(60)).toDouble()
            val samples = ArrayList<HealthHighlightMath.BeatSample>()
            var t = -40
            while (t <= 230) {
                if (next(8) != 0) {
                    val divisor = (3 + next(4)).toDouble()
                    samples += HealthHighlightMath.BeatSample(end + t, base - t / divisor + next(5))
                }
                t += step + next(3)
            }
            val r = HealthHighlightMath.recovery(samples, end)
            out += if (r == null) "rec $c nil" else
                "rec $c ${r.atEnd} ${r.afterOneMinute} ${r.afterTwoMinutes} ${r.afterThreeMinutes} ${r.drop} " +
                    r.readings.joinToString(",") { "${f(it.seconds)}/${f(it.bpm)}" }
        }

        for (c in 0 until 10) {
            val first = if (c % 2 == 0) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
            val today = LocalDate.of(2026, 1 + next(12), 1 + next(28))
            val active = HashSet<String>()
            for (back in 0 until 40) if (next(3) == 0) active += today.plusDays((-back + 3).toLong()).toString()
            for (weeks in listOf(1, 4)) {
                val k = HealthHighlightMath.consistency(active, weeks, today, first)!!
                out += "days $c $weeks ${k.count} ${k.days.first().date} " + k.days.joinToString("") {
                    when (it.mark) {
                        HighlightConsistency.Mark.DONE -> "d"
                        HighlightConsistency.Mark.MISSED -> "m"
                        HighlightConsistency.Mark.AHEAD -> "a"
                    }
                }
            }
        }
        return out.joinToString("\n")
    }

    @Test
    fun everyCaseMatchesTheSwiftTwin() {
        assertEquals(ORACLE.trim(), oracleOutput())
    }

    // The Swift suite's own cases (StrandTests/HealthHighlightTests.swift), kept readable beside the oracle.

    @Test
    fun progressComparesTodayWithTheUsualTotalByNow() {
        val flat = List(24) { 100.0 }
        val today = List(24) { if (it < 12) 200.0 else 0.0 }
        val p = HealthHighlightMath.progress(today, listOf(flat, flat, flat), ts(LocalDate.of(2026, 10, 11)) + 12 * 3600, ZoneOffset.UTC)!!
        assertEquals(2400.0, p.todayTotal, 0.001)
        assertEquals(1200.0, p.typicalTotal, 0.001)
        assertEquals(0.5, p.nowFraction, 0.0001)
        assertEquals(HighlightDirection.MORE, p.direction)
    }

    @Test
    fun progressLeavesOutDaysOffTheWristAndNeedsThree() {
        val flat = List(24) { 100.0 }
        val empty = List(24) { 0.0 }
        val now = ts(LocalDate.of(2026, 10, 11)) + 12 * 3600
        assertNull(HealthHighlightMath.progress(flat, listOf(flat, flat, empty), now, ZoneOffset.UTC))
        val p = HealthHighlightMath.progress(flat, listOf(flat, flat, flat, empty), now, ZoneOffset.UTC)!!
        assertEquals(2400.0, p.typical.last().total, 0.001)
        assertEquals(HighlightDirection.SAME, p.direction)
    }

    @Test
    fun rangeCutsAWorkoutIntoRoundSlices() {
        val samples = (0 until 54).map { HealthHighlightMath.RangeSample(1000L + it * 60, 100.0 + it, 100.0 + it) }
        val r = HealthHighlightMath.range(samples, 1000, 1000L + 54 * 60)!!
        assertEquals(180L, HealthHighlightMath.sliceLength(54 * 60))
        assertEquals(18, r.slices.size)
        assertEquals(0, r.minimumIndex)
        assertEquals(17, r.maximumIndex)
        assertEquals(HighlightRange.Slice(100.0, 102.0), r.slices[0])
    }

    @Test
    fun consistencyLaysOutWholeWeeksAndLeavesTheDaysAhead() {
        val today = LocalDate.of(2026, 10, 7)
        val active = setOf("2026-10-05", "2026-10-07", "2026-09-30", "2026-10-09", "2026-09-01")
        val month = HealthHighlightMath.consistency(active, 4, today, DayOfWeek.MONDAY)!!
        assertEquals(28, month.days.size)
        assertEquals(LocalDate.of(2026, 9, 14), month.days.first().date)
        assertEquals(3, month.count)
        val week = HealthHighlightMath.consistency(active, 1, today, DayOfWeek.MONDAY)!!
        assertEquals(2, week.count)
    }

    @Test
    fun hourlyTicksKeepTheStepsAcrossAnHourEdge() {
        // A counter rising two ticks a minute through the first three hours of the day.
        val samples = (0..180).map { StepSample(deviceId = "s", ts = it * 60L, counter = it * 2, activityClass = null) }
        val hours = HealthHighlightMath.hourlyTicks(samples, 0, 86_400)
        assertEquals(24, hours.size)
        assertEquals(listOf(118.0, 120.0, 120.0, 2.0), hours.take(4))
        assertEquals(360.0, hours.sum(), 0.0)
    }

    private companion object {
        const val ORACLE = """
dir same,less,same,less,more,less,more,same,more,same,more,same,more,same,more,same,more,same,more,same,more,same,more,same,more,more,more,same,more,more,more,same,more,more,more,more
progress 0 0.0257 0.0000 86.6417 less 2 0.0257 1546.8333 5060.5000
progress 1 0.1625 1096.0000 372.4333 more 5 0.1625 1126.6667 4587.0000
progress 2 nil
progress 3 nil
progress 4 0.4097 4991.0000 2308.6667 more 11 0.4097 1640.0000 5423.7500
progress 5 0.0799 1557.0000 175.5833 more 3 0.0799 933.0000 4936.7500
progress 6 0.9667 7438.0000 6042.5500 more 25 0.9667 1338.5000 6228.7500
progress 7 nil
progress 8 0.8181 6874.0000 4538.1733 more 21 0.8181 1563.2000 5667.8000
progress 9 0.7188 6461.0000 4357.4500 more 19 0.7188 1928.6000 5811.4000
progress 10 nil
progress 11 nil
week 0 7984.2000 11063.0000,1462.0000,11871.0000,4288.0000,-,-,11237.0000 2026-10-12
week 1 6379.8333 5210.0000,7152.0000,2164.0000,9898.0000,-,6257.0000,7598.0000 2026-10-11
week 2 7747.2000 11228.0000,-,-,1120.0000,8956.0000,8265.0000,9167.0000 2026-10-02
week 3 5347.7143 8215.0000,2625.0000,6892.0000,5570.0000,1493.0000,5310.0000,7329.0000 2026-09-26
week 4 5231.8333 4883.0000,2734.0000,5338.0000,9853.0000,-,1421.0000,7162.0000 2026-10-19
week 5 5030.0000 -,7274.0000,2248.0000,-,-,5568.0000,- 2026-10-07
week 6 4987.0000 3940.0000,4161.0000,-,5129.0000,-,692.0000,11013.0000 2026-10-14
week 7 5202.0000 -,1153.0000,11771.0000,11465.0000,3873.0000,2303.0000,647.0000 2026-10-17
week 8 4947.2500 5054.0000,-,808.0000,-,7669.0000,6258.0000,- 2026-10-17
week 9 5787.3333 11190.0000,9741.0000,4650.0000,388.0000,-,5248.0000,3507.0000 2026-10-20
cmp 0 month false 4931.8000 4970.2500 2026-11-01 2026-10-01 same
cmp 0 month true 49318.0000 99405.0000 2026-11-01 2026-10-01 less
cmp 0 year false 4101.9874 4262.2174 2026-01-01 2025-01-01 same
cmp 0 year true 980375.0000 588186.0000 2026-01-01 2025-01-01 more
cmp 1 month false 4539.0833 4396.7241 2025-04-01 2025-03-01 same
cmp 1 month true 54469.0000 127505.0000 2025-04-01 2025-03-01 less
cmp 1 year false 4669.6588 4443.6423 2025-01-01 2024-01-01 more
cmp 1 year true 396921.0000 1217558.0000 2025-01-01 2024-01-01 less
cmp 2 month false 4021.0588 4883.0909 2026-01-01 2025-12-01 less
cmp 2 month true 68358.0000 107428.0000 2026-01-01 2025-12-01 less
cmp 2 year false 4021.0588 4364.5349 2026-01-01 2025-01-01 less
cmp 2 year true 68358.0000 1126050.0000 2026-01-01 2025-01-01 less
cmp 3 month false nil
cmp 3 month true nil
cmp 3 year false nil
cmp 3 year true nil
cmp 4 month false 5169.5714 6117.6190 2026-02-01 2026-01-01 less
cmp 4 month true 72374.0000 128470.0000 2026-02-01 2026-01-01 less
cmp 4 year false 5738.4000 4470.4424 2026-01-01 2025-01-01 more
cmp 4 year true 200844.0000 1202549.0000 2026-01-01 2025-01-01 less
cmp 5 month false 4594.2000 3738.0435 2026-04-01 2026-03-01 more
cmp 5 month true 91884.0000 85975.0000 2026-04-01 2026-03-01 more
cmp 5 year false 4585.6136 4526.7011 2026-01-01 2025-01-01 same
cmp 5 year true 403534.0000 1226736.0000 2026-01-01 2025-01-01 less
cmp 6 month false 7186.0000 3938.3846 2025-11-01 2025-10-01 more
cmp 6 month true 43116.0000 102398.0000 2025-11-01 2025-10-01 less
cmp 6 year false 4544.4486 4617.3623 2025-01-01 2024-01-01 same
cmp 6 year true 1104301.0000 637196.0000 2025-01-01 2024-01-01 more
cmp 7 month false nil
cmp 7 month true nil
cmp 7 year false nil
cmp 7 year true nil
cmp 8 month false nil
cmp 8 month true 6529.0000 98688.0000 2026-06-01 2026-05-01 less
cmp 8 year false 4578.6577 4505.3828 2026-01-01 2025-01-01 same
cmp 8 year true 508231.0000 1153378.0000 2026-01-01 2025-01-01 less
cmp 9 month false 4671.0000 3604.6364 2025-04-01 2025-03-01 more
cmp 9 month true 23355.0000 79302.0000 2025-04-01 2025-03-01 less
cmp 9 year false 4373.1370 4723.4429 2025-01-01 2024-01-01 less
cmp 9 year true 319239.0000 1322564.0000 2025-01-01 2024-01-01 less
cmp 10 month false 4131.8824 4686.1364 2025-05-01 2025-04-01 less
cmp 10 month true 70242.0000 103095.0000 2025-05-01 2025-04-01 less
cmp 10 year false 4200.6542 4404.4422 2025-01-01 2024-01-01 same
cmp 10 year true 449470.0000 1105515.0000 2025-01-01 2024-01-01 less
cmp 11 month false nil
cmp 11 month true nil
cmp 11 year false nil
cmp 11 year true nil
cmp 12 month false 4473.7778 4861.9524 2025-05-01 2025-04-01 less
cmp 12 month true 80528.0000 102101.0000 2025-05-01 2025-04-01 less
cmp 12 year false 4700.3727 4449.0263 2025-01-01 2024-01-01 more
cmp 12 year true 517041.0000 1183441.0000 2025-01-01 2024-01-01 less
cmp 13 month false 4708.7273 4656.6316 2026-03-01 2026-02-01 same
cmp 13 month true 103592.0000 88476.0000 2026-03-01 2026-02-01 more
cmp 13 year false 4425.5455 4382.8442 2026-01-01 2025-01-01 same
cmp 13 year true 292086.0000 1209665.0000 2026-01-01 2025-01-01 less
cmp 14 month false 5497.0000 3282.7143 2026-12-01 2026-11-01 more
cmp 14 month true 60467.0000 68937.0000 2026-12-01 2026-11-01 less
cmp 14 year false 4494.1937 4371.2500 2026-01-01 2025-01-01 same
cmp 14 year true 1137031.0000 472095.0000 2026-01-01 2025-01-01 more
cmp 15 month false nil
cmp 15 month true nil
cmp 15 year false nil
cmp 15 year true nil
range 0 60 8 43.0000 182.0000 1 5 62.0000/145.0000,43.0000/146.0000,50.0000/144.0000,102.0000/167.0000,51.0000/170.0000,48.0000/182.0000,75.0000/150.0000,44.0000/142.0000
range 1 120 13 43.0000 184.0000 0 2 43.0000/156.0000,66.0000/143.0000,68.0000/184.0000,115.0000/157.0000,63.0000/161.0000,43.0000/71.0000,60.0000/128.0000,43.0000/174.0000,87.0000/181.0000,65.0000/157.0000,59.0000/151.0000,91.0000/117.0000,52.0000/153.0000
range 2 180 19 42.0000 181.0000 8 7 -,133.0000/150.0000,72.0000/87.0000,100.0000/115.0000,122.0000/122.0000,78.0000/154.0000,106.0000/166.0000,155.0000/181.0000,42.0000/64.0000,-,52.0000/76.0000,63.0000/84.0000,53.0000/105.0000,-,94.0000/119.0000,-,117.0000/124.0000,-,-
range 3 nil
range 4 300 19 43.0000 170.0000 18 17 -,-,47.0000/137.0000,102.0000/158.0000,44.0000/139.0000,131.0000/146.0000,-,58.0000/74.0000,70.0000/90.0000,64.0000/126.0000,-,72.0000/97.0000,-,123.0000/146.0000,113.0000/133.0000,-,-,159.0000/170.0000,43.0000/71.0000
range 5 nil
range 6 7200 16 46.0000 171.0000 12 0 154.0000/171.0000,118.0000/163.0000,79.0000/118.0000,56.0000/75.0000,-,-,-,-,-,48.0000/54.0000,-,-,46.0000/74.0000,105.0000/130.0000,-,-
range 7 60 13 41.0000 176.0000 9 6 44.0000/70.0000,-,75.0000/169.0000,53.0000/169.0000,-,53.0000/145.0000,115.0000/176.0000,57.0000/66.0000,66.0000/130.0000,41.0000/154.0000,85.0000/120.0000,114.0000/142.0000,49.0000/172.0000
range 8 60 9 48.0000 183.0000 3 3 74.0000/174.0000,101.0000/174.0000,65.0000/107.0000,48.0000/183.0000,98.0000/134.0000,97.0000/164.0000,53.0000/171.0000,67.0000/174.0000,-
range 9 120 13 40.0000 179.0000 1 5 42.0000/160.0000,40.0000/168.0000,48.0000/168.0000,56.0000/98.0000,70.0000/177.0000,55.0000/179.0000,65.0000/144.0000,52.0000/162.0000,65.0000/139.0000,124.0000/151.0000,51.0000/158.0000,45.0000/170.0000,46.0000/149.0000
range 10 180 19 42.0000 183.0000 11 8 44.0000/142.0000,69.0000/114.0000,106.0000/142.0000,47.0000/124.0000,45.0000/89.0000,58.0000/149.0000,96.0000/145.0000,54.0000/85.0000,65.0000/183.0000,77.0000/149.0000,101.0000/103.0000,42.0000/137.0000,59.0000/134.0000,72.0000/138.0000,-,42.0000/91.0000,51.0000/144.0000,86.0000/97.0000,88.0000/100.0000
range 11 300 13 43.0000 176.0000 9 2 57.0000/110.0000,81.0000/99.0000,67.0000/176.0000,-,145.0000/145.0000,77.0000/151.0000,44.0000/158.0000,71.0000/173.0000,111.0000/159.0000,43.0000/148.0000,82.0000/111.0000,110.0000/135.0000,126.0000/152.0000
rec 0 176 164 133 130 46 3.0000/173.4000,15.0000/172.0000,31.0000/170.8333,45.0000/165.0000,60.0000/164.0000,75.0000/161.5000,91.0000/150.2500,105.0000/154.0000,121.0000/143.7500,143.0000/148.1667,153.0000/150.5000,168.0000/140.4000
rec 1 180 166 153 143 37 8.0000/176.0000,19.0000/172.6667,30.0000/170.0000,45.0000/167.0000,61.0000/165.8000,75.0000/166.5000,94.0000/157.2000,109.0000/157.8333,121.0000/152.8000,155.0000/126.3333,167.0000/145.6000
rec 2 165 155 125 122 43 3.0000/165.0000,18.0000/161.0000,34.0000/156.2000,49.0000/147.6667,65.0000/155.1667,87.0000/151.5000,95.0000/150.1667,111.0000/128.0000,120.0000/125.0000,137.0000/142.1667,154.0000/125.5000,169.0000/121.7500
rec 3 164 150 143 134 30 10.0000/159.0000,25.0000/156.6667,42.0000/152.6000,57.0000/149.7500,74.0000/138.3333,89.0000/132.3333,105.0000/135.7500,121.0000/142.8333,136.0000/137.8000,152.0000/138.6667,168.0000/136.0000
rec 4 149 135 117 89 60 22.0000/145.3333,52.0000/134.6000,82.0000/125.5000,114.0000/116.5000,145.0000/97.6667,177.0000/89.0000
rec 5 161 153 136 133 28 2.0000/161.6667,17.0000/157.3333,31.0000/152.2500,46.0000/150.5000,60.0000/153.0000,75.0000/150.5000,91.0000/137.2500,106.0000/139.8000,120.0000/136.0000,139.0000/137.8333,151.0000/133.8333,169.0000/126.2000
rec 6 160 142 117 122 38 0.0000/160.0000,16.0000/158.8000,33.0000/152.4000,51.0000/148.2500,64.0000/140.6667,75.0000/134.0000,95.0000/143.0000,108.0000/124.0000,124.0000/116.6667,145.0000/136.8333,152.0000/109.3333,169.0000/126.2000
rec 7 178 164 150 118 60 2.0000/177.6667,17.0000/173.1667,33.0000/172.4000,50.0000/160.3333,67.0000/168.8333,76.0000/164.3333,92.0000/147.3333,108.0000/142.0000,122.0000/149.5000,158.0000/144.4000,167.0000/122.3333
rec 8 155 136 114 89 66 9.0000/148.0000,25.0000/149.0000,41.0000/142.7500,75.0000/136.0000,106.0000/117.6667,123.0000/114.0000,140.0000/127.6667,156.0000/121.8000,171.0000/98.0000
rec 9 nil
rec 10 129 116 99 68 61 0.0000/129.0000,15.0000/123.0000,31.0000/123.2500,46.0000/119.5000,62.0000/115.5000,78.0000/105.0000,91.0000/107.2500,109.0000/94.6667,121.0000/111.8333,136.0000/100.8000,151.0000/94.2500,166.0000/102.3333,180.0000/68.0000
rec 11 167 151 141 127 40 0.0000/167.0000,19.0000/158.2500,36.0000/151.0000,48.0000/151.0000,64.0000/151.0000,76.0000/144.0000,96.0000/147.0000,106.0000/136.5000,124.0000/145.3333,136.0000/129.0000,153.0000/137.5000,166.0000/130.8000
days 0 1 1 2026-06-15 dmmaaaa
days 0 4 5 2026-05-25 mmmmmdmmdmdmmmmmmdmmmdmmaaaa
days 1 1 0 2026-01-04 maaaaaa
days 1 4 10 2025-12-14 mddmddmmdddmmdmmdmdmmmaaaaaa
days 2 1 2 2026-12-07 mmmmdda
days 2 4 8 2026-11-16 mmdmdmmmmmmddmmdmdmmmmmmmdda
days 3 1 0 2026-09-06 mmaaaaa
days 3 4 4 2026-08-16 mmmmmmmdmmdddmmmmmmmmmmaaaaa
days 4 1 1 2026-03-02 daaaaaa
days 4 4 10 2026-02-09 dmdmmmmdddmdmmmmdmdmddaaaaaa
days 5 1 0 2026-04-19 maaaaaa
days 5 4 8 2026-03-29 mdmmmmmdmddmmddmmddmmmaaaaaa
days 6 1 1 2026-01-19 mmmmdmm
days 6 4 12 2025-12-29 dmdmmmdmdmddmmdddmddmmmmmdmm
days 7 1 1 2026-07-19 mmmdmaa
days 7 4 12 2026-06-28 dmddmmmmmddmdmddmdmddmmmdmaa
days 8 1 0 2026-08-10 mmmmmaa
days 8 4 8 2026-07-20 mdmdmdmmdmddmmmmdmmdmmmmmmaa
days 9 1 1 2026-03-15 mmmdaaa
days 9 4 9 2026-02-22 dmdmmmmmdddmmmmdddmmmmmmdaaa
"""
    }
}
