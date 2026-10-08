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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendPerson
import com.noop.friends.FriendShare
import com.noop.friends.FriendsError
import com.noop.friends.FriendsNick
import com.noop.friends.FriendsResult
import com.noop.ui.ProfileAvatarStore
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
// Who the reader is to their friends and what they send: the picture and the name, the four sharing
// switches (a section switched off is erased from the server, and the page says so), the friends with a
// way to remove one, and the account: its server, the password, signing out on this phone, and deleting
// the account for good.

/** A dialog the profile can have open. */
private enum class ProfileDialog { NAME, PASSWORD, SIGN_OUT, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsProfileScreen(vm: FriendsViewModel, onBack: () -> Unit, openAdd: () -> Unit, openFriend: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val me by vm.me.collectAsStateWithLifecycle()
    val feedState by vm.feed.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val lastUploadAt by vm.lastUploadAt.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()

    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    var failure by remember { mutableStateOf<FriendsError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pictureMenu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FriendPerson?>(null) }
    var passwordChanged by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    // Runs one profile change, one at a time, and keeps why it failed for the notice.
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
                    Box {
                        FriendAvatar(profile.name, profile.nick, profile.avatarRev, size = 96.dp, tone = AvatarTone.ME)
                        Box(Modifier.align(Alignment.BottomEnd)) {
                            FilledTonalIconButton(onClick = { pictureMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.friends_picture_change), modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = pictureMenu, onDismissRequest = { pictureMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.friends_picture_use_profile)) },
                                    enabled = ProfileAvatarStore.hasAvatar && !busy,
                                    onClick = { pictureMenu = false; perform { vm.uploadProfilePhoto() } },
                                )
                                if (profile.avatarRev > 0) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.friends_picture_remove)) },
                                        enabled = !busy,
                                        onClick = { pictureMenu = false; perform { vm.removePicture() } },
                                    )
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            stringResource(R.string.friends_handle, profile.nick),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { dialog = ProfileDialog.NAME }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.friends_name_edit))
                    }
                }
            }
            if (!ProfileAvatarStore.hasAvatar && profile.avatarRev == 0) {
                item(key = "picture-hint") {
                    Text(
                        stringResource(R.string.friends_picture_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            feedState.error?.let { error ->
                item(key = "refresh-notice") {
                    FriendsRefreshNotice(
                        error = error,
                        shownAge = if (feedState.feed != null) FriendsAgo.of(feedState.fetchedAt, nowSec) else null,
                        onRetry = { vm.refresh(force = true) },
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
            if (passwordChanged) {
                item(key = "password-changed") {
                    NoticeCard(icon = Icons.Filled.Key, title = stringResource(R.string.friends_password_changed))
                }
            }

            item(key = "share") {
                ListGroup(header = stringResource(R.string.friends_share_header)) {
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.friends_share_scores), share.scores,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(scores = on)) } },
                            subtitle = stringResource(R.string.friends_share_scores_note),
                            leading = { RowIcon(Icons.Filled.Bolt) }, enabled = !busy,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.today_metric_rest), share.sleep,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(sleep = on)) } },
                            subtitle = stringResource(R.string.friends_share_sleep_note),
                            leading = { RowIcon(Icons.Filled.Bedtime) }, enabled = !busy,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.nav_workouts), share.workouts,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(workouts = on)) } },
                            subtitle = stringResource(R.string.friends_share_workouts_note),
                            leading = { RowIcon(Icons.AutoMirrored.Filled.DirectionsRun) }, enabled = !busy,
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape, stringResource(R.string.friends_heart_rate), share.hr,
                            onCheckedChange = { on -> perform { vm.setShare(share.copy(hr = on)) } },
                            subtitle = stringResource(R.string.friends_share_hr_note),
                            leading = { RowIcon(Icons.Filled.Favorite) }, enabled = !busy,
                        )
                    }
                }
            }
            item(key = "share-note") {
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.friends_share_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "friends") {
                ListGroup(header = stringResource(R.string.friends_profile_friends, friends.size)) {
                    friends.forEach { person ->
                        item { shape ->
                            FriendRow(person, shape, onOpen = { openFriend(person.nick) }, onRemove = { removing = person })
                        }
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_add_by_nick),
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
                            title = stringResource(R.string.friends_server),
                            subtitle = if (sent != null) stringResource(R.string.friends_server_sent, serverUrl, agoText(sent))
                            else stringResource(R.string.friends_server_not_sent, serverUrl),
                            leading = { RowIcon(Icons.Filled.Dns) },
                        )
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_password_change),
                            leading = { RowIcon(Icons.Filled.Key) },
                            onClick = { passwordChanged = false; dialog = ProfileDialog.PASSWORD },
                        )
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_sign_out),
                            subtitle = stringResource(R.string.friends_sign_out_note),
                            leading = { RowIcon(Icons.AutoMirrored.Filled.Logout) },
                            onClick = { dialog = ProfileDialog.SIGN_OUT },
                        )
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.friends_delete_account),
                            subtitle = stringResource(R.string.friends_delete_account_note),
                            titleColor = MaterialTheme.colorScheme.error,
                            leading = { RowIcon(Icons.Filled.DeleteForever, tint = MaterialTheme.colorScheme.error) },
                            onClick = { dialog = ProfileDialog.DELETE },
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        ProfileDialog.NAME -> NameDialog(
            current = profile?.name.orEmpty(),
            onDismiss = { dialog = null },
            onSave = { name -> dialog = null; perform { vm.rename(name) } },
        )
        ProfileDialog.PASSWORD -> PasswordDialog(
            onDismiss = { dialog = null },
            change = { old, new -> vm.changePassword(old, new) },
            onChanged = { dialog = null; passwordChanged = true },
        )
        ProfileDialog.SIGN_OUT -> ConfirmDialog(
            title = stringResource(R.string.friends_sign_out_title),
            message = stringResource(R.string.friends_sign_out_body),
            confirmLabel = stringResource(R.string.friends_sign_out_confirm),
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                scope.launch { vm.signOut() }
            },
        )
        ProfileDialog.DELETE -> DeleteAccountDialog(
            nick = profile?.nick.orEmpty(),
            onDismiss = { dialog = null },
            delete = { password -> vm.deleteAccount(password) },
        )
        null -> Unit
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
                perform { (vm.unfriend(person.nick) as? FriendsResult.Fail)?.error }
            },
        )
    }
}

/** A friend in the profile's list: tap opens their page, the menu removes them. */
@Composable
private fun FriendRow(person: FriendPerson, shape: androidx.compose.ui.graphics.Shape, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListRow(
        shape = shape,
        title = person.profile.name,
        subtitle = stringResource(R.string.friends_handle, person.nick),
        leading = { FriendAvatar(person.profile.name, person.nick, person.profile.avatarRev, size = 40.dp) },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
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

@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val valid = text.trim().length in 1..FriendsNick.NAME_MAX
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friends_name_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(FriendsNick.NAME_MAX) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                supportingText = { Text(stringResource(R.string.friends_name_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = valid) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}

/** Current and new password. A wrong current password is said in the dialog, which stays open. */
@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    change: suspend (String, String) -> FriendsError?,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<FriendsError?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.friends_password_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
                PasswordField(old, { old = it; error = null }, stringResource(R.string.friends_password_current), ImeAction.Next)
                PasswordField(new, { new = it; error = null }, stringResource(R.string.friends_password_new), ImeAction.Done)
                Text(
                    error?.let { friendsErrorText(it) } ?: stringResource(R.string.friends_password_change_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && old.isNotEmpty() && FriendsNick.passwordOk(new),
                onClick = {
                    busy = true
                    scope.launch {
                        val failed = change(old, new)
                        busy = false
                        if (failed == null) onChanged() else error = failed
                    }
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.summary_cancel)) } },
    )
}

/**
 * Deleting the account, in two steps: what goes and the password, then one more question before anything
 * is sent. A wrong password comes back to the first step and says so.
 */
@Composable
private fun DeleteAccountDialog(nick: String, onDismiss: () -> Unit, delete: suspend (String) -> FriendsError?) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<FriendsError?>(null) }
    if (asking) {
        ConfirmDialog(
            title = stringResource(R.string.friends_delete_final_title),
            message = stringResource(R.string.friends_delete_final_body),
            confirmLabel = stringResource(R.string.friends_delete_confirm),
            destructive = true,
            onDismiss = { asking = false },
            onConfirm = {
                asking = false
                busy = true
                scope.launch {
                    // On success the session ends, the tab returns to the sign-in form and this dialog goes with the page.
                    error = delete(password)
                    busy = false
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.friends_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
                Text(stringResource(R.string.friends_delete_body, stringResource(R.string.friends_handle, nick)))
                PasswordField(password, { password = it; error = null }, stringResource(R.string.friends_connect_password), ImeAction.Done)
                error?.let {
                    Text(
                        friendsErrorText(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = { asking = true }) {
                Text(stringResource(R.string.friends_delete_continue), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.summary_cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, action: ImeAction) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(FriendsNick.PASSWORD_MAX)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = action),
        modifier = Modifier.fillMaxWidth(),
    )
}
