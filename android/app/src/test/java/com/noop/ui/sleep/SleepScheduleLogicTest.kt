package com.noop.ui.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Pins the Sleep Schedule's pure logic (twin of iOS SleepSchedule): cards, edits, the next wake, the countdown. */
class SleepScheduleLogicTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private fun cal(): Calendar = Calendar.getInstance(utc)

    /** Wed 17 Jun 2026 at [hour]:[minute] UTC, in ms. */
    private fun wed(hour: Int, minute: Int = 0): Long = cal().apply {
        clear(); set(2026, Calendar.JUNE, 17, hour, minute)
    }.timeInMillis

    private fun inputs(
        alarmOn: Boolean = true,
        willArm: Boolean = true,
        wake: Int = 7 * 60,
        days: Set<Int> = emptySet(),
        overrides: Map<Int, Int> = emptyMap(),
        goal: Int = 8 * 60,
        backup: Boolean = false,
    ) = SleepScheduleInputs(alarmOn, willArm, wake, days, overrides, goal, backup)

    @Test fun oneCardForEveryDay() {
        val e = SleepSchedule.entries(inputs())
        assertEquals(1, e.size)
        assertTrue(e[0].base)
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 1), e[0].days)
        assertEquals(23 * 60, e[0].bed)
        assertTrue(e[0].alarm)
    }

    @Test fun ownTimesGroupIntoTheirOwnCardsMondayFirst() {
        // Sat + Sun at 09:00 with the alarm; Fri at 06:00 off the alarm days.
        val e = SleepSchedule.entries(inputs(days = setOf(2, 3, 4, 5, 7, 1), overrides = mapOf(7 to 540, 1 to 540, 6 to 360)))
        assertEquals(3, e.size)
        assertEquals(listOf(2, 3, 4, 5), e[0].days)
        assertEquals(listOf(6), e[1].days)
        assertFalse(e[1].alarm)
        assertEquals(listOf(7, 1), e[2].days)
        assertTrue(e[2].alarm)
        assertEquals(60, e[2].bed)
    }

    @Test fun noWakeSoundsWithoutAnArmedAlarm() {
        assertFalse(SleepSchedule.entries(inputs(alarmOn = false))[0].alarm)
        assertFalse(SleepSchedule.entries(inputs(willArm = false))[0].alarm)
        // The phone backup alone still rings.
        assertTrue(SleepSchedule.entries(inputs(alarmOn = false, backup = true))[0].alarm)
    }

    @Test fun daysSummary() {
        assertEquals(SleepScheduleDays.EveryDay, SleepSchedule.daysSummary((1..7).toList()))
        assertEquals(SleepScheduleDays.Weekdays, SleepSchedule.daysSummary(listOf(2, 3, 4, 5, 6)))
        assertEquals(SleepScheduleDays.Weekends, SleepSchedule.daysSummary(listOf(7, 1)))
        assertEquals(SleepScheduleDays.NoDays, SleepSchedule.daysSummary(emptyList()))
        assertEquals(SleepScheduleDays.Listed(listOf(2, 4)), SleepSchedule.daysSummary(listOf(4, 2)))
    }

    @Test fun nextWakeAndCountdownShareOneClock() {
        val now = wed(4, 19)
        val next = SleepSchedule.nextWake(inputs(), now, ::cal)!!
        assertEquals(wed(7) / 1000, next.wakeTs)
        assertEquals(next.wakeTs - 8 * 3600, next.bedTs)
        assertEquals(SleepCountdown.Span(0, 2, 41), SleepSchedule.countdown(next, now))
    }

    @Test fun countdownOnlyForAnArmedWake() {
        val now = wed(4)
        assertNull(SleepSchedule.countdown(SleepSchedule.nextWake(inputs(alarmOn = false), now, ::cal), now))
        assertNull(SleepSchedule.countdown(SleepSchedule.nextWake(inputs(willArm = false), now, ::cal), now))
        assertEquals(SleepCountdown.UnderAMinute, SleepSchedule.countdown(SleepNextWake(now / 1000 + 30, 0, true), now))
    }

    @Test fun countdownKeepsTwoUnits() {
        // Only Tuesdays: from Wednesday 08:00 the next wake is five days and 23 hours away.
        val now = wed(8)
        val next = SleepSchedule.nextWake(inputs(days = setOf(3)), now, ::cal)!!
        assertEquals(SleepCountdown.Span(5, 23, 0), SleepSchedule.countdown(next, now))
    }

    @Test fun dayOverrideResolvesTheNextWake() {
        // Thursday has its own 05:30; from Wednesday 08:00 that is the next wake.
        val next = SleepSchedule.nextWake(inputs(overrides = mapOf(5 to 330)), wed(8), ::cal)!!
        assertEquals(wed(8) / 1000 + (21 * 60 + 30) * 60, next.wakeTs)
    }

    @Test fun editingTheBaseCardWritesTheAlarmDaysAndGoal() {
        val i = inputs(overrides = mapOf(7 to 540))
        val base = SleepSchedule.entries(i).first()
        val edit = SleepSchedule.editFor(base, i).copy(days = setOf(2, 3, 4, 5, 6), bed = 22 * 60 + 30, wake = 6 * 60 + 30)
        val stored = SleepSchedule.applying(edit, i)!!
        assertEquals(390, stored.baseWake)
        // Saturday keeps ringing at its own time; Sunday drops off the alarm.
        assertEquals(setOf(2, 3, 4, 5, 6, 7), stored.alarmDays)
        assertEquals(mapOf(7 to 540), stored.overrides)
        assertEquals(8 * 60, stored.sleepGoal)
    }

    @Test fun baseCardNeedsADay() {
        val i = inputs()
        val edit = SleepSchedule.editFor(SleepSchedule.entries(i).first(), i).copy(days = emptySet())
        assertNull(SleepSchedule.applying(edit, i))
    }

    @Test fun newScheduleAddsOwnTimesAndDeleteDropsThem() {
        val i = inputs()
        val added = SleepSchedule.applying(SleepSchedule.newEdit(i).copy(days = setOf(7, 1), wake = 540, bed = 60), i)!!
        assertEquals(mapOf(7 to 540, 1 to 540), added.overrides)
        assertEquals(emptySet<Int>(), added.alarmDays) // all seven still ring: stored as "every day"
        val after = i.copy(overrides = added.overrides)
        val own = SleepSchedule.entries(after).first { !it.base }
        val deleted = SleepSchedule.applying(SleepSchedule.editFor(own, after).copy(delete = true), after)!!
        assertTrue(deleted.overrides.isEmpty())
        assertEquals(i.sleepGoal, deleted.sleepGoal)
    }

    @Test fun goalAndDialMovesStayInRange() {
        assertEquals(5 * 60, SleepSchedule.goal(bed = 2 * 60, wake = 4 * 60))
        assertEquals(11 * 60, SleepSchedule.goal(bed = 20 * 60, wake = 10 * 60))
        assertEquals(395, SleepSchedule.snapped(397))
        // Dragging the wake to noon from a 23:00 bedtime stops at the 11-hour limit.
        assertEquals(23 * 60 to 10 * 60, SleepSchedule.moving(bedEnd = false, to = 12 * 60, bed = 23 * 60, wake = 7 * 60))
        // Dragging the bedtime past the wake wraps to the shortest span.
        assertEquals(2 * 60 to 7 * 60, SleepSchedule.moving(bedEnd = true, to = 6 * 60, bed = 23 * 60, wake = 7 * 60))
    }
}
