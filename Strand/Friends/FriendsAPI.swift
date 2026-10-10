//  FriendsAPI.swift
//  NOOP · Friends — the client of the friends service (reNOOP fork feature).
//
//  The contract is `friends-server/README.md`. An account is a nickname and a password: sign-up and
//  sign-in return a token, the phone keeps it in the Keychain, and every later call carries it. The
//  password is sent only to register, log in, change it and delete the account, and is never kept. How a
//  wearer signs in is the one part of the contract still under discussion (2026-10-09), so those three
//  calls are kept thin. This file is the wire only; what the app does with it lives in `FriendsStore`.

import Foundation
import StrandAnalytics

/// How someone stands to the signed-in account.
enum FriendRelation: String, Codable, Sendable {
    case me = "self", friend, outgoing, incoming, none
}

/// A person as others see them. `share`, `relation` and `days` come only where the call returns them.
struct FriendProfile: Codable, Equatable, Identifiable, Sendable {
    var nick: String
    var name: String
    /// 0 when the account has no picture; otherwise it changes whenever the picture does.
    var avatarRev: Int = 0
    var share: FriendsShare?
    var relation: FriendRelation?
    var requestedAt: Int?
    /// Newest day first.
    var days: [FriendFeedDay]?

    var id: String { nick }

    /// The newest uploaded day, which leads the feed.
    var latestDay: FriendFeedDay? { days?.first }
}

extension FriendProfile {
    /// Only the nickname is required. Every other member reads as absent when it is missing, null or of
    /// the wrong kind, so a section a friend does not share and one a newer server spells differently
    /// both read as not there rather than failing the whole answer.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let nick = try c.decode(String.self, forKey: .nick)
        guard !nick.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: .nick, in: c, debugDescription: "empty nick")
        }
        self.nick = nick
        let name = (try? c.decode(String.self, forKey: .name)) ?? ""
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nick : name
        avatarRev = max(0, (try? c.decode(Int.self, forKey: .avatarRev)) ?? 0)
        share = try? c.decode(FriendsShare.self, forKey: .share)
        relation = (try? c.decode(String.self, forKey: .relation)).flatMap(FriendRelation.init(rawValue:))
        requestedAt = try? c.decode(Int.self, forKey: .requestedAt)
        days = (try? c.decode([Lossy<FriendFeedDay>].self, forKey: .days))
            .map { $0.compactMap(\.value).sorted { $0.day > $1.day } }
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

struct FriendsFeed: Codable, Equatable, Sendable {
    /// The server's clock when it answered (unix seconds): what every "N min ago" on the tab counts from.
    var serverTime: Int
    var me: FriendProfile
    var friends: [FriendProfile]
    var pendingIncoming: Int
}

extension FriendsFeed {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try c.decode(Int.self, forKey: .serverTime)
        me = try c.decode(FriendProfile.self, forKey: .me)
        friends = ((try? c.decode([Lossy<FriendProfile>].self, forKey: .friends)) ?? []).compactMap(\.value)
        pendingIncoming = max(0, (try? c.decode(Int.self, forKey: .pendingIncoming)) ?? 0)
    }
}

struct FriendRequests: Codable, Equatable, Sendable {
    var incoming: [FriendProfile]
    var outgoing: [FriendProfile]
}

extension FriendRequests {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        incoming = ((try? c.decode([Lossy<FriendProfile>].self, forKey: .incoming)) ?? []).compactMap(\.value)
        outgoing = ((try? c.decode([Lossy<FriendProfile>].self, forKey: .outgoing)) ?? []).compactMap(\.value)
    }
}

struct FriendsServerInfo: Codable, Equatable, Sendable {
    var name: String
    var api: Int
    var inviteRequired: Bool
}

enum FriendsAPIError: Error, Equatable {
    /// The server's own refusal: its code, its sentence and the HTTP status.
    case server(code: String, message: String, status: Int)
    /// No usable server address is set.
    case notConfigured
    /// A call that needs an account was made with none on this phone. Nothing was sent.
    case notSignedIn
    /// The request never got an answer, or the answer was not the contract's.
    case transport(String)

    /// The token is no longer an account (deleted, or signed out elsewhere). A wrong password is a 401
    /// too, but it says `bad_credentials` and leaves the session alone.
    var isSignedOut: Bool {
        if case .server("unauthorized", _, 401) = self { return true }
        return false
    }

    /// The password sent with a sign-in, a change or a deletion was not the account's.
    var isWrongPassword: Bool {
        if case .server("bad_credentials", _, 401) = self { return true }
        return false
    }

    /// The server answered with a redirect. It is never followed, so the token and a password in a body
    /// go to the host the wearer named and nowhere else.
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

/// The nickname rule, the same as the server's: 3 to 20 of a-z, 0-9 and _, lower-cased, a leading @
/// ignored. Checked on the phone so the form can say so before anything is sent.
enum FriendsNick {
    static func normalized(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s.hasPrefix("@") { s.removeFirst() }
        return s
    }

    static func isValid(_ raw: String) -> Bool {
        let s = normalized(raw)
        return (3...20).contains(s.count) && s.unicodeScalars.allSatisfy {
            ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "_"
        }
    }
}

/// The password rule, the same as the server's: 8 to 128 characters, counted the way the server counts
/// them (Unicode scalars, as Python's `len` does).
enum FriendsPassword {
    static func isValid(_ raw: String) -> Bool { (8...128).contains(raw.unicodeScalars.count) }
}

/// Which server address the app will talk to. Passwords and the session token travel in requests, so
/// the address must be HTTPS. The one exception is for development: plain HTTP to this device's own
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

/// One server, one optional token. Every call is a plain request and a decoded answer. Three things
/// hold for each: the address was checked by `FriendsServerAddress` before a client exists; a redirect is
/// never followed; and nothing is logged, not a URL, a body or a header.
struct FriendsClient: Sendable {
    let baseURL: URL
    var token: String?
    var session: URLSession = FriendsClient.plainSession

    /// One session for the process: no cookies, no cache and no stored credentials, so nothing of an
    /// answer or a token outlives the request that carried it.
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
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    // MARK: Account

    func info() async throws -> FriendsServerInfo { try await get("/v1/info") }

    func isNickFree(_ nick: String) async throws -> Bool {
        struct Answer: Decodable { let free: Bool }
        let answer: Answer = try await get("/v1/nicks/" + FriendsNick.normalized(nick))
        return answer.free
    }

    /// Sign up: a nickname and a password, optionally a display name and the server's invite code.
    /// Returns the token that stands for the account on this phone.
    func register(nick: String, password: String, name: String?, invite: String?) async throws -> (token: String, me: FriendProfile) {
        struct Body: Encodable { let nick: String; let password: String; let name: String?; let invite: String? }
        struct Answer: Decodable { let token: String; let me: FriendProfile }
        let answer: Answer = try await send("POST", "/v1/register",
                                            body: Body(nick: FriendsNick.normalized(nick), password: password,
                                                       name: name, invite: invite))
        return (answer.token, answer.me)
    }

    /// Sign in to an account made earlier, on this phone or another. A new token; the old ones stay.
    func login(nick: String, password: String) async throws -> (token: String, me: FriendProfile) {
        struct Body: Encodable { let nick: String; let password: String }
        struct Answer: Decodable { let token: String; let me: FriendProfile }
        let answer: Answer = try await send("POST", "/v1/login",
                                            body: Body(nick: FriendsNick.normalized(nick), password: password))
        return (answer.token, answer.me)
    }

    /// Ends this phone's session on the server. The account and its other sessions stay.
    func signOut() async throws { try await sendNoAnswer("DELETE", "/v1/session") }

    /// Changes the password. The server ends every other session and answers with this phone's new token.
    func changePassword(old: String, new: String) async throws -> String {
        struct Body: Encodable { let old: String; let new: String }
        struct Answer: Decodable { let token: String }
        let answer: Answer = try await send("POST", "/v1/me/password", body: Body(old: old, new: new))
        return answer.token
    }

    func me() async throws -> FriendProfile { try await get("/v1/me") }

    func update(name: String? = nil, share: FriendsShare? = nil) async throws -> FriendProfile {
        struct Body: Encodable { let name: String?; let share: FriendsShare? }
        return try await send("PATCH", "/v1/me", body: Body(name: name, share: share))
    }

    func deleteAccount(password: String) async throws {
        struct Body: Encodable { let password: String }
        try await sendNoAnswer("POST", "/v1/me/delete", body: Body(password: password))
    }

    /// Uploads a JPEG picture. One over `FriendsLimits.maxAvatarBytes` is refused here, without a
    /// request: the server would refuse it before reading it and close the connection mid-upload, which
    /// reads as a lost connection rather than as the picture being too large.
    func putAvatar(_ jpeg: Data) async throws -> FriendProfile {
        guard jpeg.count <= FriendsLimits.maxAvatarBytes else {
            throw FriendsAPIError.server(code: "too_large", message: "", status: 413)
        }
        return try decode(try await perform("PUT", "/v1/me/avatar", body: jpeg, contentType: "image/jpeg"))
    }

    func deleteAvatar() async throws { try await sendNoAnswer("DELETE", "/v1/me/avatar") }

    /// The picture's bytes as the server holds them (JPEG, PNG or WebP).
    func avatar(_ nick: String) async throws -> Data {
        try await perform("GET", "/v1/users/\(FriendsNick.normalized(nick))/avatar", body: nil,
                          maxBytes: FriendsLimits.maxAvatarBytes)
    }

    // MARK: Days

    /// Replaces the day `dayKey` ("yyyy-MM-dd") with `json`, a `FriendsDayBuilder.json` text.
    func upload(json: String, on dayKey: String) async throws {
        _ = try await perform("PUT", "/v1/me/days/" + dayKey, body: Data(json.utf8))
    }

    /// The feed and the bytes it was read from, which is what the tab keeps for an instant or offline start.
    func feed(days: Int = FriendsLimits.feedDays) async throws -> (feed: FriendsFeed, raw: Data) {
        let raw = try await perform("GET", "/v1/feed?days=\(days)", body: nil)
        return (try decode(raw), raw)
    }

    /// One person in full, the heart-rate line included: what their own page draws.
    func person(_ nick: String, days: Int = FriendsLimits.feedDays) async throws -> FriendProfile {
        try await get("/v1/users/\(FriendsNick.normalized(nick))/days?days=\(days)")
    }

    // MARK: Friends

    /// Exact-match lookup; the server has no directory and no prefix search.
    func lookup(_ nick: String) async throws -> FriendProfile {
        try await get("/v1/users/" + FriendsNick.normalized(nick))
    }

    func requests() async throws -> FriendRequests { try await get("/v1/friends/requests") }

    func sendRequest(to nick: String) async throws -> FriendProfile {
        struct Body: Encodable { let nick: String }
        return try await send("POST", "/v1/friends/requests", body: Body(nick: FriendsNick.normalized(nick)))
    }

    func accept(_ nick: String) async throws -> FriendProfile {
        try await send("POST", "/v1/friends/requests/\(FriendsNick.normalized(nick))/accept", body: Optional<String>.none)
    }

    /// Declines an incoming request or withdraws an outgoing one.
    func dropRequest(_ nick: String) async throws {
        try await sendNoAnswer("DELETE", "/v1/friends/requests/" + FriendsNick.normalized(nick))
    }

    func unfriend(_ nick: String) async throws {
        try await sendNoAnswer("DELETE", "/v1/friends/" + FriendsNick.normalized(nick))
    }

    // MARK: Wire

    private func get<T: Decodable>(_ path: String) async throws -> T {
        try decode(try await perform("GET", path, body: nil))
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
        try decode(try await perform(method, path, body: try body.map { try Self.encoder.encode($0) }))
    }

    private func sendNoAnswer(_ method: String, _ path: String) async throws {
        _ = try await perform(method, path, body: nil)
    }

    private func sendNoAnswer<B: Encodable>(_ method: String, _ path: String, body: B) async throws {
        _ = try await perform(method, path, body: try Self.encoder.encode(body))
    }

    static func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw FriendsAPIError.transport("unexpected answer") }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T { try Self.decode(data) }

    private func perform(_ method: String, _ path: String, body: Data?, contentType: String = "application/json",
                         maxBytes: Int = FriendsLimits.maxAnswerBytes) async throws -> Data {
        guard let url = URL(string: baseURL.absoluteString + path) else { throw FriendsAPIError.notConfigured }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = body
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
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
            throw FriendsAPIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw FriendsAPIError.transport("no answer") }
        guard data.count <= maxBytes else { throw FriendsAPIError.transport("unexpected answer") }
        guard (200..<300).contains(http.statusCode) else {
            struct Refusal: Decodable { let error: String; let message: String? }
            if let refusal = try? JSONDecoder().decode(Refusal.self, from: data) {
                throw FriendsAPIError.server(code: refusal.error, message: refusal.message ?? "", status: http.statusCode)
            }
            throw FriendsAPIError.server(code: "http_\(http.statusCode)", message: "", status: http.statusCode)
        }
        return data
    }
}
