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
// (rounding, caps, the heart-rate bins) is the reference for a second implementation. Pure: no I/O, no
// clock reads. Times are unix seconds, minutes are whole, and strain is on the stored 0-100 axis.

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

    /// True when there is nothing to show: such a day is not uploaded.
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
    /// The width of one bin of the heart-rate line: 288 bins cover a day, under the 300-point cap.
    public static let seriesBinSeconds = 300
    /// What the service accepts as a heart rate; a sample outside it is not a reading.
    public static let bpmRange = 20...250

    /// What the phone has for one day, before any sharing switch is applied.
    public struct Input: Equatable, Sendable {
        /// Recovery, 0-100 (the app stores it 0-100; a 0-1 fraction is not accepted here).
        public var recovery: Double?
        /// Day strain on the stored 0-100 axis.
        public var strain: Double?
        /// The Sleep score, 0-100.
        public var sleepScore: Double?
        public var sleep: SleepInput?
        public var workouts: [WorkoutInput]
        /// Heart-rate samples of the day as `(ts, bpm)`, in any order.
        public var heartRate: [(ts: Int, bpm: Int)]
        public var restingBpm: Int?

        public init(recovery: Double? = nil, strain: Double? = nil, sleepScore: Double? = nil,
                    sleep: SleepInput? = nil, workouts: [WorkoutInput] = [],
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

    /// The night that ended on this day, in minutes as the Sleep page totals them.
    public struct SleepInput: Equatable, Sendable {
        public var startTs: Int
        public var endTs: Int
        public var awakeMin: Double
        public var remMin: Double
        public var lightMin: Double
        public var deepMin: Double
        public var needMin: Double?

        public init(startTs: Int, endTs: Int, awakeMin: Double, remMin: Double, lightMin: Double,
                    deepMin: Double, needMin: Double? = nil) {
            self.startTs = startTs; self.endTs = endTs; self.awakeMin = awakeMin; self.remMin = remMin
            self.lightMin = lightMin; self.deepMin = deepMin; self.needMin = needMin
        }
    }

    public struct WorkoutInput: Equatable, Sendable {
        public var startTs: Int
        public var endTs: Int
        public var sport: String
        public var durationS: Double?
        public var strain: Double?
        public var avgHr: Int?
        public var maxHr: Int?
        public var kcal: Double?

        public init(startTs: Int, endTs: Int, sport: String, durationS: Double? = nil, strain: Double? = nil,
                    avgHr: Int? = nil, maxHr: Int? = nil, kcal: Double? = nil) {
            self.startTs = startTs; self.endTs = endTs; self.sport = sport; self.durationS = durationS
            self.strain = strain; self.avgHr = avgHr; self.maxHr = maxHr; self.kcal = kcal
        }
    }

    /// The day as it is uploaded: every section whose switch is off is left out, and every figure is
    /// rounded and capped the one way both platforms must.
    public static func day(_ input: Input, share: FriendsShare) -> FriendsDay {
        var day = FriendsDay()
        if share.scores {
            day.recovery = input.recovery.flatMap { percent($0) }
            day.strain = input.strain.flatMap { tenth($0, in: 0...100) }
            day.sleepScore = input.sleepScore.flatMap { percent($0) }
        }
        if share.sleep, let night = input.sleep { day.sleep = sleep(night) }
        if share.workouts {
            let built = workouts(input.workouts)
            if !built.isEmpty { day.workouts = built }
        }
        if share.hr { day.hr = heartRate(input.heartRate, restingBpm: input.restingBpm) }
        return day
    }

    /// A night, or nil when it has no span or no time asleep. Time asleep is the sum of the three
    /// sleeping stages, each rounded first, so the parts a friend sees add up to the total.
    static func sleep(_ night: SleepInput) -> FriendsDay.Sleep? {
        guard night.endTs > night.startTs else { return nil }
        let rem = minutes(night.remMin), light = minutes(night.lightMin), deep = minutes(night.deepMin)
        let asleep = min(1440, rem + light + deep)
        guard asleep > 0 else { return nil }
        return FriendsDay.Sleep(startTs: night.startTs, endTs: night.endTs, asleepMin: asleep,
                                awakeMin: minutes(night.awakeMin), remMin: rem, lightMin: light,
                                deepMin: deep, needMin: night.needMin.map { minutes($0) })
    }

    /// The day's workouts, oldest first, at most `maxWorkouts` (the latest ones when there are more).
    static func workouts(_ rows: [WorkoutInput]) -> [FriendsDay.Workout] {
        let built: [FriendsDay.Workout] = rows.compactMap { row in
            let sport = String(row.sport.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxSportLength))
            guard !sport.isEmpty else { return nil }
            let seconds = row.durationS ?? Double(row.endTs - row.startTs)
            guard seconds.isFinite, seconds >= 0 else { return nil }
            return FriendsDay.Workout(
                startTs: row.startTs, sport: sport, durationS: min(86_400, Int(seconds.rounded())),
                strain: row.strain.flatMap { tenth($0, in: 0...100) },
                avgHr: row.avgHr.flatMap { bpmRange.contains($0) ? $0 : nil },
                maxHr: row.maxHr.flatMap { bpmRange.contains($0) ? $0 : nil },
                kcal: row.kcal.flatMap { $0.isFinite && $0 >= 0 ? min(20_000, Int($0.rounded())) : nil })
        }
        return Array(built.sorted { $0.startTs < $1.startTs }.suffix(maxWorkouts))
    }

    /// The latest reading, the resting figure and the day's line, or nil with no usable sample.
    ///
    /// The line is the mean of each `seriesBinSeconds` bin on the unix clock (so two phones bin alike
    /// whatever their zone), rounded half up in integers, stamped at the bin's start, oldest first. If a
    /// span longer than a day leaves more than `maxSeriesPoints` bins, the newest are kept.
    static func heartRate(_ samples: [(ts: Int, bpm: Int)], restingBpm: Int?) -> FriendsDay.HeartRate? {
        let usable = samples.filter { bpmRange.contains($0.bpm) && $0.ts > 0 }
        guard let last = usable.max(by: { ($0.ts, $0.bpm) < ($1.ts, $1.bpm) }) else { return nil }
        var sums: [Int: (sum: Int, count: Int)] = [:]
        for s in usable {
            let bin = s.ts / seriesBinSeconds * seriesBinSeconds
            let current = sums[bin] ?? (0, 0)
            sums[bin] = (current.sum + s.bpm, current.count + 1)
        }
        let series = sums.keys.sorted().suffix(maxSeriesPoints).map { bin -> [Int] in
            let (sum, count) = sums[bin]!
            return [bin, (2 * sum + count) / (2 * count)]
        }
        return FriendsDay.HeartRate(lastBpm: last.bpm, lastTs: last.ts,
                                    restingBpm: restingBpm.flatMap { bpmRange.contains($0) ? $0 : nil },
                                    series: series)
    }

    // MARK: - Rounding

    /// A 0-100 score as a whole number, or nil when it is not a finite value in range.
    static func percent(_ value: Double) -> Int? {
        guard value.isFinite, (0...100).contains(value) else { return nil }
        return Int(value.rounded())
    }

    /// One decimal place, or nil when out of `range`.
    static func tenth(_ value: Double, in range: ClosedRange<Double>) -> Double? {
        guard value.isFinite, range.contains(value) else { return nil }
        return (value * 10).rounded() / 10
    }

    /// Whole minutes, clamped to a day.
    static func minutes(_ value: Double) -> Int {
        guard value.isFinite else { return 0 }
        return min(1440, max(0, Int(value.rounded())))
    }
}
