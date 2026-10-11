package com.noop.ui.friends

import com.noop.friends.FriendDay
import com.noop.friends.FriendPerson
import com.noop.friends.FriendProfile
import com.noop.friends.FriendShare
import com.noop.friends.FriendSleep
import com.noop.friends.FriendWorkout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.noop.friends.FriendsFeed
import com.noop.friends.FriendsInviteCode
import com.noop.ui.sleep.SleepScore
import com.noop.ui.sleep.SleepScoreWord
import com.noop.ui.workouts.effortLoadIndex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Friends tab: what the screens decide without a screen
//
// Everything the tab works out from a feed: how long ago something was, which of two people leads on a
// score, the events worth a line, the week side by side, the order of the list, a score's level. No
// Android and no Compose, so the JVM tests pin each rule; the screens only turn the results into words.
//
// One clock runs through all of it: the server's. A day's `updatedAt` and the feed's `serverTime` are
// both the server's seconds, so "6 min ago" is their difference carried forward by how long ago the feed
// arrived, and a phone whose own clock is wrong still reads the right age.

/** Which side of a comparison something belongs to. */
internal enum class FriendsSide { ME, FRIEND }

/** How long ago something happened, in the one unit worth saying. */
internal sealed class FriendsAgo {
    object JustNow : FriendsAgo()
    data class Minutes(val count: Long) : FriendsAgo()
    data class Hours(val count: Long) : FriendsAgo()
    data class Days(val count: Long) : FriendsAgo()

    companion object {
        /**
         * The age of [atSec] at [nowSec] (both unix seconds on the same clock). Under a minute is "just
         * now", and so is a stamp from the future (two clocks a few seconds apart). Null without a stamp.
         */
        fun of(atSec: Long?, nowSec: Long): FriendsAgo? {
            val at = atSec?.takeIf { it > 0L } ?: return null
            val secs = (nowSec - at).coerceAtLeast(0L)
            return when {
                secs < 60 -> JustNow
                secs < 3_600 -> Minutes(secs / 60)
                secs < 86_400 -> Hours(secs / 3_600)
                else -> Days(secs / 86_400)
            }
        }
    }
}

// MARK: Levels

/** The Strain word's index (0 light … 4 all-out) for a stored 0–100 value: the Workouts tab's own rule. */
internal fun friendsStrainLevel(strain: Double): Int = effortLoadIndex(strain / 100.0)

/** The Sleep score's word: the Sleep tab's own banding. */
internal fun friendsSleepLevel(score: Int): SleepScoreWord = SleepScore.word(score)

// MARK: Comparison

/** One sentence of the head-to-head card. */
internal sealed class FriendsComparisonLine {
    /** [side] has the higher Recovery today, by [points]. */
    data class RecoveryLead(val side: FriendsSide, val points: Int) : FriendsComparisonLine()

    /** The two Recovery scores are within [FriendsComparison.RECOVERY_LEVEL_WITHIN] of each other. */
    object RecoveryLevel : FriendsComparisonLine()

    /** [side] has clearly more Strain today. */
    data class StrainLead(val side: FriendsSide) : FriendsComparisonLine()

    /** [side] has the higher Sleep score, by [points]. */
    data class SleepLead(val side: FriendsSide, val points: Int) : FriendsComparisonLine()

    /** No score is known for both sides. */
    object NothingShared : FriendsComparisonLine()
}

internal object FriendsComparison {
    /** Recovery scores this close read as level: the difference is inside the score's own noise. */
    const val RECOVERY_LEVEL_WITHIN = 2

    /** Strain must differ by this much (stored 0–100 axis, about one point of 21) to name a leader. */
    const val STRAIN_MIN_GAP = 5.0

    /** A Sleep score lead smaller than this is not worth a sentence. */
    const val SLEEP_MIN_GAP = 3

    /**
     * At most two sentences comparing today's [me] and [friend]: Recovery first when both have it, then
     * Strain when it clearly differs, then the Sleep score while there is room. A score only one side has
     * is never compared, and with nothing in common the answer is the single
     * [FriendsComparisonLine.NothingShared].
     */
    fun lines(me: FriendDay?, friend: FriendDay?): List<FriendsComparisonLine> {
        val out = ArrayList<FriendsComparisonLine>(2)
        val myRecovery = me?.recovery
        val theirRecovery = friend?.recovery
        if (myRecovery != null && theirRecovery != null) {
            val diff = theirRecovery - myRecovery
            out += if (abs(diff) <= RECOVERY_LEVEL_WITHIN) {
                FriendsComparisonLine.RecoveryLevel
            } else {
                FriendsComparisonLine.RecoveryLead(if (diff > 0) FriendsSide.FRIEND else FriendsSide.ME, abs(diff))
            }
        }
        val myStrain = me?.strain
        val theirStrain = friend?.strain
        if (myStrain != null && theirStrain != null && abs(theirStrain - myStrain) >= STRAIN_MIN_GAP) {
            out += FriendsComparisonLine.StrainLead(if (theirStrain > myStrain) FriendsSide.FRIEND else FriendsSide.ME)
        }
        val mySleep = me?.sleepScore
        val theirSleep = friend?.sleepScore
        if (out.size < 2 && mySleep != null && theirSleep != null && abs(theirSleep - mySleep) >= SLEEP_MIN_GAP) {
            out += FriendsComparisonLine.SleepLead(
                if (theirSleep > mySleep) FriendsSide.FRIEND else FriendsSide.ME, abs(theirSleep - mySleep),
            )
        }
        return if (out.isEmpty()) listOf(FriendsComparisonLine.NothingShared) else out.take(2)
    }
}

// MARK: Events

/** One line of the feed: something a person's day says happened. */
internal sealed class FriendsEvent {
    abstract val who: FriendProfile
    abstract val isMe: Boolean

    /** When it happened (unix seconds): the wake time, or the moment a workout ended. */
    abstract val ts: Long

    data class Woke(
        override val who: FriendProfile,
        override val isMe: Boolean,
        override val ts: Long,
        val asleepMin: Int,
        val sleepScore: Int?,
    ) : FriendsEvent()

    data class Workout(
        override val who: FriendProfile,
        override val isMe: Boolean,
        override val ts: Long,
        val workout: FriendWorkout,
    ) : FriendsEvent()
}

internal object FriendsEvents {
    const val DEFAULT_LIMIT = 8

    /** A stamp this far ahead of the server's clock is another clock's error, not an event. */
    private const val FUTURE_SLACK_S = 300L

    /**
     * The newest events of [people] (each with whether it is the signed-in account), newest first: one
     * per night slept and one per workout finished, read off the days the server holds. Nothing is
     * inferred: a person who shares neither sleep nor workouts contributes no line.
     */
    fun derive(people: List<Pair<FriendPerson, Boolean>>, nowSec: Long, limit: Int = DEFAULT_LIMIT): List<FriendsEvent> {
        val out = ArrayList<FriendsEvent>()
        for ((person, isMe) in people) {
            for (day in person.days) {
                day.sleep?.let { s ->
                    out += FriendsEvent.Woke(person.profile, isMe, s.endTs, s.asleepMin, day.sleepScore)
                }
                day.workouts.orEmpty().forEach { w ->
                    out += FriendsEvent.Workout(person.profile, isMe, w.startTs + w.durationS, w)
                }
            }
        }
        return out.asSequence()
            .filter { it.ts <= nowSec + FUTURE_SLACK_S }
            .sortedWith(compareByDescending<FriendsEvent> { it.ts }.thenBy { it.who.id })
            .take(limit)
            .toList()
    }
}

// MARK: The week

/** Seven days of Recovery for two people, oldest first, a null where a day has none. */
internal data class FriendsWeek(
    val days: List<LocalDate>,
    val mine: List<Int?>,
    val theirs: List<Int?>,
) {
    /** The mean of the days that have a score, rounded; null when none does. */
    val myAverage: Int? get() = average(mine)
    val theirAverage: Int? get() = average(theirs)

    /** True when neither person has a single score in the week: there is nothing to draw. */
    val isEmpty: Boolean get() = mine.all { it == null } && theirs.all { it == null }

    companion object {
        const val DAYS = 7

        /** The week ending on [todayKey] ("yyyy-MM-dd"); null when the key is not a date. */
        fun of(todayKey: String, me: FriendPerson?, friend: FriendPerson?): FriendsWeek? {
            val today = runCatching { LocalDate.parse(todayKey) }.getOrNull() ?: return null
            val days = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
            fun scores(person: FriendPerson?) = days.map { d -> person?.day(d.toString())?.recovery }
            return FriendsWeek(days, scores(me), scores(friend))
        }

        private fun average(values: List<Int?>): Int? =
            values.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    }
}

// MARK: The list

internal enum class FriendsSort { RECOVERY, STRAIN, SLEEP }

/** One card of the list: a person and the day being compared (null when they uploaded none). */
internal data class FriendsListEntry(val person: FriendPerson, val isMe: Boolean, val day: FriendDay?)

internal object FriendsList {
    /**
     * The signed-in account and every friend for the day [dayKey], highest [sort] first. People without
     * that figure follow in name order, so a friend who does not share scores is listed, never dropped.
     */
    fun entries(feed: FriendsFeed, sort: FriendsSort, dayKey: String): List<FriendsListEntry> {
        val all = listOf(FriendsListEntry(feed.me, true, feed.me.day(dayKey))) +
            feed.friends.map { FriendsListEntry(it, false, it.day(dayKey)) }
        fun value(entry: FriendsListEntry): Double? = when (sort) {
            FriendsSort.RECOVERY -> entry.day?.recovery?.toDouble()
            FriendsSort.STRAIN -> entry.day?.strain
            FriendsSort.SLEEP -> entry.day?.sleepScore?.toDouble()
        }
        return all.sortedWith(
            compareBy<FriendsListEntry> { value(it) == null }
                .thenByDescending { value(it) ?: 0.0 }
                .thenBy { it.person.profile.name.lowercase() }
                .thenBy { it.person.id },
        )
    }
}

// MARK: Sleep against its need

/** How a night's time asleep stands against the need it was read against. */
internal sealed class FriendsSleepGoal {
    abstract val needMin: Int

    /** [byMin] short of the need. */
    data class Short(val byMin: Int, override val needMin: Int) : FriendsSleepGoal()

    /** Under the need by less than [FriendsSleepGoal.ALMOST_WITHIN_MIN]. */
    data class Almost(override val needMin: Int) : FriendsSleepGoal()
    data class Met(override val needMin: Int) : FriendsSleepGoal()

    companion object {
        const val ALMOST_WITHIN_MIN = 30

        /** Null when the night carries no need, in which case nothing is said about one. */
        fun of(sleep: FriendSleep): FriendsSleepGoal? {
            val need = sleep.needMin?.takeIf { it > 0 } ?: return null
            val short = need - sleep.asleepMin
            return when {
                short <= 0 -> Met(need)
                short < ALMOST_WITHIN_MIN -> Almost(need)
                else -> Short(short, need)
            }
        }

        /** The share of the need slept, 0…1, for the bar; null without a need. */
        fun fraction(sleep: FriendSleep): Float? {
            val need = sleep.needMin?.takeIf { it > 0 } ?: return null
            return (sleep.asleepMin.toFloat() / need).coerceIn(0f, 1f)
        }
    }
}

// MARK: What a person shares

/** The four sections in the order the tab names them. */
internal enum class FriendsSection { SCORES, SLEEP, WORKOUTS, HEART_RATE }

internal fun FriendShare.has(section: FriendsSection): Boolean = when (section) {
    FriendsSection.SCORES -> scores
    FriendsSection.SLEEP -> sleep
    FriendsSection.WORKOUTS -> workouts
    FriendsSection.HEART_RATE -> hr
}

/** The sections [share] has on, and the ones it has off, each in the tab's order. */
internal fun FriendShare.sections(): Pair<List<FriendsSection>, List<FriendsSection>> =
    FriendsSection.entries.partition { has(it) }

/** The first letter of a name for an avatar without a picture; "?" for a name with no letter or digit. */
internal fun friendsInitial(name: String): String {
    val trimmed = name.trim()
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp)).uppercase()
        i += Character.charCount(cp)
    }
    return "?"
}

// MARK: Turning on, and an invite

/** "481 902": six digits in two groups, as codes are read out. Anything else is left alone. */
internal fun spacedCode(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

/**
 * "15 October 2026, 22:53" in the reader's language. The date, not the weekday: the moments shown are
 * days away, and a seven-day wait ends on the weekday it began on.
 */
internal fun friendsMoment(ts: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(locale)
        .format(Instant.ofEpochSecond(ts).atZone(zone).toLocalDateTime())

/** A QR code as its grid of modules. Pure, so the drawing is tested without a screen. */
internal class FriendsQrCode private constructor(val side: Int, private val dark: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = dark[y * side + x]

    companion object {
        /** The code for [text] with no quiet zone of its own, or null when the text cannot be encoded. */
        fun of(text: String): FriendsQrCode? = runCatching {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
            // Asked for at size 0, the writer answers one cell to a module.
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val side = matrix.width
            if (side <= 0 || matrix.height != side) return@runCatching null
            FriendsQrCode(side, BooleanArray(side * side) { matrix.get(it % side, it / side) })
        }.getOrNull()
    }
}

/**
 * The invite code a link into the app carries (`renoop://friends/add?c=…`), or null when the link is
 * not an invite. Read by the same rule as a pasted code, so the link the friends server's invite page
 * opens and the one a message carries are told from every other link the same way.
 */
internal fun friendsInviteCodeOfLink(link: String?): String? {
    val text = link ?: return null
    if (!text.startsWith("renoop:", ignoreCase = true)) return null
    return FriendsInviteCode.extract(text)
}
