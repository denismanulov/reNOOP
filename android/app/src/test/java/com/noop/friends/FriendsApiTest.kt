package com.noop.friends

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The Friends client against the contract in `friends-server/README.md` (API version 2): what it signs
 * and sends, and what it reads. The cases of the Swift `FriendsClientTests` that are about the wire. The
 * server is [FriendsTestServer] on loopback and the key a software one, so what is asserted is what
 * OkHttp really sent.
 */
class FriendsApiTest {
    private val server = FriendsTestServer()

    @After
    fun tearDown() = server.close()

    private val offset = FriendsClockOffset()
    private val transport = FriendsTransport(offset = offset, now = { FIXED_NOW }, nonce = { FIXED_NONCE })

    private fun client(signer: FriendsSigner? = SoftwareFriendsSigner(), base: String = server.url): FriendsApi =
        FriendsApi.create(base, signer, transport)!!

    private fun <T> FriendsResult<T>.ok(): T = when (this) {
        is FriendsResult.Ok -> value
        is FriendsResult.Fail -> throw AssertionError("expected a value, got $error")
    }

    /** Whether [seen] carries a signature by [signer] over exactly this method, target, time and body. */
    private fun isSigned(seen: FriendsTestServer.Seen, signer: FriendsSigner, target: String, time: Long = FIXED_NOW): Boolean {
        if (seen.header("X-Friends-Key") != signer.keyId || seen.header("X-Friends-Time") != time.toString()) return false
        if (seen.header("X-Friends-Nonce") != FIXED_NONCE) return false
        val signature = runCatching { Base64.getDecoder().decode(seen.header("X-Friends-Signature")) }.getOrNull() ?: return false
        val text = FriendsWire.signingString(seen.method, target, time, FIXED_NONCE, FriendsKeys.sha256Hex(seen.body))
        return friendsSignatureVerifies(signer.publicKeySpki, text.toByteArray(Charsets.UTF_8), signature)
    }

    // MARK: - Signing

    /** The README's vector, as this client produces it: the same method, target, time, nonce and body. */
    @Test
    fun aRequestIsSignedOverItsMethodTargetTimeNonceAndBody() = runBlocking {
        val signer = SoftwareFriendsSigner()
        server.answer("PUT /v2/me/days/2026-10-10", 204 to "")
        client(signer).putDay("2026-10-10", """{"recovery":81}""").ok()
        val sent = server.seen.single()
        assertEquals("""{"recovery":81}""", sent.text)
        assertEquals("a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445", FriendsKeys.sha256Hex(sent.body))
        assertTrue(isSigned(sent, signer, "/v2/me/days/2026-10-10"))
        assertNull("there is no bearer token any more", sent.header("Authorization"))
        assertEquals("standard base64 with padding", 0, sent.header("X-Friends-Signature")!!.length % 4)
    }

    /**
     * A body goes out with its length and never chunked: the server refuses a request with a
     * `Transfer-Encoding` header (`411 length_required`). So does a call with no body at all.
     */
    @Test
    fun aBodyIsSentWithItsLengthAndNeverChunked() = runBlocking {
        val signer = SoftwareFriendsSigner()
        server.answer("PUT /v2/me/days/2026-10-10", 204 to "")
        server.answer("PUT /v2/me/avatar", 200 to ME)
        server.answer("POST /v2/invites", 201 to """{"id":"0123456789abcdef","code":"K7QM2-XRD4P","expiresAt":1792144800}""")
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""")
        server.answer("DELETE /v2/claims/mine", 204 to "")
        val api = client(signer)
        api.putDay("2026-10-10", """{"recovery":81}""").ok()
        api.putAvatar(JPEG).ok()
        api.createInvite().ok()
        api.enroll("Anna", HANDLE).ok()
        api.withdrawClaim().ok()
        assertEquals(5, server.seen.size)
        for (sent in server.seen) {
            assertNull("${sent.method} ${sent.path}", sent.header("Transfer-Encoding"))
            assertEquals("${sent.method} ${sent.path}", sent.body.size.toString(), sent.header("Content-Length"))
        }
        assertEquals("15", server.seen[0].header("Content-Length"))
        assertEquals("a call with no body is signed over nothing", 0, server.seen[2].body.size)
        assertTrue(isSigned(server.seen[2], signer, "/v2/invites"))
        assertEquals(FriendsKeys.sha256Hex(ByteArray(0)), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun theQueryIsPartOfWhatIsSigned() = runBlocking {
        val signer = SoftwareFriendsSigner()
        server.answer("GET /v2/feed?days=7", 200 to EMPTY_FEED)
        client(signer).feed().ok()
        val sent = server.seen.single()
        assertTrue(isSigned(sent, signer, "/v2/feed?days=7"))
        assertFalse(isSigned(sent, signer, "/v2/feed"))
    }

    /**
     * A server mounted under a prefix by its proxy is asked under that prefix, and sees the path without
     * it: what is signed is the target beginning "/v2/", the query included.
     */
    @Test
    fun aServerUnderAPathPrefixIsSignedWithoutThePrefix() = runBlocking {
        val signer = SoftwareFriendsSigner()
        val prefixed = client(signer, base = server.url + "/friends/")
        server.answer("GET /friends/v2/feed?days=7", 200 to EMPTY_FEED)
        server.answer("PUT /friends/v2/me/days/2026-10-10", 204 to "")
        prefixed.feed().ok()
        prefixed.putDay("2026-10-10", """{"recovery":81}""").ok()
        assertEquals(listOf("/friends/v2/feed?days=7", "/friends/v2/me/days/2026-10-10"), server.seen.map { it.path })
        assertTrue(isSigned(server.seen[0], signer, "/v2/feed?days=7"))
        assertFalse(isSigned(server.seen[0], signer, "/friends/v2/feed?days=7"))
        assertTrue(isSigned(server.seen[1], signer, "/v2/me/days/2026-10-10"))
        assertFalse(isSigned(server.seen[1], signer, "/friends/v2/me/days/2026-10-10"))
    }

    @Test
    fun infoIsTheOneCallThatIsNotSigned() = runBlocking {
        server.answer("GET /v2/info", 200 to """{"name":"renoop-friends","api":2,"time":1791540000}""")
        assertEquals(FriendsServerInfo("renoop-friends", 2, 1_791_540_000L), client(null).info().ok())
        assertNull(server.seen.single().header("X-Friends-Signature"))
        assertNull(server.seen.single().header("X-Friends-Key"))
    }

    @Test
    fun withoutAKeyNothingElseIsSent() = runBlocking {
        assertEquals(FriendsError.NOT_ENROLLED, client(null).me().errorOrNull)
        assertEquals(FriendsError.NOT_ENROLLED, client(null).enroll("Anna", null).errorOrNull)
        assertEquals(FriendsError.NOT_ENROLLED, client(null).putDay("2026-10-10", "{}").errorOrNull)
        assertTrue(server.seen.isEmpty())
    }

    /** A phone whose clock is wrong is told the server's, signs once more by it, and remembers the gap. */
    @Test
    fun aWrongClockSignsOnceMoreByTheServers() = runBlocking {
        val signer = SoftwareFriendsSigner()
        server.answer("GET /v2/me", 401 to """{"error":"clock_skew","message":"","serverTime":1791545000}""", 200 to ME)
        assertEquals(ANNA, client(signer).me().ok().id)
        assertEquals(2, server.seen.size)
        assertTrue(isSigned(server.seen[0], signer, "/v2/me"))
        assertTrue(isSigned(server.seen[1], signer, "/v2/me", time = 1_791_545_000L))
        assertEquals(5_000L, offset.seconds)
        // Every later request is stamped by the server's clock from the start.
        server.answer("GET /v2/me", 200 to ME)
        client(signer).me().ok()
        assertEquals("1791545000", server.seen[2].header("X-Friends-Time"))
    }

    @Test
    fun aClockTheServerRefusesTwiceIsTheAnswer() = runBlocking {
        server.answer("GET /v2/me", 401 to """{"error":"clock_skew","message":"","serverTime":1791545000}""")
        assertEquals(FriendsError.CLOCK_SKEW, client().me().errorOrNull)
        assertEquals("once, and once more", 2, server.seen.size)
    }

    /** Another `401` is not a clock: it is the answer at once, and nothing is signed again. */
    @Test
    fun onlyAClockRefusalIsAnsweredBySigningAgain() = runBlocking {
        server.answer("GET /v2/me", 401 to """{"error":"unknown_key","message":""}""")
        assertEquals(FriendsError.UNKNOWN_KEY, client().me().errorOrNull)
        assertEquals(1, server.seen.size)
        assertEquals(0L, offset.seconds)
    }

    // MARK: - Calls

    @Test
    fun enrollingSendsTheKeyTheNameThePlatformAndTheStrap() = runBlocking {
        val signer = SoftwareFriendsSigner()
        server.answer("POST /v2/enroll", 201 to """{"me":$ME}""", 200 to """{"me":$ME}""")
        val first = client(signer).enroll("Anna", HANDLE).ok()
        assertTrue("201 is an account made now", first.isNew)
        assertEquals(true, first.me.strapBound)
        assertNull(first.me.device?.probationUntil)
        val body = JSONObject(server.seen[0].text)
        assertEquals(setOf("key", "name", "platform", "strap"), body.keySet())
        assertEquals(Base64.getEncoder().encodeToString(signer.publicKeySpki), body.getString("key"))
        assertEquals("Anna", body.getString("name"))
        assertEquals("android", body.getString("platform"))
        assertEquals(HANDLE, body.getString("strap"))
        assertTrue(isSigned(server.seen[0], signer, "/v2/enroll"))

        val again = client(signer).enroll("Anna", null).ok()
        assertFalse("200 is the account the key already had", again.isNew)
        assertEquals(ANNA, again.me.id)
        assertFalse("no strap is no member, not a null", JSONObject(server.seen[1].text).has("strap"))
    }

    @Test
    fun aRefusalCarriesTheServersCode() = runBlocking {
        val cases = listOf(
            Triple(401, "unknown_key", FriendsError.UNKNOWN_KEY), Triple(409, "strap_bound", FriendsError.STRAP_BOUND),
            Triple(403, "probation", FriendsError.PROBATION), Triple(404, "no_claim", FriendsError.NO_CLAIM),
            Triple(404, "strap_free", FriendsError.STRAP_FREE), Triple(409, "already_enrolled", FriendsError.ALREADY_ENROLLED),
        )
        for ((status, code, error) in cases) {
            server.answer("GET /v2/me", status to """{"error":"$code","message":""}""")
            assertEquals(code, error, client().me().errorOrNull)
        }
        assertEquals(
            "a code the client acts on is believed only with its own status",
            FriendsError.SERVER_ERROR, FriendsError.of(500, "unknown_key"),
        )
    }

    @Test
    fun bindingAStrapIsDoneOrAskedOfItsOwner() = runBlocking {
        server.answer(
            "PUT /v2/me/strap",
            200 to """{"bound":true}""",
            202 to """{"claim":{"id":7,"kind":"take","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}}""",
        )
        assertEquals(FriendsStrapAnswer.Bound, client().putStrap(HANDLE).ok())
        assertEquals(
            FriendsStrapAnswer.Claimed(
                FriendsClaim(7, FriendsClaim.Kind.TAKE, "481902", FriendsClaim.State.PENDING, "ios", 1_791_540_000L, 1_791_712_800L),
            ),
            client().putStrap(HANDLE).ok(),
        )
        assertEquals("""{"strap":"$HANDLE"}""", server.seen[0].text)
    }

    @Test
    fun anInviteIsMadeListedAndUsed() = runBlocking {
        server.answer("POST /v2/invites", 201 to """{"id":"0123456789abcdef","code":"K7QM2-XRD4P","expiresAt":1792144800}""")
        server.answer("GET /v2/invites", 200 to """{"invites":[{"id":"0123456789abcdef","createdAt":1791540000,"expiresAt":1792144800}]}""")
        server.answer("POST /v2/invites/redeem", 200 to """{"friend":{"id":"00000000000000aa","name":"Anna","avatarRev":2}}""")
        server.answer("DELETE /v2/invites/0123456789abcdef", 204 to "")
        assertEquals("K7QM2-XRD4P", client().createInvite().ok().code)
        val listed = client().invites().ok()
        assertEquals(listOf("0123456789abcdef"), listed.map { it.id })
        assertNull("a listing never repeats a code", listed[0].code)
        assertEquals(FriendProfile(ANNA, "Anna", avatarRev = 2), client().redeem("K7QM2XRD4P").ok())
        assertEquals("""{"code":"K7QM2XRD4P"}""", server.seen[2].text)
        client().revokeInvite("0123456789abcdef").ok()
        assertEquals("DELETE", server.seen[3].method)
    }

    /** An id goes into a path only when it has the shape of one. */
    @Test
    fun whatIsNotAnIdNeverReachesAPath() = runBlocking {
        for (id in listOf("../me", "anna", "00000000000000AA", "")) {
            assertEquals(id, FriendsError.NO_SUCH_USER, client().userDays(id).errorOrNull)
            assertEquals(id, FriendsError.NO_SUCH_USER, client().avatar(id).errorOrNull)
            assertEquals(id, FriendsError.NO_SUCH_USER, client().unfriend(id).errorOrNull)
            assertEquals(id, FriendsError.NO_SUCH_INVITE, client().revokeInvite(id).errorOrNull)
            assertEquals(id, FriendsError.NO_SUCH_DEVICE, client().removeDevice(id).errorOrNull)
            assertEquals(id, FriendsError.NO_SUCH_DEVICE, client().trustDevice(id).errorOrNull)
        }
        assertEquals(FriendsError.REJECTED, client().putDay("../../me", "{}").errorOrNull)
        assertTrue(server.seen.isEmpty())
        assertTrue(FriendsId.isValid(ANNA))
        assertTrue(FriendsId.isKeyId(SoftwareFriendsSigner().keyId))
    }

    /**
     * The feed as the server writes it: a friend with a day, the requests that wait for an answer, and
     * around what cannot be read (a friend without an id, a claim of an unknown kind) instead of failing.
     */
    @Test
    fun theFeedDecodesAroundWhatCannotBeRead() = runBlocking {
        val claim = """{"id":3,"kind":"join","code":"481902","state":"pending","platform":"android","createdAt":1791540000,"maturesAt":1791712800}"""
        server.answer(
            "GET /v2/feed?days=7",
            200 to """
            {"serverTime":1791540000,"me":$ME,
             "friends":[{"id":"00000000000000bb","name":"Max","avatarRev":3,
                         "share":{"scores":true,"sleep":false,"workouts":true,"hr":false},
                         "days":[{"day":"2026-10-09","updatedAt":1791500000,"recovery":64,"strain":null},
                                 {"day":"2026-10-10","updatedAt":1791539000,"recovery":81,"strain":38.6,"sleepScore":88},
                                 {"day":"yesterday","recovery":1}]},
                        {"name":"No id"},{"id":"not-an-id","name":"Bad id"}],
             "claims":[$claim,{"id":4,"kind":"sideways","code":"1","state":"pending","createdAt":1}],
             "strapClaim":null,
             "unconfirmed":[{"id":"k2","platform":"android","addedAt":1791500000,"lastSeenAt":1791539000,"probationUntil":1792104800,"current":false}]}
            """.trimIndent(),
        )
        val (feed, raw) = client().feed().ok()
        assertTrue(raw.isNotEmpty())
        assertEquals(feed, FriendsJson.feed(raw))
        assertEquals(1_791_540_000L, feed.serverTime)
        assertEquals(ANNA, feed.me.id)
        assertEquals(listOf("00000000000000bb"), feed.friends.map { it.id })
        assertEquals("newest first, unreadable dropped", listOf("2026-10-10", "2026-10-09"), feed.friends[0].days.map { it.day })
        assertEquals(81, feed.friends[0].days.first().recovery)
        assertFalse(feed.friends[0].share.sleep)
        assertEquals(listOf(3L), feed.claims.map { it.id })
        assertEquals(FriendsClaim.Kind.JOIN, feed.claims[0].kind)
        assertNull(feed.strapClaim)
        assertEquals(listOf("android"), feed.unconfirmed.map { it.platform })
        assertEquals("a feed without the member reads as none", emptyList<FriendsDevice>(), FriendsJson.feed(EMPTY_FEED).unconfirmed)
    }

    @Test
    fun aPictureGoesUpAsAJpegAndOneTooLargeIsNotSent() = runBlocking {
        server.answer("PUT /v2/me/avatar", 200 to ME)
        client().putAvatar(JPEG).ok()
        assertEquals("image/jpeg", server.seen[0].header("Content-Type"))
        assertArrayEquals(JPEG, server.seen[0].body)
        assertEquals(FriendsError.TOO_LARGE, client().putAvatar(ByteArray(FriendsLimits.MAX_AVATAR_BYTES + 1)).errorOrNull)
        assertEquals(1, server.seen.size)
    }

    /** A redirect is the answer, not a hop: a signed request goes to the host the wearer named only. */
    @Test
    fun aRedirectIsAFailureNotAHop() = runBlocking {
        val elsewhere = FriendsTestServer()
        try {
            server.redirects["GET /v2/me"] = elsewhere.url + "/v2/me"
            assertEquals(FriendsError.REDIRECTED, client().me().errorOrNull)
            assertEquals(1, server.seen.size)
            assertTrue("the signature did not travel on", elsewhere.seen.isEmpty())
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun aServerThatDropsTheConnectionIsOffline() = runBlocking {
        server.failures += "GET /v2/me"
        assertEquals(FriendsError.OFFLINE, client().me().errorOrNull)
        assertTrue(FriendsError.OFFLINE.isUnanswered)
        server.failures.clear()
        server.answer("GET /v2/me", 200 to "<html>a captive portal</html>")
        assertEquals(FriendsError.BAD_RESPONSE, client().me().errorOrNull)
        assertTrue("no answer the contract describes is no answer", FriendsError.BAD_RESPONSE.isUnanswered)
        assertFalse(FriendsError.STRAP_BOUND.isUnanswered)
    }

    /** What the server stores comes back as the text it sent, not as something read and written again. */
    @Test
    fun theExportIsTheTextTheServerSent() = runBlocking {
        val wire = """{"me":{"name":"Anna"},"days":[{"day":"2026-10-10","strain":38.60,"kcal":12.0}],"e":1e-7}"""
        server.answer("GET /v2/me/export", 200 to wire)
        assertEquals(wire, client().export().ok())
    }

    companion object {
        const val FIXED_NOW = 1_791_540_000L
        const val FIXED_NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
        const val ANNA = "00000000000000aa"
        const val HANDLE = "98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"
        const val ME =
            """{"id":"00000000000000aa","name":"Anna","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false},"strapBound":true,"device":{"id":"k","probationUntil":null}}"""

        /** The account with every sharing switch off, so an upload run builds nothing out of the app's data. */
        const val ME_SHARING_NOTHING =
            """{"id":"00000000000000aa","name":"Anna","avatarRev":0,"share":{"scores":false,"sleep":false,"workouts":false,"hr":false},"strapBound":true,"device":{"id":"k","probationUntil":null}}"""

        /** The account sharing workouts only. */
        const val ME_SHARING_WORKOUTS =
            """{"id":"00000000000000aa","name":"Anna","avatarRev":0,"share":{"scores":false,"sleep":false,"workouts":true,"hr":false},"strapBound":true,"device":{"id":"k","probationUntil":null}}"""
        const val EMPTY_FEED = """{"serverTime":1791540000,"me":$ME,"friends":[],"claims":[],"strapClaim":null}"""
        val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { 0x30 }
    }
}
