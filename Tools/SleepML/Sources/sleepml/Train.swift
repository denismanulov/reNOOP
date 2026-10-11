#if canImport(CreateML)
import CoreML
import CreateML
import Foundation
import StrandAnalytics
import TabularData

// Train — fit the stage model with Create ML and measure it the way the brief demands.
//
// The model is two boosted-tree classifiers, one row per 30 s epoch. The first takes `SleepStageFeatures`
// and their surroundings (`SleepStageContext.firstNames`); the second takes the same again plus the
// first one's probabilities around the epoch (`secondNames`). The second's class probabilities are then
// smoothed by `SleepStageDecoder`, V2's own Viterbi. Four rules shape this file:
//
//  * PEOPLE ARE NEVER SPLIT. Consecutive epochs of one night are near-copies of each other, so a split by
//    row lets a model score by memorising the sleeper. Folds are by person; the validation table Create
//    ML stops early on is an explicit, person-level slice of the training people (its automatic split is
//    by row and is never used); and a second experiment trains on one dataset and tests on the other.
//  * THE SECOND MODEL NEVER LEARNS FROM A FIRST MODEL THAT SAW THE SLEEPER. A first model is close to
//    certain about the nights it was trained on, and a second model fed that would learn to trust it
//    blindly. Every training night's first-stage probabilities come from a model fit without that
//    person (`innerFolds` folds by person inside the training people).
//  * A MISSING FEATURE IS A NUMBER. See `SleepStageFeatures.missing`.
//  * BEAT INTERVALS MUST NOT NAME THE DATASET. Only one dataset has them, so "intervals present" would
//    otherwise mean "this is that hardware". Half of that dataset's training nights have their interval
//    features withheld, and every such test night is scored both with and without them.

struct Epoch {
    let dataset: String
    let subject: String
    let night: String
    let stage: String?              // nil: the PSG carries no score for this epoch
    let shipped: String
    let shippedNoRR: String
    let x: [Double]
}

struct NightRows {
    let dataset: String
    let subject: String
    let night: String
    var epochs: [Epoch]
}

/// What the first model takes: the table's features, then their surroundings. An `Epoch.x` is one of these rows.
let featureNames = SleepStageContext.firstNames
/// Every column made from beat intervals, the surroundings of the interval features included.
let intervalColumns = featureNames.indices.filter { featureNames[$0].hasPrefix("rr_") }

func withheld(_ x: [Double]) -> [Double] {
    var y = x
    for i in intervalColumns { y[i] = SleepStageFeatures.missing }
    return y
}

/// The feature table `sleepml features` wrote, grouped into nights in file order.
func loadTable(_ path: String) -> [NightRows] {
    guard let text = try? String(contentsOfFile: path, encoding: .utf8) else { return [] }
    var nights: [NightRows] = []
    var header: [String] = []
    var first = 0
    let tableNames = SleepStageFeatures.names
    text.enumerateLines { line, _ in
        let f = line.split(separator: ",", omittingEmptySubsequences: false)
        if header.isEmpty {
            header = f.map(String.init)
            first = header.firstIndex(of: tableNames[0]) ?? 0
            precondition(Array(header[first...]) == tableNames, "the table's columns are not this build's features")
            return
        }
        let x = (0..<tableNames.count).map { Double(f[first + $0]) ?? SleepStageFeatures.missing }
        let e = Epoch(dataset: String(f[0]), subject: String(f[1]), night: String(f[2]),
                      stage: f[4].isEmpty ? nil : String(f[4]), shipped: String(f[5]),
                      shippedNoRR: String(f[6]), x: x)
        if let last = nights.last, last.night == e.night, last.dataset == e.dataset {
            nights[nights.count - 1].epochs.append(e)
        } else {
            nights.append(NightRows(dataset: e.dataset, subject: e.subject, night: e.night, epochs: [e]))
        }
    }
    // The surroundings are a function of a whole night's rows, so they are added once the night is whole.
    return nights.map { n in
        let values = n.epochs.map { e in e.x.map { $0 == SleepStageFeatures.missing ? nil : $0 } }
        let full = SleepStageContext.firstInput(values: values)
        var out = n
        out.epochs = zip(n.epochs, full).map { e, x in
            Epoch(dataset: e.dataset, subject: e.subject, night: e.night, stage: e.stage, shipped: e.shipped,
                  shippedNoRR: e.shippedNoRR, x: x)
        }
        return out
    }
}

/// A fixed sequence: folds and the withheld nights are the same on every run.
struct SplitMix: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

struct TrainOptions {
    var folds = 5
    var depth = 4
    var iterations = 300
    var step = 0.1
    var minChild = 50.0
    var rowSample = 0.8
    var columnSample = 0.8
    var patience = 30
    var seed = 7
    var innerFolds = 4
}

func frame(_ rows: [(x: [Double], stage: String)], _ names: [String]) -> DataFrame {
    var df = DataFrame()
    for (i, name) in names.enumerated() {
        df.append(column: Column(name: name, contents: rows.map { $0.x[i] }))
    }
    df.append(column: Column(name: "stage", contents: rows.map { $0.stage }))
    return df
}

/// Training rows of a set of nights: scored epochs only, interval features withheld on the nights in `blank`.
func trainingRows(_ nights: [NightRows], blank: Set<String>) -> [(x: [Double], stage: String)] {
    var out: [(x: [Double], stage: String)] = []
    for n in nights {
        let hide = blank.contains(n.night)
        for e in n.epochs {
            if let s = e.stage { out.append((hide ? withheld(e.x) : e.x, s)) }
        }
    }
    return out
}

func fit(_ train: [(x: [Double], stage: String)], _ valid: [(x: [Double], stage: String)], names: [String],
         _ o: TrainOptions) throws -> MLBoostedTreeClassifier {
    let p = MLBoostedTreeClassifier.ModelParameters(
        validation: .dataFrame(frame(valid, names)),
        maxDepth: o.depth, maxIterations: o.iterations, minLossReduction: 0, minChildWeight: o.minChild,
        randomSeed: o.seed, stepSize: o.step, earlyStoppingRounds: o.patience,
        rowSubsample: o.rowSample, columnSubsample: o.columnSample)
    return try MLBoostedTreeClassifier(trainingData: frame(train, names),
                                       targetColumn: "stage", featureColumns: names, parameters: p)
}

/// The first model alone, on nights' own rows.
func fit(train: [NightRows], valid: [NightRows], blank: Set<String>, _ o: TrainOptions) throws -> MLBoostedTreeClassifier {
    try fit(trainingRows(train, blank: blank), trainingRows(valid, blank: blank), names: featureNames, o)
}

/// The two models of one fit.
struct Staged {
    let first: MLModel
    let second: MLModel
}

/// A night's rows as training shows them: interval columns withheld on the nights in `blank`.
func shown(_ n: NightRows, blank: Set<String>) -> [[Double]] {
    blank.contains(n.night) ? n.epochs.map { withheld($0.x) } : n.epochs.map { $0.x }
}

/// Class probabilities of the two models in turn over one night's first-model rows.
func probabilities(_ m: Staged, _ xs: [[Double]]) throws -> [[Double]] {
    let p1 = try probabilities(m.first, xs, names: featureNames)
    return try probabilities(m.second, SleepStageContext.secondInput(first: xs, probabilities: p1),
                             names: SleepStageContext.secondNames)
}

/// Fit both models on `train`, stopping each on `valid`. Also returned: the second model's inputs for
/// the validation nights, built like the training ones from first models that never saw the person, so
/// the decoder's settings can be fit on probabilities of the kind a new sleeper will produce.
func fitStaged(train: [NightRows], valid: [NightRows], blank: Set<String>, _ o: TrainOptions, seed: Int)
    throws -> (model: Staged, first: MLBoostedTreeClassifier, second: MLBoostedTreeClassifier, validSecond: [[[Double]]]) {
    let first = try fit(train: train, valid: valid, blank: blank, o)

    // Second-model inputs of every training and validation night, from inner folds by person.
    let all = train + valid
    var rng = SplitMix(state: UInt64(seed + 100))
    let people = Array(Set(all.map { $0.dataset + "/" + $0.subject })).sorted().shuffled(using: &rng)
    var inner: [String: Int] = [:]
    for (i, s) in people.enumerated() { inner[s] = i % o.innerFolds }
    var second: [String: [[Double]]] = [:]                       // dataset/night -> rows of `secondNames`
    for k in 0..<o.innerFolds {
        let others = all.filter { inner[$0.dataset + "/" + $0.subject] != k }
        let (ta, va) = split(others, everyNth: 6, seed: UInt64(seed + k + 1))
        let mk = try fit(train: ta, valid: va, blank: blank, o).model
        for n in all where inner[n.dataset + "/" + n.subject] == k {
            let xs = shown(n, blank: blank)
            second[n.dataset + "/" + n.night] = SleepStageContext.secondInput(
                first: xs, probabilities: try probabilities(mk, xs, names: featureNames))
        }
    }
    func rows(_ nights: [NightRows]) -> [(x: [Double], stage: String)] {
        var out: [(x: [Double], stage: String)] = []
        for n in nights {
            let x2 = second[n.dataset + "/" + n.night]!
            for (e, x) in zip(n.epochs, x2) { if let s = e.stage { out.append((x, s)) } }
        }
        return out
    }
    let m2 = try fit(rows(train), rows(valid), names: SleepStageContext.secondNames, o)
    return (Staged(first: first.model, second: m2.model), first, m2,
            valid.map { second[$0.dataset + "/" + $0.night]! })
}

/// Class probabilities in `SleepStageDecoder.stages` order, through the Core ML model itself: the path
/// the app will take, so what is scored here is what would ship.
func probabilities(_ model: MLModel, _ xs: [[Double]], names: [String]) throws -> [[Double]] {
    // The model says which features it takes; rows carry every feature this build computes.
    let wanted = names.indices.filter { model.modelDescription.inputDescriptionsByName[names[$0]] != nil }
    let providers: [MLFeatureProvider] = try xs.map { x in
        try MLDictionaryFeatureProvider(dictionary: Dictionary(uniqueKeysWithValues: wanted.map { (names[$0], x[$0]) }))
    }
    let out = try model.predictions(fromBatch: MLArrayBatchProvider(array: providers))
    let key = model.modelDescription.predictedProbabilitiesName ?? "stageProbability"
    return (0..<out.count).map { i in
        let d = out.features(at: i).featureValue(for: key)?.dictionaryValue ?? [:]
        return SleepStageDecoder.stages.map { d[AnyHashable($0)]?.doubleValue ?? 0 }
    }
}

func argmax(_ p: [[Double]]) -> [String] {
    p.map { row in SleepStageDecoder.stages[row.indices.max { row[$0] < row[$1] } ?? 1] }
}

/// The readings of one test night under every variant compared.
struct Scored {
    var variants: [String: [NightScore]] = [:]
    mutating func add(_ name: String, _ s: NightScore) { variants[name, default: []].append(s) }
    mutating func merge(_ o: Scored) { for (k, v) in o.variants { variants[k, default: []] += v } }
}

let vShipped = "shipped V2"
let vFirst = "first model only, each epoch alone"
let vArgmax = "model, each epoch alone"
let vArgmaxW = "model, each epoch alone, fitted weight"
let vFull = "model + V2 smoothing in full"
let vModel = "model + fitted smoothing"
let vShippedNoRR = "shipped V2, intervals withheld"
let vModelNoRR = "model + fitted smoothing, intervals withheld"

/// Each stage's share of the scored epochs of a set of nights, in `SleepStageDecoder.stages` order: the
/// prior the decoder divides by. Always taken from TRAINING nights.
func classPrior(_ nights: [NightRows]) -> [Double] {
    var n = [Double](repeating: 0, count: SleepStageDecoder.stages.count)
    for night in nights {
        for e in night.epochs {
            if let s = e.stage, let i = SleepStageDecoder.stages.firstIndex(of: s) { n[i] += 1 }
        }
    }
    let total = max(1, n.reduce(0, +))
    return n.map { $0 / total }
}

/// What the decoder is given besides the probabilities. The prior comes from the training people; the
/// two settings are fit on the validation people, who are neither trained on nor tested on.
struct Decoding {
    let prior: [Double]
    let weight: Double                  // with `smoothing`
    let smoothing: Double
    let fullWeight: Double              // with V2's matrix in full
    let aloneWeight: Double             // with no smoothing at all
}

/// How far a decoding of `nights` is from their PSG on the two things the settings control: stage shares
/// (sum over stages of the absolute difference, in points of the night, pooled over epochs) and
/// fragmentation (stage changes, as a ratio to the PSG's).
func miss(_ probs: [[[Double]]], _ nights: [NightRows], prior: [Double], weight: Double, smoothing: Double)
    -> (shares: Double, changes: Double) {
    var c = Confusion()
    var changesPred = 0, changesTruth = 0
    for (n, p) in zip(nights, probs) {
        let d = SleepStageDecoder.decode(p, prior: prior, weight: weight, smoothing: smoothing)
        let truth = n.epochs.map { $0.stage }
        for (t, x) in zip(truth, d) { if let t = t { c.add(ref: t, pred: x) } }
        // counted where the PSG is scored on both sides, for prediction and truth alike
        for i in 1..<max(1, d.count) where truth[i] != nil && truth[i - 1] != nil {
            if d[i] != d[i - 1] { changesPred += 1 }
            if truth[i] != truth[i - 1] { changesTruth += 1 }
        }
    }
    let shares = stageOrder.reduce(0.0) { $0 + abs(c.share($1, predicted: true) - c.share($1, predicted: false)) }
    return (shares, Double(changesPred) / Double(max(1, changesTruth)))
}

/// The weight in 0...1 that brings the stage shares closest to the PSG's, at a given smoothing.
func fitWeight(_ probs: [[[Double]]], _ nights: [NightRows], prior: [Double], smoothing: Double) -> Double {
    (0...20).map { Double($0) / 20 }.min {
        miss(probs, nights, prior: prior, weight: $0, smoothing: smoothing).shares
            < miss(probs, nights, prior: prior, weight: $1, smoothing: smoothing).shares
    } ?? 1
}

func decoding(_ probs: [[[Double]]], train: [NightRows], valid: [NightRows]) -> Decoding {
    let prior = classPrior(train)
    // Smoothing first: the strength at which, with the shares already fitted, the night is as
    // fragmented as the PSG. Then the weight at that strength.
    var best = (gap: Double.infinity, smoothing: 1.0, weight: 1.0)
    for step in 0...20 {
        let s = Double(step) / 20
        let w = fitWeight(probs, valid, prior: prior, smoothing: s)
        let gap = abs(log(max(miss(probs, valid, prior: prior, weight: w, smoothing: s).changes, 1e-6)))
        if gap < best.gap { best = (gap, s, w) }
    }
    return Decoding(prior: prior, weight: best.weight, smoothing: best.smoothing,
                    fullWeight: fitWeight(probs, valid, prior: prior, smoothing: 1),
                    aloneWeight: fitWeight(probs, valid, prior: prior, smoothing: 0))
}

/// One line per test night of the grouped folds: how much the wrist moved, and each stage's share in the
/// PSG and in the model's hypnogram. Lets a result be read against how restless the night was.
var nightLines: [String] = []

/// One line per test epoch of the grouped folds: the PSG's stage, the probabilities of a model that never
/// saw the sleeper, and the decoder settings of that fold. Decoders are compared on this file without
/// training anything again.
var epochLines: [String] = []

func evaluate(_ model: Staged, _ nights: [NightRows], _ d: Decoding, record: Bool = false) throws -> Scored {
    var out = Scored()
    for n in nights {
        let truth = n.epochs.map { $0.stage }
        func add(_ name: String, _ pred: [String]) {
            out.add(name, score(dataset: n.dataset, subject: n.subject, truth: truth, pred: pred))
        }
        let xs = n.epochs.map { $0.x }
        let p = try probabilities(model, xs)
        add(vShipped, n.epochs.map { $0.shipped })
        add(vFirst, argmax(try probabilities(model.first, xs, names: featureNames)))
        add(vArgmax, argmax(p))
        add(vArgmaxW, SleepStageDecoder.decode(p, prior: d.prior, weight: d.aloneWeight, smoothing: 0))
        add(vFull, SleepStageDecoder.decode(p, prior: d.prior, weight: d.fullWeight, smoothing: 1))
        let decoded = SleepStageDecoder.decode(p, prior: d.prior, weight: d.weight, smoothing: d.smoothing)
        add(vModel, decoded)
        if record, let mf = featureNames.firstIndex(of: "move_share") {
            let settings = (d.prior + [d.weight, d.smoothing]).map { String(format: "%.6g", $0) }
            for (i, e) in n.epochs.enumerated() {
                epochLines.append(([n.dataset, n.night, String(i), e.stage ?? "", e.shipped, decoded[i],
                                    String(format: "%.6g", e.x[mf])] + p[i].map { String(format: "%.6g", $0) }
                                   + settings).joined(separator: ","))
            }
            let moved = n.epochs.map { $0.x[mf] }.filter { $0 != SleepStageFeatures.missing }
            let scored = zip(truth, decoded).filter { $0.0 != nil }
            func share(_ st: String, _ pick: ((String?, String)) -> String?) -> String {
                fmt(100 * Double(scored.filter { pick($0) == st }.count) / Double(max(1, scored.count)), 2)
            }
            let shipped = zip(truth, n.epochs.map { $0.shipped }).filter { $0.0 != nil }
            let shippedShares = stageOrder.map { st in
                fmt(100 * Double(shipped.filter { $0.1 == st }.count) / Double(max(1, shipped.count)), 2)
            }
            nightLines.append(([n.dataset, n.night, fmt(100 * mean(moved), 3)]
                + stageOrder.map { st in share(st) { $0.0 } } + stageOrder.map { st in share(st) { $0.1 } }
                + shippedShares).joined(separator: ","))
        }
        let hasIntervals = n.epochs.contains { e in intervalColumns.contains { e.x[$0] != SleepStageFeatures.missing } }
        if hasIntervals {
            add(vShippedNoRR, n.epochs.map { $0.shippedNoRR })
            add(vModelNoRR, SleepStageDecoder.decode(try probabilities(model, xs.map { withheld($0) }),
                                                     prior: d.prior, weight: d.weight, smoothing: d.smoothing))
        }
    }
    return out
}

// MARK: - the comparison table

func pad(_ s: String, _ w: Int) -> String { s.count >= w ? s : s + String(repeating: " ", count: w - s.count) }
func rpad(_ s: String, _ w: Int) -> String { s.count >= w ? s : String(repeating: " ", count: w - s.count) + s }

func compare(_ title: String, _ scored: Scored, order: [String]) {
    let names = order.filter { scored.variants[$0] != nil }
    guard let any = names.first.flatMap({ scored.variants[$0] }) else { return }
    var pooledAny = Confusion()
    for r in any { pooledAny.merge(r.confusion) }
    print("\n== \(title): \(Set(any.map { $0.dataset + "/" + $0.subject }).count) people,"
          + " \(any.count) nights, \(pooledAny.total) scored epochs")
    let w = 38
    print(pad("", w) + " acc %  kappa  k/night   F1 wake  light   deep    rem   wake recall")
    for name in names {
        let rows = scored.variants[name]!
        var c = Confusion()
        for r in rows { c.merge(r.confusion) }
        let perNight = mean(rows.map { $0.confusion.kappa }.filter { !$0.isNaN })
        print(pad(name, w) + rpad(fmt(100 * c.accuracy, 2), 6) + rpad(fmt(c.kappa, 3), 7) + rpad(fmt(perNight, 3), 9)
              + "      " + stageOrder.map { rpad(fmt(c.f1($0), 3), 6) }.joined(separator: " ")
              + rpad(fmt(c.recall("wake"), 3), 11))
    }
    print(pad("share of the window, points off PSG", w) + "   pooled over epochs: wake  light   deep    rem"
          + "    mean over nights: wake  light   deep    rem    mean |off| per night: wake  light   deep    rem")
    for name in names {
        let rows = scored.variants[name]!
        var c = Confusion()
        for r in rows { c.merge(r.confusion) }
        let pooled = stageOrder.map { rpad(signed(c.share($0, predicted: true) - c.share($0, predicted: false)), 6) }
        let per = stageOrder.map { st -> (Double, Double) in
            let d = rows.map { $0.confusion.share(st, predicted: true) - $0.confusion.share(st, predicted: false) }
            return (mean(d), mean(d.map { abs($0) }))
        }
        print(pad(name, w) + "                    " + pooled.joined(separator: " ")
              + "                    " + per.map { rpad(signed($0.0), 6) }.joined(separator: " ")
              + "                        " + per.map { rpad(fmt($0.1), 6) }.joined(separator: " "))
    }
    print(pad("shape of the night", w) + "   deep latency, median min   nights with deep in 10 min"
          + "   wake bouts a night, median   bout length, median min   stage changes a night, median")
    func shape(_ name: String, _ lat: [Double], _ bouts: [[Double]], _ changes: [Int]) {
        print(pad(name, w) + rpad(fmt(median(lat)), 20) + rpad("\(lat.filter { $0 <= 10 }.count) of \(bouts.count)", 29)
              + rpad(fmt(median(bouts.map { Double($0.count) }), 0), 26) + rpad(fmt(median(bouts.flatMap { $0 })), 24)
              + rpad(fmt(median(changes.map { Double($0) }), 0), 28))
    }
    shape("PSG", any.compactMap { $0.deepLatencyTruth }, any.map { $0.wakeBoutsTruth }, any.map { $0.changesTruth })
    for name in names {
        let rows = scored.variants[name]!
        shape(name, rows.compactMap { $0.deepLatencyPred }, rows.map { $0.wakeBoutsPred }, rows.map { $0.changesPred })
    }
}

// MARK: - commands

/// Every `step`-th person of a list, as the validation slice Create ML stops early on.
func split(_ nights: [NightRows], everyNth step: Int, seed: UInt64) -> (train: [NightRows], valid: [NightRows]) {
    var rng = SplitMix(state: seed)
    let people = Array(Set(nights.map { $0.dataset + "/" + $0.subject })).sorted().shuffled(using: &rng)
    let held = Set(people.enumerated().filter { $0.offset % step == 0 }.map { $0.element })
    return (nights.filter { !held.contains($0.dataset + "/" + $0.subject) },
            nights.filter { held.contains($0.dataset + "/" + $0.subject) })
}

func runTrain(table: String, out: String?) throws {
    let nights = loadTable(table)
    if nights.isEmpty { print("no rows in \(table)"); exit(1) }
    let o = TrainOptions()
    print("\(nights.count) nights, \(nights.reduce(0) { $0 + $1.epochs.count }) epochs, \(featureNames.count) features")
    print("boosted trees: depth \(o.depth), up to \(o.iterations) rounds (stops \(o.patience) after the validation"
          + " loss last improved), step \(o.step), min child weight \(o.minChild), row/column sample"
          + " \(o.rowSample)/\(o.columnSample), seed \(o.seed)")

    // Interval features are withheld on a fixed half of the nights that have them.
    var rng = SplitMix(state: UInt64(o.seed))
    let withIntervals = nights.filter { n in
        n.epochs.contains { e in intervalColumns.contains { e.x[$0] != SleepStageFeatures.missing } }
    }.map { $0.night }.sorted().shuffled(using: &rng)
    let blank = Set(withIntervals.prefix(withIntervals.count / 2))
    print("interval features withheld in training on \(blank.count) of the \(withIntervals.count) nights that have them")

    let order = [vShipped, vFirst, vArgmax, vArgmaxW, vFull, vModel, vShippedNoRR, vModelNoRR]
    /// Both models and the decoder settings that go with them, as all three experiments below fit them.
    func fitAll(train: [NightRows], valid: [NightRows], seed: Int) throws
        -> (model: Staged, d: Decoding, first: MLBoostedTreeClassifier, second: MLBoostedTreeClassifier) {
        let f = try fitStaged(train: train, valid: valid, blank: blank, o, seed: seed)
        let probs = try f.validSecond.map {
            try probabilities(f.model.second, $0, names: SleepStageContext.secondNames)
        }
        return (f.model, decoding(probs, train: train, valid: valid), f.first, f.second)
    }

    // 1. Grouped folds: every person is predicted by a model that never saw them.
    var people: [String: [String]] = [:]                          // dataset -> its people, shuffled
    for ds in Set(nights.map { $0.dataset }).sorted() {
        people[ds] = Array(Set(nights.filter { $0.dataset == ds }.map { $0.subject })).sorted().shuffled(using: &rng)
    }
    var foldOf: [String: Int] = [:]
    for (ds, list) in people { for (i, s) in list.enumerated() { foldOf[ds + "/" + s] = i % o.folds } }
    var cv = Scored()
    var settings: [String] = []
    for k in 0..<o.folds {
        let test = nights.filter { foldOf[$0.dataset + "/" + $0.subject] == k }
        let rest = nights.filter { foldOf[$0.dataset + "/" + $0.subject] != k }
        let (train, valid) = split(rest, everyNth: 6, seed: UInt64(o.seed + k))
        let t0 = Date()
        let f = try fitAll(train: train, valid: valid, seed: o.seed + k)
        let d = f.d
        settings.append("\(fmt(d.smoothing, 2))/\(fmt(d.weight, 2))")
        cv.merge(try evaluate(f.model, test, d, record: true))
        FileHandle.standardError.write(("fold \(k + 1)/\(o.folds): trained on \(train.count) nights, stopped on"
            + " \(valid.count), tested on \(test.count); validation error first model"
            + " \(fmt(100 * f.first.validationMetrics.classificationError, 2)) %, second"
            + " \(fmt(100 * f.second.validationMetrics.classificationError, 2)) %, smoothing \(fmt(d.smoothing, 2)),"
            + " prior weight \(fmt(d.weight, 2)), \(Int(Date().timeIntervalSince(t0))) s\n").data(using: .utf8)!)
    }
    func only(_ s: Scored, _ ds: String) -> Scored {
        var out = Scored()
        for (k, v) in s.variants { out.variants[k] = v.filter { $0.dataset == ds } }
        out.variants = out.variants.filter { !$0.value.isEmpty }
        return out
    }
    let perNight = ((table as NSString).deletingLastPathComponent as NSString).appendingPathComponent("cv_nights.csv")
    try (["dataset,night,moving_pct," + stageOrder.map { "true_" + $0 }.joined(separator: ",") + ","
          + stageOrder.map { "model_" + $0 }.joined(separator: ",") + ","
          + stageOrder.map { "shipped_" + $0 }.joined(separator: ",")] + nightLines).joined(separator: "\n")
        .write(toFile: perNight, atomically: true, encoding: .utf8)
    let perEpoch = ((table as NSString).deletingLastPathComponent as NSString).appendingPathComponent("cv_epochs.csv")
    try (["dataset,night,epoch,stage,shipped,model,move_share,"
          + SleepStageDecoder.stages.map { "p_" + $0 }.joined(separator: ",") + ","
          + SleepStageDecoder.stages.map { "prior_" + $0 }.joined(separator: ",") + ",weight,smoothing"]
         + epochLines).joined(separator: "\n").write(toFile: perEpoch, atomically: true, encoding: .utf8)
    print("\n#### grouped \(o.folds)-fold: each person staged by a model trained without them")
    print("decoder smoothing/prior weight, fit in each fold on its validation people: "
          + settings.joined(separator: ", "))
    compare("all datasets", cv, order: order)
    for ds in people.keys.sorted() { compare(ds, only(cv, ds), order: order) }

    // 2. Leave one dataset out: other hardware, other lab, other scorers.
    print("\n#### leave one dataset out")
    for ds in people.keys.sorted() {
        let test = nights.filter { $0.dataset == ds }
        let (train, valid) = split(nights.filter { $0.dataset != ds }, everyNth: 6, seed: UInt64(o.seed))
        if train.isEmpty { continue }
        let f = try fitAll(train: train, valid: valid, seed: o.seed)
        let d = f.d
        compare("\(ds), by a model that never saw this dataset (smoothing \(fmt(d.smoothing, 2)),"
                + " prior weight \(fmt(d.weight, 2)))", try evaluate(f.model, test, d), order: order)
    }

    // 3. The models that would ship: every person, the same recipe.
    if let out = out {
        let (train, valid) = split(nights, everyNth: 8, seed: UInt64(o.seed))
        let f = try fitAll(train: train, valid: valid, seed: o.seed)
        let d = f.d
        let url = URL(fileURLWithPath: out)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        func metadata(_ what: String, _ more: [String: String]) -> MLModelMetadata {
            MLModelMetadata(
                author: "reNOOP Tools/SleepML",
                shortDescription: "Sleep stage per 30 s epoch (wake, light, deep, rem) from wrist motion and heart rate, "
                    + what + " Experimental. Trained on Wearanize+ OA (Radboud University, CC BY 4.0) and sleep-accel"
                    + " (Walch et al., PhysioNet, ODC-By 1.0).",
                license: "Model weights derived from CC BY 4.0 and ODC-By 1.0 data; attribution required.",
                version: "0.2",
                additional: ["classes": SleepStageDecoder.stages.joined(separator: ",")].merging(more) { $1 })
        }
        try f.first.write(to: url, metadata: metadata(
            "first of two models: its probabilities are inputs of the second, not a hypnogram.", ["stage": "first"]))
        try f.second.write(to: URL(fileURLWithPath: secondPath(out)), metadata: metadata(
            "second of two models: takes the first one's probabilities around each epoch.",
            ["stage": "second",
             "classPrior": d.prior.map { String(format: "%.6f", $0) }.joined(separator: ","),
             "priorWeight": String(format: "%.2f", d.weight),
             "smoothing": String(format: "%.2f", d.smoothing)]))
        func size(_ path: String) -> Int { ((try? FileManager.default.attributesOfItem(atPath: path)[.size] as? Int) ?? 0) / 1024 }
        print("\nmodels for shadow use: \(out) (\(size(out)) kB) and \(secondPath(out)) (\(size(secondPath(out))) kB),"
              + " trained on \(train.count) nights (stopped on \(valid.count)); validation error first"
              + " \(fmt(100 * f.first.validationMetrics.classificationError, 2)) %, second"
              + " \(fmt(100 * f.second.validationMetrics.classificationError, 2)) %")
        print("decoder settings stored in the second model's metadata: class prior "
              + zip(SleepStageDecoder.stages, d.prior).map { "\($0) \(fmt(100 * $1)) %" }.joined(separator: ", ")
              + "; prior weight \(fmt(d.weight, 2)); smoothing \(fmt(d.smoothing, 2))")
    }
}

/// Where the second model of a pair is kept, given the first one's path.
func secondPath(_ first: String) -> String { (first as NSString).deletingPathExtension + ".second.mlmodel" }

/// A stored pair, compiled and loaded for the CPU, with the decoder settings from the second's metadata.
struct LoadedPair {
    let model: Staged
    let prior: [Double]?
    let weight: Double
    let smoothing: Double
    let compiledBytes: Int
    let loadMs: Double

    init(_ path: String) throws {
        let config = MLModelConfiguration()
        config.computeUnits = .cpuOnly
        let compiled = try [path, secondPath(path)].map { try MLModel.compileModel(at: URL(fileURLWithPath: $0)) }
        let t0 = Date()
        model = Staged(first: try MLModel(contentsOf: compiled[0], configuration: config),
                       second: try MLModel(contentsOf: compiled[1], configuration: config))
        loadMs = Date().timeIntervalSince(t0) * 1000
        let meta = model.second.modelDescription.metadata[.creatorDefinedKey] as? [String: String] ?? [:]
        let p = meta["classPrior"].map { $0.split(separator: ",").compactMap { Double($0) } }
        prior = p?.count == SleepStageDecoder.stages.count ? p : nil
        weight = meta["priorWeight"].flatMap { Double($0) } ?? 1
        smoothing = meta["smoothing"].flatMap { Double($0) } ?? 1
        compiledBytes = compiled.reduce(0) { total, url in
            total + ((FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey])?
                .compactMap { ($0 as? URL).flatMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize } }
                .reduce(0, +)) ?? 0)
        }
    }

    /// A night's hypnogram from its feature rows: both models, then the decoder with the stored settings.
    func stage(_ rows: [SleepStageFeatures.Row], withIntervals: Bool = true) throws -> [String] {
        let xs = SleepStageContext.firstInput(rows)
        return SleepStageDecoder.decode(try probabilities(model, withIntervals ? xs : xs.map { withheld($0) }),
                                        prior: prior, weight: weight, smoothing: smoothing)
    }
}

/// How long a night takes, on this machine, for the things the phone would do.
func runSpeed(reduced: String, modelPath: String) throws {
    let nights = Reduced.load(root: reduced)
    guard let n = nights.max(by: { ($0.lastLabel - $0.firstLabel) < ($1.lastLabel - $1.firstLabel) }),
          let w = n.window(.sleep) else { print("no night to time"); exit(1) }
    func clock(_ runs: Int, _ body: () throws -> Void) rethrows -> Double {
        var t: [Double] = []
        for _ in 0..<runs { let a = Date(); try body(); t.append(Date().timeIntervalSince(a) * 1000) }
        return median(t)
    }
    let pair = try LoadedPair(modelPath)
    var rows: [SleepStageFeatures.Row] = []
    let feat = clock(5) { rows = SleepStageFeatures.rows(start: w.start, end: w.end, grav: n.grav, hr: n.hr, rr: n.rr) }
    var xs: [[Double]] = []
    let around = clock(5) { xs = SleepStageContext.firstInput(rows) }
    var p1: [[Double]] = [], p: [[Double]] = []
    let predict1 = try clock(5) { p1 = try probabilities(pair.model.first, xs, names: featureNames) }
    var xs2: [[Double]] = []
    let between = clock(5) { xs2 = SleepStageContext.secondInput(first: xs, probabilities: p1) }
    let predict2 = try clock(5) { p = try probabilities(pair.model.second, xs2, names: SleepStageContext.secondNames) }
    var staged: [String] = []
    let decode = clock(5) {
        staged = SleepStageDecoder.decode(p, prior: pair.prior, weight: pair.weight, smoothing: pair.smoothing)
    }
    let shipped = clock(1) {
        _ = SleepStagerV2.stageSession(start: w.start, end: w.end, grav: n.grav, hr: n.hr, rr: n.rr, resp: [])
    }
    print("longest night: \(n.dataset) \(n.id), \(rows.count) epochs (\(fmt(Double(rows.count) / 120)) h),"
          + " \(n.rr.count) beat intervals; staged \(staged.count) epochs")
    print("compiled models \(pair.compiledBytes / 1024) kB together; CPU only")
    print("  load both models          \(fmt(pair.loadMs)) ms (once per launch)")
    print("  features for the night    \(fmt(feat)) ms")
    print("  their surroundings        \(fmt(around)) ms")
    print("  first model over the night  \(fmt(predict1)) ms")
    print("  its neighbours            \(fmt(between)) ms")
    print("  second model over the night \(fmt(predict2)) ms")
    print("  Viterbi                   \(fmt(decode)) ms")
    print("  all of it                 \(fmt(feat + around + predict1 + between + predict2 + decode)) ms")
    print("  for reference, SleepStagerV2.stageSession on the same night, uncached: \(fmt(shipped)) ms")
}
#endif
