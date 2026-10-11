package com.noop.friends

import com.noop.ui.friends.FriendWeek
import com.noop.ui.friends.FriendsDayName
import com.noop.ui.friends.friendsDate
import com.noop.ui.friends.friendsWeekdayLetter
import com.noop.ui.friends.friendsWeekdayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * A person's page on the Friends tab: which seven days it covers and how it names a day. The cases are
 * those of the Swift app's `FriendPageTests`.
 */
class FriendPageTest {
    private fun uploaded(vararg keys: String) = keys.mapIndexed { index, key -> FriendDay(key, 0, recovery = 50 + index) }

    // MARK: The last seven days

    @Test
    fun theWeekIsSevenDaysOldestFirstEndingOnTheAnchor() {
        val days = FriendWeek.days("2026-10-10", emptyList())
        assertEquals(
            listOf("2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10"),
            days.map { it.key },
        )
        assertTrue(days.all { it.summary == null })
    }

    @Test
    fun theWeekCrossesAMonthAndAYear() {
        val cases = listOf("2026-10-03" to "2026-09-27", "2027-01-02" to "2026-12-27", "2028-03-02" to "2028-02-25")
        for ((anchor, first) in cases) {
            val days = FriendWeek.days(anchor, emptyList())
            assertEquals(anchor, 7, days.size)
            assertEquals(anchor, first, days.first().key)
            assertEquals(anchor, anchor, days.last().key)
        }
    }

    @Test
    fun eachDayCarriesWhatWasUploadedForItAndNothingElse() {
        val days = FriendWeek.days("2026-10-10", uploaded("2026-10-10", "2026-10-08", "2026-10-01"))
        assertEquals(
            "a day outside the week is not drawn, and a day without an upload stays empty",
            listOf(null, null, null, null, 51, null, 50), days.map { it.summary?.recovery },
        )
    }

    @Test
    fun aDayUploadedTwiceIsReadOnce() {
        val twice = listOf(FriendDay("2026-10-10", 0, recovery = 80), FriendDay("2026-10-10", 0, recovery = 10))
        assertEquals(80, FriendWeek.days("2026-10-10", twice).last().summary?.recovery)
    }

    @Test
    fun aKeyThatIsNotADateYieldsNoWeek() {
        for (key in listOf("", "today", "2026-13-40", "10.10.2026")) {
            assertTrue(key, FriendWeek.days(key, uploaded("2026-10-10")).isEmpty())
        }
    }

    @Test
    fun theWeekEndsOnTheWearersDayUnlessTheFriendsCalendarIsAhead() {
        val cases = listOf(
            Triple("2026-10-10", null, "2026-10-10"), Triple("2026-10-10", "2026-10-10", "2026-10-10"),
            Triple("2026-10-10", "2026-10-08", "2026-10-10"), Triple("2026-10-10", "2026-10-11", "2026-10-11"),
        )
        for ((today, newest, anchor) in cases) {
            assertEquals("$today / ${newest ?: "nothing uploaded"}", anchor, FriendWeek.anchor(today, newest))
        }
    }

    // MARK: Naming a day

    @Test
    fun aDaysKeyReadsBackAsItself() {
        for (key in listOf("2026-10-10", "2026-01-01", "2028-02-29")) assertEquals(key, friendsDate(key)?.toString())
        assertNull(friendsDate("2026-02-29"))
    }

    @Test
    fun aWeekdayGoesByOneLetterInTheActiveLanguage() {
        // 10 October 2026 is a Saturday.
        val english = Locale.UK
        val russian = Locale("ru", "RU")
        fun week(locale: Locale) = (4..10).map { friendsWeekdayLetter("2026-10-%02d".format(it), locale) }
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), week(english))
        assertEquals(listOf("В", "П", "В", "С", "Ч", "П", "С"), week(russian))
        assertEquals("", friendsWeekdayLetter("nonsense", english))
    }

    @Test
    fun aWorkoutOfThePastWeekIsDatedByItsWeekday() {
        val today = LocalDate.parse("2026-10-10")
        val english = Locale.UK
        val russian = Locale("ru", "RU")
        val cases = listOf(
            Triple("2026-10-08", "Thursday", "Четверг"), Triple("2026-10-05", "Monday", "Понедельник"),
            Triple("2026-10-04", "Sunday", "Воскресенье"),
        )
        for ((key, en, ru) in cases) {
            val name = FriendsDayName.of(key, today, weekdays = true) as FriendsDayName.Weekday
            assertEquals(key, en, friendsWeekdayName(name.day, english))
            assertEquals(key, ru, friendsWeekdayName(name.day, russian))
        }
    }

    @Test
    fun todayYesterdayAndAnythingOlderThanAWeekAreNamedAsEverywhereElse() {
        val today = LocalDate.parse("2026-10-10")
        // Today and yesterday by their words, a week back and beyond (and a day ahead) by the date: the
        // same answer the list gives, so the page and the list never name one day two ways.
        for (key in listOf("2026-10-10", "2026-10-09", "2026-10-03", "2026-09-01", "2026-10-11")) {
            assertEquals(key, FriendsDayName.of(key, today, weekdays = false), FriendsDayName.of(key, today, weekdays = true))
        }
        assertEquals(FriendsDayName.Today, FriendsDayName.of("2026-10-10", today, weekdays = true))
        assertEquals(FriendsDayName.Yesterday, FriendsDayName.of("2026-10-09", today, weekdays = true))
        assertEquals(FriendsDayName.OnDate(LocalDate.parse("2026-10-03")), FriendsDayName.of("2026-10-03", today, weekdays = true))
        assertEquals(FriendsDayName.OnDate(LocalDate.parse("2026-10-11")), FriendsDayName.of("2026-10-11", today, weekdays = true))
        // The list names a day of the past week by its date too: only a page's workouts go by the weekday.
        assertEquals(FriendsDayName.OnDate(LocalDate.parse("2026-10-06")), FriendsDayName.of("2026-10-06", today, weekdays = false))
        assertEquals(FriendsDayName.Weekday(DayOfWeek.TUESDAY), FriendsDayName.of("2026-10-06", today, weekdays = true))
        assertNull(FriendsDayName.of("nonsense", today, weekdays = true))
    }
}
