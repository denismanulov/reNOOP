package com.noop.friends

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// MARK: - Friends: the HTTP client
//
// One method per endpoint of friends-server/README.md. Three things hold for every call:
//  - the address was checked by [FriendsServerUrl] before a client exists (HTTPS, or HTTP to loopback);
//  - a redirect is never followed, so the bearer token and a password in a body go to the host the
//    wearer named and nowhere else; a 3xx is reported as a failure;
//  - nothing is logged: not a URL, not a body, not a header.
// Answers are read up to a fixed size, so a wrong or hostile host cannot make the app hold a huge body.

class FriendsApi private constructor(
    private val base: HttpUrl,
    private val token: String?,
    private val client: OkHttpClient,
) {
    /** A raw answer inside 200..299. */
    private class Answer(val bytes: ByteArray, val contentType: String?) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    // MARK: Without a session

    suspend fun info(): FriendsResult<FriendsServerInfo> =
        request("GET", listOf("v1", "info"), auth = false).parse(FriendsJson::info)

    /** Whether [nick] (already cleaned) is free to sign up with. */
    suspend fun nickFree(nick: String): FriendsResult<Boolean> =
        request("GET", listOf("v1", "nicks", nick), auth = false).parse(FriendsJson::nickFree)

    suspend fun register(nick: String, password: String, invite: String?): FriendsResult<FriendsSession> {
        val body = JSONObject().put("nick", nick).put("password", password)
        if (!invite.isNullOrEmpty()) body.put("invite", invite)
        return request("POST", listOf("v1", "register"), json = body.toString(), auth = false).parse(FriendsJson::session)
    }

    suspend fun login(nick: String, password: String): FriendsResult<FriendsSession> {
        val body = JSONObject().put("nick", nick).put("password", password)
        return request("POST", listOf("v1", "login"), json = body.toString(), auth = false).parse(FriendsJson::session)
    }

    // MARK: The signed-in account

    suspend fun logout(): FriendsResult<Unit> = request("DELETE", listOf("v1", "session")).unit()

    suspend fun me(): FriendsResult<FriendProfile> = request("GET", listOf("v1", "me")).parse(FriendsJson::profile)

    /** Changes the display name and / or any of the sharing switches; only what is given is sent. */
    suspend fun patchMe(name: String? = null, share: FriendShare? = null): FriendsResult<FriendProfile> {
        val body = JSONObject()
        if (name != null) body.put("name", name)
        if (share != null) {
            body.put(
                "share",
                JSONObject().put("scores", share.scores).put("sleep", share.sleep)
                    .put("workouts", share.workouts).put("hr", share.hr),
            )
        }
        return request("PATCH", listOf("v1", "me"), json = body.toString()).parse(FriendsJson::profile)
    }

    /** The new session token; the server ends every other session of the account. */
    suspend fun changePassword(old: String, new: String): FriendsResult<String> {
        val body = JSONObject().put("old", old).put("new", new)
        return request("POST", listOf("v1", "me", "password"), json = body.toString()).parse(FriendsJson::token)
    }

    suspend fun deleteAccount(password: String): FriendsResult<Unit> {
        val body = JSONObject().put("password", password)
        return request("POST", listOf("v1", "me", "delete"), json = body.toString()).unit()
    }

    /**
     * Uploads a JPEG picture. One over [FriendsLimits.MAX_AVATAR_BYTES] is refused here, without a
     * request: the server would refuse it before reading it and close the connection mid-upload, which
     * reads as a lost connection rather than as the picture being too large.
     */
    suspend fun putAvatar(jpeg: ByteArray): FriendsResult<FriendProfile> {
        if (jpeg.size > FriendsLimits.MAX_AVATAR_BYTES) return FriendsResult.Fail(FriendsError.TOO_LARGE)
        return request("PUT", listOf("v1", "me", "avatar"), raw = jpeg, rawType = JPEG).parse(FriendsJson::profile)
    }

    suspend fun deleteAvatar(): FriendsResult<Unit> = request("DELETE", listOf("v1", "me", "avatar")).unit()

    /** Replaces the day [day] ("yyyy-MM-dd") with [json], a [FriendsDayPayload.toJson] text. */
    suspend fun putDay(day: String, json: String): FriendsResult<Unit> =
        request("PUT", listOf("v1", "me", "days", day), json = json).unit()

    // MARK: Friends

    /** The feed and the text it was read from, which is what the tab keeps for an instant or offline start. */
    suspend fun feed(days: Int = FriendsLimits.FEED_DAYS): FriendsResult<Pair<FriendsFeed, String>> =
        request("GET", listOf("v1", "feed"), query = "days" to days.toString()).parse { FriendsJson.feed(it) to it }

    /** One friend (or the signed-in account) in full, the day's heart-rate line included. */
    suspend fun userDays(nick: String, days: Int = FriendsLimits.FEED_DAYS): FriendsResult<FriendPerson> =
        request("GET", listOf("v1", "users", nick, "days"), query = "days" to days.toString())
            .parse(FriendsJson::person)

    /** Exact-nickname lookup. */
    suspend fun user(nick: String): FriendsResult<FriendProfile> =
        request("GET", listOf("v1", "users", nick)).parse(FriendsJson::profile)

    /** The picture's bytes as the server holds them (JPEG, PNG or WebP). */
    suspend fun avatar(nick: String): FriendsResult<ByteArray> =
        request("GET", listOf("v1", "users", nick, "avatar"), maxBytes = FriendsLimits.MAX_AVATAR_BYTES).map { it.bytes }

    suspend fun requests(): FriendsResult<FriendRequests> =
        request("GET", listOf("v1", "friends", "requests")).parse(FriendsJson::requests)

    /** Sends a request; answering someone who already asked is an acceptance (`relation: friend`). */
    suspend fun sendRequest(nick: String): FriendsResult<FriendProfile> =
        request("POST", listOf("v1", "friends", "requests"), json = JSONObject().put("nick", nick).toString())
            .parse(FriendsJson::profile)

    suspend fun acceptRequest(nick: String): FriendsResult<FriendProfile> =
        request("POST", listOf("v1", "friends", "requests", nick, "accept")).parse(FriendsJson::profile)

    /** Declines an incoming request or withdraws an outgoing one. */
    suspend fun deleteRequest(nick: String): FriendsResult<Unit> =
        request("DELETE", listOf("v1", "friends", "requests", nick)).unit()

    suspend fun unfriend(nick: String): FriendsResult<Unit> = request("DELETE", listOf("v1", "friends", nick)).unit()

    // MARK: Transport

    private suspend fun request(
        method: String,
        segments: List<String>,
        query: Pair<String, String>? = null,
        json: String? = null,
        raw: ByteArray? = null,
        rawType: MediaType? = null,
        auth: Boolean = true,
        maxBytes: Int = FriendsLimits.MAX_ANSWER_BYTES,
    ): FriendsResult<Answer> {
        if (auth && token.isNullOrEmpty()) return FriendsResult.Fail(FriendsError.NOT_SIGNED_IN)
        val url = base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            if (query != null) addQueryParameter(query.first, query.second)
        }.build()
        val body: RequestBody? = when {
            json != null -> json.toByteArray(Charsets.UTF_8).toRequestBody(JSON)
            raw != null -> raw.toRequestBody(rawType)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val built = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (auth) header("Authorization", "Bearer $token")
            when (method) {
                "GET" -> get()
                "DELETE" -> delete()
                else -> method(method, body)
            }
        }.build()
        return try {
            client.newCall(built).await().use { response ->
                // One byte past the limit is read on purpose: it is how an over-long answer is told apart.
                val bytes = response.body?.byteStream()?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (out.size() <= maxBytes) {
                        val read = input.read(chunk, 0, minOf(chunk.size, maxBytes + 1 - out.size()))
                        if (read < 0) break
                        out.write(chunk, 0, read)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                when {
                    response.code !in 200..299 ->
                        FriendsResult.Fail(FriendsError.of(response.code, FriendsJson.errorCode(bytes.toString(Charsets.UTF_8))))
                    bytes.size > maxBytes -> FriendsResult.Fail(FriendsError.BAD_RESPONSE)
                    else -> FriendsResult.Ok(Answer(bytes, response.header("Content-Type")))
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            FriendsResult.Fail(FriendsError.OFFLINE)
        }
    }

    private inline fun <T> FriendsResult<Answer>.parse(read: (String) -> T): FriendsResult<T> = when (this) {
        is FriendsResult.Fail -> this
        is FriendsResult.Ok -> try {
            FriendsResult.Ok(read(value.text))
        } catch (_: FriendsParseException) {
            FriendsResult.Fail(FriendsError.BAD_RESPONSE)
        }
    }

    private fun FriendsResult<Answer>.unit(): FriendsResult<Unit> = map { }

    /** Cancelling the caller cancels the socket instead of waiting out the timeout. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { response.close() }
                } else {
                    response.close()
                }
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()

        /** One client for the process: its connection pool is what makes a refresh a single handshake. */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * A client for the server at [serverUrl] acting with [token] (null before sign-in), or null when
         * the address is not one [FriendsServerUrl] accepts. No request is made here.
         */
        fun create(serverUrl: String, token: String?): FriendsApi? {
            val normalised = FriendsServerUrl.normalised(serverUrl) ?: return null
            val base = normalised.toHttpUrlOrNull() ?: return null
            return FriendsApi(base, token, sharedClient)
        }
    }
}

/** Sizes the client holds itself and the server to. */
object FriendsLimits {
    /** Days of history the tab asks for: the week the charts draw. */
    const val FEED_DAYS = 7

    /** The server refuses a larger picture. */
    const val MAX_AVATAR_BYTES = 200 * 1024

    /** The largest JSON answer read: a hundred friends' fortnight is well under this. */
    const val MAX_ANSWER_BYTES = 4 * 1024 * 1024
}
