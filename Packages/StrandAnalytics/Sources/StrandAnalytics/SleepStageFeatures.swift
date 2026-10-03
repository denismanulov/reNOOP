import Foundation
import WhoopProtocol

// SleepStageFeatures.swift — the per-epoch inputs of the LEARNED sleep-stage model.
//
// `SleepStagerV2` stages a night from a handful of per-epoch quantities and coefficients fixed a priori.
// The learned model (experimental, default off, run in shadow beside V2 — see `Tools/SleepML`) replaces
// those coefficients with ones fit to human-scored polysomnography. A model is only as portable as its
// inputs, so the inputs are computed HERE, by the code that ships: the training table is this file's
// output over the open datasets, and inference in the app calls the same function. Nothing is
// reimplemented in the training script.
//
// Three properties every feature is held to, because the training data comes from other hardware:
//
//  1. ONE INPUT SHAPE. Per-second mean acceleration in g, heart rate, beat intervals where they exist:
//     what a WHOOP strap gives, and what every dataset is reduced to.
//  2. ONE HEART-RATE CADENCE. An Apple Watch reports heart rate about every 5 s, an Empatica E4 and a
//     strap every second. Every heart-rate feature is computed from 10 s bin means, so a source's own
//     cadence does not leak into a variability feature.
//  3. NIGHT-RELATIVE SCALES. Heart rate enters as a z-score or rank within the night; motion as a
//     multiple of the night's own quiescent jerk floor, the same floor `SleepStagerV2` thresholds on. A
//     different accelerometer, fit or resting pulse moves the scale, not the feature.
//
// Beat-interval features are optional: nil for a source without intervals, and nil for an epoch whose
// window holds too few beats. Whether the model may lean on them is a training decision, not made here.
//
// The window is the sleep session: it starts where the wearer fell asleep. Context is read from outside
// it (`reach` seconds either side) because what the wrist did in the half hour before sleep is evidence
// about the first epochs of it; the callers already hold those samples.

public enum SleepStageFeatures {

    /// Column names, in the order of `Row.values`. Append only: a trained model binds to these by name.
    public static let names: [String] = [
        "minutes", "fraction", "minutes_left",
        "move_frac", "jerk_max_log", "jerk_mean_log",
        "move_prev_5m", "move_next_5m", "move_prev_30m", "move_next_30m",
        "still_minutes", "still_ahead_minutes", "tilt_change", "posture_minutes",
        "hr_z", "hr_rank", "hr_sd_5m_z", "hr_sd_11m_rank", "hr_slope_5m",
        "hr_step_prev", "hr_step_next", "hr_range_90s", "hr_z_30m",
        "rr_rmssd_5m_z", "rr_sdnn_5m_z", "rr_resp_reg_z",
    ]

    /// One 30 s epoch. `values[i]` is the feature `names[i]`, nil where it could not be measured.
    public struct Row: Equatable, Sendable {
        public let start: Int
        public let values: [Double?]
    }

    /// Seconds of stream read outside the window, either side.
    public static let reach = 1800

    static let hrBin = 10                 // seconds per heart-rate bin
    static let capMinutes = 120.0         // ceiling of the "minutes since / until" features
    static let postureDegrees = 20.0      // an epoch-to-epoch tilt above this is a change of posture
    static let minBeats = 60              // beats a 5-min window needs before its interval statistics count

    /// Features for every 30 s epoch of the wall-clock grid covering `[start, end)`: the grid
    /// `SleepStagerV2` stages on. Streams may be unsorted and may run far outside the window.
    public static func rows(start: Int, end: Int, grav: [GravitySample],
                            hr: [HRSample], rr: [RRInterval]) -> [Row] {
        if end <= start { return [] }
        let firstE = ((start + 29) / 30) * 30
        if firstE >= end { return [] }
        let nWin = (end - firstE + 29) / 30                       // epochs in the window
        let pad = reach / 30                                      // context epochs either side
        let lo = firstE - reach                                   // first second read
        let nEp = nWin + 2 * pad
        let nSec = nEp * 30
        func epochStart(_ i: Int) -> Int { lo + i * 30 }          // i over the padded range

        // ── per-second gravity and jerk ───────────────────────────────────────────────────────────
        var gx = [Double](repeating: 0, count: nSec), gy = gx, gz = gx
        var gn = [Int](repeating: 0, count: nSec)
        for g in grav {
            let t = g.ts - lo
            if t < 0 || t >= nSec { continue }
            gx[t] += g.x; gy[t] += g.y; gz[t] += g.z; gn[t] += 1
        }
        var jerk = [Double](repeating: .nan, count: nSec)         // jerk[t]: second t-1 -> t, both present
        for t in 0..<nSec where gn[t] > 0 {
            let d = Double(gn[t])
            gx[t] /= d; gy[t] /= d; gz[t] /= d
            if t > 0 && gn[t - 1] > 0 {
                let dx = gx[t] - gx[t - 1], dy = gy[t] - gy[t - 1], dz = gz[t] - gz[t - 1]
                jerk[t] = (dx * dx + dy * dy + dz * dz).squareRoot()
            }
        }
        // The night's quiescent jerk floor: the median per-second jerk over the window, as in V2.
        let winLo = start - lo, winHi = min(nSec, end - lo)
        var pool: [Double] = []
        pool.reserveCapacity(max(0, winHi - winLo))
        for t in max(0, winLo)..<winHi where !jerk[t].isNaN { pool.append(jerk[t]) }
        let floor = max(median(pool) ?? 0, 1e-6)
        let moveThr = floor * SleepStagerV2.jerkFloorMoveMult

        // ── per-epoch motion over the padded range ────────────────────────────────────────────────
        var moveFrac = [Double?](repeating: nil, count: nEp)
        var jerkMax = [Double?](repeating: nil, count: nEp)
        var jerkMean = [Double?](repeating: nil, count: nEp)
        var dir = [(Double, Double, Double)?](repeating: nil, count: nEp)   // mean gravity direction
        for i in 0..<nEp {
            var n = 0, moves = 0, sum = 0.0, mx = 0.0
            var sx = 0.0, sy = 0.0, sz = 0.0, gcount = 0
            for t in (i * 30)..<(i * 30 + 30) {
                if gn[t] > 0 { sx += gx[t]; sy += gy[t]; sz += gz[t]; gcount += 1 }
                let j = jerk[t]
                if j.isNaN { continue }
                n += 1; sum += j; mx = max(mx, j)
                if j > moveThr { moves += 1 }
            }
            if n > 0 {
                moveFrac[i] = Double(moves) / Double(n)
                jerkMax[i] = log10(mx / floor + 1)
                jerkMean[i] = log10(sum / Double(n) / floor + 1)
            }
            if gcount > 0 {
                let norm = (sx * sx + sy * sy + sz * sz).squareRoot()
                if norm > 0 { dir[i] = (sx / norm, sy / norm, sz / norm) }
            }
        }
        var tilt = [Double?](repeating: nil, count: nEp)          // degrees turned since the epoch before
        for i in 1..<nEp {
            guard let a = dir[i - 1], let b = dir[i] else { continue }
            let dot = min(1.0, max(-1.0, a.0 * b.0 + a.1 * b.1 + a.2 * b.2))
            tilt[i] = acos(dot) * 180 / Double.pi
        }

        // ── heart rate on 10 s bins ───────────────────────────────────────────────────────────────
        let nBin = nSec / hrBin
        var bSum = [Double](repeating: 0, count: nBin)
        var bCnt = [Int](repeating: 0, count: nBin)
        for s in hr {
            let t = s.ts - lo
            if t < 0 || t >= nSec { continue }
            bSum[t / hrBin] += Double(s.bpm); bCnt[t / hrBin] += 1
        }
        var bin = [Double](repeating: .nan, count: nBin)
        for b in 0..<nBin where bCnt[b] > 0 { bin[b] = bSum[b] / Double(bCnt[b]) }
        /// Present bins of `[a, b)` seconds relative to `lo`, clamped to the padded range.
        func bins(_ a: Int, _ b: Int) -> [(Double, Double)] {       // (bin centre in seconds, bpm)
            var out: [(Double, Double)] = []
            let first = max(0, a) / hrBin, last = min(nSec, b) / hrBin
            if last <= first { return out }
            for k in first..<last where !bin[k].isNaN { out.append((Double(k * hrBin) + Double(hrBin) / 2, bin[k])) }
            return out
        }
        func meanBpm(_ a: Int, _ b: Int) -> Double? {
            let v = bins(a, b)
            return v.isEmpty ? nil : v.reduce(0) { $0 + $1.1 } / Double(v.count)
        }
        func sdBpm(_ a: Int, _ b: Int) -> Double? {
            let v = bins(a, b)
            if v.count < 3 { return nil }
            let m = v.reduce(0) { $0 + $1.1 } / Double(v.count)
            return (v.reduce(0) { $0 + ($1.1 - m) * ($1.1 - m) } / Double(v.count)).squareRoot()
        }
        var epochHR = [Double?](repeating: nil, count: nEp)
        for i in 0..<nEp { epochHR[i] = meanBpm(i * 30, i * 30 + 30) }

        // ── beat intervals ────────────────────────────────────────────────────────────────────────
        // Kept in arrival order within a second, as the strap delivers them.
        var beatT: [Int] = [], beatMs: [Double] = []
        if !rr.isEmpty {
            let sorted = rr.enumerated().sorted {
                $0.element.ts != $1.element.ts ? $0.element.ts < $1.element.ts : $0.offset < $1.offset
            }
            for (_, r) in sorted {
                let t = r.ts - lo
                if t < 0 || t >= nSec { continue }
                beatT.append(t); beatMs.append(min(max(Double(r.rrMs), 300), 2000))
            }
        }
        func beatRange(_ a: Int, _ b: Int) -> Range<Int> {
            var l = 0, h = beatT.count
            while l < h { let m = (l + h) / 2; if beatT[m] < a { l = m + 1 } else { h = m } }
            let first = l
            h = beatT.count
            while l < h { let m = (l + h) / 2; if beatT[m] < b { l = m + 1 } else { h = m } }
            return first..<l
        }
        var respDFT: [Int: SleepStagerV2.RespDFT] = [:]

        // ── window epochs: raw values, then night-relative forms ──────────────────────────────────
        let w0 = pad                                              // padded index of the window's first epoch
        var rawHRsd5 = [Double?](repeating: nil, count: nWin)
        var rawHRsd11 = [Double?](repeating: nil, count: nWin)
        var rawSlope = [Double?](repeating: nil, count: nWin)
        var rawRmssd = [Double?](repeating: nil, count: nWin)
        var rawSdnn = [Double?](repeating: nil, count: nWin)
        var rawResp = [Double?](repeating: nil, count: nWin)
        for k in 0..<nWin {
            let e = (w0 + k) * 30                                 // epoch start, seconds from `lo`
            rawHRsd5[k] = sdBpm(e - 150, e + 180)
            rawHRsd11[k] = sdBpm(e - 330, e + 390)
            let v = bins(e - 150, e + 180)
            if v.count >= 6 {
                let mt = v.reduce(0) { $0 + $1.0 } / Double(v.count)
                let mh = v.reduce(0) { $0 + $1.1 } / Double(v.count)
                let sxx = v.reduce(0) { $0 + ($1.0 - mt) * ($1.0 - mt) }
                if sxx > 0 { rawSlope[k] = v.reduce(0) { $0 + ($1.0 - mt) * ($1.1 - mh) } / sxx * 60 }
            }
            if !beatT.isEmpty {
                let r5 = beatRange(e - 150, e + 180)
                if r5.count >= minBeats {
                    let ms = Array(beatMs[r5])
                    let m = ms.reduce(0, +) / Double(ms.count)
                    rawSdnn[k] = (ms.reduce(0) { $0 + ($1 - m) * ($1 - m) } / Double(ms.count)).squareRoot()
                    // Successive differences only across beats that follow one another: a pair further
                    // apart in time than the later interval is long has a dropped beat between.
                    var sq = 0.0, pairs = 0
                    for i in r5.dropFirst() {
                        if Double(beatT[i] - beatT[i - 1]) > beatMs[i] / 1000 + 1 { continue }
                        let d = beatMs[i] - beatMs[i - 1]
                        sq += d * d; pairs += 1
                    }
                    if pairs >= minBeats / 2 { rawRmssd[k] = (sq / Double(pairs)).squareRoot() }
                }
                let rs = beatRange(e - 90, e + 120)
                if rs.count >= 12 {
                    let beats = rs.map { (Double(beatT[$0]), beatMs[$0]) }
                    rawResp[k] = SleepStagerV2.respRegularity(beats, dft: &respDFT)
                }
            }
        }
        let winHR = (0..<nWin).map { epochHR[w0 + $0] }
        let (hrMean, hrSD) = meanSD(winHR)
        let zSD5 = zscore(rawHRsd5), rkSD11 = rank(rawHRsd11), rkHR = rank(winHR)
        let zRmssd = zscore(rawRmssd), zSdnn = zscore(rawSdnn), zResp = zscore(rawResp)
        func hrZ(_ i: Int) -> Double? { epochHR[i].map { ($0 - hrMean) / hrSD } }   // padded index

        /// Mean of the present values of `v` over padded epochs `[a, b)`.
        func meanOver(_ v: [Double?], _ a: Int, _ b: Int) -> Double? {
            var s = 0.0, n = 0
            for i in max(0, a)..<min(nEp, b) { if let x = v[i] { s += x; n += 1 } }
            return n == 0 ? nil : s / Double(n)
        }
        /// Minutes back (step -1) or ahead (step +1) from padded epoch `i` to the nearest epoch where
        /// `hit` holds, capped. An epoch without a measurement is not evidence either way and is passed over.
        func minutesTo(_ i: Int, step: Int, _ hit: (Int) -> Bool?) -> Double? {
            var j = i, seen = false
            while j >= 0 && j < nEp {
                if let h = hit(j) {
                    seen = true
                    if h { return min(capMinutes, Double(abs(j - i)) * 0.5) }
                }
                if Double(abs(j - i)) * 0.5 >= capMinutes { break }
                j += step
            }
            return seen ? min(capMinutes, Double(abs(j - i)) * 0.5) : nil
        }

        let span = Double(end - start)
        var out: [Row] = []
        out.reserveCapacity(nWin)
        for k in 0..<nWin {
            let i = w0 + k
            let e = epochStart(i)
            let centre = Double(e + 15 - start)
            var v: [Double?] = []
            v.reserveCapacity(names.count)
            v.append(centre / 60)
            v.append(centre / span)
            v.append((span - centre) / 60)

            v.append(moveFrac[i])
            v.append(jerkMax[i])
            v.append(jerkMean[i])
            v.append(meanOver(moveFrac, i - 10, i))
            v.append(meanOver(moveFrac, i + 1, i + 11))
            v.append(meanOver(moveFrac, i - 60, i))
            v.append(meanOver(moveFrac, i + 1, i + 61))
            v.append(minutesTo(i, step: -1) { moveFrac[$0].map { $0 > 0 } })
            v.append(minutesTo(i, step: 1) { moveFrac[$0].map { $0 > 0 } })
            v.append(tilt[i])
            v.append(minutesTo(i, step: -1) { tilt[$0].map { $0 > postureDegrees } })

            v.append(hrZ(i))
            v.append(rkHR[k])
            v.append(zSD5[k])
            v.append(rkSD11[k])
            v.append(rawSlope[k].map { $0 / hrSD })
            let before = meanBpm(i * 30 - 300, i * 30), after = meanBpm(i * 30 + 30, i * 30 + 330)
            v.append(epochHR[i].flatMap { h in before.map { (h - $0) / hrSD } })
            v.append(epochHR[i].flatMap { h in after.map { ($0 - h) / hrSD } })
            let near = bins(i * 30 - 30, i * 30 + 60).map { $0.1 }
            v.append(near.count < 3 ? nil : (near.max()! - near.min()!) / hrSD)
            var zs = 0.0, zn = 0
            for j in max(0, i - 30)...min(nEp - 1, i + 30) { if let z = hrZ(j) { zs += z; zn += 1 } }
            v.append(zn == 0 ? nil : zs / Double(zn))

            v.append(zRmssd[k])
            v.append(zSdnn[k])
            v.append(zResp[k])
            out.append(Row(start: e, values: v))
        }
        return out
    }

    // MARK: - Night-relative scales

    static func median(_ v: [Double]) -> Double? {
        if v.isEmpty { return nil }
        let s = v.sorted()
        return s.count % 2 == 1 ? s[s.count / 2] : 0.5 * (s[s.count / 2 - 1] + s[s.count / 2])
    }

    /// Mean and population standard deviation of the present values; (0, 1) when there are none, and a
    /// zero deviation becomes 1 so a flat channel reads as neutral rather than dividing by nothing.
    static func meanSD(_ v: [Double?]) -> (Double, Double) {
        let p = v.compactMap { $0 }
        if p.isEmpty { return (0, 1) }
        let m = p.reduce(0, +) / Double(p.count)
        let sd = (p.reduce(0) { $0 + ($1 - m) * ($1 - m) } / Double(p.count)).squareRoot()
        return (m, sd == 0 ? 1 : sd)
    }

    static func zscore(_ v: [Double?]) -> [Double?] {
        let (m, sd) = meanSD(v)
        return v.map { $0.map { ($0 - m) / sd } }
    }

    /// Rank within the night as a fraction in (0, 1]: the share of present values at or below this one.
    static func rank(_ v: [Double?]) -> [Double?] {
        let s = v.compactMap { $0 }.sorted()
        if s.isEmpty { return v.map { _ in nil } }
        return v.map { x in
            guard let x = x else { return nil }
            var lo = 0, hi = s.count
            while lo < hi { let m = (lo + hi) / 2; if s[m] <= x { lo = m + 1 } else { hi = m } }
            return Double(lo) / Double(s.count)
        }
    }
}
