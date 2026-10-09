package com.noop.ui.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ui.AppViewModel
import com.noop.ui.EffortScale
import com.noop.ui.UnitPrefs
import com.noop.ui.m3.ExpressiveBar
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LevelBadge
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar

// MARK: - Friends tab: the list (everyone at a glance)
//
// One card per person for today, the reader's own among them and marked as theirs: the name, what their
// heart is doing if they share it, Recovery large with its level, and the three scores as bars with the
// figure and its level in words under each. The chips choose which score orders the list.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsListScreen(appVm: AppViewModel, vm: FriendsViewModel, onBack: () -> Unit, openFriend: (String) -> Unit) {
    val context = LocalContext.current
    val state by vm.feed.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()
    val today = rememberFriendsToday(appVm, nowSec)
    val scale = remember { UnitPrefs.effortScale(context) }
    val serverNow = vm.serverNow(nowSec)
    var sort by rememberSaveable { mutableStateOf(FriendsSort.RECOVERY) }
    val feed = state.feed

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.friends_list_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
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

            item(key = "sort") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = M3Dimens.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(FriendsSort.entries, key = { it.name }) { option ->
                        FilterChip(
                            selected = option == sort,
                            onClick = { sort = option },
                            label = { Text(sortLabel(option)) },
                            leadingIcon = if (option == sort) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                            modifier = Modifier.heightIn(min = M3Dimens.minTouch),
                        )
                    }
                }
            }

            val entries = FriendsList.entries(feed, sort, today.key)
            // A nickname may be any word, "sort" included, so a person's key carries a prefix no fixed key has.
            items(entries, key = { "person:${it.person.nick}" }) { entry ->
                PersonCard(
                    entry = entry,
                    scale = scale,
                    serverNow = serverNow,
                    onClick = if (entry.isMe) null else { -> openFriend(entry.person.nick) },
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                )
            }

            item(key = "cadence") { FriendsNote(Icons.Filled.Schedule, stringResource(R.string.friends_cadence_note)) }
        }
    }
}

@Composable
private fun sortLabel(sort: FriendsSort): String = stringResource(
    when (sort) {
        FriendsSort.RECOVERY -> R.string.today_metric_charge
        FriendsSort.STRAIN -> R.string.today_metric_effort
        FriendsSort.SLEEP -> R.string.today_metric_rest
    },
)

/** One person's day as a card. The reader's own carries a primary outline and says whose it is. */
@Composable
private fun PersonCard(
    entry: FriendsListEntry,
    scale: EffortScale,
    serverNow: Long,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val person = entry.person
    val day = entry.day
    val health = Health.colors
    val shape = RoundedCornerShape(M3Dimens.heroRadius)
    val name = if (entry.isMe) stringResource(R.string.friends_you) else person.profile.name
    HealthCard(
        modifier = if (entry.isMe) modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape) else modifier,
        onClick = onClick,
        onClickLabel = if (onClick != null) stringResource(R.string.friends_open_page, person.profile.name) else null,
        shape = shape,
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FriendAvatar(
                person.profile.name, person.nick, person.profile.avatarRev, size = 56.dp,
                tone = if (entry.isMe) AvatarTone.ME else AvatarTone.FRIEND,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    personLine(entry, serverNow),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            day?.recovery?.let { recovery ->
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        recovery.toString(),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                        color = health.charge,
                    )
                    if (recoveryIsHigh(recovery)) {
                        LevelBadge(recoveryLevelText(recovery), fill = health.charge)
                    } else {
                        LevelBadge(
                            recoveryLevelText(recovery),
                            fill = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ink = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (!person.share.scores) {
            Text(
                if (entry.isMe) stringResource(R.string.friends_you_not_sharing_scores)
                else stringResource(R.string.friends_not_sharing_scores, person.profile.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val none = stringResource(R.string.friends_no_data_today)
                ScoreColumn(
                    stringResource(R.string.today_metric_charge), health.charge, day?.recovery?.let { it / 100f },
                    day?.recovery?.let { stringResource(R.string.friends_score_of_100, it) } ?: none,
                    Modifier.weight(1f),
                )
                ScoreColumn(
                    stringResource(R.string.today_metric_effort), health.effort, day?.strain?.let { (it / 100.0).toFloat() },
                    day?.strain?.let { stringResource(R.string.friends_value_with_level, strainShown(it, scale), strainLevelText(it)) } ?: none,
                    Modifier.weight(1f),
                )
                ScoreColumn(
                    stringResource(R.string.today_metric_rest), health.rest, day?.sleepScore?.let { it / 100f },
                    day?.sleepScore?.let { stringResource(R.string.friends_value_with_level, it.toString(), sleepLevelText(it)) } ?: none,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

/** The line under a name: whose card this is, or the friend's latest heart rate, or when they last uploaded. */
@Composable
private fun personLine(entry: FriendsListEntry, serverNow: Long): String {
    if (entry.isMe) return stringResource(R.string.friends_list_me_note)
    val hr = entry.day?.hr
    val hrAge = hr?.let { FriendsAgo.of(it.lastTs, serverNow) }
    if (hr != null && hrAge != null) {
        return stringResource(R.string.friends_list_hr_line, hr.lastBpm, agoText(hrAge))
    }
    val updated = FriendsAgo.of(entry.person.lastUpdatedAt, serverNow)
    return if (updated != null) stringResource(R.string.summary_updated, agoText(updated))
    else stringResource(R.string.friends_no_upload_yet)
}

/** One score in a third of the card: its name in its hue, a bar, and the figure with its meaning. */
@Composable
private fun ScoreColumn(title: String, color: Color, fraction: Float?, caption: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ExpressiveBar(fraction ?: 0f, color, height = 10.dp)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
