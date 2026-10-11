package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// SleepStageFeatures.kt — the per-epoch inputs of the LEARNED sleep-stage model.
//
// Kotlin twin of `SleepStageFeatures.swift` (StrandAnalytics): the same quantities, summed in the same
// order, because the trees that read them compare each one against a threshold and the hypnogram is
// stored data. The reasoning behind each feature (one input shape, one heart-rate cadence,
// night-relative heart rate, motion in absolute g) is written out in the Swift file and not repeated
// here. Parity is pinned by `SleepStageOracleTest` against `sleep_stage_oracle.json`.
//
// The window is the sleep session: it starts where the wearer fell asleep. Context is read from outside
// it ([SleepStageFeatures.REACH] seconds either side) because what the wrist did in the half hour before
// sleep is evidence about the first epochs of it; the callers already hold those samples.

object SleepStageFeatures {

    /** Column names, in the order of [Row.values]. A trained model binds to these by name, so a name is
     *  never reused for a different quantity. */
    val names: List<String> = listOf(
        "minutes", "fraction", "minutes_left",
        "move_share", "jerk_max", "jerk_mean",
        "move_prev_5m", "move_next_5m", "move_prev_30m", "move_next_30m",
        "still_minutes", "still_ahead_minutes", "tilt_change", "posture_minutes",
        "hr_z", "hr_rank", "hr_sd_5m_z", "hr_sd_11m_rank", "hr_slope_5m",
        "hr_step_prev", "hr_step_next", "hr_range_rank", "hr_z_30m",
        "rr_rmssd_5m_z", "rr_sdnn_5m_z", "rr_resp_reg_z",
    )

    /** One 30 s epoch. `values[i]` is the feature `names[i]`, null where it could not be measured. */
    data class Row(val start: Long, val values: List<Double?>)

    /** Seconds of stream read outside the window, either side. */
    const val REACH = 1800

    /** What the model is given where a feature could not be measured: an ordinary number far outside
     *  every feature's range, so "not measured" is a value a tree can split on. Never NaN. */
    const val MISSING = -999.0

    /** A row as the model takes it: one number per name. */
    fun modelInput(row: Row): List<Double> = row.values.map { it ?: MISSING }

    internal const val HR_BIN = 10             // seconds per heart-rate bin
    private const val CAP_MINUTES = 120.0      // ceiling of the "minutes since / until" features
    private const val POSTURE_DEGREES = 20.0   // an epoch-to-epoch tilt above this is a change of posture
    private const val MIN_BEATS = 60           // beats a 5-min window needs before its interval statistics count
    /** A change of the per-second mean acceleration above this, in g, is movement. */
    private const val MOVE_G = 0.05
    private const val JERK_FLOOR_G = 1e-5      // below every sensor's resolution; keeps the logarithm finite

    /**
     * Features for every 30 s epoch of the wall-clock grid covering `[start, end)`: the grid
     * [SleepStagerV2] stages on. Streams may be unsorted and may run far outside the window.
     */
    fun rows(
        start: Long, end: Long, grav: List<GravitySample>,
        hr: List<HrSample>, rr: List<RrInterval>,
    ): List<Row> {
        if (end <= start) return emptyList()
        val firstE = ((start + 29) / 30) * 30
        if (firstE >= end) return emptyList()
        val nWin = ((end - firstE + 29) / 30).toInt()             // epochs in the window
        val pad = REACH / 30                                      // context epochs either side
        val lo = firstE - REACH                                   // first second read
        val nEp = nWin + 2 * pad
        val nSec = nEp * 30

        // ── per-second gravity and jerk ───────────────────────────────────────────────────────────
        val gx = DoubleArray(nSec); val gy = DoubleArray(nSec); val gz = DoubleArray(nSec)
        val gn = IntArray(nSec)
        for (g in grav) {
            val d = g.ts - lo
            if (d < 0 || d >= nSec) continue
            val t = d.toInt()
            gx[t] += g.x; gy[t] += g.y; gz[t] += g.z; gn[t] += 1
        }
        val jerk = DoubleArray(nSec) { Double.NaN }               // jerk[t]: second t-1 -> t, both present
        for (t in 0 until nSec) {
            if (gn[t] <= 0) continue
            val d = gn[t].toDouble()
            gx[t] /= d; gy[t] /= d; gz[t] /= d
            if (t > 0 && gn[t - 1] > 0) {
                val dx = gx[t] - gx[t - 1]; val dy = gy[t] - gy[t - 1]; val dz = gz[t] - gz[t - 1]
                jerk[t] = sqrt(dx * dx + dy * dy + dz * dz)
            }
        }

        // ── per-epoch motion over the padded range ────────────────────────────────────────────────
        val moveShare = arrayOfNulls<Double>(nEp)                 // share of seconds above MOVE_G
        val jerkMax = arrayOfNulls<Double>(nEp)                   // log10 of the peak jerk, g
        val jerkMean = arrayOfNulls<Double>(nEp)                  // log10 of the mean jerk, g
        val dirX = DoubleArray(nEp); val dirY = DoubleArray(nEp); val dirZ = DoubleArray(nEp)
        val hasDir = BooleanArray(nEp)                            // mean gravity direction, where there is one
        for (i in 0 until nEp) {
            var n = 0; var moves = 0; var sum = 0.0; var mx = 0.0
            var sx = 0.0; var sy = 0.0; var sz = 0.0; var gcount = 0
            for (t in (i * 30) until (i * 30 + 30)) {
                if (gn[t] > 0) { sx += gx[t]; sy += gy[t]; sz += gz[t]; gcount += 1 }
                val j = jerk[t]
                if (j.isNaN()) continue
                n += 1; sum += j; mx = max(mx, j)
                if (j > MOVE_G) moves += 1
            }
            if (n > 0) {
                moveShare[i] = moves.toDouble() / n.toDouble()
                jerkMax[i] = log10(max(mx, JERK_FLOOR_G))
                jerkMean[i] = log10(max(sum / n.toDouble(), JERK_FLOOR_G))
            }
            if (gcount > 0) {
                val norm = sqrt(sx * sx + sy * sy + sz * sz)
                if (norm > 0) { dirX[i] = sx / norm; dirY[i] = sy / norm; dirZ[i] = sz / norm; hasDir[i] = true }
            }
        }
        val tilt = arrayOfNulls<Double>(nEp)                      // degrees turned since the epoch before
        for (i in 1 until nEp) {
            if (!hasDir[i - 1] || !hasDir[i]) continue
            val dot = min(1.0, max(-1.0, dirX[i - 1] * dirX[i] + dirY[i - 1] * dirY[i] + dirZ[i - 1] * dirZ[i]))
            tilt[i] = acos(dot) * 180 / PI
        }

        // ── heart rate on 10 s bins ───────────────────────────────────────────────────────────────
        val nBin = nSec / HR_BIN
        val bSum = DoubleArray(nBin)
        val bCnt = IntArray(nBin)
        for (s in hr) {
            val d = s.ts - lo
            if (d < 0 || d >= nSec) continue
            val b = d.toInt() / HR_BIN
            bSum[b] += s.bpm.toDouble(); bCnt[b] += 1
        }
        val bin = DoubleArray(nBin) { Double.NaN }
        for (b in 0 until nBin) if (bCnt[b] > 0) bin[b] = bSum[b] / bCnt[b].toDouble()

        // The present bins of `[a, b)` seconds relative to `lo`, clamped to the padded range, are bins
        // `binFirst(a) until binLast(b)` that are not NaN; a bin's centre is `k * HR_BIN + HR_BIN / 2` s.
        fun binFirst(a: Int): Int = max(0, a) / HR_BIN
        fun binLast(b: Int): Int = min(nSec, b) / HR_BIN
        fun meanBpm(a: Int, b: Int): Double? {
            var s = 0.0; var n = 0
            for (k in binFirst(a) until binLast(b)) if (!bin[k].isNaN()) { s += bin[k]; n += 1 }
            return if (n == 0) null else s / n.toDouble()
        }
        fun sdBpm(a: Int, b: Int): Double? {
            val first = binFirst(a); val last = binLast(b)
            var s = 0.0; var n = 0
            for (k in first until last) if (!bin[k].isNaN()) { s += bin[k]; n += 1 }
            if (n < 3) return null
            val m = s / n.toDouble()
            var sq = 0.0
            for (k in first until last) if (!bin[k].isNaN()) sq += (bin[k] - m) * (bin[k] - m)
            return sqrt(sq / n.toDouble())
        }
        val epochHR = arrayOfNulls<Double>(nEp)
        for (i in 0 until nEp) epochHR[i] = meanBpm(i * 30, i * 30 + 30)

        // ── beat intervals ────────────────────────────────────────────────────────────────────────
        // Kept in arrival order within a second, as the strap delivers them (a stable sort).
        var beatT = IntArray(0); var beatMs = DoubleArray(0); var nBeat = 0
        if (rr.isNotEmpty()) {
            val sorted = rr.sortedBy { it.ts }
            beatT = IntArray(sorted.size); beatMs = DoubleArray(sorted.size)
            for (r in sorted) {
                val d = r.ts - lo
                if (d < 0 || d >= nSec) continue
                beatT[nBeat] = d.toInt(); beatMs[nBeat] = min(max(r.rrMs.toDouble(), 300.0), 2000.0)
                nBeat += 1
            }
        }
        // First beat at or after second `a`, searched from `from`.
        fun beatLower(a: Int, from: Int): Int {
            var l = from; var h = nBeat
            while (l < h) { val m = (l + h) / 2; if (beatT[m] < a) l = m + 1 else h = m }
            return l
        }
        val respDft = HashMap<Int, SleepStagerV2.RespDft>()

        // ── window epochs: raw values, then night-relative forms ──────────────────────────────────
        val w0 = pad                                              // padded index of the window's first epoch
        val rawHRsd5 = arrayOfNulls<Double>(nWin)
        val rawHRsd11 = arrayOfNulls<Double>(nWin)
        val rawSlope = arrayOfNulls<Double>(nWin)
        val rawRmssd = arrayOfNulls<Double>(nWin)
        val rawSdnn = arrayOfNulls<Double>(nWin)
        val rawResp = arrayOfNulls<Double>(nWin)
        for (k in 0 until nWin) {
            val e = (w0 + k) * 30                                 // epoch start, seconds from `lo`
            rawHRsd5[k] = sdBpm(e - 150, e + 180)
            rawHRsd11[k] = sdBpm(e - 330, e + 390)
            val first = binFirst(e - 150); val last = binLast(e + 180)
            var n = 0; var st = 0.0; var sh = 0.0
            for (b in first until last) if (!bin[b].isNaN()) {
                st += (b * HR_BIN).toDouble() + HR_BIN.toDouble() / 2; sh += bin[b]; n += 1
            }
            if (n >= 6) {
                val mt = st / n.toDouble(); val mh = sh / n.toDouble()
                var sxx = 0.0; var sxy = 0.0
                for (b in first until last) if (!bin[b].isNaN()) {
                    val t = (b * HR_BIN).toDouble() + HR_BIN.toDouble() / 2
                    sxx += (t - mt) * (t - mt)
                }
                if (sxx > 0) {
                    for (b in first until last) if (!bin[b].isNaN()) {
                        val t = (b * HR_BIN).toDouble() + HR_BIN.toDouble() / 2
                        sxy += (t - mt) * (bin[b] - mh)
                    }
                    rawSlope[k] = sxy / sxx * 60
                }
            }
            if (nBeat > 0) {
                val r5a = beatLower(e - 150, 0); val r5b = beatLower(e + 180, r5a)
                val count = r5b - r5a
                if (count >= MIN_BEATS) {
                    var s = 0.0
                    for (i in r5a until r5b) s += beatMs[i]
                    val m = s / count.toDouble()
                    var sq = 0.0
                    for (i in r5a until r5b) sq += (beatMs[i] - m) * (beatMs[i] - m)
                    rawSdnn[k] = sqrt(sq / count.toDouble())
                    // Successive differences only across beats that follow one another: a pair further
                    // apart in time than the later interval is long has a dropped beat between.
                    var dsq = 0.0; var pairs = 0
                    for (i in (r5a + 1) until r5b) {
                        if ((beatT[i] - beatT[i - 1]).toDouble() > beatMs[i] / 1000 + 1) continue
                        val d = beatMs[i] - beatMs[i - 1]
                        dsq += d * d; pairs += 1
                    }
                    if (pairs >= MIN_BEATS / 2) rawRmssd[k] = sqrt(dsq / pairs.toDouble())
                }
                val rsa = beatLower(e - 90, 0); val rsb = beatLower(e + 120, rsa)
                if (rsb - rsa >= 12) {
                    val beats = ArrayList<Pair<Double, Double>>(rsb - rsa)
                    for (i in rsa until rsb) beats.add(Pair(beatT[i].toDouble(), beatMs[i]))
                    rawResp[k] = SleepStagerV2.respRegularity(beats, respDft)
                }
            }
        }
        val rawRange = arrayOfNulls<Double>(nWin)                 // heart-rate range over 90 s, bpm
        for (k in 0 until nWin) {
            var n = 0; var mx = 0.0; var mn = 0.0
            for (b in binFirst((w0 + k) * 30 - 30) until binLast((w0 + k) * 30 + 60)) {
                val v = bin[b]
                if (v.isNaN()) continue
                if (n == 0) { mx = v; mn = v } else { if (v > mx) mx = v; if (v < mn) mn = v }
                n += 1
            }
            if (n >= 3) rawRange[k] = mx - mn
        }
        // A rank, not night-SD units: how wide a 90 s swing reads depends on how hard the device smooths
        // its heart rate.
        val rkRange = rank(rawRange)
        val winHR = Array(nWin) { epochHR[w0 + it] }
        val (hrMean, hrSD) = meanSD(winHR)
        val zSD5 = zscore(rawHRsd5); val rkSD11 = rank(rawHRsd11); val rkHR = rank(winHR)
        val zRmssd = zscore(rawRmssd); val zSdnn = zscore(rawSdnn); val zResp = zscore(rawResp)
        fun hrZ(i: Int): Double? = epochHR[i]?.let { (it - hrMean) / hrSD }   // padded index

        /** Mean of the present values of `v` over padded epochs `[a, b)`. */
        fun meanOver(v: Array<Double?>, a: Int, b: Int): Double? {
            var s = 0.0; var n = 0
            for (i in max(0, a) until min(nEp, b)) { val x = v[i]; if (x != null) { s += x; n += 1 } }
            return if (n == 0) null else s / n.toDouble()
        }
        /** Minutes back (step -1) or ahead (step +1) from padded epoch `i` to the nearest epoch where
         *  `hit` holds, capped. An epoch without a measurement is not evidence either way and is passed over. */
        fun minutesTo(i: Int, step: Int, hit: (Int) -> Boolean?): Double? {
            var j = i; var seen = false
            while (j >= 0 && j < nEp) {
                val h = hit(j)
                if (h != null) {
                    seen = true
                    if (h) return min(CAP_MINUTES, abs(j - i).toDouble() * 0.5)
                }
                if (abs(j - i).toDouble() * 0.5 >= CAP_MINUTES) break
                j += step
            }
            return if (seen) min(CAP_MINUTES, abs(j - i).toDouble() * 0.5) else null
        }

        val span = (end - start).toDouble()
        val out = ArrayList<Row>(nWin)
        for (k in 0 until nWin) {
            val i = w0 + k
            val e = lo + i.toLong() * 30
            val centre = (e + 15 - start).toDouble()
            val v = ArrayList<Double?>(names.size)
            v.add(centre / 60)
            v.add(centre / span)
            v.add((span - centre) / 60)

            v.add(moveShare[i])
            v.add(jerkMax[i])
            v.add(jerkMean[i])
            v.add(meanOver(moveShare, i - 10, i))
            v.add(meanOver(moveShare, i + 1, i + 11))
            v.add(meanOver(moveShare, i - 60, i))
            v.add(meanOver(moveShare, i + 1, i + 61))
            v.add(minutesTo(i, -1) { j -> moveShare[j]?.let { it > 0 } })
            v.add(minutesTo(i, 1) { j -> moveShare[j]?.let { it > 0 } })
            v.add(tilt[i])
            v.add(minutesTo(i, -1) { j -> tilt[j]?.let { it > POSTURE_DEGREES } })

            v.add(hrZ(i))
            v.add(rkHR[k])
            v.add(zSD5[k])
            v.add(rkSD11[k])
            v.add(rawSlope[k]?.let { it / hrSD })
            val before = meanBpm(i * 30 - 300, i * 30); val after = meanBpm(i * 30 + 30, i * 30 + 330)
            val h = epochHR[i]
            v.add(if (h != null && before != null) (h - before) / hrSD else null)
            v.add(if (h != null && after != null) (after - h) / hrSD else null)
            v.add(rkRange[k])
            var zs = 0.0; var zn = 0
            for (j in max(0, i - 30)..min(nEp - 1, i + 30)) { val z = hrZ(j); if (z != null) { zs += z; zn += 1 } }
            v.add(if (zn == 0) null else zs / zn.toDouble())

            v.add(zRmssd[k])
            v.add(zSdnn[k])
            v.add(zResp[k])
            out.add(Row(start = e, values = v))
        }
        return out
    }

    // ── Night-relative scales ─────────────────────────────────────────────────────────────────────

    /** Mean and population standard deviation of the present values; (0, 1) when there are none, and a
     *  zero deviation becomes 1 so a flat channel reads as neutral rather than dividing by nothing. */
    internal fun meanSD(v: Array<Double?>): Pair<Double, Double> {
        var s = 0.0; var n = 0
        for (x in v) if (x != null) { s += x; n += 1 }
        if (n == 0) return Pair(0.0, 1.0)
        val m = s / n.toDouble()
        var sq = 0.0
        for (x in v) if (x != null) sq += (x - m) * (x - m)
        val sd = sqrt(sq / n.toDouble())
        return Pair(m, if (sd == 0.0) 1.0 else sd)
    }

    internal fun zscore(v: Array<Double?>): Array<Double?> {
        val (m, sd) = meanSD(v)
        return Array(v.size) { i -> v[i]?.let { (it - m) / sd } }
    }

    /** Swift's `rounded()`: to the nearest whole number, a half going away from zero. */
    private fun roundedAwayFromZero(x: Double): Double {
        val t = if (x < 0) ceil(x) else floor(x)
        return if (abs(x - t) >= 0.5) t + (if (x < 0) -1.0 else 1.0) else t
    }

    /** Rank within the night as a fraction in (0, 1]: the share of present values at or below this one.
     *  Values are compared to nine decimals: a statistic of whole-bpm samples ties often, and two values
     *  that are the same number must not be ranked apart by the last bit of how each was summed. */
    internal fun rank(v: Array<Double?>): Array<Double?> {
        fun key(x: Double): Double = roundedAwayFromZero(x * 1e9)
        var n = 0
        for (x in v) if (x != null) n += 1
        if (n == 0) return arrayOfNulls(v.size)
        val s = DoubleArray(n)
        var at = 0
        for (x in v) if (x != null) s[at++] = key(x)
        s.sort()
        return Array(v.size) { i ->
            val raw = v[i] ?: return@Array null
            val x = key(raw)
            var lo = 0; var hi = n
            while (lo < hi) { val m = (lo + hi) / 2; if (s[m] <= x) lo = m + 1 else hi = m }
            lo.toDouble() / n.toDouble()
        }
    }
}

// ── Surroundings ──────────────────────────────────────────────────────────────────────────────────

/**
 * What surrounds an epoch, for a model that is shown one row at a time. Kotlin twin of Swift
 * `SleepStageContext`.
 *
 *  * [surroundings]: for the features that carry the stage, their mean over the ten, thirty and ninety
 *    minutes centred on the epoch, over the ten minutes before it and the ten after, their value one
 *    and three minutes either side, and how far the epoch stands from its ninety-minute mean.
 *  * [neighbours]: a first model's class probabilities around the epoch, for a second model that takes
 *    them beside everything the first one saw.
 */
object SleepStageContext {

    /** The features whose surroundings are summarised, by their name in [SleepStageFeatures.names]. */
    internal val sources = listOf(
        "move_share", "jerk_mean", "jerk_max", "still_minutes", "posture_minutes", "tilt_change",
        "hr_z", "hr_rank", "hr_sd_5m_z", "hr_sd_11m_rank", "hr_slope_5m", "hr_range_rank",
        "hr_z_30m", "rr_rmssd_5m_z", "rr_sdnn_5m_z", "rr_resp_reg_z")
    private val around = intArrayOf(21, 61, 181)          // centred windows, in epochs: 10, 30 and 90 minutes
    private const val BESIDE = 20                         // epochs before the epoch, and after it: 10 minutes
    private val steps = intArrayOf(2, 6)                  // single epochs this far back and ahead: 1 and 3 minutes

    private val neighbourSteps = intArrayOf(1, 2, 4, 8)
    private val neighbourAround = intArrayOf(5, 11, 21, 41, 81)

    /** Column names of [surroundings], in its order. */
    val names: List<String> = sources.flatMap { s ->
        around.map { "${s}_around_$it" } + listOf("${s}_before_$BESIDE", "${s}_after_$BESIDE") +
            steps.flatMap { listOf("${s}_back_$it", "${s}_ahead_$it") } + listOf("${s}_vs_${around[around.size - 1]}")
    }

    /** Column names of [neighbours], in its order. */
    val neighbourNames: List<String> = SleepStageDecoder.stages.flatMap { s ->
        neighbourSteps.map { "p_${s}_back_$it" } + neighbourSteps.map { "p_${s}_ahead_$it" } +
            neighbourAround.map { "p_${s}_around_$it" } + listOf("p_${s}_so_far", "p_${s}_to_come")
    }

    /** What the first model takes, and what the second one does. */
    val firstNames: List<String> = SleepStageFeatures.names + names
    val secondNames: List<String> = firstNames + neighbourNames

    /** A night as the first model takes it: each row's features, then its surroundings, one number per
     *  name in [firstNames], [SleepStageFeatures.MISSING] where a value could not be measured. */
    fun firstInput(rows: List<SleepStageFeatures.Row>): List<DoubleArray> = firstInputOfValues(rows.map { it.values })

    /** [firstInput] over bare feature rows. */
    fun firstInputOfValues(values: List<List<Double?>>): List<DoubleArray> {
        val around = surroundingsOfValues(values)
        return values.indices.map { i ->
            val a = values[i]; val b = around[i]
            DoubleArray(a.size + b.size) { j -> (if (j < a.size) a[j] else b[j - a.size]) ?: SleepStageFeatures.MISSING }
        }
    }

    /** A night as the second model takes it: what the first model was given, then that model's
     *  probabilities around each epoch, one number per name in [secondNames]. */
    fun secondInput(first: List<DoubleArray>, probabilities: List<DoubleArray>): List<DoubleArray> {
        val near = neighbours(probabilities)
        return List(min(first.size, near.size)) { i ->
            val a = first[i]; val b = near[i]
            DoubleArray(a.size + b.size) { j -> if (j < a.size) a[j] else (b[j - a.size] ?: SleepStageFeatures.MISSING) }
        }
    }

    /** Running sums over the present values of a column, so a window's mean costs two subtractions. */
    private class Sums(v: List<Double?>) {
        private val total = DoubleArray(v.size + 1)
        private val count = IntArray(v.size + 1)

        init {
            for (i in v.indices) {
                val x = v[i]
                total[i + 1] = total[i] + (x ?: 0.0)
                count[i + 1] = count[i] + (if (x == null) 0 else 1)
            }
        }

        /** Mean of the present values at `lo until hi`, clipped to the column; null when there are none. */
        fun mean(lo: Int, hi: Int): Double? {
            val a = max(0, lo); val b = min(count.size - 1, hi)
            if (b <= a) return null
            val n = count[b] - count[a]
            return if (n == 0) null else (total[b] - total[a]) / n.toDouble()
        }
    }

    /** One row of [names] per row of a night, in order. The rows are one night's, as
     *  [SleepStageFeatures.rows] returns them: a window never reaches into another night. */
    fun surroundings(rows: List<SleepStageFeatures.Row>): List<List<Double?>> = surroundingsOfValues(rows.map { it.values })

    /** [surroundings] over bare feature rows, for a caller that holds a table and not `Row`s. */
    fun surroundingsOfValues(values: List<List<Double?>>): List<List<Double?>> {
        val n = values.size
        val out = List(n) { ArrayList<Double?>(names.size) }
        val widest = around[around.size - 1]
        for (s in sources) {
            val c = SleepStageFeatures.names.indexOf(s)
            val x = values.map { it[c] }
            val sums = Sums(x)
            for (i in 0 until n) {
                for (w in around) out[i].add(sums.mean(i - w / 2, i + w / 2 + 1))
                out[i].add(sums.mean(i - BESIDE, i))
                out[i].add(sums.mean(i + 1, i + 1 + BESIDE))
                for (d in steps) {
                    out[i].add(if (i - d >= 0) x[i - d] else null)
                    out[i].add(if (i + d < n) x[i + d] else null)
                }
                val wide = sums.mean(i - widest / 2, i + widest / 2 + 1)
                val v = x[i]
                out[i].add(if (v != null && wide != null) v - wide else null)
            }
        }
        return out
    }

    /** One row of [neighbourNames] per epoch. `probabilities[i]` is a first model's answer for epoch i in
     *  [SleepStageDecoder.stages] order, over one night. A value is null only where there is no epoch to
     *  read: before the first, after the last. */
    fun neighbours(probabilities: List<DoubleArray>): List<List<Double?>> {
        val n = probabilities.size
        val out = List(n) { ArrayList<Double?>(neighbourNames.size) }
        for (c in SleepStageDecoder.stages.indices) {
            val x: List<Double?> = probabilities.map { it[c] }
            val sums = Sums(x)
            for (i in 0 until n) {
                for (d in neighbourSteps) out[i].add(if (i - d >= 0) x[i - d] else null)
                for (d in neighbourSteps) out[i].add(if (i + d < n) x[i + d] else null)
                for (w in neighbourAround) out[i].add(sums.mean(i - w / 2, i + w / 2 + 1))
                out[i].add(sums.mean(0, i))
                out[i].add(sums.mean(i + 1, n))
            }
        }
        return out
    }
}

// ── Decoding ──────────────────────────────────────────────────────────────────────────────────────

/**
 * From per-epoch class probabilities to a hypnogram. Kotlin twin of Swift `SleepStageDecoder`. The
 * learned model replaces [SleepStagerV2]'s hand-set emissions; the smoothing stays the one V2 uses, a
 * Viterbi path under its sticky transition matrix.
 *
 * Two settings travel with a model (in its metadata): `weight`, how much of the class prior is divided
 * out, and `smoothing`, the power the transition matrix is raised to. The Swift file says what each was
 * fit to.
 */
object SleepStageDecoder {

    /** The model's classes, in the order of a probability row. */
    val stages: List<String> = listOf("wake", "light", "deep", "rem")

    /** A probability this small or smaller is treated as this small: a class the model rules out stays
     *  reachable through a run of epochs that all point to it, as in V2's own lattice. */
    private const val FLOOR = 1e-6

    /**
     * The most likely stage path. `probabilities[i]` is epoch i's probabilities in [stages] order; rows
     * follow one another on the 30 s grid. [prior] is each stage's share of the epochs the model was
     * trained on, in the same order. With [smoothing] 1 this is [SleepStagerV2.viterbi] exactly, ties
     * included: the lattice below walks V2's stages in V2's order under V2's matrix and floor.
     */
    fun decode(
        probabilities: List<DoubleArray>, prior: List<Double>? = null, weight: Double = 1.0,
        smoothing: Double = 1.0,
    ): List<String> {
        if (probabilities.isEmpty()) return emptyList()
        val order = SleepStagerV2.stageNames                       // V2's order decides ties
        val column = IntArray(order.size) { stages.indexOf(if (order[it] == "awake") "wake" else order[it]) }
        val p0 = (prior ?: listOf(1.0, 1.0, 1.0, 1.0)).map { it.pow(weight) }
        val k = order.size
        val logT = Array(k) { from ->
            DoubleArray(k) { to -> smoothing * ln(max(SleepStagerV2.transition[order[from]]!![order[to]]!!, 1e-9)) }
        }
        fun emission(p: DoubleArray): DoubleArray = DoubleArray(k) { ln(max(p[column[it]], FLOOR) / p0[column[it]]) }
        var score = emission(probabilities[0])                     // uniform start
        val back = ArrayList<IntArray>(probabilities.size)
        for (t in 1 until probabilities.size) {
            val e = emission(probabilities[t])
            val next = DoubleArray(k); val from = IntArray(k)
            for (j in 0 until k) {
                var best = 0; var bestScore = score[0] + logT[0][j]
                for (i in 1 until k) if (score[i] + logT[i][j] > bestScore) { best = i; bestScore = score[i] + logT[i][j] }
                next[j] = bestScore + e[j]
                from[j] = best
            }
            score = next
            back.add(from)
        }
        var last = 0
        for (j in 1 until k) if (score[j] > score[last]) last = j
        val path = ArrayList<Int>(probabilities.size)
        path.add(last)
        for (t in back.indices.reversed()) { last = back[t][last]; path.add(last) }
        return path.asReversed().map { stages[column[it]] }
    }
}
