package com.noop.friends

import com.noop.ui.friends.FriendsBoard
import com.noop.ui.friends.FriendsBoardRow
import com.noop.ui.friends.FriendsBoardRow.Detail
import com.noop.ui.friends.FriendsBoardRow.Standing
import com.noop.ui.friends.FriendsMetric
import com.noop.ui.friends.FriendsOrder
import com.noop.ui.friends.friendsClockDuration
import com.noop.ui.friends.friendsPercent
import com.noop.ui.friends.friendsShortName
import com.noop.ui.workouts.WorkoutFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The Friends tab's list: who comes first under each order, and which figure a card shows. The cases are
 * those of the Swift app's `FriendsBoardTests`, one for one.
 */
class FriendsBoardTest {
    private val today = "2026-10-08"
    private val english = FriendsBoard.nameOrder(Locale.UK)

    private val all = FriendShare(scores = true, sleep = true, workouts = true, hr = false)

    private fun person(
        id: String,
        name: String? = null,
        day: String? = "2026-10-08",
        recovery: Int? = null,
        strain: Double? = null,
        sleep: Int? = null,
        sharesScores: Boolean = true,
    ) = FriendPerson(
        FriendProfile(id, name ?: id.replaceFirstChar { it.uppercase() }),
        all.copy(scores = sharesScores),
        listOfNotNull(day?.let { FriendDay(it, 0, recovery = recovery, strain = strain, sleepScore = sleep) }),
    )

    private fun rows(
        me: FriendPerson,
        friends: List<FriendPerson>,
        order: FriendsOrder,
        meName: String = "Me",
        names: Comparator<String> = english,
    ) = FriendsBoard.rows(me, friends, order, today, meName, names)

    private fun order(rows: List<FriendsBoardRow>) = rows.map { it.person.id }

    @Test
    fun byAScoreTheHighestLeadsAndTheWearerIsAmongFriends() {
        val rows = rows(person("me", recovery = 58), listOf(person("anna", recovery = 56), person("max", recovery = 73)), FriendsOrder.RECOVERY)
        assertEquals(listOf("max", "me", "anna"), order(rows))
        assertEquals(listOf(false, true, false), rows.map { it.isMe })
        assertEquals(listOf(73.0, 58.0, 56.0), rows.map { it.value })
    }

    @Test
    fun eachScoreHasItsOwnOrderAndItsOwnFigure() {
        val me = person("me", recovery = 50, strain = 60.0, sleep = 70)
        val anna = person("anna", recovery = 90, strain = 20.0, sleep = 80)
        val cases = listOf(
            Triple(FriendsOrder.RECOVERY, listOf("anna", "me"), listOf(90.0, 50.0)),
            Triple(FriendsOrder.STRAIN, listOf("me", "anna"), listOf(60.0, 20.0)),
            Triple(FriendsOrder.SLEEP, listOf("anna", "me"), listOf(80.0, 70.0)),
        )
        for ((sort, expected, figures) in cases) {
            val rows = rows(me, listOf(anna), sort)
            assertEquals(sort.wire, expected, order(rows))
            assertEquals(sort.wire, figures, rows.map { it.value })
        }
    }

    @Test
    fun byNameTheWearerIsFiledUnderTheWordOnTheirCard() {
        val rows = rows(
            person("zzz", recovery = 1),
            listOf(person("max", recovery = 99), person("anna", day = null), person("boris", day = "2026-10-07", recovery = 70)),
            FriendsOrder.NAME,
        )
        assertEquals("\"Me\" files after Max, whatever the account is called", listOf("anna", "boris", "max", "zzz"), order(rows))
        assertEquals(listOf(Standing.NoData, Standing.Stale("2026-10-07"), Standing.Today, Standing.Today), rows.map { it.standing })
        assertEquals(listOf(false, false, false, true), rows.map { it.isMe })
    }

    @Test
    fun byNameTheWordForTheWearerIsTheActiveLanguages() {
        val friends = listOf(person("anya", name = "Аня"), person("yana", name = "Яна"), person("boris", name = "Борис"))
        val rows = rows(person("me", name = "Денис"), friends, FriendsOrder.NAME, meName = "Я", names = FriendsBoard.nameOrder(Locale("ru", "RU")))
        assertEquals(listOf("anya", "boris", "me", "yana"), order(rows))
    }

    @Test
    fun byNameTheCardsShowRecovery() {
        assertEquals(FriendsMetric.RECOVERY, FriendsOrder.NAME.metric)
        assertEquals(
            listOf(FriendsMetric.RECOVERY, FriendsMetric.RECOVERY, FriendsMetric.STRAIN, FriendsMetric.SLEEP, FriendsMetric.WORKOUTS),
            FriendsOrder.entries.map { it.metric },
        )
        assertEquals(40.0, rows(person("me", recovery = 40, strain = 80.0), emptyList(), FriendsOrder.NAME).first().value)
    }

    @Test
    fun equalFiguresFallBackToTheName() {
        val rows = rows(
            person("me", recovery = 70),
            listOf(person("zoe", recovery = 80), person("anna", recovery = 80), person("max", recovery = 60)),
            FriendsOrder.RECOVERY,
        )
        assertEquals(listOf("anna", "zoe", "me", "max"), order(rows))
    }

    @Test
    fun aFigureFromAnEarlierDayComesAfterEveryFigureFromToday() {
        val rows = rows(
            person("me", recovery = 40),
            listOf(person("pasha", day = "2026-10-07", recovery = 99), person("ahead", day = "2026-10-09", recovery = 10)),
            FriendsOrder.RECOVERY,
        )
        assertEquals(listOf("me", "ahead", "pasha"), order(rows))
        assertEquals(listOf(Standing.Today, Standing.Today, Standing.Stale("2026-10-07")), rows.map { it.standing })
        assertEquals("yesterday's figure is still shown, said to be yesterday's", 99.0, rows.last().value)
    }

    @Test
    fun peopleWithNoFigureFromTodayComeLastByName() {
        val rows = rows(
            person("me", recovery = 40),
            listOf(
                person("zed", day = null), person("kate", recovery = 80, sharesScores = false),
                person("oleg", strain = 30.0), person("boris", day = "2026-10-06", recovery = 70),
            ),
            FriendsOrder.RECOVERY,
        )
        assertEquals(listOf("me", "boris", "kate", "oleg", "zed"), order(rows))
        assertEquals(
            listOf(Standing.Today, Standing.Stale("2026-10-06"), Standing.NotShared, Standing.NoData, Standing.NoData),
            rows.map { it.standing },
        )
        assertNull("a figure the person does not share is not carried", rows[2].value)
    }

    @Test
    fun theWearerAloneIsStillOnTheList() {
        for (sort in FriendsOrder.entries) {
            assertEquals(sort.wire, listOf("me"), order(rows(person("me", recovery = 50, strain = 50.0, sleep = 88), emptyList(), sort)))
            assertEquals(sort.wire, listOf<Standing>(Standing.NoData), rows(person("me", day = null), emptyList(), sort).map { it.standing })
        }
    }

    // MARK: The second line

    private fun onToday(day: FriendDay, share: FriendShare = all, id: String = "me", name: String = "Me") =
        FriendPerson(FriendProfile(id, name), share, listOf(day.copy(day = today)))

    private fun workout(minutes: Int) = FriendWorkout(startTs = 1_791_400_000, sport = "Running", durationS = minutes * 60)

    @Test
    fun onlyScoresWithAnAmountBehindThemHaveASecondLine() {
        assertEquals(listOf(false, true, true, true), FriendsMetric.entries.map { it.hasDetail })
    }

    @Test
    fun sleepIsFollowedByTheTimeAsleepAndRecoveryByNothing() {
        val night = FriendSleep(startTs = 0, endTs = 0, asleepMin = 488)
        val me = onToday(FriendDay("", 0, recovery = 70, sleepScore = 59, sleep = night))
        assertEquals(Detail.Asleep(488), rows(me, emptyList(), FriendsOrder.SLEEP).first().detail)
        assertNull(rows(me, emptyList(), FriendsOrder.RECOVERY).first().detail)
        assertNull(rows(me, emptyList(), FriendsOrder.NAME).first().detail)
    }

    @Test
    fun strainIsFollowedByTheDaysWorkoutMinutes() {
        val cases = listOf(
            Triple("two workouts add up", listOf(workout(25), workout(44)), 69),
            Triple("a day without a workout", null, 0),
            Triple("seconds round to the minute", listOf(FriendWorkout(startTs = 0, sport = "Yoga", durationS = 1_530)), 26),
        )
        for ((name, workouts, minutes) in cases) {
            val me = onToday(FriendDay("", 0, strain = 31.9, workouts = workouts))
            assertEquals(name, Detail.Workouts(minutes), rows(me, emptyList(), FriendsOrder.STRAIN).first().detail)
        }
    }

    @Test
    fun aSectionAPersonDoesNotShareIsNotGuessedAt() {
        val night = FriendSleep(startTs = 0, endTs = 0, asleepMin = 400)
        val summary = FriendDay("", 0, strain = 40.0, sleepScore = 80, sleep = night, workouts = listOf(workout(30)))
        val quiet = onToday(summary, share = all.copy(sleep = false, workouts = false), id = "kate", name = "Kate")
        val me = person("me", strain = 10.0, sleep = 10)
        for (sort in listOf(FriendsOrder.SLEEP, FriendsOrder.STRAIN)) {
            assertNull(sort.wire, rows(me, listOf(quiet), sort).first { !it.isMe }.detail)
        }
        // Scores without the night behind them: the figure stands alone.
        assertNull(rows(me, emptyList(), FriendsOrder.SLEEP).first().detail)
    }

    // MARK: By workouts

    private fun sporty(id: String, workouts: List<FriendWorkout>?, sharesWorkouts: Boolean = true, dayKey: String = "2026-10-08") =
        FriendPerson(
            FriendProfile(id, id.replaceFirstChar { it.uppercase() }), all.copy(workouts = sharesWorkouts),
            listOf(FriendDay(dayKey, 0, recovery = 50, workouts = workouts)),
        )

    private fun burn(kcal: Int?, sport: String = "Running") =
        FriendWorkout(startTs = 1_791_400_000, sport = sport, durationS = 1_800, kcal = kcal)

    @Test
    fun byWorkoutsTheMostEnergyLeadsAndADayWithoutOneCountsAsZero() {
        val rows = rows(
            sporty("me", emptyList()),
            listOf(sporty("anna", listOf(burn(250))), sporty("max", listOf(burn(400), burn(200, sport = "Yoga")))),
            FriendsOrder.WORKOUTS,
        )
        assertEquals(listOf("max", "anna", "me"), order(rows))
        assertEquals(listOf(600.0, 250.0, 0.0), rows.map { it.value })
        assertEquals(listOf<Detail?>(Detail.Sessions(2, null), Detail.Sessions(1, "Running"), Detail.Sessions(0, null)), rows.map { it.detail })
        assertEquals(listOf<Standing>(Standing.Today, Standing.Today, Standing.Today), rows.map { it.standing })
    }

    @Test
    fun workoutsThatCarryNoEnergyAreListedWithoutAFigure() {
        val rows = rows(sporty("me", listOf(burn(120))), listOf(sporty("anna", listOf(burn(null)))), FriendsOrder.WORKOUTS)
        assertEquals(listOf("me", "anna"), order(rows))
        assertNull("an unknown amount is not shown as zero", rows.last().value)
        assertEquals(Standing.Today, rows.last().standing)
        assertEquals(Detail.Sessions(1, "Running"), rows.last().detail)
    }

    @Test
    fun byWorkoutsOnlyTheWorkoutsSwitchDecidesWhatIsShown() {
        val cases = listOf(
            Triple("workouts switched off", sporty("kate", listOf(burn(300)), sharesWorkouts = false), Standing.NotShared),
            Triple("workouts not read for the day", sporty("kate", null), Standing.NoData),
            Triple("nothing uploaded", FriendPerson(FriendProfile("kate", "Kate"), all, emptyList()), Standing.NoData),
            Triple("yesterday's workouts", sporty("kate", listOf(burn(300)), dayKey = "2026-10-07"), Standing.Stale("2026-10-07")),
        )
        for ((name, friend, standing) in cases) {
            assertEquals(name, standing, rows(sporty("me", emptyList()), listOf(friend), FriendsOrder.WORKOUTS).first { !it.isMe }.standing)
        }
        // Scores switched off do not hide the workouts, which have their own switch.
        val scoresOff = FriendPerson(
            FriendProfile("kate", "Kate"), all.copy(scores = false), listOf(FriendDay(today, 0, workouts = listOf(burn(300)))),
        )
        assertEquals(300.0, rows(sporty("me", emptyList()), listOf(scoresOff), FriendsOrder.WORKOUTS).first().value)
    }

    // MARK: Figures

    @Test
    fun aWorkoutsLengthReadsAsHoursAndMinutes() {
        val cases = listOf(
            0 to "0:00", 29 to "0:00", 30 to "0:01", 1_800 to "0:30", 3_600 to "1:00", 4_320 to "1:12", 36_000 to "10:00", -5 to "0:00",
        )
        for ((seconds, text) in cases) assertEquals("$seconds s", text, friendsClockDuration(seconds))
    }

    @Test
    fun aPersonGoesByTheirGivenNameInASentence() {
        val cases = listOf("Anna Smith" to "Anna", "Anna" to "Anna", "" to "", "anna_92" to "anna_92")
        for ((name, short) in cases) assertEquals(name, short, friendsShortName(name))
    }

    /** Strain is written by the app's one formatter for it (`UnitFormatter.effortDisplay`), as on its own tab. */
    @Test
    fun figuresReadAsTheSummaryWritesThem() {
        assertEquals("540", WorkoutFormat.grouped(540.4, Locale.UK))
        assertEquals("73%", friendsPercent(72.6))
        assertEquals("88%", friendsPercent(88.0))
        assertEquals("0%", friendsPercent(0.0))
    }

    /** Not a Swift case: the order kept between runs reads back, and anything else is by name. */
    @Test
    fun theOrderIsKeptByItsNameAndOpensByName() {
        for (order in FriendsOrder.entries) assertEquals(order, FriendsOrder.of(order.wire))
        assertEquals(listOf("name", "recovery", "strain", "sleep", "workouts"), FriendsOrder.entries.map { it.wire })
        assertEquals(FriendsOrder.NAME, FriendsOrder.of(null))
        assertEquals(FriendsOrder.NAME, FriendsOrder.of("RECOVERY"))
    }
}
