package com.noop.friends

import com.noop.ui.friends.FriendsAgo
import com.noop.ui.friends.FriendsSleepGoal
import com.noop.ui.friends.friendsInitial
import com.noop.ui.friends.friendsStrainLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Friends tab works out from a feed without a screen: "updated N min ago", the comparison
 * sentence, a night against its need, and a score's level.
 */
class FriendsLogicTest {
    private val now = 1_791_540_000L
    private val today = "2026-10-08"

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
    fun strainUsesTheWordsOfTheWorkoutsTab() {
        // Strain: the Workouts tab's bands on 6, 10, 14 and 18 of 21, read off the stored 0-100 axis.
        assertEquals(0, friendsStrainLevel(0.0))
        assertEquals(0, friendsStrainLevel(28.0))
        assertEquals(1, friendsStrainLevel(38.6))
        assertEquals(2, friendsStrainLevel(59.0))
        assertEquals(3, friendsStrainLevel(71.0))
        assertEquals(4, friendsStrainLevel(90.0))
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
