package com.noop.friends

import com.noop.ui.friends.FriendsHighlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Highlights" on the Friends tab is read out of the days the feed already carries: which workouts count
 * as news, and in what order. The cases are those of the Swift app's `FriendsHighlightsTests`.
 */
class FriendsHighlightsTest {
    private val now = 1_791_500_000L
    private val all = FriendShare(scores = true, sleep = true, workouts = true, hr = false)

    private fun person(id: String, workouts: List<List<FriendWorkout>>) = FriendPerson(
        FriendProfile(id, id.replaceFirstChar { it.uppercase() }), all,
        workouts.mapIndexed { index, list -> FriendDay("2026-10-0${8 - index}", 0, workouts = list) },
    )

    /** A workout that ended [ago] seconds before [now] and ran for half an hour. */
    private fun workout(ago: Long, sport: String = "Running") =
        FriendWorkout(startTs = now - ago - 1800, sport = sport, durationS = 1800, strain = 30.0)

    @Test
    fun whichWorkoutsAreNews() {
        val cases = listOf(
            Triple("just ended", 0L, true),
            Triple("an hour ago", 3600L, true),
            Triple("at the window's edge", FriendsHighlights.WINDOW_SECONDS, true),
            Triple("a second past the window", FriendsHighlights.WINDOW_SECONDS + 1, false),
            Triple("a week ago", 7 * 86_400L, false),
            Triple("a friend's clock runs ahead, within the slack", -FriendsHighlights.CLOCK_SLACK_SECONDS, true),
            Triple("still running by this phone's clock", -FriendsHighlights.CLOCK_SLACK_SECONDS - 1, false),
        )
        for ((name, endedAgo, shown) in cases) {
            val found = FriendsHighlights.recent(listOf(person("anna", listOf(listOf(workout(endedAgo))))), now)
            assertEquals(name, if (shown) 1 else 0, found.size)
        }
    }

    @Test
    fun newestFirstAcrossFriendsAndDays() {
        val anna = person("anna", listOf(listOf(workout(7200)), listOf(workout(90_000, sport = "Yoga"))))
        val max = person("max", listOf(listOf(workout(600, sport = "Cycling"), workout(30_000, sport = "Walking"))))
        val found = FriendsHighlights.recent(listOf(anna, max), now)
        assertEquals(listOf("Cycling", "Running", "Walking", "Yoga"), found.map { it.workout.sport })
        assertEquals(listOf("max", "anna", "max", "anna"), found.map { it.personId })
    }

    @Test
    fun twoFriendsEndingTogetherKeepAStableOrder() {
        val found = FriendsHighlights.recent(
            listOf(person("zoe", listOf(listOf(workout(100)))), person("anna", listOf(listOf(workout(100))))), now,
        )
        assertEquals(listOf("anna", "zoe"), found.map { it.personId })
    }

    @Test
    fun aWorkoutListedTwiceIsShownOnce() {
        val twice = workout(100)
        assertEquals(1, FriendsHighlights.recent(listOf(person("anna", listOf(listOf(twice), listOf(twice)))), now).size)
    }

    @Test
    fun noMoreThanTheLimit() {
        val many = (0 until FriendsHighlights.LIMIT + 5).map { workout(60L * (it + 1)) }
        val found = FriendsHighlights.recent(listOf(person("anna", listOf(many))), now)
        assertEquals(FriendsHighlights.LIMIT, found.size)
        assertEquals(now - 60, found.first().endTs)
    }

    @Test
    fun friendsWithNothingToShow() {
        val silent = FriendPerson(FriendProfile("kate", "Kate"), all, emptyList())
        val rested = FriendPerson(FriendProfile("oleg", "Oleg"), all, listOf(FriendDay("2026-10-08", 0, recovery = 80)))
        assertTrue(FriendsHighlights.recent(listOf(silent, rested), now).isEmpty())
        assertTrue(FriendsHighlights.recent(emptyList(), now).isEmpty())
    }
}
