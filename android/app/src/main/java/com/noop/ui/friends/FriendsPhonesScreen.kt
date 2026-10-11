package com.noop.ui.friends

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendsDevice
import com.noop.friends.FriendsError
import com.noop.friends.FriendsResult
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.RowIcon
import kotlinx.coroutines.launch

// MARK: - Friends tab: the account's phones, and what the server stores
//
// Two pages pushed from the profile. The phones the account lives on: each holds its own key, so
// removing one ends what that phone can do at once. And everything the server holds about the reader,
// shown as the server sent it, so the reader sees what is kept instead of being told.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsPhonesScreen(vm: FriendsViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val feedState by vm.feed.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()

    // Whether the list could not be loaded. Then there is no list to show, only the reason.
    var loadFailed by remember { mutableStateOf(false) }
    // Why the load, a Remove or a Keep failed, and why this phone could not leave. Kept here, not read
    // from the store, so what the tab left there is not mistaken for something that happened on this page.
    var failure by remember { mutableStateOf<FriendsError?>(null) }
    var leaveFailure by remember { mutableStateOf<FriendsError?>(null) }
    var removing by remember { mutableStateOf<FriendsDevice?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // A page left before the answer came is cancelled here, and nothing is reported.
        vm.loadDevices()?.let {
            loadFailed = true
            failure = it
        }
    }

    val phones = if (loadFailed) emptyList() else devices.orEmpty()
    val serverNow = vm.serverNow(nowSec)
    // A phone that joined without confirmation removes nobody but itself.
    val locked = feedState.feed?.me?.profile?.device?.probationUntil != null
    // This phone may leave while the account stays on another confirmed phone, or when it is itself
    // unconfirmed. The account's only confirmed phone cannot: there would be no way back in.
    val canLeave = locked || phones.any { !it.current && it.probationUntil == null }

    fun run(call: suspend () -> FriendsError?) {
        if (working) return
        working = true
        failure = null
        scope.launch {
            failure = call()
            working = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.friends_phones), onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (devices == null && !loadFailed) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(phones, key = { "phone:${it.id}" }) { phone ->
                PhoneCard(
                    phone = phone,
                    serverNow = serverNow,
                    canAct = !phone.current && !locked && !working,
                    onKeep = { run { vm.trustDevice(phone.id) } },
                    onRemove = { removing = phone },
                )
            }
            failure?.let { item(key = "failure") { Footnote(friendsErrorText(it), error = true) } }
            if (canLeave) {
                item(key = "leave") {
                    ListRow(
                        shape = RoundedCornerShape(M3Dimens.cardRadius),
                        title = stringResource(R.string.friends_remove_this_phone),
                        titleColor = MaterialTheme.colorScheme.error,
                        leading = { RowIcon(Icons.Filled.PhonelinkErase, tint = MaterialTheme.colorScheme.error) },
                        enabled = !working,
                        onClick = { confirmLeave = true },
                    )
                }
                leaveFailure?.let { item(key = "leave-failure") { Footnote(friendsErrorText(it), error = true) } }
            }
        }
    }

    // Removing a phone asks first: it ends what that phone can do at once.
    removing?.let { phone ->
        ConfirmDialog(
            title = stringResource(R.string.friends_phone_remove_title, friendsDeviceText(phone.platform)),
            message = stringResource(R.string.friends_phone_remove_body),
            confirmLabel = stringResource(R.string.friends_remove_confirm),
            destructive = true,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                run { vm.removeDevice(phone.id) }
            },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.friends_remove_this_phone),
            message = stringResource(R.string.friends_remove_this_phone_body),
            confirmLabel = stringResource(R.string.friends_remove_confirm),
            destructive = true,
            onDismiss = { confirmLeave = false },
            onConfirm = {
                confirmLeave = false
                if (!working) {
                    working = true
                    leaveFailure = null
                    // On success Friends goes off and this page goes with it.
                    scope.launch {
                        leaveFailure = vm.removeThisPhone()
                        working = false
                    }
                }
            },
        )
    }
}

/** One phone of the account: what it is, when it joined and was last heard from, and what can be done about it. */
@Composable
private fun PhoneCard(phone: FriendsDevice, serverNow: Long, canAct: Boolean, onKeep: () -> Unit, onRemove: () -> Unit) {
    HealthCard(
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 8.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                friendsDeviceText(phone.platform),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (phone.current) {
                Text(
                    stringResource(R.string.friends_this_phone),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        Text(
            friendsSeenText(phone, serverNow),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (phone.probationUntil != null) {
            Text(
                stringResource(R.string.friends_phone_unconfirmed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (!phone.current) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRemove, enabled = canAct, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.friends_remove_confirm), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (phone.probationUntil != null) {
                    Button(onClick = onKeep, enabled = canAct, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.friends_request_keep), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * Everything the server holds for the account, as the server sent it. The text is laid out to be read
 * and never parsed, so every figure is the one the server wrote and the members keep the server's order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsExportScreen(vm: FriendsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<FriendsError?>(null) }

    LaunchedEffect(Unit) {
        when (val answer = vm.exportText()) {
            is FriendsResult.Ok -> text = answer.value
            is FriendsResult.Fail -> failure = answer.error
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(R.string.friends_export_title),
            onBack = onBack,
            actions = {
                text?.let { shown ->
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shown)
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.coach_share_action))
                    }
                }
            },
        )
        val shown = text
        val failed = failure
        when {
            shown != null -> SelectionContainer {
                Text(
                    shown,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = M3Dimens.bottomBarClearance),
                )
            }
            failed != null -> Box(Modifier.padding(horizontal = M3Dimens.screenPadding)) { Footnote(friendsErrorText(failed), error = true) }
            else -> Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
