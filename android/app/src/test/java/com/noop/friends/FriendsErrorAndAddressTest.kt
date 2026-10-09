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

    @Test
    fun everyCodeTheServerSendsHasItsOwnFailure() {
        // (status, code) pairs exactly as friends-server/server.py raises them.
        val expected = mapOf(
            (409 to "nick_taken") to FriendsError.NICK_TAKEN,
            (401 to "bad_credentials") to FriendsError.BAD_CREDENTIALS,
            (401 to "unauthorized") to FriendsError.UNAUTHORIZED,
            (429 to "rate_limited") to FriendsError.RATE_LIMITED,
            (404 to "no_such_user") to FriendsError.NO_SUCH_USER,
            (403 to "bad_invite") to FriendsError.BAD_INVITE,
            (403 to "server_full") to FriendsError.SERVER_FULL,
            (400 to "bad_nick") to FriendsError.BAD_NICK,
            (400 to "bad_name") to FriendsError.BAD_NAME,
            (400 to "bad_password") to FriendsError.BAD_PASSWORD,
            (400 to "self_request") to FriendsError.SELF_REQUEST,
            (404 to "no_request") to FriendsError.NO_REQUEST,
            (409 to "too_many_friends") to FriendsError.TOO_MANY_FRIENDS,
            (409 to "too_many_requests") to FriendsError.TOO_MANY_REQUESTS,
            (415 to "bad_image") to FriendsError.BAD_IMAGE,
            (413 to "too_large") to FriendsError.TOO_LARGE,
            (404 to "no_avatar") to FriendsError.NO_AVATAR,
            (400 to "bad_payload") to FriendsError.REJECTED,
            (400 to "bad_json") to FriendsError.REJECTED,
            (400 to "bad_day") to FriendsError.REJECTED,
            (404 to "not_found") to FriendsError.REJECTED,
            (405 to "method_not_allowed") to FriendsError.REJECTED,
            (500 to "internal") to FriendsError.SERVER_ERROR,
        )
        for ((answer, error) in expected) {
            assertEquals(answer.toString(), error, FriendsError.of(answer.first, answer.second))
        }
    }

    @Test
    fun a401IsADeadSessionOnlyWhenTheServerSaysSo() {
        // A wrong password and a dead session share the status; only the second signs the phone out.
        assertEquals(FriendsError.BAD_CREDENTIALS, FriendsError.of(401, "bad_credentials"))
        assertEquals(FriendsError.UNAUTHORIZED, FriendsError.of(401, "unauthorized"))
        assertEquals(FriendsError.UNAUTHORIZED, FriendsError.of(401, null))
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
        assertNotNull(FriendsApi.create("http://127.0.0.1:8787", "token"))
        assertNull(FriendsApi.create("http://renoop.duckdns.org", "token"))
        assertNull(FriendsApi.create("", null))
    }

    // MARK: Nicknames

    @Test
    fun aNicknameIsCleanedTheWayTheServerStoresIt() {
        assertEquals("ruslan", FriendsNick.clean("@Ruslan"))
        assertEquals("den_chik7", FriendsNick.clean("  den_chik7 "))
        assertNull(FriendsNick.clean("ab"))
        assertNull(FriendsNick.clean("has space"))
        assertNull(FriendsNick.clean("кириллица"))
        assertNull(FriendsNick.clean("x".repeat(21)))
        assertNull(FriendsNick.clean("a/b/c"))
        assertNull(FriendsNick.clean("../me"))
    }

    @Test
    fun typingIsHeldToWhatANicknameMayContain() {
        assertEquals("ruslan_7", FriendsNick.filterTyping("@Ruslan_7!"))
        assertEquals("x".repeat(20), FriendsNick.filterTyping("x".repeat(30)))
        assertEquals("", FriendsNick.filterTyping("Привет"))
    }

    @Test
    fun aPasswordIs8To128Characters() {
        assertFalse(FriendsNick.passwordOk("1234567"))
        assertTrue(FriendsNick.passwordOk("12345678"))
        assertTrue(FriendsNick.passwordOk("x".repeat(128)))
        assertFalse(FriendsNick.passwordOk("x".repeat(129)))
    }
}
