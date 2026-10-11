package com.noop.ui.friends

import com.noop.friends.FriendDay
import com.noop.friends.FriendPerson
import com.noop.friends.FriendWorkout
import java.text.Collator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Friends tab: the list, the highlights and a person's week, without a screen
//
// Twins of the Swift app's `FriendsBoard.rows`, `FriendsHighlights.recent` and `FriendWeek`
// (`Strand/Friends/`). Everything here is pure: who comes first under each order and which figure a card
// shows, which workouts are news, which seven days a person's page covers. `FriendsBoardOracleTest` holds
// the Swift functions' own answers over a spread of rosters; the screens only draw what comes out.

/** What a card shows: one of the three scores, or the day's workouts. */
internal enum class FriendsMetric {
    RECOVERY, STRAIN, SLEEP, WORKOUTS;

    /**
     * The day's figure. For workouts it is the energy they burned, added up: zero on a day without one,
     * and null when the day has workouts but none of them carries its energy.
     */
    fun value(day: FriendDay): Double? = when (this) {
        RECOVERY -> day.recovery?.toDouble()
        STRAIN -> day.strain
        SLEEP -> day.sleepScore?.toDouble()
        WORKOUTS -> day.workouts?.let { list ->
            val known = list.mapNotNull { it.kcal }
            if (known.isEmpty() && list.isNotEmpty()) null else known.sum().toDouble()
        }
    }

    /**
     * Whether a card for this figure carries a second line. Sleep has the time asleep behind it, Strain
     * the minutes of workouts and the workouts their name; nothing a friend uploads stands behind
     * Recovery, so its cards are one line.
     */
    val hasDetail: Boolean get() = this != RECOVERY
}

/**
 * The list's order (`FriendsSort` in the Swift app; [wire] is its raw value there, and what both apps keep
 * the choice as). Each order puts one figure on the cards; by name it is Recovery, the first ring.
 */
internal enum class FriendsOrder(val wire: String) {
    NAME("name"), RECOVERY("recovery"), STRAIN("strain"), SLEEP("sleep"), WORKOUTS("workouts");

    val metric: FriendsMetric
        get() = when (this) {
            NAME, RECOVERY -> FriendsMetric.RECOVERY
            STRAIN -> FriendsMetric.STRAIN
            SLEEP -> FriendsMetric.SLEEP
            WORKOUTS -> FriendsMetric.WORKOUTS
        }

    companion object {
        /** The order kept as [wire], or by name: the order the list opens in. */
        fun of(wire: String?): FriendsOrder = entries.firstOrNull { it.wire == wire } ?: NAME
    }
}

/** One person on the list. */
internal data class FriendsBoardRow(
    val person: FriendPerson,
    val isMe: Boolean,
    val value: Double?,
    val standing: Standing,
    val detail: Detail? = null,
) {
    /** How the shown figure stands to the wearer's own day. */
    sealed class Standing {
        /** A figure from today. */
        object Today : Standing()

        /** The newest figure is from an earlier day, named by its key. */
        data class Stale(val dayKey: String) : Standing()

        /** The person has switched the section off. */
        object NotShared : Standing()

        /** Nothing uploaded yet, or nothing for this figure. */
        object NoData : Standing()
    }

    /** The amount behind the figure, from the same day. */
    sealed class Detail {
        /** Time asleep that night. */
        data class Asleep(val minutes: Int) : Detail()

        /** The day's workouts, added up; zero on a day without one. */
        data class Workouts(val minutes: Int) : Detail()

        /** How many workouts the day had and, when it was one, which sport. */
        data class Sessions(val count: Int, val sport: String?) : Detail()
    }
}

internal object FriendsBoard {
    /**
     * Everyone on the list in [order], each with their figure for the order's metric.
     *
     * By name the wearer stands among their friends under [meName], the word their own card carries. By
     * a figure, those with one from [todayKey] (or a later day, for a friend whose calendar is already
     * ahead) come first, highest first, the wearer among them; then everyone else by name. [names] puts
     * two names in the reader's order ignoring case ([nameOrder]); equal names fall back to the account
     * id. [todayKey] is the wearer's own day, "yyyy-MM-dd".
     */
    fun rows(
        me: FriendPerson,
        friends: List<FriendPerson>,
        order: FriendsOrder,
        todayKey: String,
        meName: String,
        names: Comparator<String>,
    ): List<FriendsBoardRow> {
        val metric = order.metric
        val all = listOf(row(me, isMe = true, metric, todayKey)) + friends.map { row(it, isMe = false, metric, todayKey) }
        fun listed(row: FriendsBoardRow) = if (row.isMe) meName else row.person.profile.name
        val byListedName: (FriendsBoardRow, FriendsBoardRow) -> Boolean = { a, b ->
            val compared = names.compare(listed(a), listed(b))
            if (compared != 0) compared < 0 else a.person.id < b.person.id
        }
        if (order == FriendsOrder.NAME) return swiftSorted(all, byListedName)
        val fresh = swiftSorted(all.filter { it.standing == FriendsBoardRow.Standing.Today }) { a, b ->
            if (a.value != b.value) (a.value ?: 0.0) > (b.value ?: 0.0) else byListedName(a, b)
        }
        return fresh + swiftSorted(all.filter { it.standing != FriendsBoardRow.Standing.Today }, byListedName)
    }

    private fun row(person: FriendPerson, isMe: Boolean, metric: FriendsMetric, todayKey: String): FriendsBoardRow {
        // The scores and the workouts each have their own sharing switch.
        val shared = if (metric == FriendsMetric.WORKOUTS) person.share.workouts else person.share.scores
        if (!shared) return FriendsBoardRow(person, isMe, null, FriendsBoardRow.Standing.NotShared)
        val day = person.days.firstOrNull() ?: return FriendsBoardRow(person, isMe, null, FriendsBoardRow.Standing.NoData)
        val value = metric.value(day)
        // A day whose workouts were not read says nothing about workouts; one that has some without
        // their energy is still that day's, with no figure to show.
        if (value == null && (metric != FriendsMetric.WORKOUTS || day.workouts == null)) {
            return FriendsBoardRow(person, isMe, null, FriendsBoardRow.Standing.NoData)
        }
        return FriendsBoardRow(
            person, isMe, value,
            if (day.day >= todayKey) FriendsBoardRow.Standing.Today else FriendsBoardRow.Standing.Stale(day.day),
            detail(person, day, metric),
        )
    }

    /**
     * What stands behind the figure, where the person shares it. A section a person has switched off is
     * not sent, so it is not known and nothing is shown for it.
     */
    private fun detail(person: FriendPerson, day: FriendDay, metric: FriendsMetric): FriendsBoardRow.Detail? = when (metric) {
        FriendsMetric.RECOVERY -> null
        FriendsMetric.SLEEP -> day.sleep?.takeIf { person.share.sleep }?.let { FriendsBoardRow.Detail.Asleep(it.asleepMin) }
        FriendsMetric.STRAIN ->
            if (!person.share.workouts) null
            else FriendsBoardRow.Detail.Workouts(friendsWorkoutMinutes(day.workouts.orEmpty()))
        FriendsMetric.WORKOUTS -> {
            val list = day.workouts.orEmpty()
            FriendsBoardRow.Detail.Sessions(list.size, if (list.size == 1) list[0].sport else null)
        }
    }

    /**
     * Two names in the order [locale] files them, ignoring case: the twin of Swift's
     * `localizedCaseInsensitiveCompare`, which the list and the "Sharing With" card are both ordered by.
     */
    fun nameOrder(locale: Locale): Comparator<String> {
        val collator = Collator.getInstance(locale)
        // Secondary: an accent tells two names apart, the case of a letter does not.
        collator.strength = Collator.SECONDARY
        collator.decomposition = Collator.CANONICAL_DECOMPOSITION
        return Comparator { a, b -> collator.compare(a, b) }
    }

    /**
     * [list] sorted as Swift's `sorted(by:)` sorts it. Two rows of the list can be neither before nor
     * after each other without being equal (a day of workouts with no energy against a day with none at
     * all), and where they end up then depends on the steps of the sort itself. So the steps are the
     * Swift standard library's own (`Sort.swift`, the stable sort since Swift 5): an insertion sort
     * below 64 elements; from 64 up, natural runs (a descending one reversed) each grown to a minimum
     * length by insertion, merged by the rule that keeps the run lengths in timsort's order.
     */
    internal fun <T> swiftSorted(list: List<T>, areInIncreasingOrder: (T, T) -> Boolean): List<T> {
        val a = list.toMutableList()
        val count = a.size
        val minimumRun = minimumMergeRunLength(count)
        if (count <= minimumRun) {
            if (count > 1) insertionSort(a, 0, count, 1, areInIncreasingOrder)
            return a
        }
        val runs = ArrayList<IntArray>()
        var start = 0
        while (start < count) {
            var end = start + 1
            var descending = false
            if (end < count) {
                descending = areInIncreasingOrder(a[end], a[start])
                do {
                    end++
                } while (end < count && descending == areInIncreasingOrder(a[end], a[end - 1]))
            }
            if (descending) a.subList(start, end).reverse()
            if (end < count && end - start < minimumRun) {
                val grown = minOf(count, start + minimumRun)
                insertionSort(a, start, grown, end, areInIncreasingOrder)
                end = grown
            }
            runs += intArrayOf(start, end)
            mergeTopRuns(a, runs, areInIncreasingOrder)
            start = end
        }
        while (runs.size > 1) mergeRuns(a, runs, runs.size - 1, areInIncreasingOrder)
        return a
    }

    /** The whole list below 64 elements; from there a length between 32 and 64 that divides it into few runs. */
    private fun minimumMergeRunLength(count: Int): Int {
        val bitsToUse = 6
        if (count < (1 shl bitsToUse)) return count
        val offset = (Int.SIZE_BITS - bitsToUse) - count.countLeadingZeroBits()
        val mask = (1 shl offset) - 1
        return (count shr offset) + (if (count and mask == 0) 0 else 1)
    }

    /** Sorts [from, to), whose first elements up to [sortedEnd] are sorted already. */
    private fun <T> insertionSort(a: MutableList<T>, from: Int, to: Int, sortedEnd: Int, less: (T, T) -> Boolean) {
        for (next in sortedEnd until to) {
            var i = next
            while (i > from && less(a[i], a[i - 1])) {
                val moved = a[i]
                a[i] = a[i - 1]
                a[i - 1] = moved
                i--
            }
        }
    }

    private fun length(run: IntArray): Int = run[1] - run[0]

    /** Merges the newest runs until each is shorter than the one before it and than the two after it together. */
    private fun <T> mergeTopRuns(a: MutableList<T>, runs: MutableList<IntArray>, less: (T, T) -> Boolean) {
        while (runs.size > 1) {
            var last = runs.size - 1
            if (last >= 3 && length(runs[last - 3]) <= length(runs[last - 2]) + length(runs[last - 1])) {
                if (length(runs[last - 2]) < length(runs[last])) last--
            } else if (last >= 2 && length(runs[last - 2]) <= length(runs[last - 1]) + length(runs[last])) {
                if (length(runs[last - 2]) < length(runs[last])) last--
            } else if (length(runs[last - 1]) > length(runs[last])) {
                break
            }
            mergeRuns(a, runs, last, less)
        }
    }

    /** Merges the run at [index] into the one before it, the shorter of the two set aside first. */
    private fun <T> mergeRuns(a: MutableList<T>, runs: MutableList<IntArray>, index: Int, less: (T, T) -> Boolean) {
        val low = runs[index - 1][0]
        val mid = runs[index][0]
        val high = runs[index][1]
        if (mid - low < high - mid) {
            val buffer = a.subList(low, mid).toList()
            var taken = 0
            var source = mid
            var dest = low
            while (taken < buffer.size && source < high) {
                if (less(a[source], buffer[taken])) a[dest++] = a[source++] else a[dest++] = buffer[taken++]
            }
            while (taken < buffer.size) a[dest++] = buffer[taken++]
        } else {
            val buffer = a.subList(mid, high).toList()
            var left = buffer.size
            var source = mid
            var dest = high
            while (left > 0 && source > low) {
                dest--
                if (less(buffer[left - 1], a[source - 1])) a[dest] = a[--source] else a[dest] = buffer[--left]
            }
            while (left > 0) a[--dest] = buffer[--left]
        }
        runs[index - 1] = intArrayOf(low, high)
        runs.removeAt(index)
    }
}

/** The minutes a list of workouts ran for, added up and rounded to the minute, halves upward. */
internal fun friendsWorkoutMinutes(workouts: List<FriendWorkout>): Int =
    (workouts.sumOf { it.durationS.toLong() } / 60.0).let { Math.round(it).toInt() }

// MARK: Highlights

/** One finished workout of one friend. */
internal data class FriendHighlight(
    val personId: String,
    val name: String,
    /** The revision of the friend's picture; 0 is an account without one. */
    val avatarRev: Int,
    val workout: FriendWorkout,
) {
    val endTs: Long get() = workout.startTs + workout.durationS
    val id: String get() = "$personId#${workout.startTs}"
}

internal object FriendsHighlights {
    /** How far back a workout still counts as news. */
    const val WINDOW_SECONDS = 48 * 3600L

    /**
     * A friend's phone may run a little ahead of the server's clock; a workout that ended this far in
     * the "future" is still shown.
     */
    const val CLOCK_SLACK_SECONDS = 120L

    /** The tab shows the latest few; a friend's own page has the rest. */
    const val LIMIT = 5

    /** Friends' workouts that ended within the window, newest first. [now] is unix seconds. */
    fun recent(friends: List<FriendPerson>, now: Long): List<FriendHighlight> {
        val seen = HashSet<String>()
        val found = ArrayList<FriendHighlight>()
        for (person in friends) {
            for (day in person.days) {
                for (workout in day.workouts.orEmpty()) {
                    val highlight = FriendHighlight(person.id, person.profile.name, person.profile.avatarRev, workout)
                    val age = now - highlight.endTs
                    if (age < -CLOCK_SLACK_SECONDS || age > WINDOW_SECONDS || !seen.add(highlight.id)) continue
                    found += highlight
                }
            }
        }
        return found.sortedWith(compareByDescending<FriendHighlight> { it.endTs }.thenBy { it.personId }).take(LIMIT)
    }
}

// MARK: A person's week

/** The seven days a person's page covers, oldest first. */
internal object FriendWeek {
    data class Day(
        /** "yyyy-MM-dd". */
        val key: String,
        /** What the person uploaded for the day, when they did. */
        val summary: FriendDay?,
    )

    const val DAYS = 7

    /**
     * The seven days ending on [anchorKey], each with what was uploaded for it. A day uploaded twice is
     * read once, as it comes first. A key that is not a date yields no days.
     */
    fun days(anchorKey: String, uploaded: List<FriendDay>): List<Day> {
        val anchor = friendsDate(anchorKey) ?: return emptyList()
        val byKey = HashMap<String, FriendDay>()
        uploaded.forEach { byKey.putIfAbsent(it.day, it) }
        return (DAYS - 1 downTo 0).map { back ->
            val key = anchor.minusDays(back.toLong()).toString()
            Day(key, byKey[key])
        }
    }

    /**
     * The day the week ends on: the wearer's own day, or the person's newest when their calendar is
     * already a day ahead.
     */
    fun anchor(todayKey: String, newest: String?): String = if (newest != null && newest > todayKey) newest else todayKey
}

/** The date a day key names, or null when it is not one. Four-digit years only, as the keys are. */
internal fun friendsDate(dayKey: String): LocalDate? =
    if (!DAY_KEY.matches(dayKey)) null else runCatching { LocalDate.parse(dayKey) }.getOrNull()?.takeIf { it.year >= 1 }

private val DAY_KEY = Regex("""^\d{4}-\d{2}-\d{2}$""")

/** How a day is named on a person's page and beside a workout. */
internal sealed class FriendsDayName {
    object Today : FriendsDayName()
    object Yesterday : FriendsDayName()

    /** A day of the past week, by its weekday. */
    data class Weekday(val day: DayOfWeek) : FriendsDayName()

    /** Anything older, and a day ahead of today, by its date. */
    data class OnDate(val date: LocalDate) : FriendsDayName()

    companion object {
        /**
         * How [dayKey] is named from [today]: today and yesterday by their words, two to six days back
         * by the weekday when [weekdays] is asked for, anything else by the date. Null when the key is
         * not a date.
         */
        fun of(dayKey: String, today: LocalDate, weekdays: Boolean): FriendsDayName? {
            val date = friendsDate(dayKey) ?: return null
            val back = ChronoUnit.DAYS.between(date, today)
            return when {
                back == 0L -> Today
                back == 1L -> Yesterday
                weekdays && back in 2..6 -> Weekday(date.dayOfWeek)
                else -> OnDate(date)
            }
        }
    }
}

/** "Thursday" / "Четверг": a weekday by its own name in [locale], its first letter a capital. */
internal fun friendsWeekdayName(day: DayOfWeek, locale: Locale): String =
    day.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }

/** The one letter a weekday goes by over a column ("M"), in [locale]. Empty when the key is not a date. */
internal fun friendsWeekdayLetter(dayKey: String, locale: Locale): String {
    val date = friendsDate(dayKey) ?: return ""
    return date.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)
}

// MARK: Figures

/** A workout's length as hours and minutes, "0:30": rounded to the minute, halves upward. */
internal fun friendsClockDuration(seconds: Int): String {
    val minutes = Math.round(seconds.coerceAtLeast(0) / 60.0)
    return "${minutes / 60}:" + (minutes % 60).toString().padStart(2, '0')
}

/** A 0–100 score as the cards write it, "73%": rounded to a whole number, halves away from zero. */
internal fun friendsPercent(value: Double): String = "${value.roundToInt()}%"

/**
 * The name a person goes by in a sentence about them: their first word. The Swift app asks the system's
 * name parser for the given name, which this follows for a plain "Given Family" name and for a single
 * word; it does not follow it for a title in front ("Dr. Anna Smith"), a family name written first, or a
 * name in a script that writes no space between the two.
 */
internal fun friendsShortName(name: String): String =
    name.split(' ', '\t', '\n').firstOrNull { it.isNotEmpty() } ?: name
