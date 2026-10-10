import XCTest
import Foundation
import StrandAnalytics
import WhoopStore
@testable import Strand

/// The Friends client against the contract in `friends-server/README.md`: what it sends, what it
/// reads, and that the password goes out only with the four calls that ask for it.
final class FriendsClientTests: XCTestCase {

    /// Answers every request from a canned table and records what was asked.
    private final class Stub: URLProtocol {
        nonisolated(unsafe) static var answers: [String: (status: Int, body: String)] = [:]
        /// Requests answered with a redirect to the address given, keyed like `answers`.
        nonisolated(unsafe) static var redirects: [String: String] = [:]
        nonisolated(unsafe) static var seen: [(method: String, path: String, auth: String?, body: String)] = []
        nonisolated(unsafe) static var contentTypes: [String?] = []

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func stopLoading() {}

        override func startLoading() {
            var body = request.httpBody ?? Data()
            if let stream = request.httpBodyStream {
                stream.open()
                var buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable {
                    let n = stream.read(&buffer, maxLength: buffer.count)
                    if n <= 0 { break }
                    body.append(buffer, count: n)
                }
                stream.close()
            }
            let url = request.url!
            let path = url.path + (url.query.map { "?" + $0 } ?? "")
            let method = request.httpMethod ?? "GET"
            Self.seen.append((method, path, request.value(forHTTPHeaderField: "Authorization"),
                              String(decoding: body, as: UTF8.self)))
            Self.contentTypes.append(request.value(forHTTPHeaderField: "Content-Type"))
            if let target = Self.redirects[method + " " + path], let location = URL(string: target) {
                let hop = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1",
                                          headerFields: ["Location": target])!
                // A client that follows it asks `location` next, with the token still on the request; one
                // that refuses is left with the 302 as its answer.
                client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: location), redirectResponse: hop)
                client?.urlProtocol(self, didReceive: hop, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self)
                return
            }
            let answer = Self.answers[method + " " + path] ?? (404, #"{"error":"not_found","message":"Not found."}"#)
            let response = HTTPURLResponse(url: url, statusCode: answer.status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(answer.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    private func stubbedSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return URLSession(configuration: config)
    }

    private func client(token: String? = "tok") -> FriendsClient {
        FriendsClient(baseURL: URL(string: "https://friends.example")!, token: token, session: stubbedSession())
    }

    override func setUp() {
        super.setUp()
        Stub.answers = [:]
        Stub.redirects = [:]
        Stub.seen = []
        Stub.contentTypes = []
    }

    // MARK: - Account

    /// Sign-up sends the nickname (normalised) and the password, and is the one call made without a token.
    func testSignUpSendsTheNameAndPasswordAndKeepsTheToken() async throws {
        Stub.answers["POST /v1/register"] = (201, #"{"token":"abc","me":{"nick":"denis","name":"denis","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false}}}"#)
        let created = try await client(token: nil).register(nick: " @Denis ", password: "correct horse", name: nil, invite: nil)
        XCTAssertEqual(created.token, "abc")
        XCTAssertEqual(created.me.share, FriendsShare())
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(sent.body, #"{"nick":"denis","password":"correct horse"}"#)
        XCTAssertNil(sent.auth, "sign-up is made without a token")
    }

    func testSignInSendsNameAndPasswordWithoutATokenAndReturnsANewOne() async throws {
        Stub.answers["POST /v1/login"] = (200, #"{"token":"fresh","me":{"nick":"denis","name":"Денис","avatarRev":0}}"#)
        let entered = try await client(token: nil).login(nick: "@Denis", password: "correct horse")
        XCTAssertEqual(entered.token, "fresh")
        XCTAssertEqual(entered.me.name, "Денис")
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(sent.body, #"{"nick":"denis","password":"correct horse"}"#)
        XCTAssertNil(sent.auth)
    }

    func testSigningOutEndsOnlyThisSession() async throws {
        Stub.answers["DELETE /v1/session"] = (204, "")
        try await client().signOut()
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(sent.method, "DELETE")
        XCTAssertEqual(sent.auth, "Bearer tok")
        XCTAssertEqual(sent.body, "")
    }

    /// The server ends every other session and answers with this phone's new token.
    func testChangingThePasswordReturnsTheNewToken() async throws {
        Stub.answers["POST /v1/me/password"] = (200, #"{"token":"renewed"}"#)
        let token = try await client().changePassword(old: "old password", new: "new password")
        XCTAssertEqual(token, "renewed")
        XCTAssertEqual(Stub.seen.first?.body, #"{"new":"new password","old":"old password"}"#)
    }

    func testDeletingTheAccountSendsThePassword() async throws {
        Stub.answers["POST /v1/me/delete"] = (204, "")
        try await client().deleteAccount(password: "correct horse")
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(sent.body, #"{"password":"correct horse"}"#)
        XCTAssertEqual(sent.auth, "Bearer tok")
    }

    /// A wrong password answers 401 like an ended session does, but it must not sign the phone out.
    func testAWrongPasswordIsNotAnEndedSession() async {
        Stub.answers["POST /v1/me/delete"] = (401, #"{"error":"bad_credentials","message":"Wrong password."}"#)
        do {
            try await client().deleteAccount(password: "nope nope")
            XCTFail("expected a refusal")
        } catch let error as FriendsAPIError {
            XCTAssertTrue(error.isWrongPassword)
            XCTAssertFalse(error.isSignedOut)
        } catch { XCTFail("\(error)") }
    }

    func testEveryLaterCallCarriesTheToken() async throws {
        Stub.answers["GET /v1/friends/requests"] = (200, #"{"incoming":[{"nick":"ruslan","name":"Руслан","avatarRev":0,"requestedAt":1791500000}],"outgoing":[]}"#)
        let requests = try await client().requests()
        XCTAssertEqual(requests.incoming.map(\.name), ["Руслан"])
        XCTAssertEqual(Stub.seen.first?.auth, "Bearer tok")
    }

    func testARefusalCarriesTheServersCodeAndAnEndedTokenIsRecognised() async {
        Stub.answers["POST /v1/register"] = (409, #"{"error":"nick_taken","message":"This nickname is taken."}"#)
        do {
            _ = try await client(token: nil).register(nick: "denis", password: "correct horse", name: nil, invite: nil)
            XCTFail("expected a refusal")
        } catch let error as FriendsAPIError {
            XCTAssertEqual(error, .server(code: "nick_taken", message: "This nickname is taken.", status: 409))
            XCTAssertFalse(error.isSignedOut)
        } catch { XCTFail("\(error)") }

        Stub.answers["GET /v1/me"] = (401, #"{"error":"unauthorized","message":"Sign in again."}"#)
        do {
            _ = try await client().me()
            XCTFail("expected a refusal")
        } catch let error as FriendsAPIError {
            XCTAssertTrue(error.isSignedOut)
        } catch { XCTFail("\(error)") }
    }

    // MARK: - Days

    /// A day goes up under the local day in the path, as the very text the builder wrote: that text is
    /// what the skip of an unchanged day compares.
    func testUploadPutsTheDayAsBuilt() async throws {
        Stub.answers["PUT /v1/me/days/2026-10-08"] = (204, "")
        let json = FriendsDayBuilder.json(FriendsDay(recovery: 81, strain: 38.6, sleepScore: 88))
        try await client().upload(json: json, on: "2026-10-08")
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(sent.method, "PUT")
        XCTAssertEqual(sent.body, #"{"recovery":81,"strain":38.6,"sleepScore":88}"#)
        XCTAssertEqual(Stub.contentTypes.first, "application/json")
    }

    /// A day goes up unless its text is the one the server last accepted; the phone keeps the text's
    /// SHA-256. The literal is Android's (`FriendsDayPayloadTest.theFingerprintIsSha256Hex`).
    func testAnUnchangedDayIsSkippedByItsFingerprint() {
        XCTAssertEqual(FriendsUploadPolicy.fingerprint("{}"),
                       "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
        XCTAssertNotEqual(FriendsUploadPolicy.fingerprint("{}"), FriendsUploadPolicy.fingerprint("{ }"))
        let json = FriendsDayBuilder.json(FriendsDay(recovery: 81))
        XCTAssertTrue(FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: nil, json: json))
        let accepted = FriendsUploadPolicy.fingerprint(json)
        XCTAssertFalse(FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: accepted, json: json))
        XCTAssertTrue(FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: accepted,
                                                       json: FriendsDayBuilder.json(FriendsDay(recovery: 82))))
    }

    /// A workout goes to a friend under its English label, whatever language this phone is in, with its
    /// active time and its stored Strain.
    func testAWorkoutRowIsSentUnderItsEnglishLabel() {
        func row(_ sport: String, duration: Double?) -> WorkoutRow {
            WorkoutRow(startTs: 1_791_530_400, endTs: 1_791_532_260, sport: sport, source: "my-whoop",
                       durationS: duration, energyKcal: 310.3, avgHr: 139, maxHr: 162, strain: 35.2,
                       distanceM: nil, zonesJSON: nil, notes: nil, steps: nil)
        }
        let strength = FriendsUploader.workout(row("TraditionalStrengthTraining", duration: 1_800))
        XCTAssertEqual(strength.sport, "Traditional Strength Training")
        XCTAssertEqual(strength.durationS, 1_800)
        XCTAssertEqual(strength.strain, 35.2)
        XCTAssertEqual(strength.kcal, 310.3)
        let detected = FriendsUploader.workout(row("detected", duration: nil))
        XCTAssertEqual(detected.sport, "Activity")
        XCTAssertEqual(detected.durationS, 1_860, "a missing duration falls back to the span")
    }

    /// The feed as the server writes it: nulls for members a phone did not send, no heart-rate line,
    /// and a friend whose section is switched off.
    func testTheFeedDecodes() async throws {
        Stub.answers["GET /v1/feed?days=7"] = (200, #"""
        {"serverTime": 1791540100,
         "me": {"nick": "denis", "name": "Денис", "avatarRev": 0,
                "share": {"scores": true, "sleep": true, "workouts": true, "hr": false},
                "days": [{"recovery": 58, "strain": 59.9, "sleepScore": 97, "day": "2026-10-08", "updatedAt": 1791540000}]},
         "friends": [{"nick": "ruslan", "name": "Руслан", "avatarRev": 2,
                      "share": {"scores": true, "sleep": false, "workouts": true, "hr": true},
                      "days": [{"recovery": 81, "strain": 38.6, "sleepScore": 88,
                                "workouts": [{"startTs": 1791530400, "sport": "Running", "durationS": 1860,
                                              "strain": 35.2, "avgHr": 139, "maxHr": null, "kcal": 310}],
                                "hr": {"lastBpm": 62, "lastTs": 1791540000, "restingBpm": null},
                                "day": "2026-10-08", "updatedAt": 1791539000}]}],
         "pendingIncoming": 1}
        """#)
        let feed = try await client().feed().feed
        XCTAssertEqual(feed.me.latestDay?.summary.recovery, 58)
        XCTAssertEqual(feed.pendingIncoming, 1)
        let ruslan = try XCTUnwrap(feed.friends.first)
        XCTAssertEqual(ruslan.share?.sleep, false)
        XCTAssertEqual(ruslan.latestDay?.day, "2026-10-08")
        XCTAssertEqual(ruslan.latestDay?.summary.workouts?.first?.kcal, 310)
        XCTAssertNil(ruslan.latestDay?.summary.workouts?.first?.maxHr)
        XCTAssertEqual(ruslan.latestDay?.summary.hr?.lastBpm, 62)
        XCTAssertNil(ruslan.latestDay?.summary.hr?.series, "the feed leaves the line out")
        XCTAssertEqual(ruslan.avatarRev, 2)
    }

    /// Only what a screen cannot stand without is required. A friend the app cannot read, a day with no
    /// day key and a member of the wrong kind are each left out without costing the rest of the answer.
    func testAnAnswerIsReadAroundWhatCannotBeRead() async throws {
        Stub.answers["GET /v1/feed?days=7"] = (200, #"""
        {"serverTime": 1791540100,
         "me": {"nick": "denis"},
         "friends": [{"name": "no nick"},
                     {"nick": "ruslan", "name": "", "avatarRev": "two", "relation": "enemy",
                      "days": [{"recovery": 70, "day": "2026-10-07"}, {"recovery": 81},
                               {"recovery": 90, "day": "2026-10-08", "updatedAt": null}]}]}
        """#)
        let feed = try await client().feed().feed
        XCTAssertEqual(feed.me.name, "denis", "a missing name reads as the nickname")
        XCTAssertNil(feed.me.share)
        XCTAssertEqual(feed.pendingIncoming, 0)
        XCTAssertEqual(feed.friends.map(\.nick), ["ruslan"])
        let ruslan = try XCTUnwrap(feed.friends.first)
        XCTAssertEqual(ruslan.name, "ruslan")
        XCTAssertEqual(ruslan.avatarRev, 0)
        XCTAssertNil(ruslan.relation)
        XCTAssertEqual(ruslan.days?.map(\.day), ["2026-10-08", "2026-10-07"], "newest first, the keyless day dropped")

        Stub.answers["GET /v1/me"] = (200, #"{"name": "nobody"}"#)
        do {
            _ = try await client().me()
            XCTFail("an account without a nickname is not an answer")
        } catch let error as FriendsAPIError {
            XCTAssertEqual(error, .transport("unexpected answer"))
        }
    }

    // MARK: - Pictures

    func testAPictureGoesUpAsAJpegAndOneTooLargeIsNotSent() async throws {
        Stub.answers["PUT /v1/me/avatar"] = (200, #"{"nick":"denis","name":"Денис","avatarRev":3}"#)
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0] + [UInt8](repeating: 0, count: 100))
        let me = try await client().putAvatar(jpeg)
        XCTAssertEqual(me.avatarRev, 3)
        XCTAssertEqual(Stub.seen.first?.method, "PUT")
        XCTAssertEqual(Stub.contentTypes.first, "image/jpeg")
        XCTAssertEqual(FriendsAvatars.fitForUpload(jpeg), jpeg, "a small JPEG goes up untouched")
        XCTAssertNil(FriendsAvatars.fitForUpload(Data("not a picture".utf8)))

        Stub.seen = []
        do {
            _ = try await client().putAvatar(Data(count: FriendsLimits.maxAvatarBytes + 1))
            XCTFail("expected a refusal")
        } catch let error as FriendsAPIError {
            XCTAssertEqual(error, .server(code: "too_large", message: "", status: 413))
        }
        XCTAssertTrue(Stub.seen.isEmpty, "the server would close the connection mid-upload")
    }

    func testAFriendsPictureIsReadWithTheTokenAndHeldToItsSize() async throws {
        Stub.answers["GET /v1/users/ruslan/avatar"] = (200, "picture bytes")
        let bytes = try await client().avatar("@Ruslan")
        XCTAssertEqual(bytes, Data("picture bytes".utf8))
        XCTAssertEqual(Stub.seen.first?.auth, "Bearer tok")

        Stub.answers["GET /v1/users/ruslan/avatar"] = (200, String(repeating: "x", count: FriendsLimits.maxAvatarBytes + 10))
        do {
            _ = try await client().avatar("ruslan")
            XCTFail("an answer past the limit is not kept")
        } catch let error as FriendsAPIError {
            XCTAssertEqual(error, .transport("unexpected answer"))
        }
    }

    // MARK: - Redirects

    /// A redirect is never followed, so the token and a password in a body go to the host the wearer
    /// named and nowhere else. The 3xx comes back as the refusal it is.
    func testARedirectIsAFailureNotAHop() async {
        Stub.redirects["GET /v1/me"] = "https://elsewhere.example/v1/me"
        Stub.answers["GET /v1/me"] = (200, #"{"nick":"mallory","name":"Mallory"}"#)
        do {
            _ = try await client().me()
            XCTFail("expected a refusal")
        } catch let error as FriendsAPIError {
            XCTAssertTrue(error.isRedirect, "\(error)")
            XCTAssertFalse(error.isSignedOut)
        } catch { XCTFail("\(error)") }
        XCTAssertEqual(Stub.seen.count, 1, "nothing was asked of the address the redirect named")
    }

    // MARK: - Signed out

    /// With no account on the phone the store sends nothing, whatever asks it to.
    @MainActor
    func testSignedOutNothingIsSent() async throws {
        let suite = "friends.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = FriendsStore(defaults: defaults, token: nil, cache: nil, keychain: false, session: stubbedSession())
        XCTAssertFalse(store.signedIn)
        XCTAssertEqual(store.serverAddress, "https://renoop.duckdns.org", "the fork's own server until another is named")
        await store.refresh()
        let anyone = await store.person("ruslan")
        XCTAssertNil(anyone)
        let picture = await store.avatarBytes(of: "ruslan")
        XCTAssertNil(picture)
        _ = await store.sendRequest(to: "ruslan")
        _ = await store.uploadProfilePhoto(Data([0xFF, 0xD8, 0xFF, 0xE0]))
        XCTAssertTrue(Stub.seen.isEmpty, "\(Stub.seen.map(\.path))")
        XCTAssertNil(store.errorText, "no account is not a failure to report")
    }

    /// A session the server no longer knows is forgotten here too, and with it everything the phone
    /// kept about the account; the address of the server stays.
    @MainActor
    func testAnEndedSessionIsForgottenWithEverythingKeptAboutIt() async throws {
        let suite = "friends.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("http://127.0.0.1:8787", forKey: FriendsStore.addressKey)
        defaults.set("denis", forKey: FriendsStore.nickKey)
        defaults.set("abc", forKey: FriendsStore.markPrefix + "2026-10-08")
        let store = FriendsStore(defaults: defaults, token: "tok", cache: nil, keychain: false, session: stubbedSession())
        XCTAssertTrue(store.signedIn)
        XCTAssertEqual(store.uploadMark("2026-10-08"), "abc")
        Stub.answers["GET /v1/feed?days=7"] = (401, #"{"error":"unauthorized","message":"Sign in again."}"#)
        Stub.answers["GET /v1/friends/requests"] = (401, #"{"error":"unauthorized","message":"Sign in again."}"#)
        await store.refresh()
        XCTAssertFalse(store.signedIn)
        XCTAssertNotNil(store.errorText)
        XCTAssertNil(store.uploadMark("2026-10-08"))
        XCTAssertNil(defaults.string(forKey: FriendsStore.nickKey))
        XCTAssertEqual(store.serverAddress, "http://127.0.0.1:8787")
        let asked = Stub.seen.count
        await store.refresh()
        XCTAssertEqual(Stub.seen.count, asked, "nothing more is sent once the session is gone")
    }

    /// Every "N min ago" counts from the server's clock, carried forward by the time since the feed
    /// arrived, so a phone whose own clock is wrong still reads the right age.
    func testTheServersClockIsCarriedForwardFromTheFeed() {
        XCTAssertEqual(FriendsClock.serverNow(serverTime: 1_000, fetchedAtDevice: 5_000, nowDevice: 5_090), 1_090)
        XCTAssertEqual(FriendsClock.serverNow(serverTime: 1_000, fetchedAtDevice: 5_000, nowDevice: 4_000), 1_000,
                       "a device clock set back counts as no time")
    }

    // MARK: - Names and addresses

    func testTheNameRuleIsTheServers() {
        XCTAssertEqual(FriendsNick.normalized(" @Denis_1 "), "denis_1")
        for good in ["abc", "denis", "a_b_c", "x1234567890123456789"] { XCTAssertTrue(FriendsNick.isValid(good), good) }
        for bad in ["ab", "has space", "денис", String(repeating: "x", count: 21), "a-b", ""] {
            XCTAssertFalse(FriendsNick.isValid(bad), bad)
        }
    }

    /// 8 to 128 characters, counted in Unicode scalars the way the server counts them.
    func testThePasswordRuleIsTheServers() {
        for good in ["12345678", String(repeating: "x", count: 128), "пароль123"] { XCTAssertTrue(FriendsPassword.isValid(good), good) }
        for bad in ["", "1234567", String(repeating: "x", count: 129)] { XCTAssertFalse(FriendsPassword.isValid(bad), bad) }
    }

    // MARK: - Server address
    //
    // The cases of Android's `FriendsErrorAndAddressTest`, so the two apps accept the same addresses.

    private func valid(_ raw: String) -> String? { FriendsServerAddress.normalized(raw) }

    private func problem(_ raw: String) -> FriendsServerAddress.Problem? {
        if case let .failure(problem) = FriendsServerAddress.validate(raw) { return problem }
        return nil
    }

    func testTheDefaultServerIsItselfValid() {
        XCTAssertEqual(valid(FriendsServerAddress.standard), FriendsServerAddress.standard)
        XCTAssertTrue(FriendsServerAddress.standard.hasPrefix("https://"))
    }

    func testHttpsIsAcceptedAndNormalised() {
        XCTAssertEqual(valid("  https://renoop.duckdns.org/  "), "https://renoop.duckdns.org")
        XCTAssertEqual(valid("HTTPS://ReNoop.DuckDNS.org"), "https://renoop.duckdns.org")
        XCTAssertEqual(valid("https://example.org:443"), "https://example.org")
        XCTAssertEqual(valid("https://example.org:8443/"), "https://example.org:8443")
        XCTAssertEqual(valid("https://example.org/friends/"), "https://example.org/friends")
    }

    func testAnAddressTypedWithoutASchemeIsReadAsHttps() {
        XCTAssertEqual(valid("renoop.duckdns.org"), "https://renoop.duckdns.org")
        XCTAssertEqual(valid("example.org:8443"), "https://example.org:8443")
    }

    func testPlainHttpIsRefusedEverywhereButLoopback() {
        for refused in ["http://renoop.duckdns.org", "http://192.168.1.10:8787", "http://10.0.2.2:8787",
                        "http://localhost.example.org", "http://127.0.0.1.example.org", "http://127.0.0.2:8787",
                        "ftp://example.org"] {
            XCTAssertEqual(problem(refused), .notHTTPS, refused)
        }
    }

    func testPlainHttpToThisDevicesLoopbackIsAcceptedForDevelopment() {
        XCTAssertEqual(valid("http://localhost:8787"), "http://localhost:8787")
        XCTAssertEqual(valid("http://127.0.0.1:8787/"), "http://127.0.0.1:8787")
        XCTAssertEqual(valid("http://LOCALHOST:80"), "http://localhost")
        XCTAssertEqual(valid("https://localhost:8443"), "https://localhost:8443")
    }

    func testCredentialsQueriesAndNonsenseAreRefused() {
        XCTAssertEqual(problem("https://user:pass@example.org"), .hasCredentials)
        XCTAssertEqual(problem("https://example.org/?token=1"), .hasQuery)
        XCTAssertEqual(problem("https://example.org/#top"), .hasQuery)
        XCTAssertEqual(problem("   "), .empty)
        XCTAssertEqual(problem("https://"), .malformed)
        XCTAssertEqual(problem("https://exa mple.org"), .malformed)
        XCTAssertEqual(problem("https://example.org:99999"), .malformed)
        // A name outside ASCII is refused rather than guessed at; its punycode form is accepted.
        XCTAssertEqual(problem("https://домен.рф"), .malformed)
        XCTAssertEqual(valid("https://xn--d1acufc.xn--p1ai"), "https://xn--d1acufc.xn--p1ai")
        // An address typed on the wrong keyboard is not one.
        XCTAssertEqual(problem("реезЖ//127ю0ю0ю1Ж8787"), .malformed)
    }

    func testAClientAddressExistsOnlyForAnAcceptedAddress() {
        XCTAssertEqual(FriendsServerAddress.baseURL("https://renoop.duckdns.org")?.absoluteString, "https://renoop.duckdns.org")
        XCTAssertNotNil(FriendsServerAddress.baseURL("http://127.0.0.1:8787"))
        XCTAssertNil(FriendsServerAddress.baseURL("http://renoop.duckdns.org"))
        XCTAssertNil(FriendsServerAddress.baseURL(""))
    }

    func testInitialsAndTheLocalDay() {
        XCTAssertEqual(FriendsFormat.initials("Анна Ковалёва"), "АК")
        XCTAssertEqual(FriendsFormat.initials("denis"), "D")
        XCTAssertEqual(FriendsFormat.initials("anna_k"), "AK")
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let bounds = FriendsUploader.dayBounds("2026-10-08", calendar: utc)
        XCTAssertEqual(bounds?.start, 1_791_417_600)
        XCTAssertEqual(bounds.map { $0.end - $0.start }, 86_400)
        XCTAssertNil(FriendsUploader.dayBounds("not-a-day", calendar: utc))
    }
}
