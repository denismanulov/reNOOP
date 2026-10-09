package com.noop.ui.sleep

import com.noop.alarm.WindDownStore
import com.noop.ui.nextSmartAlarmEpochSec

// MARK: - Sleep schedule (twin of iOS Strand/SleepSchedule/SleepSchedule.swift)
//
// The strap alarm and the bedtime reminder read as one sleep schedule: a card per group of days that share
// a wake time, bedtime = wake − sleep goal. Pure: every figure the schedule page and its editor show comes
// from here, and the next wake resolves through [nextSmartAlarmEpochSec], the resolver the view model arms
// the strap from.
//
// Storage is the existing one: the base wake is the strap alarm's time, its days are the strap alarm's
// weekdays (empty = every day, Calendar numbering 1 = Sun … 7 = Sat), a day with its own time is a per-day
// override of the strap alarm, and the sleep goal is the wind-down store's sleep need.

internal data class SleepScheduleInputs(
    /** The strap alarm switch. */
    val alarmOn: Boolean,
    /** False for a WHOOP 5/MG without Experimental: the strap does not arm it (#864). */
    val alarmWillArm: Boolean,
    /** The wake time of every day without one of its own, minutes after midnight. */
    val baseWake: Int,
    /** Calendar weekdays (1 = Sun … 7 = Sat) the alarm fires on. Empty = every day. */
    val alarmDays: Set<Int>,
    /** Per-day wake times. */
    val overrides: Map<Int, Int>,
    /** Minutes of sleep the schedule plans for. */
    val sleepGoal: Int,
    /** Android: the phone backup alarm is on and allowed, so it rings on the alarm days even without the strap. */
    val backupArmed: Boolean = false,
) {
    /** Whether a wake on an alarm day will actually sound: the strap arms, or the phone backup rings. */
    val sounding: Boolean get() = (alarmOn && alarmWillArm) || backupArmed
}

/** One card of the full schedule. */
internal data class SleepScheduleEntry(
    val base: Boolean,
    /** Calendar weekdays, Monday first. */
    val days: List<Int>,
    val wake: Int,
    val bed: Int,
    /** Whether the strap alarm goes off on these days ("Wake Up" vs "Wake Up — No Alarm"). */
    val alarm: Boolean,
) {
    val id: String get() = (if (base) "base-" else "own-") + days.joinToString(",")
}

/** The next wake, with the evening before it. [armed]: the alarm will sound, so it may count down. */
internal data class SleepNextWake(val wakeTs: Long, val bedTs: Long, val armed: Boolean)

/** What the "Days" summary of a schedule card says. */
internal sealed class SleepScheduleDays {
    object EveryDay : SleepScheduleDays()
    object Weekdays : SleepScheduleDays()
    object Weekends : SleepScheduleDays()
    object NoDays : SleepScheduleDays()
    /** Calendar weekdays, Monday first, to name one by one. */
    data class Listed(val days: List<Int>) : SleepScheduleDays()
}

/** The time left before an armed wake. */
internal sealed class SleepCountdown {
    object UnderAMinute : SleepCountdown()
    /** At most two non-zero units, largest first (days and hours, or hours and minutes). */
    data class Span(val days: Int, val hours: Int, val minutes: Int) : SleepCountdown()
}

/** What the schedule editor hands back. */
internal data class SleepScheduleEdit(
    /** The card being edited; null adds a new schedule with its own time. */
    val original: SleepScheduleEntry?,
    val days: Set<Int>,
    val bed: Int,
    val wake: Int,
    /** Own-time schedules only: whether the strap alarm goes off on their days. */
    val alarm: Boolean,
    val delete: Boolean = false,
) {
    val isBase: Boolean get() = original?.base == true
}

/** The stored settings an edit resolves to. */
internal data class SleepScheduleStored(
    val baseWake: Int,
    val alarmDays: Set<Int>,
    val overrides: Map<Int, Int>,
    val sleepGoal: Int,
)

internal object SleepSchedule {
    const val DAY = 24 * 60
    /** Calendar weekdays laid out Monday first. */
    val weekOrder = listOf(2, 3, 4, 5, 6, 7, 1)
    /** The wind-down store's clamp on the sleep goal. */
    val goalRange = WindDownStore.SLEEP_MIN..WindDownStore.SLEEP_MAX
    /** The dial's step. */
    const val STEP = 5

    fun wrap(minutes: Int): Int = ((minutes % DAY) + DAY) % DAY

    /** The days the alarm may fire on, with "every day" spelled out. */
    fun effectiveAlarmDays(days: Set<Int>): Set<Int> {
        val valid = days.filter { it in 1..7 }.toSet()
        return valid.ifEmpty { (1..7).toSet() }
    }

    /**
     * The base card first (the alarm days without a time of their own), then one card per group of days
     * sharing their own time and alarm state, ordered by their first day. Days neither on the alarm nor
     * holding a time of their own have no card: nothing wakes anybody on them.
     */
    fun entries(inputs: SleepScheduleInputs): List<SleepScheduleEntry> {
        val alarmDays = effectiveAlarmDays(inputs.alarmDays)
        val own = inputs.overrides.filter { (d, m) -> d in 1..7 && m in 0 until DAY }
        val sounding = inputs.sounding
        fun entry(base: Boolean, days: List<Int>, wake: Int, alarm: Boolean) =
            SleepScheduleEntry(base, days, wake, wrap(wake - inputs.sleepGoal), alarm)
        val out = mutableListOf(
            entry(true, weekOrder.filter { it in alarmDays && own[it] == null }, inputs.baseWake, sounding),
        )
        val groups = LinkedHashMap<Pair<Int, Boolean>, MutableList<Int>>()
        for (d in weekOrder) {
            val w = own[d] ?: continue
            groups.getOrPut(w to (d in alarmDays)) { mutableListOf() }.add(d)
        }
        out += groups.map { (key, days) -> entry(false, days, key.first, sounding && key.second) }
            .sortedBy { weekOrder.indexOf(it.days.first()) }
        return out
    }

    /**
     * The next wake the schedule holds, from [nowMs], by the resolver the strap is armed from, so a day
     * with its own time resolves to THAT time. Every readout of the next wake (its date, bedtime and
     * countdown) comes from one call with one clock.
     */
    fun nextWake(
        inputs: SleepScheduleInputs,
        nowMs: Long,
        calendarFactory: () -> java.util.Calendar = { java.util.Calendar.getInstance() },
    ): SleepNextWake? {
        val wake = nextSmartAlarmEpochSec(
            inputs.baseWake, inputs.alarmDays, nowMs, calendarFactory, inputs.overrides,
        ) ?: return null
        return SleepNextWake(wake, wake - inputs.sleepGoal * 60L, inputs.sounding)
    }

    /** The time until an ARMED wake; null for one that will not sound (a countdown is a promise). */
    fun countdown(next: SleepNextWake?, nowMs: Long): SleepCountdown? {
        if (next == null || !next.armed) return null
        val seconds = next.wakeTs - nowMs / 1000
        if (seconds < 60) return SleepCountdown.UnderAMinute
        val totalMinutes = (seconds / 60).toInt()
        val days = totalMinutes / DAY
        val hours = (totalMinutes % DAY) / 60
        val minutes = totalMinutes % 60
        // Two units at most, the larger ones, as Health's countdown reads.
        return if (days > 0) SleepCountdown.Span(days, hours, 0) else SleepCountdown.Span(0, hours, minutes)
    }

    /** A day reads as "on" when the set is empty (= every day) or explicitly contains it. */
    fun weekdayIsSelected(dow: Int, days: Set<Int>): Boolean = days.isEmpty() || dow in days

    /** An alarm-day set written back: all seven collapse to empty, the stored spelling of "every day". */
    fun normalizedAlarmDays(days: Set<Int>): Set<Int> {
        val valid = days.filter { it in 1..7 }.toSet()
        return if (valid.size == 7) emptySet() else valid
    }

    fun daysSummary(days: Collection<Int>): SleepScheduleDays {
        val set = days.toSet()
        return when {
            set.isEmpty() -> SleepScheduleDays.NoDays
            set.size == 7 -> SleepScheduleDays.EveryDay
            set == (2..6).toSet() -> SleepScheduleDays.Weekdays
            set == setOf(1, 7) -> SleepScheduleDays.Weekends
            else -> SleepScheduleDays.Listed(weekOrder.filter { it in set })
        }
    }

    /** The sleep goal a bedtime and wake span, clamped as the wind-down store keeps it. */
    fun goal(bed: Int, wake: Int): Int = wrap(wake - bed).coerceIn(goalRange.first, goalRange.last)

    /** [minutes] on the dial's 5-minute grid. */
    fun snapped(minutes: Int): Int = wrap(Math.round(minutes / STEP.toDouble()).toInt() * STEP)

    /** Moves one end of the schedule, keeping the span inside the goal range by holding the other end. */
    fun moving(bedEnd: Boolean, to: Int, bed: Int, wake: Int): Pair<Int, Int> =
        if (bedEnd) wrap(wake - clampedSpan(wrap(wake - to))) to wake
        else bed to wrap(bed + clampedSpan(wrap(to - bed)))

    private fun clampedSpan(span: Int): Int {
        val lo = goalRange.first
        val hi = goalRange.last
        if (span in lo..hi) return span
        // Past the far side of the range the drag has wrapped round the dial: it means the short end.
        return if (span > (hi + lo + DAY) / 2) lo else if (span > hi) hi else lo
    }

    /**
     * The settings after [edit], or null when the result has no spelling (an alarm-day set cannot be
     * empty, since the empty set is how "every day" is stored).
     *
     * Base card: its days become the alarm days (own-time days that ring keep ringing), a day picked here
     * drops its own time, and its wake is the base wake. Own-time card: its days take its wake as their own
     * time and join (or leave) the alarm days; a day unpicked or deleted drops its own time and falls back
     * to the base card. Either way the bedtime sets the one sleep goal every card shares.
     */
    fun applying(edit: SleepScheduleEdit, inputs: SleepScheduleInputs): SleepScheduleStored? {
        val alarmDays = effectiveAlarmDays(inputs.alarmDays)
        val overrides = inputs.overrides.toMutableMap()
        var baseWake = inputs.baseWake
        var newAlarm = alarmDays
        val picked: Set<Int> = if (edit.delete) emptySet() else edit.days.filter { it in 1..7 }.toSet()
        if (edit.isBase) {
            if (picked.isEmpty()) return null
            val ringingOwn = alarmDays.intersect(overrides.keys) - picked
            newAlarm = picked + ringingOwn
            picked.forEach { overrides.remove(it) }
            baseWake = wrap(edit.wake)
        } else {
            edit.original?.days.orEmpty().filter { it !in picked }.forEach { overrides.remove(it) }
            picked.forEach { overrides[it] = wrap(edit.wake) }
            if (!edit.delete) newAlarm = if (edit.alarm) alarmDays + picked else alarmDays - picked
            if (newAlarm.isEmpty()) return null
        }
        val goal = if (edit.delete) inputs.sleepGoal else goal(edit.bed, edit.wake)
        return SleepScheduleStored(baseWake, normalizedAlarmDays(newAlarm), overrides, goal)
    }

    /** The editor for [entry], seeded from the schedule. */
    fun editFor(entry: SleepScheduleEntry, inputs: SleepScheduleInputs): SleepScheduleEdit =
        SleepScheduleEdit(
            original = entry, days = entry.days.toSet(), bed = entry.bed, wake = entry.wake,
            alarm = entry.days.all { it in effectiveAlarmDays(inputs.alarmDays) },
        )

    /** A new own-time schedule, seeded from the base wake. */
    fun newEdit(inputs: SleepScheduleInputs): SleepScheduleEdit =
        SleepScheduleEdit(null, emptySet(), wrap(inputs.baseWake - inputs.sleepGoal), inputs.baseWake, alarm = true)
}
