package com.noop.ui.friends

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Smartphone
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendPerson
import com.noop.friends.FriendShare
import com.noop.friends.FriendsError
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.RowIcon
import com.noop.ui.m3.SwitchRow
import kotlinx.coroutines.launch

// MARK: - Friends tab: my profile
//
// Who the reader is to their friends and what they send. There is nothing to type here: the name and the
// photo are the ones of the app's own profile, and the account is known by this phone's key. The page
// holds what friends can see (four sections and the photo; a section switched off is erased from the
// server, and the page says so), the friends with a way to remove one, the phones the account lives on,
// everything the server stores about the reader, and deleting the account for good.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsProfileScreen(
    vm: FriendsViewModel,
    onBack: () -> Unit,
    openAdd: () -> Unit,
    openFriend: (String) -> Unit,
    openPhones: () -> Unit,
    openExport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val me by vm.me.collectAsStateWithLifecycle()
    val feedState by vm.feed.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val lastUploadAt by vm.lastUploadAt.collectAsStateWithLifecycle()
    val sharePhoto by vm.sharePhoto.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()

    var failure by remember { mutableStateOf<FriendsError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FriendPerson?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.reloadProfileName()
        vm.sync()
    }

    // Runs one change, one at a time, and keeps why it failed for the notice.
    fun perform(call: suspend () -> FriendsError?) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            failure = call()
            busy = false
        }
    }

    val profile = me
    val share = profile?.share ?: FriendShare.NONE
    val friends = feedState.feed?.friends.orEmpty()
    // A phone that joined without confirmation changes nothing until it is confirmed.
    val onProbation = profile?.device?.probationUntil != null
    val canChange = !busy && !onProbation

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.friends_profile_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (profile == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                return@LazyColumn
            }

            item(key = "head") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // As friends see them: the account's own picture and name, whichever phone sent them.
                    FriendAvatar(vm.nameFriendsSee(), profile.id, profile.avatarRev, size = 96.dp, tone = AvatarTone.ME)
                    Text(
                        vm.nameFriendsSee(),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
            }
            item(key = "from-settings") {
                PageNote(Icons.Filled.AccountCircle, stringResource(R.string.friends_profile_from_settings))
            }

            feedState.error?.let { error ->
                item(key = "refresh-notice") {
                    FriendsRefreshNotice(
                        error = error,
                        shownAge = if (feedState.feed != null) FriendsAgo.of(feedState.fetchedAt, nowSec) else null,
                        onRetry = { vm.sync(force = true) },
                    )
                }
            }
            failure?.let { error ->
                item(key = "failure") {
                    NoticeCard(
                        icon = Icons.Filled.ErrorOutline,
                        title = friendsErrorText(error),
                        error = error != FriendsError.OFFLINE && error != FriendsError.RATE_LIMITED,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            item(key = "share") {
                ListGroup(header = stringResource(R.string.friends_share_header)) {
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.friends_share_scores), share.scores,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(scores = on)) } },
                            subtitle = stringResource(R.string.friends_share_scores_note),
                            leading = { RowIcon(Icons.Filled.Bolt) }, enabled = canChange,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.today_metric_rest), share.sleep,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(sleep = on)) } },
                            subtitle = stringResource(R.string.friends_share_sleep_note),
                            leading = { RowIcon(Icons.Filled.Bedtime) }, enabled = canChange,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.nav_workouts), share.workouts,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(workouts = on)) } },
                            subtitle = stringResource(R.string.friends_share_workouts_note),
                            leading = { RowIcon(Icons.AutoMirrored.Filled.DirectionsRun) }, enabled = canChange,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.friends_heart_rate), share.hr,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(hr = on)) } },
                            subtitle = stringResource(R.string.friends_share_hr_note),
                            leading = { RowIcon(Icons.Filled.Favorite) }, enabled = canChange,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.friends_share_photo), sharePhoto,
                            onCheckedChange = { on -> perform { vm.setSharePhoto(on); null } },
                            subtitle = stringResource(R.string.friends_share_photo_note),
                            leading = { RowIcon(Icons.Filled.AccountCircle) }, enabled = canChange,
                        )
                    }
                }
            }
            item(key = "share-note") {
                PageNote(Icons.Filled.Lock, stringResource(R.string.friends_share_note))
            }

            item(key = "friends") {
                ListGroup(header = stringResource(R.string.friends_profile_friends, friends.size)) {
                    friends.forEach { person ->
                        item { shape ->
                            FriendRow(person, shape, canRemove = !onProbation, onOpen = { openFriend(person.id) }, onRemove = { removing = person })
                        }
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_add_open),
                            titleColor = MaterialTheme.colorScheme.primary,
                            leading = { RowIcon(Icons.Filled.PersonAdd, tint = MaterialTheme.colorScheme.primary) },
                            onClick = openAdd,
                        )
                    }
                }
            }

            item(key = "account") {
                val sent = FriendsAgo.of(lastUploadAt, nowSec)
                ListGroup(header = stringResource(R.string.friends_account_header)) {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_phones),
                            leading = { RowIcon(Icons.Filled.Smartphone) },
                            trailing = { ChevronRight() },
                            onClick = openPhones,
                        )
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_export_title),
                            leading = { RowIcon(Icons.AutoMirrored.Filled.ManageSearch) },
                            trailing = { ChevronRight() },
                            onClick = openExport,
                        )
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_server),
                            subtitle = if (sent != null) stringResource(R.string.friends_server_sent, serverUrl, agoText(sent))
                            else stringResource(R.string.friends_server_not_sent, serverUrl),
                            leading = { RowIcon(Icons.Filled.Dns) },
                        )
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_delete_account),
                            subtitle = stringResource(R.string.friends_delete_account_note),
                            titleColor = MaterialTheme.colorScheme.error,
                            leading = { RowIcon(Icons.Filled.DeleteForever, tint = MaterialTheme.colorScheme.error) },
                            enabled = canChange,
                            onClick = { confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        // There is no password to ask for: the question itself is the last step before anything is sent.
        ConfirmDialog(
            title = stringResource(R.string.friends_delete_title),
            message = stringResource(R.string.friends_delete_body),
            confirmLabel = stringResource(R.string.friends_delete_confirm),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                // On success Friends goes off, the tab returns to its first page and this page goes with it.
                perform { vm.deleteAccount() }
            },
        )
    }

    removing?.let { person ->
        ConfirmDialog(
            title = stringResource(R.string.friends_remove_title, person.profile.name),
            message = stringResource(R.string.friends_remove_body),
            confirmLabel = stringResource(R.string.friends_remove_confirm),
            destructive = true,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                perform { vm.unfriend(person.id) }
            },
        )
    }
}

/** A line of explanation under a group, led by a small glyph. */
@Composable
private fun PageNote(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A friend in the profile's list: tap opens their page, the menu removes them. */
@Composable
private fun FriendRow(
    person: FriendPerson,
    shape: androidx.compose.ui.graphics.Shape,
    canRemove: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListRow(
        shape = shape,
        title = person.profile.name,
        leading = { FriendAvatar(person.profile.name, person.id, person.profile.avatarRev, size = 40.dp) },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }, enabled = canRemove) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.friends_more_for, person.profile.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.friends_remove_friend)) },
                        leadingIcon = { Icon(Icons.Filled.PersonRemove, contentDescription = null) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        },
        onClick = onOpen,
    )
}
