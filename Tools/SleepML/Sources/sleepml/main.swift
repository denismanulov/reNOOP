import Foundation
import StrandAnalytics
import WhoopProtocol

// sleepml — see Package.swift for what this is and why it is Swift.
//
//   sleepml baseline [--reduced DIR] [--window sleep|labelled] [--dataset NAME]
//   sleepml features [--reduced DIR] [--window sleep|labelled] --out FILE.csv
//   sleepml train    [--table FILE.csv] [--out MODEL.mlmodel]      Create ML, folds by person (macOS)
//   sleepml speed    [--reduced DIR] --model MODEL.mlmodel         what a night costs to stage
//   sleepml own      --db COPY.sqlite --model MODEL.mlmodel        the model beside a wearer's stored nights
//
// The model is a pair. `train --out X.mlmodel` writes the first model there and the second, with the
// decoder settings, beside it as `X.second.mlmodel`; `speed` and `own` take the first one's path. The
// app ships the pair as `Strand/SleepModel/SleepStageFirst.mlmodel` and `SleepStageSecond.mlmodel`.

struct Args {
    var command = ""
    var reduced = ("~/datasets/reduced" as NSString).expandingTildeInPath
    var window = Window.sleep
    var dataset: String? = nil
    var out: String? = nil
    var table: String? = nil
    var model: String? = nil
    var db: String? = nil
    var noIntervals = false
    var sessionsDevice: String? = nil
    var streamsDevice: String? = nil
}

func parseArgs() -> Args {
    var a = Args()
    var it = CommandLine.arguments.dropFirst().makeIterator()
    a.command = it.next() ?? ""
    while let k = it.next() {
        switch k {
        case "--reduced": a.reduced = ((it.next() ?? "") as NSString).expandingTildeInPath
        case "--window": a.window = Window(rawValue: it.next() ?? "") ?? a.window
        case "--dataset": a.dataset = it.next()
        case "--out": a.out = ((it.next() ?? "") as NSString).expandingTildeInPath
        case "--table": a.table = ((it.next() ?? "") as NSString).expandingTildeInPath
        case "--model": a.model = ((it.next() ?? "") as NSString).expandingTildeInPath
        case "--db": a.db = ((it.next() ?? "") as NSString).expandingTildeInPath
        case "--no-intervals": a.noIntervals = true
        case "--sessions-device": a.sessionsDevice = it.next()
        case "--streams-device": a.streamsDevice = it.next()
        default: FileHandle.standardError.write("unknown argument \(k)\n".data(using: .utf8)!)
        }
    }
    return a
}

let usage = """
    usage: sleepml baseline [--reduced DIR] [--window sleep|labelled] [--dataset NAME]
           sleepml features [--reduced DIR] [--window sleep|labelled] --out FILE.csv
           sleepml train    [--table FILE.csv] [--out MODEL.mlmodel]
           sleepml speed    [--reduced DIR] --model MODEL.mlmodel
           sleepml own      --db COPY.sqlite --model MODEL.mlmodel [--out FEATURES.csv]
                            [--sessions-device ID] [--streams-device ID]

      --window sleep      sleep onset to final awakening (default): the window the app stages
      --window labelled   first scored epoch to last: reproduces Tools/SleepPSG on sleep-accel
    """

let args = parseArgs()

/// The shipped stager over one night's window, as epoch labels.
func stageShipped(_ n: Night, start: Int, end: Int, intervals: Bool) -> [String] {
    let segs = SleepStagerV2.stageSession(start: start, end: end, grav: n.grav, hr: n.hr,
                                          rr: intervals ? n.rr : [], resp: [])
    return epochLabels(segs, start: start, end: end)
}

// MARK: - baseline

/// Every metric the brief asks for over one group of nights. Two conventions for stage shares, both
/// named (see `Tools/SleepPSG/README.md`): pooled over all scored epochs, and the mean of per-night shares.
func report(_ title: String, _ rows: [NightScore]) {
    guard !rows.isEmpty else { return }
    var pooled = Confusion()
    for r in rows { pooled.merge(r.confusion) }
    let people = Set(rows.map { $0.dataset + "/" + $0.subject }).count
    print("\n\(title): \(people) people, \(rows.count) nights, \(pooled.total) scored epochs")
    print("  accuracy \(fmt(100 * pooled.accuracy, 2)) %   kappa \(fmt(pooled.kappa, 3))"
          + "   mean per-night kappa \(fmt(mean(rows.map { $0.confusion.kappa }.filter { !$0.isNaN }), 3))")
    print("  F1      " + stageOrder.map { "\($0) \(fmt(pooled.f1($0), 3))" }.joined(separator: "   ")
          + "     wake recall \(fmt(pooled.recall("wake"), 3))")
    print("  share of the window, pooled over epochs (predicted / true, bias in points):")
    print("          " + stageOrder.map {
        "\($0) \(fmt(pooled.share($0, predicted: true))) / \(fmt(pooled.share($0, predicted: false)))"
            + " (\(signed(pooled.share($0, predicted: true) - pooled.share($0, predicted: false))))"
    }.joined(separator: "   "))
    print("  share of the window, mean of per-night shares (predicted / true, bias, mean |bias|):")
    print("          " + stageOrder.map { st in
        let p = rows.map { $0.confusion.share(st, predicted: true) }
        let t = rows.map { $0.confusion.share(st, predicted: false) }
        let absBias = mean(zip(p, t).map { abs($0 - $1) })
        return "\(st) \(fmt(mean(p))) / \(fmt(mean(t))) (\(signed(mean(p) - mean(t))), \(fmt(absBias)))"
    }.joined(separator: "   "))
    let latT = rows.compactMap { $0.deepLatencyTruth }, latP = rows.compactMap { $0.deepLatencyPred }
    print("  deep latency from the window start, minutes: predicted median \(fmt(median(latP)))"
          + " (within 10 min on \(latP.filter { $0 <= 10 }.count) of \(rows.count) nights),"
          + " true median \(fmt(median(latT))) (within 10 min on \(latT.filter { $0 <= 10 }.count))")
    let boutsP = rows.map { Double($0.wakeBoutsPred.count) }, boutsT = rows.map { Double($0.wakeBoutsTruth.count) }
    print("  wake bouts a night: predicted median \(fmt(median(boutsP), 0)) (median length"
          + " \(fmt(median(rows.flatMap { $0.wakeBoutsPred }))) min), true median \(fmt(median(boutsT), 0))"
          + " (median length \(fmt(median(rows.flatMap { $0.wakeBoutsTruth }))) min)")
}

func runBaseline() {
    let nights = Reduced.load(root: args.reduced, only: args.dataset)
    if nights.isEmpty { print("no nights under \(args.reduced)"); exit(1) }
    print("shipped SleepStagerV2 against PSG; window: \(args.window.rawValue)")
    var all: [NightScore] = []
    var skipped = 0
    for ds in Set(nights.map { $0.dataset }).sorted() {
        var with: [NightScore] = [], without: [NightScore] = []
        for n in nights where n.dataset == ds {
            guard let w = n.window(args.window) else { skipped += 1; continue }
            let truth = n.truthGrid(start: w.start, end: w.end)
            with.append(score(dataset: n.dataset, subject: n.subject, truth: truth,
                              pred: stageShipped(n, start: w.start, end: w.end, intervals: true)))
            if !n.rr.isEmpty {
                without.append(score(dataset: n.dataset, subject: n.subject, truth: truth,
                                     pred: stageShipped(n, start: w.start, end: w.end, intervals: false)))
            }
        }
        report(ds, with)
        report("\(ds), beat intervals withheld", without)
        all += with
    }
    report("all datasets", all)
    if skipped > 0 { print("\nnights without sustained sleep, not scored: \(skipped)") }
}

// MARK: - features

func runFeatures() {
    guard let out = args.out else { print(usage); exit(2) }
    let nights = Reduced.load(root: args.reduced, only: args.dataset)
    FileManager.default.createFile(atPath: out, contents: nil)
    guard let handle = FileHandle(forWritingAtPath: out) else { print("cannot write \(out)"); exit(1) }
    let header = ["dataset", "subject", "night", "ts", "stage", "stage_v2", "stage_v2_no_rr"]
        + SleepStageFeatures.names
    handle.write((header.joined(separator: ",") + "\n").data(using: .utf8)!)
    var rowsWritten = 0, scored = 0, nightsWritten = 0
    var missing = [Int](repeating: 0, count: SleepStageFeatures.names.count)
    for n in nights {
        guard let w = n.window(args.window) else { continue }
        let rows = SleepStageFeatures.rows(start: w.start, end: w.end, grav: n.grav, hr: n.hr, rr: n.rr)
        let shipped = stageShipped(n, start: w.start, end: w.end, intervals: true)
        let shippedNoRR = n.rr.isEmpty ? shipped : stageShipped(n, start: w.start, end: w.end, intervals: false)
        var text = ""
        // Every epoch of the window is written, `stage` empty where the PSG carries no score: a decoder
        // needs the whole sequence, and training simply skips the unscored rows.
        for (i, r) in rows.enumerated() {
            let stage = n.truth[r.start] ?? ""
            if !stage.isEmpty {
                scored += 1
                for (k, v) in r.values.enumerated() where v == nil { missing[k] += 1 }
            }
            let vals = r.values.map { $0.map { String(format: "%.6g", $0) } ?? "" }
            text += ([n.dataset, n.subject, n.id, String(r.start), stage,
                      i < shipped.count ? shipped[i] : "", i < shippedNoRR.count ? shippedNoRR[i] : ""]
                     + vals).joined(separator: ",") + "\n"
            rowsWritten += 1
        }
        handle.write(text.data(using: .utf8)!)
        nightsWritten += 1
    }
    handle.closeFile()
    print("\(rowsWritten) epochs (\(scored) scored) from \(nightsWritten) nights -> \(out)")
    print("share of scored epochs where a feature is missing:")
    for (k, name) in SleepStageFeatures.names.enumerated() where missing[k] > 0 {
        print("  \(name.padding(toLength: 22, withPad: " ", startingAt: 0)) \(fmt(100 * Double(missing[k]) / Double(scored), 2)) %")
    }
}

switch args.command {
case "baseline": runBaseline()
case "features": runFeatures()
case "train", "speed", "own":
    #if canImport(CreateML)
    do {
        if args.command == "train" {
            try runTrain(table: args.table ?? (args.reduced as NSString).appendingPathComponent("features.csv"),
                         out: args.out)
        } else if args.command == "speed" {
            guard let model = args.model else { print(usage); exit(2) }
            try runSpeed(reduced: args.reduced, modelPath: model)
        } else {
            guard let model = args.model, let db = args.db else { print(usage); exit(2) }
            try runOwn(dbPath: db, modelPath: model, sessionsDevice: args.sessionsDevice,
                       streamsDevice: args.streamsDevice, featuresOut: args.out, withIntervals: !args.noIntervals)
        }
    } catch {
        print("failed: \(error)")
        exit(1)
    }
    #else
    print("training needs the CreateML framework (macOS)")
    exit(1)
    #endif
default: print(usage); exit(2)
}
