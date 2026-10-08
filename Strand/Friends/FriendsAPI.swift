//  FriendsAPI.swift
//  NOOP · Friends — the client of the friends service (reNOOP fork feature).
//
//  The contract is `friends-server/README.md`. Accounts have no password: sign-up returns a token, the
//  phone keeps it in the Keychain, and every later call carries it. This file is the wire only; what
//  the app does with it lives in `FriendsStore`.

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
    var avatarRev: Int = 0
    var share: FriendsShare?
    var relation: FriendRelation?
    var requestedAt: Int?
    var days: [FriendFeedDay]?

    var id: String { nick }

    /// The newest uploaded day, which leads the feed.
    var latestDay: FriendFeedDay? { days?.first }
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
        updatedAt = try c.decodeIfPresent(Int.self, forKey: .updatedAt)
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
    var serverTime: Int
    var me: FriendProfile
    var friends: [FriendProfile]
    var pendingIncoming: Int
}

struct FriendRequests: Codable, Equatable, Sendable {
    var incoming: [FriendProfile]
    var outgoing: [FriendProfile]
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
    /// The request never got an answer, or the answer was not the contract's.
    case transport(String)

    /// The token is no longer an account (deleted, or signed out elsewhere).
    var isSignedOut: Bool {
        if case .server(_, _, 401) = self { return true }
        return false
    }
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

/// The server address as the wearer typed it, made into a base URL, or nil when it is not one the app
/// will talk to. HTTPS always; plain HTTP only to this machine or the local network, where a
/// self-hosted server under test lives. The address is configuration: none is built into the app.
enum FriendsServerAddress {
    static func baseURL(_ raw: String) -> URL? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.contains("://") { text = "https://" + text }
        while text.hasSuffix("/") { text.removeLast() }
        // The host is checked on the typed text: `URL` accepts and re-encodes almost anything, so a
        // name typed on the wrong keyboard would otherwise pass as an address.
        let afterScheme = text.components(separatedBy: "://").dropFirst().joined(separator: "://")
        let authority = afterScheme.split(separator: "/", maxSplits: 1).first.map(String.init) ?? ""
        guard !authority.isEmpty, authority.unicodeScalars.allSatisfy({
            $0.isASCII && (CharacterSet.alphanumerics.contains($0) || ".-:[]".unicodeScalars.contains($0))
        }) else { return nil }
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased(),
              let host = url.host, !host.isEmpty, url.user == nil, url.query == nil else { return nil }
        if scheme == "https" { return url }
        guard scheme == "http", isLocal(host) else { return nil }
        return url
    }

    static func isLocal(_ host: String) -> Bool {
        let h = host.lowercased()
        if h == "localhost" || h == "127.0.0.1" || h == "::1" || h.hasSuffix(".local") { return true }
        let parts = h.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, h.split(separator: ".").count == 4 else { return false }
        return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168)
            || (parts[0] == 172 && (16...31).contains(parts[1]))
    }
}

/// One server, one optional token. Every call is a plain request and a decoded answer.
struct FriendsClient: Sendable {
    let baseURL: URL
    var token: String?
    var session: URLSession = .shared

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

    /// Sign up: a nickname, optionally a display name and the server's invite code. Returns the token
    /// that is the account from then on.
    func register(nick: String, name: String?, invite: String?) async throws -> (token: String, me: FriendProfile) {
        struct Body: Encodable { let nick: String; let name: String?; let invite: String? }
        struct Answer: Decodable { let token: String; let me: FriendProfile }
        let answer: Answer = try await send("POST", "/v1/register",
                                            body: Body(nick: FriendsNick.normalized(nick), name: name, invite: invite))
        return (answer.token, answer.me)
    }

    func me() async throws -> FriendProfile { try await get("/v1/me") }

    func update(name: String? = nil, share: FriendsShare? = nil) async throws -> FriendProfile {
        struct Body: Encodable { let name: String?; let share: FriendsShare? }
        return try await send("PATCH", "/v1/me", body: Body(name: name, share: share))
    }

    func deleteAccount() async throws { try await sendNoAnswer("POST", "/v1/me/delete") }

    // MARK: Days

    func upload(_ day: FriendsDay, on dayKey: String) async throws {
        try await sendNoAnswer("PUT", "/v1/me/days/" + dayKey, body: day)
    }

    func feed(days: Int = 7) async throws -> FriendsFeed { try await get("/v1/feed?days=\(days)") }

    /// One person in full, the heart-rate line included: what their own page draws.
    func person(_ nick: String, days: Int = 7) async throws -> FriendProfile {
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

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw FriendsAPIError.transport("unexpected answer") }
    }

    private func perform(_ method: String, _ path: String, body: Data?) async throws -> Data {
        guard let url = URL(string: baseURL.absoluteString + path) else { throw FriendsAPIError.notConfigured }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: request) }
        catch { throw FriendsAPIError.transport(error.localizedDescription) }
        guard let http = response as? HTTPURLResponse else { throw FriendsAPIError.transport("no answer") }
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
