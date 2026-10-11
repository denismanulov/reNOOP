package com.noop.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the server's answers (API version 2). The fixtures marked "as answered" are what
 * `friends-server/server.py` answered to a local run on a scratch database, byte for byte, so the shapes
 * are the server's and not this test's idea of them: optional workout members come back as JSON nulls,
 * the feed has no `hr.series`, a confirmed phone's `probationUntil` is a null.
 */
class FriendsJsonTest {
    private val ruslan = "b046717b9125ce33"
    private val denchik = "fd1bb902262c8f5a"
    private val phone = "fff60b13b064963d5eaf9f8ecd4ed8e1b194e9ff9f56baaf44f94e9815dbfc4e"

    /** `GET /v2/feed?days=7`, as answered: one friend with a day, and a new phone waiting to join. */
    private val feedText = """{"serverTime":1791717772,"me":{"id":"b046717b9125ce33","name":"ruslan","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false},"strapBound":true,"device":{"id":"fff60b13b064963d5eaf9f8ecd4ed8e1b194e9ff9f56baaf44f94e9815dbfc4e","probationUntil":null},"days":[{"workouts":[],"day":"2026-10-11","updatedAt":1791717772}]},"friends":[{"id":"fd1bb902262c8f5a","name":"denchik","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":true},"days":[{"recovery":81,"strain":38.6,"sleepScore":88,"sleep":{"startTs":1791687772,"endTs":1791716772,"asleepMin":474,"awakeMin":18,"remMin":104,"lightMin":252,"deepMin":100,"needMin":480},"workouts":[{"startTs":1791716872,"sport":"Running","durationS":1860,"strain":35.2,"avgHr":139,"maxHr":162,"kcal":310},{"startTs":1791716972,"sport":"Strength","durationS":600,"strain":null,"avgHr":null,"maxHr":null,"kcal":null}],"hr":{"lastBpm":62,"lastTs":1791717712,"restingBpm":51},"day":"2026-10-11","updatedAt":1791717772}]}],"claims":[{"id":1,"kind":"join","code":"472239","state":"pending","platform":"ios","createdAt":1791717772,"maturesAt":1791890572}],"strapClaim":null,"unconfirmed":[]}"""

    /** `GET /v2/users/{id}/days?days=7`, as answered. */
    private val userDaysText = """{"id":"fd1bb902262c8f5a","name":"denchik","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":true},"days":[{"recovery":81,"strain":38.6,"sleepScore":88,"sleep":{"startTs":1791687772,"endTs":1791716772,"asleepMin":474,"awakeMin":18,"remMin":104,"lightMin":252,"deepMin":100,"needMin":480},"workouts":[{"startTs":1791716872,"sport":"Running","durationS":1860,"strain":35.2,"avgHr":139,"maxHr":162,"kcal":310},{"startTs":1791716972,"sport":"Strength","durationS":600,"strain":null,"avgHr":null,"maxHr":null,"kcal":null}],"hr":{"lastBpm":62,"lastTs":1791717712,"restingBpm":51,"series":[[1791717652,64],[1791717712,62]]},"day":"2026-10-11","updatedAt":1791717772}]}"""

    // MARK: The feed

    @Test
    fun theFeedReadsMeEveryFriendWhatWaitsAndTheServersClock() {
        val feed = FriendsJson.feed(feedText)
        assertEquals(1_791_717_772L, feed.serverTime)
        assertEquals(ruslan, feed.me.id)
        assertEquals(FriendShare(scores = true, sleep = true, workouts = true, hr = false), feed.me.share)
        assertEquals(true, feed.me.profile.strapBound)
        assertEquals(FriendsDeviceState(phone, probationUntil = null), feed.me.profile.device)
        assertEquals(1, feed.friends.size)

        val friend = feed.friends.single()
        assertEquals(FriendProfile(denchik, "denchik", 0, FriendShare(true, true, true, true)), friend.profile)
        val day = friend.day("2026-10-11")!!
        assertEquals(81, day.recovery)
        assertEquals(38.6, day.strain!!, 0.0)
        assertEquals(88, day.sleepScore)
        assertEquals(1_791_717_772L, day.updatedAt)
        assertEquals(FriendSleep(1_791_687_772, 1_791_716_772, 474, 18, 104, 252, 100, 480), day.sleep)
        assertEquals(1_791_717_772L, friend.lastUpdatedAt)

        assertEquals(
            listOf(FriendsClaim(1, FriendsClaim.Kind.JOIN, "472239", FriendsClaim.State.PENDING, "ios", 1_791_717_772L, 1_791_890_572L)),
            feed.claims,
        )
        assertNull(feed.strapClaim)
        assertTrue(feed.unconfirmed.isEmpty())
    }

    @Test
    fun theFeedLeavesTheHeartRateLineOutButKeepsTheLatestReading() {
        val hr = FriendsJson.feed(feedText).friends.single().days.single().hr!!
        assertEquals(FriendHr(lastBpm = 62, lastTs = 1_791_717_712, restingBpm = 51, series = emptyList()), hr)
    }

    @Test
    fun aWorkoutsNullMembersReadAsAbsent() {
        val workouts = FriendsJson.feed(feedText).friends.single().days.single().workouts!!
        assertEquals(FriendWorkout(1_791_716_872, "Running", 1_860, 35.2, 139, 162, 310), workouts[0])
        assertEquals(FriendWorkout(1_791_716_972, "Strength", 600, null, null, null, null), workouts[1])
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
        assertEquals(denchik, person.id)
        assertEquals(
            listOf(FriendHrPoint(1_791_717_652, 64), FriendHrPoint(1_791_717_712, 62)),
            person.days.single().hr!!.series,
        )
    }

    /** This account's own request for a strap, and a phone that got in while nobody answered. */
    @Test
    fun theFeedCarriesTheAccountsOwnStrapRequestAndAnUnconfirmedPhone() {
        val feed = FriendsJson.feed(
            """{"serverTime":10,"me":{"id":"00000000000000aa","name":"Anna","device":{"id":"k","probationUntil":1792104800}},
               "strapClaim":{"id":2,"kind":"take","code":"389131","state":"pending","platform":"ios","createdAt":1791717772,"maturesAt":1791890572},
               "unconfirmed":[{"id":"k2","platform":"android","addedAt":1791500000,"lastSeenAt":1791539000,"probationUntil":1792104800,"current":false},
                              {"id":"no platform"}]}""",
        )
        assertEquals(FriendsClaim.Kind.TAKE, feed.strapClaim?.kind)
        assertEquals(1_792_104_800L, feed.me.profile.device?.probationUntil)
        assertEquals(
            listOf(FriendsDevice("k2", "android", 1_791_500_000L, 1_791_539_000L, 1_792_104_800L, current = false)),
            feed.unconfirmed,
        )
    }

    // MARK: Missing optional members

    @Test
    fun aBareFeedIsStillAFeed() {
        val feed = FriendsJson.feed("""{"serverTime":10,"me":{"id":"00000000000000aa"}}""")
        assertEquals("", feed.me.profile.name)
        assertEquals(FriendShare.NONE, feed.me.share)
        assertTrue(feed.me.days.isEmpty())
        assertTrue(feed.friends.isEmpty())
        assertTrue(feed.claims.isEmpty())
        assertNull(feed.strapClaim)
        assertTrue(feed.unconfirmed.isEmpty())
        assertNull(feed.me.profile.device)
        assertNull(feed.me.profile.strapBound)
    }

    @Test
    fun aSectionAFriendDoesNotShareIsSimplyNotThere() {
        val person = FriendsJson.person(
            """{"id":"00000000000000bb","name":" Миша ","avatarRev":3,"share":{"scores":false,"sleep":true,"workouts":false,"hr":false},
               "days":[{"day":"2026-10-07","updatedAt":5,"sleep":{"startTs":100,"endTs":200,"asleepMin":1}},
                       {"day":"2026-10-08","updatedAt":9}]}""",
        )
        assertEquals("a name is read without the spaces around it", "Миша", person.profile.name)
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
            """{"id":"00000000000000bb","days":[{"day":"2026-10-08","recovery":"81","strain":null,"sleepScore":true,
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
        val day = FriendsJson.person("""{"id":"00000000000000bb","days":[{"day":"2026-10-08","recovery":140,"strain":-2.5,"sleepScore":-1}]}""")
            .days.single()
        assertEquals(100, day.recovery)
        assertEquals(0.0, day.strain!!, 0.0)
        assertEquals(0, day.sleepScore)
    }

    @Test
    fun aDayWithoutAKeyAndAWorkoutWithoutASportAreDropped() {
        val person = FriendsJson.person(
            """{"id":"00000000000000bb","days":[{"updatedAt":1,"recovery":50},{"day":"not a day","recovery":50},
               {"day":"2026-10-08","workouts":[{"startTs":1,"durationS":2},{"startTs":1,"sport":"Yoga","durationS":2}]}]}""",
        )
        assertEquals(listOf("2026-10-08"), person.days.map { it.day })
        assertEquals(listOf(FriendWorkout(1, "Yoga", 2)), person.days.single().workouts)
    }

    /** A list is read around what cannot be read in it: one friend without an id does not cost the others. */
    @Test
    fun aFriendWithoutAnAccountIdIsLeftOutOfTheFeedWithoutLosingTheRest() {
        val feed = FriendsJson.feed(
            """{"serverTime":1,"me":{"id":"00000000000000aa"},
               "friends":[{"name":"ghost"},{"id":"not-an-id","name":"x"},{"id":"00000000000000AA"},{"id":"00000000000000bb"}],
               "claims":[{"id":4,"kind":"sideways","code":"1","state":"pending","createdAt":1},
                         {"id":5,"kind":"join","code":"1","state":"unheard-of","createdAt":1},
                         {"id":6,"kind":"join","code":"123456","state":"pending","createdAt":1}]}""",
        )
        assertEquals(listOf("00000000000000bb"), feed.friends.map { it.id })
        assertEquals(listOf(6L), feed.claims.map { it.id })
        assertNull("a claim that is not pending any more has no moment to wait for", feed.claims.single().maturesAt)
        assertNull(feed.claims.single().platform)
    }

    // MARK: The account, claims, phones, invites

    /** `POST /v2/enroll` and `GET /v2/me`, as answered. */
    @Test
    fun theWearersOwnAccountCarriesItsStrapAndThisPhone() {
        val me = """{"id":"b046717b9125ce33","name":"ruslan","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false},"strapBound":true,"device":{"id":"fff60b13b064963d5eaf9f8ecd4ed8e1b194e9ff9f56baaf44f94e9815dbfc4e","probationUntil":null}}"""
        val expected = FriendProfile(ruslan, "ruslan", 0, FriendShare.DEFAULT, strapBound = true, device = FriendsDeviceState(phone))
        assertEquals(expected, FriendsJson.profile(me))
        assertEquals(expected, FriendsJson.enrolledAccount("""{"me":$me}"""))
    }

    /** `POST /v2/claims`, `GET /v2/claims/mine` and both answers of `PUT /v2/me/strap`, as answered. */
    @Test
    fun aClaimAndTheTwoAnswersToBindingAStrap() {
        val claim = FriendsClaim(1, FriendsClaim.Kind.JOIN, "472239", FriendsClaim.State.PENDING, "ios", 1_791_717_772L, 1_791_890_572L)
        assertEquals(
            claim,
            FriendsJson.claimAnswer("""{"claim":{"id":1,"kind":"join","code":"472239","state":"pending","platform":"ios","createdAt":1791717772,"maturesAt":1791890572}}"""),
        )
        assertEquals(FriendsStrapAnswer.Bound, FriendsJson.strapAnswer("""{"bound":true}"""))
        assertEquals(
            FriendsStrapAnswer.Claimed(claim.copy(id = 2, kind = FriendsClaim.Kind.TAKE, code = "389131")),
            FriendsJson.strapAnswer("""{"claim":{"id":2,"kind":"take","code":"389131","state":"pending","platform":"ios","createdAt":1791717772,"maturesAt":1791890572}}"""),
        )
    }

    /** A request in flight is kept between launches as text, and reads back as it was. */
    @Test
    fun aClaimSurvivesBeingKeptAndReadBack() {
        val pending = FriendsClaim(3, FriendsClaim.Kind.JOIN, "481902", FriendsClaim.State.PENDING, "ios", 1_791_540_000L, 1_791_712_800L)
        assertEquals(pending, FriendsJson.claim(FriendsJson.claimText(pending)))
        val settled = FriendsClaim(4, FriendsClaim.Kind.TAKE, "000007", FriendsClaim.State.EXPIRED, null, 1L, null)
        assertEquals(settled, FriendsJson.claim(FriendsJson.claimText(settled)))
        assertNull(FriendsJson.claim("not a claim"))
        assertNull(FriendsJson.claim("""{"id":3}"""))
    }

    /** `GET /v2/me/devices`, `POST /v2/invites`, `GET /v2/invites` and `POST /v2/invites/redeem`, as answered. */
    @Test
    fun phonesInvitesAndANewFriend() {
        assertEquals(
            listOf(FriendsDevice(phone, "android", 1_791_717_772L, 1_791_717_772L, null, current = true)),
            FriendsJson.devices("""{"devices":[{"id":"fff60b13b064963d5eaf9f8ecd4ed8e1b194e9ff9f56baaf44f94e9815dbfc4e","platform":"android","addedAt":1791717772,"lastSeenAt":1791717772,"probationUntil":null,"current":true}]}"""),
        )
        assertEquals(
            FriendsInvite("5fb5698ab598cab7", "G2A13-4RP04", null, 1_792_322_572L),
            FriendsJson.invite("""{"id":"5fb5698ab598cab7","code":"G2A13-4RP04","expiresAt":1792322572}"""),
        )
        assertEquals(
            listOf(FriendsInvite("5fb5698ab598cab7", null, 1_791_717_772L, 1_792_322_572L)),
            FriendsJson.invites("""{"invites":[{"id":"5fb5698ab598cab7","createdAt":1791717772,"expiresAt":1792322572}]}"""),
        )
        assertEquals(
            FriendProfile(ruslan, "ruslan"),
            FriendsJson.redeemedFriend("""{"friend":{"id":"b046717b9125ce33","name":"ruslan","avatarRev":0}}"""),
        )
    }

    @Test
    fun info() {
        assertEquals(
            FriendsServerInfo("renoop-friends", 2, 1_791_717_772L),
            FriendsJson.info("""{"name":"renoop-friends","api":2,"time":1791717772}"""),
        )
    }

    // MARK: What is refused whole

    @Test
    fun anAnswerWithoutWhatAScreenNeedsIsRefused() {
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("""{"me":{"id":"00000000000000aa"}}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("""{"serverTime":1}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("""{"serverTime":1,"me":{"id":"anna"}}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.profile("""{"name":"no id"}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.enrolledAccount("""{"id":"00000000000000aa"}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.claimAnswer("""{"claim":{"id":1,"kind":"sideways"}}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.strapAnswer("""{"bound":false}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.devices("""{}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.invites("""{}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.invite("""{"id":"5fb5698ab598cab7"}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.info("""{"name":"x"}""") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("<html>captive portal</html>") }
        assertThrows(FriendsParseException::class.java) { FriendsJson.feed("[]") }
    }

    /** An error body, as answered: the code, and the server's clock when the refusal is about this phone's. */
    @Test
    fun aRefusalIsReadFromTheServersShapeOnly() {
        assertEquals("strap_bound", FriendsJson.refusal("""{"error":"strap_bound","message":"This strap already has an account."}""").code)
        val skew = FriendsJson.refusal("""{"error":"clock_skew","message":"","serverTime":1791545000}""")
        assertEquals("clock_skew", skew.code)
        assertEquals(1_791_545_000L, skew.serverTime)
        assertNull(FriendsJson.refusal("""{"message":"no code"}""").code)
        assertNull(FriendsJson.refusal("""{"error":42}""").code)
        assertNull(FriendsJson.refusal("<html>502 Bad Gateway</html>").code)
        assertNull(FriendsJson.refusal("").code)
        assertNull(FriendsJson.refusal("""{"error":"not_found","message":""}""").serverTime)
    }
}
