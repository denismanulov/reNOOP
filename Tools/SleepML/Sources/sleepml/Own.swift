#if canImport(CreateML)
import CoreML
import Foundation
import SQLite3
import StrandAnalytics
import WhoopProtocol

// Own — the model beside the hypnograms a wearer's own database already holds.
//
// There is no ground truth here. A wearer's nights are a sanity check and never a training target: what
// can be asked of them is whether the model's hypnograms have the SHAPE the stored ones were faulted
// for lacking (deep sleep minutes after sleep begins, no sleep latency, one-epoch slivers between REM
// periods), and whether its stage shares on this hardware are plausible.
//
// The database is a copy, opened `immutable=1`: nothing is written, not even a journal. Streams are read
// the way the app reads them (`WhoopStore`'s queries, restated: the strap's beat intervals arrive on
// several transports and the app scores one per hour), and the proof that they are is printed first:
// the shipped stager replayed over them must give the stored hypnogram back, segment for segment.

final class ReadOnlyDB {
    private var handle: OpaquePointer?

    init(path: String) throws {
        let rc = sqlite3_open_v2("file:\(path)?immutable=1", &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, nil)
        guard rc == SQLITE_OK else { throw Failure("cannot open \(path): \(String(cString: sqlite3_errstr(rc)))") }
    }
    deinit { if let handle { sqlite3_close(handle) } }

    struct Failure: Error, CustomStringConvertible {
        let description: String
        init(_ d: String) { description = d }
    }

    func query(_ sql: String, _ each: (OpaquePointer) -> Void) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else {
            throw Failure("prepare failed: \(String(cString: sqlite3_errmsg(handle))) [\(sql.prefix(120))]")
        }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW { each(stmt!) }
    }

    func hasTable(_ name: String) -> Bool {
        var found = false
        try? query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '\(name)'") { _ in found = true }
        return found
    }

    /// The device id with the most rows in `table`.
    func busiestDevice(_ table: String) -> String? {
        var id: String? = nil
        try? query("SELECT deviceId FROM \(table) GROUP BY deviceId ORDER BY COUNT(*) DESC LIMIT 1") { s in
            id = sqlite3_column_text(s, 0).map { String(cString: $0) }
        }
        return id
    }
}

struct OwnNight {
    let start: Int
    let end: Int
    let stored: [StageSegment]
    let userEdited: Bool
}

extension ReadOnlyDB {
    /// Sessions whose stored stages are a segment timeline. The window is the hypnogram's own extent.
    func nights(device: String) throws -> [OwnNight] {
        var out: [OwnNight] = []
        try query("SELECT stagesJSON, userEdited FROM sleepSession WHERE deviceId = '\(device)' ORDER BY startTs") { s in
            let stages = AnalyticsEngine.decodeStages(sqlite3_column_text(s, 0).map { String(cString: $0) })
            guard let first = stages.map({ $0.start }).min(), let last = stages.map({ $0.end }).max() else { return }
            out.append(OwnNight(start: first, end: last, stored: stages, userEdited: sqlite3_column_int(s, 1) != 0))
        }
        return out
    }

    /// The three streams the stager takes over `[from, to]`, as `WhoopStore` reads them for scoring.
    func streams(device: String, from: Int, to: Int) throws -> (grav: [GravitySample], hr: [HRSample], rr: [RRInterval]) {
        var grav: [GravitySample] = [], hr: [HRSample] = [], rr: [RRInterval] = []
        let w = "deviceId = '\(device)' AND ts >= \(from) AND ts <= \(to)"
        try query("SELECT ts, x, y, z FROM gravitySample WHERE \(w) ORDER BY ts LIMIT 200000") { s in
            grav.append(GravitySample(ts: Int(sqlite3_column_int64(s, 0)), x: sqlite3_column_double(s, 1),
                                      y: sqlite3_column_double(s, 2), z: sqlite3_column_double(s, 3)))
        }
        // `WhoopStore.hrSamples`: the strap's heart rate, filled from the PPG-derived stream where absent.
        let ppg = hasTable("ppgHrSample") ? """
             UNION ALL SELECT p.ts, CAST(ROUND(p.bpm) AS INTEGER) FROM ppgHrSample p
             WHERE p.deviceId = '\(device)' AND p.ts >= \(from) AND p.ts <= \(to)
               AND NOT EXISTS (SELECT 1 FROM hrSample h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)
            """ : ""
        try query("SELECT ts, bpm FROM (SELECT ts, bpm FROM hrSample WHERE \(w)\(ppg)) ORDER BY ts LIMIT 200000") { s in
            hr.append(HRSample(ts: Int(sqlite3_column_int64(s, 0)), bpm: Int(sqlite3_column_int64(s, 1))))
        }
        // `WhoopStore.rrIntervals`, WHOOP 4 branch: one transport per UTC hour, in provenance order
        // (strap history 8, realtime 9, standard BLE 10, unlabelled), suspect timestamps excluded.
        // Merging transports would double the beat train on the nights that stored two.
        try query("""
            WITH hourChoice AS MATERIALIZED (
                SELECT ts / 3600 AS hour,
                       MIN(CASE WHEN srcChannel = 8 THEN 1 WHEN srcChannel = 9 THEN 2
                                WHEN srcChannel = 10 THEN 3 WHEN srcChannel IS NULL THEN 4 END) AS choice
                FROM rrInterval
                WHERE deviceId = '\(device)' AND ts >= (\(from) / 3600) * 3600 AND ts < ((\(to) / 3600) + 1) * 3600
                  AND (tsSuspect IS NULL OR tsSuspect <> 1) AND (srcChannel IS NULL OR srcChannel IN (8, 9, 10))
                GROUP BY ts / 3600)
            SELECT r.ts, r.rrMs FROM rrInterval r JOIN hourChoice h ON h.hour = r.ts / 3600
            WHERE r.deviceId = '\(device)' AND r.ts >= \(from) AND r.ts <= \(to)
              AND ((r.srcChannel = 8 AND h.choice = 1) OR (r.srcChannel = 9 AND h.choice = 2)
                   OR (r.srcChannel = 10 AND h.choice = 3) OR (r.srcChannel IS NULL AND h.choice = 4))
              AND (r.tsSuspect IS NULL OR r.tsSuspect <> 1)
            ORDER BY r.ts ASC, r.ord ASC, r.rrMs ASC, r.seq ASC LIMIT 200000
            """) { s in
            rr.append(RRInterval(ts: Int(sqlite3_column_int64(s, 0)), rrMs: Int(sqlite3_column_int64(s, 1))))
        }
        return (grav, hr, rr)
    }
}

/// The measures the stored hypnograms were faulted on, over one set of nights' epoch labels.
struct Shape {
    var nights = 0
    var firstEpochAsleep = 0
    var deepLatency: [Double] = []            // minutes from the first sleep epoch to the first deep one
    var awakenings: [Double] = []             // wake bouts after sleep has begun, per night
    var awakeningMinutes: [Double] = []
    var slivers: [Double] = []                // REM -> wake -> exactly one light epoch -> REM, per night
    var lateDeepNights = 0                    // more than 10 minutes of deep in the last third
    var seconds = [String: Double]()
    var changes: [Double] = []

    mutating func add(_ labels: [String]) {
        guard !labels.isEmpty else { return }
        nights += 1
        if labels[0] != "wake" { firstEpochAsleep += 1 }
        for l in labels { seconds[l, default: 0] += 30 }
        if let onset = labels.firstIndex(where: { $0 != "wake" }) {
            if let deep = labels.firstIndex(of: "deep") { deepLatency.append(Double(deep - onset) / 2) }
            let lastSleep = labels.lastIndex(where: { $0 != "wake" }) ?? onset
            let bouts = wakeBouts(Array(labels[onset...lastSleep]).map { Optional($0) })
            awakenings.append(Double(bouts.count))
            awakeningMinutes += bouts
        }
        var n = 0, i = 0
        while i < labels.count {                                   // rem, wake+, light x1, rem
            if labels[i] == "rem" {
                var j = i + 1
                while j < labels.count && labels[j] == "wake" { j += 1 }
                if j > i + 1 && j + 1 < labels.count && labels[j] == "light" && labels[j + 1] == "rem" { n += 1 }
            }
            i += 1
        }
        slivers.append(Double(n))
        let third = labels.count * 2 / 3
        if labels[third...].filter({ $0 == "deep" }).count > 20 { lateDeepNights += 1 }
        changes.append(Double(stageChanges(labels.map { Optional($0) })))
    }

    func share(_ stage: String) -> Double {
        let total = seconds.values.reduce(0, +)
        return total == 0 ? .nan : 100 * (seconds[stage] ?? 0) / total
    }
}

func runOwn(dbPath: String, modelPath: String, sessionsDevice: String?, streamsDevice: String?,
            featuresOut: String? = nil, withIntervals: Bool = true) throws {
    let db = try ReadOnlyDB(path: dbPath)
    guard let sDev = sessionsDevice ?? db.busiestDevice("sleepSession"),
          let dev = streamsDevice ?? db.busiestDevice("gravitySample") else {
        print("no sessions or no streams in \(dbPath)"); exit(1)
    }
    let nights = try db.nights(device: sDev)
    let compiled = try MLModel.compileModel(at: URL(fileURLWithPath: modelPath))
    let config = MLModelConfiguration()
    config.computeUnits = .cpuOnly
    let model = try MLModel(contentsOf: compiled, configuration: config)
    let meta = model.modelDescription.metadata[.creatorDefinedKey] as? [String: String] ?? [:]
    let prior = meta["classPrior"].map { $0.split(separator: ",").compactMap { Double($0) } }
    let weight = meta["priorWeight"].flatMap { Double($0) } ?? 1
    let smoothing = meta["smoothing"].flatMap { Double($0) } ?? 1
    if !withIntervals { print("beat intervals withheld from the model (the stored hypnograms had them)") }
    print("\(nights.count) stored hypnograms (sessions of \(sDev), streams of \(dev));"
          + " decoder: prior weight \(fmt(weight, 2)), smoothing \(fmt(smoothing, 1))")

    var stored = Shape(), model1 = Shape(), replayed = Shape()
    var exact = 0, skipped = 0, edited = 0
    var agree = Confusion()                                        // rows: stored, columns: model
    var latest: (stored: [String], model: [String])? = nil
    var noIntervals = 0
    var differing: [String] = []
    // With --out: the same table `sleepml features` writes, for these nights, so their inputs can be
    // set beside the training nights'. `stage` is empty (there is no truth); `stage_v2` is the stored label.
    var table = featuresOut == nil ? nil : (["dataset", "subject", "night", "ts", "stage", "stage_v2", "stage_model"]
        + SleepStageFeatures.names).joined(separator: ",") + "\n"
    for n in nights {
        let s = try db.streams(device: dev, from: n.start - 3600, to: n.end + 3600)
        // the app's own density gate for staging a window from raw
        let inWindow = s.grav.filter { $0.ts >= n.start && $0.ts <= n.end }.count
        if inWindow < max(20, (n.end - n.start) / 120) { skipped += 1; continue }
        let first = ((n.start + 29) / 30) * 30
        let replay = SleepStagerV2.stageSession(start: n.start, end: n.end, grav: s.grav, hr: s.hr, rr: s.rr, resp: [])
        if replay == n.stored { exact += 1 } else {
            if n.userEdited { edited += 1 }
            let a = epochLabels(n.stored, start: first, end: n.end), b = epochLabels(replay, start: first, end: n.end)
            let same = zip(a, b).filter { $0 == $1 }.count
            differing.append("\(Date(timeIntervalSince1970: TimeInterval(n.start)).formatted(.iso8601.year().month().day()))"
                + (n.userEdited ? " edited" : "") + " \(fmt(100 * Double(same) / Double(max(1, a.count)))) %")
        }
        let rows = SleepStageFeatures.rows(start: n.start, end: n.end, grav: s.grav, hr: s.hr,
                                           rr: withIntervals ? s.rr : [])
        let p = try probabilities(model, rows.map { SleepStageFeatures.modelInput($0) })
        let predicted = SleepStageDecoder.decode(p, prior: prior?.count == 4 ? prior : nil, weight: weight,
                                                 smoothing: smoothing)
        let was = epochLabels(n.stored, start: first, end: n.end)
        if table != nil {
            for (i, r) in rows.enumerated() {
                let vals = r.values.map { $0.map { String(format: "%.6g", $0) } ?? "" }
                table! += (["own", "own", String(n.start), String(r.start), "", i < was.count ? was[i] : "",
                            i < predicted.count ? predicted[i] : ""] + vals).joined(separator: ",") + "\n"
            }
        }
        stored.add(was)
        replayed.add(epochLabels(replay, start: first, end: n.end))
        model1.add(predicted)
        for (a, b) in zip(was, predicted) { agree.add(ref: a, pred: b) }
        latest = (was, predicted)
        if s.rr.filter({ $0.ts >= n.start && $0.ts <= n.end }).count < (n.end - n.start) / 4 { noIntervals += 1 }
    }
    print("wiring: the shipped stager replayed over these streams gives the stored hypnogram back exactly on"
          + " \(exact) of \(stored.nights) nights (\(edited) of the others were edited by hand); \(skipped)"
          + " sessions lack the raw streams and are left out")
    if !differing.isEmpty {
        print("  where it differs (night, share of epochs the replay and the stored hypnogram agree on): "
              + differing.joined(separator: "; "))
    }
    print("nights with beat intervals over less than a quarter of the window: \(noIntervals)")

    func line(_ name: String, _ f: (Shape) -> String) {
        print("  " + pad(name, 58) + rpad(f(stored), 16) + rpad(f(model1), 16))
    }
    print("\n  " + pad("", 58) + rpad("stored (V2)", 16) + rpad("model", 16))
    line("nights") { "\($0.nights)" }
    line("first epoch of the window is already sleep, nights") { "\($0.firstEpochAsleep)" }
    line("deep begins after the first sleep epoch, median min") { fmt(median($0.deepLatency)) }
    line("deep within 10 min of the first sleep epoch, nights") { "\($0.deepLatency.filter { $0 <= 10 }.count) of \($0.deepLatency.count)" }
    line("awakenings a night, median") { fmt(median($0.awakenings), 0) }
    line("an awakening lasts, median min") { fmt(median($0.awakeningMinutes)) }
    line("one-epoch light slivers between REM, mean a night") { fmt(mean($0.slivers), 2) }
    line("more than 10 min of deep in the last third, nights") { "\($0.lateDeepNights)" }
    line("stage changes a night, median") { fmt(median($0.changes), 0) }
    for st in stageOrder { line("\(st), share of all nights' time, %") { fmt($0.share(st)) } }
    if let l = latest {
        func shares(_ labels: [String]) -> String {
            stageOrder.map { st in "\(st) \(fmt(100 * Double(labels.filter { $0 == st }.count) / Double(labels.count), 0)) %" }
                .joined(separator: ", ")
        }
        print("\n  latest night: stored \(shares(l.stored))")
        print("                model  \(shares(l.model))")
    }
    print("\n  model against the stored hypnograms, epoch for epoch (agreement between two stagers, not accuracy):"
          + " \(fmt(100 * agree.accuracy)) % the same, kappa \(fmt(agree.kappa, 3))")
    print("  of the epochs stored as each stage, the model calls (rows stored; columns \(stageOrder.joined(separator: ", "))), %:")
    for (i, st) in stageOrder.enumerated() {
        let row = agree.m[i].reduce(0, +)
        print("    \(pad(st, 6))" + agree.m[i].map { rpad(fmt(row == 0 ? .nan : 100 * Double($0) / Double(row)), 7) }.joined())
    }
    _ = replayed
    if let out = featuresOut, let table = table {
        try table.write(toFile: out, atomically: true, encoding: .utf8)
        print("\n  features of these nights -> \(out)")
    }
}
#endif
