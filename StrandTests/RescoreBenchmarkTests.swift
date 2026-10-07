import XCTest
import Foundation
import WhoopProtocol
import WhoopStore
import StrandAnalytics
@testable import Strand

/// Opt-in cost benchmark over a REAL on-device store copy. Skipped unless the runner is given one:
///
///     TEST_RUNNER_NOOP_BENCH_DB=/path/to/whoop.sqlite xcodebuild test … -only-testing:StrandTests/RescoreBenchmarkTests
///
/// It never touches the file it is given: the store is copied to a temporary directory first. It prints
/// `BENCH` lines (wall time per pass, per offload-sized insert) beside the engine's own cost lines, so a
/// change to the re-score or the insert path can be compared before and after on real data instead of a
/// synthetic fixture. There are no assertions on timing — machines differ; the numbers are the output.
@MainActor
final class RescoreBenchmarkTests: XCTestCase {
    private let deviceId = "my-whoop"

    private func benchStore() throws -> String {
        guard let src = ProcessInfo.processInfo.environment["NOOP_BENCH_DB"], !src.isEmpty else {
            throw XCTSkip("NOOP_BENCH_DB not set")
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-bench-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dst = dir.appendingPathComponent("whoop.sqlite")
        try FileManager.default.copyItem(atPath: src, toPath: dst.path)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dst.path
    }

    private func withIsolatedPreferences(_ body: () async throws -> Void) async throws {
        let defaults = UserDefaults.standard
        let keys = [
            "noop.analyzeWatermark", "analyzeRecent.stepsMotionCache.v1",
            RescoreBackgroundScheduler.owedKey, RescoreBackgroundScheduler.owedTokenKey,
            RescoreBackgroundScheduler.lastPassSecondsKey,
        ]
        let saved = keys.map { ($0, defaults.object(forKey: $0)) }
        defer {
            for (key, value) in saved {
                if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
            }
        }
        for key in keys { defaults.removeObject(forKey: key) }
        try await body()
    }

    func testRealStoreRescoreCost() async throws {
        let path = try benchStore()
        try await withIsolatedPreferences {
            let store = try await WhoopStore(path: path)
            let repo = Repository(deviceId: deviceId)
            repo.setStoreForTesting(store)
            let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: deviceId)
            var lines: [String] = []
            engine.diagnosticSink = { line, _ in lines.append(line) }

            let newest = try await store.hrFingerprint().maxTs
            for label in ["cold", "warm-unchanged", "warm-new-minute"] {
                if label == "warm-new-minute" {
                    let hr = (1...60).map { HRSample(ts: newest + $0, bpm: 70) }
                    _ = try await store.insert(Streams(hr: hr), deviceId: deviceId)
                }
                lines.removeAll()
                let t0 = Date()
                await engine.analyzeRecent(maxDays: 21, force: true)
                let wall = Date().timeIntervalSince(t0)
                print("BENCH rescore \(label) wall=\(Int(wall * 1000))ms")
                for l in lines where l.hasPrefix("analyzeRecent") || l.hasPrefix("re-score") {
                    print("BENCH   \(l)")
                }
            }
        }
    }

    func testRealStoreGateFingerprintCost() async throws {
        let path = try benchStore()
        let store = try await WhoopStore(path: path)
        for round in 0..<3 {
            let t0 = Date()
            _ = try await store.analysisFingerprint()
            print("BENCH analysisFingerprint round\(round) \(Int(Date().timeIntervalSince(t0) * 1000))ms")
        }
    }

    func testRealStoreOffloadChunkInsertCost() async throws {
        let path = try benchStore()
        let store = try await WhoopStore(path: path)
        let newest = try await store.hrFingerprint().maxTs
        // One WHOOP 4.0 history chunk is ~50 one-second records: HR + motion + resp + skin each second
        // and roughly one R-R per beat.
        var total = 0.0
        let chunks = 40
        for c in 0..<chunks {
            let base = newest + 1_000 + c * 50
            let secs = (0..<50).map { base + $0 }
            let streams = Streams(
                hr: secs.map { HRSample(ts: $0, bpm: 60) },
                rr: secs.map { RRInterval(ts: $0, rrMs: 1_000) },
                gravity: secs.map { GravitySample(ts: $0, x: 0, y: 0, z: 1) })
            let t0 = Date()
            _ = try await store.insert(streams, deviceId: deviceId)
            try await store.setCursor("strap_trim", c)
            total += Date().timeIntervalSince(t0)
        }
        print("BENCH offload insert+cursor per chunk avg=\(Int(total / Double(chunks) * 1000))ms over \(chunks) chunks")
    }
}
