//  FriendsStore.swift
//  NOOP · Friends — the account on this phone, what the tab shows, and the daily upload.
//
//  A reNOOP fork feature, and off until the wearer creates an account: with no token nothing is sent
//  anywhere and the rest of the app behaves exactly as it did. The one call made signed out is the one
//  the sign-up form asks for by name, whether a nickname is free. The server address is configuration:
//  the fork's own server until the wearer names another.
//
//  An account is a nickname and a password. The phone keeps the session token it got in return, in the
//  Keychain item below: it is not a setting, is never written to UserDefaults, and never enters a
//  `.noopbak` backup. The password itself is sent once and never stored anywhere on the phone.

import CryptoKit
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

/// The one rule of the upload skip: a day goes up unless its text is the one the server last accepted
/// for it. What the phone keeps to tell the two apart is the text's SHA-256, not the text.
enum FriendsUploadPolicy {
    static func fingerprint(_ json: String) -> String {
        SHA256.hash(data: Data(json.utf8)).map { byte in
            let hex = String(byte, radix: 16)
            return hex.count == 1 ? "0" + hex : hex
        }.joined()
    }

    static func shouldUpload(lastAcceptedFingerprint: String?, json: String) -> Bool {
        lastAcceptedFingerprint == nil || lastAcceptedFingerprint != fingerprint(json)
    }
}

/// The server's clock, as a feed pins it: `serverTime` as the feed stamped it, plus the device seconds
/// since the feed arrived. A device clock set back in between counts as no time. A phone whose own
/// clock is wrong still reads the right age of what a friend uploaded.
enum FriendsClock {
    static func serverNow(serverTime: Int, fetchedAtDevice: Int, nowDevice: Int) -> Int {
        serverTime + max(0, nowDevice - fetchedAtDevice)
    }
}

/// The last feed the server answered, kept so the tab opens at once and reads offline. A file in the
/// caches directory: it is the account's, goes with it on sign-out, and never enters a backup.
struct FriendsFeedCache {
    let file: URL

    static let standard: FriendsFeedCache? = FileManager.default
        .urls(for: .cachesDirectory, in: .userDomainMask).first
        .map { FriendsFeedCache(file: $0.appendingPathComponent("friends", isDirectory: true)
                                    .appendingPathComponent("feed.json")) }

    func read() -> FriendsFeed? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? FriendsClient.decode(data)
    }

    func write(_ raw: Data) {
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? raw.write(to: file, options: [.atomic])
    }

    func clear() { try? FileManager.default.removeItem(at: file) }
}

@MainActor
final class FriendsStore: ObservableObject {
    static let shared = FriendsStore()

    /// The server address when the wearer named their own, and the signed-in nickname (so the tab can
    /// name the account before the first answer). Plain configuration; the token is NOT here.
    static let addressKey = "friends.serverAddress"
    static let nickKey = "friends.nick"
    /// When the kept feed arrived, and when a day last went up (device clock, unix seconds).
    static let feedAtKey = "friends.feedAt"
    static let uploadedAtKey = "friends.uploadedAt"
    /// One fingerprint per uploaded day, under this prefix and the day's key.
    static let markPrefix = "friends.uploaded."
    /// Coming back to the tab inside this long of a good answer shows that answer.
    static let autoRefreshEverySeconds = 60

    @Published private(set) var signedIn: Bool
    @Published private(set) var feed: FriendsFeed?
    @Published private(set) var requests: FriendRequests?
    @Published private(set) var loading = false
    /// The last failure, already worded for the wearer; nil once a call succeeds.
    @Published var errorText: String?
    /// When a day last went up from this phone (device clock, unix seconds).
    @Published private(set) var lastUploadAt: Int?

    private let defaults: UserDefaults
    private let cache: FriendsFeedCache?
    private let keychain: Bool
    private let session: URLSession
    private var token: String?
    /// Counts the sessions this process has seen: it moves whenever one starts or ends. Work that began
    /// under one session carries the value it started with, and what it brings back is kept only while
    /// that value still stands. Without it, signing out and in as someone else while a request was in
    /// flight could file the old account's answer, or the mark of a day uploaded to it, under the new one.
    private(set) var epoch = 0
    /// Device clock (unix seconds) when `feed` arrived from the server; 0 before any did.
    private var feedFetchedAt = 0
    /// Whether the last refresh failed, in which case coming back to the tab tries again at once.
    private var lastRefreshFailed = false
    private var uploadTask: Task<Void, Never>?
    private var uploadRun: Task<Void, Never>?

    /// `token` nil reads the Keychain; tests pass their own and keep the Keychain out of it.
    init(defaults: UserDefaults = .standard, token: String? = nil, cache: FriendsFeedCache? = .standard,
         keychain: Bool = true, session: URLSession = FriendsClient.plainSession) {
        self.defaults = defaults
        self.cache = cache
        self.keychain = keychain
        self.session = session
        let held = token ?? (keychain ? FriendsKeychain.read() : nil)
        self.token = held
        self.signedIn = held != nil
        if held != nil {
            feed = cache?.read()
            feedFetchedAt = defaults.integer(forKey: Self.feedAtKey)
            lastUploadAt = defaults.object(forKey: Self.uploadedAtKey) as? Int
        }
    }

    /// The server address in use; always one `FriendsServerAddress` accepts.
    var serverAddress: String {
        defaults.string(forKey: Self.addressKey).flatMap(FriendsServerAddress.normalized) ?? FriendsServerAddress.standard
    }
    var usesStandardServer: Bool { serverAddress == FriendsServerAddress.standard }
    var nick: String { feed?.me.nick ?? defaults.string(forKey: Self.nickKey) ?? "" }
    var me: FriendProfile? { feed?.me }
    var share: FriendsShare { feed?.me.share ?? FriendsShare() }

    /// The server's "now" (unix seconds): what every "N min ago" on the tab counts from.
    func serverNow(_ now: Date = Date()) -> Int {
        let device = Int(now.timeIntervalSince1970)
        guard let feed else { return device }
        return FriendsClock.serverNow(serverTime: feed.serverTime, fetchedAtDevice: feedFetchedAt, nowDevice: device)
    }

    /// A client for the account, or an `anonymous` one for the calls the sign-in form makes. Without a
    /// session there is no client for anything else: signed out, nothing is sent.
    private func client(address: String? = nil, anonymous: Bool = false) throws -> FriendsClient {
        guard anonymous || token != nil else { throw FriendsAPIError.notSignedIn }
        guard let url = FriendsServerAddress.baseURL(address ?? serverAddress) else { throw FriendsAPIError.notConfigured }
        return FriendsClient(baseURL: url, token: anonymous ? nil : token, session: session)
    }

    // MARK: - Account

    /// What the sign-up form needs to know about a server. Asked together with the first nickname check,
    /// never on its own: opening the form sends nothing.
    func serverInfo(address: String) async -> Result<FriendsServerInfo, FriendsAPIError> {
        await result { try await self.client(address: address, anonymous: true).info() }
    }

    func isNickFree(_ nick: String, address: String) async -> Bool? {
        try? await client(address: address, anonymous: true).isNickFree(nick)
    }

    /// Create the account: the one step that turns the feature on. Returns true on success.
    func signUp(address: String, nick: String, password: String, name: String?, invite: String?) async -> Bool {
        await enter(address: address) { anonymous in
            try await anonymous.register(nick: nick, password: password, name: name?.isEmpty == false ? name : nil,
                                         invite: invite?.isEmpty == false ? invite : nil)
        }
    }

    /// Sign in to an account made earlier. Returns true on success.
    func signIn(address: String, nick: String, password: String) async -> Bool {
        await enter(address: address) { anonymous in
            try await anonymous.login(nick: nick, password: password)
        }
    }

    /// Both ways in end the same: a token to keep, an address and a name to remember, the first read.
    /// The call runs in a task of its own, so a sheet that goes away mid-request does not cut it short:
    /// a sign-up cut short would create the account and lose its token.
    private func enter(address: String,
                       _ call: @escaping (FriendsClient) async throws -> (token: String, me: FriendProfile)) async -> Bool {
        await Task { await self.enterNow(address: address, call) }.value
    }

    private func enterNow(address: String,
                          _ call: (FriendsClient) async throws -> (token: String, me: FriendProfile)) async -> Bool {
        loading = true
        defer { loading = false }
        do {
            guard let normalized = FriendsServerAddress.normalized(address) else { throw FriendsAPIError.notConfigured }
            let anonymous = try client(address: normalized, anonymous: true)
            let entered = try await call(anonymous)
            guard !keychain || FriendsKeychain.save(entered.token) else {
                // A token that cannot be kept is a session nobody can use or end: end it while it is in hand.
                var holder = anonymous
                holder.token = entered.token
                try? await holder.signOut()
                errorText = String(localized: "The account's key could not be saved on this device, so you were not signed in.")
                return false
            }
            // Nothing of an earlier account on this phone may stay under the new one.
            forgetAccount()
            token = entered.token
            // The fork's own server is the absence of a choice, so a later change of it is followed.
            if normalized == FriendsServerAddress.standard { defaults.removeObject(forKey: Self.addressKey) }
            else { defaults.set(normalized, forKey: Self.addressKey) }
            defaults.set(entered.me.nick, forKey: Self.nickKey)
            epoch += 1
            signedIn = true
            errorText = nil
            await refresh()
            return true
        } catch {
            errorText = Self.message(for: error)
            return false
        }
    }

    /// Sign this phone out. The account stays on the server, and a password signs back in. If the server
    /// cannot be reached the phone still forgets the token: leaving must not depend on the network.
    func signOut() async {
        await Task {
            try? await self.client().signOut()
            self.endSession()
        }.value
    }

    /// Change the password. The server ends every other session and sends this phone a fresh token.
    func changePassword(old: String, new: String) async -> Bool {
        await Task { await self.changePasswordNow(old: old, new: new) }.value
    }

    private func changePasswordNow(old: String, new: String) async -> Bool {
        let session = epoch
        do {
            let fresh = try await client().changePassword(old: old, new: new)
            guard session == epoch else { return false }
            guard !keychain || FriendsKeychain.save(fresh) else {
                // The old token was ended along with the rest; without the new one nothing works.
                endSession()
                errorText = String(localized: "The account's key could not be saved on this device, so you were signed out. Sign in with the new password.")
                return false
            }
            token = fresh
            errorText = nil
            return true
        } catch {
            handle(error, session: session)
            return false
        }
    }

    /// Delete the account on the server and forget it here. The server asks for the password again.
    func deleteAccount(password: String) async -> Bool {
        await Task { await self.deleteAccountNow(password: password) }.value
    }

    private func deleteAccountNow(password: String) async -> Bool {
        let session = epoch
        do {
            try await client().deleteAccount(password: password)
            if session == epoch { endSession() }
            return true
        } catch {
            // A wrong password here is a 401 that must not sign the phone out; only a dead session does.
            if let api = error as? FriendsAPIError, api.isSignedOut {
                if session == epoch { endSession() }
                return true
            }
            errorText = Self.message(for: error)
            return false
        }
    }

    /// Ends the session on this phone: the one way the token, the kept feed, the pictures and the upload
    /// marks go away together, and queued uploads with them.
    private func endSession() {
        uploadTask?.cancel()
        uploadTask = nil
        if keychain { FriendsKeychain.clear() }
        forgetAccount()
        epoch += 1
        signedIn = false
    }

    /// Everything kept about the account except the server address.
    private func forgetAccount() {
        token = nil
        defaults.removeObject(forKey: Self.nickKey)
        defaults.removeObject(forKey: Self.feedAtKey)
        defaults.removeObject(forKey: Self.uploadedAtKey)
        clearUploadMarks()
        cache?.clear()
        FriendsAvatars.clear()
        feed = nil
        feedFetchedAt = 0
        lastRefreshFailed = false
        requests = nil
        lastUploadAt = nil
    }

    // MARK: - Reading

    /// Sends today and yesterday if they changed, then reads the feed: what opening the tab and a pull
    /// both do. An automatic call inside `autoRefreshEverySeconds` of the last good answer does nothing,
    /// so switching tabs back and forth costs no request; `force` (a pull, a change just made) always goes.
    func sync(repo: Repository, profile: ProfileStore, force: Bool) async {
        guard signedIn else { return }
        let age = Int(Date().timeIntervalSince1970) - feedFetchedAt
        if !force, feed != nil, requests != nil, !lastRefreshFailed, (0..<Self.autoRefreshEverySeconds).contains(age) { return }
        // The upload first, so the wearer's own card is what the server holds as of now.
        await uploadRecentDays(repo: repo, profile: profile)
        await refresh()
    }

    func refresh() async {
        guard signedIn else { return }
        let session = epoch
        loading = feed == nil
        defer { loading = false }
        do {
            let c = try client()
            async let f = c.feed()
            async let r = c.requests()
            let (answered, asked) = (try await f, try await r)
            // The session this was asked under has ended meanwhile: its answer belongs to nobody on screen.
            guard session == epoch else { return }
            feed = answered.feed
            requests = asked
            feedFetchedAt = Int(Date().timeIntervalSince1970)
            defaults.set(feedFetchedAt, forKey: Self.feedAtKey)
            cache?.write(answered.raw)
            lastRefreshFailed = false
            errorText = nil
        } catch {
            if session == epoch { lastRefreshFailed = true }
            handle(error, session: session)
        }
    }

    /// One person in full for their own page, or nil (with `errorText` set) when it cannot be read.
    func person(_ nick: String) async -> FriendProfile? {
        let session = epoch
        do { return try await client().person(nick) }
        catch { handle(error, session: session); return nil }
    }

    func lookup(_ nick: String) async -> Result<FriendProfile, FriendsAPIError> {
        let session = epoch
        let answer = await result { try await self.client().lookup(nick) }
        if case let .failure(error) = answer, error.isSignedOut { handle(error, session: session) }
        return answer
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
    /// already uploaded, so what this phone remembers having sent no longer describes the server: the
    /// marks are dropped and today and yesterday go up again.
    func setShare(_ share: FriendsShare, repo: Repository, profile: ProfileStore) async {
        guard share != self.share else { return }
        await Task {
            guard await self.act({ try await self.client().update(share: share) }, refreshing: false) != nil else { return }
            self.clearUploadMarks()
            await self.uploadRecentDays(repo: repo, profile: profile)
            await self.refresh()
        }.value
    }

    /// Makes the on-device profile photo the picture friends see. Nothing is sent until the wearer asks
    /// for this by name. False (with `errorText` set) when there is no usable photo or the server refused.
    func uploadProfilePhoto(_ photo: Data?) async -> Bool {
        guard let jpeg = photo.flatMap({ FriendsAvatars.fitForUpload($0) }) else {
            errorText = String(localized: "This picture cannot be used.")
            return false
        }
        return await act { try await self.client().putAvatar(jpeg) } != nil
    }

    func removePicture() async -> Bool {
        await act { try await self.client().deleteAvatar(); return true } != nil
    }

    /// A person's picture as the server holds it, or nil when there is none to show. Quiet: a picture
    /// that did not arrive is drawn as initials, and is no reason for a notice.
    func avatarBytes(of nick: String) async -> Data? {
        let session = epoch
        guard signedIn, let bytes = try? await client().avatar(nick), session == epoch else { return nil }
        return bytes
    }

    // MARK: - Uploading

    /// A scoring pass has finished (the cached days changed). Upload shortly after, once: a burst of
    /// refreshes becomes one upload, and an unchanged day is not sent at all. Does nothing signed out,
    /// and the strap sync that led here never waits on it or learns how it went.
    func daysChanged(repo: Repository, profile: ProfileStore) {
        guard signedIn else { return }
        uploadTask?.cancel()
        uploadTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            await self?.uploadRecentDays(repo: repo, profile: profile)
        }
    }

    /// Build and send today and yesterday, one run at a time. Failure is silent by design: a day that
    /// did not go up goes up on the next trigger.
    func uploadRecentDays(repo: Repository, profile: ProfileStore) async {
        guard signedIn else { return }
        let previous = uploadRun
        let run = Task { [weak self] in
            await previous?.value
            await self?.uploadNow(repo: repo, profile: profile)
        }
        uploadRun = run
        await run.value
    }

    private func uploadNow(repo: Repository, profile: ProfileStore) async {
        let session = epoch
        guard signedIn, let client = try? client() else { return }
        // The switches are read from the server first, so one turned off on another phone is honoured
        // here before anything is built.
        let share: FriendsShare
        do {
            guard let asked = try await client.me().share, session == epoch else { return }
            share = asked
        } catch {
            handle(error, quiet: true, session: session)
            return
        }
        let days = await FriendsUploader.recentDays(repo: repo, profile: profile, share: share)
        let keep = Set(days.map(\.key))
        for day in days {
            // The session this run started under is gone: nothing more is sent with its token.
            guard session == epoch else { return }
            let now = Int(Date().timeIntervalSince1970)
            let json = FriendsDayBuilder.json(FriendsDayBuilder.day(day.input, share: share, nowTs: now))
            guard FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: uploadMark(day.key), json: json) else { continue }
            do {
                try await client.upload(json: json, on: day.key)
                guard session == epoch else { return }
                recordUpload(day.key, fingerprint: FriendsUploadPolicy.fingerprint(json), keep: keep, at: now)
            } catch {
                handle(error, quiet: true, session: session)
                // A refused day is not worth stopping for; a dead session or no network is.
                guard let api = error as? FriendsAPIError, !api.isSignedOut, !api.isOffline else { return }
            }
        }
    }

    /// The fingerprint of the text the server last accepted for `day`, or nil when none is on record.
    func uploadMark(_ day: String) -> String? { defaults.string(forKey: Self.markPrefix + day) }

    /// Records an accepted upload of `day` and forgets the marks of every day not in `keep`.
    private func recordUpload(_ day: String, fingerprint: String, keep: Set<String>, at now: Int) {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(Self.markPrefix) {
            let marked = String(key.dropFirst(Self.markPrefix.count))
            if marked != day, !keep.contains(marked) { defaults.removeObject(forKey: key) }
        }
        defaults.set(fingerprint, forKey: Self.markPrefix + day)
        defaults.set(now, forKey: Self.uploadedAtKey)
        lastUploadAt = now
    }

    /// Forgets every fingerprint, so the next upload sends each day again. Needed whenever the server
    /// may no longer hold what was sent.
    private func clearUploadMarks() {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(Self.markPrefix) {
            defaults.removeObject(forKey: key)
        }
    }

    // MARK: - Plumbing

    private func result<T>(_ call: @escaping () async throws -> T) async -> Result<T, FriendsAPIError> {
        do { return .success(try await call()) }
        catch let error as FriendsAPIError { return .failure(error) }
        catch { return .failure(.transport(error.localizedDescription)) }
    }

    private func act<T>(_ call: @escaping () async throws -> T, refreshing: Bool = true) async -> T? {
        let session = epoch
        do {
            let value = try await call()
            guard session == epoch else { return nil }
            errorText = nil
            if refreshing { await refresh() }
            return value
        } catch {
            handle(error, session: session)
            return nil
        }
    }

    private func actNoAnswer(_ call: @escaping () async throws -> Void) async {
        _ = await act { try await call(); return true }
    }

    /// A dead session ends here, once, for every screen. One that answers for an earlier session (the
    /// phone has since signed out, or in as someone else) says nothing about the one on screen.
    private func handle(_ error: Error, quiet: Bool = false, session: Int) {
        guard session == epoch else { return }
        // Nothing was sent and nothing ended: there is no account, which the tab already shows.
        if case .notSignedIn? = error as? FriendsAPIError { return }
        if let api = error as? FriendsAPIError, api.isSignedOut {
            // The token is no longer a session. Only a sign-in can replace it, so forget it.
            endSession()
            errorText = String(localized: "The session has ended. Sign in again.")
            return
        }
        if !quiet { errorText = Self.message(for: error) }
    }

    /// The server's codes in the wearer's language; its own English sentence only as a last resort.
    static func message(for error: Error) -> String {
        guard let api = error as? FriendsAPIError else { return error.localizedDescription }
        switch api {
        case .notSignedIn:
            return String(localized: "The session has ended. Sign in again.")
        case .notConfigured:
            return String(localized: "The server address is not valid. It must start with https://.")
        case .transport:
            return String(localized: "The friends server did not answer. Check the address and your connection.")
        case let .server(code, message, status):
            switch code {
            case "nick_taken": return String(localized: "This name is taken.")
            case "bad_nick": return String(localized: "A name is 3 to 20 Latin letters, digits or underscores.")
            case "bad_name": return String(localized: "A name is 1 to 40 characters.")
            case "bad_password": return String(localized: "A password is 8 to 128 characters.")
            case "bad_credentials": return String(localized: "Wrong name or password.")
            case "bad_invite": return String(localized: "Wrong invite code.")
            case "server_full": return String(localized: "This server is not taking new accounts.")
            case "no_such_user": return String(localized: "No one has this name.")
            case "self_request": return String(localized: "That is your own name.")
            case "no_request": return String(localized: "There is no request from this person any more.")
            case "too_many_friends": return String(localized: "The friend limit is reached.")
            case "too_many_requests": return String(localized: "Too many unanswered requests. Withdraw one first.")
            case "bad_image", "too_large": return String(localized: "This picture cannot be used.")
            default:
                if status == 429 { return String(localized: "Too many attempts. Try again later.") }
                if api.isRedirect { return String(localized: "This address did not answer like a friends server.") }
                if status >= 500 { return String(localized: "The server had a problem. Try again later.") }
                return message.isEmpty ? String(localized: "The friends server refused the request.") : message
            }
        }
    }
}

/// Reads a day's figures out of the app. The one place the upload touches the app's data, and it
/// derives nothing: every figure is asked of the resolver the wearer's own screens use, so a friend sees
/// what the wearer sees.
///
///   recovery, strain, sleepScore, restingBpm   `SummaryLoader.load` (the Summary's rings and Resting HR row)
///   sleep                                      `SleepNightLoader.night` (the Summary's Sleep card, itself
///                                              the Sleep tab's night), need as "Hours vs needed" has it
///   workouts                                   `Repository.workoutRows` (the Workouts tab's list)
///   heart rate                                 `Repository.hrSamples` (the samples the Summary's live
///                                              Strain is scored from)
@MainActor
enum FriendsUploader {
    /// How many days go up on each run: today and yesterday.
    static let days = 2
    /// A day at one sample a second is 86 400 rows; the ceiling the Summary's live Strain reads under.
    private static let hrReadLimit = 200_000

    /// A day's key ("yyyy-MM-dd", the app's own logical day) and what the app holds for it.
    struct Day {
        let key: String
        let input: FriendsDayBuilder.Input
    }

    /// Today and yesterday, newest first. Only the sections `share` has on are read at all: a
    /// switched-off section costs no query and exists nowhere outside the database.
    static func recentDays(repo: Repository, profile: ProfileStore, share: FriendsShare,
                           defaults: UserDefaults = .standard) async -> [Day] {
        let prefs = SummaryLoader.Prefs.stored(defaults)
        let now = Int(Date().timeIntervalSince1970)
        let allWorkouts = share.workouts ? await repo.workoutRows(days: days + 1) : nil
        var out: [Day] = []
        for offset in 0..<days {
            let key = SummaryDay.key(offset: offset, repo: repo)
            var input = FriendsDayBuilder.Input()
            if share.scores || share.hr {
                let snapshot = await SummaryLoader.load(repo: repo, profile: profile, offset: offset, prefs: prefs)
                if share.scores {
                    // A prior night carried onto an unscored today is that night's score, not today's: the
                    // Summary says so beside it, and a day on the wire has nowhere to say it.
                    if case let .scored(pct) = snapshot.charge { input.recovery = pct }
                    input.strain = snapshot.effort
                    input.sleepScore = snapshot.restOfDay
                }
                // The day's own row only, for the same reason as Recovery above.
                if share.hr { input.restingBpm = snapshot.metrics?.day?.restingHr }
            }
            if share.sleep, let night = await SleepNightLoader.night(repo: repo, wakeDayKey: key), night.stages.asleep > 0 {
                input.sleep = .init(startTs: night.session.effectiveStartTs, endTs: night.session.endTs,
                                    asleepMin: night.stages.asleep, awakeMin: night.stages.awake,
                                    remMin: night.stages.rem, lightMin: night.stages.light,
                                    deepMin: night.stages.deep, needMin: SleepModel.sleepNeedMin(days: repo.days))
            }
            if let bounds = dayBounds(key) {
                input.workouts = allWorkouts?.filter { (bounds.start..<bounds.end).contains($0.startTs) }.map(workout)
                let end = min(bounds.end - 1, now)
                if share.hr, end >= bounds.start {
                    input.heartRate = await repo.hrSamples(from: bounds.start, to: end, limit: hrReadLimit)
                        .map { (ts: $0.ts, bpm: $0.bpm) }
                }
            }
            out.append(Day(key: key, input: input))
        }
        return out
    }

    /// One row of the Workouts list as it goes to a friend: its English label, active time and stored
    /// Strain. The label is the locale-stable one, so each phone can show it in its reader's language.
    nonisolated static func workout(_ row: WorkoutRow) -> FriendsDayBuilder.WorkoutInput {
        .init(startTs: row.startTs, sport: WorkoutSource.editableSport(row.sport),
              durationS: FriendsDayBuilder.activeSeconds(durationS: row.durationS, startTs: row.startTs, endTs: row.endTs),
              strain: row.strain, avgHr: row.avgHr, maxHr: row.maxHr, kcal: row.energyKcal)
    }

    /// True for a day key as the server keys days, "yyyy-MM-dd".
    nonisolated static func isDayKey(_ text: String) -> Bool {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        return parts.map(\.count) == [4, 2, 2] && parts.allSatisfy { $0.allSatisfy { ("0"..."9").contains($0) } }
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
