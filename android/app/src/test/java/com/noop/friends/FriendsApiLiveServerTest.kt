package com.noop.friends

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The real client against a real friends server (API version 2), end to end. Skipped unless
 * `FRIENDS_TEST_SERVER` names one, so the ordinary suite never touches a network:
 *
 *     FRIENDS_DB=/tmp/friends-test.db FRIENDS_STRAP_PEPPER=<32 hex characters> FRIENDS_PORT=8787 \
 *         python3 friends-server/server.py &
 *     FRIENDS_TEST_SERVER=http://127.0.0.1:8787 ./gradlew testFullDebugUnitTest --tests "*FriendsApiLiveServerTest"
 *
 * Point it only at a throwaway local server, and never at a public one: it makes accounts, binds straps
 * and deletes an account. The server allows five enrolments an hour from one address and counts them in
 * memory, and this test makes five, so restart the server between runs. The keys are software keys: the
 * Android Keystore is not involved.
 */
class FriendsApiLiveServerTest {
    private val server: String? = System.getenv("FRIENDS_TEST_SERVER")?.takeIf { it.isNotBlank() }

    private fun <T> FriendsResult<T>.ok(): T = when (this) {
        is FriendsResult.Ok -> value
        is FriendsResult.Fail -> throw AssertionError("expected a value, got $error")
    }

    private fun client(signer: FriendsSigner?, transport: FriendsTransport = FriendsTransport(FriendsClockOffset())): FriendsApi =
        FriendsApi.create(server!!, signer, transport)!!

    /** A strap of this run's own, so a second run against the same database does not meet the first one's. */
    private fun strap(tag: String): String = FriendsStrap.handle("whoop-TEST$tag${System.currentTimeMillis()}")

    private fun requireLocalServer() {
        assumeTrue("FRIENDS_TEST_SERVER is not set", server != null)
        // Only ever a loopback server: this test makes and deletes accounts.
        assumeTrue("not a loopback server", server!!.startsWith("http://127.0.0.1") || server.startsWith("http://localhost"))
    }

    @Test
    fun theWholeProtocolAgainstTheRealServer() = runBlocking {
        requireLocalServer()
        val keyA = SoftwareFriendsSigner()
        val keyB = SoftwareFriendsSigner()
        val a = client(keyA)
        val b = client(keyB)
        val strapA = strap("A")
        val strapB = strap("B")

        // --- Before any account ---
        val info = client(null).info().ok()
        assertEquals(2, info.api)
        assertTrue(kotlin.math.abs(info.time - System.currentTimeMillis() / 1000L) < 120)
        assertEquals("a key the server has never seen", FriendsError.UNKNOWN_KEY, a.me().errorOrNull)
        assertEquals(FriendsError.STRAP_FREE, a.fileClaim(strapA).errorOrNull)

        // --- Enrolment: the Kotlin key's encoding and signature are the ones the server takes ---
        val first = a.enroll("Руслан", strapA).ok()
        assertTrue(first.isNew)
        assertTrue(FriendsId.isValid(first.me.id))
        assertEquals("Руслан", first.me.name)
        assertEquals(FriendShare.DEFAULT, first.me.share)
        assertEquals(true, first.me.strapBound)
        assertEquals(keyA.keyId, first.me.device?.id)
        assertNull(first.me.device?.probationUntil)
        assertEquals(first.me, a.me().ok())

        // --- A strap with an account: a second phone asks, and the first one declines ---
        assertEquals(FriendsError.STRAP_BOUND, b.enroll("Denis", strapA).errorOrNull)
        val claim = b.fileClaim(strapA).ok()
        assertEquals(FriendsClaim.Kind.JOIN, claim.kind)
        assertEquals(FriendsClaim.State.PENDING, claim.state)
        assertEquals(6, claim.code.length)
        assertEquals("android", claim.platform)
        assertNotNull(claim.maturesAt)
        assertEquals(claim.code, b.myClaim().ok().code)
        val waiting = a.feed().ok().first.claims.single()
        assertEquals("both phones show the same code", claim.code, waiting.code)
        assertEquals(Unit, a.decline(waiting.id).ok())
        assertEquals(FriendsClaim.State.DECLINED, b.myClaim().ok().state)
        assertEquals(FriendsError.CLAIM_SETTLED, a.approve(waiting.id).errorOrNull)
        assertEquals(FriendsError.CLAIM_DECLINED, b.fileClaim(strapA).errorOrNull)

        // --- So the second phone starts its own account ---
        val second = b.enroll("Denis", null).ok()
        assertTrue(second.isNew)
        assertEquals(false, second.me.strapBound)
        assertNotEquals(first.me.id, second.me.id)
        assertEquals(FriendsError.ALREADY_ENROLLED, b.fileClaim(strapA).errorOrNull)

        // --- Profile ---
        assertEquals("Денис", b.patchMe(name = "Денис").ok().name)
        assertEquals(FriendsError.BAD_NAME, b.patchMe(name = "x".repeat(41)).errorOrNull)
        val everything = FriendShare(scores = true, sleep = true, workouts = true, hr = true)
        assertEquals(everything, b.patchMe(share = everything).ok().share)

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
        assertEquals(Unit, b.putDay(today, FriendsDayPayload.toJson(body)).ok())
        // An empty day and a day with only an empty workout list are accepted too.
        assertEquals(Unit, a.putDay(today, FriendsDayPayload.toJson(FriendsDayBody(workouts = emptyList()))).ok())
        assertEquals(Unit, a.putDay(today, "{}").ok())
        assertEquals(FriendsError.REJECTED, a.putDay(today, "{\"steps\":1}").errorOrNull)

        // --- Friends are made by an invite, at once and both ways ---
        assertEquals("not friends yet: the days are not readable", FriendsError.NO_SUCH_USER, a.userDays(second.me.id).errorOrNull)
        val invite = a.createInvite().ok()
        val code = FriendsInviteCode.normalized(invite.code!!)!!
        assertEquals("the server says a code as it is shown", FriendsInviteCode.display(code), invite.code)
        val listed = a.invites().ok().single()
        assertEquals(invite.id, listed.id)
        assertNull("a listing never repeats a code", listed.code)
        assertEquals(FriendsError.OWN_INVITE, a.redeem(code).errorOrNull)
        // What a friend pastes is read by the same rule the screen uses.
        val pasted = FriendsInviteCode.extract("Add me on reNOOP Friends: ${FriendsInviteCode.pageLink(server!!, code)}\nOr enter the code ${invite.code} on the Friends tab.")
        assertEquals(code, pasted)
        val friend = b.redeem(pasted!!).ok()
        assertEquals(first.me.id, friend.id)
        assertEquals("Руслан", friend.name)
        assertEquals("it works once", FriendsError.NO_SUCH_INVITE, b.redeem(code).errorOrNull)
        assertTrue(a.invites().ok().isEmpty())

        // --- The feed, and the text it is kept as ---
        val (feed, text) = a.feed().ok()
        assertEquals(feed, FriendsJson.feed(text))
        assertTrue(kotlin.math.abs(feed.serverTime - now) < 120)
        assertEquals("Руслан", feed.me.profile.name)
        assertEquals(keyA.keyId, feed.me.profile.device?.id)
        assertTrue(feed.claims.isEmpty())
        assertTrue(feed.unconfirmed.isEmpty())
        val theirs = feed.friends.single()
        assertEquals(second.me.id, theirs.id)
        assertEquals("Денис", theirs.profile.name)
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
        val page = a.userDays(second.me.id).ok()
        assertEquals(body.hr, page.day(today)!!.hr)
        assertTrue(body.hr!!.series.size <= FriendsDayPayload.MAX_HR_POINTS)

        // --- A switch turned off erases its section from the stored day ---
        b.patchMe(share = everything.copy(hr = false, sleep = false)).ok()
        val after = a.userDays(second.me.id).ok()
        assertFalse(after.share.hr)
        assertNull(after.day(today)!!.hr)
        assertNull(after.day(today)!!.sleep)
        assertEquals(body.recovery, after.day(today)!!.recovery)

        // --- Pictures ---
        assertEquals(FriendsError.NO_AVATAR, a.avatar(second.me.id).errorOrNull)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(600) { it.toByte() }
        assertEquals(1, b.putAvatar(jpeg).ok().avatarRev)
        assertArrayEquals(jpeg, a.avatar(second.me.id).ok())
        assertEquals(FriendsError.BAD_IMAGE, b.putAvatar(ByteArray(64)).errorOrNull)
        assertEquals(FriendsError.TOO_LARGE, b.putAvatar(jpeg + ByteArray(FriendsLimits.MAX_AVATAR_BYTES)).errorOrNull)
        assertEquals(Unit, b.deleteAvatar().ok())
        assertEquals(FriendsError.NO_AVATAR, a.avatar(second.me.id).errorOrNull)

        // --- The strap: a free one is bound at once, one that is someone's is asked for ---
        assertEquals(FriendsStrapAnswer.Bound, b.putStrap(strapB).ok())
        assertEquals(true, b.me().ok().strapBound)
        val asked = b.putStrap(strapA).ok() as FriendsStrapAnswer.Claimed
        assertEquals(FriendsClaim.Kind.TAKE, asked.claim.kind)
        val take = a.feed().ok().first.claims.single()
        assertEquals(FriendsClaim.Kind.TAKE, take.kind)
        assertEquals(asked.claim.id, b.feed().ok().first.strapClaim?.id)
        assertEquals(Unit, a.decline(take.id).ok())
        assertEquals(true, a.me().ok().strapBound)

        // --- The account's phones, and what the server stores ---
        val phones = a.devices().ok()
        assertEquals(listOf(keyA.keyId), phones.map { it.id })
        assertTrue(phones.single().current)
        assertEquals("android", phones.single().platform)
        assertEquals(FriendsError.LAST_DEVICE, a.removeDevice(keyA.keyId).errorOrNull)
        assertEquals(FriendsError.NO_SUCH_DEVICE, a.removeDevice(keyB.keyId).errorOrNull)
        val export = a.export().ok()
        assertTrue(export.contains(first.me.id))
        assertTrue(export.contains("Руслан") || export.contains("\\u0420"))
        val laid = FriendsWire.indented(export)
        assertEquals("laid out, with nothing of the answer changed", export.filterNot { it == ' ' || it == '\n' }, laid.filterNot { it == ' ' || it == '\n' })

        // --- A phone whose clock is wrong signs once more by the server's ---
        val offset = FriendsClockOffset()
        val late = client(keyA, FriendsTransport(offset, now = { System.currentTimeMillis() / 1000L - 1_000L }))
        assertEquals(first.me.id, late.me().ok().id)
        assertTrue("the gap is kept for the next request", offset.seconds in 990L..1_010L)
        assertEquals(first.me.id, late.me().ok().id)

        // --- Unfriending cuts both ways ---
        assertEquals(Unit, a.unfriend(second.me.id).ok())
        assertTrue(a.feed().ok().first.friends.isEmpty())
        assertEquals(FriendsError.NO_SUCH_USER, b.userDays(first.me.id).errorOrNull)

        // --- An invite taken back ---
        val unused = a.createInvite().ok()
        assertEquals(Unit, a.revokeInvite(unused.id).ok())
        assertEquals(FriendsError.NO_SUCH_INVITE, b.redeem(FriendsInviteCode.normalized(unused.code!!)!!).errorOrNull)

        // --- Leaving ---
        assertEquals(Unit, b.deleteAccount().ok())
        assertEquals(FriendsError.UNKNOWN_KEY, b.me().errorOrNull)
        assertEquals("the strap is free again", FriendsError.STRAP_FREE, b.fileClaim(strapB).errorOrNull)
    }

    /**
     * The store's own way through: one phone turns Friends on with its strap; a second phone with the
     * same strap is asked whose account it is, asks to join, is confirmed by the first, and is on the same
     * account without having sent its own profile. Both phones here are software keys in one process.
     */
    @Test
    fun aSecondPhoneJoinsTheAccountThroughTheStore() = runBlocking {
        requireLocalServer()
        val handle = FriendsStrap.Identity.Handle(strap("S"))

        class Phone(name: String) {
            val prefs = MemoryFriendsPrefs().apply { put(FriendsStore.ADDRESS_KEY, server!!) }
            val keys = MemoryFriendsKeyStore()
            val source = object : FriendsLocalSource {
                override suspend fun profile() = FriendsLocalProfile(name, null)
                override suspend fun recentDays(share: FriendShare) = emptyList<FriendsLocalDay>()
            }
            val store = FriendsStore(prefs, keys, source, strapIdentity = { handle }, transport = FriendsTransport(FriendsClockOffset()))
        }

        val first = Phone("Anna")
        first.store.turnOn()
        assertEquals(FriendsPhase.On, first.store.phase.value)
        assertNull(first.store.error.value)
        assertEquals("Anna", first.store.me?.name)
        assertEquals(FriendsStore.UploadOutcome.DONE, first.store.uploadRecentDays())
        assertEquals("the upload run had the strap accepted", handle.handle, first.prefs.string(FriendsStore.BOUND_HANDLE_KEY))

        val second = Phone("Other")
        second.store.turnOn()
        assertEquals(FriendsPhase.StrapBound, second.store.phase.value)
        second.store.claimAccount()
        val waiting = second.store.phase.value as FriendsPhase.Waiting

        first.store.refresh()
        val claim = first.store.claims.single()
        assertEquals("the code the new phone shows is the one the old phone is asked about", waiting.claim.code, claim.code)
        assertTrue(first.store.approve(claim))
        assertTrue(first.store.claims.isEmpty())

        second.store.pollClaim()
        assertEquals(FriendsPhase.On, second.store.phase.value)
        assertEquals(first.store.myId, second.store.myId)
        assertEquals("the account's name stands, not the joining phone's", "Anna", second.store.nameFriendsSee("Other"))
        assertNull("a confirmed phone is not on probation", second.store.probationUntil)
        assertEquals(FriendsStore.UploadOutcome.DONE, second.store.uploadRecentDays())
        second.store.refresh()
        assertEquals("Anna", second.store.me?.name)

        assertTrue(first.store.loadDevices())
        assertEquals(setOf(first.store.deviceId, second.store.deviceId), first.store.devices.value?.map { it.id }?.toSet())

        // The first phone removes the second: it goes off by itself and keeps its key.
        assertTrue(first.store.removeDevice(second.store.deviceId!!))
        second.store.refresh()
        assertEquals(FriendsPhase.Off, second.store.phase.value)
        assertEquals(FriendsError.LEFT_ACCOUNT, second.store.error.value)
        assertNotNull(second.store.deviceId)

        assertTrue(first.store.deleteAccount())
        assertEquals(FriendsPhase.Off, first.store.phase.value)
        assertNull(first.store.deviceId)
    }
}
