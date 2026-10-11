package com.noop.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ui.summary.keyMetricHealthIcon
import com.noop.ui.KeyMetric
import com.noop.R
import com.noop.friends.FriendDay
import com.noop.friends.FriendPerson
import com.noop.friends.FriendShare
import com.noop.friends.FriendSleep
import com.noop.friends.FriendWorkout
import com.noop.friends.FriendsError
import com.noop.ui.AppToday
import com.noop.ui.AppViewModel
import com.noop.ui.EffortScale
import com.noop.ui.UnitPrefs
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.ExpressiveBar
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.ValueWithUnit
import com.noop.ui.sleep.SleepStageRow
import com.noop.ui.sleep.sleepDuration
import com.noop.ui.sleep.stageColor
import com.noop.ui.sleep.stageName
import com.noop.ui.workouts.SportBadge
import com.noop.ui.workouts.sportLabel
import kotlinx.coroutines.launch

// MARK: - Friends tab: one person's page
//
// A friend's day against the reader's own, top to bottom: the picture and the name, the three scores of
// the day side by side with one sentence on who leads, the heart rate when it is shared (the day's line is
// the one figure the feed leaves out, fetched for this page), the two nights, then the last seven days as
// seven rings and the workouts of those days. A ring picks its day: the comparison and every figure above
// then describe that day. The page opens on the person's newest day, which is not always today.
//
// The reader's own card on the list opens the same page for their own account, as friends see it: the
// day's three figures beside the rings in place of a comparison, and no menu.
//
// The page shows the feed's copy at once and the fuller answer when it arrives; with no connection it
// stays on the feed's copy and says so.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendPageScreen(appVm: AppViewModel, vm: FriendsViewModel, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedState by vm.feed.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val page = pages[id] ?: FriendPageState(loading = true)
    val nowSec = rememberNowSec()
    val today = rememberFriendsToday(appVm, nowSec)
    val scale = remember { UnitPrefs.effortScale(context) }
    val serverNow = vm.serverNow(nowSec)

    LaunchedEffect(id) { vm.loadPage(id) }

    val me = feedState.feed?.me
    val isMe = me != null && me.id == id
    val fromFeed = if (isMe) me else feedState.feed?.friends?.firstOrNull { it.id == id }
    val person = page.person ?: fromFeed
    var picked by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<FriendsError?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = person?.profile?.name ?: stringResource(R.string.nav_friends),
            onBack = onBack,
            actions = {
                if (person != null && !isMe) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.friends_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.friends_remove_friend)) },
                                leadingIcon = { Icon(Icons.Filled.PersonRemove, contentDescription = null) },
                                onClick = { menuOpen = false; confirmRemove = true },
                            )
                        }
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            (removeError ?: page.error)?.let { error ->
                item(key = "notice") {
                    FriendsRefreshNotice(
                        error = error,
                        shownAge = if (page.person == null && fromFeed != null) FriendsAgo.of(feedState.fetchedAt, nowSec) else null,
                        onRetry = { removeError = null; vm.loadPage(id) },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }
            if (person == null) {
                if (page.error == null) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                return@LazyColumn
            }

            val name = person.profile.name
            // The day in view: the one picked from its ring while the person still has it, else their newest.
            val shownKey = picked?.takeIf { person.day(it) != null } ?: person.days.firstOrNull()?.day ?: today.key
            val day = person.day(shownKey)
            val mine = me?.day(shownKey)

            item(key = "head") { PageHead(person, isMe, serverNow) }

            item(key = "day") {
                SectionHeader(friendsDayText(shownKey, today, weekdays = false), Modifier.padding(horizontal = M3Dimens.screenPadding))
            }
            item(key = "scores") {
                when {
                    isMe -> OwnDayCard(person, day, scale)
                    me != null -> HeadToHeadCard(me, person, mine, day, scale)
                }
            }

            item(key = "hr") {
                HeartRateCard(person.share, day, serverNow, name, isMe, loadingLine = page.loading && page.person == null)
            }

            item(key = "night") { NightsCard(me?.takeIf { !isMe }, person, mine?.sleep, day?.sleep, isMe) }

            item(key = "week-header") {
                SectionHeader(stringResource(R.string.friends_last_7_days), Modifier.padding(horizontal = M3Dimens.screenPadding))
            }
            item(key = "week") {
                // A person who shares no scores has no ring to draw, and so no day to pick.
                val days = FriendWeek.days(
                    FriendWeek.anchor(today.key, person.days.firstOrNull()?.day),
                    if (person.share.scores) person.days else emptyList(),
                )
                WeekRings(days, shownKey, today, scale, onPick = { picked = it }, Modifier.padding(horizontal = M3Dimens.screenPadding))
            }

            item(key = "workouts-header") {
                SectionHeader(stringResource(R.string.nav_workouts), Modifier.padding(horizontal = M3Dimens.screenPadding))
            }
            item(key = "workouts") { WorkoutsCard(person, isMe, today, scale) }
        }
    }

    if (confirmRemove && person != null) {
        ConfirmDialog(
            title = stringResource(R.string.friends_remove_title, person.profile.name),
            message = stringResource(R.string.friends_remove_body),
            confirmLabel = stringResource(R.string.friends_remove_confirm),
            destructive = true,
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                scope.launch {
                    val failed = vm.unfriend(id)
                    if (failed == null) onBack() else removeError = failed
                }
            },
        )
    }
}

/** The person's picture and name, and when their phone last uploaded. */
@Composable
private fun PageHead(person: FriendPerson, isMe: Boolean, serverNow: Long) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FriendAvatar(
            person.profile.name, person.id, person.profile.avatarRev, size = 96.dp,
            tone = if (isMe) AvatarTone.ME else AvatarTone.FRIEND,
        )
        Text(
            person.profile.name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        val updated = FriendsAgo.of(person.lastUpdatedAt, serverNow)
        FriendsPill(
            Icons.Filled.Sync,
            if (updated != null) stringResource(R.string.summary_updated, agoText(updated))
            else stringResource(R.string.friends_no_data_today),
        )
    }
}

// MARK: The day

/** The day's three scores for two people, and one or two sentences on who leads. */
@Composable
private fun HeadToHeadCard(me: FriendPerson, friend: FriendPerson, mine: FriendDay?, theirs: FriendDay?, scale: EffortScale) {
    val name = friend.profile.name
    val you = stringResource(R.string.friends_you)
    val health = Health.colors
    HealthCard(
        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 20.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FriendAvatar(me.profile.name, me.id, me.profile.avatarRev, size = 56.dp, tone = AvatarTone.ME)
                Text(you, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
            Row(
                Modifier.weight(1f),
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
                FriendAvatar(name, friend.id, friend.profile.avatarRev, size = 56.dp)
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
            title = recoveryTitle, icon = keyMetricHealthIcon(KeyMetric.CHARGE), tint = health.charge,
            left = myRecovery, right = theirRecovery,
            leftLeads = leads(mine?.recovery?.toDouble(), theirs?.recovery?.toDouble()),
            missing = missing, description = spoken(recoveryTitle, myRecovery, theirRecovery),
        )

        val strainTitle = stringResource(R.string.today_metric_effort)
        val myStrain = strainSide(mine?.strain, scale)
        val theirStrain = strainSide(theirs?.strain, scale)
        ScoreDuel(
            title = strainTitle, icon = keyMetricHealthIcon(KeyMetric.EFFORT), tint = health.effort,
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
private fun recoverySide(recovery: Int?): DuelSide =
    if (recovery == null) DuelSide(null, null, null)
    else DuelSide(recovery.toString(), recovery / 100f, recoveryLevelText(recovery))

@Composable
private fun strainSide(strain: Double?, scale: EffortScale): DuelSide =
    if (strain == null) DuelSide(null, null, null)
    else DuelSide(strainShown(strain, scale), (strain / 100.0).toFloat(), strainLevelText(strain))

@Composable
private fun sleepScoreSide(score: Int?): DuelSide =
    if (score == null) DuelSide(null, null, null)
    else DuelSide(score.toString(), score / 100f, sleepLevelText(score))

/** One comparison sentence. Names stand in the nominative, so no name is ever declined or given a gender. */
@Composable
private fun comparisonText(line: FriendsComparisonLine, friendName: String): String = when (line) {
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

/** The reader's own day as friends see it: the three figures, each in its ring's hue, beside the rings. */
@Composable
private fun OwnDayCard(me: FriendPerson, day: FriendDay?, scale: EffortScale) {
    val health = Health.colors
    HealthCard(
        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 16.dp,
    ) {
        if (!me.share.scores) {
            Text(
                stringResource(R.string.friends_you_not_sharing_scores),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@HealthCard
        }
        val none = stringResource(R.string.friends_no_value)
        val figures: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val recovery = stringResource(R.string.today_metric_charge)
                val strain = stringResource(R.string.today_metric_effort)
                val sleep = stringResource(R.string.today_metric_rest)
                OwnFigure(recovery, day?.recovery?.let { friendsPercent(it.toDouble()) } ?: none, null, health.charge)
                OwnFigure(strain, day?.strain?.let { strainShown(it, scale) } ?: none, day?.strain?.let { strainOutOf(scale) }, health.effort)
                OwnFigure(sleep, day?.sleepScore?.let { friendsPercent(it.toDouble()) } ?: none, null, health.rest)
            }
        }
        if (friendsLargeText()) {
            figures(Modifier.fillMaxWidth())
            FriendRings(day, size = OWN_RINGS, stroke = 14.dp, gap = 3.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                figures(Modifier.weight(1f))
                FriendRings(day, size = OWN_RINGS, stroke = 14.dp, gap = 3.dp)
            }
        }
    }
}

private val OWN_RINGS = 128.dp

/** One of the day's three figures: its name over the figure in its ring's hue. */
@Composable
private fun OwnFigure(title: String, figure: String, unit: String?, hue: Color) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        FriendsFigure(figure, unit, hue, spoken = null)
    }
}

/** The day's heart rate: the latest reading, the resting figure, and the day as a line when it is shared. */
@Composable
private fun HeartRateCard(share: FriendShare, day: FriendDay?, serverNow: Long, name: String, isMe: Boolean, loadingLine: Boolean) {
    val hr = day?.hr
    when {
        !share.hr -> FriendsNote(
            Icons.Filled.Favorite,
            if (isMe) stringResource(R.string.friends_you_not_sharing_hr) else stringResource(R.string.friends_not_sharing_hr, name),
        )
        hr == null -> FriendsNote(Icons.Filled.Favorite, stringResource(R.string.friends_no_hr_day))
        else -> HealthCard(
            modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
            shape = RoundedCornerShape(M3Dimens.heroRadius),
            contentPadding = PaddingValues(20.dp),
            verticalSpacing = 12.dp,
        ) {
            CardTitleRow(Icons.Filled.Favorite, stringResource(R.string.friends_heart_rate), Health.colors.heart, chevron = false)
            ValueWithUnit(hr.lastBpm.toString(), stringResource(R.string.metric_unit_bpm))
            val age = FriendsAgo.of(hr.lastTs, serverNow)
            Text(
                if (age != null) stringResource(R.string.friends_hr_last_reading_at, friendsClock(hr.lastTs), agoText(age))
                else stringResource(R.string.friends_hr_last_reading_time, friendsClock(hr.lastTs)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hr.restingBpm?.let {
                Text(stringResource(R.string.friends_hr_resting, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                hr.series.size >= 2 -> HeartRateLine(hr.series, description = stringResource(R.string.friends_hr_line_spoken, name))
                loadingLine -> Text(
                    stringResource(R.string.friends_hr_line_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The night of the day in view: the reader's beside the person's, each with how long, from when to when
 * and how it stands against its need, then the stages of the person's night. On the reader's own page
 * there is one night, their own.
 */
@Composable
private fun NightsCard(me: FriendPerson?, person: FriendPerson, mine: FriendSleep?, theirs: FriendSleep?, isMe: Boolean) {
    val name = person.profile.name
    HealthCard(
        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 16.dp,
    ) {
        CardTitleRow(Icons.Filled.Bedtime, stringResource(R.string.summary_time_asleep), Health.colors.sleep, chevron = false)
        if (me != null) {
            SleepSide(stringResource(R.string.friends_you), mine, shares = me.share.sleep, isMe = true, name = name)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        SleepSide(if (isMe) stringResource(R.string.friends_you) else name, theirs, shares = person.share.sleep, isMe = isMe, name = name)
        if (theirs != null) SleepStages(theirs)
    }
}

/** One person's night: how long, from when to when, and how it stands against its need. */
@Composable
private fun SleepSide(label: String, sleep: FriendSleep?, shares: Boolean, isMe: Boolean, name: String) {
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
            }
        }
        when {
            sleep != null -> {
                Text(
                    stringResource(R.string.friends_time_range, friendsClock(sleep.startTs), friendsClock(sleep.endTs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
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
                stringResource(R.string.friends_no_sleep_day),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun sleepGoalText(goal: FriendsSleepGoal): String {
    val need = sleepDuration(goal.needMin.toDouble())
    return when (goal) {
        is FriendsSleepGoal.Short -> stringResource(R.string.friends_sleep_short, sleepDuration(goal.byMin.toDouble()), need)
        is FriendsSleepGoal.Almost -> stringResource(R.string.friends_sleep_almost, need)
        is FriendsSleepGoal.Met -> stringResource(R.string.friends_sleep_met, need)
    }
}

/** Each stage's time, with its share of the longest stage as a bar. */
@Composable
private fun SleepStages(sleep: FriendSleep) {
    val stages = listOf(
        SleepStageRow.AWAKE to sleep.awakeMin,
        SleepStageRow.REM to sleep.remMin,
        SleepStageRow.CORE to sleep.lightMin,
        SleepStageRow.DEEP to sleep.deepMin,
    ).mapNotNull { (row, minutes) -> minutes?.let { row to it } }
    val longest = stages.maxOfOrNull { it.second }?.takeIf { it > 0 }
    stages.forEach { (row, minutes) ->
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stageName(row), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(88.dp), maxLines = 1)
            ExpressiveBar(
                if (longest != null) minutes.toFloat() / longest else 0f,
                stageColor(row),
                Modifier.weight(1f),
                height = 10.dp,
            )
            Text(
                sleepDuration(minutes.toDouble()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

// MARK: The last seven days

/**
 * Seven small rings under the letters of their weekdays, oldest first. The day in view has its letter on
 * a disc; a ring picks its day. A day with nothing uploaded cannot be picked.
 */
@Composable
private fun WeekRings(
    days: List<FriendWeek.Day>,
    shownKey: String,
    today: AppToday,
    scale: EffortScale,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = friendsLocale()
    HealthCard(modifier = modifier, shape = RoundedCornerShape(M3Dimens.heroRadius), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            days.forEach { item ->
                val shown = item.key == shownKey
                val spoken = weekDaySpoken(item, today, scale)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(M3Dimens.groupOuter))
                        .selectable(selected = shown, enabled = item.summary != null, role = Role.Tab, onClick = { onPick(item.key) })
                        .clearAndSetSemantics { contentDescription = spoken }
                        .heightIn(min = M3Dimens.minTouch)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (shown) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            friendsWeekdayLetter(item.key, locale),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (shown) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    FriendRings(item.summary, size = 36.dp, stroke = 4.dp, gap = 1.5.dp)
                }
            }
        }
    }
}

/** A day of the week as TalkBack names it: the day, then its three scores, or that it has none. */
@Composable
private fun weekDaySpoken(item: FriendWeek.Day, today: AppToday, scale: EffortScale): String {
    val parts = mutableListOf(friendsDayText(item.key, today, weekdays = true))
    val summary = item.summary
    if (summary == null) {
        parts += stringResource(R.string.friends_no_data_today)
    } else {
        summary.recovery?.let { parts += stringResource(R.string.today_metric_charge) + " " + friendsPercent(it.toDouble()) }
        summary.strain?.let { parts += stringResource(R.string.today_metric_effort) + " " + strainShown(it, scale) }
        summary.sleepScore?.let { parts += stringResource(R.string.today_metric_rest) + " " + friendsPercent(it.toDouble()) }
    }
    return parts.joinToString(", ")
}

// MARK: Workouts

/** The workouts of the days the page covers, newest first, each dated by the day it was uploaded for. */
@Composable
private fun WorkoutsCard(person: FriendPerson, isMe: Boolean, today: AppToday, scale: EffortScale) {
    if (!person.share.workouts) {
        FriendsNote(
            Icons.AutoMirrored.Filled.DirectionsRun,
            if (isMe) stringResource(R.string.friends_you_not_sharing_workouts)
            else stringResource(R.string.friends_not_sharing_workouts, person.profile.name),
        )
        return
    }
    val workouts = remember(person) {
        person.days.flatMap { day -> day.workouts.orEmpty().map { day.day to it } }.sortedByDescending { it.second.startTs }
    }
    HealthCard(
        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalSpacing = 0.dp,
    ) {
        if (workouts.isEmpty()) {
            Text(
                stringResource(R.string.friends_no_workouts_week),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        workouts.forEach { (dayKey, workout) -> WorkoutLine(workout, friendsDayText(dayKey, today, weekdays = true), scale) }
    }
}

/** One workout: what, how long, how hard, and on which day. */
@Composable
private fun WorkoutLine(workout: FriendWorkout, dayLabel: String, scale: EffortScale) {
    val strain = workout.strain
    val subtitle = when {
        strain != null && workout.avgHr != null -> stringResource(
            R.string.friends_workout_line_both, strainShown(strain, scale), strainLevelText(strain), workout.avgHr,
        )
        strain != null -> stringResource(R.string.friends_workout_line_strain, strainShown(strain, scale), strainLevelText(strain))
        workout.avgHr != null -> stringResource(R.string.friends_workout_line_hr, workout.avgHr)
        else -> null
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
        SportBadge(workout.sport)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.friends_workout_title, sportLabel(workout.sport), sleepDuration(workout.durationS / 60.0)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(dayLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
