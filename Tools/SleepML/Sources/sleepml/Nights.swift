import Foundation
import StrandAnalytics
import WhoopProtocol

// Nights — the reduced datasets, as the streams a stager takes plus the PSG hypnogram to score against.
//
// `Tools/SleepML/reduce.py` writes every source in one shape; this reads that shape and nothing else, so
// a dataset added there needs no code here.

struct Night {
    let dataset: String
    let subject: String
    let id: String
    let grav: [GravitySample]
    let hr: [HRSample]
    let rr: [RRInterval]
    /// Scored epochs by their start second. An unscored or artifact epoch is absent.
    let truth: [Int: String]
    let firstLabel: Int
    let lastLabel: Int

    /// The label grid over `[start, end)`, nil where the PSG carries no score.
    func truthGrid(start: Int, end: Int) -> [String?] {
        stride(from: start, to: end, by: 30).map { truth[$0] }
    }
}

enum Window: String {
    /// First scored epoch to last: what `Tools/SleepPSG` hands the stager. Kept as the wiring check.
    case labelled
    /// Sleep onset to final awakening: what the app hands the stager, whose window comes from session
    /// detection and begins where the wearer fell asleep.
    case sleep
}

extension Night {
    /// Sleep onset is the first epoch of the first run of `onsetEpochs` consecutive scored sleep epochs,
    /// the rule `SleepStagerV2` itself uses for onset (5 minutes); the window ends with the last scored
    /// sleep epoch. nil when the night never sustains sleep that long.
    func window(_ kind: Window, onsetEpochs: Int = 10) -> (start: Int, end: Int)? {
        if kind == .labelled { return (firstLabel, lastLabel + 30) }
        var run = 0, onset: Int? = nil
        for t in stride(from: firstLabel, through: lastLabel, by: 30) {
            if let s = truth[t], s != "wake" { run += 1 } else { run = 0 }
            if run >= onsetEpochs { onset = t - 30 * (onsetEpochs - 1); break }
        }
        guard let start = onset else { return nil }
        var last = start
        for t in stride(from: lastLabel, through: start, by: -30) {
            if let s = truth[t], s != "wake" { last = t; break }
        }
        return (start, last + 30)
    }
}

enum Reduced {

    /// Every night listed in `<root>/<dataset>/nights.csv`, datasets in name order.
    static func load(root: String, only: String? = nil) -> [Night] {
        let fm = FileManager.default
        var out: [Night] = []
        for ds in ((try? fm.contentsOfDirectory(atPath: root)) ?? []).sorted() {
            if let only = only, only != ds { continue }
            let dir = (root as NSString).appendingPathComponent(ds)
            guard let index = try? String(contentsOfFile: (dir as NSString).appendingPathComponent("nights.csv"),
                                          encoding: .utf8) else { continue }
            let lines = index.split(whereSeparator: \.isNewline).map(String.init)
            guard let header = lines.first?.split(separator: ",", omittingEmptySubsequences: false).map(String.init),
                  let cSubject = header.firstIndex(of: "subject"), let cNight = header.firstIndex(of: "night")
            else { continue }
            for line in lines.dropFirst() {
                let f = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
                if f.count <= max(cSubject, cNight) { continue }
                if let n = night(dir: dir, dataset: ds, subject: f[cSubject], id: f[cNight]) { out.append(n) }
            }
        }
        return out
    }

    static func night(dir: String, dataset: String, subject: String, id: String) -> Night? {
        func path(_ suffix: String) -> String { (dir as NSString).appendingPathComponent(id + suffix) }
        var truth: [Int: String] = [:]
        guard let labels = try? String(contentsOfFile: path(".labels.csv"), encoding: .utf8) else { return nil }
        for line in labels.split(whereSeparator: \.isNewline).dropFirst() {
            let f = line.split(separator: ",", omittingEmptySubsequences: false)
            if f.count >= 2, let ts = Int(f[0]) { truth[ts] = String(f[1]) }
        }
        guard let first = truth.keys.min(), let last = truth.keys.max() else { return nil }

        var grav: [GravitySample] = []
        numbers(path(".grav.csv"), columns: 4) { v in
            grav.append(GravitySample(ts: Int(v[0]), x: v[1], y: v[2], z: v[3]))
        }
        // Heart rate as a strap reports it: whole beats per minute, implausible values dropped. The same
        // gate `Tools/SleepPSG` applies, so the two harnesses stage identical input.
        var hr: [HRSample] = []
        numbers(path(".hr.csv"), columns: 2) { v in
            let bpm = Int(v[1].rounded())
            if bpm > 0 && bpm < 300 { hr.append(HRSample(ts: Int(v[0]), bpm: bpm)) }
        }
        var rr: [RRInterval] = []
        numbers(path(".rr.csv"), columns: 2) { v in rr.append(RRInterval(ts: Int(v[0]), rrMs: Int(v[1]))) }
        return Night(dataset: dataset, subject: subject, id: id, grav: grav, hr: hr, rr: rr,
                     truth: truth, firstLabel: first, lastLabel: last)
    }

    /// Hand every data row of a numeric CSV to `body`. Byte-level: the gravity files run to tens of
    /// thousands of rows a night and a `String` per line dominates the load otherwise. A missing file
    /// is an empty stream (a source without beat intervals has no `.rr.csv`).
    static func numbers(_ path: String, columns: Int, _ body: ([Double]) -> Void) {
        guard let data = FileManager.default.contents(atPath: path) else { return }
        var bytes = [UInt8](data)
        bytes.append(0)
        var vals = [Double](repeating: 0, count: columns)
        bytes.withUnsafeBufferPointer { buf in
            guard let raw = buf.baseAddress else { return }
            let base = UnsafeRawPointer(raw).assumingMemoryBound(to: CChar.self)
            let count = buf.count - 1
            var i = 0, lineNo = 0
            while i < count {
                var lineEnd = i
                while lineEnd < count && buf[lineEnd] != 0x0A { lineEnd += 1 }
                defer { i = lineEnd + 1; lineNo += 1 }
                if lineNo == 0 { continue }                       // header
                var p = i, n = 0
                while p < lineEnd && n < columns {
                    var endPtr: UnsafeMutablePointer<CChar>?
                    let v = strtod(base + p, &endPtr)
                    guard let e = endPtr, UnsafePointer(e) > base + p else { break }
                    vals[n] = v
                    n += 1
                    p = UnsafePointer(e) - base + 1               // step over the comma
                }
                if n == columns { body(vals) }
            }
        }
    }
}
