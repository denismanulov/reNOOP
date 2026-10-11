import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// The learned stage model as Android runs it: the trees read from text. Two things are pinned here.
/// The reader takes a well-formed file and nothing else. And one whole night, from its streams to its
/// hypnogram, comes out as the committed oracle says, which is the file the Kotlin twin is tested
/// against: `android/app/src/test/resources/sleep_stage_oracle.json`.
///
/// To write the oracle again after the models or the features change:
/// `SLEEP_STAGE_ORACLE_WRITE=1 swift test --filter SleepStageTreesTests`, then commit the file.
final class SleepStageTreesTests: XCTestCase {

    override func tearDown() {
        SleepStageLearned.model = nil
        super.tearDown()
    }

    // MARK: - The reader

    /// Two columns, two classes, two trees: a branch with two leaves, and a lone leaf.
    private let small = """
        renoop-sleep-stage-trees 1
        # a comment
        source Small.mlmodel sha256 00ff
        classes b a
        transform softmax
        base 0.5 0.5
        meta note two words
        columns 2
        x
        y
        trees 2
        tree 3
        b 1 2.5 1 2
        l 0 1.0
        l 1 -1.0
        tree 1
        l 0 0.25

        """

    func testAFileIsReadAsItsColumnsClassesAndSettings() throws {
        let trees = try XCTUnwrap(SleepStageTrees(text: small))
        XCTAssertEqual(trees.columns, ["x", "y"])
        XCTAssertEqual(trees.classes, ["b", "a"])
        XCTAssertEqual(trees.meta, ["note": "two words"])
        XCTAssertEqual(trees.source, "00ff")
        XCTAssertEqual(trees.treeCount, 2)
    }

    func testARowWalksEachTreeAndTheSumsBecomeProbabilities() throws {
        let trees = try XCTUnwrap(SleepStageTrees(text: small))
        // y below 2.5: class b gets 0.5 + 1.0 + 0.25, class a stays at 0.5.
        let below = trees.probabilities([9, 2.4])
        XCTAssertEqual(below[0], 1 / (1 + exp(0.5 - 1.75)), accuracy: 1e-15)
        XCTAssertEqual(below[0] + below[1], 1, accuracy: 1e-15)
        // y not below it (the threshold itself is not below): b gets 0.5 + 0.25, a gets 0.5 - 1.0.
        let at = trees.probabilities([9, 2.5])
        XCTAssertEqual(at[0], 1 / (1 + exp(-0.5 - 0.75)), accuracy: 1e-15)
        XCTAssertEqual(trees.probabilities([9, 7]), at)
        // The missing-value mark is an ordinary number, and it is below every threshold.
        XCTAssertEqual(trees.probabilities([9, SleepStageFeatures.missing]), below)
    }

    func testProbabilitiesAreHandedOverInTheOrderAsked() throws {
        let trees = try XCTUnwrap(SleepStageTrees(text: small))
        let p = trees.probabilities([0, 0])
        XCTAssertEqual(trees.probabilities([[0, 0]], order: ["a", "b"]), [[p[1], p[0]]])
        XCTAssertNil(trees.probabilities([[0, 0]], order: ["a", "c"]), "a class the model does not have")
        XCTAssertNil(trees.probabilities([[0]], order: ["a", "b"]), "a row of the wrong width")
    }

    func testAnythingMalformedIsRefused() {
        func changed(_ from: String, _ to: String) -> String {
            XCTAssertTrue(small.contains(from), "the case must change something: \(from)")
            return small.replacingOccurrences(of: from, with: to)
        }
        XCTAssertNil(SleepStageTrees(text: ""))
        XCTAssertNil(SleepStageTrees(text: changed("trees 1\n# a", "trees 2\n# a")), "another format version")
        XCTAssertNil(SleepStageTrees(text: changed("transform softmax", "transform logistic")))
        XCTAssertNil(SleepStageTrees(text: changed("base 0.5 0.5", "base 0.5")), "a base per class")
        XCTAssertNil(SleepStageTrees(text: changed("b 1 2.5 1 2", "b 2 2.5 1 2")), "a column that is not there")
        XCTAssertNil(SleepStageTrees(text: changed("b 1 2.5 1 2", "b 1 2.5 0 2")), "a branch back to itself")
        XCTAssertNil(SleepStageTrees(text: changed("b 1 2.5 1 2", "b 1 2.5 1 3")), "a branch out of its tree")
        XCTAssertNil(SleepStageTrees(text: changed("l 1 -1.0", "l 2 -1.0")), "a class that is not there")
        XCTAssertNil(SleepStageTrees(text: changed("l 1 -1.0", "l 1 nan")))
        XCTAssertNil(SleepStageTrees(text: changed("tree 1\n", "tree 2\n")), "fewer nodes than promised")
        XCTAssertNil(SleepStageTrees(text: small + "l 0 1.0\n"), "lines left over")
        XCTAssertNil(SleepStageTrees(text: changed("trees 2\ntree", "trees 3\ntree")), "fewer trees than promised")
    }

    // MARK: - The shipped pair

    private func repositoryFile(_ relative: String) throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("\(relative) not found above \(#filePath): this test must not pass by default")
        throw CocoaError(.fileNoSuchFile)
    }

    private func shipped(_ name: String) throws -> SleepStageTrees {
        let url = try repositoryFile("android/app/src/main/assets/sleepstage/\(name).trees")
        return try XCTUnwrap(SleepStageTrees(text: String(contentsOf: url, encoding: .utf8)), "\(name).trees is not readable")
    }

    func testTheShippedFilesTakeThisBuildsColumnsAndCarryTheDecoderSettings() throws {
        let first = try shipped("SleepStageFirst"), second = try shipped("SleepStageSecond")
        XCTAssertEqual(first.columns, SleepStageContext.firstNames)
        XCTAssertEqual(second.columns, SleepStageContext.secondNames)
        XCTAssertEqual(Set(first.classes), Set(SleepStageDecoder.stages))
        XCTAssertEqual(Set(second.classes), Set(SleepStageDecoder.stages))
        XCTAssertEqual(first.source.count, 64)
        XCTAssertEqual(second.source.count, 64)
        let model = try XCTUnwrap(SleepStageModel(first: first, second: second, version: "trees"))
        XCTAssertEqual(model.prior?.count, 4)
        XCTAssertEqual(model.prior?.reduce(0, +) ?? 0, 1, accuracy: 1e-3)
        // A pair handed over the wrong way round takes the wrong columns and is refused.
        XCTAssertNil(SleepStageModel(first: second, second: first, version: "trees"))
    }

    // MARK: - One night, against the committed oracle

    private static let oraclePath = "android/app/src/test/resources/sleep_stage_oracle.json"
    /// The epochs whose full model inputs the oracle carries.
    private static let sampleEvery = 16

    private struct Night {
        var start: Int, end: Int
        var grav: [GravitySample], hr: [HRSample], rr: [RRInterval]
    }

    private struct Staged {
        var rows: [SleepStageFeatures.Row]
        var first: [[Double]], second: [[Double]]
        var p1: [[Double]], p2: [[Double]]
        var stages: [String]
        var segments: [StageSegment]
        /// The `.mlmodel` each tree file was written from, first then second.
        var sources: [String]
    }

    /// The night through every step, with the shipped trees installed as the model.
    private func stage(_ night: Night) throws -> Staged {
        let first = try shipped("SleepStageFirst"), second = try shipped("SleepStageSecond")
        let model = try XCTUnwrap(SleepStageModel(first: first, second: second,
                                                  version: first.source + second.source))
        let rows = SleepStageFeatures.rows(start: night.start, end: night.end, grav: night.grav, hr: night.hr, rr: night.rr)
        let xs = SleepStageContext.firstInput(rows)
        let p1 = try XCTUnwrap(model.first(xs))
        let xs2 = SleepStageContext.secondInput(first: xs, probabilities: p1)
        let p2 = try XCTUnwrap(model.second(xs2))
        let stages = SleepStageDecoder.decode(p2, prior: model.prior, weight: model.weight, smoothing: model.smoothing)
        SleepStageLearned.model = model
        let segments = try XCTUnwrap(
            SleepStageLearned.stageSession(start: night.start, end: night.end, grav: night.grav, hr: night.hr, rr: night.rr),
            "the oracle night is dense enough for the model")
        return Staged(rows: rows, first: xs, second: xs2, p1: p1, p2: p2, stages: stages, segments: segments,
                      sources: [first.source, second.source])
    }

    func testOneNightComesOutAsTheCommittedOracleSays() throws {
        if ProcessInfo.processInfo.environment["SLEEP_STAGE_ORACLE_WRITE"] == "1" {
            let night = Self.inventedNight()
            let text = Self.oracleText(night, try stage(night))
            let dir = try repositoryFile("android/app/src/test/resources")
            try text.write(to: dir.appendingPathComponent("sleep_stage_oracle.json"), atomically: true, encoding: .utf8)
        }
        let url = try repositoryFile(Self.oraclePath)
        let oracle = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let night = try Self.night(from: oracle)
        let staged = try stage(night)

        // Transcendental functions (log10 in the features, exp in the softmax) may differ in the last
        // place between platforms; everything discrete must be equal.
        func close(_ a: Double, _ b: Double) -> Bool { abs(a - b) <= 1e-9 * max(1, abs(a), abs(b)) }

        let features = try XCTUnwrap(oracle["features"] as? [[Any]])
        XCTAssertEqual(features.count, staged.rows.count)
        XCTAssertEqual(oracle["featureNames"] as? [String], SleepStageFeatures.names)
        for (i, expected) in features.enumerated() where i < staged.rows.count {
            XCTAssertEqual(expected.count, staged.rows[i].values.count)
            for (j, e) in expected.enumerated() {
                let got = staged.rows[i].values[j]
                if e is NSNull {
                    XCTAssertNil(got, "epoch \(i) \(SleepStageFeatures.names[j])")
                } else if let e = (e as? NSNumber)?.doubleValue, let got {
                    XCTAssertTrue(close(e, got), "epoch \(i) \(SleepStageFeatures.names[j]): \(got) is not \(e)")
                } else {
                    XCTFail("epoch \(i) \(SleepStageFeatures.names[j]): \(String(describing: got)) is not \(e)")
                }
            }
        }
        XCTAssertEqual((oracle["epochStarts"] as? [NSNumber])?.map(\.intValue), staged.rows.map(\.start))

        func matrix(_ key: String) throws -> [[Double]] {
            try XCTUnwrap(oracle[key] as? [[NSNumber]], key).map { $0.map(\.doubleValue) }
        }
        func compare(_ key: String, _ got: [[Double]]) throws {
            let expected = try matrix(key)
            XCTAssertEqual(expected.count, got.count, key)
            for (i, row) in expected.enumerated() where i < got.count {
                XCTAssertEqual(row.count, got[i].count, "\(key) row \(i)")
                for (j, e) in row.enumerated() where j < got[i].count {
                    XCTAssertTrue(close(e, got[i][j]), "\(key) row \(i) column \(j): \(got[i][j]) is not \(e)")
                }
            }
        }
        let sampled = try XCTUnwrap(oracle["sampleEpochs"] as? [NSNumber]).map(\.intValue)
        XCTAssertEqual(sampled, Array(stride(from: 0, to: staged.rows.count, by: Self.sampleEvery)))
        try compare("firstInput", sampled.map { staged.first[$0] })
        try compare("neighbours", sampled.map { Array(staged.second[$0].dropFirst(SleepStageContext.firstNames.count)) })
        try compare("firstProbabilities", staged.p1)
        try compare("secondProbabilities", staged.p2)

        XCTAssertEqual(oracle["stageOrder"] as? [String], SleepStageDecoder.stages)
        XCTAssertEqual(oracle["stages"] as? [String], staged.stages)
        let segments = try XCTUnwrap(oracle["segments"] as? [[Any]])
        XCTAssertEqual(segments.count, staged.segments.count)
        for (expected, got) in zip(segments, staged.segments) {
            XCTAssertEqual((expected[0] as? NSNumber)?.intValue, got.start)
            XCTAssertEqual((expected[1] as? NSNumber)?.intValue, got.end)
            XCTAssertEqual(expected[2] as? String, got.stage)
        }
        XCTAssertEqual(staged.segments.first?.start, night.start)
        XCTAssertEqual(staged.segments.last?.end, night.end)
        // An oracle that exercised one stage only would prove little about the decoder.
        XCTAssertGreaterThanOrEqual(Set(staged.stages).count, 3)

        let trees = try XCTUnwrap(oracle["trees"] as? [String: String])
        XCTAssertEqual(trees["first"], try shipped("SleepStageFirst").source, "the oracle was written from other trees")
        XCTAssertEqual(trees["second"], try shipped("SleepStageSecond").source, "the oracle was written from other trees")
    }

    // MARK: - Reading the oracle's night

    /// Streams are stored one entry a second from `from`, `null` where there is no sample, gravity in
    /// ten-thousandths of a g; beat intervals as seconds after `from` and milliseconds.
    private static func night(from oracle: [String: Any]) throws -> Night {
        let start = try XCTUnwrap(oracle["start"] as? Int), end = try XCTUnwrap(oracle["end"] as? Int)
        let g = try XCTUnwrap(oracle["gravity"] as? [String: Any])
        let gFrom = try XCTUnwrap(g["from"] as? Int), scale = try XCTUnwrap(g["scale"] as? Double)
        let gx = try XCTUnwrap(g["x"] as? [Any]), gy = try XCTUnwrap(g["y"] as? [Any]), gz = try XCTUnwrap(g["z"] as? [Any])
        var grav: [GravitySample] = []
        for i in gx.indices {
            guard let x = (gx[i] as? NSNumber)?.doubleValue, let y = (gy[i] as? NSNumber)?.doubleValue,
                  let z = (gz[i] as? NSNumber)?.doubleValue, !(gx[i] is NSNull) else { continue }
            grav.append(GravitySample(ts: gFrom + i, x: x / scale, y: y / scale, z: z / scale))
        }
        let h = try XCTUnwrap(oracle["heartRate"] as? [String: Any])
        let hFrom = try XCTUnwrap(h["from"] as? Int), bpm = try XCTUnwrap(h["bpm"] as? [Any])
        var hr: [HRSample] = []
        for i in bpm.indices {
            guard !(bpm[i] is NSNull), let b = (bpm[i] as? NSNumber)?.intValue else { continue }
            hr.append(HRSample(ts: hFrom + i, bpm: b))
        }
        let r = try XCTUnwrap(oracle["beatIntervals"] as? [String: Any])
        let rFrom = try XCTUnwrap(r["from"] as? Int)
        let at = try XCTUnwrap(r["at"] as? [Int]), ms = try XCTUnwrap(r["ms"] as? [Int])
        let rr = zip(at, ms).map { RRInterval(ts: rFrom + $0, rrMs: $1) }
        return Night(start: start, end: end, grav: grav, hr: hr, rr: rr)
    }

    // MARK: - Writing the oracle

    /// An invented two-hour night, not anyone's recording: a wrist that lies still, turns over now and
    /// then, moves about for five minutes in the middle, and a pulse that sinks, rises with the
    /// movement and grows restless for a stretch. A few seconds of motion, forty of pulse and ten
    /// minutes of beat intervals are left out, so the features meet missing data too.
    private static func inventedNight() -> Night {
        var seed: UInt64 = 0x2545_F491_4F6C_DD1D
        func next() -> Double {                                   // xorshift64*, in [0, 1)
            seed ^= seed >> 12; seed ^= seed << 25; seed ^= seed >> 27
            return Double((seed &* 0x2545_F491_4F6C_DD1D) >> 11) / Double(1 << 53)
        }
        let start = 1_749_513_600 + 2 * 3600 + 17, seconds = 2 * 3600
        let end = start + seconds
        let from = start - SleepStageFeatures.reach - 30, to = end + SleepStageFeatures.reach + 30
        let postures: [(Double, Double, Double)] = [(0.30, 0.10, 0.94), (-0.62, 0.20, 0.75), (0.05, -0.55, 0.83), (0.71, 0.02, 0.70)]
        var grav: [GravitySample] = [], hr: [HRSample] = [], rr: [RRInterval] = []
        var posture = 0, nextTurn = from + 600, burstUntil = 0, burstSize = 0.0
        var beatAt = Double(from)
        for t in from..<to {
            let k = t - start
            let awake = k < -240 || (k >= 3300 && k < 3600) || k >= seconds + 120
            if t >= nextTurn { posture = (posture + 1 + Int(next() * 3)) % postures.count; nextTurn = t + 900 + Int(next() * 900); burstUntil = t + 6; burstSize = 0.25 }
            if t >= burstUntil, next() < (awake ? 0.08 : 0.004) { burstUntil = t + 3 + Int(next() * (awake ? 30 : 8)); burstSize = 0.04 + 0.3 * next() }
            let moving = t < burstUntil
            let p = postures[posture]
            func axis(_ base: Double) -> Double {
                let v = base + 0.002 * sin(Double(t) / 4.1) + (moving ? burstSize * (2 * next() - 1) : 0.0006 * (2 * next() - 1))
                return (v * 10_000).rounded() / 10_000
            }
            let sample = GravitySample(ts: t, x: axis(p.0), y: axis(p.1), z: axis(p.2))
            if !(k > 0 && k % 977 < 3) { grav.append(sample) }
            let restless = k >= 5000 && k < 6400
            let pulse = 61 - 8 * Double(max(0, min(k, seconds))) / Double(seconds) + 2.5 * sin(Double(k) / 420)
                + (awake ? 9 : 0) + (moving ? 6 : 0) + (restless ? 4 + 3 * sin(Double(k) / 37) : 0) + (2 * next() - 1)
            let bpm = Int(pulse.rounded())
            if !(k >= 2000 && k < 2040) { hr.append(HRSample(ts: t, bpm: bpm)) }
            while beatAt < Double(t + 1) {
                let spread = restless ? 55.0 : (awake ? 25 : 38)
                let ms = Int((60_000 / pulse + spread * (2 * next() - 1) + 30 * sin(beatAt / 4.3)).rounded())
                if !(k >= 4200 && k < 4800) { rr.append(RRInterval(ts: t, rrMs: ms)) }
                beatAt += Double(ms) / 1000
            }
        }
        return Night(start: start, end: end, grav: grav, hr: hr, rr: rr)
    }

    private static func oracleText(_ night: Night, _ staged: Staged) -> String {
        func number(_ v: Double) -> String { v == v.rounded() && abs(v) < 1e15 ? String(Int64(v)) : "\(v)" }
        func row(_ values: [Double]) -> String { "[" + values.map(number).joined(separator: ",") + "]" }
        func rows(_ m: [[Double]]) -> String { "[\n    " + m.map(row).joined(separator: ",\n    ") + "\n  ]" }
        func strings(_ s: [String]) -> String { "[" + s.map { "\"\($0)\"" }.joined(separator: ",") + "]" }
        let from = night.start - SleepStageFeatures.reach - 30, to = night.end + SleepStageFeatures.reach + 30
        var gx = [String](repeating: "null", count: to - from), gy = gx, gz = gx
        for g in night.grav {
            gx[g.ts - from] = String(Int((g.x * 10_000).rounded()))
            gy[g.ts - from] = String(Int((g.y * 10_000).rounded()))
            gz[g.ts - from] = String(Int((g.z * 10_000).rounded()))
        }
        var bpm = [String](repeating: "null", count: to - from)
        for h in night.hr { bpm[h.ts - from] = String(h.bpm) }
        let sampled = Array(stride(from: 0, to: staged.rows.count, by: sampleEvery))
        let features = staged.rows.map { r in "[" + r.values.map { $0.map(number) ?? "null" }.joined(separator: ",") + "]" }
        var out = "{\n"
        out += "  \"about\": \"One invented night staged by the learned sleep stage model as a platform without Core ML runs it: the trees in android/app/src/main/assets/sleepstage read as text. Written by SleepStageTreesTests (SLEEP_STAGE_ORACLE_WRITE=1); the Swift and the Kotlin test both read it. Streams hold one entry a second from 'from', null where there is no sample; gravity is in 1/scale g; a beat interval is 'at' seconds after 'from' and 'ms' long. Probabilities are in stageOrder. firstInput and neighbours are given for sampleEpochs only: the first model's whole row, and the columns the second model's row adds to it.\",\n"
        out += "  \"trees\": {\"first\": \"\(staged.sources[0])\", \"second\": \"\(staged.sources[1])\"},\n"
        out += "  \"start\": \(night.start),\n  \"end\": \(night.end),\n"
        out += "  \"gravity\": {\"from\": \(from), \"scale\": 10000.0,\n    \"x\": [\(gx.joined(separator: ","))],\n    \"y\": [\(gy.joined(separator: ","))],\n    \"z\": [\(gz.joined(separator: ","))]},\n"
        out += "  \"heartRate\": {\"from\": \(from), \"bpm\": [\(bpm.joined(separator: ","))]},\n"
        out += "  \"beatIntervals\": {\"from\": \(from),\n    \"at\": [\(night.rr.map { String($0.ts - from) }.joined(separator: ","))],\n    \"ms\": [\(night.rr.map { String($0.rrMs) }.joined(separator: ","))]},\n"
        out += "  \"featureNames\": \(strings(SleepStageFeatures.names)),\n"
        out += "  \"epochStarts\": [\(staged.rows.map { String($0.start) }.joined(separator: ","))],\n"
        out += "  \"features\": [\n    \(features.joined(separator: ",\n    "))\n  ],\n"
        out += "  \"sampleEpochs\": [\(sampled.map(String.init).joined(separator: ","))],\n"
        out += "  \"firstInput\": \(rows(sampled.map { staged.first[$0] })),\n"
        out += "  \"neighbours\": \(rows(sampled.map { Array(staged.second[$0].dropFirst(SleepStageContext.firstNames.count)) })),\n"
        out += "  \"stageOrder\": \(strings(SleepStageDecoder.stages)),\n"
        out += "  \"firstProbabilities\": \(rows(staged.p1)),\n"
        out += "  \"secondProbabilities\": \(rows(staged.p2)),\n"
        out += "  \"stages\": \(strings(staged.stages)),\n"
        out += "  \"segments\": [\(staged.segments.map { "[\($0.start),\($0.end),\"\($0.stage)\"]" }.joined(separator: ","))]\n"
        out += "}\n"
        return out
    }
}
