import XCTest
import Foundation
import WhoopProtocol
import WhoopStore
import StrandAnalytics
@testable import Strand

/// The wiring behind a tapped "I'm awake" (#461): the one pending mark, and what applying it does to
/// the night on record. The rule itself is `WakeMarkTrim`, tested in StrandAnalytics; these pin what
/// reaches the store and what stays pending.
@MainActor
final class PendingWakeMarkTests: XCTestCase {
    private let strap = "my-whoop"
    private var computed: String { strap + "-noop" }
    private var defaults: UserDefaults!
    private let suite = "PendingWakeMarkTests"

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    // MARK: - The pending mark

    func testThePendingMarkIsOneValueUnderTheSharedKey() {
        XCTAssertEqual(WakeMarkStore.pendingKey, "noop.pendingWakeMarkTs")
        XCTAssertNil(WakeMarkStore.pending(defaults))
        WakeMarkStore.setPending(1_791_000_000, defaults)
        XCTAssertEqual(defaults.integer(forKey: "noop.pendingWakeMarkTs"), 1_791_000_000)
        XCTAssertEqual(WakeMarkStore.pending(defaults), 1_791_000_000)
        // A newer tap replaces an older one.
        WakeMarkStore.setPending(1_791_000_600, defaults)
        XCTAssertEqual(WakeMarkStore.pending(defaults), 1_791_000_600)
        WakeMarkStore.clear(defaults)
        XCTAssertNil(WakeMarkStore.pending(defaults))
        XCTAssertNil(defaults.object(forKey: "noop.pendingWakeMarkTs"))
    }

    /// The mark is a request, not a setting or a measurement: a restored backup must not replay a tap.
    func testThePendingMarkIsNotInTheBackupWhitelist() {
        XCTAssertNil(BackupSettings.whitelist[WakeMarkStore.pendingKey])
        XCTAssertFalse(BackupSettings.appleDefaultsKey.values.contains(WakeMarkStore.pendingKey))
        WakeMarkStore.setPending(1_791_000_000, defaults)
        let snapshot = BackupSettings.snapshot(from: defaults)
        XCTAssertFalse(snapshot.values.contains { ($0 as? Int) == 1_791_000_000 })
    }

    // MARK: - Applying it

    private struct Fixture {
        let store: WhoopStore
        let repo: Repository
        let start: Int
        let end: Int
    }

    /// A night under the computed source that began eight hours ago and was detected to end a minute ago.
    private func fixture(endedAgo: Int = 60) async throws -> Fixture {
        let store = try await WhoopStore.inMemory()
        try DeviceRegistryStore(dbQueue: store.registryWriter).add(PairedDevice(
            id: strap, brand: "WHOOP", model: "4.0", sourceKind: .liveBLE,
            capabilities: [.hr, .hrv], status: .active, addedAt: 1, lastSeenAt: 1))
        let now = Int(Date().timeIntervalSince1970)
        let end = now - endedAgo
        let start = end - 8 * 3_600
        _ = try await store.upsertSleepSessions([detected(start: start, end: end)], deviceId: computed)
        let repo = Repository(deviceId: strap)
        repo.setStoreForTesting(store)
        return Fixture(store: store, repo: repo, start: start, end: end)
    }

    private func detected(start: Int, end: Int) -> CachedSleepSession {
        CachedSleepSession(startTs: start, endTs: end, efficiency: 0.9, restingHr: 54, avgHrv: 60,
                           stagesJSON: nil)
    }

    private func night(_ f: Fixture) async throws -> CachedSleepSession {
        let rows = try await f.store.sleepSessions(deviceId: computed, from: f.start - 10, to: f.start + 10,
                                                   limit: 5)
        return try XCTUnwrap(rows.first)
    }

    private func apply(_ f: Fixture, now: Int? = nil) async -> (trimmed: Bool, log: [String]) {
        var lines: [String] = []
        let trimmed = await PendingWakeMark.apply(
            repo: f.repo, defaults: defaults, now: now ?? Int(Date().timeIntervalSince1970)) { lines.append($0) }
        return (trimmed, lines)
    }

    func testAMarkShortlyBeforeTheDetectedEndEndsTheNightThere() async throws {
        let f = try await fixture()
        let wake = f.end - 186
        WakeMarkStore.setPending(wake, defaults)

        let result = await apply(f)

        XCTAssertTrue(result.trimmed)
        XCTAssertEqual(result.log, ["Sleep mark · wake moved the end of sleep 186 s earlier"])
        let after = try await night(f)
        XCTAssertEqual(after.endTs, wake, "the night ends at the mark")
        XCTAssertEqual(after.startTs, f.start, "the detected key never moves")
        XCTAssertEqual(after.effectiveStartTs, f.start, "the onset is left where it was")
        XCTAssertTrue(after.userEdited, "the trim goes through the hand-edit path")
        XCTAssertNil(WakeMarkStore.pending(defaults), "an applied mark is cleared")
    }

    /// The reason the trim uses the hand-edit path: the next pass re-detects the longer night, and must
    /// not write it back over the trimmed one.
    func testALaterReDetectionDoesNotRestoreTheLongerNight() async throws {
        let f = try await fixture()
        let wake = f.end - 19 * 60
        WakeMarkStore.setPending(wake, defaults)
        _ = await apply(f)

        _ = try await f.store.upsertSleepSessions([detected(start: f.start, end: f.end + 300)],
                                                  deviceId: computed)

        let after = try await night(f)
        XCTAssertEqual(after.endTs, wake)
        XCTAssertTrue(after.userEdited)
    }

    /// The usual order: the mark is tapped before the night has synced. It waits, and trims the night
    /// once a later pass has grown it past the mark.
    func testAMarkWaitsForItsNightAndTrimsItOnceItHasGrown() async throws {
        let f = try await fixture(endedAgo: 30 * 60)
        let wake = f.end + 27 * 60
        WakeMarkStore.setPending(wake, defaults)

        let early = await apply(f)
        XCTAssertFalse(early.trimmed)
        XCTAssertTrue(early.log.isEmpty)
        XCTAssertEqual(WakeMarkStore.pending(defaults), wake, "a waiting mark must still be there")
        let untouched = try await night(f)
        XCTAssertEqual(untouched.endTs, f.end)
        XCTAssertFalse(untouched.userEdited)

        // The next pass stores the same night running three minutes past the mark.
        _ = try await f.store.upsertSleepSessions([detected(start: f.start, end: wake + 180)],
                                                  deviceId: computed)
        let late = await apply(f)
        XCTAssertTrue(late.trimmed)
        XCTAssertEqual(late.log, ["Sleep mark · wake moved the end of sleep 180 s earlier"])
        let after = try await night(f)
        XCTAssertEqual(after.endTs, wake)
        XCTAssertNil(WakeMarkStore.pending(defaults))
    }

    /// A mark never extends a night, and one deeper than an hour inside it is a stray tap.
    func testAMarkAfterTheEndOrDeepInsideTheNightChangesNothing() async throws {
        let f = try await fixture(endedAgo: 600)
        for wake in [f.end, f.end + 300, f.end - 3_601, f.start] {
            WakeMarkStore.setPending(wake, defaults)
            let result = await apply(f)
            XCTAssertFalse(result.trimmed, "mark at \(wake - f.end) s from the end")
            let after = try await night(f)
            XCTAssertEqual(after.endTs, f.end)
            XCTAssertFalse(after.userEdited)
            XCTAssertEqual(WakeMarkStore.pending(defaults), wake)
        }
    }

    /// A mark older than twelve hours is forgotten, even when a night would fit it.
    func testAnOldMarkExpiresWithoutTouchingTheNight() async throws {
        let f = try await fixture()
        let wake = f.end - 186
        WakeMarkStore.setPending(wake, defaults)

        let result = await apply(f, now: wake + WakeMarkTrim.maxPendingSeconds + 1)

        XCTAssertFalse(result.trimmed)
        XCTAssertNil(WakeMarkStore.pending(defaults))
        let after = try await night(f)
        XCTAssertEqual(after.endTs, f.end)
        XCTAssertFalse(after.userEdited)
    }

    func testWithNoPendingMarkNothingHappens() async throws {
        let f = try await fixture()
        let result = await apply(f)
        XCTAssertFalse(result.trimmed)
        XCTAssertTrue(result.log.isEmpty)
        let after = try await night(f)
        XCTAssertEqual(after.endTs, f.end)
    }
}
