import XCTest
@testable import Strand
import WhoopProtocol
import WhoopStore

/// The 2026-09-30 lost night, and what now guards against a repeat: a restore that leaves the process on
/// a replaced store must never ack a chunk, a second app pulling the strap's history must be noticed, and
/// the offload's per-chunk cadence must be measurable from a strap log.
final class OffloadSafetyTests: XCTestCase {

    private final class CountingStore: BackfillStoreWriting {
        var inserts = 0
        @discardableResult
        func insert(_ streams: Streams, deviceId: String) async throws
            -> (hr: Int, rr: Int, events: Int, battery: Int,
                spo2: Int, skinTemp: Int, resp: Int, gravity: Int) {
            inserts += 1
            return (streams.hr.count, streams.rr.count, 0, 0,
                    streams.spo2.count, streams.skinTemp.count, streams.resp.count, streams.gravity.count)
        }
        func enqueueRawBatch(_ meta: RawBatchMeta, frames: [[UInt8]]) async throws {}
        func setCursor(_ name: String, _ value: Int) async throws {}
        func cursor(_ name: String) async throws -> Int? { nil }
    }

    private func le32(_ v: UInt32) -> [UInt8] {
        [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
    }

    private func historyStartFrame() -> [UInt8] {
        frameFromPayload(le32(1_700_000_000) + [0, 0] + le32(0) + le32(0), type: 49, seq: 0, cmd: 1)
    }

    private func historyEndFrame(trim: UInt32) -> [UInt8] {
        frameFromPayload(le32(1_700_000_000) + [0, 0] + le32(0) + le32(trim), type: 49, seq: 0, cmd: 2)
    }

    // MARK: - a replaced store holds every ack

    @MainActor func testAReplacedStoreAcksNothingAndStalls() async {
        var acked: [UInt32] = []
        let backfiller = Backfiller(store: CountingStore(), deviceId: "test", ackTrim: { trim, _ in acked.append(trim) })
        backfiller.storeReplaced = { true }
        backfiller.begin(family: .whoop4)
        await backfiller.ingest(historyStartFrame())
        await backfiller.ingest(historyEndFrame(trim: 70_476))
        XCTAssertEqual(acked, [], "a chunk that cannot reach the restored store must stay on the strap")
        XCTAssertTrue(backfiller.persistStalled, "the stall stamp and the no-ack guard both key off this")
    }

    @MainActor func testTheSameChunkAcksWhenTheStoreWasNotReplaced() async {
        var acked: [UInt32] = []
        let backfiller = Backfiller(store: CountingStore(), deviceId: "test", ackTrim: { trim, _ in acked.append(trim) })
        backfiller.storeReplaced = { false }
        backfiller.begin(family: .whoop4)
        await backfiller.ingest(historyStartFrame())
        await backfiller.ingest(historyEndFrame(trim: 70_476))
        XCTAssertEqual(acked, [70_476], "control: the guard must not stall a healthy offload")
        XCTAssertFalse(backfiller.persistStalled)
    }

    func testOnlyTheLiveStorePathLatches() throws {
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("restore-\(UUID()).sqlite")
        XCTAssertFalse(LiveStoreReplacement.isLiveStore(scratch.path),
                       "a unit test restoring into a throwaway file must not latch the test host")
        let live = try StorePaths.defaultDatabasePath()
        XCTAssertTrue(LiveStoreReplacement.isLiveStore(live))
    }

    // MARK: - chunk cadence

    @MainActor func testChunkTimingSplitsPhoneAndStrapSides() async {
        let backfiller = Backfiller(store: CountingStore(), deviceId: "test", ackTrim: { _, _ in })
        backfiller.storeReplaced = { false }
        backfiller.begin(family: .whoop4)
        XCTAssertNil(backfiller.sessionChunkTiming.logLine, "nothing acked yet, nothing to say")
        await backfiller.ingest(historyStartFrame())
        await backfiller.ingest(historyEndFrame(trim: 1))
        await backfiller.ingest(historyStartFrame())
        await backfiller.ingest(historyEndFrame(trim: 2))
        let timing = backfiller.sessionChunkTiming
        XCTAssertEqual(timing.phoneCount, 2)
        XCTAssertEqual(timing.strapCount, 1, "only a START that follows one of OUR acks measures the strap")
        XCTAssertNotNil(timing.logLine)
        backfiller.begin(family: .whoop4)
        XCTAssertEqual(backfiller.sessionChunkTiming, Backfiller.ChunkTiming(), "a new session starts clean")
    }

    // MARK: - another app pulling the strap's history

    func testForeignHistoryNeedsTheFullBurstOutsideTheCooldown() {
        var d = ForeignOffloadDetector()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        d.noteOwnOffloadActivity(at: t0)
        // Our own trailing frames inside the cooldown never count, however many there are.
        for i in 0..<100 {
            XCTAssertFalse(d.noteHistoryOutsideOwnOffload(at: t0.addingTimeInterval(Double(i) * 0.2)))
        }
        let late = t0.addingTimeInterval(ForeignOffloadDetector.cooldownSeconds + 1)
        for i in 0..<(ForeignOffloadDetector.framesToFlag - 1) {
            XCTAssertFalse(d.noteHistoryOutsideOwnOffload(at: late.addingTimeInterval(Double(i))))
        }
        XCTAssertTrue(d.noteHistoryOutsideOwnOffload(at: late.addingTimeInterval(Double(ForeignOffloadDetector.framesToFlag))),
                      "a foreign chunk's worth of records inside the window is the evidence")
    }

    func testSparseStraysOutsideTheWindowNeverAddUp() {
        var d = ForeignOffloadDetector()
        let t0 = Date(timeIntervalSince1970: 2_000_000)
        for i in 0..<(ForeignOffloadDetector.framesToFlag * 3) {
            let t = t0.addingTimeInterval(Double(i) * (ForeignOffloadDetector.windowSeconds / 4))
            XCTAssertFalse(d.noteHistoryOutsideOwnOffload(at: t), "four strays a minute are not an offload")
        }
    }

    func testOwnActivityResetsTheEvidence() {
        var d = ForeignOffloadDetector()
        let t0 = Date(timeIntervalSince1970: 3_000_000)
        for i in 0..<(ForeignOffloadDetector.framesToFlag - 1) {
            _ = d.noteHistoryOutsideOwnOffload(at: t0.addingTimeInterval(Double(i) * 0.1))
        }
        d.noteOwnOffloadActivity(at: t0.addingTimeInterval(2))
        XCTAssertFalse(d.noteHistoryOutsideOwnOffload(at: t0.addingTimeInterval(3)),
                       "records right after our own request are ours")
    }

    func testHistoryRecordTypeByteSitsWhereEachFamilyPutsIt() {
        var w4 = [UInt8](repeating: 0, count: 12)
        w4[4] = 47
        XCTAssertTrue(ForeignOffloadDetector.isHistoryRecord(w4, family: .whoop4))
        XCTAssertFalse(ForeignOffloadDetector.isHistoryRecord(w4, family: .whoop5))
        var w5 = [UInt8](repeating: 0, count: 12)
        w5[8] = 47
        XCTAssertTrue(ForeignOffloadDetector.isHistoryRecord(w5, family: .whoop5))
        w4[4] = 43   // the live raw flood
        XCTAssertFalse(ForeignOffloadDetector.isHistoryRecord(w4, family: .whoop4))
        XCTAssertFalse(ForeignOffloadDetector.isHistoryRecord([0, 1], family: .whoop4))
    }

    func testWidgetLinksUseTheForkSchemeAndStillReadTheLegacyOne() {
        XCTAssertEqual(WidgetLink.today.url.absoluteString, "renoop://today",
                       "reNOOP must not claim upstream's noop:// scheme")
        XCTAssertEqual(WidgetLink(url: URL(string: "renoop://coach")!), .coach)
        XCTAssertEqual(WidgetLink(url: URL(string: "noop://coach")!), .coach, "links built before the rename")
        XCTAssertNil(WidgetLink(url: URL(string: "https://coach")!))
    }

    func testOtherAppsPhrase() {
        XCTAssertNil(OtherStrapApps.phrase([]))
        XCTAssertEqual(OtherStrapApps.phrase(["NOOP"]), "NOOP")
        XCTAssertNotNil(OtherStrapApps.phrase(["NOOP", "WHOOP"]))
    }
}
