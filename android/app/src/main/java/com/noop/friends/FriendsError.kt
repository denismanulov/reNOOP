package com.noop.friends

// MARK: - Friends: what can go wrong, as a code the screen turns into words
//
// The server answers an error as {"error": "<code>", "message": "<text>"} with a matching HTTP status
// (friends-server/README.md, "Errors"). The code is what the tab reads; the message is English and is
// never shown. A status without a known code (a proxy's own 502, a captive portal's page) falls back on
// the status alone. The rest of the list is what goes wrong on the phone itself, before any request.

/** Every failure a Friends call or step can end in. */
enum class FriendsError {
    // --- Nothing came back, or not what the contract says ---

    /** No connection, a timeout, a TLS failure: nothing reached the server or nothing came back. */
    OFFLINE,

    /** The answer was not what the API documents (not JSON, a member of the wrong kind, too long). */
    BAD_RESPONSE,

    /** The server answered with a redirect. It is never followed, so a signed request goes to the host named only. */
    REDIRECTED,

    // --- The server's own refusals ---

    /** The server does not know this phone's key: it was removed from the account, or the account is gone. */
    UNKNOWN_KEY,

    /** This phone's clock is too far from the server's, and signing once more by the server's did not help. */
    CLOCK_SKEW,

    /** This phone joined without confirmation and may only read and upload for now. */
    PROBATION,

    /** The strap already belongs to an account. */
    STRAP_BOUND,

    /** No account holds the strap a claim was filed for. */
    STRAP_FREE,
    NO_CLAIM,
    CLAIM_SETTLED,

    /** This key is already a phone of an account, so it has nothing to claim. */
    ALREADY_ENROLLED,
    CLAIM_DECLINED,
    TOO_MANY_CLAIMS,
    TOO_MANY_DEVICES,
    LAST_DEVICE,
    NO_SUCH_DEVICE,
    NO_SUCH_INVITE,
    OWN_INVITE,
    TOO_MANY_INVITES,
    TOO_MANY_FRIENDS,
    NO_SUCH_USER,
    BAD_NAME,
    SERVER_FULL,
    BAD_IMAGE,
    TOO_LARGE,
    NO_AVATAR,
    RATE_LIMITED,

    /** The server refused what this app sent (a day, a field, a signature). A bug on one side or a newer server. */
    REJECTED,
    SERVER_ERROR,

    // --- On this phone ---

    /** A call that needs this phone's key was made with none: Friends is off. Nothing was sent. */
    NOT_ENROLLED,

    /** The saved server address is not one the app will talk to. */
    NOT_CONFIGURED,

    /** The key was made but could not be kept, so it would not survive a relaunch. */
    KEY_NOT_KEPT,

    /** A key may be kept but could not be read just now, so none may be made in its place. */
    KEY_NOT_READ,

    /** Turn On was tapped with no name in the profile. */
    NO_NAME,

    /** "This is my account" needs the strap's handle, and the strap has not been read. */
    NO_STRAP,

    /** The request to join was declined on the other phone. */
    CLAIM_WAS_DECLINED,

    /** The request to join expired unanswered. */
    CLAIM_EXPIRED,

    /** What was typed or pasted carries no invite code. */
    NOT_A_CODE,

    /** The server stopped knowing this phone, so Friends went off on it. */
    LEFT_ACCOUNT,
    UNKNOWN;

    /**
     * No answer, or none the contract describes: worth another try later, unlike a refusal, and the one
     * case in which a request that was sent may or may not have been carried out.
     */
    val isUnanswered: Boolean get() = this == OFFLINE || this == BAD_RESPONSE

    companion object {
        /** Codes that mean what they say only with their own status. */
        private val strict = mapOf(
            "unknown_key" to (401 to UNKNOWN_KEY),
            "strap_bound" to (409 to STRAP_BOUND),
            "probation" to (403 to PROBATION),
            "no_claim" to (404 to NO_CLAIM),
            "strap_free" to (404 to STRAP_FREE),
            "already_enrolled" to (409 to ALREADY_ENROLLED),
        )

        private val byCode = mapOf(
            "clock_skew" to CLOCK_SKEW,
            "claim_settled" to CLAIM_SETTLED,
            "claim_declined" to CLAIM_DECLINED,
            "too_many_claims" to TOO_MANY_CLAIMS,
            "too_many_devices" to TOO_MANY_DEVICES,
            "last_device" to LAST_DEVICE,
            "no_such_device" to NO_SUCH_DEVICE,
            "no_such_invite" to NO_SUCH_INVITE,
            "own_invite" to OWN_INVITE,
            "too_many_invites" to TOO_MANY_INVITES,
            "too_many_friends" to TOO_MANY_FRIENDS,
            "no_such_user" to NO_SUCH_USER,
            "bad_name" to BAD_NAME,
            "server_full" to SERVER_FULL,
            "bad_image" to BAD_IMAGE,
            "too_large" to TOO_LARGE,
            "no_avatar" to NO_AVATAR,
            "rate_limited" to RATE_LIMITED,
            "unsigned" to REJECTED,
            "replayed" to REJECTED,
            "bad_signature" to REJECTED,
            "bad_key" to REJECTED,
            "bad_json" to REJECTED,
            "bad_payload" to REJECTED,
            "bad_platform" to REJECTED,
            "bad_strap" to REJECTED,
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
         * when its body carried one. The code wins over the status, except for the six refusals the
         * client acts on by itself: `unknown_key` turns Friends off on this phone, so it is believed only
         * as the `401` the contract sends it with.
         */
        fun of(status: Int, code: String?): FriendsError {
            strict[code]?.let { (expected, error) -> if (status == expected) return error }
            byCode[code]?.let { return it }
            return when (status) {
                in 300..399 -> REDIRECTED
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
