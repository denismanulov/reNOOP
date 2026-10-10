//  FriendsStore.swift
//  NOOP · Friends — the account on this phone, what the tab shows, and the daily upload.
//
//  A reNOOP fork feature, and off until the wearer turns it on: until then no key exists, nothing is
//  sent anywhere and the rest of the app behaves exactly as it did. The server address is
//  configuration: the fork's own server until the wearer names another.
//
//  There is no name to type and no password. Turning Friends on makes this phone's key (`FriendsKey`,
//  in the Keychain, never in UserDefaults and never in a `.noopbak` backup) and an account bound to
//  the strap worn (`FriendsStrap`). The same strap on another phone asks to join that account; a new
//  strap on this phone takes the account over by itself.

import CryptoKit
import Foundation
import StrandAnalytics
import WhoopStore

/// The rules of the upload skip. A day goes up unless its text is the one the server last accepted for
/// it; what the phone keeps to tell the two apart is the text's SHA-256, not the text. And a phone does
/// not introduce a day with nothing to show: an upload replaces the day, and another phone of the
/// account may have uploaded it.
enum FriendsUploadPolicy {
    static func fingerprint(_ json: String) -> String {
        SHA256.hash(data: Data(json.utf8)).map { byte in
            let hex = String(byte, radix: 16)
            return hex.count == 1 ? "0" + hex : hex
        }.joined()
    }

    /// The same fingerprint for bytes: what tells one picture from another.
    static func fingerprint(_ data: Data) -> String { FriendsKey.hex(SHA256.hash(data: data)) }

    /// `isEmpty` is whether the day built has nothing to show a friend (`FriendsDay.isEmpty`). Such a
    /// day goes up only over one this phone sent before, which it then replaces.
    static func shouldUpload(lastAcceptedFingerprint: String?, json: String, isEmpty: Bool) -> Bool {
        guard let lastAcceptedFingerprint else { return !isEmpty }
        return lastAcceptedFingerprint != fingerprint(json)
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

/// Where Friends stands on this phone.
enum FriendsPhase: Equatable {
    /// Never turned on, or turned off: no request is made.
    case off
    /// Turn On was tapped, and the strap worn has a serial this phone has not read yet.
    case waitingForStrap
    /// The strap already has an account. The wearer says whether it is theirs.
    case strapBound
    /// This phone asked to join the strap's account and waits for an answer.
    case waiting(FriendsClaim)
    case on

    /// What is kept between launches; a claim in flight is kept beside it.
    var stored: String {
        switch self {
        case .off: return "off"
        case .waitingForStrap: return "waitingForStrap"
        case .strapBound: return "strapBound"
        case .waiting: return "waiting"
        case .on: return "on"
        }
    }
}

@MainActor
final class FriendsStore: ObservableObject {
    static let shared = FriendsStore()

    /// The server address when the wearer named their own. Plain configuration; the key is NOT here.
    static let addressKey = "friends.serverAddress"
    static let phaseKey = "friends.phase"
    static let claimKey = "friends.claim"
    /// When the kept feed arrived, and when a day last went up (device clock, unix seconds).
    static let feedAtKey = "friends.feedAt"
    static let uploadedAtKey = "friends.uploadedAt"
    /// One fingerprint per uploaded day, under this prefix and the day's key.
    static let markPrefix = "friends.uploaded."
    /// What this phone last sent of the wearer's profile, so it sends again only after a change made here.
    static let pushedNameKey = "friends.pushedName"
    static let pushedPhotoKey = "friends.pushedPhoto"
    /// Set when this phone joined an account that already had a profile: the next run records this
    /// phone's profile as sent without sending it.
    static let adoptProfileKey = "friends.adoptProfile"
    /// Set while an enrolment has been sent and no answer to it has come: the account may exist.
    static let enrolPendingKey = "friends.enrolPending"
    static let sharePhotoKey = "friends.sharePhoto"
    /// The strap handle the server last accepted for the account.
    static let boundHandleKey = "friends.boundHandle"
    /// The codes of the invites made on this phone, by invite id, so a waiting invite can be shown again.
    static let inviteCodesKey = "friends.inviteCodes"
    /// Coming back to the tab inside this long of a good answer shows that answer.
    static let autoRefreshEverySeconds = 60

    @Published private(set) var phase: FriendsPhase
    @Published private(set) var feed: FriendsFeed?
    @Published private(set) var loading = false
    /// The last failure, already worded for the wearer; nil once a call succeeds.
    @Published var errorText: String?
    /// When a day last went up from this phone (device clock, unix seconds).
    @Published private(set) var lastUploadAt: Int?
    /// The account's phones and its waiting invites, once a screen has asked for them.
    @Published private(set) var devices: [FriendsDevice]?
    @Published private(set) var invites: [FriendsInvite]?
    /// An invite code that arrived by a link and waits for the wearer's yes.
    @Published var pendingInviteCode: String?

    /// Which strap is worn. The app sets it once its device registry exists; until then nothing is known.
    var strapIdentity: @MainActor () -> FriendsStrap.Identity = { .pending }

    private let defaults: UserDefaults
    private let cache: FriendsFeedCache?
    private let keys: FriendsKeyStorage
    private let session: URLSession
    /// Deletes the kept pictures when the account is forgotten.
    private let clearPictures: () -> Void
    private var key: (any FriendsSigner)?
    /// Counts the accounts this process has seen: it moves whenever Friends goes on or off. Work that
    /// began under one carries the value it started with, and what it brings back is kept only while
    /// that value still stands. Without it, leaving and joining as someone else while a request was in
    /// flight could file the old account's answer, or the mark of a day uploaded to it, under the new one.
    private(set) var epoch = 0
    /// Device clock (unix seconds) when `feed` arrived from the server; 0 before any did.
    private var feedFetchedAt = 0
    /// Whether the last refresh failed, in which case coming back to the tab tries again at once.
    private var lastRefreshFailed = false
    private var uploadTask: Task<Void, Never>?
    private var uploadRun: Task<Void, Never>?

    /// `signer` nil reads the kept key; tests pass their own and keep the Keychain out of it. Tests
    /// also pass their own `clearPictures`, so the pictures the app itself keeps are left alone.
    init(defaults: UserDefaults = .standard, cache: FriendsFeedCache? = .standard,
         keys: FriendsKeyStorage = FriendsKeychainStorage(), session: URLSession = FriendsClient.plainSession,
         signer: (any FriendsSigner)? = nil, clearPictures: @escaping () -> Void = FriendsAvatars.clear) {
        self.defaults = defaults
        self.cache = cache
        self.keys = keys
        self.session = session
        self.clearPictures = clearPictures
        let address = defaults.string(forKey: Self.addressKey).flatMap(FriendsServerAddress.normalized)
            ?? FriendsServerAddress.standard
        let held = signer ?? FriendsKey.load(server: address, storage: keys)
        self.key = held
        // A key that could not be read leaves Friends off for now with what is kept untouched:
        // `recoverKey` brings the phase back once the key can be read.
        let phase = Self.keptPhase(in: defaults, hasKey: held != nil)
        self.phase = phase
        if phase == .on { loadKeptFeed() }
    }

    /// The phase kept between launches. On, and waiting on a claim, need this phone's key.
    private static func keptPhase(in defaults: UserDefaults, hasKey: Bool) -> FriendsPhase {
        switch defaults.string(forKey: phaseKey) {
        case "on" where hasKey:
            return .on
        case "waiting" where hasKey:
            let claim = defaults.data(forKey: claimKey).flatMap { try? JSONDecoder().decode(FriendsClaim.self, from: $0) }
            return claim.map { .waiting($0) } ?? .strapBound
        case "strapBound":
            return .strapBound
        case "waitingForStrap":
            return .waitingForStrap
        default:
            return .off
        }
    }

    private func loadKeptFeed() {
        feed = cache?.read()
        feedFetchedAt = defaults.integer(forKey: Self.feedAtKey)
        lastUploadAt = defaults.object(forKey: Self.uploadedAtKey) as? Int
    }

    var isOn: Bool { phase == .on }
    /// The server address in use; always one `FriendsServerAddress` accepts.
    var serverAddress: String {
        defaults.string(forKey: Self.addressKey).flatMap(FriendsServerAddress.normalized) ?? FriendsServerAddress.standard
    }
    var me: FriendProfile? { feed?.me }
    /// The wearer's own account id; empty before the first answer.
    var myID: String { feed?.me.id ?? "" }
    var share: FriendsShare { feed?.me.share ?? FriendsShare() }
    /// Requests waiting for this account's answer.
    var claims: [FriendsClaim] { feed?.claims ?? [] }
    /// The account's other phones that joined without confirmation: kept or removed from this one.
    var unconfirmed: [FriendsDevice] { feed?.unconfirmed ?? [] }
    /// Unix seconds until which this phone may only read and upload; nil for a confirmed phone.
    var probationUntil: Int? { feed?.me.device?.probationUntil }
    /// This phone's key id, which is its id among the account's phones.
    var deviceID: String? { key?.keyID }
    /// Whether the profile photo is sent for friends to see.
    var sharePhoto: Bool { defaults.object(forKey: Self.sharePhotoKey) as? Bool ?? true }

    /// The server's "now" (unix seconds): what every "N min ago" on the tab counts from.
    func serverNow(_ now: Date = Date()) -> Int {
        let device = Int(now.timeIntervalSince1970)
        guard let feed else { return device }
        return FriendsClock.serverNow(serverTime: feed.serverTime, fetchedAtDevice: feedFetchedAt, nowDevice: device)
    }

    /// A client signed with this phone's key. Without a key there is no client: nothing is sent.
    private func client() throws -> FriendsClient {
        guard let key else { throw FriendsAPIError.notEnrolled }
        guard let url = FriendsServerAddress.baseURL(serverAddress) else { throw FriendsAPIError.notConfigured }
        return FriendsClient(baseURL: url, signer: key, session: session)
    }

    /// The same, making the key first when there is none. The key exists from the first Turn On and
    /// never before it. The storage is read again first, and a key is made only when it says none is
    /// kept: one that is kept but cannot be read just now is never replaced.
    private func clientMakingKey() throws -> FriendsClient {
        if key == nil {
            switch FriendsKey.find(server: serverAddress, storage: keys) {
            case let .key(found): key = found
            case .absent: key = try FriendsKey.create(server: serverAddress, storage: keys)
            case .unreadable: throw FriendsKeyError.notRead
            }
        }
        return try client()
    }

    /// How this phone's key stands before a step that would use one or make one.
    private enum KeyCheck {
        /// A key is in hand, or none is kept and one may be made.
        case clear
        /// The key was read again and brought back the phase kept between launches.
        case resumed
        /// A key may be kept but cannot be read just now: nothing may change until it can.
        case unreadable
    }

    /// Reads the key again when this process holds none. A process the system started before the
    /// device's first unlock could not read it, and came up off with `friends.phase` still saying on,
    /// or waiting on a claim: with the key back, so is that phase.
    private func checkKey() -> KeyCheck {
        guard key == nil else { return .clear }
        switch FriendsKey.find(server: serverAddress, storage: keys) {
        case .absent:
            return .clear
        case .unreadable:
            return .unreadable
        case let .key(found):
            key = found
            let kept = Self.keptPhase(in: defaults, hasKey: true)
            guard kept != phase else { return .clear }
            phase = kept
            if kept == .on { loadKeptFeed() }
            return .resumed
        }
    }

    /// Brings Friends back by itself after a launch that could not read the key. Asks the storage only
    /// when what is kept says Friends was on or waiting on a claim, so a phone that never turned it on
    /// reads nothing.
    private func recoverKey() {
        guard key == nil, let kept = defaults.string(forKey: Self.phaseKey), kept == "on" || kept == "waiting" else { return }
        _ = checkKey()
    }

    /// Whether a step that turns Friends on may go on. It may not when the tap found Friends as it was
    /// kept, and not while a kept key cannot be read: then nothing is made, sent or changed.
    private func mayEnter() -> Bool {
        switch checkKey() {
        case .clear:
            return true
        case .resumed:
            errorText = nil
            return false
        case .unreadable:
            errorText = Self.message(for: FriendsKeyError.notRead)
            return false
        }
    }

    /// What naming a server came to.
    enum ServerChange: Equatable {
        case done
        /// The address is not one the app will talk to; nothing was written.
        case refused(FriendsServerAddress.Problem)
        /// Friends is not off (or is on behind a key that cannot be read just now); nothing was written.
        case notOff
    }

    /// Points Friends at the server the wearer names, or back at the standard one when `text` is empty.
    /// Only while Friends is off: a server is chosen before turning on. An address `FriendsServerAddress`
    /// refuses is not written. Another server is another account, so the key in hand becomes the one
    /// kept for that address (none is made here) and nothing remembered about the previous server
    /// stays. Naming the address already in use changes nothing.
    func setServerAddress(_ text: String) -> ServerChange {
        // Off behind a key that cannot be read just now is not off, and a key that has just come back
        // may have brought Friends back with it.
        guard mayEnter(), phase == .off, !loading else { return .notOff }
        let typed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var named: String?
        if !typed.isEmpty {
            switch FriendsServerAddress.validate(typed) {
            case let .success(address): named = address
            case let .failure(problem): return .refused(problem)
            }
        }
        guard (named ?? FriendsServerAddress.standard) != serverAddress else { return .done }
        if let named {
            defaults.set(named, forKey: Self.addressKey)
        } else {
            defaults.removeObject(forKey: Self.addressKey)
        }
        forgetAccount()
        FriendsClockOffset.shared.set(0)
        errorText = nil
        if case let .key(kept) = FriendsKey.find(server: serverAddress, storage: keys) {
            key = kept
        } else {
            key = nil
        }
        epoch += 1
        setPhase(.off)
        return .done
    }

    private func setPhase(_ next: FriendsPhase) {
        phase = next
        defaults.set(next.stored, forKey: Self.phaseKey)
        if case let .waiting(claim) = next, let data = try? JSONEncoder().encode(claim) {
            defaults.set(data, forKey: Self.claimKey)
        } else {
            defaults.removeObject(forKey: Self.claimKey)
        }
    }

    // MARK: - Turning on

    /// Turn Friends on: the one step that makes a key and sends anything. With a strap whose serial is
    /// known the account is bound to it; with a strap not read yet this waits for it.
    func turnOn(profile: ProfileStore) async { await turnOn(name: profile.displayName) }

    /// The same with the name given outright. The name is the profile's; this form exists for tests.
    func turnOn(name: String) async {
        await Task { await self.enter(name: name, bind: true) }.value
    }

    /// Turn Friends on with no strap to bind to, or beside a strap that belongs to someone else's
    /// account. The strap, once known, is bound or asked for by the next upload.
    func turnOnWithoutStrap(profile: ProfileStore) async { await turnOnWithoutStrap(name: profile.displayName) }

    func turnOnWithoutStrap(name: String) async {
        await Task { await self.enter(name: name, bind: false) }.value
    }

    /// Runs in a task of its own, so a view that goes away mid-request does not cut it short: an
    /// enrolment cut short would make the account and never show it. One at a time, and never once
    /// Friends is on: the tab asks again by itself while a strap is waited for, and a second enrolment
    /// with the same key would be answered as an account the key already had.
    private func enter(name: String, bind: Bool) async {
        guard mayEnter(), !loading, phase != .on else { return }
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            errorText = String(localized: "Enter your name first.")
            return
        }
        var strap: String?
        if bind {
            switch strapIdentity() {
            case .pending:
                errorText = nil
                setPhase(.waitingForStrap)
                return
            case let .handle(found): strap = found
            case .none: break
            }
        }
        loading = true
        defer { loading = false }
        do {
            let client = try clientMakingKey()
            // An earlier enrolment that got no answer may have made the account all the same.
            let unanswered = defaults.bool(forKey: Self.enrolPendingKey)
            defaults.set(true, forKey: Self.enrolPendingKey)
            let enrolled = try await client.enroll(name: name, strap: strap)
            // The strap sent is not recorded as accepted: the upload run's own request is what says so.
            // A key that was already a phone of an account joined that account as it stands, so this
            // phone sends neither its name nor its picture on joining. An account that an unanswered
            // enrolment of this phone's own made is not one it joined.
            let own = enrolled.isNew || unanswered
            becomeOn(me: enrolled.me, pushedName: own ? name : nil)
            if !own { defaults.set(true, forKey: Self.adoptProfileKey) }
            await refresh()
        } catch let error as FriendsAPIError where error.isStrapBound {
            defaults.removeObject(forKey: Self.enrolPendingKey)
            errorText = nil
            setPhase(.strapBound)
        } catch {
            // A refusal is an answer: no account was made. No answer leaves that in doubt.
            if let api = error as? FriendsAPIError, !api.isOffline { defaults.removeObject(forKey: Self.enrolPendingKey) }
            if !(error is CancellationError) { errorText = Self.message(for: error) }
        }
    }

    /// "Not Now" on the two pages that wait on the strap: Friends is off again, as before Turn On.
    /// Nothing is sent, and the strap being read later finishes nothing by itself. A key this phone
    /// holds is kept, and the next Turn On uses it.
    func notNow() {
        guard phase == .waitingForStrap || phase == .strapBound else { return }
        errorText = nil
        setPhase(.off)
    }

    /// "This is my account": ask to join the account the strap is bound to.
    func claimAccount(profile: ProfileStore) async { await claimAccount(name: profile.displayName) }

    func claimAccount(name: String) async {
        await Task { await self.claimNow(name: name) }.value
    }

    private func claimNow(name: String) async {
        guard mayEnter(), !loading, phase != .on else { return }
        guard case let .handle(strap) = strapIdentity() else {
            setPhase(.waitingForStrap)
            return
        }
        loading = true
        defer { loading = false }
        do {
            let claim = try await clientMakingKey().fileClaim(strap: strap)
            errorText = nil
            setPhase(.waiting(claim))
        } catch let error as FriendsAPIError where error.isStrapFree {
            // Its owner let the strap go meanwhile: there is no account to join, so make one.
            loading = false
            await enter(name: name, bind: true)
        } catch let error as FriendsAPIError where error.isAlreadyEnrolled {
            await adopt()
        } catch {
            if !(error is CancellationError) { errorText = Self.message(for: error) }
        }
    }

    /// Withdraws the request to join and goes back to the choice.
    func cancelClaim() async {
        await Task {
            try? await self.client().withdrawClaim()
            if case .waiting = self.phase { self.setPhase(.strapBound) }
        }.value
    }

    /// Asks how the request to join stands. Called when the tab appears and while it stays open.
    func pollClaim() async {
        guard case .waiting = phase else { return }
        do {
            let claim = try await client().myClaim()
            guard case .waiting = phase else { return }
            switch claim.state {
            case .pending:
                setPhase(.waiting(claim))
            case .approved:
                await adopt()
            case .declined:
                errorText = String(localized: "The request was declined on your other phone.")
                setPhase(.strapBound)
            case .expired:
                errorText = String(localized: "The request expired. Send it again.")
                setPhase(.strapBound)
            }
        } catch let error as FriendsAPIError where error.isNoClaim || error.isUnknownKey {
            // The claim is gone from the server: granted long ago and cleared away, or withdrawn.
            await adopt()
        } catch let error as FriendsAPIError where error.isOffline {
            // No answer is not an answer: the screen keeps showing the code.
        } catch {
            if !(error is CancellationError) { errorText = Self.message(for: error) }
        }
    }

    /// The request was granted: this phone is one of the account's. It joins without sending its own
    /// profile, since what the account already shows stands.
    private func adopt() async {
        do {
            let me = try await client().me()
            becomeOn(me: me, pushedName: nil)
            defaults.set(true, forKey: Self.adoptProfileKey)
            await refresh()
        } catch let error as FriendsAPIError where error.isUnknownKey {
            setPhase(.strapBound)
        } catch {
            if !(error is CancellationError) { errorText = Self.message(for: error) }
        }
    }

    private func becomeOn(me: FriendProfile, pushedName: String?) {
        // Nothing of an earlier account on this phone may stay under the new one.
        forgetAccount()
        if let pushedName { defaults.set(pushedName, forKey: Self.pushedNameKey) }
        epoch += 1
        errorText = nil
        setPhase(.on)
    }

    // MARK: - Leaving

    /// Delete the account on the server and forget it here. The strap is free again.
    func deleteAccount() async -> Bool {
        await Task {
            let session = self.epoch
            do {
                try await self.client().deleteAccount()
                if session == self.epoch { self.leave(deleteKey: true) }
                return true
            } catch {
                self.handle(error, session: session)
                // An account that is already gone is a deletion that worked.
                return !self.isOn
            }
        }.value
    }

    /// Takes this phone off the account, which goes on living on the wearer's other phones. The server
    /// refuses it for the account's only confirmed phone.
    func removeThisPhone() async -> Bool {
        guard let id = deviceID else { return false }
        return await Task {
            let session = self.epoch
            do {
                try await self.client().removeDevice(id)
                if session == self.epoch { self.leave(deleteKey: true) }
                return true
            } catch {
                self.handle(error, session: session)
                return !self.isOn
            }
        }.value
    }

    /// Friends goes off on this phone: the one way the kept feed, the pictures and the upload marks go
    /// away together, and queued uploads with them. The key goes too when the phone left by its own
    /// doing; when the server stopped knowing it the key stays, so turning Friends on again asks to
    /// rejoin as the same phone.
    private func leave(deleteKey: Bool) {
        uploadTask?.cancel()
        uploadTask = nil
        if deleteKey {
            FriendsKey.delete(server: serverAddress, storage: keys)
            key = nil
        }
        forgetAccount()
        epoch += 1
        setPhase(.off)
    }

    /// Everything kept about the account except the server address and the key. Whether the photo is
    /// shared stays too: it is the wearer's choice about their picture, not a fact about one account.
    private func forgetAccount() {
        for name in [Self.feedAtKey, Self.uploadedAtKey, Self.pushedNameKey, Self.pushedPhotoKey,
                     Self.adoptProfileKey, Self.enrolPendingKey, Self.boundHandleKey, Self.inviteCodesKey] {
            defaults.removeObject(forKey: name)
        }
        clearUploadMarks()
        cache?.clear()
        clearPictures()
        feed = nil
        feedFetchedAt = 0
        lastRefreshFailed = false
        devices = nil
        invites = nil
        lastUploadAt = nil
    }

    // MARK: - Reading

    /// What opening the tab and a pull both do. Before Friends is on it moves the turning-on along: a
    /// strap that has since been read is enrolled with, a request to join is asked about. Once on it
    /// sends today and yesterday if they changed and reads the feed; an automatic call inside
    /// `autoRefreshEverySeconds` of the last good answer does nothing, so switching tabs back and forth
    /// costs no request, and `force` (a pull, a change just made) always goes. A launch that could not
    /// read the key is put right first, here and wherever else Friends is refreshed.
    func sync(repo: Repository, profile: ProfileStore, force: Bool) async {
        recoverKey()
        switch phase {
        case .off, .strapBound:
            return
        case .waitingForStrap:
            if strapIdentity() != .pending { await turnOn(profile: profile) }
        case .waiting:
            await pollClaim()
        case .on:
            let age = Int(Date().timeIntervalSince1970) - feedFetchedAt
            if !force, feed != nil, !lastRefreshFailed, (0..<Self.autoRefreshEverySeconds).contains(age) { return }
            // The upload first, so the wearer's own card is what the server holds as of now.
            await uploadRecentDays(repo: repo, profile: profile)
            await refresh()
        }
    }

    func refresh() async {
        recoverKey()
        guard isOn else { return }
        let session = epoch
        loading = feed == nil
        defer { loading = false }
        do {
            let answered = try await client().feed()
            // The account this was asked under is gone meanwhile: its answer belongs to nobody on screen.
            guard session == epoch else { return }
            feed = answered.feed
            feedFetchedAt = Int(Date().timeIntervalSince1970)
            defaults.set(feedFetchedAt, forKey: Self.feedAtKey)
            cache?.write(answered.raw)
            lastRefreshFailed = false
            errorText = nil
        } catch {
            if session == epoch, !(error is CancellationError) { lastRefreshFailed = true }
            handle(error, session: session)
        }
    }

    /// One person in full for their own page, or nil (with `errorText` set) when it cannot be read.
    func person(_ id: String) async -> FriendProfile? {
        let session = epoch
        do { return try await client().person(id) }
        catch { handle(error, session: session); return nil }
    }

    /// A person's picture as the server holds it, or nil when there is none to show. Quiet: a picture
    /// that did not arrive is drawn as initials, and is no reason for a notice.
    func avatarBytes(of id: String) async -> Data? {
        let session = epoch
        guard isOn, let bytes = try? await client().avatar(id), session == epoch else { return nil }
        return bytes
    }

    // MARK: - Friends

    func unfriend(_ id: String) async { await actNoAnswer { try await self.client().unfriend(id) } }

    /// Makes a one-time invite and remembers its code, which the server says only now.
    func createInvite() async -> FriendsInvite? {
        guard let invite = await act({ try await self.client().createInvite() }, refreshing: false) else { return nil }
        if let code = invite.code {
            var kept = defaults.dictionary(forKey: Self.inviteCodesKey) as? [String: String] ?? [:]
            kept[invite.id] = code
            defaults.set(kept, forKey: Self.inviteCodesKey)
        }
        await loadInvites()
        return invite
    }

    /// The code of an invite made on this phone, or nil for one made elsewhere.
    func inviteCode(_ id: String) -> String? {
        (defaults.dictionary(forKey: Self.inviteCodesKey) as? [String: String])?[id]
    }

    func loadInvites() async {
        let session = epoch
        do {
            let found = try await client().invites()
            guard session == epoch else { return }
            invites = found
            // A code whose invite is used, revoked or expired is of no further use.
            let live = Set(found.map(\.id))
            let kept = (defaults.dictionary(forKey: Self.inviteCodesKey) as? [String: String] ?? [:]).filter { live.contains($0.key) }
            defaults.set(kept, forKey: Self.inviteCodesKey)
        } catch {
            handle(error, session: session)
        }
    }

    /// Whether the revoke itself went through, apart from the reload of the list after it.
    @discardableResult
    func revokeInvite(_ id: String) async -> Bool {
        let went = await actNoAnswer({ try await self.client().revokeInvite(id) }, refreshing: false)
        await loadInvites()
        return went
    }

    /// Uses an invite: whatever was typed, pasted or opened is read for its code. Answers the new friend,
    /// or nil with `errorText` set.
    func redeem(_ text: String) async -> FriendProfile? {
        guard let code = FriendsInviteCode.extract(text) else {
            errorText = String(localized: "That is not an invite code.")
            return nil
        }
        return await act { try await self.client().redeem(code) }
    }

    // MARK: - Requests on the strap

    /// Confirms a waiting request: a new phone joins the account, or the strap goes to who asked for it.
    /// The answers below return whether the call itself went through, whatever the refresh after it did.
    @discardableResult
    func approve(_ claim: FriendsClaim) async -> Bool { await actNoAnswer { try await self.client().approve(claim.id) } }

    @discardableResult
    func decline(_ claim: FriendsClaim) async -> Bool { await actNoAnswer { try await self.client().decline(claim.id) } }

    // MARK: - Phones

    /// Whether the list was loaded; a refusal or a lost connection leaves it as it was, with the reason
    /// in `errorText`.
    @discardableResult
    func loadDevices() async -> Bool {
        let session = epoch
        do {
            let found = try await client().devices()
            guard session == epoch else { return false }
            devices = found
            return true
        } catch {
            handle(error, session: session)
            return false
        }
    }

    @discardableResult
    func removeDevice(_ id: String) async -> Bool {
        let went = await actNoAnswer { try await self.client().removeDevice(id) }
        await loadDevices()
        return went
    }

    /// Keeps a phone that joined without confirmation: its probation ends now.
    @discardableResult
    func trustDevice(_ id: String) async -> Bool {
        let went = await actNoAnswer { try await self.client().trustDevice(id) }
        await loadDevices()
        return went
    }

    // MARK: - Profile and sharing

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

    /// Whether the profile photo is sent. Off removes the server's copy on the next run, which is now.
    func setSharePhoto(_ on: Bool, repo: Repository, profile: ProfileStore) async {
        guard on != sharePhoto else { return }
        defaults.set(on, forKey: Self.sharePhotoKey)
        objectWillChange.send()
        await uploadRecentDays(repo: repo, profile: profile)
        await refresh()
    }

    /// Everything the server holds for the account, laid out to be read; nil when it cannot be fetched.
    func exportText() async -> String? {
        let session = epoch
        do {
            let raw = try await client().export()
            guard let object = try? JSONSerialization.jsonObject(with: raw),
                  let pretty = try? JSONSerialization.data(
                    withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]) else {
                return String(decoding: raw, as: UTF8.self)
            }
            return String(decoding: pretty, as: UTF8.self)
        } catch {
            handle(error, session: session)
            return nil
        }
    }

    // MARK: - Uploading

    /// A scoring pass has finished (the cached days changed). Upload shortly after, once: a burst of
    /// refreshes becomes one upload, and an unchanged day is not sent at all. Does nothing while Friends
    /// is off, and the strap sync that led here never waits on it or learns how it went.
    func daysChanged(repo: Repository, profile: ProfileStore) {
        recoverKey()
        guard isOn else { return }
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
        guard isOn else { return }
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
        guard isOn, let client = try? client() else { return }
        // The account is read from the server first, so a switch turned off on another phone is honoured
        // here before anything is built.
        let account: FriendProfile
        do {
            account = try await client.me()
            guard session == epoch else { return }
        } catch {
            handle(error, quiet: true, session: session)
            return
        }
        guard let share = account.share else { return }
        // A phone that joined without confirmation reads and uploads its days; the strap and the profile
        // wait until it is confirmed.
        if account.device?.probationUntil == nil {
            await bindStrap(client, session: session)
            await pushProfile(client, profile: profile, avatarRev: account.avatarRev, session: session)
        }
        let days = await FriendsUploader.recentDays(repo: repo, profile: profile, share: share)
        let keep = Set(days.map(\.key))
        for day in days {
            // The account this run started under is gone: nothing more is sent with its key.
            guard session == epoch else { return }
            let now = Int(Date().timeIntervalSince1970)
            let built = FriendsDayBuilder.day(day.input, share: share, nowTs: now)
            let json = FriendsDayBuilder.json(built)
            guard FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: uploadMark(day.key), json: json,
                                                   isEmpty: built.isEmpty) else { continue }
            do {
                try await client.upload(json: json, on: day.key)
                guard session == epoch else { return }
                recordUpload(day.key, fingerprint: FriendsUploadPolicy.fingerprint(json), keep: keep, at: now)
            } catch {
                handle(error, quiet: true, session: session)
                // A refused day is not worth stopping for; a phone the server no longer knows, or no network, is.
                guard let api = error as? FriendsAPIError, !api.isUnknownKey, !api.isOffline else { return }
            }
        }
    }

    /// Tells the server which strap is worn, when it is not the one the server last accepted. A new
    /// strap on the same phone takes the account over this way, with nothing for the wearer to do. A
    /// strap that belongs to another account is asked for: the feed carries that request, and every run
    /// asks again until it is settled.
    private func bindStrap(_ client: FriendsClient, session: Int) async {
        guard case let .handle(strap) = strapIdentity(), strap != defaults.string(forKey: Self.boundHandleKey) else { return }
        do {
            let answer = try await client.putStrap(strap)
            guard session == epoch else { return }
            if answer == .bound { defaults.set(strap, forKey: Self.boundHandleKey) }
        } catch {
            handle(error, quiet: true, session: session)
        }
    }

    /// Sends the profile's name and photo when the wearer changed them on this phone since they were
    /// last sent. Never because the server's differ: two phones of one account with different profiles
    /// would otherwise overwrite each other forever. Photo switched off is the one exception: it is
    /// the wearer's word about the account's picture, so the picture goes whoever sent it. `avatarRev`
    /// is the account's as this run read it: 0 when it has no picture.
    private func pushProfile(_ client: FriendsClient, profile: ProfileStore, avatarRev: Int, session: Int) async {
        let name = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let photo = sharePhoto ? profile.avatarImageData.flatMap { FriendsAvatars.fitForUpload($0) } : nil
        let photoMark = photo.map { FriendsUploadPolicy.fingerprint($0) } ?? (sharePhoto ? "none" : "off")
        if defaults.bool(forKey: Self.adoptProfileKey) {
            defaults.set(name, forKey: Self.pushedNameKey)
            defaults.removeObject(forKey: Self.adoptProfileKey)
            // With Photo off nothing is recorded for the picture, so the removal below still happens.
            guard !sharePhoto else {
                defaults.set(photoMark, forKey: Self.pushedPhotoKey)
                return
            }
        }
        do {
            if !name.isEmpty, name != defaults.string(forKey: Self.pushedNameKey) {
                _ = try await client.update(name: name)
                guard session == epoch else { return }
                defaults.set(name, forKey: Self.pushedNameKey)
            }
            let sent = defaults.string(forKey: Self.pushedPhotoKey)
            guard photoMark != sent else { return }
            let sentAPicture = sent != nil && sent != "none" && sent != "off"
            if let photo {
                _ = try await client.putAvatar(photo)
            } else if sentAPicture || (!sharePhoto && avatarRev > 0) {
                try await client.deleteAvatar()
            }
            guard session == epoch else { return }
            defaults.set(photoMark, forKey: Self.pushedPhotoKey)
        } catch {
            handle(error, quiet: true, session: session)
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

    @discardableResult
    private func actNoAnswer(_ call: @escaping () async throws -> Void, refreshing: Bool = true) async -> Bool {
        await act({ try await call(); return true }, refreshing: refreshing) != nil
    }

    /// A phone the server no longer knows ends here, once, for every screen. An answer for an earlier
    /// account (Friends has since gone off, or on as someone else) says nothing about the one on screen.
    private func handle(_ error: Error, quiet: Bool = false, session: Int) {
        guard session == epoch else { return }
        // The request was cancelled with its task: nothing failed and nothing changed.
        if error is CancellationError { return }
        // Nothing was sent and nothing ended: Friends is off, which the tab already shows.
        if case .notEnrolled? = error as? FriendsAPIError { return }
        if let api = error as? FriendsAPIError, api.isUnknownKey {
            // Removed from the account by another phone, or the account is gone.
            leave(deleteKey: false)
            errorText = String(localized: "This phone is no longer part of the account. Turn Friends on to join again.")
            return
        }
        if !quiet { errorText = Self.message(for: error) }
    }

    /// The server's codes in the wearer's language; its own English sentence only as a last resort.
    static func message(for error: Error) -> String {
        if let key = error as? FriendsKeyError {
            switch key {
            case .notKept: return String(localized: "The key for Friends could not be saved on this device.")
            case .notRead: return String(localized: "The key for Friends could not be read right now. Try again in a moment.")
            }
        }
        guard let api = error as? FriendsAPIError else { return error.localizedDescription }
        switch api {
        case .notEnrolled:
            return String(localized: "Friends is off on this phone.")
        case .notConfigured:
            return String(localized: "The server address is not valid. It must start with https://.")
        case .transport:
            return String(localized: "The friends server did not answer. Check the address and your connection.")
        case .clockSkew:
            return String(localized: "This phone's clock is too far off. Set the date and time automatically.")
        case let .server(code, message, status):
            switch code {
            case "bad_name": return String(localized: "A name is 1 to 40 characters.")
            case "server_full": return String(localized: "This server is not taking new accounts.")
            case "strap_bound": return String(localized: "This strap already has an account.")
            case "probation": return String(localized: "This phone joined without confirmation. It can change things once it is confirmed.")
            case "claim_declined": return String(localized: "That request was declined. Try again in a week.")
            case "too_many_claims": return String(localized: "Too many requests are waiting on this strap. Try again later.")
            case "too_many_devices": return String(localized: "The account already has five phones. Remove one first.")
            case "last_device": return String(localized: "This is the account's only confirmed phone. Delete the account instead.")
            case "claim_settled", "no_claim": return String(localized: "That request is no longer waiting.")
            case "no_such_invite": return String(localized: "This code is not valid any more.")
            case "own_invite": return String(localized: "That is your own invite.")
            case "too_many_invites": return String(localized: "Too many invites are waiting. Revoke one first.")
            case "too_many_friends": return String(localized: "The friend limit is reached.")
            case "no_such_user": return String(localized: "This person is no longer here.")
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
