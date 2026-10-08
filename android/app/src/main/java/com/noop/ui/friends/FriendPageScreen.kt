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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendDay
import com.noop.friends.FriendPerson
import com.noop.friends.FriendShare
import com.noop.friends.FriendSleep
import com.noop.friends.FriendWorkout
import com.noop.friends.FriendsError
import com.noop.friends.FriendsResult
import com.noop.ui.AppViewModel
import com.noop.ui.EffortScale
import com.noop.ui.UnitPrefs
import com.noop.ui.m3.CardTitleRow
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.CookieShape
import com.noop.ui.m3.ExpressiveBar
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LevelBadge
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
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
import java.time.LocalDate

// MARK: - Friends tab: one friend's page
//
// The friend's day in full, top to bottom: Recovery on its cookie with its level, Strain and the Sleep
// score, today's heart rate as a line (the one figure the feed leaves out, fetched for this page), last
// night's stages, the week's workouts, the week against the reader's, and a closing line that says what
// this friend shares. The page shows the feed's copy at once and the fuller answer when it arrives; with
// no connection it stays on the feed's copy and says so.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendPageScreen(appVm: AppViewModel, vm: FriendsViewModel, nick: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedState by vm.feed.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val page = pages[nick] ?: FriendPageState(loading = true)
    val nowSec = rememberNowSec()
    val today = rememberFriendsToday(appVm, nowSec)
    val scale = remember { UnitPrefs.effortScale(context) }
    val serverNow = vm.serverNow(nowSec)

    LaunchedEffect(nick) { vm.loadPage(nick) }

    val fromFeed = feedState.feed?.friends?.firstOrNull { it.nick == nick }
    val person = page.person ?: fromFeed
    val me = feedState.feed?.me
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<FriendsError?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = person?.profile?.name ?: stringResource(R.string.friends_handle, nick),
            onBack = onBack,
            actions = {
                if (person != null) {
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
                        onRetry = { removeError = null; vm.loadPage(nick) },
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
            val day = person.day(today.key)
            val mine = me?.day(today.key)

            item(key = "head") { PageHead(person, serverNow) }

            if (!person.share.scores) {
                item(key = "no-scores") { FriendsNote(Icons.Filled.Bolt, stringResource(R.string.friends_not_sharing_scores, name)) }
            } else {
                item(key = "recovery") { RecoveryCard(day?.recovery, mine?.recovery) }
                item(key = "strain") {
                    val strain = day?.strain
                    ScoreCard(
                        title = stringResource(R.string.today_metric_effort),
                        icon = Icons.Filled.LocalFireDepartment,
                        tint = Health.colors.effort,
                        value = strain?.let { strainShown(it, scale) },
                        unit = strainOutOf(scale),
                        level = strain?.let { strainLevelText(it) },
                        fraction = strain?.let { (it / 100.0).toFloat() },
                        note = mine?.strain?.let { stringResource(R.string.friends_page_yours, strainShown(it, scale)) },
                    )
                }
                item(key = "sleep-score") {
                    val score = day?.sleepScore
                    ScoreCard(
                        title = stringResource(R.string.today_metric_rest),
                        icon = Icons.Filled.Bedtime,
                        tint = Health.colors.rest,
                        value = score?.toString(),
                        unit = stringResource(R.string.friends_out_of, "100"),
                        level = score?.let { sleepLevelText(it) },
                        fraction = score?.let { it / 100f },
                        note = mine?.sleepScore?.let { stringResource(R.string.friends_page_yours, it.toString()) },
                    )
                }
            }

            item(key = "hr") { HeartRateCard(person.share, day, serverNow, name, loadingLine = page.loading && page.person == null) }

            item(key = "night") { NightCard(person.share, day?.sleep, mine?.sleep, name) }

            item(key = "workouts-header") {
                SectionHeader(stringResource(R.string.nav_workouts), Modifier.padding(horizontal = M3Dimens.screenPadding))
            }
            item(key = "workouts") { WorkoutsCard(person, today.date, scale) }

            item(key = "week-header") {
                SectionHeader(stringResource(R.string.friends_week), Modifier.padding(horizontal = M3Dimens.screenPadding))
            }
            item(key = "week") {
                WeekCard(
                    week = remember(person, me, today.key) { FriendsWeek.of(today.key, me, person) },
                    friendName = name,
                    friendShares = person.share.scores,
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                )
            }

            item(key = "shares") { FriendsNote(Icons.Filled.Visibility, sharesSentence(name, person.share)) }
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
                    when (val answer = vm.unfriend(nick)) {
                        is FriendsResult.Ok -> onBack()
                        is FriendsResult.Fail -> removeError = answer.error
                    }
                }
            },
        )
    }
}

/** The friend's picture, name and nickname, and when their phone last uploaded. */
@Composable
private fun PageHead(person: FriendPerson, serverNow: Long) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FriendAvatar(person.profile.name, person.nick, person.profile.avatarRev, size = 96.dp)
        Text(
            person.profile.name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.friends_handle, person.nick),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val updated = FriendsAgo.of(person.lastUpdatedAt, serverNow)
        FriendsPill(
            Icons.Filled.Sync,
            if (updated != null) stringResource(R.string.summary_updated, agoText(updated))
            else stringResource(R.string.friends_no_upload_yet),
        )
    }
}

/** Recovery: the figure on its cookie, its level, and the reader's own beside it in words. */
@Composable
private fun RecoveryCard(recovery: Int?, mine: Int?) {
    val tint = Health.colors.charge
    HealthCard(
        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 16.dp,
    ) {
        CardTitleRow(Icons.Filled.Bolt, stringResource(R.string.today_metric_charge), tint, chevron = false)
        if (recovery == null) {
            Text(stringResource(R.string.friends_no_data_today), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@HealthCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(112.dp).clip(CookieShape).background(tint), contentAlignment = Alignment.Center) {
                Text(
                    recovery.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.surface,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LevelBadge(recoveryLevelText(recovery), fill = tint)
                Text(
                    stringResource(R.string.friends_score_of_100, recovery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (mine != null) {
                    Text(
                        stringResource(R.string.friends_page_yours, mine.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ExpressiveBar(recovery / 100f, tint, height = 16.dp)
    }
}

/** One score as a card: the figure with what it is out of, its level, a bar, and the reader's own in words. */
@Composable
private fun ScoreCard(
    title: String,
    icon: ImageVector,
    tint: Color,
    value: String?,
    unit: String,
    level: String?,
    fraction: Float?,
    note: String?,
) {
    HealthCard(
        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 12.dp,
    ) {
        CardTitleRow(icon, title, tint, chevron = false)
        if (value == null) {
            Text(stringResource(R.string.friends_no_data_today), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@HealthCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueWithUnit(value, unit, modifier = Modifier.weight(1f))
            if (level != null) LevelBadge(level, fill = tint)
        }
        ExpressiveBar(fraction ?: 0f, tint, height = 12.dp)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Today's heart rate: the latest reading, the resting figure, and the day as a line when it is shared. */
@Composable
private fun HeartRateCard(share: FriendShare, day: FriendDay?, serverNow: Long, name: String, loadingLine: Boolean) {
    val hr = day?.hr
    when {
        !share.hr -> FriendsNote(Icons.Filled.Favorite, stringResource(R.string.friends_not_sharing_hr, name))
        hr == null -> FriendsNote(Icons.Filled.Favorite, stringResource(R.string.friends_no_hr_today, name))
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

/** Last night: how long, when, each stage's time with its share of the night as a bar, and the reader's own night. */
@Composable
private fun NightCard(share: FriendShare, sleep: FriendSleep?, mine: FriendSleep?, name: String) {
    when {
        !share.sleep -> FriendsNote(Icons.Filled.Bedtime, stringResource(R.string.friends_not_sharing_sleep, name))
        sleep == null -> FriendsNote(Icons.Filled.Bedtime, stringResource(R.string.friends_no_sleep_today))
        else -> HealthCard(
            modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
            shape = RoundedCornerShape(M3Dimens.heroRadius),
            contentPadding = PaddingValues(20.dp),
            verticalSpacing = 12.dp,
        ) {
            CardTitleRow(Icons.Filled.Bedtime, stringResource(R.string.friends_last_night), Health.colors.sleep, chevron = false)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    sleepDuration(sleep.asleepMin.toDouble()),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Health.colors.sleep,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.friends_time_range, friendsClock(sleep.startTs), friendsClock(sleep.endTs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            FriendsSleepGoal.of(sleep)?.let { goal ->
                Text(sleepGoalText(goal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (mine != null) {
                Text(
                    stringResource(R.string.friends_page_your_sleep, sleepDuration(mine.asleepMin.toDouble())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The week's workouts, newest first: what, how long, how hard, and when. */
@Composable
private fun WorkoutsCard(person: FriendPerson, today: LocalDate, scale: EffortScale) {
    val name = person.profile.name
    if (!person.share.workouts) {
        FriendsNote(Icons.AutoMirrored.Filled.DirectionsRun, stringResource(R.string.friends_not_sharing_workouts, name))
        return
    }
    val workouts = remember(person) {
        person.days.flatMap { it.workouts.orEmpty() }.sortedByDescending { it.startTs }.take(MAX_WORKOUT_ROWS)
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
        workouts.forEach { WorkoutLine(it, today, scale) }
    }
}

private const val MAX_WORKOUT_ROWS = 12

@Composable
private fun WorkoutLine(workout: FriendWorkout, today: LocalDate, scale: EffortScale) {
    val strain = workout.strain
    val subtitle = when {
        strain != null && workout.avgHr != null -> stringResource(
            R.string.friends_workout_line_both, strainShown(strain, scale), strainLevelText(strain), workout.avgHr,
        )
        strain != null -> stringResource(R.string.friends_workout_line_strain, strainShown(strain, scale), strainLevelText(strain))
        workout.avgHr != null -> stringResource(R.string.friends_event_workout_hr, workout.avgHr)
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
        Text(
            eventStamp(workout.startTs + workout.durationS, today),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** "Denis shares: scores, sleep. Doesn't share: heart rate." */
@Composable
internal fun sharesSentence(name: String, share: FriendShare): String {
    val (on, off) = share.sections()
    val separator = stringResource(R.string.friends_list_separator)
    val names = FriendsSection.entries.associateWith { sectionName(it) }
    fun joined(sections: List<FriendsSection>) = sections.joinToString(separator) { names.getValue(it) }
    return when {
        off.isEmpty() -> stringResource(R.string.friends_shares_all, name, joined(on))
        on.isEmpty() -> stringResource(R.string.friends_shares_nothing, name)
        else -> stringResource(R.string.friends_shares_some, name, joined(on), joined(off))
    }
}

@Composable
internal fun sectionName(section: FriendsSection): String = stringResource(
    when (section) {
        FriendsSection.SCORES -> R.string.friends_section_scores
        FriendsSection.SLEEP -> R.string.friends_section_sleep
        FriendsSection.WORKOUTS -> R.string.friends_section_workouts
        FriendsSection.HEART_RATE -> R.string.friends_section_hr
    },
)
