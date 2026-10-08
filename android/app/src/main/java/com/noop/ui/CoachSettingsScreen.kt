package com.noop.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ai.AiProvider
import com.noop.ai.CustomAiAuthHeader
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SwitchRow
import com.noop.ui.sleep.ClockPicker

/**
 * Coach settings (#2243), a full-screen page drawn as Android Settings: the connection (provider, server,
 * model, key), what the coach may read, its instructions, the morning brief, and the two ways to end a
 * conversation. Twin of Swift `CoachSettingsView`, section for section.
 *
 * The provider can only be chosen while nothing is connected. A stored key records which provider it
 * belongs to and is never sent anywhere else (AiKeyStore.read(ctx, provider)), so switching provider
 * under a key would leave a key that cannot be used; Disconnect (confirmed) is the way to change it. The
 * first successful connection returns to the conversation.
 *
 * `vm` is deliberately REQUIRED. The default `viewModel()` resolves against the NavBackStackEntry, so it
 * would hand this destination its own CoachViewModel rather than the conversation's: consent is held in
 * memory and read by `send`, so a revoke made against a second instance would leave the conversation
 * sending on the old one. AppRoot passes the Coach entry's view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachSettingsScreen(vm: CoachViewModel, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var instructions by rememberSaveable { mutableStateOf(false) }
    // The brief's state is read from prefs on show: this page can be the first to render it.
    LaunchedEffect(Unit) { vm.loadBriefSettings(context) }

    if (instructions) {
        BackHandler { instructions = false }
        CoachInstructionsPage(vm, onBack = { instructions = false })
        return
    }

    val keyVersion by vm.keyVersion.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val customConnected by vm.customConnected.collectAsStateWithLifecycle()
    val configured = remember(keyVersion, provider, customConnected) { vm.isConfigured(context) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(stringResource(R.string.coach_settings), onBack)
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding, end = M3Dimens.screenPadding,
                top = 8.dp, bottom = M3Dimens.bottomBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item { ConnectionSection(vm, configured, onConnected = onBack) }
            if (configured) {
                item { DataSection(vm, provider) }
                item { InstructionsRow(vm, onOpen = { instructions = true }) }
                item { BriefSection(vm) }
                item { EndSection(vm, provider) }
            }
        }
    }
}

// MARK: - Connection

@Composable
private fun ConnectionSection(vm: CoachViewModel, configured: Boolean, onConnected: () -> Unit) {
    val context = LocalContext.current
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val models by vm.availableModels.collectAsStateWithLifecycle()
    val refreshing by vm.refreshingModels.collectAsStateWithLifecycle()
    val baseUrl by vm.customBaseUrl.collectAsStateWithLifecycle()
    val authHeader by vm.customAuthHeader.collectAsStateWithLifecycle()
    val customConnected by vm.customConnected.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val keyVersion by vm.keyVersion.collectAsStateWithLifecycle()
    val isCustom = provider == AiProvider.CUSTOM
    // Re-read whenever a key is saved or cleared (and per provider: a key belongs to one provider).
    val hasKey = remember(keyVersion, provider) { vm.hasKey(context) }

    // Pending key text (never persisted here, handed to saveKey). Also the repair for a rejected key:
    // saveKey replaces the stored key and keeps the transcript.
    var keyDraft by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf(false) }
    var customModelDraft by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }

    fun applyCustomModel() {
        val id = customModelDraft.trim()
        if (id.isEmpty()) return
        vm.selectModel(context, id)
        customModel = false
    }

    /** Save the key (cloud) or connect the server (custom, the key optional there); the first successful
     *  connection returns to the conversation. */
    fun commit() {
        if (customModel) applyCustomModel()
        val wasConfigured = vm.isConfigured(context)
        val trimmed = keyDraft.trim()
        if (trimmed.isNotEmpty()) {
            vm.saveKey(context, trimmed)
            keyDraft = ""
        }
        if (vm.provider.value == AiProvider.CUSTOM && !vm.customConnected.value) vm.connectCustom(context)
        if (!wasConfigured && vm.isConfigured(context)) onConnected()
    }

    Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap)) {
        ListGroup {
            item { shape ->
                if (configured) {
                    ListRow(shape = shape, title = stringResource(R.string.coach_settings_provider), trailing = {
                        ValueText(coachProviderLabel(provider))
                    })
                } else {
                    PickerRow(
                        shape = shape,
                        title = stringResource(R.string.coach_settings_provider),
                        value = coachProviderLabel(provider),
                        options = AiProvider.entries.map { it to coachProviderLabel(it) },
                        onPick = { vm.selectProvider(context, it) },
                    )
                }
            }
            if (isCustom) {
                item { shape ->
                    FieldRow(
                        shape = shape,
                        label = stringResource(R.string.coach_settings_server_url),
                        value = baseUrl,
                        onValueChange = { vm.setCustomBaseUrl(context, it) },
                        placeholder = "http://localhost:11434/v1",
                        keyboardType = KeyboardType.Uri,
                    )
                }
                // How the key is sent is a server detail most readers never change.
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.coach_settings_advanced),
                        trailing = {
                            Icon(
                                if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { advanced = !advanced },
                    )
                }
                if (advanced) {
                    item { shape ->
                        PickerRow(
                            shape = shape,
                            title = stringResource(R.string.coach_settings_key_header),
                            value = authHeader.displayName,
                            options = CustomAiAuthHeader.entries.map { it to it.displayName },
                            onPick = { vm.setCustomAuthHeader(context, it) },
                        )
                    }
                }
            }
            item { shape ->
                val customLabel = stringResource(R.string.coach_settings_custom_model)
                PickerRow(
                    shape = shape,
                    title = stringResource(R.string.coach_settings_model),
                    value = if (customModel) customLabel else model,
                    options = models.map { it to it } + (CUSTOM_MODEL_TAG to customLabel),
                    onPick = { picked ->
                        if (picked == CUSTOM_MODEL_TAG) {
                            customModel = true
                            if (customModelDraft.isEmpty()) customModelDraft = model
                        } else {
                            customModel = false
                            vm.selectModel(context, picked)
                        }
                    },
                )
            }
            if (customModel) {
                item { shape ->
                    FieldRow(
                        shape = shape,
                        label = stringResource(R.string.coach_settings_model_id),
                        value = customModelDraft,
                        onValueChange = { customModelDraft = it },
                        onDone = ::applyCustomModel,
                    )
                }
            }
            item { shape ->
                FieldRow(
                    shape = shape,
                    label = stringResource(if (isCustom) R.string.coach_settings_api_key_optional else R.string.coach_settings_api_key),
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    // The field empties on save (the stored key is never shown again), so without this
                    // line a saved key and no key look the same.
                    supporting = if (hasKey && keyDraft.isEmpty()) stringResource(R.string.coach_settings_key_saved) else null,
                    password = true,
                    onDone = ::commit,
                )
            }
            // The row appears once there is a key to save, so an empty form never shows a greyed button
            // that reads as another field.
            if (keyDraft.isNotBlank() && !(isCustom && !configured)) {
                item { shape ->
                    ActionRow(
                        shape = shape,
                        title = stringResource(if (hasKey) R.string.coach_update_key else R.string.coach_settings_save_key),
                        onClick = ::commit,
                    )
                }
            }
        }
        // Setup failures only: once connected, the conversation shows a failed send under itself.
        val errorText = error
        if (!configured && !errorText.isNullOrEmpty()) {
            Text(
                errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (isCustom && !customConnected) {
            ListGroup {
                item { shape ->
                    ActionRow(
                        shape = shape,
                        title = stringResource(R.string.coach_settings_connect),
                        enabled = baseUrl.isNotBlank(),
                        onClick = ::commit,
                    )
                }
            }
        } else if (!isCustom) {
            ListGroup {
                item { shape ->
                    ActionRow(
                        shape = shape,
                        title = stringResource(R.string.coach_settings_refresh_models),
                        enabled = hasKey && !refreshing,
                        busy = refreshing,
                        onClick = { vm.refreshModels(context) },
                    )
                }
            }
        }
    }
}

/** The sentinel option that opens the free-text model id instead of selecting a real one. */
private const val CUSTOM_MODEL_TAG = "__custom__"

// MARK: - Data

/** Explicit, revocable permission for the coach to read and send the wearer's data (off by default), and
 *  the opt-in that depends on it. The footer names who receives what, whether on or off (#2033, CR-10). */
@Composable
private fun DataSection(vm: CoachViewModel, provider: AiProvider) {
    val context = LocalContext.current
    val consent by vm.consent.collectAsStateWithLifecycle()
    var signals by remember { mutableStateOf(NoopPrefs.coachSignals(context)) }
    ListGroup(footer = stringResource(R.string.coach_settings_data_footer, coachProviderLabel(provider))) {
        item { shape ->
            SwitchRow(
                shape = shape,
                title = stringResource(R.string.coach_settings_use_my_data),
                checked = consent,
                onCheckedChange = { vm.setConsent(context, it) },
            )
        }
        if (consent) {
            // v5: summaries of the strongest patterns + Lab Book, never raw readings.
            item { shape ->
                SwitchRow(
                    shape = shape,
                    title = stringResource(R.string.coach_settings_patterns),
                    checked = signals,
                    onCheckedChange = {
                        signals = it
                        NoopPrefs.setCoachSignals(context, it)
                    },
                )
            }
        }
    }
}

// MARK: - Instructions

@Composable
private fun InstructionsRow(vm: CoachViewModel, onOpen: () -> Unit) {
    val hasCustom by vm.hasCustomPrompt.collectAsStateWithLifecycle()
    ListGroup {
        item { shape ->
            ListRow(
                shape = shape,
                title = stringResource(R.string.coach_settings_instructions),
                trailing = {
                    ValueText(stringResource(if (hasCustom) R.string.coach_settings_custom else R.string.coach_settings_default))
                    ChevronRight()
                },
                onClick = onOpen,
            )
        }
    }
}

/** The instructions that frame every reply, edited in place (they persist and apply from the next
 *  message), with Reset in the bar. Twin of Swift `CoachInstructionsPage`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoachInstructionsPage(vm: CoachViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prompt by vm.systemPrompt.collectAsStateWithLifecycle()
    val hasCustom by vm.hasCustomPrompt.collectAsStateWithLifecycle()
    val label = stringResource(R.string.coach_settings_instructions)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(label, onBack, actions = {
            TextButton(onClick = { vm.resetSystemPrompt(context) }, enabled = hasCustom) {
                Text(stringResource(R.string.coach_settings_reset))
            }
        })
        OutlinedTextField(
            value = prompt,
            onValueChange = { vm.setSystemPrompt(context, it) },
            textStyle = MaterialTheme.typography.bodyLarge,
            // Fills the page and scrolls inside itself, however long the instructions grow.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(M3Dimens.screenPadding),
        )
    }
}

// MARK: - Morning brief

/** K5: the scheduled morning-brief notification: toggle, time, and an explicit "Generate Now". Turning it
 *  on asks for the notification permission it needs (Android 13+), and says so when it is refused. */
@Composable
private fun BriefSection(vm: CoachViewModel) {
    val context = LocalContext.current
    val enabled by vm.briefEnabled.collectAsStateWithLifecycle()
    val minutes by vm.briefMinutes.collectAsStateWithLifecycle()
    val generating by vm.briefGenerating.collectAsStateWithLifecycle()
    val status by vm.briefStatus.collectAsStateWithLifecycle()
    var notificationsOff by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val is24h = remember { ClockPrefs.uses24Hour(context) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setBriefEnabled(context, true) else notificationsOff = true
    }

    fun toggle(on: Boolean) {
        notificationsOff = false
        when {
            !on -> vm.setBriefEnabled(context, false)
            NotificationManagerCompat.from(context).areNotificationsEnabled() -> vm.setBriefEnabled(context, true)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> notificationsOff = true
        }
    }

    val footer = when {
        notificationsOff -> stringResource(R.string.coach_settings_notifications_off)
        else -> status
    }
    ListGroup(footer = footer) {
        item { shape ->
            SwitchRow(
                shape = shape,
                title = stringResource(R.string.coach_settings_morning_brief),
                checked = enabled,
                onCheckedChange = ::toggle,
            )
        }
        if (enabled) {
            item { shape ->
                ListRow(
                    shape = shape,
                    title = stringResource(R.string.coach_settings_time),
                    trailing = { ValueText(formatClock(minutes, is24h)) },
                    onClick = { picking = true },
                )
            }
            item { shape ->
                ActionRow(
                    shape = shape,
                    title = stringResource(R.string.coach_settings_generate_now),
                    enabled = !generating,
                    busy = generating,
                    onClick = { vm.generateBriefNow(context) },
                )
            }
        }
    }
    if (picking) {
        ClockPicker(
            title = stringResource(R.string.coach_settings_time),
            hour = minutes / 60,
            minute = minutes % 60,
            is24h = is24h,
            onPick = { h, m -> vm.setBriefMinutes(context, h * 60 + m); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** "07:00" / "7:00 AM" for minutes since midnight, in the reader's clock. */
private fun formatClock(minutes: Int, is24h: Boolean): String {
    val h = minutes / 60
    val m = minutes % 60
    if (is24h) return "%02d:%02d".format(h, m)
    val h12 = if (h % 12 == 0) 12 else h % 12
    val ampm = java.text.DateFormatSymbols.getInstance().amPmStrings[if (h < 12) 0 else 1]
    return "%d:%02d %s".format(h12, m, ampm)
}

// MARK: - Clear · disconnect

@Composable
private fun EndSection(vm: CoachViewModel, provider: AiProvider) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    ListGroup {
        item { shape ->
            ActionRow(
                shape = shape,
                title = stringResource(R.string.coach_settings_clear_conversation),
                destructive = true,
                enabled = messages.isNotEmpty(),
                onClick = { confirmClear = true },
            )
        }
        item { shape ->
            ActionRow(
                shape = shape,
                title = stringResource(R.string.coach_settings_disconnect),
                destructive = true,
                onClick = { confirmDisconnect = true },
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.coach_settings_clear_confirm)) },
            text = { Text(stringResource(R.string.coach_clear_conversation_message)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearConversation() }) {
                    Text(stringResource(R.string.coach_settings_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.coach_cancel)) } },
        )
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.coach_settings_disconnect_confirm, coachProviderLabel(provider))) },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; vm.disconnect(context) }) {
                    Text(stringResource(R.string.coach_settings_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.coach_cancel)) } },
        )
    }
}

// MARK: - Row pieces

/** A setting's current value, grey at the row's end (Pixel Settings states a value this way). */
@Composable
private fun ValueText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 200.dp),
    )
}

/** A row that states its value and opens a menu of [options] to change it. */
@Composable
private fun <T> PickerRow(
    shape: Shape,
    title: String,
    value: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(
            shape = shape,
            title = title,
            trailing = { ValueText(value) },
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (option, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

/** A text field set in a list group row, its label floating inside it. */
@Composable
private fun FieldRow(
    shape: Shape,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    supporting: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supporting?.let { { Text(it) } },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                autoCorrect = false,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A row that is an action: its title in the primary colour (the error colour when destructive). */
@Composable
private fun ActionRow(
    shape: Shape,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    busy: Boolean = false,
) {
    ListRow(
        shape = shape,
        title = title,
        titleColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        enabled = enabled,
        trailing = if (busy) {
            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else null,
        onClick = onClick,
    )
}
