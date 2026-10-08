package com.noop.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendsError
import com.noop.friends.FriendsNick
import com.noop.friends.FriendsResult
import com.noop.friends.FriendsServerUrl
import com.noop.ui.m3.CookieShape
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LargeTitle
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.PeriodSegmented
import com.noop.ui.m3.RowIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MARK: - Friends: signed out
//
// A nickname and a password, nothing else: "Create account" or "Sign in". The form makes no request when
// it appears. The only ones it makes on its own are while a nickname is being typed into "Create
// account": whether it is free (after a pause in the typing), and, with the first of those, whether this
// server asks for an invite code. Everything else waits for the button.

/** What the line under the nickname field says. */
private sealed class NickState {
    object Idle : NickState()
    object Invalid : NickState()
    object Checking : NickState()
    object Free : NickState()
    object Taken : NickState()
    data class Unknown(val error: FriendsError) : NickState()
}

@Composable
internal fun FriendsConnectScreen(vm: FriendsViewModel) {
    val scope = rememberCoroutineScope()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val sessionEnded by vm.sessionEnded.collectAsStateWithLifecycle()

    var create by rememberSaveable { mutableStateOf(true) }
    var nick by rememberSaveable { mutableStateOf("") }
    // The secrets are held for this composition only: saved instance state can be written to disk.
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    // True once the nickname field was typed in during this visit. A nickname restored with the form
    // (a rotation, a return to the tab) is not asked about again until it is touched.
    var nickTyped by remember { mutableStateOf(false) }
    var inviteRequired by rememberSaveable { mutableStateOf(false) }
    var infoAsked by remember(serverUrl) { mutableStateOf(false) }
    var nickState by remember { mutableStateOf<NickState>(NickState.Idle) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<FriendsError?>(null) }
    var editServer by remember { mutableStateOf(false) }

    // The live nickname check: sign-up only, and only after the typing has paused.
    LaunchedEffect(nick, create, serverUrl, nickTyped) {
        if (!create || nick.isEmpty() || !nickTyped) {
            nickState = NickState.Idle
            return@LaunchedEffect
        }
        val clean = FriendsNick.clean(nick)
        if (clean == null) {
            nickState = if (nick.length < FriendsNick.MIN_LENGTH) NickState.Idle else NickState.Invalid
            return@LaunchedEffect
        }
        nickState = NickState.Checking
        delay(NICK_CHECK_PAUSE_MS)
        if (!infoAsked) {
            infoAsked = true
            vm.serverInfo().valueOrNull?.let { inviteRequired = it.inviteRequired }
        }
        nickState = when (val answer = vm.nickFree(clean)) {
            is FriendsResult.Ok -> if (answer.value) NickState.Free else NickState.Taken
            is FriendsResult.Fail -> NickState.Unknown(answer.error)
        }
    }

    val nickOk = FriendsNick.clean(nick) != null
    val passwordOk = if (create) FriendsNick.passwordOk(password) else password.isNotEmpty()
    val canSubmit = !busy && nickOk && passwordOk && nickState != NickState.Taken &&
        (!create || !inviteRequired || invite.isNotBlank())

    fun submit() {
        if (!canSubmit) return
        busy = true
        failure = null
        scope.launch {
            val error = vm.signIn(create, nick, password, invite.takeIf { create && it.isNotBlank() })
            busy = false
            failure = error
            // A server that wants a code says so by refusing a sign-up without one.
            if (error == FriendsError.BAD_INVITE) inviteRequired = true
            if (error == FriendsError.NICK_TAKEN) nickState = NickState.Taken
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding(),
        contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
    ) {
        item(key = "title") { LargeTitle(stringResource(R.string.nav_friends)) }

        if (sessionEnded) {
            item(key = "ended") {
                NoticeCard(
                    icon = Icons.Filled.ErrorOutline,
                    title = stringResource(R.string.friends_error_session),
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                )
            }
        }

        item(key = "hero") {
            HealthCard(
                modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(M3Dimens.heroRadius),
                contentPadding = PaddingValues(20.dp),
                verticalSpacing = 12.dp,
            ) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    HeroCookie(Icons.Filled.Person, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.offset(x = (-34).dp))
                    HeroCookie(Icons.Filled.Group, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Modifier.offset(x = 34.dp))
                }
                Text(
                    stringResource(R.string.friends_connect_headline),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.friends_connect_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "mode") {
            PeriodSegmented(
                options = listOf(stringResource(R.string.friends_connect_create), stringResource(R.string.friends_connect_sign_in)),
                selectedIndex = if (create) 0 else 1,
                onSelect = { create = it == 0; failure = null },
                modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
            )
        }

        item(key = "form") {
            HealthCard(
                modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                verticalSpacing = 12.dp,
            ) {
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = FriendsNick.filterTyping(it); nickTyped = true; failure = null },
                    label = { Text(stringResource(R.string.friends_connect_nick)) },
                    prefix = { Text(stringResource(R.string.friends_at_sign)) },
                    singleLine = true,
                    isError = nickState == NickState.Taken || nickState == NickState.Invalid,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    trailingIcon = when (nickState) {
                        NickState.Free -> {
                            { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Health.colors.positive) }
                        }
                        NickState.Checking -> {
                            { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                        }
                        else -> null
                    },
                    supportingText = {
                        Text(
                            nickLine(nickState, create),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(FriendsNick.PASSWORD_MAX); failure = null },
                    label = { Text(stringResource(R.string.friends_connect_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (showPassword) R.string.friends_password_hide else R.string.friends_password_show,
                                ),
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                if (create) R.string.friends_connect_password_note else R.string.friends_connect_password_note_sign_in,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (create && inviteRequired) {
                    OutlinedTextField(
                        value = invite,
                        onValueChange = { invite = it.take(200); failure = null },
                        label = { Text(stringResource(R.string.friends_connect_invite)) },
                        singleLine = true,
                        isError = failure == FriendsError.BAD_INVITE,
                        supportingText = { Text(stringResource(R.string.friends_connect_invite_note)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        failure?.let { error ->
            item(key = "failure") {
                NoticeCard(
                    icon = Icons.Filled.ErrorOutline,
                    title = friendsErrorText(error),
                    error = error != FriendsError.OFFLINE && error != FriendsError.RATE_LIMITED,
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        item(key = "server") {
            ListRow(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(M3Dimens.cardRadius),
                title = stringResource(R.string.friends_server),
                subtitle = serverUrl,
                leading = { RowIcon(Icons.Filled.Dns) },
                trailing = {
                    Text(
                        stringResource(R.string.friends_server_change),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { editServer = true },
                modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
            )
        }

        item(key = "privacy") { FriendsNote(Icons.Filled.Lock, stringResource(R.string.friends_connect_privacy)) }

        item(key = "submit") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = ::submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.size(12.dp))
                    }
                    Text(stringResource(if (create) R.string.friends_connect_create else R.string.friends_connect_sign_in))
                }
                if (create) {
                    Text(
                        stringResource(R.string.friends_connect_later),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    if (editServer) {
        ServerAddressDialog(
            current = serverUrl,
            onDismiss = { editServer = false },
            onSave = { typed ->
                val result = vm.setServerUrl(typed)
                if (result is FriendsServerUrl.Result.Valid) {
                    editServer = false
                    failure = null
                    inviteRequired = false
                }
                result
            },
        )
    }
}

/** How long the typing must pause before the nickname is asked about (milliseconds). */
private const val NICK_CHECK_PAUSE_MS = 500L

@Composable
private fun nickLine(state: NickState, create: Boolean): String = when {
    !create -> stringResource(R.string.friends_connect_nick_note_sign_in)
    state == NickState.Free -> stringResource(R.string.friends_connect_nick_free)
    state == NickState.Taken -> stringResource(R.string.friends_connect_nick_taken)
    state == NickState.Invalid -> stringResource(R.string.friends_error_bad_nick)
    state == NickState.Checking -> stringResource(R.string.friends_connect_nick_checking)
    state is NickState.Unknown -> stringResource(R.string.friends_connect_nick_unknown, friendsErrorText(state.error))
    else -> stringResource(R.string.friends_connect_nick_note)
}

@Composable
private fun HeroCookie(icon: ImageVector, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(88.dp).clip(CookieShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(40.dp))
    }
}

/** The server address editor: one field, the reason an address is refused, and a way back to the default. */
@Composable
private fun ServerAddressDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> FriendsServerUrl.Result,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    var problem by remember { mutableStateOf<FriendsServerUrl.Problem?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friends_server_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
                Text(stringResource(R.string.friends_server_dialog_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; problem = null },
                    singleLine = true,
                    isError = problem != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false, imeAction = ImeAction.Done),
                    supportingText = {
                        Text(
                            when (problem) {
                                null -> stringResource(R.string.friends_server_dialog_note)
                                FriendsServerUrl.Problem.NOT_HTTPS -> stringResource(R.string.friends_server_problem_https)
                                FriendsServerUrl.Problem.EMPTY -> stringResource(R.string.friends_server_problem_empty)
                                else -> stringResource(R.string.friends_server_problem_malformed)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { text = FriendsServerUrl.DEFAULT; problem = null }) {
                    Text(stringResource(R.string.friends_server_dialog_default))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { problem = (onSave(text) as? FriendsServerUrl.Result.Invalid)?.problem }) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}
