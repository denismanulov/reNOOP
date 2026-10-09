package com.noop.friends

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlin.math.roundToInt

// MARK: - Friends: the day a phone uploads
//
// The pure half of the upload: figures in, the README's "day" object out. The figures are read elsewhere
// (FriendsDaySource) through the same resolvers the Summary, Sleep and Workouts tabs use; nothing is
// derived here beyond rounding to the wire's whole numbers and thinning the heart-rate line. Three rules
// hold for every member:
//  - a figure the app does not have is left out, never sent as 0;
//  - a figure outside the range the server accepts is left out too, since one refused member would cost
//    the whole day (the server answers such a day with a 400);
//  - a section whose sharing switch is off is not built at all.

/** The main sleep of a day as the app holds it: unix seconds and fractional minutes. */
data class FriendsSleepInput(
    val startTs: Long,
    val endTs: Long,
    val asleepMin: Double,
    val awakeMin: Double? = null,
    val remMin: Double? = null,
    val lightMin: Double? = null,
    val deepMin: Double? = null,
    val needMin: Double? = null,
)

/** One workout as the Workouts tab lists it. */
data class FriendsWorkoutInput(
    val startTs: Long,
    /** The app's English sport label. */
    val sport: String,
    val durationS: Double,
    /** Stored 0–100 axis. */
    val strain: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val kcal: Double? = null,
)

/** Everything the app holds for one day, before the sharing switches and the wire's limits apply. */
data class FriendsDayInputs(
    /** The day's own Recovery, 0–100. Null while the day is unscored (a carried prior night is not this day's). */
    val recovery: Double? = null,
    /** Day Strain on the STORED 0–100 axis, never the display scale. */
    val strain: Double? = null,
    /** The day's own Rest / sleep-performance score, 0–100. */
    val sleepScore: Double? = null,
    val sleep: FriendsSleepInput? = null,
    /** The day's workouts, or null when they were not read (which is not the same as a day with none). */
    val workouts: List<FriendsWorkoutInput>? = null,
    /** The day's heart-rate samples, any order. */
    val hrSamples: List<FriendHrPoint> = emptyList(),
    val restingBpm: Int? = null,
)

/** The day as it goes on the wire. */
data class FriendsDayBody(
    val recovery: Int? = null,
    val strain: Double? = null,
    val sleepScore: Int? = null,
    val sleep: FriendSleep? = null,
    val workouts: List<FriendWorkout>? = null,
    val hr: FriendHr? = null,
)

object FriendsDayPayload {
    /** The longest heart-rate line the server stores for one day. */
    const val MAX_HR_POINTS = 300
    const val MAX_WORKOUTS = 20
    const val MAX_SPORT_CHARS = 40

    /** The server refuses a timestamp before this (September 2017) or more than two days ahead of its clock. */
    private const val MIN_TS = 1_500_000_000L
    private const val TS_AHEAD_S = 2 * 86_400L
    private val BPM = 20..250
    private val MINUTES = 0..1_440
    private val KCAL = 0..20_000
    private const val MAX_DURATION_S = 86_400

    /** The day to upload: [inputs] through the sharing switches [share], as of [nowTs] (unix seconds). */
    fun build(inputs: FriendsDayInputs, share: FriendShare, nowTs: Long): FriendsDayBody {
        val maxTs = nowTs + TS_AHEAD_S
        fun ts(value: Long): Long? = value.takeIf { it in MIN_TS..maxTs }
        return FriendsDayBody(
            recovery = if (share.scores) score(inputs.recovery) else null,
            strain = if (share.scores) strain(inputs.strain) else null,
            sleepScore = if (share.scores) score(inputs.sleepScore) else null,
            sleep = if (share.sleep) inputs.sleep?.let { sleep(it, ::ts) } else null,
            workouts = if (share.workouts) inputs.workouts?.let { workouts(it, ::ts) } else null,
            hr = if (share.hr) hr(inputs.hrSamples, inputs.restingBpm, ::ts) else null,
        )
    }

    private fun score(value: Double?): Int? =
        value?.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it in 0..100 }

    /** Two decimals, as the server stores it. */
    private fun strain(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).toDouble() }
            ?.takeIf { it <= 100.0 }

    private fun minutes(value: Double?): Int? =
        value?.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it in MINUTES }

    private fun sleep(input: FriendsSleepInput, ts: (Long) -> Long?): FriendSleep? {
        val start = ts(input.startTs) ?: return null
        val end = ts(input.endTs) ?: return null
        val asleep = minutes(input.asleepMin) ?: return null
        if (end <= start) return null
        return FriendSleep(
            startTs = start, endTs = end, asleepMin = asleep,
            awakeMin = minutes(input.awakeMin), remMin = minutes(input.remMin),
            lightMin = minutes(input.lightMin), deepMin = minutes(input.deepMin), needMin = minutes(input.needMin),
        )
    }

    /** Oldest first; when a day somehow holds more than the server keeps, the most recent ones stay. */
    private fun workouts(inputs: List<FriendsWorkoutInput>, ts: (Long) -> Long?): List<FriendWorkout> =
        inputs.sortedBy { it.startTs }.mapNotNull { w ->
            val start = ts(w.startTs) ?: return@mapNotNull null
            val sport = sportLabel(w.sport) ?: return@mapNotNull null
            val duration = w.durationS.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it in 0..MAX_DURATION_S }
                ?: return@mapNotNull null
            FriendWorkout(
                startTs = start, sport = sport, durationS = duration,
                strain = strain(w.strain),
                avgHr = w.avgHr?.takeIf { it in BPM },
                maxHr = w.maxHr?.takeIf { it in BPM },
                kcal = w.kcal?.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it in KCAL },
            )
        }.takeLast(MAX_WORKOUTS)

    /** A sport label the server will store: printable, trimmed, at most 40 characters; null when nothing is left. */
    internal fun sportLabel(raw: String): String? {
        val out = StringBuilder()
        var count = 0
        var i = 0
        val text = raw.trim()
        while (i < text.length && count < MAX_SPORT_CHARS) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!printable(cp)) continue
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun printable(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
        Character.SPACE_SEPARATOR -> cp == ' '.code
        else -> true
    }

    private fun hr(samples: List<FriendHrPoint>, restingBpm: Int?, ts: (Long) -> Long?): FriendHr? {
        val valid = samples.asSequence()
            .filter { it.bpm in BPM && ts(it.ts) != null }
            .sortedBy { it.ts }
            .distinctBy { it.ts }
            .toList()
        val last = valid.lastOrNull() ?: return null
        return FriendHr(
            lastBpm = last.bpm,
            lastTs = last.ts,
            restingBpm = restingBpm?.takeIf { it in BPM },
            series = downsample(valid),
        )
    }

    /**
     * [samples] (oldest first, one per timestamp) thinned to at most [maxPoints]. A short day is sent as
     * it is. A longer one is cut into equal stretches of time, each drawn as the mean of its samples at
     * their mean time, and the line always ends on the newest sample itself, so its last point and the
     * "latest heart rate" beside it are one reading and cannot disagree.
     */
    fun downsample(samples: List<FriendHrPoint>, maxPoints: Int = MAX_HR_POINTS): List<FriendHrPoint> {
        if (samples.size <= maxPoints) return samples
        if (maxPoints <= 1) return listOf(samples.last())
        val last = samples.last()
        val body = samples.subList(0, samples.size - 1)
        val buckets = maxPoints - 1
        val first = body.first().ts
        val span = (last.ts - first).coerceAtLeast(1L)
        val sumTs = LongArray(buckets)
        val sumBpm = LongArray(buckets)
        val count = IntArray(buckets)
        for (s in body) {
            val index = ((s.ts - first) * buckets / span).toInt().coerceIn(0, buckets - 1)
            sumTs[index] += s.ts - first
            sumBpm[index] += s.bpm.toLong()
            count[index]++
        }
        val out = ArrayList<FriendHrPoint>(maxPoints)
        for (i in 0 until buckets) {
            val n = count[i]
            if (n == 0) continue
            out += FriendHrPoint(first + sumTs[i] / n, (sumBpm[i].toDouble() / n).roundToInt())
        }
        out += last
        return out
    }

    // MARK: The wire text
    //
    // Written by hand rather than through a JSON library: the upload is skipped when this text is the
    // same as the last one the server accepted for the day, so it must come out byte for byte the same
    // for the same figures, on the phone and in a JVM test alike. Members are in the README's order.

    fun toJson(body: FriendsDayBody): String {
        val members = ArrayList<String>(6)
        body.recovery?.let { members += "\"recovery\":$it" }
        body.strain?.let { members += "\"strain\":${number(it)}" }
        body.sleepScore?.let { members += "\"sleepScore\":$it" }
        body.sleep?.let { s ->
            val parts = ArrayList<String>(8)
            parts += "\"startTs\":${s.startTs}"
            parts += "\"endTs\":${s.endTs}"
            parts += "\"asleepMin\":${s.asleepMin}"
            s.awakeMin?.let { parts += "\"awakeMin\":$it" }
            s.remMin?.let { parts += "\"remMin\":$it" }
            s.lightMin?.let { parts += "\"lightMin\":$it" }
            s.deepMin?.let { parts += "\"deepMin\":$it" }
            s.needMin?.let { parts += "\"needMin\":$it" }
            members += "\"sleep\":{${parts.joinToString(",")}}"
        }
        body.workouts?.let { list ->
            val items = list.map { w ->
                val parts = ArrayList<String>(7)
                parts += "\"startTs\":${w.startTs}"
                parts += "\"sport\":${quote(w.sport)}"
                parts += "\"durationS\":${w.durationS}"
                w.strain?.let { parts += "\"strain\":${number(it)}" }
                w.avgHr?.let { parts += "\"avgHr\":$it" }
                w.maxHr?.let { parts += "\"maxHr\":$it" }
                w.kcal?.let { parts += "\"kcal\":$it" }
                "{${parts.joinToString(",")}}"
            }
            members += "\"workouts\":[${items.joinToString(",")}]"
        }
        body.hr?.let { h ->
            val parts = ArrayList<String>(4)
            parts += "\"lastBpm\":${h.lastBpm}"
            parts += "\"lastTs\":${h.lastTs}"
            h.restingBpm?.let { parts += "\"restingBpm\":$it" }
            parts += "\"series\":[${h.series.joinToString(",") { "[${it.ts},${it.bpm}]" }}]"
            members += "\"hr\":{${parts.joinToString(",")}}"
        }
        return "{${members.joinToString(",")}}"
    }

    /** A decimal without an exponent or a trailing zero: 38.6, 38, 0.05. */
    private fun number(value: Double): String {
        val plain = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        return (if (plain.scale() < 0) plain.setScale(0) else plain).toPlainString()
    }

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (ch in text) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch < ' ' -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    /** SHA-256 of [json] as lower-case hex: what the phone keeps to tell an unchanged day from a changed one. */
    fun fingerprint(json: String): String =
        MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** The one rule of the upload skip: a day goes up unless its text is the one the server last accepted for it. */
object FriendsUploadPolicy {
    fun shouldUpload(lastAcceptedFingerprint: String?, json: String): Boolean =
        lastAcceptedFingerprint == null || lastAcceptedFingerprint != FriendsDayPayload.fingerprint(json)
}
