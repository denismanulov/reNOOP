package com.noop.friends

import com.noop.friends.FriendsApiTest.Companion.ANNA
import com.noop.friends.FriendsApiTest.Companion.EMPTY_FEED
import com.noop.friends.FriendsApiTest.Companion.HANDLE
import com.noop.friends.FriendsApiTest.Companion.JPEG
import com.noop.friends.FriendsApiTest.Companion.ME
import com.noop.friends.FriendsApiTest.Companion.ME_SHARING_NOTHING
import com.noop.friends.FriendsApiTest.Companion.ME_SHARING_WORKOUTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * How Friends on this phone moves between its states, and what an upload run sends. The cases of the
 * Swift `FriendsClientTests` that are about the store, one for one: the rule each pins is in its own
 * comment. The server is [FriendsTestServer] on loopback, the key a software one, the preferences a map
 * and the app's data a fake, so nothing here needs a phone and nothing reaches any other host.
 */
class FriendsStoreTest {
    private val server = FriendsTestServer()
    private val folder: File = Files.createTempDirectory("friends-store-test").toFile()

    @After
    fun tearDown() {
        server.close()
        folder.deleteRecursively()
    }

    private val offset = FriendsClockOffset()
    private val transport = FriendsTransport(offset = offset, now = { FriendsApiTest.FIXED_NOW }, nonce = { FriendsApiTest.FIXED_NONCE })

    /** The same server under its other loopback name: another address, so another account and another key. */
    private val otherServer: String get() = server.url.replace("127.0.0.1", "localhost")

    private val today: String = LocalDate.now().toString()

    /** The app's profile and days, as a test sets them. */
    private class Source(var name: String = "Anna", var photo: ByteArray? = null) : FriendsLocalSource {
        /** Whether today has a workout: the one section these tests fill. */
        var hasWorkout = false
        var day: String = LocalDate.now().toString()

        override suspend fun profile() = FriendsLocalProfile(name, photo)

        override suspend fun recentDays(share: FriendShare): List<FriendsLocalDay> {
            val now = System.currentTimeMillis() / 1000L
            val workouts = when {
                !share.workouts -> null
                hasWorkout -> listOf(FriendsWorkoutInput(now - 3_600, "Running", 1_800.0, 35.2, 139, 162, 310.0))
                else -> emptyList()
            }
            return listOf(FriendsLocalDay(day, FriendsDayInputs(workouts = workouts)))
        }
    }

    private class Made(val store: FriendsStore, val prefs: MemoryFriendsPrefs, val keys: MemoryFriendsKeyStore, val source: Source)

    private fun store(
        identity: FriendsStrap.Identity,
        signer: FriendsSigner? = SoftwareFriendsSigner(),
        prefs: MemoryFriendsPrefs = MemoryFriendsPrefs(),
        keys: MemoryFriendsKeyStore = MemoryFriendsKeyStore(),
        invites: FriendsInviteCodes? = null,
        source: Source = Source(),
    ): Made {
        if (prefs.string(FriendsStore.ADDRESS_KEY) == null) prefs.put(FriendsStore.ADDRESS_KEY, server.url)
        val store = FriendsStore(
            prefs = prefs, keys = keys, source = source, strapIdentity = { identity }, transport = transport,
            inviteFile = invites, signer = signer,
        )
        return Made(store, prefs, keys, source)
    }

    /** Friends on, as an enrolment answered [status] leaves it, with the requests so far forgotten. */
    private suspend fun turnedOn(identity: FriendsStrap.Identity, status: Int = 201, name: String = "Anna"): Made {
        val made = store(identity, source = Source(name))
        server.answer("POST /v2/enroll", status to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn(name)
        assertEquals(FriendsPhase.On, made.store.phase.value)
        server.seen.clear()
        return made
    }

    private fun sentDays() = server.seen.filter { it.method == "PUT" && it.path.startsWith("/v2/me/days/") }

    private fun handle(text: String = HANDLE) = FriendsStrap.Identity.Handle(text)

    private val pendingClaim =
        """{"claim":{"id":3,"kind":"join","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}}"""

    // MARK: - Turning on

    @Test
    fun offNothingIsSentAndNoKeyIsMade() = runBlocking {
        val made = store(handle(), signer = null)
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        made.store.refresh()
        made.store.sync(force = true)
        assertEquals(FriendsStore.UploadOutcome.OFF, made.store.uploadRecentDays())
        made.store.person(ANNA)
        made.store.loadInvites()
        assertTrue(server.seen.isEmpty())
        assertNull(made.store.deviceId)
        assertNull(made.keys.kept(server.url))
        assertEquals(0, made.keys.writeCount)
    }

    @Test
    fun turningOnEnrolsWithTheStrapAndGoesOn() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("  Anna ")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals(ANNA, made.store.myId)
        assertNull(made.store.error.value)
        val body = JSONObject(server.seen[0].text)
        assertEquals("Anna", body.getString("name"))
        assertEquals(HANDLE, body.getString("strap"))
        assertEquals("on", made.prefs.string(FriendsStore.PHASE_KEY))
        assertNull("an enrolment records no strap: the upload run's own request does", made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
        assertEquals("the name just sent is not sent again", "Anna", made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
        assertFalse(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
    }

    /**
     * The key was already a phone of an account (a reinstall can keep the Keystore entry): the server
     * answers that account and takes neither the name nor the strap sent. Nothing is recorded as
     * accepted, and the phone joins as any other does, without pushing its profile.
     */
    @Test
    fun anEnrolmentThatFindsAnExistingAccountRecordsNoStrapAndPushesNoProfile() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 200 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Other")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertNull(made.store.error.value)
        assertNull(made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
        assertNull("the name sent was not taken", made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
        assertTrue(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
    }

    /**
     * Turn On runs once. A second tap, or the tab asking again by itself, while the first enrolment is
     * still in flight sends nothing: a second enrolment with the same key would be answered as an
     * account the key already had, and read as joining someone's account.
     */
    @Test
    fun turningOnTwiceAtOnceEnrolsOnce() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""", 200 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        server.delays["POST /v2/enroll"] = 300
        val first = async(Dispatchers.Default) { made.store.turnOn("Anna") }
        val second = async(Dispatchers.Default) { made.store.turnOn("Anna") }
        first.await()
        second.await()
        made.store.settle()
        assertEquals(1, server.sent("POST", "/v2/enroll").size)
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertFalse(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertEquals("Anna", made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
        // On already: one more tap sends nothing either.
        made.store.turnOn("Anna")
        assertEquals(1, server.sent("POST", "/v2/enroll").size)
    }

    /**
     * The page that waits for the strap asks again every few seconds, and so does every appearance of
     * the tab. With the strap read and an enrolment under way, asking again sends nothing.
     */
    @Test
    fun syncWhileAnEnrolmentIsInFlightSendsNoSecondOne() = runBlocking {
        val made = store(FriendsStrap.Identity.Pending)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.WaitingForStrap, made.store.phase.value)
        made.store.strapIdentity = { handle() }
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""", 200 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.delays["POST /v2/enroll"] = 300
        val appeared = async(Dispatchers.Default) { made.store.sync(force = false) }
        val ticked = async(Dispatchers.Default) { made.store.sync(force = false) }
        appeared.await()
        ticked.await()
        made.store.settle()
        assertEquals(1, server.sent("POST", "/v2/enroll").size)
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertFalse(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
    }

    /**
     * An enrolment whose answer was lost on the way made the account all the same. Turning on again is
     * answered with that account (`200`): it is this phone's own, made a moment ago, so the phone does
     * not behave as one that joined somebody's account, and its picture goes up.
     */
    @Test
    fun anEnrolmentWhoseAnswerWasLostIsNotReadAsJoiningAnAccount() = runBlocking {
        val made = store(FriendsStrap.Identity.None, source = Source("Anna", JPEG))
        server.failures += "POST /v2/enroll"
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertEquals(FriendsError.OFFLINE, made.store.error.value)
        assertTrue("no answer leaves the enrolment in doubt", made.prefs.boolean(FriendsStore.ENROL_PENDING_KEY))

        server.failures.clear()
        server.answer("POST /v2/enroll", 200 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertFalse(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertEquals("Anna", made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
        assertFalse(made.prefs.boolean(FriendsStore.ENROL_PENDING_KEY))

        server.seen.clear()
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("PATCH /v2/me", 200 to ME)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        made.store.uploadRecentDays()
        assertEquals(1, server.sent("PUT", "/v2/me/avatar").size)
        assertArrayEquals(JPEG, server.sent("PUT", "/v2/me/avatar").single().body)
        assertTrue("the name went with the enrolment", server.sent("PATCH", "/v2/me").isEmpty())
    }

    /**
     * A refusal is an answer: nothing is in doubt after it, and a later `200` is an account the key
     * already had, joined as it stands.
     */
    @Test
    fun aRefusedEnrolmentLeavesNothingInDoubt() = runBlocking {
        val made = store(FriendsStrap.Identity.None)
        server.answer("POST /v2/enroll", 500 to """{"error":"internal","message":""}""", 200 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertFalse(made.prefs.boolean(FriendsStore.ENROL_PENDING_KEY))
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertTrue(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertNull(made.prefs.string(FriendsStore.PUSHED_NAME_KEY))

        val bound = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        bound.store.turnOn("Anna")
        assertEquals(FriendsPhase.StrapBound, bound.store.phase.value)
        assertFalse(bound.prefs.boolean(FriendsStore.ENROL_PENDING_KEY))
    }

    @Test
    fun withoutANameNothingIsSent() = runBlocking {
        val made = store(handle())
        made.store.turnOn("   ")
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertEquals(FriendsError.NO_NAME, made.store.error.value)
        assertTrue(server.seen.isEmpty())
        assertEquals("no key is made for a tap that could not go on", 0, made.keys.writeCount)
    }

    @Test
    fun aStrapNotReadYetWaitsAndSendsNothing() = runBlocking {
        val made = store(FriendsStrap.Identity.Pending)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.WaitingForStrap, made.store.phase.value)
        assertTrue(server.seen.isEmpty())
        assertEquals("waitingForStrap", made.prefs.string(FriendsStore.PHASE_KEY))
        // The strap is read: the same tap finishes by itself.
        made.store.strapIdentity = { handle() }
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
    }

    /**
     * The page that waits for the strap finishes by itself: a sync while the strap is still unread sends
     * nothing, and the first one after it is read enrols with it, under the profile's name.
     */
    @Test
    fun syncFinishesTurningOnOnceTheStrapIsRead() = runBlocking {
        val made = store(FriendsStrap.Identity.Pending)
        made.store.turnOn()
        assertEquals(FriendsPhase.WaitingForStrap, made.store.phase.value)
        made.store.sync(force = false)
        assertEquals(FriendsPhase.WaitingForStrap, made.store.phase.value)
        assertTrue(server.seen.isEmpty())

        made.store.strapIdentity = { handle() }
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.sync(force = false)
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals("/v2/enroll", server.seen.first().path)
        val body = JSONObject(server.seen.first().text)
        assertEquals(HANDLE, body.getString("strap"))
        assertEquals("Anna", body.getString("name"))
        assertEquals(1, server.sent("POST", "/v2/enroll").size)
    }

    /**
     * "Start a new account" beside a strap that belongs to another account enrols without it: the strap
     * is asked for afterwards, by the upload run.
     */
    @Test
    fun startingANewAccountBesideABoundStrapSendsNoStrap() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.StrapBound, made.store.phase.value)
        made.store.turnOnWithoutStrap("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        val enrolments = server.sent("POST", "/v2/enroll")
        assertEquals(2, enrolments.size)
        assertEquals(HANDLE, JSONObject(enrolments[0].text).getString("strap"))
        assertFalse(JSONObject(enrolments[1].text).has("strap"))
        assertEquals("Anna", JSONObject(enrolments[1].text).getString("name"))
        assertTrue(server.sent("POST", "/v2/claims").isEmpty())
        assertNull(made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
    }

    @Test
    fun aStrapWithAnAccountAsksWhoseItIsAndAClaimSurvivesARelaunch() = runBlocking {
        val signer = SoftwareFriendsSigner()
        val made = store(handle(), signer = signer)
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.StrapBound, made.store.phase.value)
        assertNull("a strap with an account is a question, not a failure", made.store.error.value)

        server.answer("POST /v2/claims", 201 to pendingClaim)
        made.store.claimAccount("Anna")
        val waiting = made.store.phase.value as FriendsPhase.Waiting
        assertEquals("481902", waiting.claim.code)
        assertEquals(1_791_712_800L, waiting.claim.maturesAt)
        val claimBody = JSONObject(server.sent("POST", "/v2/claims").single().text)
        assertEquals(HANDLE, claimBody.getString("strap"))
        assertEquals("android", claimBody.getString("platform"))

        // The state, the claim's code and when silence alone grants it are all there after a relaunch.
        val relaunched = store(handle(), signer = signer, prefs = made.prefs)
        assertEquals(FriendsPhase.Waiting(waiting.claim), relaunched.store.phase.value)

        server.answer("DELETE /v2/claims/mine", 204 to "")
        relaunched.store.cancelClaim()
        assertEquals(FriendsPhase.StrapBound, relaunched.store.phase.value)
        assertNull(relaunched.prefs.string(FriendsStore.CLAIM_KEY))
    }

    /** The pages that wait keep their place across a relaunch too. */
    @Test
    fun theWaitingPagesSurviveARelaunch() = runBlocking {
        val waitingForStrap = store(FriendsStrap.Identity.Pending, signer = null)
        waitingForStrap.store.turnOn("Anna")
        assertEquals(FriendsPhase.WaitingForStrap, store(FriendsStrap.Identity.Pending, signer = null, prefs = waitingForStrap.prefs).store.phase.value)

        val bound = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        bound.store.turnOn("Anna")
        assertEquals(FriendsPhase.StrapBound, store(handle(), prefs = bound.prefs).store.phase.value)
    }

    /** A phone that joins an account does not send its own profile: what the account shows stands. */
    @Test
    fun aGrantedClaimJoinsWithoutPushingTheProfile() = runBlocking {
        val made = store(handle(), source = Source("Other", JPEG))
        fun claim(state: String) =
            """{"claim":{"id":3,"kind":"join","code":"481902","state":"$state","platform":"ios","createdAt":1791540000,"maturesAt":null}}"""
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        server.answer("POST /v2/claims", 201 to claim("pending"))
        made.store.turnOn("Other")
        made.store.claimAccount("Other")
        server.answer("GET /v2/claims/mine", 200 to claim("pending"), 200 to claim("approved"))
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.pollClaim()
        assertTrue("left waiting on a pending claim", made.store.phase.value is FriendsPhase.Waiting)
        made.store.pollClaim()
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertTrue(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertNull(made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
        // The upload run is where a profile would be sent: with a name and a photo of its own, this
        // phone still sends neither, and records them as the ones it last sent.
        server.answer("PUT /v2/me/strap", 200 to """{"bound":true}""")
        server.answer("PATCH /v2/me", 200 to ME)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        made.store.uploadRecentDays()
        assertEquals("the run reached the server", 2, server.sent("GET", "/v2/me").size)
        assertTrue(server.sent("PATCH", "/v2/me").isEmpty())
        assertTrue(server.sent("PUT", "/v2/me/avatar").isEmpty())
        assertFalse(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertEquals("Other", made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
    }

    @Test
    fun aDeclinedClaimGoesBackToTheChoice() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        server.answer("POST /v2/claims", 201 to """{"claim":{"id":3,"kind":"join","code":"481902","state":"pending","createdAt":1}}""")
        server.answer("GET /v2/claims/mine", 200 to """{"claim":{"id":3,"kind":"join","code":"481902","state":"declined","createdAt":1}}""")
        made.store.turnOn("Anna")
        made.store.claimAccount("Anna")
        made.store.pollClaim()
        assertEquals(FriendsPhase.StrapBound, made.store.phase.value)
        assertEquals(FriendsError.CLAIM_WAS_DECLINED, made.store.error.value)
    }

    /** A claim that expired unanswered says so, and one the server no longer holds is read as granted. */
    @Test
    fun anExpiredClaimSaysSoAndAClaimThatIsGoneIsAskedAboutAsGranted() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        server.answer("POST /v2/claims", 201 to pendingClaim)
        server.answer("GET /v2/claims/mine", 200 to """{"claim":{"id":3,"kind":"join","code":"481902","state":"expired","createdAt":1}}""")
        made.store.turnOn("Anna")
        made.store.claimAccount("Anna")
        made.store.pollClaim()
        assertEquals(FriendsPhase.StrapBound, made.store.phase.value)
        assertEquals(FriendsError.CLAIM_EXPIRED, made.store.error.value)

        // Asked again; this time the claim is gone, and so is the key from the server: back to the choice.
        made.store.claimAccount("Anna")
        assertTrue(made.store.phase.value is FriendsPhase.Waiting)
        server.answer("GET /v2/claims/mine", 401 to """{"error":"unknown_key","message":""}""")
        server.answer("GET /v2/me", 401 to """{"error":"unknown_key","message":""}""")
        made.store.pollClaim()
        assertEquals(FriendsPhase.StrapBound, made.store.phase.value)

        // No answer is not an answer: the page keeps showing the code.
        made.store.claimAccount("Anna")
        val waiting = made.store.phase.value
        server.failures += "GET /v2/claims/mine"
        made.store.setError(null)
        made.store.pollClaim()
        assertEquals(waiting, made.store.phase.value)
        assertNull(made.store.error.value)
    }

    // MARK: - Answers

    /**
     * An answer reports whether the call itself went through, apart from what the refresh after it did:
     * a refusal is a failure with a reason, and an accepted answer stays one when the feed then fails.
     */
    @Test
    fun anAnswerReportsItsOwnOutcomeNotTheRefreshAfterIt() = runBlocking {
        val made = turnedOn(handle())
        val claim = FriendsClaim(3, FriendsClaim.Kind.JOIN, "481902", FriendsClaim.State.PENDING, "ios", 1_791_540_000L, null)
        server.answer("POST /v2/claims/3/approve", 409 to """{"error":"claim_settled","message":"That request is no longer waiting"}""")
        assertFalse(made.store.approve(claim))
        assertEquals(FriendsError.CLAIM_SETTLED, made.store.error.value)

        made.store.setError(null)
        server.answer("POST /v2/claims/3/approve", 204 to "")
        server.answer("GET /v2/feed?days=7", 500 to """{"error":"internal","message":""}""")
        assertTrue("the answer went through, though the feed after it did not", made.store.approve(claim))
        assertNotNull("the failed refresh is still reported, by the tab", made.store.error.value)

        server.answer("POST /v2/claims/3/decline", 204 to "")
        assertTrue(made.store.decline(claim))
        assertEquals(1, server.sent("POST", "/v2/claims/3/decline").size)
    }

    /**
     * A revoke reports whether the call itself went through, apart from the reload after it: a refusal
     * is a failure with a reason, an accepted revoke is a success.
     */
    @Test
    fun aRevokeReportsWhetherTheServerTookIt() = runBlocking {
        val made = turnedOn(handle())
        server.answer("GET /v2/invites", 200 to """{"invites":[]}""")
        server.answer("DELETE /v2/invites/0123456789abcdef", 404 to """{"error":"not_found","message":"That invite is gone"}""")
        assertFalse(made.store.revokeInvite("0123456789abcdef"))
        assertNotNull(made.store.error.value)

        made.store.setError(null)
        server.answer("DELETE /v2/invites/0123456789abcdef", 204 to "")
        assertTrue(made.store.revokeInvite("0123456789abcdef"))
    }

    /**
     * A request cancelled because its page was left is not a server failure; any other failure of the
     * connection still is one.
     */
    @Test
    fun aCancelledRequestIsNotReportedAsAServerFailure() = runBlocking {
        val made = turnedOn(handle())
        val phase = made.store.phase.value
        made.store.setError(null)

        server.answer("GET /v2/me/devices", 200 to """{"devices":[]}""")
        server.delays["GET /v2/me/devices"] = 600
        val page = launch(Dispatchers.Default) { made.store.loadDevices() }
        while (server.sent("GET", "/v2/me/devices").isEmpty()) delay(10)
        page.cancelAndJoin()
        assertNull(made.store.error.value)
        assertEquals(phase, made.store.phase.value)
        assertNull("what was asked for by a page that left is not kept", made.store.devices.value)

        server.delays.clear()
        server.failures += "GET /v2/me/devices"
        assertFalse(made.store.loadDevices())
        assertEquals(FriendsError.OFFLINE, made.store.error.value)
        assertEquals(phase, made.store.phase.value)
    }

    /** The phones list says whether it was loaded, so a page can tell a load that failed from one under way. */
    @Test
    fun loadingThePhonesReportsWhetherTheListWasLoaded() = runBlocking {
        val made = turnedOn(handle())
        server.answer("GET /v2/me/devices", 500 to """{"error":"internal","message":""}""")
        assertFalse(made.store.loadDevices())
        assertNotNull(made.store.error.value)
        assertNull(made.store.devices.value)

        made.store.setError(null)
        server.answer("GET /v2/me/devices", 200 to """{"devices":[{"id":"k","platform":"ios","addedAt":1,"lastSeenAt":2,"probationUntil":null,"current":true}]}""")
        assertTrue(made.store.loadDevices())
        assertEquals(1, made.store.devices.value?.size)
        assertNull(made.store.error.value)
    }

    /** Keeping or removing a phone says whether the server took it, and the list is asked for again after. */
    @Test
    fun keepingAndRemovingAPhoneReportWhetherTheServerTookIt() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        val other = SoftwareFriendsSigner().keyId
        server.answer("GET /v2/me/devices", 200 to """{"devices":[]}""")
        server.answer("POST /v2/me/devices/$other/trust", 204 to "")
        server.answer("DELETE /v2/me/devices/$other", 403 to """{"error":"probation","message":""}""")
        assertTrue(made.store.trustDevice(other))
        assertFalse(made.store.removeDevice(other))
        assertEquals(FriendsError.PROBATION, made.store.error.value)
        assertEquals(2, server.sent("GET", "/v2/me/devices").size)
        assertEquals(FriendsPhase.On, made.store.phase.value)
    }

    // MARK: - Leaving

    /**
     * A phone removed from the account by another one goes off, and keeps its key so that turning
     * Friends on again asks to rejoin as the same phone.
     */
    @Test
    fun aPhoneTheServerNoLongerKnowsGoesOffAndKeepsItsKey() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED, 401 to """{"error":"unknown_key","message":""}""")
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        // An upload run has the strap accepted, so there is something on record to forget.
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("PUT /v2/me/strap", 200 to """{"bound":true}""")
        made.store.uploadRecentDays()
        assertEquals(HANDLE, made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
        val epoch = made.store.epoch
        made.store.refresh()
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertEquals(FriendsError.LEFT_ACCOUNT, made.store.error.value)
        assertNotNull(made.store.deviceId)
        assertNull(made.store.feed.value)
        assertTrue(made.store.epoch > epoch)
        assertNull(made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
        assertEquals("off", made.prefs.string(FriendsStore.PHASE_KEY))
    }

    @Test
    fun deletingTheAccountForgetsTheKey() = runBlocking {
        val made = store(FriendsStrap.Identity.None, signer = null)
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        server.answer("POST /v2/me/delete", 204 to "")
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertNotNull("the key is made by turning on", made.keys.kept(server.url))
        assertFalse("a device with no serial enrols unbound", JSONObject(server.seen[0].text).has("strap"))
        assertTrue(made.store.deleteAccount())
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertNull(made.store.deviceId)
        assertNull(made.keys.kept(server.url))
    }

    /**
     * Removing this phone from the account deletes its key, since the account lives on elsewhere.
     * Refused for the account's only confirmed phone, nothing changes: Friends stays on, with its key.
     */
    @Test
    fun removingThisPhoneDeletesItsKeyAndARefusalKeepsIt() = runBlocking {
        val made = store(FriendsStrap.Identity.None, signer = null)
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        val id = made.store.deviceId!!

        server.answer("DELETE /v2/me/devices/$id", 409 to """{"error":"last_device","message":""}""")
        assertFalse(made.store.removeThisPhone())
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals(id, made.store.deviceId)
        assertNotNull("a refusal keeps the key", made.keys.kept(server.url))
        assertEquals(FriendsError.LAST_DEVICE, made.store.error.value)

        server.answer("DELETE /v2/me/devices/$id", 204 to "")
        assertTrue(made.store.removeThisPhone())
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertNull(made.store.deviceId)
        assertNull("the phone left by its own doing: its key goes", made.keys.kept(server.url))
        assertEquals(2, server.sent("DELETE", "/v2/me/devices/$id").size)
    }

    // MARK: - The kept key

    /**
     * A store whose key is kept and whose first read of it fails, as when the key store cannot be
     * reached at launch. [phase] is what was kept between launches.
     */
    private fun storeThatCouldNotReadItsKey(phase: String?, identity: FriendsStrap.Identity = FriendsStrap.Identity.None): Pair<Made, FriendsSigner> {
        val keys = MemoryFriendsKeyStore()
        val kept = keys.create(server.url)
        val prefs = MemoryFriendsPrefs()
        if (phase != null) prefs.put(FriendsStore.PHASE_KEY, phase)
        keys.failsReads = true
        return store(identity, signer = null, prefs = prefs, keys = keys) to kept
    }

    /**
     * Friends looks off while the key cannot be read, with the kept phase left alone, and comes back by
     * itself once it can: the same key, nothing made in its place.
     */
    @Test
    fun aKeyThatCouldNotBeReadComesBackWithItsPhase() = runBlocking {
        val (made, kept) = storeThatCouldNotReadItsKey("on")
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertNull(made.store.deviceId)
        made.store.sync(force = false)
        assertEquals("still unreadable: nothing changes", FriendsPhase.Off, made.store.phase.value)
        assertEquals("the kept phase is not written over", "on", made.prefs.string(FriendsStore.PHASE_KEY))
        assertTrue(server.seen.isEmpty())

        made.keys.failsReads = false
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.sync(force = false)
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals(kept.keyId, made.store.deviceId)
        assertEquals("the one key made: nothing replaced it", 1, made.keys.writeCount)
        assertEquals(kept.keyId, server.seen.first().header("X-Friends-Key"))
        assertFalse(server.seen.any { it.path == "/v2/enroll" })
    }

    /** A claim in flight comes back the same way, with its code. */
    @Test
    fun aKeyThatCouldNotBeReadComesBackWithItsClaim() = runBlocking {
        val (made, kept) = storeThatCouldNotReadItsKey("waiting")
        val claim = FriendsClaim(3, FriendsClaim.Kind.JOIN, "481902", FriendsClaim.State.PENDING, "ios", 1_791_540_000L, 1_791_712_800L)
        made.prefs.put(FriendsStore.CLAIM_KEY, FriendsJson.claimText(claim))
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        made.keys.failsReads = false
        made.store.refresh()
        assertEquals(FriendsPhase.Waiting(claim), made.store.phase.value)
        assertEquals(kept.keyId, made.store.deviceId)
        assertEquals(1, made.keys.writeCount)
    }

    /**
     * Turn On is the one button the page offers while Friends looks off. With the key out of reach it
     * makes nothing, sends nothing and leaves the phase alone, whatever the strap says.
     */
    @Test
    fun turningOnNeverReplacesAKeyThatCannotBeRead() = runBlocking {
        for (identity in listOf(FriendsStrap.Identity.Pending, handle(), FriendsStrap.Identity.None)) {
            val (made, _) = storeThatCouldNotReadItsKey("on", identity)
            made.store.turnOn("Anna")
            assertEquals("nothing was made over the kept key", 1, made.keys.writeCount)
            assertTrue(server.seen.isEmpty())
            assertEquals(FriendsError.KEY_NOT_READ, made.store.error.value)
            assertEquals(FriendsPhase.Off, made.store.phase.value)
            assertEquals("on", made.prefs.string(FriendsStore.PHASE_KEY))
            made.store.setError(null)
            made.store.turnOnWithoutStrap("Anna")
            assertEquals(1, made.keys.writeCount)
            assertTrue(server.seen.isEmpty())
            assertNotNull(made.store.error.value)
            assertEquals("on", made.prefs.string(FriendsStore.PHASE_KEY))
            made.store.setError(null)
            made.store.claimAccount("Anna")
            assertEquals(1, made.keys.writeCount)
            assertTrue(server.seen.isEmpty())
        }
    }

    /**
     * The key is read again before one is made. A tap on Turn On once it is readable finds Friends as it
     * was, and a phone that was not on enrols with the key it kept.
     */
    @Test
    fun turningOnReadsTheKeyAgainBeforeMakingOne() = runBlocking {
        val (made, kept) = storeThatCouldNotReadItsKey("on")
        made.keys.failsReads = false
        made.store.turnOn("Anna")
        assertEquals("Friends was on all along", FriendsPhase.On, made.store.phase.value)
        assertEquals(kept.keyId, made.store.deviceId)
        assertEquals(1, made.keys.writeCount)
        assertFalse(server.seen.any { it.path == "/v2/enroll" })

        server.seen.clear()
        val (fresh, freshKept) = storeThatCouldNotReadItsKey(null)
        fresh.keys.failsReads = false
        server.answer("POST /v2/enroll", 200 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        fresh.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, fresh.store.phase.value)
        assertEquals("the kept key was used, not replaced", 1, fresh.keys.writeCount)
        assertEquals("/v2/enroll", server.seen.first().path)
        assertEquals(freshKept.keyId, server.seen.first().header("X-Friends-Key"))
    }

    /** With nothing kept, Turn On makes the key and enrols with it. */
    @Test
    fun turningOnWithNoKeyKeptMakesOneAndEnrols() = runBlocking {
        val made = store(FriendsStrap.Identity.None, signer = null)
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals(1, made.keys.writeCount)
        assertEquals("/v2/enroll", server.seen.first().path)
        assertEquals(made.store.deviceId, server.seen.first().header("X-Friends-Key"))
        assertEquals(made.store.deviceId, made.keys.kept(server.url)?.keyId)
    }

    /** A key that could not be kept is not used: nothing is sent with a key a relaunch would not find. */
    @Test
    fun aKeyThatCouldNotBeKeptSendsNothing() = runBlocking {
        val made = store(FriendsStrap.Identity.None, signer = null)
        made.keys.refusesWrites = true
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertEquals(FriendsError.KEY_NOT_KEPT, made.store.error.value)
        assertTrue(server.seen.isEmpty())
        assertFalse(made.prefs.boolean(FriendsStore.ENROL_PENDING_KEY))
    }

    // MARK: - The upload run

    /**
     * A phone with nothing to show for a day does not send that day: another phone of the account may
     * have uploaded it, and an upload replaces the day. What this phone did send it still replaces when
     * the day has since become empty, once.
     */
    @Test
    fun aPhoneWithNothingToShowSendsNoDayAndReplacesOnlyWhatItSent() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("GET /v2/me", 200 to ME_SHARING_WORKOUTS)
        assertEquals(FriendsStore.UploadOutcome.DONE, made.store.uploadRecentDays())
        assertEquals("the run reached the server", 1, server.sent("GET", "/v2/me").size)
        assertTrue("a freshly joined phone with nothing to show sends no day", sentDays().isEmpty())

        made.source.hasWorkout = true
        server.answer("PUT /v2/me/days/$today", 204 to "")
        made.store.uploadRecentDays()
        assertEquals(listOf("/v2/me/days/$today"), sentDays().map { it.path })
        assertNotNull(made.store.uploadMark(today))
        assertTrue(sentDays().single().text.contains(""""sport":"Running""""))
        assertNotNull(made.store.lastUploadAt.value)

        // The workout is gone from this phone: the day it sent is replaced by an empty one, once.
        made.source.hasWorkout = false
        made.store.uploadRecentDays()
        assertEquals(2, sentDays().size)
        assertEquals("""{"workouts":[]}""", sentDays().last().text)
        made.store.uploadRecentDays()
        assertEquals(2, sentDays().size)
    }

    /**
     * The strap worn goes to the server when it is not the one the server last accepted. Accepted, it is
     * recorded and not sent again; asked of the account that holds it, it is asked again every run.
     */
    @Test
    fun theUploadRunBindsTheStrapWornAndAsksAgainForOneThatIsSomeoneElses() = runBlocking {
        val made = turnedOn(handle())
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("PUT /v2/me/strap", 200 to """{"bound":true}""")
        made.store.uploadRecentDays()
        assertEquals(listOf("""{"strap":"$HANDLE"}"""), server.sent("PUT", "/v2/me/strap").map { it.text })
        assertEquals(HANDLE, made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
        made.store.uploadRecentDays()
        assertEquals("an accepted strap is not sent again", 1, server.sent("PUT", "/v2/me/strap").size)

        // Another strap is worn, and it belongs to another account.
        val other = "ab".repeat(32)
        made.store.strapIdentity = { handle(other) }
        server.answer(
            "PUT /v2/me/strap",
            202 to """{"claim":{"id":7,"kind":"take","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}}""",
        )
        made.store.uploadRecentDays()
        assertEquals(2, server.sent("PUT", "/v2/me/strap").size)
        assertEquals("asked for is not accepted", HANDLE, made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
        made.store.uploadRecentDays()
        assertEquals("asked again until it is settled", 3, server.sent("PUT", "/v2/me/strap").size)
        assertEquals("""{"strap":"$other"}""", server.sent("PUT", "/v2/me/strap").last().text)

        // A strap not read, or a device with none, sends nothing about a strap.
        made.store.strapIdentity = { FriendsStrap.Identity.Pending }
        made.store.uploadRecentDays()
        made.store.strapIdentity = { FriendsStrap.Identity.None }
        made.store.uploadRecentDays()
        assertEquals(3, server.sent("PUT", "/v2/me/strap").size)
    }

    /**
     * A phone that joined an account records its own name and photo as sent without sending them, once.
     * After that it sends what the wearer changes on this phone, once per change.
     */
    @Test
    fun aPhoneThatJoinedSendsItsProfileOnlyAfterAChangeMadeHere() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None, status = 200, name = "Other")
        made.source.photo = JPEG
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("PATCH /v2/me", 200 to ME)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        made.store.uploadRecentDays()
        assertEquals("the run reached the server", 1, server.sent("GET", "/v2/me").size)
        assertTrue(server.sent("PATCH", "/v2/me").isEmpty())
        assertTrue(server.sent("PUT", "/v2/me/avatar").isEmpty())
        assertFalse("joining is recorded once", made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        made.store.uploadRecentDays()
        assertTrue(server.sent("PATCH", "/v2/me").isEmpty())
        assertTrue(server.sent("PUT", "/v2/me/avatar").isEmpty())

        made.source.name = "Renamed"
        made.store.uploadRecentDays()
        assertEquals(listOf("""{"name":"Renamed"}"""), server.sent("PATCH", "/v2/me").map { it.text })
        made.store.uploadRecentDays()
        assertEquals("a name already sent is not sent again", 1, server.sent("PATCH", "/v2/me").size)
        assertTrue("the photo did not change", server.sent("PUT", "/v2/me/avatar").isEmpty())
    }

    /** A phone that made its own account sends the name and photo of its profile, once each. */
    @Test
    fun aPhoneThatMadeTheAccountSendsItsPhotoAndARenameOnce() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        made.source.photo = JPEG
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("PATCH /v2/me", 200 to ME)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        made.store.uploadRecentDays()
        made.store.uploadRecentDays()
        assertTrue("the name went with the enrolment", server.sent("PATCH", "/v2/me").isEmpty())
        assertEquals(1, server.sent("PUT", "/v2/me/avatar").size)
        assertArrayEquals(JPEG, server.sent("PUT", "/v2/me/avatar").single().body)
        assertEquals(FriendsKeys.sha256Hex(JPEG), made.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))
        made.source.name = "Only In This Test"
        made.store.uploadRecentDays()
        assertEquals(listOf("""{"name":"Only In This Test"}"""), server.sent("PATCH", "/v2/me").map { it.text })
    }

    /**
     * A phone that joined without confirmation uploads its days and nothing else: neither the strap nor
     * the profile goes until it is confirmed.
     */
    @Test
    fun aPhoneOnProbationSendsNeitherItsStrapNorItsProfile() = runBlocking {
        val made = turnedOn(handle())
        made.source.name = "Renamed"
        made.source.photo = JPEG
        made.source.hasWorkout = true
        val onProbation = ME_SHARING_WORKOUTS.replace(""""probationUntil":null""", """"probationUntil":1792104800""")
        assertTrue(onProbation != ME_SHARING_WORKOUTS)
        server.answer("GET /v2/me", 200 to onProbation)
        server.answer("PUT /v2/me/strap", 200 to """{"bound":true}""")
        server.answer("PATCH /v2/me", 200 to ME)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        server.answer("PUT /v2/me/days/$today", 204 to "")
        made.store.uploadRecentDays()
        assertTrue(server.sent("PUT", "/v2/me/strap").isEmpty())
        assertTrue(server.sent("PATCH", "/v2/me").isEmpty())
        assertTrue(server.sent("PUT", "/v2/me/avatar").isEmpty())
        assertEquals("its days still go up", listOf("/v2/me/days/$today"), sentDays().map { it.path })
        assertTrue(sentDays().first().text.contains(""""sport":"Running""""))

        // Confirmed: the same run now sends all three.
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        made.store.uploadRecentDays()
        assertEquals(1, server.sent("PUT", "/v2/me/strap").size)
        assertEquals(1, server.sent("PATCH", "/v2/me").size)
        assertEquals(1, server.sent("PUT", "/v2/me/avatar").size)
    }

    /** Switching Photo off removes the server's copy of a picture this phone sent, once. */
    @Test
    fun switchingThePhotoOffDeletesTheServersCopy() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        made.source.photo = JPEG
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        server.answer("DELETE /v2/me/avatar", 204 to "")
        made.store.uploadRecentDays()
        assertEquals(1, server.sent("PUT", "/v2/me/avatar").size)
        assertEquals(FriendsKeys.sha256Hex(JPEG), made.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))
        assertTrue(server.sent("DELETE", "/v2/me/avatar").isEmpty())

        made.store.setSharePhoto(false)
        assertEquals(1, server.sent("DELETE", "/v2/me/avatar").size)
        assertEquals("off", made.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))
        made.store.uploadRecentDays()
        assertEquals("a copy already removed is not removed again", 1, server.sent("DELETE", "/v2/me/avatar").size)
        assertEquals(1, server.sent("PUT", "/v2/me/avatar").size)

        // Switched on again, the picture goes up once more.
        made.store.setSharePhoto(true)
        assertEquals(2, server.sent("PUT", "/v2/me/avatar").size)
    }

    /**
     * The account's picture may be one this phone never sent: another phone's, or this phone's own from
     * before a reinstall. Switching Photo off removes it all the same, once.
     */
    @Test
    fun switchingThePhotoOffRemovesAPictureThisPhoneDidNotSend() = runBlocking {
        val withPicture = ME_SHARING_NOTHING.replace(""""avatarRev":0""", """"avatarRev":3""")
        assertTrue(withPicture != ME_SHARING_NOTHING)

        // Photo is switched off before this phone has made any run of its own.
        val made = turnedOn(FriendsStrap.Identity.None, status = 200)
        server.answer("GET /v2/me", 200 to withPicture)
        server.answer("DELETE /v2/me/avatar", 204 to "")
        made.store.setSharePhoto(false)
        assertEquals(1, server.sent("DELETE", "/v2/me/avatar").size)
        assertEquals("off", made.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))
        assertFalse(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertTrue("the phone joined: its name is not sent", server.sent("PATCH", "/v2/me").isEmpty())
        made.store.uploadRecentDays()
        assertEquals("a copy already removed is not removed again", 1, server.sent("DELETE", "/v2/me/avatar").size)

        // The same after a run that recorded this phone as having no picture to send.
        val later = turnedOn(FriendsStrap.Identity.None, status = 200)
        server.answer("GET /v2/me", 200 to withPicture)
        later.store.uploadRecentDays()
        assertEquals("none", later.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))
        assertTrue("joining removes nothing", server.sent("DELETE", "/v2/me/avatar").isEmpty())
        later.store.setSharePhoto(false)
        assertEquals(1, server.sent("DELETE", "/v2/me/avatar").size)
        assertEquals("off", later.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))
        later.store.uploadRecentDays()
        assertEquals(1, server.sent("DELETE", "/v2/me/avatar").size)
    }

    /** An account with no picture has nothing to remove. */
    @Test
    fun switchingThePhotoOffSendsNothingWhenTheAccountHasNoPicture() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None, status = 200)
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        made.store.setSharePhoto(false)
        assertTrue(server.sent("DELETE", "/v2/me/avatar").isEmpty())
        assertEquals("off", made.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))
    }

    /**
     * Photo off is the wearer's choice about their picture, not a fact about one account: it stands when
     * the phone drops off the account and joins again, when another server is named, and after a relaunch.
     */
    @Test
    fun photoOffOutlivesTheAccountItWasSetOn() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        assertTrue("on until the wearer says otherwise", made.store.sharePhoto.value)
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        made.store.setSharePhoto(false)
        assertFalse(made.store.sharePhoto.value)

        server.answer("GET /v2/feed?days=7", 401 to """{"error":"unknown_key","message":""}""")
        made.store.refresh()
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertFalse(made.store.sharePhoto.value)
        assertEquals(FriendsStore.ServerChange.Done, made.store.setServerAddress(otherServer))
        assertFalse(made.store.sharePhoto.value)
        assertEquals(FriendsStore.ServerChange.Done, made.store.setServerAddress(server.url))

        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertFalse(made.store.sharePhoto.value)
        assertFalse(store(FriendsStrap.Identity.None, prefs = made.prefs).store.sharePhoto.value)
    }

    /**
     * An upload whose answer was lost may have reached the server, and leaves no accepted text on record.
     * When that day then has nothing to show, the empty day still goes up: the server would otherwise go
     * on showing friends what the wearer has removed.
     */
    @Test
    fun anEmptyDayGoesUpAfterAnUploadWhoseAnswerWasLost() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        made.source.hasWorkout = true
        server.answer("GET /v2/me", 200 to ME_SHARING_WORKOUTS)
        server.failures += "PUT /v2/me/days/$today"
        assertEquals(FriendsStore.UploadOutcome.OFFLINE, made.store.uploadRecentDays())
        assertEquals("the day was sent", listOf("/v2/me/days/$today"), sentDays().map { it.path })
        assertNull("and no answer came", made.store.uploadMark(today))

        // The workout is gone from this phone, and the server answers again.
        server.failures.clear()
        server.answer("PUT /v2/me/days/$today", 204 to "")
        made.source.hasWorkout = false
        made.store.uploadRecentDays()
        assertEquals(2, sentDays().size)
        assertEquals("""{"workouts":[]}""", sentDays().last().text)
        made.store.uploadRecentDays()
        assertEquals("accepted, so not sent again", 2, sentDays().size)
    }

    /**
     * Changing a sharing switch drops the accepted texts, so every day goes up again. A day this phone
     * sent before and that now has nothing to show goes up empty all the same.
     */
    @Test
    fun anEmptyDayGoesUpAfterTheSharingSwitchesChanged() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        made.source.hasWorkout = true
        server.answer("GET /v2/me", 200 to ME_SHARING_WORKOUTS)
        server.answer("PUT /v2/me/days/$today", 204 to "")
        made.store.uploadRecentDays()
        assertNotNull(made.store.uploadMark(today))

        server.seen.clear()
        made.source.hasWorkout = false
        server.answer("PATCH /v2/me", 200 to ME_SHARING_WORKOUTS)
        assertTrue(made.store.setShare(made.store.share.copy(scores = !made.store.share.scores)))
        assertEquals(1, server.sent("PATCH", "/v2/me").size)
        assertEquals(listOf("""{"workouts":[]}"""), sentDays().map { it.text })

        // Friends going off forgets which days were put, with everything else about the account.
        server.answer("GET /v2/feed?days=7", 401 to """{"error":"unknown_key","message":""}""")
        made.store.refresh()
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertFalse(made.prefs.keys().any { it.startsWith(FriendsStore.PUT_PREFIX) || it.startsWith(FriendsStore.MARK_PREFIX) })
    }

    /** A switch the server refused to change changes nothing here, and says why. */
    @Test
    fun aSharingSwitchTheServerRefusedSendsNoDayAndSaysWhy() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("PATCH /v2/me", 403 to """{"error":"probation","message":""}""")
        assertFalse(made.store.setShare(made.store.share.copy(hr = true)))
        assertEquals(FriendsError.PROBATION, made.store.error.value)
        assertTrue(server.sent("GET", "/v2/me").isEmpty())
        // The switches already as asked for send nothing at all.
        server.seen.clear()
        assertTrue(made.store.setShare(made.store.share))
        assertTrue(server.seen.isEmpty())
    }

    /**
     * What the server stores is shown as it came: laid out to be read, with every figure the one the
     * server wrote and the members in the server's order.
     */
    @Test
    fun whatTheServerStoresIsShownWithItsOwnFigures() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        val wire = """{"me":{"name":"Anna"},"days":[{"day":"2026-10-10","strain":38.6,"workouts":[{"strain":35.2,"kcal":12.0}]}],"e":1e-7}"""
        server.answer("GET /v2/me/export", 200 to wire)
        val text = made.store.exportText()!!
        assertTrue(text, text.contains(""""strain": 38.6,"""))
        assertFalse(text, text.contains("38.60"))
        assertTrue(text.contains(""""strain": 35.2,"""))
        assertTrue(text.contains(""""kcal": 12.0"""))
        assertTrue(text.contains(""""e": 1e-7"""))
        assertTrue("one member to a line", text.split("\n").size > 10)
        assertEquals("nothing but layout was added", wire, text.filterNot { it.isWhitespace() })
        assertTrue("the server's order, not the alphabet's", text.startsWith("{\n  \"me\": {"))

        server.answer("GET /v2/me/export", 500 to """{"error":"internal","message":""}""")
        assertNull(made.store.exportText())
        assertEquals(FriendsError.SERVER_ERROR, made.store.error.value)
    }

    /**
     * The name on the wearer's own page is the account's, as friends see it. On a phone that joined it
     * can differ from this phone's profile, which stands in only before any answer.
     */
    @Test
    fun theNameFriendsSeeIsTheAccountsNotThisPhonesProfile() = runBlocking {
        assertEquals("Other", store(FriendsStrap.Identity.None).store.nameFriendsSee("Other"))
        val made = turnedOn(FriendsStrap.Identity.None, status = 200, name = "Other")
        assertEquals("Anna", made.store.me?.name)
        assertEquals("Anna", made.store.nameFriendsSee("Other"))
    }

    // MARK: - A pull to refresh

    private val feedWithAFriend =
        """{"serverTime":1791540000,"me":$ME,"friends":[{"id":"00000000000000bb","name":"Max","avatarRev":0}],"claims":[],"strapClaim":null}"""

    /**
     * A pull runs in a coroutine the screen owns, and the screen may cancel it while the server is still
     * answering. What the server answers is shown all the same: a pull that ended with the tab unchanged
     * and nothing said would leave a new friend out of sight until the next one.
     */
    @Test
    fun aPullWhoseTaskIsCancelledStillShowsWhatTheServerAnswered() = runBlocking {
        // Cancelled while the feed is on its way back.
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        server.answer("GET /v2/feed?days=7", 200 to feedWithAFriend)
        server.delays["GET /v2/feed?days=7"] = 400
        val pull = launch(Dispatchers.Default) { made.store.sync(force = true) }
        while (server.sent("GET", "/v2/feed?days=7").isEmpty()) delay(10)
        pull.cancelAndJoin()
        made.store.settle()
        assertEquals(listOf("Max"), made.store.feed.value?.friends?.map { it.profile.name })
        assertNull(made.store.error.value)

        // Cancelled earlier, while the upload run before it is still asking about the account.
        server.delays.clear()
        val early = turnedOn(FriendsStrap.Identity.None)
        server.answer("GET /v2/feed?days=7", 200 to feedWithAFriend)
        server.delays["GET /v2/me"] = 400
        val earlyPull = launch(Dispatchers.Default) { early.store.sync(force = true) }
        while (server.sent("GET", "/v2/me").isEmpty()) delay(10)
        earlyPull.cancelAndJoin()
        early.store.settle()
        assertEquals(1, server.sent("GET", "/v2/feed?days=7").size)
        assertEquals(listOf("Max"), early.store.feed.value?.friends?.map { it.profile.name })
    }

    /** Coming back to the tab inside a minute of a good answer asks nothing; a pull always asks. */
    @Test
    fun comingBackToTheTabInsideAMinuteAsksNothing() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        made.store.sync(force = false)
        assertTrue(server.seen.isEmpty())
        made.store.sync(force = true)
        assertEquals(listOf("/v2/me", "/v2/feed?days=7"), server.seen.map { it.path })
        // After a refresh that failed, coming back tries again at once.
        server.seen.clear()
        server.answer("GET /v2/feed?days=7", 500 to """{"error":"internal","message":""}""", 200 to EMPTY_FEED)
        made.store.sync(force = true)
        server.seen.clear()
        made.store.sync(force = false)
        assertEquals(1, server.sent("GET", "/v2/feed?days=7").size)
        assertNull(made.store.error.value)
    }

    // MARK: - Invite codes

    private val madeInvite = """{"id":"0123456789abcdef","code":"K7QM2-XRD4P","expiresAt":1792144800}"""
    private val listedInvite = """{"invites":[{"id":"0123456789abcdef","createdAt":1791540000,"expiresAt":1792144800}]}"""

    /**
     * An invite's code is kept so the invite can be shown again, in a file of its own and never in the
     * preferences. It goes when its invite does, and with the account. (That the file's directory is one
     * Android leaves out of backups is the app's wiring, not something a JVM test can see.)
     */
    @Test
    fun inviteCodesAreKeptInAFileAndNeverInThePreferences() = runBlocking {
        val file = File(File(folder, "friends"), "invites.json")
        val invites = FriendsInviteCodes(file)
        val signer = SoftwareFriendsSigner()
        val made = store(FriendsStrap.Identity.None, signer = signer, invites = invites)
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        server.answer("POST /v2/invites", 201 to madeInvite)
        server.answer("GET /v2/invites", 200 to listedInvite)
        made.store.turnOn("Anna")

        assertEquals("K7QM2-XRD4P", made.store.createInvite()?.code)
        assertEquals("K7QM2-XRD4P", made.store.inviteCode("0123456789abcdef"))
        assertFalse("no code in the preferences", made.prefs.holds("K7QM2"))
        assertEquals(mapOf("0123456789abcdef" to "K7QM2-XRD4P"), invites.read())
        assertEquals(listOf("0123456789abcdef"), made.store.invites.value?.map { it.id })

        // A relaunch reads it back from the file.
        val relaunched = store(FriendsStrap.Identity.None, signer = signer, prefs = made.prefs, invites = invites)
        assertEquals(FriendsPhase.On, relaunched.store.phase.value)
        assertEquals("K7QM2-XRD4P", relaunched.store.inviteCode("0123456789abcdef"))
        assertNull("an invite made elsewhere has no code here", relaunched.store.inviteCode("fedcba9876543210"))

        // The invite was used or revoked: its code is of no further use, and no empty file stays.
        server.answer("GET /v2/invites", 200 to """{"invites":[]}""")
        relaunched.store.loadInvites()
        assertNull(relaunched.store.inviteCode("0123456789abcdef"))
        assertFalse(file.exists())

        // Leaving the account removes what is kept.
        server.answer("GET /v2/invites", 200 to listedInvite)
        relaunched.store.createInvite()
        assertTrue(file.exists())
        server.answer("POST /v2/me/delete", 204 to "")
        assertTrue(relaunched.store.deleteAccount())
        assertNull(relaunched.store.inviteCode("0123456789abcdef"))
        assertFalse(file.exists())
    }

    /** Without a file the codes live for the session only, and still not in the preferences. */
    @Test
    fun withoutAFileInviteCodesAreHeldInMemory() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("POST /v2/invites", 201 to madeInvite)
        server.answer("GET /v2/invites", 200 to listedInvite)
        made.store.createInvite()
        assertEquals("K7QM2-XRD4P", made.store.inviteCode("0123456789abcdef"))
        assertEquals(mapOf("0123456789abcdef" to "K7QM2-XRD4P"), made.store.keptInviteCodes())
        assertFalse(made.prefs.holds("K7QM2"))
    }

    /** An invite that could not be made says why, and keeps nothing. */
    @Test
    fun anInviteThatCouldNotBeMadeSaysWhy() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("POST /v2/invites", 409 to """{"error":"too_many_invites","message":""}""")
        assertNull(made.store.createInvite())
        assertEquals(FriendsError.TOO_MANY_INVITES, made.store.error.value)
        assertTrue(made.store.keptInviteCodes().isEmpty())
    }

    @Test
    fun anInviteIsReadOutOfALinkAndWhatIsNotACodeIsNotSent() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("POST /v2/invites/redeem", 200 to """{"friend":{"id":"00000000000000bb","name":"Max","avatarRev":0}}""")
        assertNull(made.store.redeem("hello"))
        assertEquals(FriendsError.NOT_A_CODE, made.store.error.value)
        assertTrue(server.seen.isEmpty())
        assertEquals("Max", made.store.redeem("https://friends.example/i/k7qm2-xrd4p")?.name)
        assertEquals("""{"code":"K7QM2XRD4P"}""", server.sent("POST", "/v2/invites/redeem").single().text)
        assertEquals("the feed is read again, so the new friend is on the tab", 1, server.sent("GET", "/v2/feed?days=7").size)
        // The whole message the app shares, pasted as it was received.
        val pasted = made.store.redeem("Add me on reNOOP Friends: https://friends.example/i/AAAAA-BBBBB\nOr enter the code AAAAA-BBBBB on the Friends tab.")
        assertEquals("Max", pasted?.name)
        assertEquals("""{"code":"AAAAABBBBB"}""", server.sent("POST", "/v2/invites/redeem").last().text)
        // The app's own link, as the invite page opens it.
        made.store.redeem("renoop://friends/add?c=CCCCC-DDDDD")
        assertEquals("""{"code":"CCCCCDDDDD"}""", server.sent("POST", "/v2/invites/redeem").last().text)
    }

    @Test
    fun aFriendIsRemovedByTheirIdAndTheFeedIsReadAgain() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        server.answer("DELETE /v2/friends/00000000000000bb", 204 to "")
        assertTrue(made.store.unfriend("00000000000000bb"))
        assertEquals(1, server.sent("GET", "/v2/feed?days=7").size)
        server.answer("DELETE /v2/friends/00000000000000bb", 404 to """{"error":"no_such_user","message":""}""")
        assertFalse(made.store.unfriend("00000000000000bb"))
        assertEquals(FriendsError.NO_SUCH_USER, made.store.error.value)
    }

    // MARK: - Loose ends

    /**
     * Cancel withdraws the request on the server. When the server could not be reached the request is
     * still there, so the page stays and says why; any answer of the server's ends the wait.
     */
    @Test
    fun cancellingAClaimOfflineStaysOnThePage() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        server.answer("POST /v2/claims", 201 to """{"claim":{"id":3,"kind":"join","code":"481902","state":"pending","createdAt":1}}""")
        made.store.turnOn("Anna")
        made.store.claimAccount("Anna")
        val waiting = made.store.phase.value
        assertTrue(waiting is FriendsPhase.Waiting)

        server.failures += "DELETE /v2/claims/mine"
        made.store.cancelClaim()
        assertEquals("the request is still on the server", waiting, made.store.phase.value)
        assertEquals(FriendsError.OFFLINE, made.store.error.value)

        server.failures.clear()
        server.answer("DELETE /v2/claims/mine", 404 to """{"error":"no_claim","message":""}""")
        made.store.cancelClaim()
        assertEquals("a refusal is an answer: nothing waits on the server", FriendsPhase.StrapBound, made.store.phase.value)
        assertNull(made.store.error.value)
    }

    /**
     * "This is my account" needs the strap's handle. Without one nothing is sent and the page stands:
     * moving on would end, at the next sync, in a new account, the opposite of what was tapped.
     */
    @Test
    fun claimingTheAccountWithoutAStrapSendsNothingAndStays() = runBlocking {
        for (identity in listOf(FriendsStrap.Identity.None, FriendsStrap.Identity.Pending)) {
            val made = store(handle())
            server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
            made.store.turnOn("Anna")
            assertEquals(FriendsPhase.StrapBound, made.store.phase.value)
            server.seen.clear()
            made.store.strapIdentity = { identity }
            made.store.claimAccount("Anna")
            assertTrue(server.seen.isEmpty())
            assertEquals(FriendsPhase.StrapBound, made.store.phase.value)
            assertEquals("strapBound", made.prefs.string(FriendsStore.PHASE_KEY))
            assertEquals(FriendsError.NO_STRAP, made.store.error.value)
            made.store.sync(force = true)
            assertTrue("and no sync enrols by itself", server.seen.isEmpty())
        }
    }

    /** The strap's owner let it go while the page stood: there is no account to join, so one is made. */
    @Test
    fun claimingAStrapThatHasSinceBeenFreedMakesAnAccount() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""", 201 to """{"me":$ME}""")
        server.answer("POST /v2/claims", 404 to """{"error":"strap_free","message":""}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        made.store.claimAccount("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals(2, server.sent("POST", "/v2/enroll").size)
        assertEquals(HANDLE, JSONObject(server.sent("POST", "/v2/enroll").last().text).getString("strap"))
        assertEquals("Anna", made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
    }

    /**
     * A phone that joined records its profile as already sent at its first run, confirmed or not. What
     * the wearer changes during the seven days is then a change made here, sent once confirmed.
     */
    @Test
    fun aRenameDuringProbationIsSentOnceThePhoneIsConfirmed() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None, status = 200, name = "Other")
        made.source.photo = JPEG
        val onProbation = ME_SHARING_NOTHING.replace(""""probationUntil":null""", """"probationUntil":1792104800""")
        server.answer("GET /v2/me", 200 to onProbation)
        server.answer("PATCH /v2/me", 200 to ME)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        assertTrue(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        made.store.uploadRecentDays()
        assertFalse("joining is recorded by a run on probation too", made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertEquals("Other", made.prefs.string(FriendsStore.PUSHED_NAME_KEY))
        assertEquals(FriendsKeys.sha256Hex(JPEG), made.prefs.string(FriendsStore.PUSHED_PHOTO_KEY))

        made.source.name = "Renamed"
        made.store.uploadRecentDays()
        assertTrue("nothing changes while on probation", server.sent("PATCH", "/v2/me").isEmpty())

        server.answer("GET /v2/me", 200 to ME_SHARING_NOTHING)
        made.store.uploadRecentDays()
        assertEquals(listOf("""{"name":"Renamed"}"""), server.sent("PATCH", "/v2/me").map { it.text })
        assertTrue("the photo did not change", server.sent("PUT", "/v2/me/avatar").isEmpty())
    }

    /**
     * The account a run started under can go while the run is asking about the strap. Nothing more of
     * that run is sent: the profile is not pushed with a key the server has just stopped knowing.
     */
    @Test
    fun aRunWhoseAccountWentWhileBindingTheStrapSendsNoProfile() = runBlocking {
        val made = turnedOn(handle())
        made.source.name = "Renamed"
        made.source.photo = JPEG
        made.source.hasWorkout = true
        server.answer("GET /v2/me", 200 to ME_SHARING_WORKOUTS)
        server.answer("PUT /v2/me/strap", 200 to """{"bound":true}""")
        server.answer("PATCH /v2/me", 200 to ME)
        server.answer("PUT /v2/me/avatar", 200 to ME)
        server.answer("PUT /v2/me/days/$today", 204 to "")
        server.answer("GET /v2/feed?days=7", 401 to """{"error":"unknown_key","message":""}""")
        server.delays["PUT /v2/me/strap"] = 400
        val run = async(Dispatchers.Default) { made.store.uploadRecentDays() }
        // The run is waiting on the strap's answer when a refresh finds the phone off the account.
        while (server.sent("PUT", "/v2/me/strap").isEmpty()) delay(10)
        made.store.refresh()
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertEquals(FriendsStore.UploadOutcome.OFF, run.await())
        assertTrue(server.sent("PATCH", "/v2/me").isEmpty())
        assertTrue(server.sent("PUT", "/v2/me/avatar").isEmpty())
        assertTrue(sentDays().isEmpty())
        assertNull("nothing of the account that went is recorded", made.prefs.string(FriendsStore.BOUND_HANDLE_KEY))
    }

    /**
     * What API version 1 left on the phone, its token and what it kept beside it, goes the first time
     * Friends comes on, once. Nothing is touched before that, and no phone key is involved.
     */
    @Test
    fun theLeftoversOfVersionOneGoWhenFriendsComesOn() = runBlocking {
        val prefs = MemoryFriendsPrefs()
        prefs.put("nick", "anna")
        prefs.put("me", """{"nick":"anna"}""")
        prefs.put("feed", "{}")
        prefs.put("uploaded.2026-10-01", "a1")
        val made = store(FriendsStrap.Identity.None, signer = null, prefs = prefs)
        made.store.sync(force = true)
        assertEquals("nothing is touched while Friends is off", 0, made.keys.obsoleteTokenRemovals)
        assertEquals("anna", prefs.string("nick"))

        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        server.answer("POST /v2/me/delete", 204 to "")
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals(1, made.keys.obsoleteTokenRemovals)
        for (key in listOf("nick", "me", "feed", "uploaded.2026-10-01")) assertNull(key, prefs.string(key))
        assertEquals("the server address stays", server.url, prefs.string(FriendsStore.ADDRESS_KEY))
        val key = made.store.deviceId!!
        assertEquals("the phone key is as it was made", key, made.keys.kept(server.url)?.keyId)

        assertTrue(made.store.deleteAccount())
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        assertEquals("once", 1, made.keys.obsoleteTokenRemovals)
    }

    // MARK: - Not now

    /**
     * Turn On can be taken back while the page waits for the strap: Friends is off again, nothing was
     * sent, the key is as it was, and the strap being read later does not finish it by itself.
     */
    @Test
    fun notNowWhileWaitingForTheStrapGoesBackToOffAndStaysThere() = runBlocking {
        val keys = MemoryFriendsKeyStore()
        val kept = keys.create(server.url)
        val made = store(FriendsStrap.Identity.Pending, signer = null, keys = keys)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.WaitingForStrap, made.store.phase.value)
        made.store.notNow()
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertEquals("off", made.prefs.string(FriendsStore.PHASE_KEY))
        assertEquals(kept.keyId, keys.kept(server.url)?.keyId)
        assertEquals(1, keys.writeCount)

        // The strap is read, and the profile has the name an enrolment needs: only a tap on Turn On may use them.
        made.store.strapIdentity = { handle() }
        made.store.sync(force = true)
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertTrue(server.seen.isEmpty())

        val relaunched = store(handle(), signer = null, prefs = made.prefs, keys = keys)
        assertEquals(FriendsPhase.Off, relaunched.store.phase.value)
        assertNull("while Friends is off the key store is not even asked", relaunched.store.deviceId)
        // The key is kept for the next Turn On, which uses it and makes no other.
        server.answer("POST /v2/enroll", 200 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        relaunched.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, relaunched.store.phase.value)
        assertEquals(kept.keyId, relaunched.store.deviceId)
        assertEquals(kept.keyId, server.seen.first().header("X-Friends-Key"))
        assertEquals(1, keys.writeCount)
    }

    /** A phone that never turned Friends on asks nothing of the key store, at launch or when the tab opens. */
    @Test
    fun withFriendsOffTheKeyStoreIsNotRead() = runBlocking {
        val keys = MemoryFriendsKeyStore()
        keys.create(server.url)
        keys.failsReads = true
        val made = store(handle(), signer = null, keys = keys)
        made.store.sync(force = true)
        made.store.refresh()
        assertEquals(FriendsStore.UploadOutcome.OFF, made.store.uploadRecentDays())
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertNull("a read would have failed, and said so", made.store.error.value)
        assertNull(made.store.deviceId)
    }

    @Test
    fun notNowOnAStrapWithAnAccountGoesBackToOff() = runBlocking {
        val made = store(handle())
        server.answer("POST /v2/enroll", 409 to """{"error":"strap_bound","message":""}""")
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.StrapBound, made.store.phase.value)
        val asked = server.seen.size
        made.store.notNow()
        assertEquals(FriendsPhase.Off, made.store.phase.value)
        assertEquals("off", made.prefs.string(FriendsStore.PHASE_KEY))
        assertEquals("nothing is sent to take it back", asked, server.seen.size)
        assertNotNull(made.store.deviceId)
        assertEquals(FriendsPhase.Off, store(handle(), prefs = made.prefs).store.phase.value)
    }

    /**
     * It is the way back from those two pages only: a claim is withdrawn with Cancel, and Friends that is
     * on is left by deleting the account.
     */
    @Test
    fun notNowDoesNothingElsewhere() = runBlocking {
        val made = turnedOn(FriendsStrap.Identity.None)
        made.store.notNow()
        assertEquals(FriendsPhase.On, made.store.phase.value)
    }

    // MARK: - The server address

    /** An address the rules refuse is not written, and the one in use stands. */
    @Test
    fun aServerAddressTheRulesRefuseIsNotWritten() = runBlocking {
        val made = store(FriendsStrap.Identity.None, signer = null)
        for (bad in listOf("http://elsewhere.example", "https://user:secret@elsewhere.example", "not an address")) {
            assertTrue(bad, made.store.setServerAddress(bad) is FriendsStore.ServerChange.Refused)
            assertEquals(server.url, made.prefs.string(FriendsStore.ADDRESS_KEY))
            assertEquals(server.url, made.store.serverAddress)
        }
        assertEquals(
            FriendsStore.ServerChange.Refused(FriendsServerUrl.Problem.NOT_HTTPS),
            made.store.setServerAddress("http://elsewhere.example"),
        )
    }

    /**
     * Another server is another account: the key in hand becomes the one kept for that address, or none,
     * and nothing remembered about the previous server stays. No key is made here.
     */
    @Test
    fun anotherServerBringsItsOwnKeyAndForgetsThePreviousOne() = runBlocking {
        val keys = MemoryFriendsKeyStore()
        val first = keys.create(server.url)
        val second = keys.create(otherServer)
        val made = store(FriendsStrap.Identity.None, signer = null, keys = keys)
        val remembered = listOf(
            FriendsStore.BOUND_HANDLE_KEY, FriendsStore.PUSHED_NAME_KEY, FriendsStore.PUSHED_PHOTO_KEY,
            FriendsStore.MARK_PREFIX + "2026-10-10",
        )
        for (key in remembered) made.prefs.put(key, "kept")
        made.prefs.put(FriendsStore.ADOPT_PROFILE_KEY, true)
        offset.seconds = 5_000
        made.store.setError(FriendsError.OFFLINE)

        assertEquals("the address already in use", FriendsStore.ServerChange.Done, made.store.setServerAddress(server.url + "/"))
        assertEquals("the same address changes nothing", remembered.size, remembered.count { made.prefs.string(it) == "kept" })
        assertEquals(5_000L, offset.seconds)
        assertEquals(first.keyId, keys.kept(server.url)?.keyId)

        assertEquals(FriendsStore.ServerChange.Done, made.store.setServerAddress("  " + otherServer.replace("localhost", "LocalHost") + "/ "))
        assertEquals(otherServer, made.prefs.string(FriendsStore.ADDRESS_KEY))
        assertEquals(otherServer, made.store.serverAddress)
        assertEquals(otherServer, made.store.serverAddressFlow.value)
        assertEquals(second.keyId, made.store.deviceId)
        assertTrue("nothing of the previous server stays", remembered.none { made.prefs.string(it) != null })
        assertFalse(made.prefs.boolean(FriendsStore.ADOPT_PROFILE_KEY))
        assertEquals(0L, offset.seconds)
        assertNull(made.store.error.value)
        assertEquals(FriendsPhase.Off, made.store.phase.value)

        assertEquals(FriendsStore.ServerChange.Done, made.store.setServerAddress("https://third.example"))
        assertNull("no key is kept for that server, and none is made by naming it", made.store.deviceId)
        assertEquals(2, keys.writeCount)
        assertTrue(server.seen.isEmpty())
    }

    /** Nothing typed is the standard server: the setting is removed. */
    @Test
    fun anEmptyServerAddressIsTheStandardServer() = runBlocking {
        val made = store(FriendsStrap.Identity.None, signer = null)
        assertEquals(FriendsStore.ServerChange.Done, made.store.setServerAddress("   "))
        assertNull(made.prefs.string(FriendsStore.ADDRESS_KEY))
        assertEquals(FriendsServerUrl.DEFAULT, made.store.serverAddress)
    }

    /** A server is chosen before Friends is turned on, and at no other time. */
    @Test
    fun theServerAddressChangesOnlyWhileFriendsIsOff() = runBlocking {
        val on = turnedOn(FriendsStrap.Identity.None)
        assertEquals(FriendsStore.ServerChange.NotOff, on.store.setServerAddress(otherServer))
        assertEquals(server.url, on.prefs.string(FriendsStore.ADDRESS_KEY))
        assertEquals(FriendsPhase.On, on.store.phase.value)

        val waiting = store(FriendsStrap.Identity.Pending)
        waiting.store.turnOn("Anna")
        assertEquals(FriendsPhase.WaitingForStrap, waiting.store.phase.value)
        assertEquals(FriendsStore.ServerChange.NotOff, waiting.store.setServerAddress(otherServer))
        assertEquals(server.url, waiting.prefs.string(FriendsStore.ADDRESS_KEY))
        assertEquals(FriendsPhase.WaitingForStrap, waiting.store.phase.value)

        // Friends is on, and only looks off because its key cannot be read: the address stays.
        val (unread, _) = storeThatCouldNotReadItsKey("on")
        assertEquals(FriendsStore.ServerChange.NotOff, unread.store.setServerAddress(otherServer))
        assertEquals(server.url, unread.prefs.string(FriendsStore.ADDRESS_KEY))
        assertEquals("on", unread.prefs.string(FriendsStore.PHASE_KEY))
    }

    /** Turning on after naming another server enrols there, signed with that server's key. */
    @Test
    fun turningOnAfterNamingAnotherServerEnrolsThereWithItsKey() = runBlocking {
        val keys = MemoryFriendsKeyStore()
        keys.create(server.url)
        val second = keys.create(otherServer)
        val made = store(FriendsStrap.Identity.None, signer = null, keys = keys)
        assertEquals(FriendsStore.ServerChange.Done, made.store.setServerAddress(otherServer))
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        made.store.turnOn("Anna")
        assertEquals(FriendsPhase.On, made.store.phase.value)
        val host = otherServer.removePrefix("http://")
        assertEquals(listOf(host, host), server.seen.map { it.header("Host") })
        assertEquals("/v2/enroll", server.seen.first().path)
        assertEquals(second.keyId, server.seen.first().header("X-Friends-Key"))
        assertEquals("no third key was made", 2, keys.writeCount)
    }

    // MARK: - The server's clock

    /** Every "N min ago" counts from the server's clock, carried forward by the time since the feed arrived. */
    @Test
    fun theServersClockIsCarriedForwardFromTheFeed() = runBlocking {
        assertEquals(1_090L, FriendsClock.serverNow(serverTime = 1_000, fetchedAtDevice = 5_000, nowDevice = 5_090))
        assertEquals("a device clock set back counts as no time", 1_000L, FriendsClock.serverNow(1_000, 5_000, 4_000))
        val made = turnedOn(FriendsStrap.Identity.None)
        val fetchedAt = made.store.feedFetchedAt.value
        assertEquals(1_791_540_000L + 90, made.store.serverNow(fetchedAt + 90))
        assertEquals("before any feed, the device's own clock", 42L, store(FriendsStrap.Identity.None).store.serverNow(42L))
    }
}
