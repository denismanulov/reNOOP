package com.noop.friends

import org.json.JSONArray
import org.json.JSONObject

// MARK: - Friends: reading the server's answers
//
// Every optional member is read as "absent" when it is missing, null, or of the wrong kind, so a section
// a friend does not share and a section a newer server spells differently both read as not there rather
// than as a zero or a crash. Only what a screen cannot stand without (a nickname, a token, the feed's
// own account) is required; without it the answer is refused whole ([FriendsParseException]).

/** The answer did not carry what the API documents. Its text names a member, never a value. */
class FriendsParseException(message: String) : Exception(message)

object FriendsJson {

    /** The `error` code of an error body, or null when the body is not the server's own error shape. */
    fun errorCode(body: String): String? =
        runCatching { JSONObject(body).opt("error") as? String }.getOrNull()

    fun profile(body: String): FriendProfile = profile(root(body))

    /** A profile as text [profile] reads back: how the signed-in account is kept between launches. */
    fun profileText(profile: FriendProfile): String {
        val obj = JSONObject().put("nick", profile.nick).put("name", profile.name).put("avatarRev", profile.avatarRev)
        profile.share?.let { s ->
            obj.put(
                "share",
                JSONObject().put("scores", s.scores).put("sleep", s.sleep).put("workouts", s.workouts).put("hr", s.hr),
            )
        }
        return obj.toString()
    }

    fun session(body: String): FriendsSession {
        val obj = root(body)
        val token = obj.string("token")?.takeIf { it.isNotBlank() } ?: missing("token")
        return FriendsSession(token, profile(obj.obj("me") ?: missing("me")))
    }

    /** The new token of a password change. */
    fun token(body: String): String = root(body).string("token")?.takeIf { it.isNotBlank() } ?: missing("token")

    fun nickFree(body: String): Boolean = root(body).bool("free") ?: missing("free")

    fun info(body: String): FriendsServerInfo {
        val obj = root(body)
        return FriendsServerInfo(
            name = obj.string("name").orEmpty(),
            api = obj.int("api") ?: missing("api"),
            inviteRequired = obj.bool("inviteRequired") ?: false,
        )
    }

    fun feed(body: String): FriendsFeed {
        val obj = root(body)
        return FriendsFeed(
            serverTime = obj.long("serverTime") ?: missing("serverTime"),
            me = person(obj.obj("me") ?: missing("me")),
            friends = obj.array("friends").objects().mapNotNull { runCatching { person(it) }.getOrNull() },
            pendingIncoming = (obj.int("pendingIncoming") ?: 0).coerceAtLeast(0),
        )
    }

    /** `GET /v1/users/{nick}/days`: one person in full. */
    fun person(body: String): FriendPerson = person(root(body))

    fun requests(body: String): FriendRequests {
        val obj = root(body)
        fun list(key: String) = obj.array(key).objects().mapNotNull { item ->
            runCatching { FriendRequest(profile(item), item.long("requestedAt") ?: 0L) }.getOrNull()
        }
        return FriendRequests(incoming = list("incoming"), outgoing = list("outgoing"))
    }

    // MARK: Pieces

    private fun profile(obj: JSONObject): FriendProfile {
        val nick = obj.string("nick")?.takeIf { it.isNotBlank() } ?: missing("nick")
        return FriendProfile(
            nick = nick,
            name = obj.string("name")?.takeIf { it.isNotBlank() } ?: nick,
            avatarRev = (obj.int("avatarRev") ?: 0).coerceAtLeast(0),
            share = obj.obj("share")?.let(::share),
            relation = FriendRelation.of(obj.string("relation")),
        )
    }

    private fun share(obj: JSONObject) = FriendShare(
        scores = obj.bool("scores") ?: false,
        sleep = obj.bool("sleep") ?: false,
        workouts = obj.bool("workouts") ?: false,
        hr = obj.bool("hr") ?: false,
    )

    private fun person(obj: JSONObject): FriendPerson {
        val profile = profile(obj)
        return FriendPerson(
            profile = profile,
            share = profile.share ?: FriendShare.NONE,
            days = obj.array("days").objects().mapNotNull(::day).sortedByDescending { it.day },
        )
    }

    /** One day, or null when it carries no day key (nothing could be said about when it was). */
    private fun day(obj: JSONObject): FriendDay? {
        val key = obj.string("day")?.takeIf { DAY_KEY.matches(it) } ?: return null
        return FriendDay(
            day = key,
            updatedAt = obj.long("updatedAt") ?: 0L,
            recovery = obj.int("recovery")?.coerceIn(0, 100),
            strain = obj.double("strain")?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0),
            sleepScore = obj.int("sleepScore")?.coerceIn(0, 100),
            sleep = obj.obj("sleep")?.let(::sleep),
            workouts = obj.opt("workouts").let { it as? JSONArray }?.objects()?.mapNotNull(::workout),
            hr = obj.obj("hr")?.let(::hr),
        )
    }

    private fun sleep(obj: JSONObject): FriendSleep? {
        val start = obj.long("startTs") ?: return null
        val end = obj.long("endTs") ?: return null
        val asleep = obj.int("asleepMin") ?: return null
        if (end <= start || asleep < 0) return null
        fun minutes(key: String) = obj.int(key)?.takeIf { it >= 0 }
        return FriendSleep(
            startTs = start, endTs = end, asleepMin = asleep,
            awakeMin = minutes("awakeMin"), remMin = minutes("remMin"), lightMin = minutes("lightMin"),
            deepMin = minutes("deepMin"), needMin = minutes("needMin"),
        )
    }

    private fun workout(obj: JSONObject): FriendWorkout? {
        val start = obj.long("startTs") ?: return null
        val sport = obj.string("sport")?.takeIf { it.isNotBlank() } ?: return null
        val duration = obj.int("durationS")?.takeIf { it >= 0 } ?: return null
        return FriendWorkout(
            startTs = start, sport = sport, durationS = duration,
            strain = obj.double("strain")?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0),
            avgHr = obj.int("avgHr"), maxHr = obj.int("maxHr"), kcal = obj.int("kcal"),
        )
    }

    private fun hr(obj: JSONObject): FriendHr? {
        val bpm = obj.int("lastBpm") ?: return null
        val ts = obj.long("lastTs") ?: return null
        val series = obj.array("series").let { array ->
            (0 until array.length()).mapNotNull { i ->
                val point = array.opt(i) as? JSONArray ?: return@mapNotNull null
                val pointTs = (point.opt(0) as? Number)?.toLong() ?: return@mapNotNull null
                val pointBpm = (point.opt(1) as? Number)?.toInt() ?: return@mapNotNull null
                FriendHrPoint(pointTs, pointBpm)
            }
        }
        return FriendHr(lastBpm = bpm, lastTs = ts, restingBpm = obj.int("restingBpm"), series = series.sortedBy { it.ts })
    }

    // MARK: Typed reads

    private val DAY_KEY = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private fun root(body: String): JSONObject =
        runCatching { JSONObject(body) }.getOrNull() ?: throw FriendsParseException("not a JSON object")

    private fun missing(member: String): Nothing = throw FriendsParseException("missing $member")

    private fun JSONObject.string(key: String): String? = opt(key) as? String
    private fun JSONObject.bool(key: String): Boolean? = opt(key) as? Boolean
    private fun JSONObject.int(key: String): Int? = number(key)?.let { if (it.isFinite()) Math.round(it).toInt() else null }
    private fun JSONObject.long(key: String): Long? = number(key)?.let { if (it.isFinite()) Math.round(it) else null }
    private fun JSONObject.double(key: String): Double? = number(key)
    private fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()
    private fun JSONObject.obj(key: String): JSONObject? = opt(key) as? JSONObject
    private fun JSONObject.array(key: String): JSONArray = opt(key) as? JSONArray ?: JSONArray()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { opt(it) as? JSONObject }
}
