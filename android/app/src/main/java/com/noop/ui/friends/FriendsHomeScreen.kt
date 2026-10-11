package com.noop.ui.friends

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendsClaim
import com.noop.friends.FriendsPhase
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.EffortScale
import com.noop.ui.OnScrollToTop
import com.noop.ui.UnitPrefs
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LargeTitle
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.ProgressRing
import com.noop.ui.m3.SectionHeader
import com.noop.ui.sleep.sleepDuration
import com.noop.ui.sportIcon
import com.noop.ui.workouts.WorkoutFormat
import com.noop.ui.workouts.sportLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// MARK: - Friends tab: the list (everyone's day, one card each)
//
// Read top to bottom. What friends did lately: Highlights, their workouts of the last two days, paging
// side by side. Then everyone, the reader among them as "Me": a person's picture and name, the day's
// figure for the list's order with the amount behind it on a second line, and the three rings. The menu
// over the list picks the order and, with it, what the cards show. A card opens that person's page.
// Inviting a friend and whatever waits for an answer are behind the bar's one button, which counts them.
//
// Every figure on the page is the server's copy, the reader's own included, so "Me" here is what a
// friend sees. The order and what a card shows are decided by [FriendsBoard.rows]; this file draws them.

/** Where the Friends tab's taps go; the shell resolves each to a route on the tab. */
internal class FriendsActions(
    val openFriend: (String) -> Unit,
    /** The page behind the bar's button: who the reader shares with, inviting, and what waits. */
    val openManage: () -> Unit,
)

/** The tab's root: the turning-on page until Friends is on, then the list. */
@Composable
internal fun FriendsTabScreen(appVm: AppViewModel, vm: FriendsViewModel, actions: FriendsActions) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    if (phase == FriendsPhase.On) {
        FriendsHomeScreen(appVm, vm, actions)
        FriendsInviteLinkPrompt(vm)
    } else {
        FriendsConnectScreen(vm)
    }
}

/**
 * A pushed Friends screen. It is drawn only while Friends is on; when it goes off under it (the account
 * deleted, this phone removed, or dropped by the server) [onOff] takes the reader back to the tab's
 * root, which is then the turning-on page and says why.
 */
@Composable
internal fun FriendsWhileOn(vm: FriendsViewModel, onOff: () -> Unit, content: @Composable () -> Unit) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val on = phase == FriendsPhase.On
    LaunchedEffect(on) { if (!on) onOff() }
    if (on) content()
}

/**
 * An invite that arrived by a link is answered here. Nobody is added by opening a link: the tab asks
 * first. One that arrives while Friends is off waits until it is on.
 */
@Composable
private fun FriendsInviteLinkPrompt(vm: FriendsViewModel) {
    val scope = rememberCoroutineScope()
    val code by FriendsInviteLinks.code.collectAsStateWithLifecycle()
    val pending = code ?: return
    ConfirmDialog(
        title = stringResource(R.string.friends_add_open),
        message = stringResource(R.string.friends_link_body),
        confirmLabel = stringResource(R.string.friends_add),
        onDismiss = { FriendsInviteLinks.take() },
        onConfirm = {
            FriendsInviteLinks.take()
            // A refusal is said by the tab's own notice: the store keeps it as the last failure.
            scope.launch { vm.redeem(pending) }
        },
    )
}

/** The app's one "today", read the way the Summary reads it, so both tabs mean the same day by the word. */
@Composable
internal fun rememberFriendsToday(appVm: AppViewModel, nowSec: Long): AppToday {
    val todayRow by appVm.today.collectAsStateWithLifecycle()
    return remember(todayRow?.day, nowSec) { AppToday.now(todayRow?.day) }
}

/** From this font scale up a card stacks what it sets side by side at the default size. */
internal const val FRIENDS_LARGE_TEXT = 1.3f

@Composable
internal fun friendsLargeText(): Boolean = LocalDensity.current.fontScale >= FRIENDS_LARGE_TEXT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendsHomeScreen(appVm: AppViewModel, vm: FriendsViewModel, actions: FriendsActions) {
    val context = LocalContext.current
    val state by vm.feed.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()
    val today = rememberFriendsToday(appVm, nowSec)
    val scale = remember { UnitPrefs.effortScale(context) }
    val locale = friendsLocale()
    val meName = stringResource(R.string.friends_me)
    val feed = state.feed

    // The tab opened (or came back): send what changed and fetch. Throttled inside the view model.
    LaunchedEffect(Unit) { vm.sync() }

    val pull = rememberPullToRefreshState()
    LaunchedEffect(pull.isRefreshing) {
        if (!pull.isRefreshing) return@LaunchedEffect
        vm.syncNow()
        pull.endRefresh()
    }
    val listState = rememberLazyListState()
    OnScrollToTop { listState.animateScrollToItem(0) }

    val waiting = (feed?.claims?.size ?: 0) + (feed?.unconfirmed?.size ?: 0)
    val serverNow = vm.serverNow(nowSec)
    // Kept for the minute, not the second: a workout does not leave the row between two frames.
    val highlights = remember(feed, serverNow / 60) { feed?.let { FriendsHighlights.recent(it.friends, serverNow) }.orEmpty() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "title") {
                LargeTitle(stringResource(R.string.nav_friends)) {
                    AddFriendButton(pending = waiting, onClick = actions.openManage)
                }
            }

            state.error?.let { error ->
                item(key = "notice") {
                    FriendsRefreshNotice(
                        error = error,
                        shownAge = if (feed != null) FriendsAgo.of(state.fetchedAt, nowSec) else null,
                        onRetry = { vm.sync(force = true) },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }

            if (feed == null) {
                if (state.error == null) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                return@LazyColumn
            }

            // What waits for the wearer's answer leads the page; the answers are given behind "Review".
            feed.me.profile.device?.probationUntil?.let { until ->
                item(key = "probation") {
                    NoticeCard(
                        icon = Icons.Filled.HourglassEmpty,
                        title = stringResource(R.string.friends_probation_title),
                        message = stringResource(R.string.friends_probation_body, friendsMoment(until)),
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }
            items(feed.claims, key = { "claim:${it.id}" }) { claim ->
                NoticeCard(
                    icon = if (claim.kind == FriendsClaim.Kind.JOIN) Icons.Filled.Smartphone else Icons.Filled.Sensors,
                    title = friendsClaimTitle(claim),
                    message = friendsClaimDetail(claim),
                    action = stringResource(R.string.friends_review),
                    onAction = actions.openManage,
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                )
            }
            items(feed.unconfirmed, key = { "phone:${it.id}" }) { phone ->
                NoticeCard(
                    icon = Icons.Filled.GppMaybe,
                    title = stringResource(R.string.friends_unconfirmed_title),
                    message = friendsJoinedText(phone, serverNow),
                    action = stringResource(R.string.friends_review),
                    onAction = actions.openManage,
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                )
            }

            if (highlights.isNotEmpty()) {
                item(key = "highlights-header") {
                    SectionHeader(stringResource(R.string.friends_highlights), Modifier.padding(horizontal = M3Dimens.screenPadding))
                }
                item(key = "highlights") { HighlightsRow(highlights, serverNow, actions.openFriend) }
            }

            item(key = "board-header") {
                BoardHeader(order, vm::setOrder, today.date, Modifier.padding(horizontal = M3Dimens.screenPadding))
            }
            val rows = FriendsBoard.rows(feed.me, feed.friends, order, today.key, meName, FriendsBoard.nameOrder(locale))
            items(rows, key = { "person:${it.person.id}" }) { row ->
                FriendScoreCard(
                    row = row,
                    metric = order.metric,
                    scale = scale,
                    today = today,
                    onClick = { actions.openFriend(row.person.id) },
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                )
            }
            if (feed.friends.isEmpty()) {
                item(key = "empty") { NoFriendsCard(actions.openManage, Modifier.padding(horizontal = M3Dimens.screenPadding)) }
            }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** The bar's one button: it opens the page of friends, and counts what waits there for an answer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFriendButton(pending: Int, onClick: () -> Unit) {
    val description = if (pending > 0) {
        pluralStringResource(R.plurals.friends_add_open_with_requests, pending, pending)
    } else stringResource(R.string.friends_add_open)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        BadgedBox(badge = { if (pending > 0) Badge { Text(pending.toString()) } }) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null)
        }
    }
}

// MARK: Highlights

/**
 * The highlights in a row that pages card by card, a strip of the next one in sight at the edge. At a
 * large font size the cards stack instead, each as tall as its text needs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightsRow(highlights: List<FriendHighlight>, serverNow: Long, openFriend: (String) -> Unit) {
    if (friendsLargeText()) {
        Column(
            Modifier.padding(horizontal = M3Dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            highlights.forEach { HighlightCard(it, serverNow, onClick = { openFriend(it.personId) }) }
        }
        return
    }
    val pager = rememberPagerState(pageCount = { highlights.size })
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = M3Dimens.screenPadding + HIGHLIGHT_PEEK),
        pageSpacing = M3Dimens.itemGap,
        key = { highlights.getOrNull(it)?.id ?: it },
    ) { page ->
        val highlight = highlights.getOrNull(page) ?: return@HorizontalPager
        HighlightCard(highlight, serverNow, onClick = { openFriend(highlight.personId) })
    }
}

/** How much of the neighbouring highlight shows at either edge. */
private val HIGHLIGHT_PEEK = 20.dp

/**
 * One highlight: who and when across the top, a closed ring around the activity's glyph, how long the
 * workout ran in the ring's hue and the activity under it.
 */
@Composable
private fun HighlightCard(highlight: FriendHighlight, serverNow: Long, onClick: () -> Unit) {
    val hue = Health.colors.fitness
    val workout = highlight.workout
    HealthCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.friends_open_page, highlight.name),
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(M3Dimens.cardPadding),
        verticalSpacing = 12.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FriendAvatar(highlight.name, highlight.personId, highlight.avatarRev, size = 40.dp)
            Column(Modifier.weight(1f)) {
                // Two lines kept whatever the name's length, so every card of the row is one height.
                Text(
                    stringResource(R.string.friends_completed_workout, friendsShortName(highlight.name)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    minLines = if (friendsLargeText()) 1 else 2,
                    maxLines = if (friendsLargeText()) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    agoText(FriendsAgo.of(highlight.endTs, serverNow) ?: FriendsAgo.JustNow),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProgressRing(fraction = 1f, color = hue, modifier = Modifier.size(120.dp), stroke = 14.dp) {
                Icon(sportIcon(workout.sport), contentDescription = null, tint = hue, modifier = Modifier.size(48.dp))
            }
        }
        val spokenLength = sleepDuration(workout.durationS / 60.0)
        Text(
            friendsClockDuration(workout.durationS),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = hue,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = spokenLength },
        )
        Text(
            sportLabel(workout.sport),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = if (friendsLargeText()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// MARK: The list

/** The heading over the list: its title with the order menu across from it, and the day the figures belong to. */
@Composable
private fun BoardHeader(order: FriendsOrder, onOrder: (FriendsOrder) -> Unit, day: LocalDate, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val sortWord = stringResource(R.string.friends_sort)
    val chosen = orderLabel(order)
    Column(modifier.fillMaxWidth().padding(top = M3Dimens.sectionTop - M3Dimens.itemGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.friends_rings),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
            Box {
                TextButton(
                    onClick = { open = true },
                    // TalkBack says what the button is for, then which order is chosen.
                    modifier = Modifier.semantics {
                        contentDescription = sortWord
                        stateDescription = chosen
                    },
                ) {
                    Text(chosen, maxLines = 1)
                    Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    FriendsOrder.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(orderLabel(option)) },
                            leadingIcon = {
                                if (option == order) Icon(Icons.Filled.Check, contentDescription = null)
                                else Box(Modifier.size(24.dp))
                            },
                            onClick = {
                                open = false
                                onOrder(option)
                            },
                        )
                    }
                }
            }
        }
        Text(
            stringResource(
                R.string.friends_board_day,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(friendsLocale()).format(day),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun orderLabel(order: FriendsOrder): String =
    if (order == FriendsOrder.NAME) stringResource(R.string.friends_sort_name) else metricTitle(order.metric)

/** The name a figure goes by: the three scores as their own tabs name them, and the workouts. */
@Composable
internal fun metricTitle(metric: FriendsMetric): String = stringResource(
    when (metric) {
        FriendsMetric.RECOVERY -> R.string.today_metric_charge
        FriendsMetric.STRAIN -> R.string.today_metric_effort
        FriendsMetric.SLEEP -> R.string.today_metric_rest
        FriendsMetric.WORKOUTS -> R.string.nav_workouts
    },
)

/** The hue a figure is written in: its ring's on the Summary, and the Workouts tab's own for the workouts. */
@Composable
internal fun metricHue(metric: FriendsMetric): Color = when (metric) {
    FriendsMetric.RECOVERY -> Health.colors.charge
    FriendsMetric.STRAIN -> Health.colors.effort
    FriendsMetric.SLEEP -> Health.colors.rest
    FriendsMetric.WORKOUTS -> Health.colors.fitness
}

/**
 * One person's card: the picture and the name, the figure for the list's order in its hue with the
 * amount behind it under, and the rings on the trailing side. A figure that is not today's is set back in
 * grey and says which day it is from.
 */
@Composable
private fun FriendScoreCard(
    row: FriendsBoardRow,
    metric: FriendsMetric,
    scale: EffortScale,
    today: AppToday,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val person = row.person
    val name = if (row.isMe) stringResource(R.string.friends_me) else person.profile.name
    val fresh = row.standing == FriendsBoardRow.Standing.Today
    val hue = if (fresh) metricHue(metric) else MaterialTheme.colorScheme.onSurfaceVariant
    val large = friendsLargeText()
    // The rings are the person's newest day, the same day the figure is from.
    val ringsDay = if (person.share.scores) person.days.firstOrNull() else null

    val note = when (val standing = row.standing) {
        FriendsBoardRow.Standing.Today -> null
        is FriendsBoardRow.Standing.Stale -> friendsDayText(standing.dayKey, today, weekdays = false)
        FriendsBoardRow.Standing.NotShared -> stringResource(
            if (metric == FriendsMetric.WORKOUTS) R.string.friends_card_not_sharing_workouts else R.string.friends_card_not_sharing_scores,
        )
        FriendsBoardRow.Standing.NoData -> stringResource(R.string.friends_no_data_today)
    }
    val figure = row.value?.let { value ->
        when (metric) {
            FriendsMetric.RECOVERY, FriendsMetric.SLEEP -> friendsPercent(value)
            FriendsMetric.STRAIN -> strainShown(value, scale)
            FriendsMetric.WORKOUTS -> WorkoutFormat.grouped(value, friendsLocale())
        }
    }
    val unit = if (metric == FriendsMetric.WORKOUTS && figure != null) stringResource(R.string.metric_unit_kcal) else null
    val spokenFigure = when {
        figure == null -> null
        unit != null -> "$figure $unit"
        else -> metricTitle(metric) + " " + figure
    }
    // The workouts order's first line: the sport when the day had one workout, how many otherwise.
    val sessions = (row.detail as? FriendsBoardRow.Detail.Sessions)?.let { detail ->
        detail.sport?.let { sportLabel(it) } ?: pluralStringResource(R.plurals.friends_workouts_count, detail.count, detail.count)
    }
    // The amount behind a score, and what TalkBack calls it. Nothing stands behind Recovery.
    val amount: Pair<String, String>? = if (!metric.hasDetail) null else when (val detail = row.detail) {
        is FriendsBoardRow.Detail.Workouts -> {
            val text = stringResource(R.string.sleep_duration_m, detail.minutes)
            text to stringResource(R.string.nav_workouts) + " " + text
        }
        is FriendsBoardRow.Detail.Asleep -> {
            val text = sleepDuration(detail.minutes.toDouble())
            text to stringResource(R.string.summary_time_asleep) + " " + text
        }
        is FriendsBoardRow.Detail.Sessions, null -> null
    }

    HealthCard(
        modifier = modifier,
        onClick = onClick,
        onClickLabel = stringResource(R.string.friends_open_page, name),
        contentPadding = PaddingValues(M3Dimens.cardPadding),
    ) {
        val text: @Composable (Modifier) -> Unit = { textModifier ->
            Column(textModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FriendAvatar(
                        person.profile.name, person.id, person.profile.avatarRev, size = 32.dp,
                        tone = if (row.isMe) AvatarTone.ME else AvatarTone.FRIEND,
                    )
                    // The wearer's own card carries a dot in the hue of its figure.
                    if (row.isMe) Box(Modifier.size(10.dp).clip(CircleShape).background(hue))
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (large) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sessions != null) {
                    Text(sessions, style = MaterialTheme.typography.bodyLarge, color = hue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val figureLine: @Composable (Modifier) -> Unit = { figureModifier ->
                    FriendsFigure(figure ?: stringResource(R.string.friends_no_value), unit, hue, spokenFigure, figureModifier)
                }
                val noteLine: @Composable (Modifier) -> Unit = { noteModifier ->
                    if (note != null) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (large) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = noteModifier,
                        )
                    }
                }
                if (large) {
                    figureLine(Modifier)
                    noteLine(Modifier)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        figureLine(Modifier.alignByBaseline())
                        noteLine(Modifier.alignByBaseline().weight(1f, fill = false))
                    }
                }
                if (metric == FriendsMetric.STRAIN || metric == FriendsMetric.SLEEP) {
                    // Every card under this order keeps the line, so the cards stay one height.
                    val spoken = amount?.second
                    Text(
                        amount?.first ?: " ",
                        style = MaterialTheme.typography.titleMedium,
                        color = hue,
                        maxLines = 1,
                        modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
                    )
                }
            }
        }
        if (large) {
            text(Modifier.fillMaxWidth())
            FriendRings(ringsDay, size = CARD_RINGS, stroke = 8.dp, gap = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                text(Modifier.weight(1f))
                FriendRings(ringsDay, size = CARD_RINGS, stroke = 8.dp, gap = 2.dp)
            }
        }
    }
}

private val CARD_RINGS = 68.dp

/** The list with nobody on it but the reader: what the tab is for, and the way to the first friend. */
@Composable
private fun NoFriendsCard(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    HealthCard(modifier = modifier, contentPadding = PaddingValues(M3Dimens.cardPadding)) {
        Text(
            stringResource(R.string.friends_empty_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.friends_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onAdd) { Text(stringResource(R.string.friends_add_open)) }
    }
}
