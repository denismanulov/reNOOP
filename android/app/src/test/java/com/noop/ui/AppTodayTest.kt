package com.noop.ui

import com.noop.ui.summary.SummaryStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One fact, one readout: the day a value is stamped against.
 *
 * Between 00:00 and 04:00 the Summary stamped a value "Today" (the logical day, 04:00 roll) while its
 * metric page said "Latest: Yesterday" (the calendar day). Both now count from one [AppToday]. The first
 * half pins the resolver; the second audits the SOURCE, because a test that only exercises the resolver
 * passes while a new reader resolves the day by itself with its own clock (AGENTS.md: assert the single
 * resolver, do not count call sites).
 */
class AppTodayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun at(day: String, hour: Int, minute: Int = 0) =
        LocalDateTime.of(LocalDate.parse(day), java.time.LocalTime.of(hour, minute)).atZone(zone)

    // MARK: The resolver

    @Test fun smallHoursAreStillYesterdaysToday() {
        // 01:00 on 3 Oct, no night banked for the 3rd: the today row is the 2nd's (or not loaded yet).
        for (row in listOf("2026-10-02", null)) {
            val today = AppToday.resolve(row, at("2026-10-03", 1))
            assertEquals(LocalDate.parse("2026-10-02"), today.date)
            assertEquals(LocalDate.parse("2026-10-03"), today.calendarDate)
            // The reading of the 2nd is "Today" for the Summary card AND for the metric page's "Latest".
            assertEquals(DayRelation.Today, today.relation("2026-10-02"))
            assertEquals(SummaryStamp.Today, SummaryStamp.resolve("2026-10-02", today))
            assertEquals(DayRelation.Yesterday, today.relation("2026-10-01"))
            assertEquals(SummaryStamp.Yesterday, SummaryStamp.resolve("2026-10-01", today))
        }
    }

    @Test fun aNightBankedBeforeFourMovesTodayForEveryone() {
        // #304: woke at 03:10, the night is banked under the 3rd, so the today row is the 3rd's.
        val today = AppToday.resolve("2026-10-03", at("2026-10-03", 3, 30))
        assertEquals(LocalDate.parse("2026-10-03"), today.date)
        assertEquals(DayRelation.Today, today.relation("2026-10-03"))
        // Yesterday is the 2nd for the stamp and for the pager alike (offset 1 is the day before today).
        assertEquals(DayRelation.Yesterday, today.relation("2026-10-02"))
        assertEquals(LocalDate.parse("2026-10-02"), today.minusDays(1))
    }

    @Test fun daytimeIsTheCalendarDay() {
        val today = AppToday.resolve("2026-10-03", at("2026-10-03", 14))
        assertEquals("2026-10-03", today.key)
        assertEquals("2026-10-03", today.calendarKey)
        assertEquals(DayRelation.OnDate(LocalDate.parse("2026-09-24")), today.relation("2026-09-24"))
        assertEquals(SummaryStamp.OnDate(LocalDate.parse("2026-09-24")), SummaryStamp.resolve("2026-09-24", today))
    }

    @Test fun rollsAtFour() {
        assertEquals("2026-10-02", AppToday.resolve(null, at("2026-10-03", 3, 59)).key)
        assertEquals("2026-10-03", AppToday.resolve(null, at("2026-10-03", 4, 0)).key)
        assertEquals("2026-10-03", AppToday.resolve(null, at("2026-10-03", 23, 59)).key)
    }

    @Test fun aRowCachedBeforeTheRollDoesNotKeepYesterdayAsToday() {
        // The view model's today row is re-resolved only when the day history changes, so at 05:00 it can
        // still be the 2nd's. It is neither the logical nor the calendar day any more: today is the 3rd.
        val today = AppToday.resolve("2026-10-02", at("2026-10-03", 5))
        assertEquals("2026-10-03", today.key)
        assertEquals(DayRelation.Yesterday, today.relation("2026-10-02"))
    }

    @Test fun aStrayOrUnreadableRowDayIsIgnored() {
        assertEquals("2026-10-03", AppToday.resolve("2031-01-01", at("2026-10-03", 12)).key)
        assertEquals("2026-10-03", AppToday.resolve("not a day", at("2026-10-03", 12)).key)
    }

    @Test fun relationOfNothingIsNothing() {
        val today = AppToday.resolve(null, at("2026-10-03", 12))
        assertNull(today.relation(null))
        assertNull(today.relation("garbage"))
        assertNull(SummaryStamp.resolve(null, today))
        // A row dated after today (a strap clock ahead) is named by its date, never "Today".
        assertEquals(DayRelation.OnDate(LocalDate.parse("2026-10-04")), today.relation("2026-10-04"))
    }

    @Test fun pastDayCardsStampOnlyCarriedValues() {
        val today = AppToday.resolve(null, at("2026-09-30", 12))
        assertNull(SummaryStamp.forCard("2026-09-24", 6, "2026-09-24", today))
        assertEquals(
            SummaryStamp.OnDate(LocalDate.parse("2026-09-23")),
            SummaryStamp.forCard("2026-09-23", 6, "2026-09-24", today),
        )
        assertEquals(SummaryStamp.Today, SummaryStamp.forCard("2026-09-30", 0, "2026-09-30", today))
    }

    // MARK: The single resolver (source audit)

    private fun uiSourceDir(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(userDir, "src/main/java/com/noop/ui"),
            File(userDir, "app/src/main/java/com/noop/ui"),
            File(userDir, "android/app/src/main/java/com/noop/ui"),
        ).firstOrNull { it.isDirectory }
    }

    private fun stripComments(source: String): String {
        val noBlocks = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(source, "")
        return noBlocks.lineSequence().joinToString("\n") { it.substringBefore("//") }
    }

    /** The clock reads that resolve a DAY. An instant ("5 minutes ago", a window's end) is not one. */
    private val dayClockReads = listOf(
        "LocalDate.now(", "LocalDateTime.now(", "ZonedDateTime.now(", "logicalDayNow(", "logicalDayKeyNow(",
        "Calendar.getInstance(",
    )

    @Test fun summaryAndMetricPagesReadNoDayClockOfTheirOwn() {
        val ui = uiSourceDir()
        assumeTrue("ui sources not found from ${System.getProperty("user.dir")}", ui != null)
        val offenders = listOf("summary", "metric")
            .flatMap { File(ui, it).listFiles { f -> f.extension == "kt" }.orEmpty().toList() }
            .flatMap { file ->
                val code = stripComments(file.readText())
                dayClockReads.filter { it in code }.map { "${file.name}: $it" }
            }
        assertTrue(
            "These read their own day clock; take the screen's AppToday instead (one today for every " +
                "Today/Yesterday readout): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test fun bothStampsGoThroughTheOneResolver() {
        val ui = uiSourceDir()
        assumeTrue("ui sources not found from ${System.getProperty("user.dir")}", ui != null)
        val metricLabels = stripComments(File(ui, "metric/MetricDateLabels.kt").readText())
        val summaryLogic = stripComments(File(ui, "summary/SummaryLogic.kt").readText())
        // Each decides Today / Yesterday / a date by asking AppToday, and only takes an AppToday to ask.
        assertTrue(Regex("fun stamp\\(day: String, today: AppToday, locale: Locale\\)[^{]*=\\s*when \\(val r = today\\.relation\\(day\\)\\)").containsMatchIn(metricLabels))
        assertTrue(Regex("fun resolve\\(dayKey: String\\?, today: AppToday\\)[^{]*=\\s*when \\(val r = today\\.relation\\(dayKey\\)\\)").containsMatchIn(summaryLogic))
        // AppToday can only come from its resolver: no public constructor to feed a second clock into.
        val appToday = stripComments(File(ui, "AppToday.kt").readText())
        assertTrue("AppToday's constructor must stay private", "class AppToday private constructor(" in appToday)
    }
}
