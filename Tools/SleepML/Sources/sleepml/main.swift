import Foundation
import StrandAnalytics
import WhoopProtocol

// sleepml — see Package.swift for what this is and why it is Swift.
//
//   sleepml baseline [--reduced DIR] [--window sleep|labelled] [--dataset NAME]
//   sleepml features [--reduced DIR] [--window sleep|labelled] --out FILE.csv

struct Args {
    var command = ""
    var reduced = ("~/datasets/reduced" as NSString).expandingTildeInPath
    var window = Window.sleep
    var dataset: String? = nil
    var out: String? = nil
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
        default: FileHandle.standardError.write("unknown argument \(k)\n".data(using: .utf8)!)
        }
    }
    return a
}

let usage = """
    usage: sleepml baseline [--reduced DIR] [--window sleep|labelled] [--dataset NAME]
           sleepml features [--reduced DIR] [--window sleep|labelled] --out FILE.csv

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
    let people = Set(rows.map { $0.night.dataset + "/" + $0.night.subject }).count
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
            with.append(score(n, start: w.start, end: w.end,
                              pred: stageShipped(n, start: w.start, end: w.end, intervals: true)))
            if !n.rr.isEmpty {
                without.append(score(n, start: w.start, end: w.end,
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
    let header = ["dataset", "subject", "night", "ts", "stage", "stage_v2"] + SleepStageFeatures.names
    handle.write((header.joined(separator: ",") + "\n").data(using: .utf8)!)
    var rowsWritten = 0, nightsWritten = 0
    var missing = [Int](repeating: 0, count: SleepStageFeatures.names.count)
    for n in nights {
        guard let w = n.window(args.window) else { continue }
        let rows = SleepStageFeatures.rows(start: w.start, end: w.end, grav: n.grav, hr: n.hr, rr: n.rr)
        let shipped = stageShipped(n, start: w.start, end: w.end, intervals: true)
        var text = ""
        for (i, r) in rows.enumerated() {
            guard let stage = n.truth[r.start] else { continue }  // an unscored epoch teaches nothing
            for (k, v) in r.values.enumerated() where v == nil { missing[k] += 1 }
            let vals = r.values.map { $0.map { String(format: "%.6g", $0) } ?? "" }
            text += ([n.dataset, n.subject, n.id, String(r.start), stage, i < shipped.count ? shipped[i] : ""]
                     + vals).joined(separator: ",") + "\n"
            rowsWritten += 1
        }
        handle.write(text.data(using: .utf8)!)
        nightsWritten += 1
    }
    handle.closeFile()
    print("\(rowsWritten) epochs from \(nightsWritten) nights -> \(out)")
    print("share of epochs where a feature is missing:")
    for (k, name) in SleepStageFeatures.names.enumerated() where missing[k] > 0 {
        print("  \(name.padding(toLength: 22, withPad: " ", startingAt: 0)) \(fmt(100 * Double(missing[k]) / Double(rowsWritten), 2)) %")
    }
}

switch args.command {
case "baseline": runBaseline()
case "features": runFeatures()
default: print(usage); exit(2)
}
