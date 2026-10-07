import Foundation
import WhoopProtocol

/// Notices a SECOND app pulling this strap's history.
///
/// A WHOOP keeps one history queue with one trim cursor, and CoreBluetooth shares the link system-wide, so
/// every app connected to the strap sees every notification. When another app (upstream NOOP, the WHOOP
/// app) asks for an offload, its history records reach us too — as type-47 HISTORICAL_DATA frames while
/// we are running no offload of our own (#494 established this for 5/MG; a WHOOP 4.0 behaves the same).
/// Whichever app acks a chunk first makes the strap drop it, so the other app never stores those hours:
/// on 2026-09-30 that is how a night reached upstream NOOP and never reached reNOOP.
///
/// The evidence has to be more than a trailing frame of our own session, which the strap can flush just
/// after we finish: nothing counts within `cooldownSeconds` of our own offload activity, and one verdict
/// needs `framesToFlag` records inside `windowSeconds` (one foreign chunk is ~50 records, a trailing
/// flush a handful). Pure and clock-injected so the thresholds are unit-tested without a strap.
struct ForeignOffloadDetector {
    static let cooldownSeconds: TimeInterval = 30
    static let windowSeconds: TimeInterval = 60
    static let framesToFlag = 20

    private(set) var lastOwnActivity: Date = .distantPast
    private var hits: [Date] = []

    /// Our own offload moved (request sent, a frame routed to the Backfiller, the session ended).
    mutating func noteOwnOffloadActivity(at now: Date) {
        lastOwnActivity = now
        hits.removeAll()
    }

    /// Feed one history record seen while we run no offload. True when it completes the evidence.
    mutating func noteHistoryOutsideOwnOffload(at now: Date) -> Bool {
        guard now.timeIntervalSince(lastOwnActivity) >= Self.cooldownSeconds else { return false }
        hits.removeAll { now.timeIntervalSince($0) >= Self.windowSeconds }
        hits.append(now)
        guard hits.count >= Self.framesToFlag else { return false }
        hits.removeAll()
        return true
    }

    /// A HISTORICAL_DATA record: the inner type byte is at frame[4] on WHOOP 4.0 and frame[8] under the
    /// 5/MG puffin envelope (the same offsets `BLEManager.isOffloadFrame` reads).
    static func isHistoryRecord(_ frame: [UInt8], family: DeviceFamily) -> Bool {
        let typeIndex = family == .whoop5 ? 8 : 4
        return frame.count > typeIndex && frame[typeIndex] == 47
    }
}
