package com.noop.friends

// MARK: - Friends: what the server holds, as plain values
//
// The Friends tab is a reNOOP fork feature (friends-server/README.md is the contract, API version 2).
// People are known by an opaque account id: there is no nickname and nothing to type. Everything here is
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

/** An account id as the server mints it, and a phone's key id. Checked wherever one goes into a path or a file name. */
object FriendsId {
    /** 16 lower-case hex characters. */
    fun isValid(id: String): Boolean = id.length == 16 && id.all(::isLowerHex)

    /** 64 lower-case hex characters: the SHA-256 of a public key. */
    fun isKeyId(id: String): Boolean = id.length == 64 && id.all(::isLowerHex)

    private fun isLowerHex(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f'
}

/** How this phone stands on the wearer's own account. */
data class FriendsDeviceState(
    /** This phone's key id. */
    val id: String,
    /** Unix seconds until which this phone may only read and upload; null for a confirmed phone. */
    val probationUntil: Long? = null,
)

/**
 * An account as others see it. [share] comes on the wearer's own account and on a feed entry;
 * [strapBound] and [device] only on the wearer's own account.
 */
data class FriendProfile(
    /** The account's id on the server: opaque, never shown, never typed. */
    val id: String,
    val name: String,
    /** 0 when the account has no picture; otherwise it changes whenever the picture does. */
    val avatarRev: Int = 0,
    val share: FriendShare? = null,
    val strapBound: Boolean? = null,
    val device: FriendsDeviceState? = null,
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

/** A person with their days, newest day first: the wearer's own account or one friend. */
data class FriendPerson(
    val profile: FriendProfile,
    val share: FriendShare,
    val days: List<FriendDay>,
) {
    val id: String get() = profile.id

    /** The day keyed [dayKey], or null when the person uploaded none. */
    fun day(dayKey: String): FriendDay? = days.firstOrNull { it.day == dayKey }

    /** When this person's phone last uploaded anything in the window, or null with no day at all. */
    val lastUpdatedAt: Long? get() = days.maxOfOrNull { it.updatedAt }
}

/**
 * A request waiting on a strap: a new phone asking to join the account ([Kind.JOIN]), or another account
 * asking for the strap ([Kind.TAKE]).
 */
data class FriendsClaim(
    val id: Long,
    val kind: Kind,
    /** Six digits shown on both phones, so two claims can be told apart. */
    val code: String,
    val state: State,
    val platform: String? = null,
    val createdAt: Long,
    /** When silence alone will grant it (unix seconds); null once settled. */
    val maturesAt: Long? = null,
) {
    enum class Kind(val wire: String) {
        JOIN("join"), TAKE("take");

        companion object {
            fun of(wire: String?): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }

    enum class State(val wire: String) {
        PENDING("pending"), APPROVED("approved"), DECLINED("declined"), EXPIRED("expired");

        companion object {
            fun of(wire: String?): State? = entries.firstOrNull { it.wire == wire }
        }
    }
}

/** One phone of the wearer's account. */
data class FriendsDevice(
    /** Its key id. */
    val id: String,
    /** "ios", "android" or "mac": all the server knows about the device. */
    val platform: String,
    val addedAt: Long,
    val lastSeenAt: Long,
    val probationUntil: Long? = null,
    /** This phone. */
    val current: Boolean,
)

/** A one-time invite. */
data class FriendsInvite(
    val id: String,
    /** Said once, in the answer that made the invite. */
    val code: String? = null,
    val createdAt: Long? = null,
    val expiresAt: Long,
)

/** `GET /v2/feed`: the wearer's own account, every friend, and what waits for an answer. */
data class FriendsFeed(
    /** The server's clock when it answered (unix seconds): the reference every "N min ago" counts from. */
    val serverTime: Long,
    val me: FriendPerson,
    val friends: List<FriendPerson>,
    /** Claims waiting for this account's answer. */
    val claims: List<FriendsClaim> = emptyList(),
    /** This account's own request for a strap bound to someone else. */
    val strapClaim: FriendsClaim? = null,
    /** The account's other phones that joined without confirmation and are still on probation. */
    val unconfirmed: List<FriendsDevice> = emptyList(),
)

/** `GET /v2/info`. */
data class FriendsServerInfo(val name: String, val api: Int, val time: Long)

/** `POST /v2/enroll`: the account, and whether it was made now or is one the key already had. */
data class FriendsEnrolment(val me: FriendProfile, val isNew: Boolean)

/** What the server said to "bind this strap": done, or asked of the account that holds it. */
sealed class FriendsStrapAnswer {
    object Bound : FriendsStrapAnswer()
    data class Claimed(val claim: FriendsClaim) : FriendsStrapAnswer()
}

/**
 * The names a build gives the server for a phone: all the server learns about the device. [CURRENT] is
 * this build's; the other two are what the Swift app sends from an iPhone and from a Mac.
 */
object FriendsPlatform {
    const val CURRENT = "android"
    const val IOS = "ios"
    const val MAC = "mac"

    /** What those two are called on a screen: product names, written the same in every language. */
    const val IPHONE_NAME = "iPhone"
    const val MAC_NAME = "Mac"
}
