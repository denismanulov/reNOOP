//  FriendsStore.swift
//  NOOP · Friends — the account on this phone, what the tab shows, and the daily upload.
//
//  A reNOOP fork feature, and off until the wearer creates an account: with no token nothing is sent
//  anywhere and the rest of the app behaves exactly as it did. The server address is configuration the
//  wearer enters; none is built in.
//
//  The account is the token. There is no password, so the Keychain item below is the only way back
//  into the account: it is not a setting, is never written to UserDefaults, and never enters a
//  `.noopbak` backup.

import Foundation
import Security
import StrandAnalytics
import WhoopStore

/// The account token, in the Keychain. Readable after the first unlock so an upload can follow a
/// background sync; this device only, since it is a credential.
enum FriendsKeychain {
    private static let service = "com.renoop.friends"
    private static let account = "token"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func read() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    static func save(_ token: String) -> Bool {
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = Data(token.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    static func clear() { SecItemDelete(query as CFDictionary) }
}

@MainActor
final class FriendsStore: ObservableObject {
    static let shared = FriendsStore()

    /// The server address as typed, and the signed-in nickname (so the tab can name the account before
    /// the first answer). Plain configuration; the token is NOT here.
    static let addressKey = "friends.serverAddress"
    static let nickKey = "friends.nick"

    @Published private(set) var signedIn: Bool
    @Published private(set) var feed: FriendsFeed?
    @Published private(set) var requests: FriendRequests?
    @Published private(set) var loading = false
    /// The last failure, already worded for the wearer; nil once a call succeeds.
    @Published var errorText: String?

    private let defaults: UserDefaults
    private var token: String?
    /// The JSON last uploaded per day, so an unchanged day is not sent again (the server allows 120 an hour).
    private var uploaded: [String: Data] = [:]
    private var uploadTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard, token: String? = FriendsKeychain.read()) {
        self.defaults = defaults
        self.token = token
        self.signedIn = token != nil
    }

    var serverAddress: String { defaults.string(forKey: Self.addressKey) ?? "" }
    var nick: String { feed?.me.nick ?? defaults.string(forKey: Self.nickKey) ?? "" }
    var me: FriendProfile? { feed?.me }
    var share: FriendsShare { feed?.me.share ?? FriendsShare() }

    private func client(address: String? = nil, anonymous: Bool = false) throws -> FriendsClient {
        guard let url = FriendsServerAddress.baseURL(address ?? serverAddress) else { throw FriendsAPIError.notConfigured }
        return FriendsClient(baseURL: url, token: anonymous ? nil : token)
    }

    // MARK: - Account

    /// What the sign-up form needs to know about a server before it sends anything.
    func serverInfo(address: String) async -> Result<FriendsServerInfo, FriendsAPIError> {
        await result { try await self.client(address: address, anonymous: true).info() }
    }

    func isNickFree(_ nick: String, address: String) async -> Bool? {
        try? await client(address: address, anonymous: true).isNickFree(nick)
    }

    /// Create the account: the one step that turns the feature on. Returns true on success.
    func signUp(address: String, nick: String, name: String?, invite: String?) async -> Bool {
        loading = true
        defer { loading = false }
        do {
            let anonymous = try client(address: address, anonymous: true)
            let created = try await anonymous.register(nick: nick, name: name?.isEmpty == false ? name : nil,
                                                       invite: invite?.isEmpty == false ? invite : nil)
            guard FriendsKeychain.save(created.token) else {
                // The token is the only way into the account. If it cannot be kept, take the account
                // back while the token is still in hand, so the name is not left taken by nobody.
                var holder = anonymous
                holder.token = created.token
                try? await holder.deleteAccount()
                errorText = String(localized: "The account's key could not be saved on this device, so the account was not created.")
                return false
            }
            token = created.token
            defaults.set(address.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Self.addressKey)
            defaults.set(created.me.nick, forKey: Self.nickKey)
            signedIn = true
            errorText = nil
            await refresh()
            return true
        } catch {
            errorText = Self.message(for: error)
            return false
        }
    }

    /// Delete the account on the server and forget it here. With no password there is no signing out
    /// and back in, so this is the only way to leave.
    func deleteAccount() async -> Bool {
        do {
            try await client().deleteAccount()
            forgetAccount()
            return true
        } catch {
            if let api = error as? FriendsAPIError, api.isSignedOut { forgetAccount(); return true }
            errorText = Self.message(for: error)
            return false
        }
    }

    private func forgetAccount() {
        FriendsKeychain.clear()
        token = nil
        defaults.removeObject(forKey: Self.nickKey)
        uploaded = [:]
        feed = nil
        requests = nil
        signedIn = false
    }

    // MARK: - Reading

    func refresh() async {
        guard signedIn else { return }
        loading = feed == nil
        defer { loading = false }
        do {
            let c = try client()
            async let f = c.feed(days: 7)
            async let r = c.requests()
            feed = try await f
            requests = try await r
            errorText = nil
        } catch {
            handle(error)
        }
    }

    /// One person in full for their own page, or nil (with `errorText` set) when it cannot be read.
    func person(_ nick: String) async -> FriendProfile? {
        do { return try await client().person(nick, days: 7) }
        catch { handle(error); return nil }
    }

    func lookup(_ nick: String) async -> Result<FriendProfile, FriendsAPIError> {
        await result { try await self.client().lookup(nick) }
    }

    // MARK: - Friends

    func sendRequest(to nick: String) async -> FriendProfile? {
        await act { try await self.client().sendRequest(to: nick) }
    }

    func accept(_ nick: String) async { _ = await act { try await self.client().accept(nick) } }

    func dropRequest(_ nick: String) async { await actNoAnswer { try await self.client().dropRequest(nick) } }

    func unfriend(_ nick: String) async { await actNoAnswer { try await self.client().unfriend(nick) } }

    // MARK: - Profile

    func rename(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != me?.name else { return }
        _ = await act { try await self.client().update(name: trimmed) }
    }

    /// Change the sharing switches. Turning one off erases that section on the server from every day
    /// already uploaded; turning one on sends the recent days again, since the server kept nothing.
    func setShare(_ share: FriendsShare, repo: Repository) async {
        guard share != self.share else { return }
        guard await act({ try await self.client().update(share: share) }) != nil else { return }
        uploaded = [:]
        await uploadRecentDays(repo: repo)
        await refresh()
    }

    // MARK: - Uploading

    /// A scoring pass has finished (the cached days changed). Upload shortly after, once: a burst of
    /// refreshes becomes one upload, and an unchanged day is not sent at all. Does nothing signed out.
    func daysChanged(repo: Repository) {
        guard signedIn else { return }
        uploadTask?.cancel()
        uploadTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            await self?.uploadRecentDays(repo: repo)
        }
    }

    /// Build and send today and yesterday. Nothing is built for a section whose switch is off.
    func uploadRecentDays(repo: Repository) async {
        guard signedIn, let client = try? client() else { return }
        // The switches as the server last reported them; before the first answer, ask.
        let share: FriendsShare
        if let known = feed?.me.share { share = known }
        else if let asked = try? await client.me().share { share = asked }
        else { return }
        let today = Repository.logicalDay(Date())
        for offset in [1, 0] {
            guard let date = Calendar.current.date(byAdding: .day, value: -offset, to: today) else { continue }
            let key = Repository.localDayKey(date)
            let input = await FriendsUploader.input(repo: repo, dayKey: key, includeHeartRate: share.hr)
            let day = FriendsDayBuilder.day(input, share: share)
            guard !day.isEmpty, let body = try? FriendsUploader.encoder.encode(day), uploaded[key] != body else { continue }
            do {
                try await client.upload(day, on: key)
                uploaded[key] = body
            } catch {
                handle(error, quiet: true)
                return
            }
        }
    }

    // MARK: - Plumbing

    private func result<T>(_ call: @escaping () async throws -> T) async -> Result<T, FriendsAPIError> {
        do { return .success(try await call()) }
        catch let error as FriendsAPIError { return .failure(error) }
        catch { return .failure(.transport(error.localizedDescription)) }
    }

    private func act<T>(_ call: @escaping () async throws -> T) async -> T? {
        do {
            let value = try await call()
            errorText = nil
            await refresh()
            return value
        } catch {
            handle(error)
            return nil
        }
    }

    private func actNoAnswer(_ call: @escaping () async throws -> Void) async {
        _ = await act { try await call(); return true }
    }

    private func handle(_ error: Error, quiet: Bool = false) {
        if let api = error as? FriendsAPIError, api.isSignedOut {
            // The token is no longer an account. Nothing can renew it, so forget it.
            forgetAccount()
            errorText = String(localized: "This account no longer exists on the server.")
            return
        }
        if !quiet { errorText = Self.message(for: error) }
    }

    /// The server's codes in the wearer's language; its own English sentence only as a last resort.
    static func message(for error: Error) -> String {
        guard let api = error as? FriendsAPIError else { return error.localizedDescription }
        switch api {
        case .notConfigured:
            return String(localized: "Enter the address of your friends server. It must start with https://.")
        case .transport:
            return String(localized: "The friends server did not answer. Check the address and your connection.")
        case let .server(code, message, status):
            switch code {
            case "nick_taken": return String(localized: "This name is taken.")
            case "bad_nick": return String(localized: "A name is 3 to 20 Latin letters, digits or underscores.")
            case "bad_invite": return String(localized: "Wrong invite code.")
            case "server_full": return String(localized: "This server is not taking new accounts.")
            case "no_such_user": return String(localized: "No one has this name.")
            default:
                if status == 429 { return String(localized: "Too many attempts. Try again later.") }
                return message.isEmpty ? String(localized: "The friends server refused the request.") : message
            }
        }
    }
}

/// Gathers what the app has for one day. Reading only; `FriendsDayBuilder` decides what is sent.
@MainActor
enum FriendsUploader {
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    /// `dayKey` is the wearer's local calendar day ("yyyy-MM-dd"). Heart-rate samples are read only
    /// when that section is shared: a day of them is the one heavy read here.
    static func input(repo: Repository, dayKey: String, includeHeartRate: Bool) async -> FriendsDayBuilder.Input {
        var input = FriendsDayBuilder.Input()
        let row = repo.days.last(where: { $0.day == dayKey })
        input.recovery = row?.recovery
        input.strain = row?.strain
        input.restingBpm = row?.restingHr
        let rest = await repo.exploreSeries(key: "sleep_performance", source: "my-whoop")
        input.sleepScore = rest.last(where: { $0.day == dayKey })?.value

        if let night = await SleepNightLoader.night(repo: repo, wakeDayKey: dayKey), night.stages.asleep > 0 {
            input.sleep = .init(startTs: night.session.effectiveStartTs, endTs: night.session.endTs,
                                awakeMin: night.stages.awake, remMin: night.stages.rem,
                                lightMin: night.stages.light, deepMin: night.stages.deep,
                                needMin: SleepModel.sleepNeedMin(days: repo.days))
        }

        guard let bounds = dayBounds(dayKey) else { return input }
        input.workouts = await repo.workoutRows(days: 3)
            .filter { (bounds.start..<bounds.end).contains($0.startTs) }
            .map { .init(startTs: $0.startTs, endTs: $0.endTs, sport: $0.sport, durationS: $0.durationS,
                         strain: $0.strain, avgHr: $0.avgHr, maxHr: $0.maxHr, kcal: $0.energyKcal) }
        if includeHeartRate {
            input.heartRate = await repo.hrSamples(from: bounds.start, to: bounds.end - 1, limit: 90_000)
                .map { (ts: $0.ts, bpm: $0.bpm) }
        }
        return input
    }

    /// The local day `[start, end)` in unix seconds.
    nonisolated static func dayBounds(_ dayKey: String, calendar: Calendar = .current) -> (start: Int, end: Int)? {
        let parts = dayKey.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3,
              let start = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
              let end = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
        return (Int(start.timeIntervalSince1970), Int(end.timeIntervalSince1970))
    }
}
