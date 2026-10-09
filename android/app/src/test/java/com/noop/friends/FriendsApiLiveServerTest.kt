package com.noop.friends

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The real client against a real friends server, end to end. Skipped unless `FRIENDS_TEST_SERVER` names
 * one, so the ordinary suite never touches a network:
 *
 *     FRIENDS_DB=/tmp/friends-test.db python3 friends-server/server.py &
 *     FRIENDS_TEST_SERVER=http://127.0.0.1:8787 ./gradlew testFullDebugUnitTest --tests "*FriendsApiLiveServerTest"
 *
 * Point it only at a throwaway local server: it creates two accounts and deletes one. The server allows
 * five sign-ups an hour from one address, so restart it between runs.
 */
class FriendsApiLiveServerTest {
    private val server: String? = System.getenv("FRIENDS_TEST_SERVER")?.takeIf { it.isNotBlank() }

    private fun <T> FriendsResult<T>.ok(): T = when (this) {
        is FriendsResult.Ok -> value
        is FriendsResult.Fail -> throw AssertionError("expected a value, got $error")
    }

    private fun client(token: String? = null): FriendsApi = FriendsApi.create(server!!, token)!!

    @Test
    fun theWholeProtocolAgainstTheRealServer() = runBlocking {
        assumeTrue("FRIENDS_TEST_SERVER is not set", server != null)
        // Only ever a loopback server: this test signs up and deletes accounts.
        assumeTrue("not a loopback server", server!!.startsWith("http://127.0.0.1") || server.startsWith("http://localhost"))

        val suffix = (System.currentTimeMillis() % 1_000_000).toString()
        val a = "ruslan_$suffix"
        val b = "denis_$suffix"
        val password = "correct horse"
        val anon = client()

        // --- Without a session ---
        val info = anon.info().ok()
        assertEquals(1, info.api)
        assertTrue(anon.nickFree(a).ok())

        val first = anon.register(a, password, null).ok()
        assertEquals(a, first.me.nick)
        assertEquals(a, first.me.name)
        assertEquals(FriendShare.DEFAULT, first.me.share)
        assertFalse(anon.nickFree(a).ok())
        assertEquals(FriendsError.NICK_TAKEN, anon.register(a, password, null).errorOrNull)
        assertEquals(FriendsError.BAD_CREDENTIALS, anon.login(a, "not the password").errorOrNull)
        assertEquals(FriendsError.NOT_SIGNED_IN, anon.me().errorOrNull)
        assertEquals(FriendsError.UNAUTHORIZED, client("not-a-token").me().errorOrNull)

        val second = anon.register(b, password, null).ok()
        val me = client(anon.login(a, password).ok().token)
        val friend = client(second.token)

        // --- Profile ---
        assertEquals("Руслан", me.patchMe(name = "Руслан").ok().name)
        val everything = FriendShare(scores = true, sleep = true, workouts = true, hr = true)
        assertEquals(everything, friend.patchMe(share = everything).ok().share)
        assertEquals(everything, friend.me().ok().share)

        // --- A day built by the app's own builder is a day the server accepts ---
        val now = System.currentTimeMillis() / 1000L
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val inputs = FriendsDayInputs(
            recovery = 80.6,
            strain = 38.604,
            sleepScore = 87.5,
            sleep = FriendsSleepInput(now - 30_000, now - 1_000, 474.4, 18.2, 104.0, 252.4, 99.6, 480.0),
            workouts = listOf(
                FriendsWorkoutInput(now - 900, "Running", 1_860.0, 35.2, 139, 162, 310.0),
                FriendsWorkoutInput(now - 800, "Силовая \"A\"", 600.0),
            ),
            hrSamples = (0 until 5_000).map { FriendHrPoint(now - 5_000 + it, 60 + it % 50) },
            restingBpm = 51,
        )
        val body = FriendsDayPayload.build(inputs, everything, now)
        assertEquals(Unit, friend.putDay(today, FriendsDayPayload.toJson(body)).ok())
        // An empty day and a day with only an empty workout list are accepted too.
        assertEquals(Unit, me.putDay(today, FriendsDayPayload.toJson(FriendsDayBody(workouts = emptyList()))).ok())
        assertEquals(Unit, me.putDay(today, "{}").ok())
        assertEquals(FriendsError.REJECTED, me.putDay(today, "{\"steps\":1}").errorOrNull)

        // --- Friendship ---
        assertEquals(FriendRelation.NONE, me.user(b).ok().relation)
        assertEquals(FriendsError.NO_SUCH_USER, me.user("nobody_$suffix").errorOrNull)
        assertEquals(FriendsError.SELF_REQUEST, me.sendRequest(a).errorOrNull)
        assertEquals(FriendRelation.OUTGOING, me.sendRequest(b).ok().relation)
        assertEquals(listOf(b), me.requests().ok().outgoing.map { it.profile.nick })
        val incoming = friend.requests().ok().incoming.single()
        assertEquals(a, incoming.profile.nick)
        assertTrue(incoming.requestedAt > 0)
        assertEquals(1, friend.feed().ok().first.pendingIncoming)
        // Not friends yet: the days are not readable.
        assertEquals(FriendsError.NO_SUCH_USER, me.userDays(b).errorOrNull)
        assertEquals(FriendRelation.FRIEND, friend.acceptRequest(a).ok().relation)

        // --- The feed, and the text it is kept as ---
        val (feed, text) = me.feed().ok()
        assertEquals(feed, FriendsJson.feed(text))
        assertTrue(kotlin.math.abs(feed.serverTime - now) < 120)
        assertEquals("Руслан", feed.me.profile.name)
        val theirs = feed.friends.single()
        assertEquals(b, theirs.nick)
        assertEquals(everything, theirs.share)
        val day = theirs.day(today)!!
        assertEquals(body.recovery, day.recovery)
        assertEquals(body.strain, day.strain)
        assertEquals(body.sleepScore, day.sleepScore)
        assertEquals(body.sleep, day.sleep)
        assertEquals(body.workouts, day.workouts)
        // The feed carries the latest reading but not the line.
        assertEquals(body.hr!!.copy(series = emptyList()), day.hr)
        assertTrue(day.updatedAt >= now - 5)

        // --- The friend's page carries the line ---
        val page = me.userDays(b).ok()
        assertEquals(body.hr, page.day(today)!!.hr)
        assertTrue(body.hr!!.series.size <= FriendsDayPayload.MAX_HR_POINTS)

        // --- A switch turned off erases its section from the stored day ---
        friend.patchMe(share = everything.copy(hr = false, sleep = false)).ok()
        val after = me.userDays(b).ok()
        assertFalse(after.share.hr)
        assertNull(after.day(today)!!.hr)
        assertNull(after.day(today)!!.sleep)
        assertEquals(body.recovery, after.day(today)!!.recovery)

        // --- Pictures ---
        assertEquals(FriendsError.NO_AVATAR, me.avatar(b).errorOrNull)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(600) { it.toByte() }
        assertEquals(1, friend.putAvatar(jpeg).ok().avatarRev)
        assertArrayEquals(jpeg, me.avatar(b).ok())
        assertEquals(FriendsError.BAD_IMAGE, friend.putAvatar(ByteArray(64)).errorOrNull)
        assertEquals(FriendsError.TOO_LARGE, friend.putAvatar(jpeg + ByteArray(FriendsLimits.MAX_AVATAR_BYTES)).errorOrNull)
        assertEquals(Unit, friend.deleteAvatar().ok())
        assertEquals(FriendsError.NO_AVATAR, me.avatar(b).errorOrNull)

        // --- Password: the old session ends, a wrong current password does not end this one ---
        assertEquals(FriendsError.BAD_CREDENTIALS, friend.changePassword("wrong one!", "another horse").errorOrNull)
        val newToken = friend.changePassword(password, "another horse").ok()
        assertNotEquals(second.token, newToken)
        assertEquals(FriendsError.UNAUTHORIZED, friend.me().errorOrNull)
        val friendAgain = client(newToken)
        assertEquals(b, friendAgain.me().ok().nick)

        // --- Unfriending cuts both ways ---
        assertEquals(Unit, me.unfriend(b).ok())
        assertTrue(me.feed().ok().first.friends.isEmpty())
        assertEquals(FriendsError.NO_SUCH_USER, friendAgain.userDays(a).errorOrNull)

        // --- Withdrawing a request ---
        assertEquals(FriendRelation.OUTGOING, me.sendRequest(b).ok().relation)
        assertEquals(Unit, me.deleteRequest(b).ok())
        assertTrue(friendAgain.requests().ok().incoming.isEmpty())

        // --- Leaving ---
        assertEquals(FriendsError.BAD_CREDENTIALS, friendAgain.deleteAccount("not it at all").errorOrNull)
        assertEquals(Unit, friendAgain.deleteAccount("another horse").ok())
        assertEquals(FriendsError.UNAUTHORIZED, friendAgain.me().errorOrNull)
        assertTrue(anon.nickFree(b).ok())
        assertEquals(Unit, me.logout().ok())
        assertEquals(FriendsError.UNAUTHORIZED, me.me().errorOrNull)
    }
}
