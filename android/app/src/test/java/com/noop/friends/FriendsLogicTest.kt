package com.noop.friends

import com.noop.ui.friends.FriendsAgo
import com.noop.ui.friends.FriendsClock
import com.noop.ui.friends.FriendsComparison
import com.noop.ui.friends.FriendsComparisonLine
import com.noop.ui.friends.FriendsEvent
import com.noop.ui.friends.FriendsEvents
import com.noop.ui.friends.FriendsList
import com.noop.ui.friends.FriendsSection
import com.noop.ui.friends.FriendsSide
import com.noop.ui.friends.FriendsSleepGoal
import com.noop.ui.friends.FriendsSort
import com.noop.ui.friends.FriendsWeek
import com.noop.ui.friends.friendsInitial
import com.noop.ui.friends.friendsSleepLevel
import com.noop.ui.friends.friendsStrainLevel
import com.noop.ui.friends.sections
import com.noop.ui.sleep.SleepScoreWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What the Friends tab works out from a feed without a screen: "updated N min ago", the comparison
 * sentence, the events of the feed, the paired week, the list's order, and a score's level.
 */
class FriendsLogicTest {
    private val now = 1_791_540_000L
    private val today = "2026-10-08"

    private fun person(nick: String, name: String = nick, share: FriendShare = FriendShare.DEFAULT, vararg days: FriendDay) =
        FriendPerson(FriendProfile(nick, name, share = share), share, days.toList().sortedByDescending { it.day })

    private fun day(
        key: String = today,
        recovery: Int? = null,
        strain: Double? = null,
        sleepScore: Int? = null,
        sleep: FriendSleep? = null,
        workouts: List<FriendWorkout>? = null,
        updatedAt: Long = now - 60,
    ) = FriendDay(key, updatedAt, recovery, strain, sleepScore, sleep, workouts)

    // MARK: "Updated N min ago"

    @Test
    fun anAgeIsSaidInTheOneUnitWorthSaying() {
        assertEquals(FriendsAgo.JustNow, FriendsAgo.of(now, now))
        assertEquals(FriendsAgo.JustNow, FriendsAgo.of(now - 59, now))
        assertEquals(FriendsAgo.Minutes(1), FriendsAgo.of(now - 60, now))
        assertEquals(FriendsAgo.Minutes(6), FriendsAgo.of(now - 6 * 60 - 20, now))
        assertEquals(FriendsAgo.Minutes(59), FriendsAgo.of(now - 3_599, now))
        assertEquals(FriendsAgo.Hours(1), FriendsAgo.of(now - 3_600, now))
        assertEquals(FriendsAgo.Hours(23), FriendsAgo.of(now - 86_399, now))
        assertEquals(FriendsAgo.Days(1), FriendsAgo.of(now - 86_400, now))
        assertEquals(FriendsAgo.Days(3), FriendsAgo.of(now - 3 * 86_400 - 5, now))
    }

    @Test
    fun aStampFromTheFutureIsJustNowAndNoStampIsNoAge() {
        assertEquals(FriendsAgo.JustNow, FriendsAgo.of(now + 40, now))
        assertNull(FriendsAgo.of(null, now))
        assertNull(FriendsAgo.of(0L, now))
    }

    @Test
    fun agesCountFromTheServersClockNotThePhones() {
        // The phone's clock is an hour fast. The feed arrived at device time D stamped server time S.
        val server = now
        val device = now + 3_600
        val updatedAt = server - 6 * 60
        // Seen at once: six minutes, not sixty-six.
        assertEquals(FriendsAgo.Minutes(6), FriendsAgo.of(updatedAt, FriendsClock.serverNow(server, device, device)))
        // Two minutes later on the phone, it reads eight.
        assertEquals(FriendsAgo.Minutes(8), FriendsAgo.of(updatedAt, FriendsClock.serverNow(server, device, device + 120)))
        // A device clock that jumped back does not make the past younger than it was when fetched.
        assertEquals(server, FriendsClock.serverNow(server, device, device - 500))
    }

    // MARK: The comparison sentence

    @Test
    fun theMockupsDayReadsAsTheMockupSays() {
        // You 67 / 59 strain / 74 sleep; the friend 81 / 39 / 88.
        val lines = FriendsComparison.lines(
            day(recovery = 67, strain = 59.0, sleepScore = 74),
            day(recovery = 81, strain = 38.6, sleepScore = 88),
        )
        assertEquals(
            listOf(FriendsComparisonLine.RecoveryLead(FriendsSide.FRIEND, 14), FriendsComparisonLine.StrainLead(FriendsSide.ME)),
            lines,
        )
    }

    @Test
    fun recoveryWithinTwoPointsIsLevelAndBeyondItNamesTheLeader() {
        fun first(mine: Int, theirs: Int) = FriendsComparison.lines(day(recovery = mine), day(recovery = theirs)).first()
        assertEquals(FriendsComparisonLine.RecoveryLevel, first(70, 70))
        assertEquals(FriendsComparisonLine.RecoveryLevel, first(70, 72))
        assertEquals(FriendsComparisonLine.RecoveryLevel, first(72, 70))
        assertEquals(FriendsComparisonLine.RecoveryLead(FriendsSide.FRIEND, 3), first(70, 73))
        assertEquals(FriendsComparisonLine.RecoveryLead(FriendsSide.ME, 30), first(90, 60))
    }

    @Test
    fun strainNeedsAClearGapAndSleepFillsTheSecondLineWhenStrainIsClose() {
        val close = FriendsComparison.lines(
            day(recovery = 60, strain = 40.0, sleepScore = 70),
            day(recovery = 80, strain = 44.9, sleepScore = 82),
        )
        assertEquals(
            listOf(FriendsComparisonLine.RecoveryLead(FriendsSide.FRIEND, 20), FriendsComparisonLine.SleepLead(FriendsSide.FRIEND, 12)),
            close,
        )
        val clear = FriendsComparison.lines(day(recovery = 60, strain = 40.0), day(recovery = 80, strain = 45.0))
        assertEquals(FriendsComparisonLine.StrainLead(FriendsSide.FRIEND), clear[1])
    }

    @Test
    fun atMostTwoLinesAndRecoveryFirst() {
        val lines = FriendsComparison.lines(
            day(recovery = 50, strain = 10.0, sleepScore = 50),
            day(recovery = 90, strain = 90.0, sleepScore = 90),
        )
        assertEquals(2, lines.size)
        assertTrue(lines[0] is FriendsComparisonLine.RecoveryLead)
        assertTrue(lines[1] is FriendsComparisonLine.StrainLead)
    }

    @Test
    fun aScoreOnlyOneSideHasIsNeverCompared() {
        val lines = FriendsComparison.lines(day(recovery = 67, strain = 59.0), day(sleepScore = 88))
        assertEquals(listOf(FriendsComparisonLine.NothingShared), lines)
        assertEquals(listOf(FriendsComparisonLine.NothingShared), FriendsComparison.lines(null, day(recovery = 80)))
        assertEquals(listOf(FriendsComparisonLine.NothingShared), FriendsComparison.lines(day(recovery = 80), null))
        assertEquals(listOf(FriendsComparisonLine.NothingShared), FriendsComparison.lines(null, null))
    }

    @Test
    fun withoutRecoveryStrainAndSleepCarryTheComparison() {
        val lines = FriendsComparison.lines(day(strain = 70.0, sleepScore = 90), day(strain = 20.0, sleepScore = 60))
        assertEquals(
            listOf(FriendsComparisonLine.StrainLead(FriendsSide.ME), FriendsComparisonLine.SleepLead(FriendsSide.ME, 30)),
            lines,
        )
        // Scores that are all too close to call still say something true: nothing to tell apart is not "nothing shared".
        assertEquals(
            listOf(FriendsComparisonLine.RecoveryLevel),
            FriendsComparison.lines(day(recovery = 70, strain = 40.0, sleepScore = 80), day(recovery = 71, strain = 42.0, sleepScore = 81)),
        )
    }

    // MARK: The feed's events

    @Test
    fun aNightAndAWorkoutEachBecomeOneEventNewestFirst() {
        val me = person(
            "ruslan", "Руслан", FriendShare.DEFAULT,
            day(workouts = listOf(FriendWorkout(now - 8_000, "Running", 2_520, 46.7, 148))),
        )
        val friend = person(
            "denchik", "Денис", FriendShare.DEFAULT,
            day(sleepScore = 88, sleep = FriendSleep(now - 40_000, now - 12_000, 474)),
            day("2026-10-07", workouts = listOf(FriendWorkout(now - 90_000, "Strength", 3_480, 53.3, 126))),
        )
        val events = FriendsEvents.derive(listOf(me to true, friend to false), now)
        assertEquals(3, events.size)

        val run = events[0] as FriendsEvent.Workout
        assertTrue(run.isMe)
        assertEquals("Running", run.workout.sport)
        // A workout is an event when it ends.
        assertEquals(now - 8_000 + 2_520, run.ts)

        val woke = events[1] as FriendsEvent.Woke
        assertFalse(woke.isMe)
        assertEquals("Денис", woke.who.name)
        assertEquals(now - 12_000, woke.ts)
        assertEquals(474, woke.asleepMin)
        assertEquals(88, woke.sleepScore)

        assertEquals("Strength", (events[2] as FriendsEvent.Workout).workout.sport)
        assertEquals(events.sortedByDescending { it.ts }, events)
    }

    @Test
    fun whatIsNotSharedProducesNoEvent() {
        val quiet = person("misha", "Миша", FriendShare.NONE, day(recovery = 43, strain = 71.0))
        assertTrue(FriendsEvents.derive(listOf(quiet to false), now).isEmpty())
        assertTrue(FriendsEvents.derive(emptyList(), now).isEmpty())
    }

    @Test
    fun theFeedIsCutToItsLimitKeepingTheNewest() {
        val many = (0 until 20).map { FriendWorkout(now - 1_000L * (it + 1), "Walking", 300) }
        val events = FriendsEvents.derive(listOf(person("ruslan", days = arrayOf(day(workouts = many))) to true), now, limit = 5)
        assertEquals(5, events.size)
        assertEquals(now - 1_000 + 300, events.first().ts)
    }

    @Test
    fun anEventStampedAheadOfTheServersClockIsLeftOut() {
        val ahead = person(
            "denchik", days = arrayOf(
                day(sleep = FriendSleep(now - 100, now + 7_200, 60), workouts = listOf(FriendWorkout(now - 600, "Yoga", 300))),
            ),
        )
        val events = FriendsEvents.derive(listOf(ahead to false), now)
        assertEquals(1, events.size)
        assertTrue(events.single() is FriendsEvent.Workout)
    }

    // MARK: The week

    @Test
    fun theWeekPairsSevenDaysEndingTodayOldestFirst() {
        val me = person("ruslan", days = arrayOf(day(today, recovery = 67), day("2026-10-06", recovery = 71), day("2026-10-01", recovery = 5)))
        val friend = person("denchik", days = arrayOf(day(today, recovery = 81), day("2026-10-07", recovery = 70), day("2026-10-02", recovery = 74)))
        val week = FriendsWeek.of(today, me, friend)!!
        assertEquals((2..8).map { LocalDate.of(2026, 10, it) }, week.days)
        assertEquals(listOf(null, null, null, null, 71, null, 67), week.mine)
        assertEquals(listOf(74, null, null, null, null, 70, 81), week.theirs)
        // A day outside the week (1 October) is not in the average.
        assertEquals(69, week.myAverage)
        assertEquals(75, week.theirAverage)
        assertFalse(week.isEmpty)
    }

    @Test
    fun aWeekWithNoScoreHasNoAverageAndSaysItIsEmpty() {
        val week = FriendsWeek.of(today, person("ruslan"), person("misha", share = FriendShare.NONE))!!
        assertNull(week.myAverage)
        assertNull(week.theirAverage)
        assertTrue(week.isEmpty)
        assertEquals(7, week.days.size)
        assertNull(FriendsWeek.of("not a day", null, null))
        assertTrue(FriendsWeek.of(today, null, null)!!.isEmpty)
    }

    // MARK: The list

    private fun feed(me: FriendPerson, vararg friends: FriendPerson) = FriendsFeed(now, me, friends.toList(), 0)

    @Test
    fun theListOrdersByTheChosenScoreHighestFirst() {
        val me = person("ruslan", "Руслан", FriendShare.DEFAULT, day(recovery = 67, strain = 59.0, sleepScore = 74))
        val denis = person("denchik", "Денис", FriendShare.DEFAULT, day(recovery = 81, strain = 38.6, sleepScore = 88))
        val misha = person("mishka", "Миша", FriendShare.DEFAULT, day(recovery = 43, strain = 71.0, sleepScore = 52))
        val all = feed(me, denis, misha)
        fun order(sort: FriendsSort) = FriendsList.entries(all, sort, today).map { it.person.nick }
        assertEquals(listOf("denchik", "ruslan", "mishka"), order(FriendsSort.RECOVERY))
        assertEquals(listOf("mishka", "ruslan", "denchik"), order(FriendsSort.STRAIN))
        assertEquals(listOf("denchik", "ruslan", "mishka"), order(FriendsSort.SLEEP))
        // The reader's own card is in the list and marked.
        assertEquals(listOf("ruslan"), FriendsList.entries(all, FriendsSort.RECOVERY, today).filter { it.isMe }.map { it.person.nick })
    }

    @Test
    fun aPersonWithoutTheFigureIsListedLastNotDropped() {
        val me = person("ruslan", "Руслан", FriendShare.DEFAULT, day(recovery = 67))
        val quiet = person("anna", "Анна", FriendShare.NONE)
        val stale = person("boris", "Борис", FriendShare.DEFAULT, day("2026-10-07", recovery = 99))
        val zero = person("zed", "Зед", FriendShare.DEFAULT, day(recovery = 0))
        val entries = FriendsList.entries(feed(me, stale, quiet, zero), FriendsSort.RECOVERY, today)
        // A real 0 outranks no figure at all; the two without one follow in name order.
        assertEquals(listOf("ruslan", "zed", "anna", "boris"), entries.map { it.person.nick })
        // Yesterday's score is not passed off as today's.
        assertNull(entries.last().day)
    }

    // MARK: Sleep against its need

    @Test
    fun aNightIsShortAlmostOrMetAgainstItsNeed() {
        fun goal(asleep: Int, need: Int?) = FriendsSleepGoal.of(FriendSleep(1, 2, asleep, needMin = need))
        assertEquals(FriendsSleepGoal.Short(72, 480), goal(408, 480))
        assertEquals(FriendsSleepGoal.Short(30, 480), goal(450, 480))
        assertEquals(FriendsSleepGoal.Almost(495), goal(474, 495))
        assertEquals(FriendsSleepGoal.Met(480), goal(480, 480))
        assertEquals(FriendsSleepGoal.Met(480), goal(530, 480))
        assertNull(goal(400, null))
        assertNull(goal(400, 0))
    }

    @Test
    fun theBarIsTheShareOfTheNeedSlept() {
        assertEquals(0.85f, FriendsSleepGoal.fraction(FriendSleep(1, 2, 408, needMin = 480))!!, 0.001f)
        assertEquals(1f, FriendsSleepGoal.fraction(FriendSleep(1, 2, 600, needMin = 480))!!, 0f)
        assertNull(FriendsSleepGoal.fraction(FriendSleep(1, 2, 408)))
    }

    // MARK: Levels and names

    @Test
    fun strainAndSleepUseTheWordsOfTheirOwnTabs() {
        // Strain: the Workouts tab's bands on 6, 10, 14 and 18 of 21, read off the stored 0-100 axis.
        assertEquals(0, friendsStrainLevel(0.0))
        assertEquals(0, friendsStrainLevel(28.0))
        assertEquals(1, friendsStrainLevel(38.6))
        assertEquals(2, friendsStrainLevel(59.0))
        assertEquals(3, friendsStrainLevel(71.0))
        assertEquals(4, friendsStrainLevel(90.0))
        // Sleep: the Sleep tab's bands.
        assertEquals(SleepScoreWord.POOR, friendsSleepLevel(49))
        assertEquals(SleepScoreWord.FAIR, friendsSleepLevel(52))
        assertEquals(SleepScoreWord.GOOD, friendsSleepLevel(74))
        assertEquals(SleepScoreWord.OPTIMAL, friendsSleepLevel(88))
    }

    @Test
    fun theSharedAndUnsharedSectionsAreNamedInOneOrder() {
        val (on, off) = FriendShare.DEFAULT.sections()
        assertEquals(listOf(FriendsSection.SCORES, FriendsSection.SLEEP, FriendsSection.WORKOUTS), on)
        assertEquals(listOf(FriendsSection.HEART_RATE), off)
        assertEquals(FriendsSection.entries, FriendShare.NONE.sections().second)
        assertTrue(FriendShare(true, true, true, true).sections().second.isEmpty())
    }

    @Test
    fun anAvatarWithoutAPictureShowsTheNamesFirstLetter() {
        assertEquals("Д", friendsInitial("Денис"))
        assertEquals("R", friendsInitial("  ruslan"))
        assertEquals("7", friendsInitial("_7up"))
        assertEquals("?", friendsInitial("…"))
        assertEquals("?", friendsInitial(""))
    }
}
