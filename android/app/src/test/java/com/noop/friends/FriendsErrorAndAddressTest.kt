package com.noop.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two gates in front of every Friends request: which server address the app will talk to (HTTPS, or
 * plain HTTP to this phone's own loopback for development), and how an error answer becomes a typed
 * failure the tab can put into words.
 */
class FriendsErrorAndAddressTest {

    // MARK: Error codes

    /** The codes of `friends-server/README.md`, "Errors", each with the status it comes with. */
    @Test
    fun everyCodeTheServerSendsHasItsOwnFailure() {
        val expected = mapOf(
            (401 to "unsigned") to FriendsError.REJECTED,
            (401 to "clock_skew") to FriendsError.CLOCK_SKEW,
            (401 to "unknown_key") to FriendsError.UNKNOWN_KEY,
            (401 to "replayed") to FriendsError.REJECTED,
            (401 to "bad_signature") to FriendsError.REJECTED,
            (403 to "probation") to FriendsError.PROBATION,
            (403 to "server_full") to FriendsError.SERVER_FULL,
            (400 to "bad_key") to FriendsError.REJECTED,
            (400 to "bad_json") to FriendsError.REJECTED,
            (400 to "bad_payload") to FriendsError.REJECTED,
            (400 to "bad_name") to FriendsError.BAD_NAME,
            (400 to "bad_platform") to FriendsError.REJECTED,
            (400 to "bad_strap") to FriendsError.REJECTED,
            (400 to "bad_day") to FriendsError.REJECTED,
            (400 to "bad_query") to FriendsError.REJECTED,
            (400 to "bad_length") to FriendsError.REJECTED,
            (400 to "own_invite") to FriendsError.OWN_INVITE,
            (404 to "no_claim") to FriendsError.NO_CLAIM,
            (404 to "no_such_device") to FriendsError.NO_SUCH_DEVICE,
            (404 to "no_such_invite") to FriendsError.NO_SUCH_INVITE,
            (404 to "no_such_user") to FriendsError.NO_SUCH_USER,
            (404 to "no_avatar") to FriendsError.NO_AVATAR,
            (404 to "strap_free") to FriendsError.STRAP_FREE,
            (404 to "not_found") to FriendsError.REJECTED,
            (405 to "method_not_allowed") to FriendsError.REJECTED,
            (409 to "already_enrolled") to FriendsError.ALREADY_ENROLLED,
            (409 to "strap_bound") to FriendsError.STRAP_BOUND,
            (409 to "claim_settled") to FriendsError.CLAIM_SETTLED,
            (409 to "last_device") to FriendsError.LAST_DEVICE,
            (409 to "too_many_devices") to FriendsError.TOO_MANY_DEVICES,
            (409 to "too_many_invites") to FriendsError.TOO_MANY_INVITES,
            (409 to "too_many_friends") to FriendsError.TOO_MANY_FRIENDS,
            (411 to "length_required") to FriendsError.REJECTED,
            (413 to "too_large") to FriendsError.TOO_LARGE,
            (415 to "bad_image") to FriendsError.BAD_IMAGE,
            (429 to "rate_limited") to FriendsError.RATE_LIMITED,
            (429 to "claim_declined") to FriendsError.CLAIM_DECLINED,
            (429 to "too_many_claims") to FriendsError.TOO_MANY_CLAIMS,
            (500 to "internal") to FriendsError.SERVER_ERROR,
        )
        for ((answer, error) in expected) {
            assertEquals(answer.toString(), error, FriendsError.of(answer.first, answer.second))
        }
    }

    /**
     * `unknown_key` turns Friends off on this phone, so it is believed only as the `401` the contract
     * sends it with: a proxy's `401` with no code, or the same word under another status, ends nothing.
     * The same holds for the other refusals the client acts on by itself.
     */
    @Test
    fun aRefusalTheClientActsOnIsBelievedOnlyWithItsOwnStatus() {
        assertEquals(FriendsError.UNKNOWN_KEY, FriendsError.of(401, "unknown_key"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(401, null))
        assertEquals(FriendsError.SERVER_ERROR, FriendsError.of(500, "unknown_key"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(403, "unknown_key"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(404, "strap_bound"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(409, "probation"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(409, "no_claim"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(409, "strap_free"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(404, "already_enrolled"))
    }

    /** No answer, or none the contract describes, is the one case in which what was sent may have been carried out. */
    @Test
    fun onlyNoAnswerLeavesARequestInDoubt() {
        assertEquals(setOf(FriendsError.OFFLINE, FriendsError.BAD_RESPONSE), FriendsError.entries.filter { it.isUnanswered }.toSet())
    }

    @Test
    fun anAnswerWithoutAKnownCodeFallsBackOnItsStatus() {
        assertEquals(FriendsError.RATE_LIMITED, FriendsError.of(429, null))
        assertEquals(FriendsError.TOO_LARGE, FriendsError.of(413, null))
        assertEquals(FriendsError.SERVER_ERROR, FriendsError.of(502, null))
        assertEquals(FriendsError.SERVER_ERROR, FriendsError.of(503, "something_new"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(418, "teapot"))
        assertEquals(FriendsError.UNKNOWN, FriendsError.of(404, null))
    }

    @Test
    fun aRedirectIsAFailureNotAHop() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            assertEquals(FriendsError.REDIRECTED, FriendsError.of(status, null))
        }
    }

    @Test
    fun aResultCarriesItsValueOrItsFailure() {
        val ok: FriendsResult<Int> = FriendsResult.Ok(2)
        val fail: FriendsResult<Int> = FriendsResult.Fail(FriendsError.OFFLINE)
        assertEquals(4, ok.map { it * 2 }.valueOrNull)
        assertNull(ok.errorOrNull)
        assertEquals(FriendsError.OFFLINE, fail.map { it * 2 }.errorOrNull)
        assertNull(fail.valueOrNull)
    }

    // MARK: Server address

    private fun valid(raw: String): String? = FriendsServerUrl.normalised(raw)

    private fun problem(raw: String): FriendsServerUrl.Problem? =
        (FriendsServerUrl.validate(raw) as? FriendsServerUrl.Result.Invalid)?.problem

    @Test
    fun theDefaultServerIsItselfValid() {
        assertEquals(FriendsServerUrl.DEFAULT, valid(FriendsServerUrl.DEFAULT))
        assertTrue(FriendsServerUrl.DEFAULT.startsWith("https://"))
    }

    @Test
    fun httpsIsAcceptedAndNormalised() {
        assertEquals("https://renoop.duckdns.org", valid("  https://renoop.duckdns.org/  "))
        assertEquals("https://renoop.duckdns.org", valid("HTTPS://ReNoop.DuckDNS.org"))
        assertEquals("https://example.org", valid("https://example.org:443"))
        assertEquals("https://example.org:8443", valid("https://example.org:8443/"))
        assertEquals("https://example.org/friends", valid("https://example.org/friends/"))
    }

    @Test
    fun anAddressTypedWithoutASchemeIsReadAsHttps() {
        assertEquals("https://renoop.duckdns.org", valid("renoop.duckdns.org"))
        assertEquals("https://example.org:8443", valid("example.org:8443"))
    }

    @Test
    fun plainHttpIsRefusedEverywhereButLoopback() {
        assertEquals(FriendsServerUrl.Problem.NOT_HTTPS, problem("http://renoop.duckdns.org"))
        assertEquals(FriendsServerUrl.Problem.NOT_HTTPS, problem("http://192.168.1.10:8787"))
        assertEquals(FriendsServerUrl.Problem.NOT_HTTPS, problem("http://10.0.2.2:8787"))
        assertEquals(FriendsServerUrl.Problem.NOT_HTTPS, problem("http://localhost.example.org"))
        assertEquals(FriendsServerUrl.Problem.NOT_HTTPS, problem("http://127.0.0.1.example.org"))
        assertEquals(FriendsServerUrl.Problem.NOT_HTTPS, problem("http://127.0.0.2:8787"))
        assertEquals(FriendsServerUrl.Problem.NOT_HTTPS, problem("ftp://example.org"))
    }

    @Test
    fun plainHttpToThisPhonesLoopbackIsAcceptedForDevelopment() {
        assertEquals("http://localhost:8787", valid("http://localhost:8787"))
        assertEquals("http://127.0.0.1:8787", valid("http://127.0.0.1:8787/"))
        assertEquals("http://localhost", valid("http://LOCALHOST:80"))
        assertEquals("https://localhost:8443", valid("https://localhost:8443"))
    }

    @Test
    fun credentialsQueriesAndNonsenseAreRefused() {
        assertEquals(FriendsServerUrl.Problem.HAS_CREDENTIALS, problem("https://user:pass@example.org"))
        assertEquals(FriendsServerUrl.Problem.HAS_QUERY, problem("https://example.org/?token=1"))
        assertEquals(FriendsServerUrl.Problem.HAS_QUERY, problem("https://example.org/#top"))
        assertEquals(FriendsServerUrl.Problem.EMPTY, problem("   "))
        assertEquals(FriendsServerUrl.Problem.MALFORMED, problem("https://"))
        assertEquals(FriendsServerUrl.Problem.MALFORMED, problem("https://exa mple.org"))
        assertEquals(FriendsServerUrl.Problem.MALFORMED, problem("https://example.org:99999"))
        // A name outside ASCII is refused rather than guessed at; its punycode form is accepted.
        assertEquals(FriendsServerUrl.Problem.MALFORMED, problem("https://домен.рф"))
        assertEquals("https://xn--d1acufc.xn--p1ai", valid("https://xn--d1acufc.xn--p1ai"))
    }

    @Test
    fun aClientExistsOnlyForAnAcceptedAddress() {
        assertNotNull(FriendsApi.create("https://renoop.duckdns.org", null))
        assertNotNull(FriendsApi.create("https://renoop.duckdns.org", SoftwareFriendsSigner()))
        assertNotNull(FriendsApi.create("http://127.0.0.1:8787", null))
        assertNull(FriendsApi.create("http://renoop.duckdns.org", SoftwareFriendsSigner()))
        assertNull(FriendsApi.create("", null))
    }

    /**
     * Every line of the Swift twin's own output ([FriendsWireOracle], section "address"): the two apps
     * take and refuse the same addresses, and keep the same text for the ones they take.
     */
    @Test
    fun anAddressIsCheckedAsTheSwiftTwinChecksIt() {
        val lines = FriendsWireOracle.section("address")
        val differing = lines.mapNotNull { expected ->
            val input = FriendsWireOracle.input(expected)
            val answer = when (val result = FriendsServerUrl.validate(input)) {
                is FriendsServerUrl.Result.Valid -> "ok " + result.url
                is FriendsServerUrl.Result.Invalid -> "refused " + when (result.problem) {
                    FriendsServerUrl.Problem.EMPTY -> "empty"
                    FriendsServerUrl.Problem.MALFORMED -> "malformed"
                    FriendsServerUrl.Problem.NOT_HTTPS -> "notHTTPS"
                    FriendsServerUrl.Problem.HAS_CREDENTIALS -> "hasCredentials"
                    FriendsServerUrl.Problem.HAS_QUERY -> "hasQuery"
                }
            }
            val actual = FriendsWireOracle.line(input, answer)
            if (actual == expected) null else "swift:  $expected\nkotlin: $actual"
        }
        assertTrue("differs from the Swift twin:\n" + differing.joinToString("\n"), differing.isEmpty())
        assertTrue(lines.size > 100)
    }
}
