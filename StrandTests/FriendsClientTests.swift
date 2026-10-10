import CryptoKit
import XCTest
import Foundation
import StrandAnalytics
import WhoopStore
@testable import Strand

/// The Friends client against the contract in `friends-server/README.md` (API version 2): what it
/// signs and sends, what it reads, and how Friends on this phone moves between its states.
final class FriendsClientTests: XCTestCase {

    /// Answers every request from a canned table and records what was asked.
    private final class Stub: URLProtocol {
        struct Seen {
            let method: String
            let path: String
            let headers: [String: String]
            let body: Data
        }

        /// Answers by "METHOD path". Each request takes the next answer of its list; the last one repeats.
        nonisolated(unsafe) static var answers: [String: [(status: Int, body: String)]] = [:]
        /// Requests answered with a redirect to the address given, keyed like `answers`.
        nonisolated(unsafe) static var redirects: [String: String] = [:]
        nonisolated(unsafe) static var seen: [Seen] = []

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
            let key = method + " " + path
            Self.seen.append(Seen(method: method, path: path, headers: request.allHTTPHeaderFields ?? [:], body: body))
            if let target = Self.redirects[key], let location = URL(string: target) {
                let hop = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1",
                                          headerFields: ["Location": target])!
                // A client that follows it asks `location` next, with the signature still on the request;
                // one that refuses is left with the 302 as its answer.
                client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: location), redirectResponse: hop)
                client?.urlProtocol(self, didReceive: hop, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self)
                return
            }
            var list = Self.answers[key] ?? [(404, #"{"error":"not_found","message":""}"#)]
            let answer = list.count > 1 ? list.removeFirst() : list[0]
            if Self.answers[key] != nil { Self.answers[key] = list }
            let response = HTTPURLResponse(url: url, statusCode: answer.status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(answer.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    /// A fixed software key, so no test touches the Keychain or the Secure Enclave.
    private struct TestSigner: FriendsSigner, @unchecked Sendable {
        let key = P256.Signing.PrivateKey()
        var publicKeySPKI: Data { key.publicKey.derRepresentation }
        var keyID: String { FriendsKey.hex(SHA256.hash(data: publicKeySPKI)) }
        func sign(_ message: Data) throws -> Data { try key.signature(for: message).derRepresentation }
    }

    private static let base = "https://friends.example"
    private static let fixedNow = 1_791_540_000
    private static let fixedNonce = "AAAAAAAAAAAAAAAAAAAAAA"
    private static let annaID = "00000000000000aa"
    private static let me = #"{"id":"00000000000000aa","name":"Anna","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false},"strapBound":true,"device":{"id":"k","probationUntil":null}}"#
    /// The account with every sharing switch off, so an upload run reads nothing out of the app's data.
    private static let meSharingNothing = #"{"id":"00000000000000aa","name":"Anna","avatarRev":0,"share":{"scores":false,"sleep":false,"workouts":false,"hr":false},"strapBound":true,"device":{"id":"k","probationUntil":null}}"#
    private static let emptyFeed = #"{"serverTime":1791540000,"me":\#(me),"friends":[],"claims":[],"strapClaim":null}"#
    private static let handle = "98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"

    private func stubbedSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return URLSession(configuration: config)
    }

    private func client(_ signer: (any FriendsSigner)? = TestSigner(), offset: FriendsClockOffset = FriendsClockOffset()) -> FriendsClient {
        var c = FriendsClient(baseURL: URL(string: Self.base)!, signer: signer, session: stubbedSession())
        c.offset = offset
        c.now = { Self.fixedNow }
        c.nonce = { Self.fixedNonce }
        return c
    }

    /// Whether `seen` carries a signature by `signer` over exactly this method, target, time and body.
    private func isSigned(_ seen: Stub.Seen, by signer: TestSigner, target: String, time: Int = FriendsClientTests.fixedNow) throws -> Bool {
        guard seen.headers["X-Friends-Key"] == signer.keyID, seen.headers["X-Friends-Time"] == String(time),
              seen.headers["X-Friends-Nonce"] == Self.fixedNonce,
              let signature = seen.headers["X-Friends-Signature"].flatMap({ Data(base64Encoded: $0) }) else { return false }
        let text = FriendsWire.signingString(method: seen.method, target: target, time: time, nonce: Self.fixedNonce,
                                             bodyHashHex: FriendsKey.hex(SHA256.hash(data: seen.body)))
        return try P256.Signing.PublicKey(derRepresentation: signer.publicKeySPKI)
            .isValidSignature(P256.Signing.ECDSASignature(derRepresentation: signature), for: Data(text.utf8))
    }

    override func setUp() {
        super.setUp()
        Self.restoreHostProfile()
        Stub.answers = [:]
        Stub.redirects = [:]
        Stub.seen = []
    }

    // MARK: - Signing

    /// The README's vector, as this client produces it: the same method, target, time, nonce and body.
    func testARequestIsSignedOverItsMethodTargetTimeNonceAndBody() async throws {
        let signer = TestSigner()
        Stub.answers["PUT /v2/me/days/2026-10-10"] = [(204, "")]
        try await client(signer).upload(json: #"{"recovery":81}"#, on: "2026-10-10")
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(String(decoding: sent.body, as: UTF8.self), #"{"recovery":81}"#)
        XCTAssertEqual(FriendsKey.hex(SHA256.hash(data: sent.body)),
                       "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445")
        XCTAssertTrue(try isSigned(sent, by: signer, target: "/v2/me/days/2026-10-10"))
        XCTAssertNil(sent.headers["Authorization"], "there is no bearer token any more")
    }

    func testTheQueryIsPartOfWhatIsSigned() async throws {
        let signer = TestSigner()
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        _ = try await client(signer).feed()
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertTrue(try isSigned(sent, by: signer, target: "/v2/feed?days=7"))
        XCTAssertFalse(try isSigned(sent, by: signer, target: "/v2/feed"))
    }

    func testInfoIsTheOneCallThatIsNotSigned() async throws {
        Stub.answers["GET /v2/info"] = [(200, #"{"name":"renoop-friends","api":2,"time":1791540000}"#)]
        let info = try await client(nil).info()
        XCTAssertEqual(info, FriendsServerInfo(name: "renoop-friends", api: 2, time: 1_791_540_000))
        XCTAssertNil(Stub.seen.first?.headers["X-Friends-Signature"])
    }

    func testWithoutAKeyNothingElseIsSent() async {
        do {
            _ = try await client(nil).me()
            XCTFail("a call that needs the key was made without one")
        } catch {
            XCTAssertEqual(error as? FriendsAPIError, .notEnrolled)
        }
        XCTAssertTrue(Stub.seen.isEmpty)
    }

    /// A phone whose clock is wrong is told the server's, signs once more by it, and remembers the gap.
    func testAWrongClockSignsOnceMoreByTheServers() async throws {
        let signer = TestSigner()
        let offset = FriendsClockOffset()
        Stub.answers["GET /v2/me"] = [(401, #"{"error":"clock_skew","message":"","serverTime":1791545000}"#), (200, Self.me)]
        let me = try await client(signer, offset: offset).me()
        XCTAssertEqual(me.id, Self.annaID)
        XCTAssertEqual(Stub.seen.count, 2)
        XCTAssertTrue(try isSigned(Stub.seen[1], by: signer, target: "/v2/me", time: 1_791_545_000))
        XCTAssertEqual(offset.seconds, 5_000)
    }

    func testAClockTheServerRefusesTwiceIsTheAnswer() async {
        Stub.answers["GET /v2/me"] = [(401, #"{"error":"clock_skew","message":"","serverTime":1791545000}"#)]
        do {
            _ = try await client().me()
            XCTFail("a second refusal was retried")
        } catch {
            XCTAssertEqual(error as? FriendsAPIError, .clockSkew(serverTime: 1_791_545_000))
        }
        XCTAssertEqual(Stub.seen.count, 2, "once, and once more")
    }

    // MARK: - Calls

    func testEnrollingSendsTheKeyTheNameThePlatformAndTheStrap() async throws {
        let signer = TestSigner()
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#), (200, #"{"me":\#(Self.me)}"#)]
        let (me, isNew) = try await client(signer).enroll(name: "Anna", strap: Self.handle)
        XCTAssertTrue(isNew, "201 is an account made now")
        XCTAssertEqual(me.strapBound, true)
        XCTAssertNil(me.device?.probationUntil)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[0].body) as? [String: String])
        XCTAssertEqual(body, ["key": signer.publicKeySPKI.base64EncodedString(), "name": "Anna",
                              "platform": FriendsPlatform.current, "strap": Self.handle])
        XCTAssertTrue(try isSigned(Stub.seen[0], by: signer, target: "/v2/enroll"))

        let again = try await client(signer).enroll(name: "Anna", strap: nil)
        XCTAssertFalse(again.isNew, "200 is the account the key already had")
        XCTAssertEqual(again.me.id, Self.annaID)
        let unbound = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[1].body) as? [String: String])
        XCTAssertNil(unbound["strap"], "no strap is no member, not a null")
    }

    func testARefusalCarriesTheServersCode() async {
        for (status, code, check) in [
            (401, "unknown_key", \FriendsAPIError.isUnknownKey), (409, "strap_bound", \.isStrapBound),
            (403, "probation", \.isProbation), (404, "no_claim", \.isNoClaim), (404, "strap_free", \.isStrapFree),
            (409, "already_enrolled", \.isAlreadyEnrolled),
        ] as [(Int, String, KeyPath<FriendsAPIError, Bool>)] {
            Stub.answers["GET /v2/me"] = [(status, #"{"error":"\#(code)","message":""}"#)]
            do {
                _ = try await client().me()
                XCTFail(code)
            } catch {
                XCTAssertEqual(error as? FriendsAPIError, .server(code: code, message: "", status: status))
                XCTAssertEqual((error as? FriendsAPIError)?[keyPath: check], true, code)
            }
        }
        XCTAssertFalse(FriendsAPIError.server(code: "unknown_key", message: "", status: 500).isUnknownKey)
    }

    func testBindingAStrapIsDoneOrAskedOfItsOwner() async throws {
        Stub.answers["PUT /v2/me/strap"] = [
            (200, #"{"bound":true}"#),
            (202, #"{"claim":{"id":7,"kind":"take","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}}"#),
        ]
        let first = try await client().putStrap(Self.handle)
        XCTAssertEqual(first, .bound)
        let second = try await client().putStrap(Self.handle)
        XCTAssertEqual(second, .claimed(FriendsClaim(id: 7, kind: .take, code: "481902", state: .pending, platform: "ios",
                                                     createdAt: 1_791_540_000, maturesAt: 1_791_712_800)))
        XCTAssertEqual(String(decoding: Stub.seen[0].body, as: UTF8.self), #"{"strap":"\#(Self.handle)"}"#)
    }

    func testAnInviteIsMadeListedAndUsed() async throws {
        Stub.answers["POST /v2/invites"] = [(201, #"{"id":"0123456789abcdef","code":"K7QM2-XRD4P","expiresAt":1792144800}"#)]
        Stub.answers["GET /v2/invites"] = [(200, #"{"invites":[{"id":"0123456789abcdef","createdAt":1791540000,"expiresAt":1792144800}]}"#)]
        Stub.answers["POST /v2/invites/redeem"] = [(200, #"{"friend":{"id":"00000000000000aa","name":"Anna","avatarRev":2}}"#)]
        Stub.answers["DELETE /v2/invites/0123456789abcdef"] = [(204, "")]
        let made = try await client().createInvite()
        XCTAssertEqual(made.code, "K7QM2-XRD4P")
        let listed = try await client().invites()
        XCTAssertEqual(listed.map(\.id), ["0123456789abcdef"])
        XCTAssertNil(listed[0].code, "a listing never repeats a code")
        let friend = try await client().redeem("K7QM2XRD4P")
        XCTAssertEqual(friend, FriendProfile(id: Self.annaID, name: "Anna", avatarRev: 2))
        XCTAssertEqual(String(decoding: Stub.seen[2].body, as: UTF8.self), #"{"code":"K7QM2XRD4P"}"#)
        try await client().revokeInvite("0123456789abcdef")
        XCTAssertEqual(Stub.seen[3].method, "DELETE")
    }

    /// An id goes into a path only when it has the shape of one.
    func testWhatIsNotAnIdNeverReachesAPath() async {
        for id in ["../me", "anna", "00000000000000AA", ""] {
            do {
                _ = try await client().person(id)
                XCTFail(id)
            } catch {
                XCTAssertEqual(error as? FriendsAPIError, .server(code: "no_such_user", message: "", status: 404))
            }
        }
        XCTAssertTrue(Stub.seen.isEmpty)
        XCTAssertTrue(FriendsID.isValid(Self.annaID))
    }

    // MARK: - Days

    /// A day goes up unless its text is the one the server last accepted; the phone keeps the text's
    /// SHA-256. The literal is Android's (`FriendsDayPayloadTest.theFingerprintIsSha256Hex`).
    func testAnUnchangedDayIsSkippedByItsFingerprint() {
        XCTAssertEqual(FriendsUploadPolicy.fingerprint("{}"),
                       "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
        XCTAssertEqual(FriendsUploadPolicy.fingerprint(Data("{}".utf8)), FriendsUploadPolicy.fingerprint("{}"))
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

    /// The feed as the server writes it: a friend with a day, the requests that wait for an answer, and
    /// around what cannot be read (a friend without an id, a claim of an unknown kind) instead of failing.
    func testTheFeedDecodesAroundWhatCannotBeRead() async throws {
        let claim = #"{"id":3,"kind":"join","code":"481902","state":"pending","platform":"android","createdAt":1791540000,"maturesAt":1791712800}"#
        Stub.answers["GET /v2/feed?days=7"] = [(200, """
        {"serverTime":1791540000,"me":\(Self.me),
         "friends":[{"id":"00000000000000bb","name":"Max","avatarRev":3,
                     "share":{"scores":true,"sleep":false,"workouts":true,"hr":false},
                     "days":[{"day":"2026-10-09","updatedAt":1791500000,"recovery":64,"strain":null},
                             {"day":"2026-10-10","updatedAt":1791539000,"recovery":81,"strain":38.6,"sleepScore":88},
                             {"day":"yesterday","recovery":1}]},
                    {"name":"No id"},{"id":"not-an-id","name":"Bad id"}],
         "claims":[\(claim),{"id":4,"kind":"sideways","code":"1","state":"pending","createdAt":1}],
         "strapClaim":null,
         "unconfirmed":[{"id":"k2","platform":"android","addedAt":1791500000,"lastSeenAt":1791539000,"probationUntil":1792104800,"current":false}]}
        """)]
        let (feed, raw) = try await client().feed()
        XCTAssertFalse(raw.isEmpty)
        XCTAssertEqual(feed.serverTime, 1_791_540_000)
        XCTAssertEqual(feed.me.id, Self.annaID)
        XCTAssertEqual(feed.friends.map(\.id), ["00000000000000bb"])
        XCTAssertEqual(feed.friends[0].days?.map(\.day), ["2026-10-10", "2026-10-09"], "newest first, unreadable dropped")
        XCTAssertEqual(feed.friends[0].latestDay?.summary.recovery, 81)
        XCTAssertEqual(feed.friends[0].share?.sleep, false)
        XCTAssertEqual(feed.claims.map(\.id), [3])
        XCTAssertEqual(feed.claims[0].kind, .join)
        XCTAssertNil(feed.strapClaim)
        XCTAssertEqual(feed.unconfirmed.map(\.platform), ["android"])
        let bare: FriendsFeed = try FriendsClient.decode(Data(Self.emptyFeed.utf8))
        XCTAssertEqual(bare.unconfirmed, [], "a feed without the member reads as none")
    }

    func testAPictureGoesUpAsAJpegAndOneTooLargeIsNotSent() async throws {
        Stub.answers["PUT /v2/me/avatar"] = [(200, Self.me)]
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(repeating: 0x30, count: 64)
        _ = try await client().putAvatar(jpeg)
        XCTAssertEqual(Stub.seen[0].headers["Content-Type"], "image/jpeg")
        XCTAssertEqual(Stub.seen[0].body, jpeg)
        do {
            _ = try await client().putAvatar(Data(repeating: 0xFF, count: FriendsLimits.maxAvatarBytes + 1))
            XCTFail("a picture over the limit was sent")
        } catch {
            XCTAssertEqual(error as? FriendsAPIError, .server(code: "too_large", message: "", status: 413))
        }
        XCTAssertEqual(Stub.seen.count, 1)
    }

    /// A redirect is the answer, not a hop: a signed request goes to the host the wearer named only.
    func testARedirectIsAFailureNotAHop() async {
        Stub.redirects["GET /v2/me"] = "https://elsewhere.example/v2/me"
        do {
            _ = try await client().me()
            XCTFail("the redirect was followed")
        } catch {
            XCTAssertEqual((error as? FriendsAPIError)?.isRedirect, true)
        }
        XCTAssertEqual(Stub.seen.count, 1)
    }

    // MARK: - The store

    @MainActor
    private func store(_ identity: FriendsStrap.Identity, signer: (any FriendsSigner)? = TestSigner(),
                       defaults: UserDefaults? = nil,
                       keys: FriendsMemoryKeyStorage = FriendsMemoryKeyStorage()) throws -> (FriendsStore, UserDefaults) {
        let defaults = try defaults ?? XCTUnwrap(UserDefaults(suiteName: "friends-store-\(UUID().uuidString)"))
        defaults.set(Self.base, forKey: FriendsStore.addressKey)
        let store = FriendsStore(defaults: defaults, cache: nil, keys: keys, session: stubbedSession(), signer: signer)
        store.strapIdentity = { identity }
        return (store, defaults)
    }

    @MainActor
    func testOffNothingIsSentAndNoKeyIsMade() async throws {
        let keys = FriendsMemoryKeyStorage()
        let (store, _) = try store(.handle(Self.handle), signer: nil, keys: keys)
        XCTAssertEqual(store.phase, .off)
        await store.refresh()
        let repo = Repository(deviceId: "test-friends")
        let profile = ProfileStore()
        await store.sync(repo: repo, profile: profile, force: true)
        store.daysChanged(repo: repo, profile: profile)
        _ = await store.person(Self.annaID)
        XCTAssertTrue(Stub.seen.isEmpty)
        XCTAssertNil(store.deviceID)
        XCTAssertNil(keys.read(account: FriendsKey.account(forServer: Self.base)))
    }

    @MainActor
    func testTurningOnEnrolsWithTheStrapAndGoesOn() async throws {
        let (store, defaults) = try store(.handle(Self.handle))
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.turnOn(name: "  Anna ")
        XCTAssertEqual(store.phase, .on)
        XCTAssertEqual(store.myID, Self.annaID)
        XCTAssertNil(store.errorText)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[0].body) as? [String: String])
        XCTAssertEqual(body["name"], "Anna")
        XCTAssertEqual(body["strap"], Self.handle)
        XCTAssertEqual(defaults.string(forKey: FriendsStore.phaseKey), "on")
        XCTAssertNil(defaults.string(forKey: FriendsStore.boundHandleKey),
                     "an enrolment records no strap: the upload run's own request does")
        XCTAssertEqual(defaults.string(forKey: FriendsStore.pushedNameKey), "Anna", "the name just sent is not sent again")
        XCTAssertFalse(defaults.bool(forKey: FriendsStore.adoptProfileKey))
    }

    /// The key was already a phone of an account (a reinstall keeps the Keychain item): the server
    /// answers that account and takes neither the name nor the strap sent. Nothing is recorded as
    /// accepted, and the phone joins as any other does, without pushing its profile.
    @MainActor
    func testAnEnrolmentThatFindsAnExistingAccountRecordsNoStrapAndPushesNoProfile() async throws {
        let (store, defaults) = try store(.handle(Self.handle))
        Stub.answers["POST /v2/enroll"] = [(200, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.turnOn(name: "Other")
        XCTAssertEqual(store.phase, .on)
        XCTAssertNil(store.errorText)
        XCTAssertNil(defaults.string(forKey: FriendsStore.boundHandleKey))
        XCTAssertNil(defaults.string(forKey: FriendsStore.pushedNameKey), "the name sent was not taken")
        XCTAssertTrue(defaults.bool(forKey: FriendsStore.adoptProfileKey))
    }

    @MainActor
    func testWithoutANameNothingIsSent() async throws {
        let (store, _) = try store(.handle(Self.handle))
        await store.turnOn(name: "   ")
        XCTAssertEqual(store.phase, .off)
        XCTAssertNotNil(store.errorText)
        XCTAssertTrue(Stub.seen.isEmpty)
    }

    @MainActor
    func testAStrapNotReadYetWaitsAndSendsNothing() async throws {
        let (store, defaults) = try store(.pending)
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .waitingForStrap)
        XCTAssertTrue(Stub.seen.isEmpty)
        XCTAssertEqual(defaults.string(forKey: FriendsStore.phaseKey), "waitingForStrap")
        // The strap is read: the same tap finishes by itself.
        store.strapIdentity = { .handle(Self.handle) }
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on)
    }

    @MainActor
    func testAStrapWithAnAccountAsksWhoseItIsAndAClaimSurvivesARelaunch() async throws {
        let signer = TestSigner()
        let (store, defaults) = try store(.handle(Self.handle), signer: signer)
        Stub.answers["POST /v2/enroll"] = [(409, #"{"error":"strap_bound","message":""}"#)]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .strapBound)
        XCTAssertNil(store.errorText, "a strap with an account is a question, not a failure")

        let claim = #"{"id":3,"kind":"join","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}"#
        Stub.answers["POST /v2/claims"] = [(201, #"{"claim":\#(claim)}"#)]
        await store.claimAccount(name: "Anna")
        guard case let .waiting(waiting) = store.phase else { return XCTFail("not waiting") }
        XCTAssertEqual(waiting.code, "481902")
        XCTAssertEqual(waiting.maturesAt, 1_791_712_800)

        let (relaunched, _) = try self.store(.handle(Self.handle), signer: signer, defaults: defaults)
        XCTAssertEqual(relaunched.phase, .waiting(waiting))

        Stub.answers["DELETE /v2/claims/mine"] = [(204, "")]
        await relaunched.cancelClaim()
        XCTAssertEqual(relaunched.phase, .strapBound)
    }

    /// A phone that joins an account does not send its own profile: what the account shows stands.
    @MainActor
    func testAGrantedClaimJoinsWithoutPushingTheProfile() async throws {
        let signer = TestSigner()
        let (store, defaults) = try store(.handle(Self.handle), signer: signer)
        let claim = { (state: String) in
            #"{"claim":{"id":3,"kind":"join","code":"481902","state":"\#(state)","platform":"ios","createdAt":1791540000,"maturesAt":null}}"#
        }
        Stub.answers["POST /v2/enroll"] = [(409, #"{"error":"strap_bound","message":""}"#)]
        Stub.answers["POST /v2/claims"] = [(201, claim("pending"))]
        await store.turnOn(name: "Other")
        await store.claimAccount(name: "Other")
        Stub.answers["GET /v2/claims/mine"] = [(200, claim("pending")), (200, claim("approved"))]
        Stub.answers["GET /v2/me"] = [(200, Self.meSharingNothing)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.pollClaim()
        guard case .waiting = store.phase else { return XCTFail("left waiting on a pending claim") }
        await store.pollClaim()
        XCTAssertEqual(store.phase, .on)
        XCTAssertTrue(defaults.bool(forKey: FriendsStore.adoptProfileKey))
        XCTAssertNil(defaults.string(forKey: FriendsStore.pushedNameKey))
        // The upload run is where a profile would be sent: with a name and a photo of its own, this
        // phone still sends neither, and records them as the ones it last sent.
        Stub.answers["PUT /v2/me/strap"] = [(200, #"{"bound":true}"#)]
        Stub.answers["PATCH /v2/me"] = [(200, Self.me)]
        Stub.answers["PUT /v2/me/avatar"] = [(200, Self.me)]
        await store.uploadRecentDays(repo: Repository(deviceId: "test-friends"), profile: profile(name: "Other", photo: Self.jpeg))
        XCTAssertEqual(sent("GET", "/v2/me").count, 2, "the run reached the server")
        XCTAssertTrue(sent("PATCH", "/v2/me").isEmpty)
        XCTAssertTrue(sent("PUT", "/v2/me/avatar").isEmpty)
        XCTAssertFalse(defaults.bool(forKey: FriendsStore.adoptProfileKey))
        XCTAssertEqual(defaults.string(forKey: FriendsStore.pushedNameKey), "Other")
    }

    @MainActor
    func testADeclinedClaimGoesBackToTheChoice() async throws {
        let (store, _) = try store(.handle(Self.handle))
        Stub.answers["POST /v2/enroll"] = [(409, #"{"error":"strap_bound","message":""}"#)]
        Stub.answers["POST /v2/claims"] = [(201, #"{"claim":{"id":3,"kind":"join","code":"481902","state":"pending","createdAt":1}}"#)]
        Stub.answers["GET /v2/claims/mine"] = [(200, #"{"claim":{"id":3,"kind":"join","code":"481902","state":"declined","createdAt":1}}"#)]
        await store.turnOn(name: "Anna")
        await store.claimAccount(name: "Anna")
        await store.pollClaim()
        XCTAssertEqual(store.phase, .strapBound)
        XCTAssertNotNil(store.errorText)
    }

    /// A phone removed from the account by another one goes off, and keeps its key so that turning
    /// Friends on again asks to rejoin as the same phone.
    @MainActor
    func testAPhoneTheServerNoLongerKnowsGoesOffAndKeepsItsKey() async throws {
        let (store, defaults) = try store(.handle(Self.handle))
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed), (401, #"{"error":"unknown_key","message":""}"#)]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on)
        let epoch = store.epoch
        await store.refresh()
        XCTAssertEqual(store.phase, .off)
        XCTAssertNotNil(store.errorText)
        XCTAssertNotNil(store.deviceID)
        XCTAssertNil(store.feed)
        XCTAssertGreaterThan(store.epoch, epoch)
        XCTAssertNil(defaults.string(forKey: FriendsStore.boundHandleKey))
    }

    @MainActor
    func testDeletingTheAccountForgetsTheKey() async throws {
        let keys = FriendsMemoryKeyStorage()
        let (store, _) = try store(.none, signer: nil, keys: keys)
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        Stub.answers["POST /v2/me/delete"] = [(204, "")]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on)
        XCTAssertNotNil(keys.read(account: FriendsKey.account(forServer: Self.base)), "the key is made by turning on")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[0].body) as? [String: String])
        XCTAssertNil(body["strap"], "a device with no serial enrols unbound")
        let deleted = await store.deleteAccount()
        XCTAssertTrue(deleted)
        XCTAssertEqual(store.phase, .off)
        XCTAssertNil(store.deviceID)
        XCTAssertNil(keys.read(account: FriendsKey.account(forServer: Self.base)))
    }

    // MARK: - The kept key

    /// A store whose key is kept in `keys` and whose first read of it fails, as in a process the system
    /// started before the device's first unlock. `phase` is what was kept between launches.
    @MainActor
    private func storeThatCouldNotReadItsKey(phase: String?, identity: FriendsStrap.Identity = .none) throws
        -> (store: FriendsStore, defaults: UserDefaults, keys: FriendsMemoryKeyStorage, kept: FriendsKey) {
        let keys = FriendsMemoryKeyStorage()
        let kept = try FriendsKey.create(server: Self.base, storage: keys, enclave: false)
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "friends-store-\(UUID().uuidString)"))
        if let phase { defaults.set(phase, forKey: FriendsStore.phaseKey) }
        keys.failsReads = true
        let (store, _) = try store(identity, signer: nil, defaults: defaults, keys: keys)
        return (store, defaults, keys, kept)
    }

    /// Friends looks off while the key cannot be read, with the kept phase left alone, and comes back
    /// by itself once it can: the same key, nothing written in its place.
    @MainActor
    func testAKeyThatCouldNotBeReadComesBackWithItsPhase() async throws {
        let (store, defaults, keys, kept) = try storeThatCouldNotReadItsKey(phase: "on")
        XCTAssertEqual(store.phase, .off)
        XCTAssertNil(store.deviceID)
        let repo = Repository(deviceId: "test-friends")
        let profile = ProfileStore()
        await store.sync(repo: repo, profile: profile, force: false)
        XCTAssertEqual(store.phase, .off, "still unreadable: nothing changes")
        XCTAssertEqual(defaults.string(forKey: FriendsStore.phaseKey), "on", "the kept phase is not written over")
        XCTAssertTrue(Stub.seen.isEmpty)

        keys.failsReads = false
        Stub.answers["GET /v2/me"] = [(200, Self.meSharingNothing)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.sync(repo: repo, profile: profile, force: false)
        XCTAssertEqual(store.phase, .on)
        XCTAssertEqual(store.deviceID, kept.keyID)
        XCTAssertEqual(keys.writeCount, 1, "the one write that made the key: nothing replaced it")
        XCTAssertEqual(Stub.seen.first?.headers["X-Friends-Key"], kept.keyID)
        XCTAssertFalse(Stub.seen.contains { $0.path == "/v2/enroll" })
    }

    /// A claim in flight comes back the same way, with its code.
    @MainActor
    func testAKeyThatCouldNotBeReadComesBackWithItsClaim() async throws {
        let (store, defaults, keys, kept) = try storeThatCouldNotReadItsKey(phase: "waiting")
        let claim = FriendsClaim(id: 3, kind: .join, code: "481902", state: .pending, platform: "ios",
                                 createdAt: 1_791_540_000, maturesAt: 1_791_712_800)
        defaults.set(try JSONEncoder().encode(claim), forKey: FriendsStore.claimKey)
        XCTAssertEqual(store.phase, .off)
        keys.failsReads = false
        await store.refresh()
        XCTAssertEqual(store.phase, .waiting(claim))
        XCTAssertEqual(store.deviceID, kept.keyID)
        XCTAssertEqual(keys.writeCount, 1)
    }

    /// Turn On is the one button the page offers while Friends looks off. With the key out of reach it
    /// writes nothing, sends nothing and leaves the phase alone, whatever the strap says.
    @MainActor
    func testTurningOnNeverReplacesAKeyThatCannotBeRead() async throws {
        for identity in [FriendsStrap.Identity.pending, .handle(Self.handle), .none] {
            let (store, defaults, keys, _) = try storeThatCouldNotReadItsKey(phase: "on", identity: identity)
            await store.turnOn(name: "Anna")
            XCTAssertEqual(keys.writeCount, 1, "nothing was written over the kept key")
            XCTAssertTrue(Stub.seen.isEmpty)
            XCTAssertEqual(store.errorText, FriendsStore.message(for: FriendsKeyError.notRead))
            XCTAssertEqual(store.phase, .off)
            XCTAssertEqual(defaults.string(forKey: FriendsStore.phaseKey), "on")
            store.errorText = nil
            await store.turnOnWithoutStrap(name: "Anna")
            XCTAssertEqual(keys.writeCount, 1)
            XCTAssertTrue(Stub.seen.isEmpty)
            XCTAssertNotNil(store.errorText)
            XCTAssertEqual(defaults.string(forKey: FriendsStore.phaseKey), "on")
        }
    }

    /// The key is read again before one is made. A tap on Turn On once it is readable finds Friends as
    /// it was, and a phone that was not on enrols with the key it kept.
    @MainActor
    func testTurningOnReadsTheKeyAgainBeforeMakingOne() async throws {
        let (store, _, keys, kept) = try storeThatCouldNotReadItsKey(phase: "on")
        keys.failsReads = false
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on, "Friends was on all along")
        XCTAssertEqual(store.deviceID, kept.keyID)
        XCTAssertEqual(keys.writeCount, 1)
        XCTAssertFalse(Stub.seen.contains { $0.path == "/v2/enroll" })

        Stub.seen = []
        let (fresh, _, freshKeys, freshKept) = try storeThatCouldNotReadItsKey(phase: nil)
        freshKeys.failsReads = false
        Stub.answers["POST /v2/enroll"] = [(200, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await fresh.turnOn(name: "Anna")
        XCTAssertEqual(fresh.phase, .on)
        XCTAssertEqual(freshKeys.writeCount, 1, "the kept key was used, not replaced")
        XCTAssertEqual(Stub.seen.first?.path, "/v2/enroll")
        XCTAssertEqual(Stub.seen.first?.headers["X-Friends-Key"], freshKept.keyID)
    }

    /// With nothing kept, Turn On makes the key and enrols with it.
    @MainActor
    func testTurningOnWithNoKeyKeptMakesOneAndEnrols() async throws {
        let keys = FriendsMemoryKeyStorage()
        let (store, _) = try store(.none, signer: nil, keys: keys)
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on)
        XCTAssertEqual(keys.writeCount, 1)
        XCTAssertEqual(Stub.seen.first?.path, "/v2/enroll")
        XCTAssertEqual(Stub.seen.first?.headers["X-Friends-Key"], store.deviceID)
        XCTAssertEqual(FriendsKey.load(server: Self.base, storage: keys)?.keyID, store.deviceID)
    }

    // MARK: - The upload run

    private static let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(repeating: 0x30, count: 64)

    private static let hostProfileKeys = ["profile.displayName", "profile.avatarImageData"]
    private static let keptHostProfileKey = "tests.friends.keptProfile"

    /// Puts back the name and photo the test host's own defaults held before a test replaced them.
    /// What was there is kept in the defaults themselves until then, so a run that died mid-test is
    /// put right by the next one (`setUp` calls this too).
    private static func restoreHostProfile() {
        let standard = UserDefaults.standard
        guard let kept = standard.dictionary(forKey: keptHostProfileKey) else { return }
        for key in hostProfileKeys {
            if let value = kept[key] { standard.set(value, forKey: key) } else { standard.removeObject(forKey: key) }
        }
        standard.removeObject(forKey: keptHostProfileKey)
    }

    /// A profile with the name and photo a test needs. `ProfileStore` keeps both in the test host's own
    /// defaults, so what was there is put back when the test ends.
    @MainActor
    private func profile(name: String, photo: Data? = nil) -> ProfileStore {
        let standard = UserDefaults.standard
        if standard.dictionary(forKey: Self.keptHostProfileKey) == nil {
            var kept: [String: Any] = [:]
            for key in Self.hostProfileKeys { kept[key] = standard.object(forKey: key) }
            standard.set(kept, forKey: Self.keptHostProfileKey)
        }
        addTeardownBlock { Self.restoreHostProfile() }
        let profile = ProfileStore()
        profile.displayName = name
        profile.avatarImageData = photo
        return profile
    }

    /// Friends on, as an enrolment answered `status` leaves it, with the requests so far forgotten.
    @MainActor
    private func turnedOn(_ identity: FriendsStrap.Identity, enrolAnswer status: Int = 201,
                          name: String = "Anna") async throws -> (FriendsStore, UserDefaults) {
        let (store, defaults) = try store(identity)
        Stub.answers["POST /v2/enroll"] = [(status, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.turnOn(name: name)
        XCTAssertEqual(store.phase, .on)
        Stub.seen = []
        return (store, defaults)
    }

    private func sent(_ method: String, _ path: String) -> [Stub.Seen] {
        Stub.seen.filter { $0.method == method && $0.path == path }
    }

    /// The strap worn goes to the server when it is not the one the server last accepted. Accepted, it
    /// is recorded and not sent again; asked of the account that holds it, it is asked again every run.
    @MainActor
    func testTheUploadRunBindsTheStrapWornAndAsksAgainForOneThatIsSomeoneElses() async throws {
        let (store, defaults) = try await turnedOn(.handle(Self.handle))
        let repo = Repository(deviceId: "test-friends")
        let profile = profile(name: "Anna")
        Stub.answers["GET /v2/me"] = [(200, Self.meSharingNothing)]
        Stub.answers["PUT /v2/me/strap"] = [(200, #"{"bound":true}"#)]
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PUT", "/v2/me/strap").map { String(decoding: $0.body, as: UTF8.self) },
                       [#"{"strap":"\#(Self.handle)"}"#])
        XCTAssertEqual(defaults.string(forKey: FriendsStore.boundHandleKey), Self.handle)
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PUT", "/v2/me/strap").count, 1, "an accepted strap is not sent again")

        // Another strap is worn, and it belongs to another account.
        let other = String(repeating: "ab", count: 32)
        store.strapIdentity = { .handle(other) }
        Stub.answers["PUT /v2/me/strap"] = [(202, #"{"claim":{"id":7,"kind":"take","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}}"#)]
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PUT", "/v2/me/strap").count, 2)
        XCTAssertEqual(defaults.string(forKey: FriendsStore.boundHandleKey), Self.handle, "asked for is not accepted")
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PUT", "/v2/me/strap").count, 3, "asked again until it is settled")
        XCTAssertEqual(sent("PUT", "/v2/me/strap").last.map { String(decoding: $0.body, as: UTF8.self) }, #"{"strap":"\#(other)"}"#)
    }

    /// A phone that joined an account records its own name and photo as sent without sending them,
    /// once. After that it sends what the wearer changes on this phone, once per change.
    @MainActor
    func testAPhoneThatJoinedSendsItsProfileOnlyAfterAChangeMadeHere() async throws {
        let (store, defaults) = try await turnedOn(.none, enrolAnswer: 200, name: "Other")
        let repo = Repository(deviceId: "test-friends")
        let profile = profile(name: "Other", photo: Self.jpeg)
        Stub.answers["GET /v2/me"] = [(200, Self.meSharingNothing)]
        Stub.answers["PATCH /v2/me"] = [(200, Self.me)]
        Stub.answers["PUT /v2/me/avatar"] = [(200, Self.me)]
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("GET", "/v2/me").count, 1, "the run reached the server")
        XCTAssertTrue(sent("PATCH", "/v2/me").isEmpty)
        XCTAssertTrue(sent("PUT", "/v2/me/avatar").isEmpty)
        XCTAssertFalse(defaults.bool(forKey: FriendsStore.adoptProfileKey), "joining is recorded once")
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertTrue(sent("PATCH", "/v2/me").isEmpty)
        XCTAssertTrue(sent("PUT", "/v2/me/avatar").isEmpty)

        profile.displayName = "Renamed"
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PATCH", "/v2/me").map { String(decoding: $0.body, as: UTF8.self) }, [#"{"name":"Renamed"}"#])
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PATCH", "/v2/me").count, 1, "a name already sent is not sent again")
        XCTAssertTrue(sent("PUT", "/v2/me/avatar").isEmpty, "the photo did not change")
    }

    /// A phone that joined without confirmation uploads its days and nothing else: neither the strap
    /// nor the profile goes until it is confirmed.
    @MainActor
    func testAPhoneOnProbationSendsNeitherItsStrapNorItsProfile() async throws {
        let (store, _) = try await turnedOn(.handle(Self.handle))
        let repo = Repository(deviceId: "test-friends")
        let profile = profile(name: "Renamed", photo: Self.jpeg)
        let onProbation = Self.meSharingNothing.replacingOccurrences(of: #""probationUntil":null"#, with: #""probationUntil":1792104800"#)
        XCTAssertNotEqual(onProbation, Self.meSharingNothing)
        Stub.answers["GET /v2/me"] = [(200, onProbation)]
        Stub.answers["PUT /v2/me/strap"] = [(200, #"{"bound":true}"#)]
        Stub.answers["PATCH /v2/me"] = [(200, Self.me)]
        Stub.answers["PUT /v2/me/avatar"] = [(200, Self.me)]
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertTrue(sent("PUT", "/v2/me/strap").isEmpty)
        XCTAssertTrue(sent("PATCH", "/v2/me").isEmpty)
        XCTAssertTrue(sent("PUT", "/v2/me/avatar").isEmpty)
        XCTAssertTrue(Stub.seen.contains { $0.method == "PUT" && $0.path.hasPrefix("/v2/me/days/") }, "its days still go up")

        // Confirmed: the same run now sends all three.
        Stub.answers["GET /v2/me"] = [(200, Self.meSharingNothing)]
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PUT", "/v2/me/strap").count, 1)
        XCTAssertEqual(sent("PATCH", "/v2/me").count, 1)
        XCTAssertEqual(sent("PUT", "/v2/me/avatar").count, 1)
    }

    /// Switching Photo off removes the server's copy of a picture this phone sent, once.
    @MainActor
    func testSwitchingThePhotoOffDeletesTheServersCopy() async throws {
        let (store, defaults) = try await turnedOn(.none)
        let repo = Repository(deviceId: "test-friends")
        let profile = profile(name: "Anna", photo: Self.jpeg)
        Stub.answers["GET /v2/me"] = [(200, Self.meSharingNothing)]
        Stub.answers["PUT /v2/me/avatar"] = [(200, Self.me)]
        Stub.answers["DELETE /v2/me/avatar"] = [(204, "")]
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("PUT", "/v2/me/avatar").map(\.body), [Self.jpeg])
        XCTAssertEqual(defaults.string(forKey: FriendsStore.pushedPhotoKey), FriendsUploadPolicy.fingerprint(Self.jpeg))
        XCTAssertTrue(sent("DELETE", "/v2/me/avatar").isEmpty)

        await store.setSharePhoto(false, repo: repo, profile: profile)
        XCTAssertEqual(sent("DELETE", "/v2/me/avatar").count, 1)
        XCTAssertEqual(defaults.string(forKey: FriendsStore.pushedPhotoKey), "off")
        await store.uploadRecentDays(repo: repo, profile: profile)
        XCTAssertEqual(sent("DELETE", "/v2/me/avatar").count, 1, "a copy already removed is not removed again")
        XCTAssertEqual(sent("PUT", "/v2/me/avatar").count, 1)
    }

    @MainActor
    func testAnInviteIsReadOutOfALinkAndWhatIsNotACodeIsNotSent() async throws {
        let (store, _) = try store(.none)
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        Stub.answers["POST /v2/invites/redeem"] = [(200, #"{"friend":{"id":"00000000000000bb","name":"Max","avatarRev":0}}"#)]
        await store.turnOn(name: "Anna")
        let sent = Stub.seen.count
        let nobody = await store.redeem("hello")
        XCTAssertNil(nobody)
        XCTAssertNotNil(store.errorText)
        XCTAssertEqual(Stub.seen.count, sent)
        let friend = await store.redeem("https://friends.example/i/k7qm2-xrd4p")
        XCTAssertEqual(friend?.name, "Max")
        XCTAssertEqual(String(decoding: Stub.seen[sent].body, as: UTF8.self), #"{"code":"K7QM2XRD4P"}"#)
    }

    // MARK: - Clock

    /// Every "N min ago" counts from the server's clock, carried forward by the time since the feed
    /// arrived, so a phone whose own clock is wrong still reads the right age.
    func testTheServersClockIsCarriedForwardFromTheFeed() {
        XCTAssertEqual(FriendsClock.serverNow(serverTime: 1_000, fetchedAtDevice: 5_000, nowDevice: 5_090), 1_090)
        XCTAssertEqual(FriendsClock.serverNow(serverTime: 1_000, fetchedAtDevice: 5_000, nowDevice: 4_000), 1_000,
                       "a device clock set back counts as no time")
    }

    // MARK: - Names and addresses

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
