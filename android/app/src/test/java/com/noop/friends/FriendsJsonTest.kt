package com.noop.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the server's answers. The two long fixtures are what `friends-server/server.py` answered to a
 * local run, byte for byte (only the token is replaced), so the shapes are the server's and not this
 * test's idea of them: optional workout members come back as JSON nulls, the feed has no `hr.series`.
 */
class FriendsJsonTest {

    private val feedText = """{"serverTime":1791467413,"me":{"nick":"ruslan","name":"ruslan","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false},"days":[{"workouts":[],"day":"2026-10-08","updatedAt":1791467413}]},"friends":[{"nick":"denchik","name":"denchik","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":true},"days":[{"recovery":81,"strain":38.6,"sleepScore":88,"sleep":{"startTs":1791437412,"endTs":1791466412,"asleepMin":474,"awakeMin":18,"remMin":104,"lightMin":252,"deepMin":100,"needMin":480},"workouts":[{"startTs":1791466512,"sport":"Running","durationS":1860,"strain":35.2,"avgHr":139,"maxHr":162,"kcal":310},{"startTs":1791466612,"sport":"Strength","durationS":600,"strain":null,"avgHr":null,"maxHr":null,"kcal":null}],"hr":{"lastBpm":62,"lastTs":1791467352,"restingBpm":51},"day":"2026-10-08","updatedAt":1791467413}]}],"pendingIncoming":0}"""

    private val userDaysText = """{"nick":"denchik","name":"denchik","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":true},"days":[{"recovery":81,"strain":38.6,"sleepScore":88,"sleep":{"startTs":1791437412,"endTs":1791466412,"asleepMin":474,"awakeMin":18,"remMin":104,"lightMin":252,"deepMin":100,"needMin":480},"workouts":[{"startTs":1791466512,"sport":"Running","durationS":1860,"strain":35.2,"avgHr":139,"maxHr":162,"kcal":310},{"startTs":1791466612,"sport":"Strength","durationS":600,"strain":null,"avgHr":null,"maxHr":null,"kcal":null}],"hr":{"lastBpm":62,"lastTs":1791467352,"restingBpm":51,"series":[[1791467292,64],[1791467352,62]]},"day":"2026-10-08","updatedAt":1791467413}]}"""

    // MARK: The feed

    @Test
    fun theFeedReadsMeEveryFriendAndTheServersClock() {
        val feed = FriendsJson.feed(feedText)
        assertEquals(1_791_467_413L, feed.serverTime)
        assertEquals(0, feed.pendingIncoming)
        assertEquals("ruslan", feed.me.nick)
        assertEquals(FriendShare(scores = true, sleep = true, workouts = true, hr = false), feed.me.share)
        assertEquals(1, feed.friends.size)

        val friend = feed.friends.single()
        assertEquals(FriendProfile("denchik", "denchik", 0, FriendShare(true, true, true, true), null), friend.profile)
        val day = friend.day("2026-10-08")!!
        assertEquals(81, day.recovery)
        assertEquals(38.6, day.strain!!, 0.0)
        assertEquals(88, day.sleepScore)
        assertEquals(1_791_467_413L, day.updatedAt)
        assertEquals(FriendSleep(1_791_437_412, 1_791_466_412, 474, 18, 104, 252, 100, 480), day.sleep)
        assertEquals(1_791_467_413L, friend.lastUpdatedAt)
    }

    @Test
    fun theFeedLeavesTheHeartRateLineOutButKeepsTheLatestReading() {
        val hr = FriendsJson.feed(feedText).friends.single().days.single().hr!!
        assertEquals(FriendHr(lastBpm = 62, lastTs = 1_791_467_352, restingBpm = 51, series = emptyList()), hr)
    }

    @Test
    fun aWorkoutsNullMembersReadAsAbsent() {
        val workouts = FriendsJson.feed(feedText).friends.single().days.single().workouts!!
        assertEquals(FriendWorkout(1_791_466_512, "Running", 1_860, 35.2, 139, 162, 310), workouts[0])
        assertEquals(FriendWorkout(1_791_466_612, "Strength", 600, null, null, null, null), workouts[1])
    }

    @Test
    fun aDayWithOnlyAnEmptyWorkoutListHasNoScoresAndNoWorkouts() {
        val mine = FriendsJson.feed(feedText).me.days.single()
        assertEquals(emptyList<FriendWorkout>(), mine.workouts)
        assertNull(mine.recovery)
        assertNull(mine.strain)
        assertNull(mine.sleepScore)
        assertNull(mine.sleep)
        assertNull(mine.hr)
    }

    @Test
    fun aFriendsFullPageCarriesTheLine() {
        val person = FriendsJson.person(userDaysText)
        assertEquals("denchik", person.nick)
        assertEquals(
            listOf(FriendHrPoint(1_791_467_292, 64), FriendHrPoint(1_791_467_352, 62)),
            person.days.single().hr!!.series,
        )
    }

    // MARK: Missing optional members

    @Test
    fun aBareFeedIsStillAFeed() {
        val feed = FriendsJson.feed("""{"serverTime":10,"me":{"nick":"a_b"}}""")
        assertEquals("a_b", feed.me.profile.name)
        assertEquals(FriendShare.NONE, feed.me.share)
        assertTrue(feed.me.days.isEmpty())
        assertTrue(feed.friends.isEmpty())
        assertEquals(0, feed.pendingIncoming)
    }

    @Test
    fun aSectionAFriendDoesNotShareIsSimplyNotThere() {
        val person = FriendsJson.person(
            """{"nick":"misha","name":"Миша","avatarRev":3,"share":{"scores":false,"sleep":true,"workouts":false,"hr":false},
               "days":[{"day":"2026-10-07","updatedAt":5,"sleep":{"startTs":100,"endTs":200,"asleepMin":1}},
                       {"day":"2026-10-08","updatedAt":9}]}""",
        )
        assertEquals("Миша", person.profile.name)
        assertEquals(3, person.profile.avatarRev)
        // Newest day first, whatever order the server sent.
        assertEquals(listOf("2026-10-08", "2026-10-07"), person.days.map { it.day })
        val older = person.day("2026-10-07")!!
        assertNull(older.recovery)
        assertNull(older.workouts)
        assertNull(older.hr)
        assertEquals(FriendSleep(100, 200, 1), older.sleep)
        assertEquals(9L, person.lastUpdatedAt)
        assertNull(person.day("2026-10-09"))
    }

    @Test
    fun membersOfTheWrongKindReadAsAbsentNotAsZero() {
        val day = FriendsJson.person(
            """{"nick":"x_y","days":[{"day":"2026-10-08","recovery":"81","strain":null,"sleepScore":true,
               "sleep":{"startTs":200,"endTs":100,"asleepMin":5},"workouts":"none","hr":{"lastBpm":60}}]}""",
        ).days.single()
        assertNull(day.recovery)
        assertNull(day.strain)
        assertNull(day.sleepScore)
        assertNull(day.sleep)
        assertNull(day.workouts)
        assertNull(day.hr)
    }

    @Test
    fun scoresOutsideTheirRangeAreBroughtInsideIt() {
        val day = FriendsJson.person("""{"nick":"x_y","days":[{"day":"2026-10-08","recovery":140,"strain":-2.5,"sleepScore":-1}]}""")
            .days.single()
        assertEquals(100, day.recovery)
        assertEquals(0.0, day.strain!!, 0.0)
        assertEquals(0, day.sleepScore)
    }

    @Test
    fun aDayWithoutAKeyAndAWorkoutWithoutASportAreDropped() {
        val person = FriendsJson.person(
            """{"nick":"x_y","days":[{"updatedAt":1,"recovery":50},{"day":"not a day","recovery":50},
               {"day":"2026-10-08","workouts":[{"startTs":1,"durationS":2},{"startTs":1,"sport":"Yoga","durationS":2}]}]}""",
        )
        assertEquals(listOf("2026-10-08"), person.days.map { it.day })
        assertEquals(listOf(FriendWorkout(1, "Yoga", 2)), person.days.single().workouts)
    }

    @Test
    fun aFriendWithoutANicknameIsLeftOutOfTheFeedWithoutLosingTheRest() {
        val feed = FriendsJson.feed("""{"serverTime":1,"me":{"nick":"me_1"},"friends":[{"name":"ghost"},{"nick":"real_1"}]}""")
        assertEquals(listOf("real_1"), feed.friends.map { it.nick })
    }

    // MARK: Profiles, sessions, requests

    @Test
    fun aSessionCarriesTheTokenAndTheAccount() {
        val session = FriendsJson.session(
            """{"token":"t0ken","me":{"nick":"ruslan","name":"ruslan","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false}}}""",
        )
        assertEquals("t0ken", session.token)
        assertEquals(FriendShare.DEFAULT, session.me.share)
        // The token never reaches a log through the session's text.
        assertFalse(session.toString().contains("t0ken"))
    }

    @Test
    fun aProfileWithARelationAndWithoutSwitches() {
        val profile = FriendsJson.profile("""{"nick":"denchik","name":"Денис","avatarRev":2,"relation":"outgoing"}""")
        assertEquals(FriendProfile("denchik", "Денис", 2, null, FriendRelation.OUTGOING), profile)
        assertNull(FriendsJson.profile("""{"nick":"denchik","relation":"blocked"}""").relation)
    }

    @Test
    fun aProfileSurvivesBeingKeptAndReadBack() {
        val mine = FriendProfile("ruslan", "Руслан \"R\"", 4, FriendShare(true, false, true, false))
        assertEquals(mine, FriendsJson.profile(FriendsJson.profileText(mine)))
        val bare = FriendProfile("ruslan", "ruslan")
        assertEquals(bare, FriendsJson.profile(FriendsJson.profileText(bare)))
    }

    @Test
    fun requestsReadBothDirectionsWithTheirTime() {
        val requests = FriendsJson.requests(
            """{"incoming":[{"nick":"ruslan","name":"ruslan","avatarRev":0,"requestedAt":1791467412}],"outgoing":[]}""",
        )
        assertEquals(listOf(FriendRequest(FriendProfile("ruslan", "ruslan"), 1_791_467_412)), requests.incoming)
        assertTrue(requests.outgoing.isEmpty())
        assertEquals(FriendRequests(emptyList(), emptyList()), FriendsJson.requests("{}"))
    }

    @Test
    fun infoAndTheNicknameCheck() {
        assertEquals(
            FriendsServerInfo("renoop-friends", 1, inviteRequired = false),
            FriendsJson.info("""{"name":"renoop-friends","api":1,"inviteRequired":false}"""),
        )
        assertTrue(FriendsJson.info("""{"api":1,"inviteRequired":true}""").inviteRequired)
        assertTrue(FriendsJson.nickFree("""{"nick":"ruslan","free":true}"""))
        assertFalse(FriendsJson.nickFree("""{"nick":"ruslan","free":false}"""))
        assertEquals("abc", FriendsJson.token("""{"token":"abc"}"""))
    }

    // MARK: What is refused whole

    @Test
    fun anAnswerWithoutWhatAScreenNeedsIsRefused() {
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("""{"me":{"nick":"a_b"}}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("""{"serverTime":1}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.profile("""{"name":"no nick"}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.session("""{"token":"","me":{"nick":"a_b"}}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.session("""{"token":"x"}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.nickFree("""{"nick":"a_b"}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("<html>captive portal</html>") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("[]") }
    }

    @Test
    fun theErrorCodeIsReadFromTheServersShapeOnly() {
        assertEquals("nick_taken", FriendsJson.errorCode("""{"error":"nick_taken","message":"This nickname is taken."}"""))
        assertNull(FriendsJson.errorCode("""{"message":"no code"}"""))
        assertNull(FriendsJson.errorCode("""{"error":42}"""))
        assertNull(FriendsJson.errorCode("<html>502 Bad Gateway</html>"))
        assertNull(FriendsJson.errorCode(""))
    }
}
