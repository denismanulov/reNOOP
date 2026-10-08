import XCTest
import Foundation
import StrandAnalytics
@testable import Strand

/// The Friends client against the contract in `friends-server/README.md`: what it sends, what it
/// reads, and that an account is a name and a token with no password anywhere on the wire.
final class FriendsClientTests: XCTestCase {

    /// Answers every request from a canned table and records what was asked.
    private final class Stub: URLProtocol {
        nonisolated(unsafe) static var answers: [String: (status: Int, body: String)] = [:]
        nonisolated(unsafe) static var seen: [(method: String, path: String, auth: String?, body: String)] = []

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
            let answer = Self.answers[method + " " + path] ?? (404, #"{"error":"not_found","message":"Not found."}"#)
            let response = HTTPURLResponse(url: url, statusCode: answer.status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(answer.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    private func client(token: String? = "tok") -> FriendsClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return FriendsClient(baseURL: URL(string: "https://friends.example")!, token: token,
                             session: URLSession(configuration: config))
    }

    override func setUp() {
        super.setUp()
        Stub.answers = [:]
        Stub.seen = []
    }

    // MARK: - Account

    /// Sign-up sends a name and nothing else: there is no password member to send.
    func testSignUpSendsOnlyTheNameAndKeepsTheToken() async throws {
        Stub.answers["POST /v1/register"] = (201, #"{"token":"abc","me":{"nick":"denis","name":"denis","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false}}}"#)
        let created = try await client(token: nil).register(nick: " @Denis ", name: nil, invite: nil)
        XCTAssertEqual(created.token, "abc")
        XCTAssertEqual(created.me.share, FriendsShare())
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(sent.body, #"{"nick":"denis"}"#)
        XCTAssertFalse(sent.body.contains("password"))
        XCTAssertNil(sent.auth, "sign-up is the one call made without a token")
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
            _ = try await client(token: nil).register(nick: "denis", name: nil, invite: nil)
            XCTFail("expected a refusal")
        } catch let error as FriendsAPIError {
            XCTAssertEqual(error, .server(code: "nick_taken", message: "This nickname is taken.", status: 409))
            XCTAssertFalse(error.isSignedOut)
        } catch { XCTFail("\(error)") }

        Stub.answers["GET /v1/me"] = (401, #"{"error":"unauthorized","message":"Sign in."}"#)
        do {
            _ = try await client().me()
            XCTFail("expected a refusal")
        } catch let error as FriendsAPIError {
            XCTAssertTrue(error.isSignedOut)
        } catch { XCTFail("\(error)") }
    }

    // MARK: - Days

    /// A day goes up under the local day in the path, with absent members absent rather than null.
    func testUploadPutsTheDayAsBuilt() async throws {
        Stub.answers["PUT /v1/me/days/2026-10-08"] = (204, "")
        try await client().upload(FriendsDay(recovery: 81, strain: 38.6, sleepScore: 88), on: "2026-10-08")
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(sent.method, "PUT")
        XCTAssertEqual(sent.body, #"{"recovery":81,"sleepScore":88,"strain":38.6}"#)
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
        let feed = try await client().feed()
        XCTAssertEqual(feed.me.latestDay?.summary.recovery, 58)
        XCTAssertEqual(feed.pendingIncoming, 1)
        let ruslan = try XCTUnwrap(feed.friends.first)
        XCTAssertEqual(ruslan.share?.sleep, false)
        XCTAssertEqual(ruslan.latestDay?.day, "2026-10-08")
        XCTAssertEqual(ruslan.latestDay?.summary.workouts?.first?.kcal, 310)
        XCTAssertNil(ruslan.latestDay?.summary.workouts?.first?.maxHr)
        XCTAssertEqual(ruslan.latestDay?.summary.hr?.lastBpm, 62)
        XCTAssertNil(ruslan.latestDay?.summary.hr?.series, "the feed leaves the line out")
    }

    // MARK: - Names and addresses

    func testTheNameRuleIsTheServers() {
        XCTAssertEqual(FriendsNick.normalized(" @Denis_1 "), "denis_1")
        for good in ["abc", "denis", "a_b_c", "x1234567890123456789"] { XCTAssertTrue(FriendsNick.isValid(good), good) }
        for bad in ["ab", "has space", "денис", String(repeating: "x", count: 21), "a-b", ""] {
            XCTAssertFalse(FriendsNick.isValid(bad), bad)
        }
    }

    /// No address is built in. HTTPS anywhere; plain HTTP only to this machine or the local network.
    func testOnlyHTTPSOrALocalServerIsAnAddress() {
        XCTAssertEqual(FriendsServerAddress.baseURL("friends.example")?.absoluteString, "https://friends.example")
        XCTAssertEqual(FriendsServerAddress.baseURL(" https://friends.example/ ")?.absoluteString, "https://friends.example")
        XCTAssertEqual(FriendsServerAddress.baseURL("http://127.0.0.1:8787")?.absoluteString, "http://127.0.0.1:8787")
        XCTAssertNotNil(FriendsServerAddress.baseURL("http://192.168.1.20:8787"))
        XCTAssertNotNil(FriendsServerAddress.baseURL("http://nas.local"))
        for refused in ["", "http://friends.example", "ftp://friends.example", "https://user@friends.example",
                        "https://friends.example?x=1", "реезЖ//127ю0ю0ю1Ж8787", "http://8.8.8.8"] {
            XCTAssertNil(FriendsServerAddress.baseURL(refused), refused)
        }
    }

    // MARK: - The list

    func testAPersonWithNoFigureSortsAfterEveryoneWhoHasOne() {
        let a = FriendProfile(nick: "a", name: "Anna"), b = FriendProfile(nick: "b", name: "Boris")
        XCTAssertTrue(FriendsSort.descending(81, 64, a, b))
        XCTAssertFalse(FriendsSort.descending(64, 81, a, b))
        XCTAssertTrue(FriendsSort.descending(10, nil, b, a))
        XCTAssertFalse(FriendsSort.descending(nil, 10, a, b))
        XCTAssertTrue(FriendsSort.descending(nil, nil, a, b), "ties fall back to the name")
        XCTAssertTrue(FriendsSort.descending(50, 50, a, b))
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
