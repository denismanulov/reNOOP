import Foundation
import StrandAnalytics

// Scoring — agreement and calibration over plain label arrays.
//
// The definitions match `Tools/SleepPSG/Sources/sleeppsg/Scoring.swift` on purpose: a kappa that meant
// something slightly different here would make this tool's baseline incomparable with that one's, and
// `baseline --window labelled` reproducing its published sleep-accel figures is the check that they do.

let stageOrder = ["wake", "light", "deep", "rem"]

/// Per-epoch labels of a `[StageSegment]` tiling: each epoch takes the segment covering its start.
func epochLabels(_ stages: [StageSegment], start: Int, end: Int) -> [String] {
    let sorted = stages.sorted { $0.start < $1.start }
    var out: [String] = []
    var si = 0
    for t in stride(from: start, to: end, by: 30) {
        while si + 1 < sorted.count && sorted[si].end <= t { si += 1 }
        out.append(sorted.isEmpty ? "wake" : sorted[si].stage)
    }
    return out
}

/// A square confusion matrix, rows = reference, columns = prediction.
struct Confusion {
    var m = Array(repeating: Array(repeating: 0, count: stageOrder.count), count: stageOrder.count)

    mutating func add(ref: String, pred: String) {
        guard let r = stageOrder.firstIndex(of: ref), let c = stageOrder.firstIndex(of: pred) else { return }
        m[r][c] += 1
    }
    mutating func merge(_ o: Confusion) {
        for i in m.indices { for j in m.indices { m[i][j] += o.m[i][j] } }
    }
    var total: Int { m.reduce(0) { $0 + $1.reduce(0, +) } }
    var accuracy: Double {
        total == 0 ? .nan : Double(m.indices.reduce(0) { $0 + m[$1][$1] }) / Double(total)
    }
    /// Cohen's kappa: (p_o - p_e) / (1 - p_e).
    var kappa: Double {
        let t = Double(total)
        guard t > 0 else { return .nan }
        var pe = 0.0
        for i in m.indices {
            let row = Double(m[i].reduce(0, +)), col = Double(m.indices.reduce(0) { $0 + m[$1][i] })
            pe += (row / t) * (col / t)
        }
        return pe >= 1 ? .nan : (accuracy - pe) / (1 - pe)
    }
    /// One-vs-rest F1 of a stage; 0 when it is never predicted or never present.
    func f1(_ stage: String) -> Double {
        guard let k = stageOrder.firstIndex(of: stage) else { return .nan }
        let tp = m[k][k]
        let fn = m[k].reduce(0, +) - tp
        let fp = m.indices.reduce(0) { $0 + m[$1][k] } - tp
        return tp == 0 ? 0 : 2 * Double(tp) / Double(2 * tp + fn + fp)
    }
    func recall(_ stage: String) -> Double {
        guard let k = stageOrder.firstIndex(of: stage) else { return .nan }
        let row = m[k].reduce(0, +)
        return row == 0 ? .nan : Double(m[k][k]) / Double(row)
    }
    /// Share of the scored epochs the reference (rows) or the prediction (columns) spends in a stage, %.
    func share(_ stage: String, predicted: Bool) -> Double {
        guard let k = stageOrder.firstIndex(of: stage), total > 0 else { return .nan }
        let n = predicted ? m.indices.reduce(0) { $0 + m[$1][k] } : m[k].reduce(0, +)
        return 100 * Double(n) / Double(total)
    }
}

/// One night scored: the confusion over its scored epochs, and the shape measures kappa does not see.
struct NightScore {
    let night: Night
    let confusion: Confusion
    /// Minutes from the window start to the first deep epoch; nil when there is none.
    let deepLatencyTruth: Double?
    let deepLatencyPred: Double?
    /// Runs of consecutive wake epochs inside the window, and their lengths in minutes.
    let wakeBoutsTruth: [Double]
    let wakeBoutsPred: [Double]
}

func wakeBouts(_ labels: [String?]) -> [Double] {
    var out: [Double] = []
    var run = 0
    for l in labels {
        if l == "wake" { run += 1 } else { if run > 0 { out.append(Double(run) / 2) }; run = 0 }
    }
    if run > 0 { out.append(Double(run) / 2) }
    return out
}

func score(_ night: Night, start: Int, end: Int, pred: [String]) -> NightScore {
    let truth = night.truthGrid(start: start, end: end)
    var c = Confusion()
    for (t, p) in zip(truth, pred) { if let t = t { c.add(ref: t, pred: p) } }
    func latency(_ labels: [String?]) -> Double? {
        labels.firstIndex(of: "deep").map { Double($0) / 2 }
    }
    return NightScore(night: night, confusion: c,
                      deepLatencyTruth: latency(truth), deepLatencyPred: latency(pred.map { Optional($0) }),
                      wakeBoutsTruth: wakeBouts(truth), wakeBoutsPred: wakeBouts(pred.map { Optional($0) }))
}

func mean(_ v: [Double]) -> Double { v.isEmpty ? .nan : v.reduce(0, +) / Double(v.count) }
func median(_ v: [Double]) -> Double {
    guard !v.isEmpty else { return .nan }
    let s = v.sorted()
    return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
}
func fmt(_ v: Double, _ p: Int = 1) -> String { v.isNaN ? "n/a" : String(format: "%.\(p)f", v) }
func signed(_ v: Double, _ p: Int = 1) -> String { v.isNaN ? "n/a" : String(format: "%+.\(p)f", v) }
