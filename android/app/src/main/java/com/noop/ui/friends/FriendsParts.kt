package com.noop.ui.friends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.friends.FriendDay
import com.noop.friends.FriendHrPoint
import com.noop.friends.FriendsAvatars
import com.noop.friends.FriendsClaim
import com.noop.friends.FriendsDevice
import com.noop.friends.FriendsError
import com.noop.friends.FriendsPlatform
import com.noop.ui.AppToday
import com.noop.ui.ClockPrefs
import com.noop.ui.EffortScale
import com.noop.ui.UnitFormatter
import com.noop.ui.m3.ActivityRings
import com.noop.ui.m3.CookieShape
import com.noop.ui.m3.ExpressiveBar
import com.noop.ui.m3.Health
import com.noop.ui.m3.LevelBadge
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.ringFraction
import com.noop.ui.metric.MetricDateLabels
import com.noop.ui.metric.MetricHealthStyle
import com.noop.ui.sleep.clockLabel
import com.noop.ui.sleep.scoreWord
import kotlinx.coroutines.delay
import java.util.Locale

// MARK: - Friends tab: the pieces its screens share
//
// The avatar on its cookie, a day's three rings, a figure with its unit, the mirrored bars of the
// head-to-head card, the day's heart-rate line, and the words every screen needs (an age, a level, a figure on the reader's
// Strain scale, a failure). Colours are the scheme's and [Health.colors]; sizes are type roles.

// MARK: Time

/** The device clock in unix seconds, renewed twice a minute so an "N min ago" keeps counting on screen. */
@Composable
internal fun rememberNowSec(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis() / 1000L
        }
    }
    return now
}

/** "just now", "6 min ago", "2 h ago", "3 d ago". */
@Composable
internal fun agoText(ago: FriendsAgo): String = when (ago) {
    FriendsAgo.JustNow -> stringResource(R.string.friends_ago_now)
    is FriendsAgo.Minutes -> stringResource(R.string.friends_ago_min, ago.count.toInt())
    is FriendsAgo.Hours -> stringResource(R.string.friends_ago_hours, ago.count.toInt())
    is FriendsAgo.Days -> stringResource(R.string.friends_ago_days, ago.count.toInt())
}

/** A clock time in the reader's 12 / 24-hour form. */
@Composable
internal fun friendsClock(ts: Long): String {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val is24h = remember { ClockPrefs.uses24Hour(context) }
    return clockLabel(ts, is24h, locale)
}

/**
 * How a day is named: "Today", "Yesterday", the weekday for a day of the past week when [weekdays] is
 * asked for (a person's page dates a workout so), else the short date the rest of the app stamps a day
 * with. A key that is not a date is shown as it came.
 */
@Composable
internal fun friendsDayText(dayKey: String, today: AppToday, weekdays: Boolean): String {
    val locale = friendsLocale()
    return when (val name = FriendsDayName.of(dayKey, today.date, weekdays)) {
        null -> dayKey
        FriendsDayName.Today -> stringResource(R.string.metric_today)
        FriendsDayName.Yesterday -> stringResource(R.string.metric_yesterday)
        is FriendsDayName.Weekday -> friendsWeekdayName(name.day, locale)
        is FriendsDayName.OnDate -> MetricDateLabels.shortDate(dayKey, locale)
    }
}

// MARK: Scores in words

/**
 * Recovery's word: the state word the Recovery metric page gives the same score (depleted, low, moderate,
 * primed, peak), written the way that page writes it in a sentence, so one score never carries two words.
 */
@Composable
internal fun recoveryLevelText(recovery: Int): String {
    val locale = friendsLocale()
    return stringResource(MetricHealthStyle.chargeStateRes(recovery.toDouble()))
        .lowercase(locale).replaceFirstChar { it.titlecase(locale) }
}

/** The Strain word, by the Workouts tab's own five levels. */
@Composable
internal fun strainLevelText(strain: Double): String = stringResource(
    when (friendsStrainLevel(strain)) {
        0 -> R.string.workouts_load_light
        1 -> R.string.workouts_load_moderate
        2 -> R.string.workouts_load_strenuous
        3 -> R.string.workouts_load_high
        else -> R.string.workouts_load_all_out
    },
)

@Composable
internal fun sleepLevelText(score: Int): String = scoreWord(friendsSleepLevel(score))

/** A stored 0–100 Strain on the reader's own scale ("12.4" of 21, "59.0" of 100), to one decimal and
 *  with the decimal mark of the app's language, as the Summary writes the wearer's own Strain. */
@Composable
internal fun strainShown(strain: Double, scale: EffortScale): String =
    String.format(friendsLocale(), "%.1f", UnitFormatter.effortValue(strain, scale))

/** "of 21" / "of 100" for the reader's Strain scale. */
@Composable
internal fun strainOutOf(scale: EffortScale): String =
    stringResource(R.string.friends_out_of, UnitFormatter.effortScaleMax(scale))

// MARK: Failures in words

/** The sentence for a failure, in the reader's language. The server's own English message is never shown. */
@Composable
internal fun friendsErrorText(error: FriendsError): String = stringResource(
    when (error) {
        FriendsError.OFFLINE -> R.string.friends_error_offline
        FriendsError.RATE_LIMITED -> R.string.friends_error_rate_limited
        FriendsError.REDIRECTED, FriendsError.BAD_RESPONSE -> R.string.friends_error_not_a_server
        FriendsError.UNKNOWN_KEY, FriendsError.LEFT_ACCOUNT -> R.string.friends_error_left_account
        FriendsError.CLOCK_SKEW -> R.string.friends_error_clock
        FriendsError.PROBATION -> R.string.friends_error_probation
        FriendsError.STRAP_BOUND -> R.string.friends_error_strap_bound
        FriendsError.CLAIM_DECLINED -> R.string.friends_error_claim_declined
        FriendsError.TOO_MANY_CLAIMS -> R.string.friends_error_too_many_claims
        FriendsError.TOO_MANY_DEVICES -> R.string.friends_error_too_many_devices
        FriendsError.LAST_DEVICE -> R.string.friends_error_last_device
        FriendsError.NO_CLAIM, FriendsError.CLAIM_SETTLED -> R.string.friends_error_no_claim
        FriendsError.NO_SUCH_INVITE -> R.string.friends_error_no_such_invite
        FriendsError.OWN_INVITE -> R.string.friends_error_own_invite
        FriendsError.TOO_MANY_INVITES -> R.string.friends_error_too_many_invites
        FriendsError.TOO_MANY_FRIENDS -> R.string.friends_error_too_many_friends
        FriendsError.NO_SUCH_USER -> R.string.friends_error_no_such_user
        FriendsError.BAD_NAME -> R.string.friends_error_bad_name
        FriendsError.SERVER_FULL -> R.string.friends_error_server_full
        FriendsError.BAD_IMAGE, FriendsError.TOO_LARGE -> R.string.friends_error_bad_image
        FriendsError.SERVER_ERROR -> R.string.friends_error_server
        FriendsError.NOT_ENROLLED -> R.string.friends_error_off
        FriendsError.NOT_CONFIGURED -> R.string.friends_error_bad_address
        FriendsError.KEY_NOT_KEPT -> R.string.friends_error_key_not_kept
        FriendsError.KEY_NOT_READ -> R.string.friends_error_key_not_read
        FriendsError.NO_NAME -> R.string.friends_error_no_name
        FriendsError.NO_STRAP -> R.string.friends_error_no_strap
        FriendsError.CLAIM_WAS_DECLINED -> R.string.friends_error_claim_was_declined
        FriendsError.CLAIM_EXPIRED -> R.string.friends_error_claim_expired
        FriendsError.NOT_A_CODE -> R.string.friends_error_not_a_code
        FriendsError.STRAP_FREE, FriendsError.ALREADY_ENROLLED, FriendsError.NO_SUCH_DEVICE, FriendsError.NO_AVATAR,
        FriendsError.REJECTED,
        -> R.string.friends_error_refused
        FriendsError.UNKNOWN -> R.string.friends_error_unknown
    },
)

// MARK: Requests in words

/**
 * What a phone is called by its platform. One the app does not know reads as a phone. The two product
 * names are the same in every language, so they are not strings to translate.
 */
@Composable
internal fun friendsDeviceText(platform: String?): String = when (platform) {
    FriendsPlatform.IOS -> FriendsPlatform.IPHONE_NAME
    FriendsPlatform.MAC -> FriendsPlatform.MAC_NAME
    FriendsPlatform.CURRENT -> stringResource(R.string.friends_device_android)
    else -> stringResource(R.string.friends_device_phone)
}

@Composable
internal fun friendsClaimTitle(claim: FriendsClaim): String = stringResource(
    if (claim.kind == FriendsClaim.Kind.JOIN) R.string.friends_request_join_title else R.string.friends_request_take_title,
)

/** A request to join carries the code both phones show; a request for the strap has none to compare. */
@Composable
internal fun friendsClaimDetail(claim: FriendsClaim): String =
    if (claim.kind == FriendsClaim.Kind.JOIN) {
        stringResource(R.string.friends_request_join_detail, friendsDeviceText(claim.platform), spacedCode(claim.code))
    } else {
        stringResource(R.string.friends_request_take_detail)
    }

/** "Android phone, joined 2 h ago. Keep it only if it is yours." Ages count from [serverNow], the server's clock. */
@Composable
internal fun friendsJoinedText(phone: FriendsDevice, serverNow: Long): String =
    stringResource(R.string.friends_unconfirmed_detail, friendsDeviceText(phone.platform), agoText(FriendsAgo.of(phone.addedAt, serverNow) ?: FriendsAgo.JustNow))

/** "Joined 3 d ago · last seen 5 min ago". */
@Composable
internal fun friendsSeenText(phone: FriendsDevice, serverNow: Long): String = stringResource(
    R.string.friends_phone_seen,
    agoText(FriendsAgo.of(phone.addedAt, serverNow) ?: FriendsAgo.JustNow),
    agoText(FriendsAgo.of(phone.lastSeenAt, serverNow) ?: FriendsAgo.JustNow),
)

/**
 * The notice a screen shows above its content when the last refresh failed: what went wrong and, when an
 * older answer is still on screen, how old it is. Offline and rate-limited are information, not errors.
 */
@Composable
internal fun FriendsRefreshNotice(
    error: FriendsError,
    shownAge: FriendsAgo?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val quiet = error == FriendsError.OFFLINE || error == FriendsError.RATE_LIMITED
    NoticeCard(
        icon = when (error) {
            FriendsError.OFFLINE -> Icons.Filled.CloudOff
            FriendsError.RATE_LIMITED -> Icons.Filled.HourglassEmpty
            else -> Icons.Filled.ErrorOutline
        },
        title = friendsErrorText(error),
        message = shownAge?.let { stringResource(R.string.friends_showing_saved, agoText(it)) },
        error = !quiet,
        action = if (onRetry != null) stringResource(R.string.friends_retry) else null,
        onAction = onRetry,
        modifier = modifier,
    )
}

// MARK: Avatar

/** Which container an avatar without a picture sits on. */
internal enum class AvatarTone { ME, FRIEND, QUIET }

/**
 * A person's picture on the Expressive cookie, or their initial when they have none (or it has not loaded
 * yet). The picture is fetched once per revision and kept on disk; see [FriendsAvatars].
 */
@Composable
internal fun FriendAvatar(
    name: String,
    id: String,
    avatarRev: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    tone: AvatarTone = AvatarTone.FRIEND,
) {
    val context = LocalContext.current
    val picture by produceState(initialValue = FriendsAvatars.cached(id, avatarRev), id, avatarRev) {
        // Reset first: a reused slot must not keep showing the person it drew before.
        value = FriendsAvatars.cached(id, avatarRev)
        value = FriendsAvatars.load(context, id, avatarRev)
    }
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        AvatarTone.ME -> scheme.primaryContainer to scheme.onPrimaryContainer
        AvatarTone.FRIEND -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        AvatarTone.QUIET -> scheme.surfaceContainerHighest to scheme.onSurface
    }
    Box(
        modifier = modifier.size(size).clip(CookieShape).background(container).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val shown = picture
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val style = when {
                size >= 72.dp -> MaterialTheme.typography.headlineLarge
                size >= 48.dp -> MaterialTheme.typography.titleLarge
                size >= 40.dp -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.labelLarge
            }
            Text(friendsInitial(name), style = style.copy(fontWeight = FontWeight.SemiBold), color = content, maxLines = 1)
        }
    }
}

// MARK: The head-to-head bars

/** One side of a duel: its figure, how full its bar is, and its level word. All null when there is no figure. */
internal class DuelSide(val value: String?, val fraction: Float?, val level: String?)

/**
 * One score for two people: the name of the score, then two thick bars that grow outward from the middle,
 * the reader's on the left and the friend's on the right, each with its figure at the outer end and its
 * level under it. The higher side's level is written on the score's hue. A side with no figure shows a
 * dash, an empty track and [missing] in place of a level.
 */
@Composable
internal fun ScoreDuel(
    title: String,
    icon: ImageVector,
    tint: Color,
    left: DuelSide,
    right: DuelSide,
    leftLeads: Boolean?,
    missing: String,
    description: String,
    modifier: Modifier = Modifier,
    centre: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = tint)
        }
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DuelFigure(left.value, TextAlign.Start)
            // The left bar is the same indicator mirrored, so both grow away from the centre line.
            ExpressiveBar(left.fraction ?: 0f, tint, Modifier.weight(1f).scale(scaleX = -1f, scaleY = 1f), height = 16.dp)
            ExpressiveBar(right.fraction ?: 0f, tint, Modifier.weight(1f), height = 16.dp)
            DuelFigure(right.value, TextAlign.End)
        }
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DuelLevel(left.level, missing, tint, strong = leftLeads == true)
            Text(
                centre.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1,
            )
            DuelLevel(right.level, missing, tint, strong = leftLeads == false)
        }
    }
}

@Composable
private fun DuelFigure(value: String?, align: TextAlign) {
    Text(
        value ?: stringResource(R.string.friends_no_value),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.widthIn(min = 52.dp),
    )
}

@Composable
private fun DuelLevel(level: String?, missing: String, tint: Color, strong: Boolean) {
    if (level == null) {
        Text(missing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    } else if (strong) {
        LevelBadge(level, fill = tint)
    } else {
        LevelBadge(level, fill = MaterialTheme.colorScheme.surfaceContainerHighest, ink = MaterialTheme.colorScheme.onSurface)
    }
}

// MARK: Rings and figures

/**
 * A day's three scores as the Summary's rings, outer to inner Recovery, Strain and Sleep. A score the
 * day does not carry is an empty ring, and a day that is not there is three of them. Decoration: the
 * figures the rings stand for are written beside them, so TalkBack passes the rings by.
 */
@Composable
internal fun FriendRings(day: FriendDay?, size: Dp, stroke: Dp, gap: Dp, modifier: Modifier = Modifier) {
    ActivityRings(
        charge = ringFraction(day?.recovery?.toDouble(), 100.0),
        effort = ringFraction(day?.strain, 100.0),
        rest = ringFraction(day?.sleepScore?.toDouble(), 100.0),
        modifier = modifier.size(size).clearAndSetSemantics {},
        stroke = stroke,
        gap = gap,
    )
}

/**
 * A figure as a card writes one: the number large, in [color], and its unit straight after it, smaller.
 * [spoken] is what TalkBack says in its place ("Recovery 73%").
 */
@Composable
internal fun FriendsFigure(number: String, unit: String?, color: Color, spoken: String?, modifier: Modifier = Modifier) {
    val numberStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val unitStyle = MaterialTheme.typography.titleMedium
    val text = buildAnnotatedString {
        append(number)
        if (!unit.isNullOrEmpty()) {
            withStyle(SpanStyle(fontSize = unitStyle.fontSize, fontWeight = unitStyle.fontWeight)) { append(" $unit") }
        }
    }
    Text(
        text,
        style = numberStyle,
        color = color,
        maxLines = 1,
        modifier = if (spoken != null) modifier.semantics { contentDescription = spoken } else modifier,
    )
}

// MARK: Charts

/**
 * A day's heart rate as a line from its first reading to its last, with the clock under its two ends and
 * its middle. Nothing is drawn for fewer than two points.
 */
@Composable
internal fun HeartRateLine(points: List<FriendHrPoint>, description: String, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val color = Health.colors.heart
    val rule = MaterialTheme.colorScheme.outlineVariant
    val first = points.first().ts
    val last = points.last().ts
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
            val lo = points.minOf { it.bpm }.toFloat()
            val hi = points.maxOf { it.bpm }.toFloat()
            val range = (hi - lo).takeIf { it > 0f } ?: 1f
            val span = (last - first).coerceAtLeast(1L).toFloat()
            val pad = 4.dp.toPx()
            fun at(p: FriendHrPoint) = Offset(
                (p.ts - first) / span * size.width,
                pad + (1f - (p.bpm - lo) / range) * (size.height - pad * 2),
            )
            drawLine(rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1.dp.toPx())
            val path = Path()
            points.forEachIndexed { i, p ->
                val o = at(p)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, radius = 4.dp.toPx(), center = at(points.last()))
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(first, first + (last - first) / 2, last).forEach { ts ->
                Text(friendsClock(ts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A quiet line with a leading icon: a note under a card, or the words that stand in for one. */
@Composable
internal fun FriendsNote(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding + 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A pill with an icon and a short line: "Updated 6 min ago". */
@Composable
internal fun FriendsPill(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}

/** The reader's locale. */
@Composable
internal fun friendsLocale(): Locale = LocalContext.current.resources.configuration.locales[0]
