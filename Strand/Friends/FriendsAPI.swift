//  FriendsAPI.swift
//  NOOP · Friends — the client of the friends service (reNOOP fork feature).
//
//  The contract is `friends-server/README.md`, API version 2. There is no name to type and no password:
//  the phone holds a P-256 key (`FriendsKey`) and signs every request with it, and people are known by
//  an opaque account id. This file is the wire only; what the app does with it lives in `FriendsStore`.

import CryptoKit
import Foundation
import StrandAnalytics

/// A person as others see them. `share` and `days` come only where the call returns them; `strapBound`
/// and `device` only for the wearer's own account.
struct FriendProfile: Codable, Equatable, Identifiable, Sendable {
    /// The account's id on the server: opaque, never shown, never typed.
    var id: String
    var name: String
    /// 0 when the account has no picture; otherwise it changes whenever the picture does.
    var avatarRev: Int = 0
    var share: FriendsShare?
    /// Newest day first.
    var days: [FriendFeedDay]?
    var strapBound: Bool?
    var device: FriendsDeviceState?

    /// The newest uploaded day, which leads the feed.
    var latestDay: FriendFeedDay? { days?.first }
}

extension FriendProfile {
    /// Only the id is required. Every other member reads as absent when it is missing, null or of the
    /// wrong kind, so a section a friend does not share and one a newer server spells differently both
    /// read as not there rather than failing the whole answer.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let id = try c.decode(String.self, forKey: .id)
        guard FriendsID.isValid(id) else {
            throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "not an account id")
        }
        self.id = id
        name = ((try? c.decode(String.self, forKey: .name)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        avatarRev = max(0, (try? c.decode(Int.self, forKey: .avatarRev)) ?? 0)
        share = try? c.decode(FriendsShare.self, forKey: .share)
        days = (try? c.decode([Lossy<FriendFeedDay>].self, forKey: .days))
            .map { $0.compactMap(\.value).sorted { $0.day > $1.day } }
        strapBound = try? c.decode(Bool.self, forKey: .strapBound)
        device = try? c.decode(FriendsDeviceState.self, forKey: .device)
    }
}

/// An account id as the server mints it: 16 lower-case hex characters. Checked wherever an id goes
/// into a path or a file name.
enum FriendsID {
    static func isValid(_ id: String) -> Bool {
        id.unicodeScalars.count == 16 && id.unicodeScalars.allSatisfy {
            ("0"..."9").contains($0) || ("a"..."f").contains($0)
        }
    }
}

/// One element of a list that may hold something unreadable: it reads as nil instead of failing the list.
struct Lossy<Value: Decodable>: Decodable {
    let value: Value?
    init(from decoder: Decoder) throws { value = try? Value(from: decoder) }
}

/// A day as it comes back: the uploaded summary plus which day it is and when the phone sent it.
struct FriendFeedDay: Codable, Equatable, Identifiable, Sendable {
    var day: String
    var updatedAt: Int?
    var summary: FriendsDay

    var id: String { day }

    private enum Keys: String, CodingKey { case day, updatedAt }

    init(day: String, updatedAt: Int? = nil, summary: FriendsDay) {
        self.day = day; self.updatedAt = updatedAt; self.summary = summary
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        day = try c.decode(String.self, forKey: .day)
        // A day without a day key says nothing about when it was.
        guard FriendsUploader.isDayKey(day) else {
            throw DecodingError.dataCorruptedError(forKey: .day, in: c, debugDescription: "not a day key")
        }
        updatedAt = try? c.decode(Int.self, forKey: .updatedAt)
        summary = try FriendsDay(from: decoder)
    }

    func encode(to encoder: Encoder) throws {
        try summary.encode(to: encoder)
        var c = encoder.container(keyedBy: Keys.self)
        try c.encode(day, forKey: .day)
        try c.encodeIfPresent(updatedAt, forKey: .updatedAt)
    }
}

/// How this phone stands on the wearer's own account.
struct FriendsDeviceState: Codable, Equatable, Sendable {
    /// This phone's key id.
    var id: String
    /// Unix seconds until which this phone may only read and upload; nil for a confirmed phone.
    var probationUntil: Int?
}

/// A request waiting on a strap: a new phone asking to join the account (`join`), or another account
/// asking for the strap (`take`).
struct FriendsClaim: Codable, Equatable, Identifiable, Sendable {
    enum Kind: String, Codable, Sendable { case join, take }
    enum State: String, Codable, Sendable { case pending, approved, declined, expired }

    var id: Int
    var kind: Kind
    /// Six digits shown on both phones, so two claims can be told apart.
    var code: String
    var state: State
    var platform: String?
    var createdAt: Int
    /// When silence alone will grant it (unix seconds); nil once settled.
    var maturesAt: Int?
}

/// One phone of the wearer's account.
struct FriendsDevice: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var platform: String
    var addedAt: Int
    var lastSeenAt: Int
    var probationUntil: Int?
    /// This phone.
    var current: Bool
}

struct FriendsInvite: Codable, Equatable, Identifiable, Sendable {
    var id: String
    /// Said once, in the answer that made the invite.
    var code: String?
    var createdAt: Int?
    var expiresAt: Int
}

struct FriendsFeed: Codable, Equatable, Sendable {
    /// The server's clock when it answered (unix seconds): what every "N min ago" on the tab counts from.
    var serverTime: Int
    var me: FriendProfile
    var friends: [FriendProfile]
    /// Claims waiting for this account's answer.
    var claims: [FriendsClaim]
    /// This account's own request for a strap bound to someone else.
    var strapClaim: FriendsClaim?
    /// The account's other phones that joined without confirmation and are still on probation.
    var unconfirmed: [FriendsDevice] = []
}

extension FriendsFeed {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try c.decode(Int.self, forKey: .serverTime)
        me = try c.decode(FriendProfile.self, forKey: .me)
        friends = ((try? c.decode([Lossy<FriendProfile>].self, forKey: .friends)) ?? []).compactMap(\.value)
        claims = ((try? c.decode([Lossy<FriendsClaim>].self, forKey: .claims)) ?? []).compactMap(\.value)
        strapClaim = try? c.decode(FriendsClaim.self, forKey: .strapClaim)
        unconfirmed = ((try? c.decode([Lossy<FriendsDevice>].self, forKey: .unconfirmed)) ?? []).compactMap(\.value)
    }
}

struct FriendsServerInfo: Codable, Equatable, Sendable {
    var name: String
    var api: Int
    var time: Int
}

/// What the server said to "bind this strap": done, or asked of the account that holds it.
enum FriendsStrapAnswer: Equatable, Sendable {
    case bound
    case claimed(FriendsClaim)
}

enum FriendsAPIError: Error, Equatable {
    /// The server's own refusal: its code, its sentence and the HTTP status.
    case server(code: String, message: String, status: Int)
    /// No usable server address is set.
    case notConfigured
    /// A call that needs this phone's key was made with none. Nothing was sent.
    case notEnrolled
    /// The request never got an answer, or the answer was not the contract's.
    case transport(String)
    /// This phone's clock is too far from the server's, which answered with its own.
    case clockSkew(serverTime: Int)

    private func refused(_ wanted: String, _ wantedStatus: Int) -> Bool {
        if case let .server(code, _, status) = self { return code == wanted && status == wantedStatus }
        return false
    }

    /// The server does not know this phone's key: it was removed from the account, or the account is gone.
    var isUnknownKey: Bool { refused("unknown_key", 401) }
    /// The strap already belongs to an account.
    var isStrapBound: Bool { refused("strap_bound", 409) }
    /// This phone joined without confirmation and may only read and upload for now.
    var isProbation: Bool { refused("probation", 403) }
    var isNoClaim: Bool { refused("no_claim", 404) }
    var isStrapFree: Bool { refused("strap_free", 404) }
    var isAlreadyEnrolled: Bool { refused("already_enrolled", 409) }

    /// The server answered with a redirect. It is never followed, so a signed request goes to the host
    /// the wearer named and nowhere else.
    var isRedirect: Bool {
        if case let .server(_, _, status) = self { return (300..<400).contains(status) }
        return false
    }

    /// Nothing reached the server or nothing came back: worth another try later, unlike a refusal.
    var isOffline: Bool {
        if case .transport = self { return true }
        return false
    }
}

/// Sizes the client holds itself and the server to.
enum FriendsLimits {
    /// Days of history the tab asks for: the week the pages draw.
    static let feedDays = 7
    /// The server refuses a larger picture.
    static let maxAvatarBytes = 200 * 1024
    /// The largest answer read: a hundred friends' fortnight is well under this, and a wrong or hostile
    /// host cannot make the app hold more.
    static let maxAnswerBytes = 4 * 1024 * 1024
}

/// Which server address the app will talk to. Requests carry the wearer's days and a signature, so the
/// address must be HTTPS. The one exception is for development: plain HTTP to this device's own
/// loopback, where nothing crosses a network. The rule is the same as Android's `FriendsServerUrl`.
enum FriendsServerAddress {
    /// The fork's own server, used until the wearer names another.
    static let standard = "https://renoop.duckdns.org"

    /// The only hosts plain HTTP is accepted for.
    private static let loopbackHosts: Set<String> = ["localhost", "127.0.0.1"]

    enum Problem: Error, Equatable { case empty, malformed, notHTTPS, hasCredentials, hasQuery }

    /// Checks an address as typed and answers its normalised form, with no trailing slash. An address
    /// without a scheme is read as HTTPS. A path is kept, so a server mounted under a prefix by its proxy
    /// works; a query, a fragment or credentials in the address are refused.
    static func validate(_ raw: String) -> Result<String, Problem> {
        let typed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if typed.isEmpty { return .failure(.empty) }
        let text = typed.contains("://") ? typed : "https://" + typed
        // Read off the typed text: `URL` accepts and re-encodes almost anything, so a name typed on the
        // wrong keyboard would otherwise pass as an address.
        guard text.unicodeScalars.allSatisfy({ $0.isASCII && $0.value > 0x20 && $0.value < 0x7F
                                               && !"\"<>\\^`{|}".unicodeScalars.contains($0) }),
              let separator = text.range(of: "://") else { return .failure(.malformed) }
        let scheme = text[..<separator.lowerBound].lowercased()
        guard let lead = scheme.unicodeScalars.first, CharacterSet.letters.contains(lead),
              scheme.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.contains($0) || "+.-".unicodeScalars.contains($0) })
        else { return .failure(.malformed) }
        var rest = text[separator.upperBound...]
        var hasQuery = false
        if let mark = rest.firstIndex(where: { $0 == "?" || $0 == "#" }) {
            hasQuery = true
            rest = rest[..<mark]
        }
        let slash = rest.firstIndex(of: "/")
        var authority = rest[..<(slash ?? rest.endIndex)]
        let path = slash.map { String(rest[$0...]) } ?? ""
        if scheme != "https" && scheme != "http" { return .failure(.notHTTPS) }
        var hasCredentials = false
        if let at = authority.lastIndex(of: "@") {
            hasCredentials = true
            authority = authority[authority.index(after: at)...]
        }
        if hasCredentials { return .failure(.hasCredentials) }
        if hasQuery { return .failure(.hasQuery) }

        var host = String(authority).lowercased()
        var port: Int?
        if let colon = host.lastIndex(of: ":"), !host.hasSuffix("]") {
            let digits = host[host.index(after: colon)...]
            host = String(host[..<colon])
            if !digits.isEmpty {
                guard digits.allSatisfy(\.isASCII), digits.allSatisfy(\.isNumber), digits.count <= 9,
                      let number = Int(digits) else { return .failure(.malformed) }
                port = number
            }
        }
        let bracketed = host.hasPrefix("[") && host.hasSuffix("]") && host.count > 2
        let named = !host.isEmpty && host.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || $0 == "." || $0 == "-"
        } && host.unicodeScalars.contains(where: { CharacterSet.alphanumerics.contains($0) })
            && !host.hasPrefix("-") && !host.hasPrefix(".")
        guard bracketed || named else { return .failure(.malformed) }
        if let port, !(1...65_535).contains(port) { return .failure(.malformed) }
        if scheme == "http" && !loopbackHosts.contains(host) { return .failure(.notHTTPS) }

        let defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        let portText = port.flatMap { defaultPort ? nil : ":\($0)" } ?? ""
        var trimmedPath = path
        while trimmedPath.hasSuffix("/") { trimmedPath.removeLast() }
        return .success("\(scheme)://\(host)\(portText)\(trimmedPath)")
    }

    /// The normalised address, or nil when `raw` is not one the app will talk to.
    static func normalized(_ raw: String) -> String? { try? validate(raw).get() }

    static func baseURL(_ raw: String) -> URL? { normalized(raw).flatMap(URL.init(string:)) }
}

/// Refuses every redirect, so a 3xx comes back as the answer it is instead of being followed.
private final class FriendsNoRedirects: NSObject, URLSessionTaskDelegate {
    static let shared = FriendsNoRedirects()

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// The name this build gives the server for a phone. It is all the server learns about the device.
enum FriendsPlatform {
    static var current: String {
        #if os(macOS)
        return "mac"
        #else
        return "ios"
        #endif
    }
}

/// The gap between the server's clock and this phone's, learned from a `clock_skew` refusal and applied
/// to every later signature, so a phone whose clock is wrong still signs a time the server accepts.
final class FriendsClockOffset: @unchecked Sendable {
    static let shared = FriendsClockOffset()

    private let lock = NSLock()
    private var value = 0

    var seconds: Int {
        lock.lock(); defer { lock.unlock() }
        return value
    }

    func set(_ seconds: Int) {
        lock.lock(); defer { lock.unlock() }
        value = seconds
    }
}

/// One server, one key. Every call is a signed request and a decoded answer. Three things hold for
/// each: the address was checked by `FriendsServerAddress` before a client exists; a redirect is never
/// followed; and nothing is logged, not a URL, a body or a header.
struct FriendsClient: Sendable {
    let baseURL: URL
    /// This phone's key. Nil only for `info()`, the one call that is not signed.
    var signer: (any FriendsSigner)?
    var session: URLSession = FriendsClient.plainSession
    var offset: FriendsClockOffset = .shared
    /// The device clock and the nonce source, replaceable so a test can pin what is signed.
    var now: @Sendable () -> Int = { Int(Date().timeIntervalSince1970) }
    var nonce: @Sendable () -> String = { FriendsClient.randomNonce() }

    /// One session for the process: no cookies, no cache and no stored credentials, so nothing of an
    /// answer outlives the request that carried it.
    static let plainSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return e
    }()

    /// 16 random bytes as base64url without padding: new for every request.
    static func randomNonce() -> String {
        Data((0..<16).map { _ in UInt8.random(in: .min ... .max) }).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    // MARK: Entering

    func info() async throws -> FriendsServerInfo {
        try decode(try await perform("GET", "/v2/info", body: nil, signed: false))
    }

    /// Makes an account for this phone's key, bound to `strap` when one is given. Answers the account
    /// the key already has when it has one. A strap that belongs to another account is `strap_bound`.
    /// `isNew` is false when the key was already a phone of an account (`200`, not `201`): the server
    /// answered that account as it stands and took neither the name nor the strap sent.
    func enroll(name: String, strap: String?) async throws -> (me: FriendProfile, isNew: Bool) {
        struct Body: Encodable { let key: String; let name: String; let platform: String; let strap: String? }
        struct Answer: Decodable { let me: FriendProfile }
        let body = try Self.encoder.encode(Body(
            key: try key().publicKeySPKI.base64EncodedString(), name: name,
            platform: FriendsPlatform.current, strap: strap))
        let answered = try await answer("POST", "/v2/enroll", body: body)
        let account: Answer = try decode(answered.data)
        return (account.me, answered.status == 201)
    }

    /// Asks to join the account `strap` is bound to. A phone of that account confirms it, or two days
    /// of its silence do.
    func fileClaim(strap: String) async throws -> FriendsClaim {
        struct Body: Encodable { let key: String; let strap: String; let platform: String }
        let answer: ClaimAnswer = try await send("POST", "/v2/claims", body: Body(
            key: try key().publicKeySPKI.base64EncodedString(), strap: strap, platform: FriendsPlatform.current))
        return answer.claim
    }

    func myClaim() async throws -> FriendsClaim {
        let answer: ClaimAnswer = try await get("/v2/claims/mine")
        return answer.claim
    }

    func withdrawClaim() async throws { try await sendNoAnswer("DELETE", "/v2/claims/mine") }

    // MARK: Account

    func me() async throws -> FriendProfile { try await get("/v2/me") }

    func update(name: String? = nil, share: FriendsShare? = nil) async throws -> FriendProfile {
        struct Body: Encodable { let name: String?; let share: FriendsShare? }
        return try await send("PATCH", "/v2/me", body: Body(name: name, share: share))
    }

    /// Uploads a JPEG picture. One over `FriendsLimits.maxAvatarBytes` is refused here, without a
    /// request: the server would refuse it before reading it and close the connection mid-upload, which
    /// reads as a lost connection rather than as the picture being too large.
    func putAvatar(_ jpeg: Data) async throws -> FriendProfile {
        guard jpeg.count <= FriendsLimits.maxAvatarBytes else {
            throw FriendsAPIError.server(code: "too_large", message: "", status: 413)
        }
        return try decode(try await perform("PUT", "/v2/me/avatar", body: jpeg, contentType: "image/jpeg"))
    }

    func deleteAvatar() async throws { try await sendNoAnswer("DELETE", "/v2/me/avatar") }

    /// The picture's bytes as the server holds them (JPEG, PNG or WebP).
    func avatar(_ id: String) async throws -> Data {
        try await perform("GET", "/v2/users/\(try checked(id))/avatar", body: nil, maxBytes: FriendsLimits.maxAvatarBytes)
    }

    /// Binds the account to the strap now worn. A free strap is bound at once; one that belongs to
    /// another account is asked for.
    func putStrap(_ handle: String) async throws -> FriendsStrapAnswer {
        struct Body: Encodable { let strap: String }
        struct Answer: Decodable { let bound: Bool?; let claim: FriendsClaim? }
        let answer: Answer = try await send("PUT", "/v2/me/strap", body: Body(strap: handle))
        if let claim = answer.claim { return .claimed(claim) }
        guard answer.bound == true else { throw FriendsAPIError.transport("unexpected answer") }
        return .bound
    }

    func approve(_ claimID: Int) async throws { try await sendNoAnswer("POST", "/v2/claims/\(claimID)/approve") }

    func decline(_ claimID: Int) async throws { try await sendNoAnswer("POST", "/v2/claims/\(claimID)/decline") }

    func devices() async throws -> [FriendsDevice] {
        struct Answer: Decodable { let devices: [FriendsDevice] }
        let answer: Answer = try await get("/v2/me/devices")
        return answer.devices
    }

    func removeDevice(_ keyID: String) async throws { try await sendNoAnswer("DELETE", "/v2/me/devices/\(try checkedKey(keyID))") }

    /// Ends the probation of a phone that joined without confirmation.
    func trustDevice(_ keyID: String) async throws {
        try await sendNoAnswer("POST", "/v2/me/devices/\(try checkedKey(keyID))/trust")
    }

    /// Everything the server holds for the account, as it sent it.
    func export() async throws -> Data { try await perform("GET", "/v2/me/export", body: nil) }

    func deleteAccount() async throws { try await sendNoAnswer("POST", "/v2/me/delete") }

    // MARK: Days

    /// Replaces the day `dayKey` ("yyyy-MM-dd") with `json`, a `FriendsDayBuilder.json` text.
    func upload(json: String, on dayKey: String) async throws {
        _ = try await perform("PUT", "/v2/me/days/" + dayKey, body: Data(json.utf8))
    }

    /// The feed and the bytes it was read from, which is what the tab keeps for an instant or offline start.
    func feed(days: Int = FriendsLimits.feedDays) async throws -> (feed: FriendsFeed, raw: Data) {
        let raw = try await perform("GET", "/v2/feed?days=\(days)", body: nil)
        return (try decode(raw), raw)
    }

    /// One person in full, the heart-rate line included: what their own page draws.
    func person(_ id: String, days: Int = FriendsLimits.feedDays) async throws -> FriendProfile {
        try await get("/v2/users/\(try checked(id))/days?days=\(days)")
    }

    // MARK: Friends

    /// Makes a one-time invite. Its code is in this answer and nowhere else.
    func createInvite() async throws -> FriendsInvite { try await send("POST", "/v2/invites", body: Optional<String>.none) }

    func invites() async throws -> [FriendsInvite] {
        struct Answer: Decodable { let invites: [FriendsInvite] }
        let answer: Answer = try await get("/v2/invites")
        return answer.invites
    }

    func revokeInvite(_ id: String) async throws { try await sendNoAnswer("DELETE", "/v2/invites/\(try checked(id))") }

    /// Uses an invite code: the two accounts are friends at once.
    func redeem(_ code: String) async throws -> FriendProfile {
        struct Body: Encodable { let code: String }
        struct Answer: Decodable { let friend: FriendProfile }
        let answer: Answer = try await send("POST", "/v2/invites/redeem", body: Body(code: code))
        return answer.friend
    }

    func unfriend(_ id: String) async throws { try await sendNoAnswer("DELETE", "/v2/friends/\(try checked(id))") }

    // MARK: Wire

    private struct ClaimAnswer: Decodable { let claim: FriendsClaim }

    private func key() throws -> any FriendsSigner {
        guard let signer else { throw FriendsAPIError.notEnrolled }
        return signer
    }

    /// An id that is safe to put in a path. Anything else is not an account the server could have.
    private func checked(_ id: String) throws -> String {
        guard FriendsID.isValid(id) else { throw FriendsAPIError.server(code: "no_such_user", message: "", status: 404) }
        return id
    }

    private func checkedKey(_ keyID: String) throws -> String {
        guard keyID.unicodeScalars.count == 64, keyID.unicodeScalars.allSatisfy({
            ("0"..."9").contains($0) || ("a"..."f").contains($0)
        }) else { throw FriendsAPIError.server(code: "no_such_device", message: "", status: 404) }
        return keyID
    }

    private func get<T: Decodable>(_ path: String) async throws -> T {
        try decode(try await perform("GET", path, body: nil))
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
        try decode(try await perform(method, path, body: try body.map { try Self.encoder.encode($0) }))
    }

    private func sendNoAnswer(_ method: String, _ path: String) async throws {
        _ = try await perform(method, path, body: nil)
    }

    static func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw FriendsAPIError.transport("unexpected answer") }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T { try Self.decode(data) }

    private func perform(_ method: String, _ path: String, body: Data?, contentType: String = "application/json",
                         maxBytes: Int = FriendsLimits.maxAnswerBytes, signed: Bool = true) async throws -> Data {
        try await answer(method, path, body: body, contentType: contentType, maxBytes: maxBytes, signed: signed).data
    }

    /// The answer's bytes with its status, for the one call where two successes mean different things.
    private func answer(_ method: String, _ path: String, body: Data?, contentType: String = "application/json",
                        maxBytes: Int = FriendsLimits.maxAnswerBytes,
                        signed: Bool = true) async throws -> (data: Data, status: Int) {
        do {
            return try await once(method, path, body: body, contentType: contentType, maxBytes: maxBytes, signed: signed)
        } catch let FriendsAPIError.clockSkew(serverTime) {
            // This phone's clock is off. Sign once more by the server's; a second refusal is the answer.
            offset.set(serverTime - now())
            return try await once(method, path, body: body, contentType: contentType, maxBytes: maxBytes, signed: signed)
        }
    }

    private func once(_ method: String, _ path: String, body: Data?, contentType: String, maxBytes: Int,
                      signed: Bool) async throws -> (data: Data, status: Int) {
        guard let url = URL(string: baseURL.absoluteString + path) else { throw FriendsAPIError.notConfigured }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        if signed {
            let signer = try key()
            let time = now() + offset.seconds
            let nonce = self.nonce()
            // `path` is what the server sees after its proxy: the signature covers it, the query included.
            let text = FriendsWire.signingString(method: method, target: path, time: time, nonce: nonce,
                                                 bodyHashHex: FriendsKey.hex(SHA256.hash(data: body ?? Data())))
            let signature: Data
            do { signature = try signer.sign(Data(text.utf8)) }
            catch { throw FriendsAPIError.transport("could not sign") }
            request.setValue(signer.keyID, forHTTPHeaderField: "X-Friends-Key")
            request.setValue(String(time), forHTTPHeaderField: "X-Friends-Time")
            request.setValue(nonce, forHTTPHeaderField: "X-Friends-Nonce")
            request.setValue(signature.base64EncodedString(), forHTTPHeaderField: "X-Friends-Signature")
        }
        var data = Data()
        let response: URLResponse
        do {
            let (bytes, answered) = try await session.bytes(for: request, delegate: FriendsNoRedirects.shared)
            response = answered
            // Read up to the limit and one byte past it, which is how an over-long answer is told apart.
            for try await byte in bytes {
                data.append(byte)
                if data.count > maxBytes { break }
            }
        } catch {
            // A request cancelled with the task that made it (a page left, a newer run) did not fail: it
            // stays recognisable, so that nothing reports it as a server that did not answer.
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw FriendsAPIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw FriendsAPIError.transport("no answer") }
        guard data.count <= maxBytes else { throw FriendsAPIError.transport("unexpected answer") }
        guard (200..<300).contains(http.statusCode) else {
            struct Refusal: Decodable { let error: String; let message: String?; let serverTime: Int? }
            if let refusal = try? JSONDecoder().decode(Refusal.self, from: data) {
                if refusal.error == "clock_skew", let serverTime = refusal.serverTime {
                    throw FriendsAPIError.clockSkew(serverTime: serverTime)
                }
                throw FriendsAPIError.server(code: refusal.error, message: refusal.message ?? "", status: http.statusCode)
            }
            throw FriendsAPIError.server(code: "http_\(http.statusCode)", message: "", status: http.statusCode)
        }
        return (data, http.statusCode)
    }
}
