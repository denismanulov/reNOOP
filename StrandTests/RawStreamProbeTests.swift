import XCTest
@testable import Strand

/// `RawStreamProbe` sends the WHOOP 4.0 raw-stream switch on its own and repeats it until the strap
/// answers. These pin the three things that matter: nothing is sent unless the probe is opted into, the
/// write repeats until acknowledged and then stops, and a stream the probe turned on is always turned
/// off again, even by a later launch that is not opted in.
final class RawStreamProbeTests: XCTestCase {

    private final class Harness {
        let defaults: UserDefaults
        var sent: [Bool] = []
        var logs: [String] = []
        var pending: [() -> Void] = []
        private(set) lazy var probe = RawStreamProbe(
            defaults: defaults,
            send: { [unowned self] on in self.sent.append(on) },
            log: { [unowned self] line in self.logs.append(line) },
            schedule: { [unowned self] _, work in self.pending.append(work) })

        init(enabled: Bool) {
            let name = "RawStreamProbeTests-\(UUID().uuidString)"
            defaults = UserDefaults(suiteName: name)!
            defaults.removePersistentDomain(forName: name)
            defaults.set(enabled, forKey: RawStreamProbe.enabledKey)
        }

        /// Runs the scheduled work that is due now. Work scheduled while running waits for the next call.
        func fire() {
            let due = pending
            pending = []
            due.forEach { $0() }
        }
    }

    func testDoesNothingWhenNotOptedIn() {
        let h = Harness(enabled: false)
        h.probe.liveStarted()
        h.probe.liveStopped()
        h.probe.connectSettled(liveWanted: true)
        h.fire()
        XCTAssertEqual(h.sent, [])
        XCTAssertFalse(h.defaults.bool(forKey: RawStreamProbe.armedKey))
    }

    func testRepeatsUntilTheStrapAnswersThenStops() {
        let h = Harness(enabled: true)
        h.probe.liveStarted()
        XCTAssertEqual(h.sent, [], "the write waits for its own moment, apart from the realtime arm")
        h.fire()
        h.fire()
        XCTAssertEqual(h.sent, [true, true])
        h.probe.responseReceived()
        h.fire()
        h.fire()
        XCTAssertEqual(h.sent, [true, true], "no write after the acknowledgement")
        XCTAssertTrue(h.defaults.bool(forKey: RawStreamProbe.armedKey))
    }

    func testGivesUpAfterTheAttemptLimit() {
        let h = Harness(enabled: true)
        h.probe.liveStarted()
        for _ in 0..<(RawStreamProbe.maxAttempts + 3) { h.fire() }
        XCTAssertEqual(h.sent.count, RawStreamProbe.maxAttempts)
    }

    func testStoppingTurnsTheStreamOffAndClearsTheMarkerOnAcknowledgement() {
        let h = Harness(enabled: true)
        h.probe.liveStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.liveStopped()
        h.fire()
        XCTAssertEqual(h.sent, [true, false])
        XCTAssertTrue(h.defaults.bool(forKey: RawStreamProbe.armedKey), "still on until the strap answers")
        h.probe.responseReceived()
        XCTAssertFalse(h.defaults.bool(forKey: RawStreamProbe.armedKey))
    }

    func testALaterLaunchWithoutTheOptInStillTurnsTheStreamOff() {
        let h = Harness(enabled: true)
        h.probe.liveStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.disconnected()
        h.defaults.set(false, forKey: RawStreamProbe.enabledKey)

        h.probe.connectSettled(liveWanted: false)
        h.fire()
        XCTAssertEqual(h.sent, [true, false])
        h.probe.responseReceived()
        XCTAssertFalse(h.defaults.bool(forKey: RawStreamProbe.armedKey))
    }

    func testReconnectWithALiveScreenUpTurnsTheStreamBackOn() {
        let h = Harness(enabled: true)
        h.probe.connectSettled(liveWanted: true)
        h.fire()
        XCTAssertEqual(h.sent, [true])
    }

    func testDisconnectCancelsPendingWrites() {
        let h = Harness(enabled: true)
        h.probe.liveStarted()
        h.probe.disconnected()
        h.fire()
        XCTAssertEqual(h.sent, [])
    }

    func testABurstSwitchesTheStreamWithoutTheLiveOptIn() {
        let h = Harness(enabled: false)
        h.probe.burstStarted()
        h.fire()
        h.probe.responseReceived()
        XCTAssertEqual(h.sent, [true])
        XCTAssertTrue(h.defaults.bool(forKey: RawStreamProbe.armedKey))
        h.probe.burstStopped()
        h.fire()
        h.probe.responseReceived()
        XCTAssertEqual(h.sent, [true, false])
        XCTAssertFalse(h.defaults.bool(forKey: RawStreamProbe.armedKey))
    }

    func testABurstEndingLeavesTheStreamOnForAnOptedInLiveScreen() {
        let h = Harness(enabled: true)
        h.probe.liveStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.burstStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.burstStopped()
        h.fire()
        XCTAssertEqual(h.sent, [true, true], "no off switch while the Live screen wants the stream")
    }

    func testTheLiveScreenLeavingDuringABurstLeavesTheStreamToTheBurst() {
        let h = Harness(enabled: true)
        h.probe.liveStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.burstStarted()
        h.fire()
        h.probe.responseReceived()
        h.probe.liveStopped()
        h.fire()
        XCTAssertEqual(h.sent, [true, true])
        h.probe.burstStopped()
        h.fire()
        XCTAssertEqual(h.sent, [true, true, false])
    }

    func testAResponseNobodyIsWaitingForChangesNothing() {
        let h = Harness(enabled: true)
        h.defaults.set(true, forKey: RawStreamProbe.armedKey)
        h.probe.responseReceived()
        XCTAssertTrue(h.defaults.bool(forKey: RawStreamProbe.armedKey))
        XCTAssertEqual(h.sent, [])
    }
}
