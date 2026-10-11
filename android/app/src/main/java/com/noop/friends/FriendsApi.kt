package com.noop.friends

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// MARK: - Friends: the HTTP client
//
// One method per call of friends-server/README.md, API version 2. There is no name to type and no
// password: the phone holds a P-256 key ([FriendsSigner]) and signs every request with it, and people are
// known by an opaque account id. Four things hold for every call:
//  - the address was checked by [FriendsServerUrl] before a client exists (HTTPS, or HTTP to loopback);
//  - a redirect is never followed, so a signed request goes to the host the wearer named and nowhere
//    else; a 3xx is reported as a failure;
//  - a body goes out as finished bytes with a Content-Length, never chunked: the server refuses a
//    request with a Transfer-Encoding header (`411 length_required`);
//  - nothing is logged: not a URL, not a body, not a header.
// Answers are read up to a fixed size, so a wrong or hostile host cannot make the app hold a huge body.
// This file is the wire only; what the app does with it lives in [FriendsStore]. Swift twin: `FriendsClient`.

/**
 * The gap between the server's clock and this phone's, learned from a `clock_skew` refusal and applied to
 * every later signature, so a phone whose clock is wrong still signs a time the server accepts.
 */
class FriendsClockOffset {
    private val value = AtomicLong(0)

    var seconds: Long
        get() = value.get()
        set(seconds) = value.set(seconds)

    companion object {
        /** The one the app uses. Set back to zero when another server is named. */
        val shared = FriendsClockOffset()
    }
}

/** What a request is signed with and over: put on a request as its tag, read by [FriendsSigningInterceptor]. */
class FriendsSigned(
    val signer: FriendsSigner,
    /** The path and query exactly as sent, beginning "/v2/": what the server sees behind its proxy. */
    val target: String,
)

/**
 * Signs every request that carries a [FriendsSigned] tag: the four `X-Friends-` headers over the
 * six-line signing string of the contract. A `401 clock_skew` is answered once: the server's clock is
 * taken from the refusal, kept in [offset], and the request signed and sent once more. A second refusal
 * is the answer.
 */
class FriendsSigningInterceptor(
    private val offset: FriendsClockOffset,
    /** The device clock (unix seconds) and the nonce source, replaceable so a test can pin what is signed. */
    private val now: () -> Long,
    private val nonce: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val signed = request.tag(FriendsSigned::class.java) ?: return chain.proceed(request)
        val url = request.url
        val sent = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        // What is signed must be what goes out. An address that was re-encoded on the way is not sent.
        if (!sent.endsWith(signed.target)) throw IOException("could not sign")
        val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()

        val first = chain.proceed(sign(request, signed, body))
        if (first.code != 401) return first
        val serverTime = runCatching { FriendsJson.refusal(first.peekBody(PEEK_BYTES).string()) }.getOrNull()
            ?.takeIf { it.code == "clock_skew" }?.serverTime ?: return first
        first.close()
        // This phone's clock is off. Sign once more by the server's.
        offset.seconds = serverTime - now()
        return chain.proceed(sign(request, signed, body))
    }

    private fun sign(request: Request, signed: FriendsSigned, body: ByteArray): Request {
        val time = now() + offset.seconds
        val nonce = nonce()
        val text = FriendsWire.signingString(request.method, signed.target, time, nonce, FriendsKeys.sha256Hex(body))
        val signature = try {
            signed.signer.sign(text.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            throw IOException("could not sign")
        }
        return request.newBuilder()
            .header("X-Friends-Key", signed.signer.keyId)
            .header("X-Friends-Time", time.toString())
            .header("X-Friends-Nonce", nonce)
            .header("X-Friends-Signature", Base64.getEncoder().encodeToString(signature))
            .build()
    }

    companion object {
        private const val PEEK_BYTES = 64L * 1024

        /** 16 random bytes as base64url without padding: new for every request. */
        fun randomNonce(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

/** The OkHttp client every Friends call goes through, with what its signatures are stamped by. */
class FriendsTransport(
    val offset: FriendsClockOffset = FriendsClockOffset.shared,
    now: () -> Long = { System.currentTimeMillis() / 1000L },
    nonce: () -> String = { FriendsSigningInterceptor.randomNonce() },
) {
    /** No cookies, no cache, no redirects: nothing of an answer outlives the request that carried it. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(FriendsSigningInterceptor(offset, now, nonce))
        .build()

    companion object {
        /** One for the process: its connection pool is what makes a refresh a single handshake. */
        val shared: FriendsTransport by lazy { FriendsTransport() }
    }
}

class FriendsApi private constructor(
    private val base: HttpUrl,
    /** This phone's key. Null only for [info], the one call that is not signed. */
    private val signer: FriendsSigner?,
    private val client: OkHttpClient,
) {
    /** A raw answer inside 200..299. */
    private class Answer(val bytes: ByteArray, val status: Int) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    // MARK: Entering

    suspend fun info(): FriendsResult<FriendsServerInfo> =
        request("GET", "/v2/info", signed = false).parse(FriendsJson::info)

    /**
     * Makes an account for this phone's key, bound to [strap] when one is given. Answers the account the
     * key already has when it has one ([FriendsEnrolment.isNew] false: a `200`, not a `201`), in which
     * case the server took neither the name nor the strap sent. A strap that belongs to another account
     * is [FriendsError.STRAP_BOUND].
     */
    suspend fun enroll(name: String, strap: String?): FriendsResult<FriendsEnrolment> {
        val key = signer ?: return FriendsResult.Fail(FriendsError.NOT_ENROLLED)
        val body = JSONObject().put("key", spki(key)).put("name", name).put("platform", FriendsPlatform.CURRENT)
        // No strap is no member, not a null.
        if (strap != null) body.put("strap", strap)
        return when (val answer = request("POST", "/v2/enroll", json = body.toString())) {
            is FriendsResult.Fail -> answer
            is FriendsResult.Ok -> try {
                FriendsResult.Ok(FriendsEnrolment(FriendsJson.enrolledAccount(answer.value.text), isNew = answer.value.status == 201))
            } catch (_: FriendsParseException) {
                FriendsResult.Fail(FriendsError.BAD_RESPONSE)
            }
        }
    }

    /** Asks to join the account [strap] is bound to. A phone of that account confirms it, or two days of its silence do. */
    suspend fun fileClaim(strap: String): FriendsResult<FriendsClaim> {
        val key = signer ?: return FriendsResult.Fail(FriendsError.NOT_ENROLLED)
        val body = JSONObject().put("key", spki(key)).put("strap", strap).put("platform", FriendsPlatform.CURRENT)
        return request("POST", "/v2/claims", json = body.toString()).parse(FriendsJson::claimAnswer)
    }

    suspend fun myClaim(): FriendsResult<FriendsClaim> = request("GET", "/v2/claims/mine").parse(FriendsJson::claimAnswer)

    suspend fun withdrawClaim(): FriendsResult<Unit> = request("DELETE", "/v2/claims/mine").unit()

    // MARK: Account

    suspend fun me(): FriendsResult<FriendProfile> = request("GET", "/v2/me").parse(FriendsJson::profile)

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
        return request("PATCH", "/v2/me", json = body.toString()).parse(FriendsJson::profile)
    }

    /**
     * Uploads a JPEG picture. One over [FriendsLimits.MAX_AVATAR_BYTES] is refused here, without a
     * request: the server would refuse it before reading it and close the connection mid-upload, which
     * reads as a lost connection rather than as the picture being too large.
     */
    suspend fun putAvatar(jpeg: ByteArray): FriendsResult<FriendProfile> {
        if (jpeg.size > FriendsLimits.MAX_AVATAR_BYTES) return FriendsResult.Fail(FriendsError.TOO_LARGE)
        return request("PUT", "/v2/me/avatar", raw = jpeg, rawType = JPEG).parse(FriendsJson::profile)
    }

    suspend fun deleteAvatar(): FriendsResult<Unit> = request("DELETE", "/v2/me/avatar").unit()

    /** The picture's bytes as the server holds them (JPEG, PNG or WebP). */
    suspend fun avatar(id: String): FriendsResult<ByteArray> {
        if (!FriendsId.isValid(id)) return FriendsResult.Fail(FriendsError.NO_SUCH_USER)
        return request("GET", "/v2/users/$id/avatar", maxBytes = FriendsLimits.MAX_AVATAR_BYTES).map { it.bytes }
    }

    /**
     * Binds the account to the strap now worn. A free strap is bound at once; one that belongs to another
     * account is asked for.
     */
    suspend fun putStrap(handle: String): FriendsResult<FriendsStrapAnswer> =
        request("PUT", "/v2/me/strap", json = JSONObject().put("strap", handle).toString()).parse(FriendsJson::strapAnswer)

    suspend fun approve(claimId: Long): FriendsResult<Unit> = request("POST", "/v2/claims/$claimId/approve").unit()

    suspend fun decline(claimId: Long): FriendsResult<Unit> = request("POST", "/v2/claims/$claimId/decline").unit()

    suspend fun devices(): FriendsResult<List<FriendsDevice>> = request("GET", "/v2/me/devices").parse(FriendsJson::devices)

    suspend fun removeDevice(keyId: String): FriendsResult<Unit> {
        if (!FriendsId.isKeyId(keyId)) return FriendsResult.Fail(FriendsError.NO_SUCH_DEVICE)
        return request("DELETE", "/v2/me/devices/$keyId").unit()
    }

    /** Ends the probation of a phone that joined without confirmation. */
    suspend fun trustDevice(keyId: String): FriendsResult<Unit> {
        if (!FriendsId.isKeyId(keyId)) return FriendsResult.Fail(FriendsError.NO_SUCH_DEVICE)
        return request("POST", "/v2/me/devices/$keyId/trust").unit()
    }

    /** Everything the server holds for the account, as it sent it. */
    suspend fun export(): FriendsResult<String> = request("GET", "/v2/me/export").map { it.text }

    suspend fun deleteAccount(): FriendsResult<Unit> = request("POST", "/v2/me/delete").unit()

    // MARK: Days

    /** Replaces the day [day] ("yyyy-MM-dd") with [json], a [FriendsDayPayload.toJson] text. */
    suspend fun putDay(day: String, json: String): FriendsResult<Unit> {
        if (!DAY_KEY.matches(day)) return FriendsResult.Fail(FriendsError.REJECTED)
        return request("PUT", "/v2/me/days/$day", json = json).unit()
    }

    /** The feed and the text it was read from, which is what the tab keeps for an instant or offline start. */
    suspend fun feed(days: Int = FriendsLimits.FEED_DAYS): FriendsResult<Pair<FriendsFeed, String>> =
        request("GET", "/v2/feed?days=$days").parse { FriendsJson.feed(it) to it }

    /** One person in full, the heart-rate line included: what their own page draws. */
    suspend fun userDays(id: String, days: Int = FriendsLimits.FEED_DAYS): FriendsResult<FriendPerson> {
        if (!FriendsId.isValid(id)) return FriendsResult.Fail(FriendsError.NO_SUCH_USER)
        return request("GET", "/v2/users/$id/days?days=$days").parse(FriendsJson::person)
    }

    // MARK: Friends

    /** Makes a one-time invite. Its code is in this answer and nowhere else. */
    suspend fun createInvite(): FriendsResult<FriendsInvite> = request("POST", "/v2/invites").parse(FriendsJson::invite)

    suspend fun invites(): FriendsResult<List<FriendsInvite>> = request("GET", "/v2/invites").parse(FriendsJson::invites)

    suspend fun revokeInvite(id: String): FriendsResult<Unit> {
        if (!FriendsId.isValid(id)) return FriendsResult.Fail(FriendsError.NO_SUCH_INVITE)
        return request("DELETE", "/v2/invites/$id").unit()
    }

    /** Uses an invite code (its stored form): the two accounts are friends at once. */
    suspend fun redeem(code: String): FriendsResult<FriendProfile> =
        request("POST", "/v2/invites/redeem", json = JSONObject().put("code", code).toString()).parse(FriendsJson::redeemedFriend)

    suspend fun unfriend(id: String): FriendsResult<Unit> {
        if (!FriendsId.isValid(id)) return FriendsResult.Fail(FriendsError.NO_SUCH_USER)
        return request("DELETE", "/v2/friends/$id").unit()
    }

    // MARK: Transport

    private fun spki(key: FriendsSigner): String = Base64.getEncoder().encodeToString(key.publicKeySpki)

    /**
     * One request. [target] is the path and query as the server sees them, beginning "/v2/"; under a
     * server mounted at a prefix by its proxy the request goes to the prefix and the signature covers
     * the target without it.
     */
    private suspend fun request(
        method: String,
        target: String,
        json: String? = null,
        raw: ByteArray? = null,
        rawType: MediaType? = null,
        signed: Boolean = true,
        maxBytes: Int = FriendsLimits.MAX_ANSWER_BYTES,
    ): FriendsResult<Answer> {
        val key = signer
        if (signed && key == null) return FriendsResult.Fail(FriendsError.NOT_ENROLLED)
        val url = (base.toString().trimEnd('/') + target).toHttpUrlOrNull() ?: return FriendsResult.Fail(FriendsError.NOT_CONFIGURED)
        // Finished bytes every time, so the length is known and nothing is sent chunked.
        val body: RequestBody? = when {
            json != null -> json.toByteArray(Charsets.UTF_8).toRequestBody(JSON)
            raw != null -> raw.toRequestBody(rawType)
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val built = Request.Builder().url(url).header("Accept", "application/json").method(method, body).apply {
            if (signed && key != null) tag(FriendsSigned::class.java, FriendsSigned(key, target))
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
                    bytes.size > maxBytes -> FriendsResult.Fail(FriendsError.BAD_RESPONSE)
                    response.code !in 200..299 ->
                        FriendsResult.Fail(FriendsError.of(response.code, FriendsJson.refusal(bytes.toString(Charsets.UTF_8)).code))
                    else -> FriendsResult.Ok(Answer(bytes, response.code))
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A request cancelled with the work that made it (a page left) did not fail: it stays
            // recognisable, so that nothing reports it as a server that did not answer.
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
        private val DAY_KEY = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /**
         * A client for the server at [serverUrl] signing with [signer] (null before Friends is on, when
         * only [info] can be asked), or null when the address is not one [FriendsServerUrl] accepts. No
         * request is made here.
         */
        fun create(serverUrl: String, signer: FriendsSigner?, transport: FriendsTransport = FriendsTransport.shared): FriendsApi? {
            val normalised = FriendsServerUrl.normalised(serverUrl) ?: return null
            val base = normalised.toHttpUrlOrNull() ?: return null
            return FriendsApi(base, signer, transport.client)
        }
    }
}

/** Sizes the client holds itself and the server to. */
object FriendsLimits {
    /** Days of history the tab asks for: the week the charts draw. */
    const val FEED_DAYS = 7

    /** The server refuses a larger picture. */
    const val MAX_AVATAR_BYTES = 200 * 1024

    /** The largest answer read: a hundred friends' fortnight is well under this. */
    const val MAX_ANSWER_BYTES = 4 * 1024 * 1024

    /** A name is 1 to 40 characters. */
    const val NAME_MAX = 40
}
