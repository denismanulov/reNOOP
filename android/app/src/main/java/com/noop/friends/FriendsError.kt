package com.noop.friends

// MARK: - Friends: what can go wrong, as a code the screen turns into words
//
// The server answers an error as {"error": "<code>", "message": "<text>"} with a matching HTTP status.
// The code is what the tab reads; the message is English and is never shown. A status without a known
// code (a proxy's own 502, a captive portal's page) falls back on the status alone.

/** Every failure a Friends call can end in. */
enum class FriendsError {
    /** No connection, a timeout, a TLS failure: nothing reached the server or nothing came back. */
    OFFLINE,

    /** The answer was not what the API documents (not JSON, a member of the wrong kind). */
    BAD_RESPONSE,

    /** The server answered with a redirect. It is never followed, so the token never leaves the host. */
    REDIRECTED,

    /** The session is gone (signed out elsewhere, password changed, account deleted). */
    UNAUTHORIZED,

    /** Wrong nickname or password, or a wrong current password. */
    BAD_CREDENTIALS,
    RATE_LIMITED,
    NICK_TAKEN,
    BAD_NICK,
    BAD_NAME,
    BAD_PASSWORD,
    BAD_INVITE,
    SERVER_FULL,
    NO_SUCH_USER,
    SELF_REQUEST,
    NO_REQUEST,
    TOO_MANY_FRIENDS,
    TOO_MANY_REQUESTS,
    BAD_IMAGE,
    TOO_LARGE,
    NO_AVATAR,

    /** The server refused what this app sent (a day, a field). A bug on one side or a newer server. */
    REJECTED,
    SERVER_ERROR,

    /** No session on this phone, or the saved server address is not one the app will talk to. */
    NOT_SIGNED_IN,
    UNKNOWN;

    companion object {
        private val byCode = mapOf(
            "unauthorized" to UNAUTHORIZED,
            "bad_credentials" to BAD_CREDENTIALS,
            "rate_limited" to RATE_LIMITED,
            "nick_taken" to NICK_TAKEN,
            "bad_nick" to BAD_NICK,
            "bad_name" to BAD_NAME,
            "bad_password" to BAD_PASSWORD,
            "bad_invite" to BAD_INVITE,
            "server_full" to SERVER_FULL,
            "no_such_user" to NO_SUCH_USER,
            "self_request" to SELF_REQUEST,
            "no_request" to NO_REQUEST,
            "too_many_friends" to TOO_MANY_FRIENDS,
            "too_many_requests" to TOO_MANY_REQUESTS,
            "bad_image" to BAD_IMAGE,
            "too_large" to TOO_LARGE,
            "no_avatar" to NO_AVATAR,
            "bad_payload" to REJECTED,
            "bad_json" to REJECTED,
            "bad_day" to REJECTED,
            "bad_query" to REJECTED,
            "bad_length" to REJECTED,
            "length_required" to REJECTED,
            "method_not_allowed" to REJECTED,
            "not_found" to REJECTED,
            "internal" to SERVER_ERROR,
        )

        /**
         * The failure for an answer with [status] (anything outside 200..299) and the server's [code],
         * when its body carried one. The code wins: a 401 is a dead session with `unauthorized` and a
         * wrong password with `bad_credentials`, and only the first one signs the phone out.
         */
        fun of(status: Int, code: String?): FriendsError {
            byCode[code]?.let { return it }
            return when (status) {
                in 300..399 -> REDIRECTED
                401 -> UNAUTHORIZED
                413 -> TOO_LARGE
                429 -> RATE_LIMITED
                in 500..599 -> SERVER_ERROR
                else -> UNKNOWN
            }
        }
    }
}

/** A call's outcome: the value, or why there is none. */
sealed class FriendsResult<out T> {
    data class Ok<T>(val value: T) : FriendsResult<T>()
    data class Fail(val error: FriendsError) : FriendsResult<Nothing>()

    val valueOrNull: T? get() = (this as? Ok)?.value
    val errorOrNull: FriendsError? get() = (this as? Fail)?.error

    inline fun <R> map(transform: (T) -> R): FriendsResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Fail -> this
    }
}
