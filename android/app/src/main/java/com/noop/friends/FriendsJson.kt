package com.noop.friends

import org.json.JSONArray
import org.json.JSONObject

// MARK: - Friends: reading the server's answers
//
// Every optional member is read as "absent" when it is missing, null, or of the wrong kind, so a section
// a friend does not share and a section a newer server spells differently both read as not there rather
// than as a zero or a crash. Only what a screen cannot stand without (an account id, the feed's own
// account) is required; without it the answer is refused whole ([FriendsParseException]). A list is read
// around what cannot be read in it: one friend without an id does not cost the others.

/** The answer did not carry what the API documents. Its text names a member, never a value. */
class FriendsParseException(message: String) : Exception(message)

object FriendsJson {

    /** The server's own refusal: its code, and its clock when the refusal is about this phone's. */
    class Refusal(val code: String?, val serverTime: Long?)

    /** An error body read for its `error` code; both members null when it is not the server's own error shape. */
    fun refusal(body: String): Refusal {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return Refusal(null, null)
        return Refusal(obj.opt("error") as? String, obj.long("serverTime"))
    }

    /** `GET /v2/me`, `PATCH /v2/me`, `PUT /v2/me/avatar`: the wearer's own account. */
    fun profile(body: String): FriendProfile = profile(root(body))

    /** `POST /v2/enroll`. */
    fun enrolledAccount(body: String): FriendProfile = profile(root(body).obj("me") ?: missing("me"))

    /** `POST /v2/invites/redeem`: the new friend. */
    fun redeemedFriend(body: String): FriendProfile = profile(root(body).obj("friend") ?: missing("friend"))

    /** `POST /v2/claims`, `GET /v2/claims/mine`. */
    fun claimAnswer(body: String): FriendsClaim = claim(root(body).obj("claim") ?: missing("claim")) ?: missing("claim")

    /** `PUT /v2/me/strap`: bound, or asked of the account that holds the strap. */
    fun strapAnswer(body: String): FriendsStrapAnswer {
        val obj = root(body)
        obj.obj("claim")?.let(::claim)?.let { return FriendsStrapAnswer.Claimed(it) }
        if (obj.bool("bound") == true) return FriendsStrapAnswer.Bound
        missing("bound")
    }

    /** `GET /v2/me/devices`. */
    fun devices(body: String): List<FriendsDevice> =
        (root(body).opt("devices") as? JSONArray ?: missing("devices")).objects().mapNotNull(::device)

    /** `POST /v2/invites`: the one answer that says an invite's code. */
    fun invite(body: String): FriendsInvite = invite(root(body)) ?: missing("invite")

    /** `GET /v2/invites`. */
    fun invites(body: String): List<FriendsInvite> =
        (root(body).opt("invites") as? JSONArray ?: missing("invites")).objects().mapNotNull(::invite)

    fun info(body: String): FriendsServerInfo {
        val obj = root(body)
        return FriendsServerInfo(
            name = obj.string("name").orEmpty(),
            api = obj.int("api") ?: missing("api"),
            time = obj.long("time") ?: 0L,
        )
    }

    fun feed(body: String): FriendsFeed {
        val obj = root(body)
        return FriendsFeed(
            serverTime = obj.long("serverTime") ?: missing("serverTime"),
            me = person(obj.obj("me") ?: missing("me")),
            friends = obj.array("friends").objects().mapNotNull { runCatching { person(it) }.getOrNull() },
            claims = obj.array("claims").objects().mapNotNull(::claim),
            strapClaim = obj.obj("strapClaim")?.let(::claim),
            unconfirmed = obj.array("unconfirmed").objects().mapNotNull(::device),
        )
    }

    /** `GET /v2/users/{id}/days`: one person in full. */
    fun person(body: String): FriendPerson = person(root(body))

    /** A claim as text [claim] reads back: how a request in flight is kept between launches. */
    fun claimText(claim: FriendsClaim): String {
        val obj = JSONObject().put("id", claim.id).put("kind", claim.kind.wire).put("code", claim.code)
            .put("state", claim.state.wire).put("createdAt", claim.createdAt)
        claim.platform?.let { obj.put("platform", it) }
        claim.maturesAt?.let { obj.put("maturesAt", it) }
        return obj.toString()
    }

    /** The claim kept as [text], or null when it does not read back. */
    fun claim(text: String): FriendsClaim? = runCatching { JSONObject(text) }.getOrNull()?.let(::claim)

    // MARK: Pieces

    private fun profile(obj: JSONObject): FriendProfile {
        val id = obj.string("id")?.takeIf(FriendsId::isValid) ?: missing("id")
        return FriendProfile(
            id = id,
            name = FriendsText.trimmed(obj.string("name").orEmpty()),
            avatarRev = (obj.int("avatarRev") ?: 0).coerceAtLeast(0),
            share = obj.obj("share")?.let(::share),
            strapBound = obj.bool("strapBound"),
            device = obj.obj("device")?.let { device ->
                device.string("id")?.let { FriendsDeviceState(it, device.long("probationUntil")) }
            },
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

    /** A claim, or null when it lacks a member or is of a kind or state this app does not know. */
    private fun claim(obj: JSONObject): FriendsClaim? {
        return FriendsClaim(
            id = obj.long("id") ?: return null,
            kind = FriendsClaim.Kind.of(obj.string("kind")) ?: return null,
            code = obj.string("code") ?: return null,
            state = FriendsClaim.State.of(obj.string("state")) ?: return null,
            platform = obj.string("platform"),
            createdAt = obj.long("createdAt") ?: return null,
            maturesAt = obj.long("maturesAt"),
        )
    }

    private fun device(obj: JSONObject): FriendsDevice? {
        return FriendsDevice(
            id = obj.string("id") ?: return null,
            platform = obj.string("platform") ?: return null,
            addedAt = obj.long("addedAt") ?: return null,
            lastSeenAt = obj.long("lastSeenAt") ?: return null,
            probationUntil = obj.long("probationUntil"),
            current = obj.bool("current") ?: return null,
        )
    }

    private fun invite(obj: JSONObject): FriendsInvite? {
        return FriendsInvite(
            id = obj.string("id") ?: return null,
            code = obj.string("code"),
            createdAt = obj.long("createdAt"),
            expiresAt = obj.long("expiresAt") ?: return null,
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
