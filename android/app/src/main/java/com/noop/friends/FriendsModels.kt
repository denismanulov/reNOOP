package com.noop.friends

// MARK: - Friends: what the server holds, as plain values
//
// The Friends tab is a reNOOP fork feature (friends-server/README.md is the contract). Everything here is
// a plain value with no Android type in it, so the parsing, the day payload and the tab's reading of a
// feed are all unit-tested on the JVM. A member the server did not send is null: a friend who does not
// share a section, or a day the phone had no honest figure for, reads as absent, never as zero.

/** The four sharing switches. A section that is off is neither built, sent nor stored. */
data class FriendShare(
    /** Recovery, Strain and the Sleep score. */
    val scores: Boolean,
    /** The night: its times, stage minutes and need. */
    val sleep: Boolean,
    val workouts: Boolean,
    /** Latest heart rate, the day's line and resting heart rate. */
    val hr: Boolean,
) {
    companion object {
        /** What a new account shares: everything except heart rate (the server's own default). */
        val DEFAULT = FriendShare(scores = true, sleep = true, workouts = true, hr = false)

        /** Nothing: the reading of a profile that arrived without its switches. */
        val NONE = FriendShare(scores = false, sleep = false, workouts = false, hr = false)
    }
}

/** How another account stands to the signed-in one. */
enum class FriendRelation(val wire: String) {
    SELF("self"), FRIEND("friend"), OUTGOING("outgoing"), INCOMING("incoming"), NONE("none");

    companion object {
        fun of(wire: String?): FriendRelation? = entries.firstOrNull { it.wire == wire }
    }
}

/** An account as others see it. [share] is present on the signed-in account and on a feed entry. */
data class FriendProfile(
    val nick: String,
    val name: String,
    /** 0 when the account has no picture; otherwise it changes whenever the picture does. */
    val avatarRev: Int = 0,
    val share: FriendShare? = null,
    val relation: FriendRelation? = null,
)

/** The main sleep of a day. Stage minutes and the need are optional on the wire. */
data class FriendSleep(
    val startTs: Long,
    val endTs: Long,
    val asleepMin: Int,
    val awakeMin: Int? = null,
    val remMin: Int? = null,
    val lightMin: Int? = null,
    val deepMin: Int? = null,
    val needMin: Int? = null,
)

/** One workout of a day. [strain] is on the stored 0–100 axis. */
data class FriendWorkout(
    val startTs: Long,
    /** The app's own, English sport label; each phone shows it in its reader's language. */
    val sport: String,
    val durationS: Int,
    val strain: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val kcal: Int? = null,
)

/** One point of a day's heart-rate line. */
data class FriendHrPoint(val ts: Long, val bpm: Int)

/** A day's heart rate. [series] is empty in the feed, which leaves the line out. */
data class FriendHr(
    val lastBpm: Int,
    val lastTs: Long,
    val restingBpm: Int? = null,
    val series: List<FriendHrPoint> = emptyList(),
)

/** One uploaded day of one person, as the server answers it. */
data class FriendDay(
    /** The wearer's own day, "yyyy-MM-dd". */
    val day: String,
    /** When the wearer's phone last uploaded this day (unix seconds, the server's clock). */
    val updatedAt: Long,
    val recovery: Int? = null,
    /** Day Strain on the stored 0–100 axis. */
    val strain: Double? = null,
    val sleepScore: Int? = null,
    val sleep: FriendSleep? = null,
    /** Null when the section is not shared or was not uploaded; empty when the day had no workout. */
    val workouts: List<FriendWorkout>? = null,
    val hr: FriendHr? = null,
)

/** A person with their days, newest day first: the signed-in account or one friend. */
data class FriendPerson(
    val profile: FriendProfile,
    val share: FriendShare,
    val days: List<FriendDay>,
) {
    val nick: String get() = profile.nick

    /** The day keyed [dayKey], or null when the person uploaded none. */
    fun day(dayKey: String): FriendDay? = days.firstOrNull { it.day == dayKey }

    /** When this person's phone last uploaded anything in the window, or null with no day at all. */
    val lastUpdatedAt: Long? get() = days.maxOfOrNull { it.updatedAt }
}

/** `GET /v1/feed`: the signed-in account and every friend at a glance. */
data class FriendsFeed(
    /** The server's clock when it answered (unix seconds): the reference every "N min ago" counts from. */
    val serverTime: Long,
    val me: FriendPerson,
    val friends: List<FriendPerson>,
    val pendingIncoming: Int,
)

/** A pending friend request with when it was sent. */
data class FriendRequest(val profile: FriendProfile, val requestedAt: Long)

/** `GET /v1/friends/requests`. */
data class FriendRequests(val incoming: List<FriendRequest>, val outgoing: List<FriendRequest>)

/** `GET /v1/info`. */
data class FriendsServerInfo(val name: String, val api: Int, val inviteRequired: Boolean)

/** A sign-up or sign-in answer: the session token and the account it belongs to. */
class FriendsSession(val token: String, val me: FriendProfile) {
    // A token must never reach a log through string interpolation.
    override fun toString(): String = "FriendsSession(nick=${me.nick})"
}
