#if !os(watchOS)
// Chart data helpers shared by OverviewHRChart and Sparkline; the watch never draws them.
import SwiftUI
import Charts

// MARK: - Chart data

/// The index runs that `hrGapSegments` implies: one range per unbroken stretch, in order.
///
/// Charts can hand a segment id to the plotting library and let it split the line. A hand-drawn sparkline
/// cannot, so it needs the runs themselves to know where to lift the pen. Same rule, same source of truth,
/// rather than a second walk that could disagree with the first (#2082).
///
/// An empty input yields no runs. A run of one is still a run: a lone bucket between two gaps is real data
/// and a caller that drops it would be hiding a reading rather than a gap.
public func hrGapRuns(segments: [String]) -> [ClosedRange<Int>] {
    guard !segments.isEmpty else { return [] }
    var runs: [ClosedRange<Int>] = []
    var start = 0
    for i in 1..<segments.count where segments[i] != segments[i - 1] {
        runs.append(start...(i - 1))
        start = i
    }
    runs.append(start...(segments.count - 1))
    return runs
}

/// Segment ids for a bucketed time series, changing wherever the series SKIPS a bucket.
///
/// A bucket aggregate only emits rows for buckets that had samples, so an hour the strap was off simply
/// is not in the list. Without this the line joins the two neighbours across that hour and draws a
/// steady climb the wearer never had, which is a reading invented out of an absence. Handing these to
/// `TrendPoint.segment` renders the two sides as separate lines, so a gap looks like a gap.
///
/// A step of exactly one bucket is contiguous. Anything longer means at least one bucket held nothing,
/// and that is the break. No tolerance for "just one missing": a five-minute hole is still five minutes
/// of invention, and the stress trace made the same call when it stopped drawing through unscored hours.
///
/// Apple only: the Kotlin twin went with the Android chart that drew it (Material 3 port).
public func hrGapSegments(bucketTs: [Int], bucketSeconds: Int) -> [String] {
    var segment = 0
    return bucketTs.enumerated().map { i, ts in
        if i > 0, ts - bucketTs[i - 1] > bucketSeconds { segment += 1 }
        return String(segment)
    }
}

/// One point on a trend line.
public struct TrendPoint: Identifiable, Sendable {
    public var date: Date
    public var value: Double
    /// Sequential line-segment identity. Points with different ids are rendered as separate lines, so a
    /// metric can retain history without drawing a false transition across incompatible methods.
    public var segment: String

    /// Stable, content-derived identity (one point per date in a series). A random
    /// `UUID()` defeats Swift Charts' diffing — every render re-identifies all marks
    /// and replays the draw animation; keying on the date lets Charts diff by data.
    public var id: Date { date }

    public init(date: Date, value: Double, segment: String = "default") {
        self.date = date
        self.value = value
        self.segment = segment
    }
}

// MARK: - Sleep intervals

/// A single stage interval. `start`/`end` are seconds from the start of the night.
public struct SleepInterval: Identifiable, Sendable {
    public var stage: SleepStage
    public var start: TimeInterval
    public var end: TimeInterval

    /// Stable, CONTENT-derived identity (stage + start + end) rather than a random `UUID()`.
    /// A fresh UUID per value defeated SwiftUI's `ForEach` diffing — every body eval re-identified
    /// all bands as brand-new, so the whole hypnogram rebuilt on each hover/diff. Intervals are
    /// non-overlapping with distinct starts within a night, so this composite is unique and stable.
    public var id: String { "\(stage.rawValue)|\(start)|\(end)" }

    public init(stage: SleepStage, start: TimeInterval, end: TimeInterval) {
        self.stage = stage
        self.start = start
        self.end = end
    }

    public var duration: TimeInterval { max(0, end - start) }
}

// MARK: - Chart downsampling (pure)
//
// Reduces a dense point series to roughly the plot's pixel width BEFORE it reaches Swift Charts, so the
// GPU draws ~one vertex per pixel instead of hundreds it can't resolve. Uses MIN/MAX-per-bucket: each
// bucket contributes its lowest and highest sample (in time order), so every visible peak and trough
// survives and the rendered envelope is identical at normal chart widths. First and last points are
// always kept so the line spans the full domain. Pure + deterministic — same input → same output.

public enum ChartDownsample {
    /// Above this many points we downsample; at or below it the series is passed through untouched (so
    /// the common 7/30/90-day trends and the ≤60-point dotted series are byte-for-byte unchanged).
    public static let markThreshold = 120
    /// Target drawn-vertex budget — a touch above a typical ~360pt plot so the line stays crisp.
    public static let targetVertices = 400

    /// Min/max-bucketed copy of `points` when it exceeds `threshold`, else `points` unchanged.
    /// Assumes `points` is already sorted by date (both chart callers sort in their init).
    public static func minMaxBucketed(_ points: [TrendPoint], threshold: Int, targetCount: Int) -> [TrendPoint] {
        minMaxBucketed(points, threshold: threshold, targetCount: targetCount,
                       date: { $0.date }, value: { $0.value })
    }

    /// Generic form of the same algorithm, keyed by caller-supplied `date`/`value` accessors instead of
    /// `TrendPoint`'s own properties. `ChartDownsample` used to be internal-by-default and `TrendPoint`-only,
    /// so a chart type living outside this package (an app-target screen) couldn't call it and instead
    /// hand-duplicated the algorithm for its own point type (see `CompareView.Model.minMaxBucketed`, which
    /// predates this generic form and documents the mirroring). This overload is `public` and generic so
    /// new app-target chart types can share the ONE implementation instead of adding a third copy.
    /// Pure + deterministic; identical behaviour to the `TrendPoint` overload when `date`/`value` project
    /// the same fields it does.
    public static func minMaxBucketed<Point>(
        _ points: [Point],
        threshold: Int,
        targetCount: Int,
        date: (Point) -> Date,
        value: (Point) -> Double
    ) -> [Point] {
        let n = points.count
        guard n > threshold, n > 2, targetCount >= 4 else { return points }

        // Reserve the first and last; bucket the interior. Each bucket yields up to 2 vertices (min+max),
        // so aim for ~targetCount/2 buckets to land near the vertex budget.
        let first = points[0]
        let last = points[n - 1]
        let interior = n - 2
        let bucketCount = max(1, (targetCount - 2) / 2)
        guard bucketCount < interior else { return points }

        var out: [Point] = []
        out.reserveCapacity(targetCount)
        out.append(first)

        var lastEmittedDate = date(first)
        for b in 0..<bucketCount {
            // Interior indices [1 ... n-2] split into `bucketCount` contiguous ranges.
            let lo = 1 + (b * interior) / bucketCount
            let hi = 1 + ((b + 1) * interior) / bucketCount // exclusive
            guard lo < hi else { continue }

            // Find the min-value and max-value samples in this bucket.
            var minIdx = lo, maxIdx = lo
            var i = lo + 1
            while i < hi {
                if value(points[i]) < value(points[minIdx]) { minIdx = i }
                if value(points[i]) > value(points[maxIdx]) { maxIdx = i }
                i += 1
            }

            // Emit the two extremes in chronological order, skipping duplicates (monotone bucket → one
            // point) and any whose date would not advance (keeps `id: Date` unique for ForEach).
            let lowFirst = minIdx <= maxIdx
            let aIdx = lowFirst ? minIdx : maxIdx
            let bIdx = lowFirst ? maxIdx : minIdx
            for idx in [aIdx, bIdx] {
                let p = points[idx]
                let d = date(p)
                if d > lastEmittedDate {
                    out.append(p)
                    lastEmittedDate = d
                }
            }
        }

        if date(last) > lastEmittedDate { out.append(last) }
        return out
    }
}

// MARK: - Gradient → stops bridge

extension Gradient {
    /// Reconstruct ordered stops from a Gradient. SwiftUI does not expose `.stops`
    /// directly on all paths, so we use the public `stops` mirror when present.
    func toStops() -> [Gradient.Stop] {
        // `Gradient.stops` is public on macOS 13+; expose for our sampler.
        self.stops
    }
}

// MARK: - Default tooltip date

/// The charts' tooltip date ("EEE d MMM").
public enum ChartDates {
    private static let formatter: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "EEE d MMM"; return f
    }()

    public static func defaultDateString(_ date: Date) -> String { formatter.string(from: date) }
}

#endif
