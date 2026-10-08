package com.noop.ui.friends

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendProfile
import com.noop.friends.FriendRelation
import com.noop.friends.FriendRequest
import com.noop.friends.FriendsError
import com.noop.friends.FriendsNick
import com.noop.friends.FriendsResult
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SearchField
import com.noop.ui.m3.SectionHeader
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

// MARK: - Friends tab: adding a friend
//
// A person is found by their exact nickname and by nothing else: the server has no directory and no
// prefix search, so the field asks only when the reader does (the keyboard's search key or "Find").
// Under it: the requests waiting for an answer, the ones the reader sent, and the reader's own nickname
// to hand to a friend.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsAddScreen(vm: FriendsViewModel, onBack: () -> Unit, openFriend: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val myNick by vm.nick.collectAsStateWithLifecycle()
    val requestsState by vm.requests.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()

    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<FriendProfile?>(null) }
    var searchError by remember { mutableStateOf<FriendsError?>(null) }
    var actionError by remember { mutableStateOf<FriendsError?>(null) }
    var busyNick by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadRequests() }

    fun search() {
        if (searching || FriendsNick.clean(query) == null) return
        searching = true
        searchError = null
        found = null
        scope.launch {
            when (val answer = vm.lookup(query)) {
                is FriendsResult.Ok -> found = answer.value
                is FriendsResult.Fail -> searchError = answer.error
            }
            searching = false
        }
    }

    // Runs one request action on [nick]; a profile answer replaces the card's person.
    fun act(nick: String, call: suspend () -> FriendsResult<*>) {
        if (busyNick != null) return
        busyNick = nick
        actionError = null
        scope.launch {
            when (val answer = call()) {
                is FriendsResult.Ok -> {
                    val profile = answer.value as? FriendProfile
                    if (found?.nick == nick) {
                        found = profile ?: found?.copy(relation = FriendRelation.NONE)
                    }
                }
                is FriendsResult.Fail -> actionError = answer.error
            }
            busyNick = null
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()) {
        PushedTopBar(title = stringResource(R.string.friends_add_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "search") {
                Column(
                    Modifier.padding(horizontal = M3Dimens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(
                            query = query,
                            onQueryChange = { query = FriendsNick.filterTyping(it); searchError = null; found = null },
                            placeholder = stringResource(R.string.friends_add_placeholder),
                            clearLabel = stringResource(R.string.friends_add_clear),
                            onSearch = ::search,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = ::search, enabled = !searching && FriendsNick.clean(query) != null) {
                            Text(stringResource(R.string.friends_add_find))
                        }
                    }
                    Text(
                        stringResource(R.string.friends_add_exact_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            if (searching) {
                item(key = "searching") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
            searchError?.let { error ->
                item(key = "search-error") {
                    NoticeCard(
                        icon = Icons.Filled.ErrorOutline,
                        title = if (error == FriendsError.NO_SUCH_USER) {
                            stringResource(R.string.friends_add_not_found, stringResource(R.string.friends_handle, FriendsNick.clean(query).orEmpty()))
                        } else friendsErrorText(error),
                        error = error != FriendsError.NO_SUCH_USER && error != FriendsError.OFFLINE && error != FriendsError.RATE_LIMITED,
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            found?.let { person ->
                item(key = "found") {
                    FoundCard(
                        person = person,
                        busy = busyNick == person.nick,
                        onSend = { act(person.nick) { vm.sendRequest(person.nick) } },
                        onAccept = { act(person.nick) { vm.acceptRequest(person.nick) } },
                        onCancel = { act(person.nick) { vm.dropRequest(person.nick) } },
                        onOpen = { openFriend(person.nick) },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }
            actionError?.let { error ->
                item(key = "action-error") {
                    NoticeCard(
                        icon = Icons.Filled.ErrorOutline,
                        title = friendsErrorText(error),
                        error = error != FriendsError.OFFLINE && error != FriendsError.RATE_LIMITED,
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            val requests = requestsState.requests
            requestsState.error?.let { error ->
                item(key = "requests-error") {
                    FriendsRefreshNotice(
                        error = error,
                        shownAge = null,
                        onRetry = { vm.loadRequests() },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }
            if (requests == null && requestsState.loading) {
                item(key = "requests-loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }

            if (requests != null && requests.incoming.isNotEmpty()) {
                item(key = "incoming-header") {
                    SectionHeader(stringResource(R.string.friends_requests_incoming), Modifier.padding(horizontal = M3Dimens.screenPadding))
                }
                items(requests.incoming, key = { "in:${it.profile.nick}" }) { request ->
                    RequestCard(
                        request = request,
                        nowSec = vm.serverNow(nowSec),
                        incoming = true,
                        busy = busyNick == request.profile.nick,
                        onAccept = { act(request.profile.nick) { vm.acceptRequest(request.profile.nick) } },
                        onDrop = { act(request.profile.nick) { vm.dropRequest(request.profile.nick) } },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }
            if (requests != null && requests.outgoing.isNotEmpty()) {
                item(key = "outgoing-header") {
                    SectionHeader(stringResource(R.string.friends_requests_outgoing), Modifier.padding(horizontal = M3Dimens.screenPadding))
                }
                items(requests.outgoing, key = { "out:${it.profile.nick}" }) { request ->
                    RequestCard(
                        request = request,
                        nowSec = vm.serverNow(nowSec),
                        incoming = false,
                        busy = busyNick == request.profile.nick,
                        onAccept = {},
                        onDrop = { act(request.profile.nick) { vm.dropRequest(request.profile.nick) } },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
            }

            myNick?.let { nick ->
                item(key = "mine-header") {
                    SectionHeader(stringResource(R.string.friends_your_nick), Modifier.padding(horizontal = M3Dimens.screenPadding))
                }
                item(key = "mine") {
                    val handle = stringResource(R.string.friends_handle, nick)
                    HealthCard(
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                        shape = RoundedCornerShape(M3Dimens.heroRadius),
                        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(handle, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(R.string.friends_your_nick_note),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(handle)) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.friends_your_nick_copy))
                            }
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.friends_your_nick_share_text, handle))
                                }
                                runCatching { context.startActivity(Intent.createChooser(send, null)) }
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.friends_your_nick_share))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The person the lookup found, with the one action their relation to the reader allows. */
@Composable
private fun FoundCard(
    person: FriendProfile,
    busy: Boolean,
    onSend: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HealthCard(
        modifier = modifier,
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 16.dp,
    ) {
        PersonHeader(person, stringResource(R.string.friends_handle, person.nick))
        when (person.relation) {
            FriendRelation.SELF -> Text(
                stringResource(R.string.friends_error_self_request),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FriendRelation.FRIEND -> {
                Text(
                    stringResource(R.string.friends_add_already),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.friends_open_page, person.name))
                }
            }
            FriendRelation.OUTGOING -> {
                Text(
                    stringResource(R.string.friends_add_sent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.friends_request_cancel))
                }
            }
            FriendRelation.INCOMING -> {
                Text(
                    stringResource(R.string.friends_request_accept_note, person.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAccept, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.friends_request_accept))
                }
            }
            else -> {
                Button(onClick = onSend, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.friends_request_send), modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    stringResource(R.string.friends_request_send_note, person.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A pending request: who, since when, and Accept / Decline for an incoming one or Cancel for one sent. */
@Composable
private fun RequestCard(
    request: FriendRequest,
    nowSec: Long,
    incoming: Boolean,
    busy: Boolean,
    onAccept: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val person = request.profile
    val handle = stringResource(R.string.friends_handle, person.nick)
    val since = requestSince(request.requestedAt, nowSec)
    val line = when {
        !incoming -> stringResource(R.string.friends_request_waiting, handle)
        since != null -> stringResource(R.string.friends_request_from, handle, since)
        else -> handle
    }
    HealthCard(
        modifier = modifier,
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 12.dp,
    ) {
        if (incoming) {
            PersonHeader(person, line)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDrop, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.friends_request_decline))
                }
                Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.friends_request_accept))
                }
            }
            Text(
                stringResource(R.string.friends_request_accept_note, person.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { PersonHeader(person, line) }
                TextButton(onClick = onDrop, enabled = !busy) { Text(stringResource(R.string.friends_request_cancel)) }
            }
        }
    }
}

/** When a request was sent, as a word: "today", "yesterday", or the short date. Null without a stamp. */
@Composable
private fun requestSince(requestedAt: Long, nowSec: Long): String? {
    if (requestedAt <= 0L) return null
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochSecond(requestedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
    return when (day) {
        today -> stringResource(R.string.metric_today).lowercase(friendsLocale())
        today.minusDays(1) -> stringResource(R.string.metric_yesterday).lowercase(friendsLocale())
        else -> shortDate(day)
    }
}

/** An avatar, a name and one line under it. */
@Composable
internal fun PersonHeader(person: FriendProfile, line: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FriendAvatar(person.name, person.nick, person.avatarRev, size = 56.dp)
        Column {
            Text(
                person.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
