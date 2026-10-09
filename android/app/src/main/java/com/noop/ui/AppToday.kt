package com.noop.ui

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// MARK: - The one "today" every Today / Yesterday readout counts from
//
// The Summary's pager and stamps counted from the today row (the logical day, which rolls at 04:00) while a
// metric page stamped its latest reading against the calendar day, so between midnight and 04:00 one value
// read "Today" on the Summary and "Latest: Yesterday" on its own page. Both now take an [AppToday]: one
// resolver, one clock read by the caller and passed in. The constructor is private, so a screen cannot
// build its own "today" from a second clock; it can only ask [AppToday.resolve].

/** How a reading's day reads against today. */
internal sealed class DayRelation {
    object Today : DayRelation()
    object Yesterday : DayRelation()

    /** Any other day (older, or a row dated after today), named by its date. */
    data class OnDate(val date: LocalDate) : DayRelation()
}

/**
 * The day the app calls "Today", and the calendar day of the same instant.
 *
 * [date] is the day of the row the dashboard treats as today: the logical day (it rolls at 04:00, #144),
 * or the new calendar day once a night that ended before 04:00 is already banked under it (#304), which
 * is exactly the row `resolveTodayRow` picks. [calendarDate] is the wall-clock day of the same instant, for
 * the bounds that must not pass the real date (#547).
 */
internal class AppToday private constructor(val date: LocalDate, val calendarDate: LocalDate) {

    /** "yyyy-MM-dd", as day rows are keyed. */
    val key: String get() = date.toString()

    val calendarKey: String get() = calendarDate.toString()

    /** The day [offset] days before today: 0 today, 1 yesterday. */
    fun minusDays(offset: Int): LocalDate = date.minusDays(offset.toLong())

    /** How the day [dayKey] ("yyyy-MM-dd") reads from today; null for a missing or unreadable key. */
    fun relation(dayKey: String?): DayRelation? {
        val day = dayKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return when (ChronoUnit.DAYS.between(day, date)) {
            0L -> DayRelation.Today
            1L -> DayRelation.Yesterday
            else -> DayRelation.OnDate(day)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AppToday && other.date == date && other.calendarDate == calendarDate

    override fun hashCode(): Int = 31 * date.hashCode() + calendarDate.hashCode()

    override fun toString(): String = "AppToday($date, calendar $calendarDate)"

    companion object {
        /**
         * Today as of [now]. [todayRowDay] is the day of the view model's today row; it is used only while
         * it is still one of the two days that row can honestly be (the logical day or the calendar day of
         * [now]), so a row cached before the 04:00 roll cannot keep yesterday on screen as "Today".
         */
        fun resolve(todayRowDay: String?, now: ZonedDateTime): AppToday {
            val logical = logicalDay(now)
            val calendar = now.toLocalDate()
            val row = todayRowDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val date = if (row != null && (row == calendar || row == logical)) row else logical
            return AppToday(date, calendar)
        }

        /** [resolve] on the device clock: the one place a screen reads "now" for a day stamp. */
        fun now(todayRowDay: String?, zone: ZoneId = ZoneId.systemDefault()): AppToday =
            resolve(todayRowDay, ZonedDateTime.now(zone))
    }
}
