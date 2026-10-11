package com.noop.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendProfile
import com.noop.friends.FriendsError
import com.noop.friends.FriendsInvite
import com.noop.friends.FriendsResult
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// MARK: - Friends tab: adding a friend, and what waits for an answer
//
// A friend is added by a one-time invite and by nothing else: the server has no directory and no search.
// The invite travels as a QR the friend's camera reads, as a link in a message, or as ten characters
// typed here; using it makes two people friends at once. Above that, when there is any: a new phone
// asking to join the account, another account asking for the strap, and a phone that got in while nobody
// answered, each with its two answers. A phone is told apart by a six-digit code that both phones show.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsAddScreen(vm: FriendsViewModel, onBack: () -> Unit, openInvite: () -> Unit) {
    val scope = rememberCoroutineScope()
    val feedState by vm.feed.collectAsStateWithLifecycle()
    val invites by vm.invites.collectAsStateWithLifecycle()
    val inviteCodes by vm.inviteCodes.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()

    var code by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    // Each kept here, not read from the store, so a refresh that clears the store's failure does not take
    // a message away while it is being read.
    var requestFailure by remember { mutableStateOf<FriendsError?>(null) }
    var inviteFailure by remember { mutableStateOf<FriendsError?>(null) }
    var revokeFailure by remember { mutableStateOf<FriendsError?>(null) }
    var addFailure by remember { mutableStateOf<FriendsError?>(null) }
    var added by remember { mutableStateOf<FriendProfile?>(null) }

    LaunchedEffect(Unit) {
        vm.sync()
        vm.loadInvites()
    }

    val feed = feedState.feed
    val claims = feed?.claims.orEmpty()
    val unconfirmed = feed?.unconfirmed.orEmpty()
    // A phone that joined without confirmation answers nothing and changes nothing until it is confirmed.
    val locked = feed?.me?.profile?.device?.probationUntil != null
    val serverNow = vm.serverNow(nowSec)

    /** One thing at a time; [done] is told why it failed, or null when it went through. */
    fun run(done: (FriendsError?) -> Unit, call: suspend () -> FriendsError?) {
        if (working) return
        working = true
        scope.launch {
            done(call())
            working = false
        }
    }

    fun redeem() {
        if (working || code.isBlank() || locked) return
        working = true
        scope.launch {
            when (val answer = vm.redeem(code)) {
                is FriendsResult.Ok -> {
                    added = answer.value
                    addFailure = null
                    code = ""
                }
                is FriendsResult.Fail -> {
                    added = null
                    addFailure = answer.error
                }
            }
            working = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()) {
        PushedTopBar(title = stringResource(R.string.friends_add_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = M3Dimens.bottomBarClearance),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (claims.isNotEmpty() || unconfirmed.isNotEmpty()) {
                item(key = "requests-header") { SectionHeader(stringResource(R.string.friends_requests_header)) }
                items(claims, key = { "claim:${it.id}" }) { claim ->
                    RequestCard(
                        title = friendsClaimTitle(claim),
                        detail = friendsClaimDetail(claim),
                        yes = stringResource(
                            if (claim.kind == com.noop.friends.FriendsClaim.Kind.JOIN) R.string.friends_request_confirm
                            else R.string.friends_request_release,
                        ),
                        no = stringResource(R.string.friends_request_decline),
                        enabled = !working && !locked,
                        onYes = { run({ requestFailure = it }) { vm.approve(claim) } },
                        onNo = { run({ requestFailure = it }) { vm.decline(claim) } },
                    )
                }
                items(unconfirmed, key = { "phone:${it.id}" }) { phone ->
                    RequestCard(
                        title = stringResource(R.string.friends_unconfirmed_title),
                        detail = friendsJoinedText(phone, serverNow),
                        yes = stringResource(R.string.friends_request_keep),
                        no = stringResource(R.string.friends_remove_confirm),
                        enabled = !working && !locked,
                        onYes = { run({ requestFailure = it }) { vm.trustDevice(phone.id) } },
                        onNo = { run({ requestFailure = it }) { vm.removeDevice(phone.id) } },
                    )
                }
                if (locked) item(key = "requests-locked") { Footnote(stringResource(R.string.friends_requests_locked)) }
                requestFailure?.let { failure -> item(key = "requests-failure") { Footnote(friendsErrorText(failure), error = true) } }
            }

            item(key = "invite") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            run({ inviteFailure = it; if (it == null) openInvite() }) { vm.createInvite() }
                        },
                        enabled = !working && !locked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.friends_invite_a_friend), modifier = Modifier.padding(start = 8.dp))
                    }
                    inviteFailure?.let { Footnote(friendsErrorText(it), error = true) }
                }
            }

            item(key = "code") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.take(MAX_PASTED); addFailure = null; added = null },
                            label = { Text(stringResource(R.string.friends_enter_code)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrect = false,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { redeem() }),
                            modifier = Modifier.weight(1f),
                        )
                        // Add is offered for anything typed: what is not a code is answered with the reason under the field.
                        FilledTonalButton(onClick = ::redeem, enabled = !working && code.isNotBlank() && !locked) {
                            Text(stringResource(R.string.friends_add))
                        }
                    }
                    added?.let { friend ->
                        Text(
                            stringResource(R.string.friends_added, friend.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    addFailure?.let { Footnote(friendsErrorText(it), error = true) }
                }
            }

            val waiting = invites.orEmpty()
            if (waiting.isNotEmpty()) {
                item(key = "waiting-header") { SectionHeader(stringResource(R.string.friends_invites_waiting)) }
                items(waiting, key = { "invite:${it.id}" }) { invite ->
                    WaitingInvite(
                        invite = invite,
                        code = inviteCodes[invite.id],
                        enabled = !working && !locked,
                        onOpen = {
                            vm.show(invite)
                            openInvite()
                        },
                        onRevoke = { run({ revokeFailure = it }) { vm.revokeInvite(invite.id) } },
                    )
                }
                revokeFailure?.let { failure -> item(key = "revoke-failure") { Footnote(friendsErrorText(failure), error = true) } }
            }
        }
    }
}

/** The longest text the code field holds: a whole shared message, with room to spare. */
private const val MAX_PASTED = 500

/** A line under a card or a field: a reason, in the scheme's error colour when something failed. */
@Composable
internal fun Footnote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** One thing that waits for the wearer's answer, with its two answers. */
@Composable
private fun RequestCard(
    title: String,
    detail: String,
    yes: String,
    no: String,
    enabled: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    HealthCard(
        shape = RoundedCornerShape(M3Dimens.heroRadius),
        contentPadding = PaddingValues(20.dp),
        verticalSpacing = 12.dp,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onNo, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(no, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onYes, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(yes, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** An invite not used yet. One made on this phone opens again; one made elsewhere can only be revoked. */
@Composable
private fun WaitingInvite(invite: FriendsInvite, code: String?, enabled: Boolean, onOpen: () -> Unit, onRevoke: () -> Unit) {
    HealthCard(
        onClick = if (code != null) onOpen else null,
        onClickLabel = stringResource(R.string.friends_invite),
        shape = RoundedCornerShape(M3Dimens.cardRadius),
        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    code ?: stringResource(R.string.friends_invite),
                    style = if (code != null) MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
                    else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.friends_invite_valid_until, friendsLongDay(invite.expiresAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRevoke, enabled = enabled) { Text(stringResource(R.string.friends_invite_revoke)) }
        }
    }
}

/** "17 October 2026": the day an invite stops working, as the reader's language writes a date. */
@Composable
internal fun friendsLongDay(ts: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(friendsLocale())
        .format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate())
