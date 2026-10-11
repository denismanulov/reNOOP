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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.friends.FriendsError
import com.noop.friends.FriendsLimits
import com.noop.friends.FriendsPhase
import com.noop.friends.FriendsServerUrl
import com.noop.friends.FriendsStore
import com.noop.ui.ProfileAvatar
import com.noop.ui.m3.CookieShape
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.LargeTitle
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.NoticeCard
import com.noop.ui.m3.RowIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MARK: - Friends: before it is on
//
// There is no form. The name and the photo are the profile's and the account is tied to the strap, so
// turning Friends on is one tap. The same page carries the three places turning on can pause: a strap
// not read yet, a strap that already has an account, and a request to join that account waiting for an
// answer. Nothing is sent before the button is tapped, and the page asks nothing of the server when it
// appears. Which server Friends talks to is chosen here, while it is off, and not after.

@Composable
internal fun FriendsConnectScreen(vm: FriendsViewModel) {
    val scope = rememberCoroutineScope()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val profileName by vm.profileName.collectAsStateWithLifecycle()

    // The profile had no name when the page appeared, so the page asks for one. Decided once: the field
    // must not go away at the first letter typed into it.
    val asksForName = remember { profileName.isBlank() }
    var typedName by rememberSaveable { mutableStateOf("") }
    var editServer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.reloadProfileName() }

    // While the page waits for something outside it, it asks again by itself: every minute how the
    // request to join stands, every few seconds whether the strap has been read (which costs no request
    // until it has).
    LaunchedEffect(phase.stored) {
        vm.syncQuietly()
        val pause = when (phase) {
            is FriendsPhase.Waiting -> CLAIM_POLL_MS
            FriendsPhase.WaitingForStrap -> STRAP_POLL_MS
            else -> return@LaunchedEffect
        }
        while (true) {
            delay(pause)
            vm.syncQuietly()
        }
    }

    val (title, body) = when (phase) {
        FriendsPhase.WaitingForStrap -> R.string.friends_strap_wait_title to R.string.friends_strap_wait_body
        FriendsPhase.StrapBound -> R.string.friends_strap_bound_title to R.string.friends_strap_bound_body
        is FriendsPhase.Waiting -> R.string.friends_claim_title to R.string.friends_claim_body
        else -> R.string.friends_on_title to R.string.friends_on_body
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding(),
        contentPadding = PaddingValues(bottom = M3Dimens.bottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
    ) {
        item(key = "title") { LargeTitle(stringResource(R.string.nav_friends)) }

        error?.let { failure ->
            item(key = "failure") {
                NoticeCard(
                    icon = Icons.Filled.ErrorOutline,
                    title = friendsErrorText(failure),
                    error = failure != FriendsError.OFFLINE && failure != FriendsError.RATE_LIMITED,
                    modifier = Modifier.padding(horizontal = M3Dimens.screenPadding).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        item(key = "hero") {
            HealthCard(
                modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                shape = RoundedCornerShape(M3Dimens.heroRadius),
                contentPadding = PaddingValues(20.dp),
                verticalSpacing = 12.dp,
            ) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    HeroCookie(Icons.Filled.Person, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.offset(x = (-34).dp))
                    HeroCookie(Icons.Filled.Group, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Modifier.offset(x = 34.dp))
                }
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                )
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                (phase as? FriendsPhase.Waiting)?.let { waiting ->
                    // Read out digit by digit: "481 902" as a number would be four hundred and eighty-one.
                    val spoken = waiting.claim.code.toCharArray().joinToString(" ")
                    Text(
                        spacedCode(waiting.claim.code),
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
                    )
                }
            }
        }

        when (val current = phase) {
            FriendsPhase.WaitingForStrap -> {
                item(key = "strap-note") { FriendsNote(Icons.Filled.Sensors, stringResource(R.string.friends_strap_wait_note)) }
                item(key = "strap-actions") {
                    Actions {
                        FilledTonalButton(
                            onClick = { scope.launch { vm.turnOnWithoutStrap() } },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(stringResource(R.string.friends_continue_without_strap)) }
                        TextButton(onClick = { scope.launch { vm.notNow() } }, enabled = !loading) {
                            Text(stringResource(R.string.friends_not_now))
                        }
                    }
                }
            }
            FriendsPhase.StrapBound -> {
                item(key = "bound-note") { FriendsNote(Icons.Filled.Group, stringResource(R.string.friends_strap_bound_note)) }
                item(key = "bound-actions") {
                    Actions {
                        Button(
                            onClick = { scope.launch { vm.claimAccount() } },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { BusyLabel(loading, stringResource(R.string.friends_this_is_my_account)) }
                        FilledTonalButton(
                            onClick = { scope.launch { vm.turnOnWithoutStrap() } },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(stringResource(R.string.friends_start_new_account)) }
                        TextButton(onClick = { scope.launch { vm.notNow() } }, enabled = !loading) {
                            Text(stringResource(R.string.friends_not_now))
                        }
                    }
                }
            }
            is FriendsPhase.Waiting -> {
                current.claim.maturesAt?.let { matures ->
                    item(key = "matures") {
                        FriendsNote(Icons.Filled.Schedule, stringResource(R.string.friends_claim_matures, friendsMoment(matures)))
                    }
                }
                item(key = "waiting-actions") {
                    Actions {
                        FilledTonalButton(
                            onClick = { scope.launch { vm.cancelClaim() } },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(stringResource(R.string.summary_cancel)) }
                    }
                }
            }
            else -> {
                item(key = "who") {
                    if (asksForName) {
                        OutlinedTextField(
                            value = typedName,
                            onValueChange = { typedName = it.take(FriendsLimits.NAME_MAX) },
                            label = { Text(stringResource(R.string.friends_name_title)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                            supportingText = { Text(stringResource(R.string.friends_list_me_note)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding),
                        )
                    } else {
                        // Who friends will see, before anything leaves the phone.
                        ListRow(
                            shape = RoundedCornerShape(M3Dimens.cardRadius),
                            title = profileName,
                            subtitle = stringResource(R.string.friends_list_me_note),
                            leading = { ProfileAvatar(size = 40.dp) },
                            modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                        )
                    }
                }
                item(key = "server") {
                    ListRow(
                        shape = RoundedCornerShape(M3Dimens.cardRadius),
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
                        enabled = !loading,
                        onClick = { editServer = true },
                        modifier = Modifier.padding(horizontal = M3Dimens.screenPadding),
                    )
                }
                item(key = "privacy") { FriendsNote(Icons.Filled.Lock, stringResource(R.string.friends_connect_privacy)) }
                item(key = "turn-on") {
                    Actions {
                        Button(
                            onClick = { scope.launch { vm.turnOn(typedName.takeIf { asksForName }) } },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { BusyLabel(loading, stringResource(R.string.friends_turn_on)) }
                    }
                }
            }
        }
    }

    if (editServer) {
        ServerAddressDialog(
            current = serverUrl,
            onDismiss = { editServer = false },
            onSave = { typed ->
                when (val change = vm.setServerUrl(typed)) {
                    is FriendsStore.ServerChange.Refused -> change.problem
                    // Not off any more means the page behind has moved on, and says why itself.
                    else -> {
                        editServer = false
                        null
                    }
                }
            },
        )
    }
}

/** How often the page asks how a request to join stands, and whether the strap has been read (milliseconds). */
private const val CLAIM_POLL_MS = 60_000L
private const val STRAP_POLL_MS = 5_000L

@Composable
internal fun friendsMoment(ts: Long): String = friendsMoment(ts, LocalContext.current.resources.configuration.locales[0])

/** The page's buttons, one under another at the foot of the list. */
@Composable
private fun Actions(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun BusyLabel(busy: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.size(12.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HeroCookie(icon: ImageVector, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(88.dp).clip(CookieShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(40.dp))
    }
}

/** Why an address typed for the server was not taken, each reason in its own words. */
@Composable
private fun serverProblemText(problem: FriendsServerUrl.Problem): String = stringResource(
    when (problem) {
        FriendsServerUrl.Problem.NOT_HTTPS -> R.string.friends_server_problem_https
        FriendsServerUrl.Problem.HAS_CREDENTIALS -> R.string.friends_server_problem_credentials
        FriendsServerUrl.Problem.HAS_QUERY -> R.string.friends_server_problem_query
        FriendsServerUrl.Problem.EMPTY, FriendsServerUrl.Problem.MALFORMED -> R.string.friends_server_problem_malformed
    },
)

/**
 * The server address editor: one field, the reason an address is refused, and a way back to the fork's
 * own server (an empty field). [onSave] answers why the address was not taken, or null when it was.
 */
@Composable
private fun ServerAddressDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> FriendsServerUrl.Problem?,
) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(if (current == FriendsServerUrl.DEFAULT) "" else current) }
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
                    placeholder = { Text(FriendsServerUrl.DEFAULT, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false, imeAction = ImeAction.Done),
                    supportingText = {
                        Text(problem?.let { serverProblemText(it) } ?: stringResource(R.string.friends_server_dialog_note))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { text = ""; problem = null }) {
                    Text(stringResource(R.string.friends_server_dialog_default))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { problem = onSave(text) } }) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}
