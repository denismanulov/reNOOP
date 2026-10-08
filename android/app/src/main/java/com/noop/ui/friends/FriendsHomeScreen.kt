package com.noop.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendDay
import com.noop.friends.FriendPerson
import com.noop.friends.FriendSleep
import com.noop.friends.FriendsFeed
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.EffortScale
import com.noop.ui.OnScrollToTop
import com.noop.ui.UnitPrefs
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.CloverShape
import com.noop.ui.m3.CookieShape
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.ExpressiveBar
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LargeTitle
import com.noop.ui.m3.LocalTonalIcons
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.TonalIcon
import com.noop.ui.m3.ValueWithUnit
import com.noop.ui.sleep.sleepDuration
import com.noop.ui.sportIcon
import com.noop.ui.workouts.sportLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// MARK: - Friends tab: home (you beside one friend)
//
// Read top to bottom as questions. Who am I comparing with: the chips. How do we stand today: three scores
// side by side, each with its level, and one sentence that says who leads. What is their heart doing: the
// latest reading and how old it is. How did we sleep. What happened: the feed. How has the week gone.
// Every figure on the page is the server's copy, the reader's own included, so "you" here is what a friend
// sees; a section someone does not share is said in words and never drawn as an empty card.

/** Where the Friends tab's taps go; the shell resolves each to a route on the tab. */
internal class FriendsActions(
    val openList: () -> Unit,
    val openFriend: (String) -> Unit,
    val openAdd: () -> Unit,
    val openProfile: () -> Unit,
)

/** The tab's root: the sign-in form with no account on this phone, else the home. */
@Composable
internal fun FriendsTabScreen(appVm: AppViewModel, vm: FriendsViewModel, actions: FriendsActions) {
    val nick by vm.nick.collectAsStateWithLifecycle()
    if (nick == null) FriendsConnectScreen(vm) else FriendsHomeScreen(appVm, vm, actions)
}

/**
 * A pushed Friends screen. It is drawn only while an account is signed in; when the session ends under it
 * (signed out, deleted, or ended by the server) [onSignedOut] takes the reader back to the tab's root,
 * which is then the sign-in form.
 */
@Composable
internal fun FriendsSignedIn(vm: FriendsViewModel, onSignedOut: () -> Unit, content: @Composable () -> Unit) {
    val nick by vm.nick.collectAsStateWithLifecycle()
    LaunchedEffect(nick) { if (nick == null) onSignedOut() }
    if (nick != null) content()
}

/** The app's one "today", read the way the Summary reads it, so both tabs mean the same day by the word. */
@Composable
internal fun rememberFriendsToday(appVm: AppViewModel, nowSec: Long): AppToday {
    val todayRow by appVm.today.collectAsStateWithLifecycle()
    return remember(todayRow?.day, nowSec) { AppToday.now(todayRow?.day) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendsHomeScreen(appVm: AppViewModel, vm: FriendsViewModel, actions: FriendsActions) {
    val context = LocalContext.current
    val state by vm.feed.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()
    val today = rememberFriendsToday(appVm, nowSec)
    val scale = remember { UnitPrefs.effortScale(context) }
    val feed = state.feed

    // The tab opened (or came back): send what changed and fetch. Throttled inside the view model.
    LaunchedEffect(Unit) { vm.refresh() }

    val pull = rememberPullToRefreshState()
    LaunchedEffect(pull.isRefreshing) {
        if (!pull.isRefreshing) return@LaunchedEffect
        vm.refreshNow()
        pull.endRefresh()
    }
    val listState = rememberLazyListState()
    OnScrollToTop { listState.animateScrollToItem(0) }

    var pickedNick by rememberSaveable { mutableStateOf<String?>(null) }
    val friend = feed?.friends?.firstOrNull { it.nick == pickedNick } ?: feed?.friends?.firstOrNull()
    val serverNow = vm.serverNow(nowSec)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "title") {
                LargeTitle(stringResource(R.string.nav_friends)) {
                    AddFriendButton(pending = feed?.pendingIncoming ?: 0, onClick = actions.openAdd)
                    IconButton(onClick = actions.openProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.friends_profile_open))
                    }
                }
            }

            state.error?.let { error ->
                item(key = "notice") {
                    FriendsRefreshNotice(
                        error = error,
                        shownAge = if (feed != null) FriendsAgo.of(state.fetchedAt, nowSec) else null,
                        onRetry = { vm.refresh(force = true) },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }

            when {
                feed == null -> {
                    if (state.error == null) {
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
                friend == null -> {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Filled.Group,
                            title = stringResource(R.string.friends_empty_title),
                            message = stringResource(R.string.friends_empty_body),
                            action = stringResource(R.string.friends_add_by_nick),
                            onAction = actions.openAdd,
                        )
                    }
                    if (feed.pendingIncoming > 0) {
                        item(key = "pending") {
                            NoticeCard(
                                icon = Icons.Filled.PersonAdd,
                                title = pluralStringResource(R.plurals.friends_pending_requests, feed.pendingIncoming, feed.pendingIncoming),
                                action = stringResource(R.string.friends_pending_open),
                                onAction = actions.openAdd,
                                modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                            )
                        }
                    }
                }
                else -> {
                    val mine = feed.me.day(today.key)
                    val theirs = friend.day(today.key)
                    val name = friend.profile.name

                    item(key = "status") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val updated = FriendsAgo.of(friend.lastUpdatedAt, serverNow)
                            FriendsPill(
                                Icons.Filled.Sync,
                                if (updated != null) stringResource(R.string.summary_updated, agoText(updated))
                                else stringResource(R.string.friends_no_upload_yet),
                            )
                            Text(
                                DateTimeFormatter.ofPattern("EEE, d MMM", friendsLocale()).format(today.date),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item(key = "chips") {
                        FriendChips(
                            friends = feed.friends,
                            selected = friend.nick,
                            onSelect = { pickedNick = it },
                            onAll = actions.openList,
                        )
                    }

                    item(key = "duel") {
                        HeadToHeadCard(feed.me, friend, mine, theirs, scale, onOpenFriend = { actions.openFriend(friend.nick) })
                    }

                    item(key = "hr") {
                        FriendHeartRate(friend, theirs, serverNow, onOpen = { actions.openFriend(friend.nick) })
                    }

                    item(key = "sleep-header") {
                        SectionHeader(stringResource(R.string.friends_last_night), Modifier.padding(horizontal = M3Dimens.screenPadding))
                    }
                    item(key = "sleep") {
                        HealthCard(
                            modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                            shape = RoundedCornerShape(M3Dimens.heroRadius),
                            contentPadding = PaddingValues(20.dp),
                            verticalSpacing = 16.dp,
                        ) {
                            SleepSide(stringResource(R.string.friends_you), mine?.sleep, shares = feed.me.share.sleep, isMe = true, name = name)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SleepSide(name, theirs?.sleep, shares = friend.share.sleep, isMe = false, name = name)
                        }
                    }

                    item(key = "feed-header") {
                        SectionHeader(stringResource(R.string.friends_feed), Modifier.padding(horizontal = M3Dimens.screenPadding))
                    }
                    item(key = "feed") {
                        val events = remember(feed, friend.nick, serverNow / 60) {
                            FriendsEvents.derive(listOf(feed.me to true, friend to false), serverNow)
                        }
                        EventsCard(events, today.date, scale, Modifier.padding(horizontal = M3Dimens.screenPadding))
                    }

                    item(key = "week-header") {
                        SectionHeader(stringResource(R.string.friends_week), Modifier.padding(horizontal = M3Dimens.screenPadding))
                    }
                    item(key = "week") {
                        WeekCard(
                            week = remember(feed, friend.nick, today.key) { FriendsWeek.of(today.key, feed.me, friend) },
                            friendName = name,
                            friendShares = friend.share.scores,
                            modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                        )
                    }

                    item(key = "cadence") { FriendsNote(Icons.Filled.Schedule, stringResource(R.string.friends_cadence_note)) }
                }
            }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** The add-friend action, with the number of requests waiting on it. */
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

/** Who to compare with: one chip per friend, then "All", which opens the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendChips(friends: List<FriendPerson>, selected: String, onSelect: (String) -> Unit, onAll: () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = M3Dimens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(friends, key = { "person:${it.nick}" }) { person ->
            val isSelected = person.nick == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(person.nick) },
                label = { Text(person.profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    FriendAvatar(
                        person.profile.name, person.nick, person.profile.avatarRev, size = 24.dp,
                        tone = if (isSelected) AvatarTone.FRIEND else AvatarTone.QUIET,
                    )
                },
                modifier = Modifier.heightIn(min = M3Dimens.minTouch),
            )
        }
        item(key = "all") {
            AssistChip(
                onClick = onAll,
                label = { Text(stringResource(R.string.friends_all)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = M3Dimens.minTouch),
            )
        }
    }
}

/** Today's three scores for two people, and one or two sentences on who leads. */
@Composable
private fun HeadToHeadCard(
    me: FriendPerson,
    friend: FriendPerson,
    mine: FriendDay?,
    theirs: FriendDay?,
    scale: EffortScale,
    onOpenFriend: () -> Unit,
) {
    val name = friend.profile.name
    val you = stringResource(R.string.friends_you)
    val health = Health.colors
    HealthCard(
        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 20.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FriendAvatar(me.profile.name, me.nick, me.profile.avatarRev, size = 56.dp, tone = AvatarTone.ME)
                Text(you, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
            Text(
                stringResource(R.string.friends_today),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(M3Dimens.cardRadius))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.friends_open_page, name), onClick = onOpenFriend),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                FriendAvatar(name, friend.nick, friend.profile.avatarRev, size = 56.dp)
            }
        }

        if (!friend.share.scores) {
            Text(
                stringResource(R.string.friends_not_sharing_scores, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@HealthCard
        }

        val missing = stringResource(R.string.friends_no_data_today)

        @Composable
        fun side(side: DuelSide): String {
            val value = side.value ?: return missing
            return side.level?.let { stringResource(R.string.friends_value_with_level, value, it) } ?: value
        }

        @Composable
        fun spoken(title: String, left: DuelSide, right: DuelSide): String =
            stringResource(R.string.friends_duel_spoken, title, you, side(left), name, side(right))

        fun leads(a: Double?, b: Double?): Boolean? = if (a == null || b == null || a == b) null else a > b

        val recoveryTitle = stringResource(R.string.today_metric_charge)
        val myRecovery = recoverySide(mine?.recovery)
        val theirRecovery = recoverySide(theirs?.recovery)
        ScoreDuel(
            title = recoveryTitle, icon = Icons.Filled.Bolt, tint = health.charge,
            left = myRecovery, right = theirRecovery,
            leftLeads = leads(mine?.recovery?.toDouble(), theirs?.recovery?.toDouble()),
            missing = missing, description = spoken(recoveryTitle, myRecovery, theirRecovery),
        )

        val strainTitle = stringResource(R.string.today_metric_effort)
        val myStrain = strainSide(mine?.strain, scale)
        val theirStrain = strainSide(theirs?.strain, scale)
        ScoreDuel(
            title = strainTitle, icon = Icons.Filled.LocalFireDepartment, tint = health.effort,
            left = myStrain, right = theirStrain,
            leftLeads = leads(mine?.strain, theirs?.strain),
            missing = missing, description = spoken(strainTitle, myStrain, theirStrain),
            centre = strainOutOf(scale),
        )

        val sleepTitle = stringResource(R.string.today_metric_rest)
        val mySleep = sleepScoreSide(mine?.sleepScore)
        val theirSleep = sleepScoreSide(theirs?.sleepScore)
        ScoreDuel(
            title = sleepTitle, icon = Icons.Filled.Bedtime, tint = health.rest,
            left = mySleep, right = theirSleep,
            leftLeads = leads(mine?.sleepScore?.toDouble(), theirs?.sleepScore?.toDouble()),
            missing = missing, description = spoken(sleepTitle, mySleep, theirSleep),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (!me.share.scores) {
            Text(
                stringResource(R.string.friends_you_not_sharing_scores),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FriendsComparison.lines(mine, theirs).forEach { line ->
                    Text(comparisonText(line, name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
internal fun recoverySide(recovery: Int?): DuelSide =
    if (recovery == null) DuelSide(null, null, null)
    else DuelSide(recovery.toString(), recovery / 100f, recoveryLevelText(recovery))

@Composable
internal fun strainSide(strain: Double?, scale: EffortScale): DuelSide =
    if (strain == null) DuelSide(null, null, null)
    else DuelSide(strainShown(strain, scale), (strain / 100.0).toFloat(), strainLevelText(strain))

@Composable
internal fun sleepScoreSide(score: Int?): DuelSide =
    if (score == null) DuelSide(null, null, null)
    else DuelSide(score.toString(), score / 100f, sleepLevelText(score))

/** One comparison sentence. Names stand in the nominative, so no name is ever declined or given a gender. */
@Composable
internal fun comparisonText(line: FriendsComparisonLine, friendName: String): String = when (line) {
    is FriendsComparisonLine.RecoveryLead ->
        if (line.side == FriendsSide.ME) stringResource(R.string.friends_cmp_recovery_me, line.points)
        else stringResource(R.string.friends_cmp_recovery_friend, friendName, line.points)
    FriendsComparisonLine.RecoveryLevel -> stringResource(R.string.friends_cmp_recovery_level)
    is FriendsComparisonLine.StrainLead ->
        if (line.side == FriendsSide.ME) stringResource(R.string.friends_cmp_strain_me)
        else stringResource(R.string.friends_cmp_strain_friend, friendName)
    is FriendsComparisonLine.SleepLead ->
        if (line.side == FriendsSide.ME) stringResource(R.string.friends_cmp_sleep_me, line.points)
        else stringResource(R.string.friends_cmp_sleep_friend, friendName, line.points)
    FriendsComparisonLine.NothingShared -> stringResource(R.string.friends_cmp_nothing)
}

/** The friend's latest heart rate with its age, or the words for why there is none. */
@Composable
private fun FriendHeartRate(friend: FriendPerson, day: FriendDay?, serverNow: Long, onOpen: () -> Unit) {
    val name = friend.profile.name
    val hr = day?.hr
    when {
        !friend.share.hr -> FriendsNote(Icons.Filled.Favorite, stringResource(R.string.friends_not_sharing_hr, name))
        hr == null -> FriendsNote(Icons.Filled.Favorite, stringResource(R.string.friends_no_hr_today, name))
        else -> {
            val age = FriendsAgo.of(hr.lastTs, serverNow)
            val fresh = age == FriendsAgo.JustNow || (age is FriendsAgo.Minutes && age.count < HR_NOW_WITHIN_MIN)
            HealthCard(
                modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                onClick = onOpen,
                onClickLabel = stringResource(R.string.friends_open_page, name),
                shape = RoundedCornerShape(M3Dimens.heroRadius),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TonalIcon(Icons.Filled.Favorite, LocalTonalIcons.current.pink, size = 64.dp, shape = CookieShape)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (fresh) R.string.friends_hr_now else R.string.friends_hr_latest, name),
                            style = MaterialTheme.typography.labelLarge,
                            color = Health.colors.heart,
                        )
                        ValueWithUnit(hr.lastBpm.toString(), stringResource(R.string.metric_unit_bpm))
                        val agoLine = age?.let { stringResource(R.string.friends_hr_last_reading, agoText(it)) }
                        val resting = hr.restingBpm?.let { stringResource(R.string.friends_hr_resting, it) }
                        listOfNotNull(agoLine, resting).forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ChevronRight()
                }
            }
        }
    }
}

/** A reading younger than this is "now"; an older one is named the latest reading, with its age beside it. */
private const val HR_NOW_WITHIN_MIN = 10L

/** One person's night: how long, from when to when, and how it stands against its need. */
@Composable
internal fun SleepSide(label: String, sleep: FriendSleep?, shares: Boolean, isMe: Boolean, name: String) {
    val color = Health.colors.sleep
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (sleep != null) {
                Text(
                    sleepDuration(sleep.asleepMin.toDouble()),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.friends_time_range, friendsClock(sleep.startTs), friendsClock(sleep.endTs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        when {
            sleep != null -> {
                FriendsSleepGoal.fraction(sleep)?.let { ExpressiveBar(it, color, height = 12.dp) }
                FriendsSleepGoal.of(sleep)?.let { goal ->
                    Text(sleepGoalText(goal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            !shares -> Text(
                if (isMe) stringResource(R.string.friends_you_not_sharing_sleep) else stringResource(R.string.friends_not_sharing_sleep, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                stringResource(R.string.friends_no_sleep_today),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun sleepGoalText(goal: FriendsSleepGoal): String {
    val need = sleepDuration(goal.needMin.toDouble())
    return when (goal) {
        is FriendsSleepGoal.Short -> stringResource(R.string.friends_sleep_short, sleepDuration(goal.byMin.toDouble()), need)
        is FriendsSleepGoal.Almost -> stringResource(R.string.friends_sleep_almost, need)
        is FriendsSleepGoal.Met -> stringResource(R.string.friends_sleep_met, need)
    }
}

/** The feed: one row per event, or the words for a quiet few days. */
@Composable
internal fun EventsCard(events: List<FriendsEvent>, today: LocalDate, scale: EffortScale, modifier: Modifier = Modifier) {
    HealthCard(
        modifier = modifier,
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalSpacing = 0.dp,
    ) {
        if (events.isEmpty()) {
            Text(
                stringResource(R.string.friends_feed_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        events.forEach { event -> EventRow(event, today, scale) }
    }
}

@Composable
private fun EventRow(event: FriendsEvent, today: LocalDate, scale: EffortScale) {
    val who = if (event.isMe) stringResource(R.string.friends_you) else event.who.name
    val tonal = LocalTonalIcons.current
    val (title, subtitle) = when (event) {
        is FriendsEvent.Woke -> {
            val asleep = sleepDuration(event.asleepMin.toDouble())
            stringResource(R.string.friends_event_woke, who) to (
                event.sleepScore?.let { stringResource(R.string.friends_event_woke_scored, asleep, it, sleepLevelText(it)) }
                    ?: stringResource(R.string.friends_event_woke_plain, asleep)
                )
        }
        is FriendsEvent.Workout -> {
            val w = event.workout
            val duration = sleepDuration(w.durationS / 60.0)
            val strain = w.strain?.let { strainShown(it, scale) }
            stringResource(R.string.friends_event_workout, who, sportLabel(w.sport), duration) to when {
                strain != null && w.avgHr != null -> stringResource(R.string.friends_event_workout_both, strain, w.avgHr)
                strain != null -> stringResource(R.string.friends_event_workout_strain, strain)
                w.avgHr != null -> stringResource(R.string.friends_event_workout_hr, w.avgHr)
                else -> null
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = M3Dimens.rowTwoLineHeight)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (event) {
            is FriendsEvent.Woke -> TonalIcon(Icons.Filled.WbTwilight, tonal.purple, size = 44.dp, shape = CloverShape)
            is FriendsEvent.Workout -> TonalIcon(sportIcon(event.workout.sport), tonal.green, size = 44.dp, shape = CloverShape)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(eventStamp(event.ts, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Seven days of Recovery side by side, led by the two averages in a sentence. */
@Composable
internal fun WeekCard(week: FriendsWeek?, friendName: String, friendShares: Boolean, modifier: Modifier = Modifier) {
    val mineColor = Health.colors.charge
    val theirColor = MaterialTheme.colorScheme.outline
    val you = stringResource(R.string.friends_you)
    HealthCard(
        modifier = modifier,
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 16.dp,
    ) {
        val mine = week?.myAverage
        val theirs = week?.theirAverage
        val sentence = when {
            week == null || week.isEmpty ->
                if (friendShares) stringResource(R.string.friends_week_none) else stringResource(R.string.friends_not_sharing_scores, friendName)
            mine != null && theirs != null -> stringResource(R.string.friends_week_both, mine, friendName, theirs)
            mine != null ->
                if (friendShares) stringResource(R.string.friends_week_only_me, mine, friendName)
                else stringResource(R.string.friends_week_only_me_unshared, mine, friendName)
            else -> stringResource(R.string.friends_week_only_friend, friendName, theirs ?: 0)
        }
        Text(sentence, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (week != null && !week.isEmpty) {
            PairedWeekChart(
                week, mineColor, theirColor,
                description = stringResource(R.string.friends_week_chart_spoken, friendName),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LegendDot(mineColor, you)
                LegendDot(theirColor, friendName)
            }
        }
    }
}
