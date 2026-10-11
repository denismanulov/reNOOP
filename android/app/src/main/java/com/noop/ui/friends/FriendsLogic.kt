package com.noop.ui.friends

import com.noop.friends.FriendDay
import com.noop.friends.FriendSleep
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.noop.friends.FriendsInviteCode
import com.noop.ui.workouts.effortLoadIndex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// MARK: - Friends tab: what the screens decide without a screen
//
// What a person's page works out from two days: how long ago something was, which of two people leads
// on a score, a score's level, a night against its need. The list's order, the highlights and the week
// are in `FriendsBoard.kt`. No Android and no Compose, so the JVM tests pin each rule; the screens only
// turn the results into words.
//
// One clock runs through all of it: the server's. A day's `updatedAt` and the feed's `serverTime` are
// both the server's seconds, so "6 min ago" is their difference carried forward by how long ago the feed
// arrived, and a phone whose own clock is wrong still reads the right age.

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

// MARK: A person

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
