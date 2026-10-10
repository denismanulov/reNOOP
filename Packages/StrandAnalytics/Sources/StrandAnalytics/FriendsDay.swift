import Foundation

// FriendsDay — the one summary a phone uploads per day for the Friends tab, and the pure rule that
// fills it (reNOOP fork feature; the service and its contract are `friends-server/README.md`).
//
// Nothing raw leaves the phone: three scores, one night's totals, the day's workouts and a downsampled
// heart-rate line. Each of the four sections has a sharing switch, and a section whose switch is off
// is NOT BUILT. The server also drops it, but the phone must not rely on that: what is not shared is
// not sent.
//
// Two friends on different platforms read each other's figures side by side, so every derivation here
// (rounding, limits, the thinning of the heart-rate line, the wire text) is the twin of Android's
// `FriendsDayPayload`, and the tests carry the expectations of `FriendsDayPayloadTest` as literals.
// Three rules hold for every member: a figure the app does not have is left out, never sent as 0; a
// figure outside the range the server accepts is left out too, since one refused member would cost the
// whole day; a section whose switch is off is not built. Pure: no I/O and no clock reads, the caller
// passes "now". Times are unix seconds, minutes are whole, and strain is on the stored 0-100 axis.

/// The four sharing switches. New accounts share everything except heart rate.
public struct FriendsShare: Equatable, Codable, Sendable {
    public var scores: Bool
    public var sleep: Bool
    public var workouts: Bool
    public var hr: Bool

    public init(scores: Bool = true, sleep: Bool = true, workouts: Bool = true, hr: Bool = false) {
        self.scores = scores; self.sleep = sleep; self.workouts = workouts; self.hr = hr
    }
}

/// One uploaded day. Every member is optional; a missing one is simply not shown to friends.
public struct FriendsDay: Equatable, Codable, Sendable {
    public var recovery: Int?
    public var strain: Double?
    public var sleepScore: Int?
    public var sleep: Sleep?
    /// Nil when the section is not shared or was not read; empty when the day had no workout.
    public var workouts: [Workout]?
    public var hr: HeartRate?

    public struct Sleep: Equatable, Codable, Sendable {
        public var startTs: Int
        public var endTs: Int
        public var asleepMin: Int
        public var awakeMin: Int?
        public var remMin: Int?
        public var lightMin: Int?
        public var deepMin: Int?
        public var needMin: Int?

        public init(startTs: Int, endTs: Int, asleepMin: Int, awakeMin: Int? = nil, remMin: Int? = nil,
                    lightMin: Int? = nil, deepMin: Int? = nil, needMin: Int? = nil) {
            self.startTs = startTs; self.endTs = endTs; self.asleepMin = asleepMin
            self.awakeMin = awakeMin; self.remMin = remMin; self.lightMin = lightMin
            self.deepMin = deepMin; self.needMin = needMin
        }
    }

    public struct Workout: Equatable, Codable, Sendable {
        public var startTs: Int
        public var sport: String
        public var durationS: Int
        public var strain: Double?
        public var avgHr: Int?
        public var maxHr: Int?
        public var kcal: Int?

        public init(startTs: Int, sport: String, durationS: Int, strain: Double? = nil, avgHr: Int? = nil,
                    maxHr: Int? = nil, kcal: Int? = nil) {
            self.startTs = startTs; self.sport = sport; self.durationS = durationS
            self.strain = strain; self.avgHr = avgHr; self.maxHr = maxHr; self.kcal = kcal
        }
    }

    public struct HeartRate: Equatable, Codable, Sendable {
        public var lastBpm: Int
        public var lastTs: Int
        public var restingBpm: Int?
        /// `[ts, bpm]` pairs, oldest first. Present on a person's own page, absent in the feed.
        public var series: [[Int]]?

        public init(lastBpm: Int, lastTs: Int, restingBpm: Int? = nil, series: [[Int]]? = nil) {
            self.lastBpm = lastBpm; self.lastTs = lastTs; self.restingBpm = restingBpm; self.series = series
        }
    }

    public init(recovery: Int? = nil, strain: Double? = nil, sleepScore: Int? = nil, sleep: Sleep? = nil,
                workouts: [Workout]? = nil, hr: HeartRate? = nil) {
        self.recovery = recovery; self.strain = strain; self.sleepScore = sleepScore
        self.sleep = sleep; self.workouts = workouts; self.hr = hr
    }

    /// True when there is nothing to show a friend.
    public var isEmpty: Bool {
        recovery == nil && strain == nil && sleepScore == nil && sleep == nil
            && (workouts ?? []).isEmpty && hr == nil
    }
}

public enum FriendsDayBuilder {
    /// The server's limits, which a built day never exceeds.
    public static let maxWorkouts = 20
    public static let maxSportLength = 40
    public static let maxSeriesPoints = 300
    /// What the service accepts as a heart rate; a sample outside it is not a reading.
    public static let bpmRange = 20...250
    /// The server refuses a timestamp before this (September 2017) or more than two days ahead of its clock.
    static let minTs = 1_500_000_000
    static let tsAheadSeconds = 2 * 86_400
    static let minutesRange = 0...1_440
    static let kcalRange = 0...20_000
    static let maxDurationSeconds = 86_400

    /// What the phone has for one day, before any sharing switch is applied.
    public struct Input: Equatable, Sendable {
        /// The day's own Recovery, 0-100. Nil while the day is unscored: a prior night carried onto it is
        /// that night's score, and a day on the wire has nowhere to say so.
        public var recovery: Double?
        /// Day strain on the stored 0-100 axis, never the display scale.
        public var strain: Double?
        /// The day's own Sleep score, 0-100.
        public var sleepScore: Double?
        public var sleep: SleepInput?
        /// The day's workouts, or nil when they were not read (which is not a day with none).
        public var workouts: [WorkoutInput]?
        /// Heart-rate samples of the day as `(ts, bpm)`, in any order.
        public var heartRate: [(ts: Int, bpm: Int)]
        public var restingBpm: Int?

        public init(recovery: Double? = nil, strain: Double? = nil, sleepScore: Double? = nil,
                    sleep: SleepInput? = nil, workouts: [WorkoutInput]? = nil,
                    heartRate: [(ts: Int, bpm: Int)] = [], restingBpm: Int? = nil) {
            self.recovery = recovery; self.strain = strain; self.sleepScore = sleepScore
            self.sleep = sleep; self.workouts = workouts; self.heartRate = heartRate
            self.restingBpm = restingBpm
        }

        public static func == (a: Input, b: Input) -> Bool {
            a.recovery == b.recovery && a.strain == b.strain && a.sleepScore == b.sleepScore
                && a.sleep == b.sleep && a.workouts == b.workouts && a.restingBpm == b.restingBpm
                && a.heartRate.map(\.ts) == b.heartRate.map(\.ts)
                && a.heartRate.map(\.bpm) == b.heartRate.map(\.bpm)
        }
    }

    /// The night that ended on this day, in fractional minutes as the Sleep page totals them.
    public struct SleepInput: Equatable, Sendable {
        public var startTs: Int
        public var endTs: Int
        public var asleepMin: Double
        public var awakeMin: Double?
        public var remMin: Double?
        public var lightMin: Double?
        public var deepMin: Double?
        public var needMin: Double?

        public init(startTs: Int, endTs: Int, asleepMin: Double, awakeMin: Double? = nil, remMin: Double? = nil,
                    lightMin: Double? = nil, deepMin: Double? = nil, needMin: Double? = nil) {
            self.startTs = startTs; self.endTs = endTs; self.asleepMin = asleepMin; self.awakeMin = awakeMin
            self.remMin = remMin; self.lightMin = lightMin; self.deepMin = deepMin; self.needMin = needMin
        }
    }

    /// One workout as the Workouts tab lists it.
    public struct WorkoutInput: Equatable, Sendable {
        public var startTs: Int
        /// The app's English sport label; each phone shows it in its reader's language.
        public var sport: String
        /// Active time, from `activeSeconds`.
        public var durationS: Double
        /// Stored 0-100 axis.
        public var strain: Double?
        public var avgHr: Int?
        public var maxHr: Int?
        public var kcal: Double?

        public init(startTs: Int, sport: String, durationS: Double, strain: Double? = nil, avgHr: Int? = nil,
                    maxHr: Int? = nil, kcal: Double? = nil) {
            self.startTs = startTs; self.sport = sport; self.durationS = durationS
            self.strain = strain; self.avgHr = avgHr; self.maxHr = maxHr; self.kcal = kcal
        }
    }

    /// A workout's active time: its recorded duration when that is a usable figure, else its span.
    public static func activeSeconds(durationS: Double?, startTs: Int, endTs: Int) -> Double {
        if let durationS, durationS.isFinite, durationS >= 0 { return durationS }
        return Double(max(0, endTs - startTs))
    }

    /// The day to upload: `input` through the sharing switches `share`, as of `nowTs` (unix seconds).
    public static func day(_ input: Input, share: FriendsShare, nowTs: Int) -> FriendsDay {
        let maxTs = nowTs + tsAheadSeconds
        func ts(_ value: Int) -> Int? { (minTs...maxTs).contains(value) ? value : nil }
        var day = FriendsDay()
        if share.scores {
            day.recovery = whole(input.recovery, in: 0...100)
            day.strain = strain(input.strain)
            day.sleepScore = whole(input.sleepScore, in: 0...100)
        }
        if share.sleep, let night = input.sleep { day.sleep = sleep(night, ts: ts) }
        if share.workouts, let rows = input.workouts { day.workouts = workouts(rows, ts: ts) }
        if share.hr { day.hr = heartRate(input.heartRate, restingBpm: input.restingBpm, ts: ts) }
        return day
    }

    /// A night, or nil when a time is one the server would refuse, it has no span, or its time asleep
    /// is not a usable figure.
    static func sleep(_ night: SleepInput, ts: (Int) -> Int?) -> FriendsDay.Sleep? {
        guard let start = ts(night.startTs), let end = ts(night.endTs),
              let asleep = whole(night.asleepMin, in: minutesRange), end > start else { return nil }
        return FriendsDay.Sleep(startTs: start, endTs: end, asleepMin: asleep,
                                awakeMin: whole(night.awakeMin, in: minutesRange),
                                remMin: whole(night.remMin, in: minutesRange),
                                lightMin: whole(night.lightMin, in: minutesRange),
                                deepMin: whole(night.deepMin, in: minutesRange),
                                needMin: whole(night.needMin, in: minutesRange))
    }

    /// Oldest first; when a day somehow holds more than the server keeps, the most recent ones stay.
    static func workouts(_ rows: [WorkoutInput], ts: (Int) -> Int?) -> [FriendsDay.Workout] {
        let built: [FriendsDay.Workout] = stablySorted(rows, by: \.startTs).compactMap { row in
            guard let start = ts(row.startTs), let sport = sportLabel(row.sport),
                  let duration = whole(row.durationS, in: 0...maxDurationSeconds) else { return nil }
            return FriendsDay.Workout(
                startTs: start, sport: sport, durationS: duration,
                strain: strain(row.strain),
                avgHr: row.avgHr.flatMap { bpmRange.contains($0) ? $0 : nil },
                maxHr: row.maxHr.flatMap { bpmRange.contains($0) ? $0 : nil },
                kcal: whole(row.kcal, in: kcalRange))
        }
        return Array(built.suffix(maxWorkouts))
    }

    /// A sport label the server will store: printable, trimmed, at most 40 characters; nil when nothing
    /// is left. Characters are Unicode scalars, as the server counts them.
    static func sportLabel(_ raw: String) -> String? {
        var kept = String.UnicodeScalarView()
        var count = 0
        for scalar in trimmed(raw.unicodeScalars) {
            if count >= maxSportLength { break }
            guard printable(scalar) else { continue }
            kept.append(scalar)
            count += 1
        }
        let out = String(trimmed(kept))
        return out.isEmpty ? nil : out
    }

    /// The latest reading, the resting figure and the day's line, or nil with no usable sample. One
    /// sample per second is kept, the first as they arrived.
    static func heartRate(_ samples: [(ts: Int, bpm: Int)], restingBpm: Int?, ts: (Int) -> Int?) -> FriendsDay.HeartRate? {
        var seen = Set<Int>()
        let valid = stablySorted(samples.filter { bpmRange.contains($0.bpm) && ts($0.ts) != nil }, by: \.ts)
            .filter { seen.insert($0.ts).inserted }
        guard let last = valid.last else { return nil }
        return FriendsDay.HeartRate(lastBpm: last.bpm, lastTs: last.ts,
                                    restingBpm: restingBpm.flatMap { bpmRange.contains($0) ? $0 : nil },
                                    series: downsample(valid).map { [$0.ts, $0.bpm] })
    }

    /// `samples` (oldest first, one per timestamp) thinned to at most `maxPoints`. A short day is sent as
    /// it is. A longer one is cut into equal stretches of time, each drawn as the mean of its samples at
    /// their mean time, and the line always ends on the newest sample itself, so its last point and the
    /// "latest heart rate" beside it are one reading and cannot disagree.
    public static func downsample(_ samples: [(ts: Int, bpm: Int)],
                                  maxPoints: Int = maxSeriesPoints) -> [(ts: Int, bpm: Int)] {
        guard samples.count > maxPoints, let last = samples.last else { return samples }
        guard maxPoints > 1, let first = samples.first?.ts else { return [last] }
        let buckets = maxPoints - 1
        let span = max(1, last.ts - first)
        var sumTs = [Int](repeating: 0, count: buckets)
        var sumBpm = [Int](repeating: 0, count: buckets)
        var count = [Int](repeating: 0, count: buckets)
        for sample in samples.dropLast() {
            let index = min(buckets - 1, max(0, (sample.ts - first) * buckets / span))
            sumTs[index] += sample.ts - first
            sumBpm[index] += sample.bpm
            count[index] += 1
        }
        var out: [(ts: Int, bpm: Int)] = []
        out.reserveCapacity(maxPoints)
        for i in 0..<buckets where count[i] > 0 {
            out.append((first + sumTs[i] / count[i], Int((Double(sumBpm[i]) / Double(count[i])).rounded())))
        }
        out.append(last)
        return out
    }

    // MARK: - Rounding

    /// A whole number inside `range`, or nil. Halves round toward positive infinity, the way Kotlin's
    /// `roundToInt` does, so the two platforms agree on every figure, a negative half included.
    static func whole(_ value: Double?, in range: ClosedRange<Int>) -> Int? {
        guard let value, value.isFinite else { return nil }
        let floor = value.rounded(.down)
        let rounded = value - floor >= 0.5 ? floor + 1 : floor
        guard rounded >= Double(range.lowerBound), rounded <= Double(range.upperBound) else { return nil }
        return Int(rounded)
    }

    /// Strain to two decimals, as the server stores it, or nil outside 0-100.
    static func strain(_ value: Double?) -> Double? {
        hundredths(value).flatMap { Double("\($0)") }
    }

    /// `value` to two decimals, half up, read from its shortest decimal form (38.605 is 38.61, although
    /// the double under it is a hair below): the reading `BigDecimal.valueOf` gives the Kotlin twin.
    private static func hundredths(_ value: Double?) -> Decimal? {
        // Past the range nothing is kept anyway, and a `Decimal` cannot hold every double.
        guard let value, value.isFinite, value >= 0, value < 1_000,
              var exact = Decimal(string: "\(value)") else { return nil }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &exact, 2, .plain)
        return rounded <= 100 ? rounded : nil
    }

    // MARK: - Text

    /// What Kotlin's `trim` removes: the space, line and paragraph separators and the ASCII controls
    /// that separate text.
    private static func isSpace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
        default: return (0x09...0x0D).contains(scalar.value) || (0x1C...0x1F).contains(scalar.value)
        }
    }

    private static func trimmed(_ scalars: String.UnicodeScalarView) -> String.UnicodeScalarView {
        var slice = scalars[...]
        while let first = slice.first, isSpace(first) { slice = slice.dropFirst() }
        while let last = slice.last, isSpace(last) { slice = slice.dropLast() }
        return String.UnicodeScalarView(slice)
    }

    private static func printable(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .control, .format, .surrogate, .privateUse, .unassigned, .lineSeparator, .paragraphSeparator:
            return false
        case .spaceSeparator:
            return scalar == " "
        default:
            return true
        }
    }

    /// Ascending by `key`, equal keys in the order they came (the standard sort does not promise that).
    private static func stablySorted<T>(_ items: [T], by key: (T) -> Int) -> [T] {
        items.enumerated()
            .sorted { (key($0.element), $0.offset) < (key($1.element), $1.offset) }
            .map(\.element)
    }

    // MARK: - The wire text
    //
    // Written by hand rather than through `JSONEncoder`: the upload is skipped when this text is the same
    // as the last one the server accepted for the day, so it must come out byte for byte the same for the
    // same figures, and the same as the Kotlin twin writes. Members are in the README's order.

    public static func json(_ day: FriendsDay) -> String {
        var members: [String] = []
        if let v = day.recovery { members.append("\"recovery\":\(v)") }
        if let v = day.strain { members.append("\"strain\":\(number(v))") }
        if let v = day.sleepScore { members.append("\"sleepScore\":\(v)") }
        if let s = day.sleep {
            var parts = ["\"startTs\":\(s.startTs)", "\"endTs\":\(s.endTs)", "\"asleepMin\":\(s.asleepMin)"]
            if let v = s.awakeMin { parts.append("\"awakeMin\":\(v)") }
            if let v = s.remMin { parts.append("\"remMin\":\(v)") }
            if let v = s.lightMin { parts.append("\"lightMin\":\(v)") }
            if let v = s.deepMin { parts.append("\"deepMin\":\(v)") }
            if let v = s.needMin { parts.append("\"needMin\":\(v)") }
            members.append("\"sleep\":{\(parts.joined(separator: ","))}")
        }
        if let list = day.workouts {
            let items = list.map { w -> String in
                var parts = ["\"startTs\":\(w.startTs)", "\"sport\":\(quote(w.sport))", "\"durationS\":\(w.durationS)"]
                if let v = w.strain { parts.append("\"strain\":\(number(v))") }
                if let v = w.avgHr { parts.append("\"avgHr\":\(v)") }
                if let v = w.maxHr { parts.append("\"maxHr\":\(v)") }
                if let v = w.kcal { parts.append("\"kcal\":\(v)") }
                return "{\(parts.joined(separator: ","))}"
            }
            members.append("\"workouts\":[\(items.joined(separator: ","))]")
        }
        if let h = day.hr {
            var parts = ["\"lastBpm\":\(h.lastBpm)", "\"lastTs\":\(h.lastTs)"]
            if let v = h.restingBpm { parts.append("\"restingBpm\":\(v)") }
            let points = (h.series ?? []).filter { $0.count == 2 }.map { "[\($0[0]),\($0[1])]" }
            parts.append("\"series\":[\(points.joined(separator: ","))]")
            members.append("\"hr\":{\(parts.joined(separator: ","))}")
        }
        return "{\(members.joined(separator: ","))}"
    }

    /// A decimal without an exponent or a trailing zero: 38.6, 38, 0.05.
    private static func number(_ value: Double) -> String {
        hundredths(value).map { "\($0)" } ?? "0"
    }

    private static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }
}
