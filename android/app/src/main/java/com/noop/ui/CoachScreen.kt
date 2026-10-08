package com.noop.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noop.R
import com.noop.ai.AiProvider
import com.noop.ai.ChatMsg
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.GlassBackdrop
import com.noop.ui.m3.glass
import com.noop.ui.m3.glassSource
import com.noop.ui.m3.rememberGlassBackdrop
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * AI Coach, drawn as a messenger conversation. The transcript scrolls over a faint colour wash and
 * under glass: a floating header (a capsule with the Coach's picture, its name and one status line,
 * and the settings action) and, at the foot, the suggestion chips and the entry capsule with its
 * one action button. Reply bubbles sit on the left in surfaceContainerHighest (Markdown), questions on the
 * right in primaryContainer, both in the same chat-sized type with their clock time in the bottom
 * corner; corners group a run from one side, each day is headed by one chip, a new message settles in
 * from its own side, and while a reply is on its way the Coach's mark turns beside a changing verb.
 * "Today's Brief", "Stopped", "Not Delivered" with Try Again / Update Key, and the long-press menu
 * (Copy, Share, Save to Journal, Try Again) are as before.
 *
 * The status line names the model that answers, and reads "typing…" for as long as a reply is being
 * produced. It no longer names the provider or carries the general caution about AI.
 *
 * Strictly opt-in and bring-your-own-key. Nothing is sent until a provider is connected and the wearer
 * asks: a typed question, a chip, or the "Today's Brief" chip. Opening the chat sends nothing (CR-10).
 * Not connected, the screen is an empty state whose "Set Up" opens the Coach settings page.
 */
@Composable
fun CoachScreen(
    vm: CoachViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyVersion by vm.keyVersion.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val customConnected by vm.customConnected.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    // Re-evaluate the gate whenever the stored key, provider, or custom-connect state changes.
    val configured = remember(keyVersion, provider, customConnected) { vm.isConfigured(context) }
    val backdrop = rememberGlassBackdrop()
    var headerHeight by remember { mutableStateOf(0.dp) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (configured) {
            CoachConversation(
                vm = vm,
                backdrop = backdrop,
                topInset = headerHeight,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize().glassSource(backdrop).coachWash().padding(top = headerHeight),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.coach_empty_title),
                    message = stringResource(R.string.coach_empty_message),
                    action = stringResource(R.string.coach_set_up),
                    onAction = onOpenSettings,
                )
            }
        }
        CoachHeader(
            backdrop = backdrop,
            model = model.takeIf { configured && it.isNotBlank() },
            busy = configured && sending,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
        )
    }
}

// MARK: - Header

/** The provider as the screen names it: the brand, or "Custom Server" for the wearer's own. */
@Composable
internal fun coachProviderLabel(p: AiProvider): String =
    if (p == AiProvider.CUSTOM) stringResource(R.string.coach_custom_server) else p.displayName

/** A round glass button holding one icon. */
@Composable
private fun GlassIconButton(
    backdrop: GlassBackdrop,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .glass(backdrop, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The floating header: the Coach's capsule and Settings (no Back: the screen is a tab's root). The
 * capsule's second line is the one status
 * the screen states about the Coach: "typing…" while a reply is being produced ([busy]), else the
 * [model] that will answer, else nothing. The capsule is at least 48 dp and grows with the text size
 * (CR-1). The app Scaffold already pads its content below the status bar.
 */
@Composable
private fun CoachHeader(
    backdrop: GlassBackdrop,
    model: String?,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .glass(backdrop, CircleShape)
                .padding(start = 6.dp, end = 18.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoachAvatar(size = 36.dp, alive = busy)
            Column(Modifier.padding(start = 10.dp).semantics(mergeDescendants = true) { heading() }) {
                Text(
                    stringResource(R.string.coach_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val typing = stringResource(R.string.coach_typing)
                AnimatedContent(
                    targetState = if (busy) typing else model,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "status",
                ) { status ->
                    if (status != null) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (status == typing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        GlassIconButton(backdrop, Icons.Filled.Tune, stringResource(R.string.coach_settings), onOpenSettings)
    }
}

// MARK: - Conversation

/** One line of the transcript as drawn, oldest first (the list renders it reversed, anchored low). */
private sealed interface TranscriptRow {
    val key: String

    data class DayChip(override val key: String, val ms: Long) : TranscriptRow
    data class BriefLabel(override val key: String) : TranscriptRow
    data class Bubble(
        override val key: String,
        val msg: ChatMsg,
        val timeMs: Long?,
        val followsSameSide: Boolean,
        val first: Boolean,
        val failed: Boolean,
        val isLast: Boolean,
        /** The reply being written right now, which arrives where the thinking line stood. */
        val arriving: Boolean,
    ) : TranscriptRow
    data class Stopped(override val key: String) : TranscriptRow
    data object Typing : TranscriptRow { override val key = "typing" }
    data class ErrorLine(val text: String) : TranscriptRow { override val key = "error" }
}

private fun transcriptRows(
    shown: List<ChatMsg>,
    times: Map<String, Long>,
    sending: Boolean,
    error: String?,
    allMessages: List<ChatMsg>,
): List<TranscriptRow> {
    val ids = shown.map { it.id }
    val failedIdx = CoachConversationRules.failedQuestionIndex(shown, sending, error)
    val rows = mutableListOf<TranscriptRow>()
    shown.forEachIndexed { i, m ->
        CoachConversationRules.dayChipBefore(i, ids, times)?.let { rows += TranscriptRow.DayChip("day-${m.id}", it) }
        if (m.role == "assistant" && m.isBrief) rows += TranscriptRow.BriefLabel("brief-${m.id}")
        val prev = shown.getOrNull(i - 1)
        val last = i == shown.lastIndex
        rows += TranscriptRow.Bubble(
            key = m.id,
            msg = m,
            timeMs = times[m.id],
            followsSameSide = prev != null && prev.role == m.role && !prev.isInterrupted,
            first = i == 0,
            failed = failedIdx == i,
            isLast = last,
            arriving = last && sending && m.role == "assistant",
        )
        if (m.role == "assistant" && m.isInterrupted) rows += TranscriptRow.Stopped("stopped-${m.id}")
    }
    if (CoachConversationRules.showsTyping(allMessages, sending)) rows += TranscriptRow.Typing
    if (!error.isNullOrEmpty() && failedIdx == null && !sending) rows += TranscriptRow.ErrorLine(error)
    return rows
}

@Composable
private fun CoachConversation(
    vm: CoachViewModel,
    backdrop: GlassBackdrop,
    topInset: Dp,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val times by vm.messageTimes.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val keyRejected by vm.keyRejected.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val contextual by vm.suggestions.collectAsStateWithLifecycle()
    // K15: the composer draft is persisted to SharedPreferences so it survives an app relaunch.
    // Restored on first composition, saved on every change. Keyed identically to the iOS twin.
    val draftPrefs = remember { context.getSharedPreferences("noop_coach_draft", android.content.Context.MODE_PRIVATE) }
    var draft by remember { mutableStateOf(draftPrefs.getString("draft", "") ?: "") }
    fun setDraft(value: String) {
        draft = value
        draftPrefs.edit().putString("draft", value).apply()
        if (error != null && !sending) vm.clearError()
    }
    var showFailure by remember { mutableStateOf(false) }
    // The greeting waits for the restore, so a stored transcript never opens behind a flash of it.
    var restored by remember { mutableStateOf(false) }

    // K2 + K5 ordering matters and both gate on an EMPTY transcript, so this is ONE coroutine,
    // sequential: restore whatever the prior launch persisted FIRST, retire it if it belongs to an
    // earlier day, THEN surface a brief the scheduled notification already generated (if any) — so K5
    // never overwrites K2's restore, and never appends a duplicate brief onto a transcript K2 just
    // repopulated. Nothing here reaches the provider: the interactive brief is only ever asked for by
    // its chip (CR-10).
    LaunchedEffect(Unit) {
        vm.loadPersistedMessagesIfNeeded()
        // The load runs once per PROCESS, so on a process kept alive overnight it returns without
        // re-checking the day and leaves yesterday's chat in memory, which K5 then refuses to replace.
        // Retiring here is what lets today's brief reach the screen at all (#2087).
        vm.retireStaleConversationIfNeeded()
        vm.consumeScheduledBriefIfAny(context)
        restored = true
    }
    // The contextual chips are re-derived when the chat empties, so a fresh sync updates them.
    LaunchedEffect(messages.isEmpty()) { if (messages.isEmpty()) vm.refreshSuggestions() }

    // K14 / CO-6: one haptic per outcome, and TalkBack reads what came (the start of the reply, or why it
    // failed). Nothing for a stop, which the wearer has just done themselves.
    var wasSending by remember { mutableStateOf(false) }
    val replyReceived = stringResource(R.string.coach_reply_received)
    LaunchedEffect(sending) {
        if (wasSending && !sending && messages.isNotEmpty()) {
            val failed = !error.isNullOrEmpty()
            val feedback = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    if (failed) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
            view.performHapticFeedback(feedback)
            val last = messages.last()
            val announcement = when {
                failed -> error
                last.role == "assistant" && !last.isInterrupted ->
                    last.text.replace(Regex("[*_`#|]"), "").trim().take(200).ifEmpty { replyReceived }
                else -> null
            }
            @Suppress("DEPRECATION")
            if (announcement != null) view.announceForAccessibility(announcement)
        }
        wasSending = sending
    }

    val shown = remember(messages) { CoachConversationRules.shown(messages) }
    val rows = remember(shown, times, sending, error, messages) { transcriptRows(shown, times, sending, error, messages) }
    val reversed = remember(rows) { rows.asReversed() }
    val listState = rememberLazyListState()
    // A new message (or the thinking line) scrolls the conversation to its end, as a messenger does.
    LaunchedEffect(shown.size, sending) { if (rows.isNotEmpty()) listState.animateScrollToItem(0) }
    // The list moves its items as layers, which redraws nothing, so the glass over it is told when the
    // list was laid out again (a scroll, or a reply growing and pushing the rest up under the header).
    SideEffect { backdrop.observe = { listState.layoutInfo } }
    // Tapping the Coach tab while it is already showing returns to the newest message.
    OnScrollToTop { listState.animateScrollToItem(0) }

    val canRetry = remember(messages, sending, consent) { vm.canRetryLastReply(context) }

    // The composer sits on the keyboard: the part of the IME height not already covered by the
    // navigation bar under this screen is added below it. While the conversation shows, the window is
    // told not to pan for the keyboard (which would push the header off screen); edge to edge it does
    // not resize either, so this padding is the only thing that moves.
    DisposableEffect(view) {
        val window = (view.context.findActivity())?.window
        val previous = window?.attributes?.softInputMode
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { if (window != null && previous != null) window.setSoftInputMode(previous) }
    }
    val density = LocalDensity.current
    var bottomGapPx by remember { mutableIntStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val lift = with(density) { (imeBottom - bottomGapPx).coerceAtLeast(0).toDp() }
    var footHeight by remember { mutableStateOf(0.dp) }

    val chips = CoachConversationRules.suggestions(
        messages = messages,
        configured = true,
        consent = consent,
        contextual = contextual,
        followUps = vm.followUpSuggestions,
    )

    Box(
        modifier
            .onGloballyPositioned { c ->
                val root = c.findRootCoordinates()
                bottomGapPx = (root.size.height - c.boundsInRoot().bottom).toInt().coerceAtLeast(0)
            }
            .padding(bottom = lift),
    ) {
        // Everything the glass blurs: the wash, the greeting and the transcript. Nothing in here may
        // itself be glass, which would have the picture draw itself.
        Box(Modifier.fillMaxSize().glassSource(backdrop).coachWash().coachFade(top = topInset, bottom = footHeight)) {
            AnimatedVisibility(
                visible = restored && rows.isEmpty(),
                enter = fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.94f),
                exit = fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.Center).padding(top = topInset, bottom = footHeight),
            ) {
                Greeting()
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = topInset + 8.dp, bottom = footHeight + 8.dp),
            ) {
                items(reversed, key = { it.key }) { row ->
                    when (row) {
                        is TranscriptRow.DayChip -> DayChip(row.ms)
                        is TranscriptRow.BriefLabel -> MetaLine(stringResource(R.string.coach_todays_brief), top = 8.dp)
                        is TranscriptRow.Bubble -> BubbleRow(
                            row = row,
                            canRetry = row.isLast && canRetry,
                            keyRejected = keyRejected,
                            onRetryReply = { vm.retryLastReply(context) },
                            onShowFailure = { showFailure = true },
                            onUpdateKey = onOpenSettings,
                            onSaveToJournal = { vm.saveAdviceToJournal(it) },
                        )
                        is TranscriptRow.Stopped -> MetaLine(stringResource(R.string.coach_stopped), top = 2.dp)
                        TranscriptRow.Typing -> SettleIn(fromEnd = false, animate = true) {
                            CoachThinking(Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp))
                        }
                        is TranscriptRow.ErrorLine -> ErrorLine(row.text, keyRejected, onUpdateKey = onOpenSettings)
                    }
                }
            }
        }

        JumpToLatest(
            backdrop = backdrop,
            listState = listState,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = footHeight + 8.dp),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { footHeight = with(density) { it.height.toDp() } },
        ) {
            AnimatedVisibility(
                visible = draft.isBlank() && !sending && chips.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SuggestionRow(backdrop = backdrop, chips = chips, onChoose = { chip ->
                    when (chip) {
                        CoachSuggestion.Brief -> vm.startBrief(context)
                        is CoachSuggestion.Prompt -> {
                            val text = coachPromptText(context, chip.english)
                            vm.send(context, text)
                        }
                    }
                })
            }
            Composer(
                backdrop = backdrop,
                draft = draft,
                onDraftChange = ::setDraft,
                sending = sending,
                onSend = {
                    val text = draft
                    if (text.isNotBlank() && !sending) {
                        vm.send(context, text)
                        setDraft("")
                    }
                },
                onStop = { vm.stop() },
            )
        }
    }

    if (showFailure) {
        val message = error.orEmpty()
        AlertDialog(
            onDismissRequest = { showFailure = false },
            text = { Text(message) },
            confirmButton = {
                Row {
                    if (keyRejected) {
                        TextButton(onClick = { showFailure = false; onOpenSettings() }) {
                            Text(stringResource(R.string.coach_update_key))
                        }
                    }
                    TextButton(onClick = { showFailure = false; vm.retryFailedQuestion(context) }) {
                        Text(stringResource(R.string.coach_try_again))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showFailure = false }) { Text(stringResource(R.string.coach_cancel)) }
            },
        )
    }
}

// MARK: - Transcript pieces

/** What an empty chat shows in its middle: the Coach's picture and one question back to the wearer. */
@Composable
private fun Greeting() {
    Column(
        Modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoachAvatar(size = 72.dp)
        Text(
            stringResource(R.string.coach_greeting),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** The chip that heads a day: Today, Yesterday, a weekday within the week, else a date. */
@Composable
private fun DayChip(ms: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val now = remember { System.currentTimeMillis() }
    val date = coachLocalDate(ms)
    val word = when (CoachConversationRules.dayWord(ms, now)) {
        CoachDayWord.TODAY -> stringResource(R.string.coach_stamp_today)
        CoachDayWord.YESTERDAY -> stringResource(R.string.coach_stamp_yesterday)
        CoachDayWord.WEEKDAY -> date.format(DateTimeFormatter.ofPattern("EEEE", locale))
            .replaceFirstChar { it.titlecase(locale) }
        CoachDayWord.DATE -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            word,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** A line in the transcript's small grey type on the reply side (the brief's name, "Stopped"). */
@Composable
private fun MetaLine(text: String, top: Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = top, bottom = 2.dp),
    )
}

/**
 * Lets [content] settle in when it first appears: it fades up from slightly small and slightly low,
 * growing out of the bottom corner on its own side ([fromEnd] for the wearer's). Only when [animate],
 * so a restored transcript and a row scrolled back into view simply stand there; and never under
 * Remove animations or battery saver (#909).
 */
@Composable
private fun SettleIn(fromEnd: Boolean, animate: Boolean, content: @Composable () -> Unit) {
    val still = rememberPoseStill()
    val progress = remember { Animatable(if (animate && !still) 0f else 1f) }
    LaunchedEffect(Unit) { if (progress.value < 1f) progress.animateTo(1f, NoopMotion.card()) }
    Box(
        Modifier.graphicsLayer {
            val v = progress.value
            alpha = v.coerceIn(0f, 1f)
            scaleX = 0.88f + 0.12f * v
            scaleY = 0.88f + 0.12f * v
            translationY = (1f - v) * 14.dp.toPx()
            transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 1f)
        },
    ) {
        content()
    }
}

/** A message's clock time, in the device's 12 or 24 hour form. */
@Composable
private fun clockTime(ms: Long): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(ms, locale) {
        val pattern = if (ClockPrefs.uses24Hour(context)) "HH:mm" else "h:mm a"
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern, locale))
    }
}

/** How recently a message must have arrived for its bubble to settle in rather than simply stand. */
private const val FRESH_MS = 2500L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleRow(
    row: TranscriptRow.Bubble,
    canRetry: Boolean,
    keyRejected: Boolean,
    onRetryReply: () -> Unit,
    onShowFailure: () -> Unit,
    onUpdateKey: () -> Unit,
    onSaveToJournal: (String) -> Unit,
) {
    val msg = row.msg
    val outgoing = msg.role == "user"
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    var menu by remember { mutableStateOf(false) }
    // A reply may run wider than a question: it is the long one, and width is what keeps it short.
    val maxWidth = (LocalConfiguration.current.screenWidthDp * (if (outgoing) 0.80f else 0.88f)).dp
    val corners = CoachConversationRules.bubbleCorners(outgoing, row.followsSameSide)
    val shape = RoundedCornerShape(corners[0].dp, corners[1].dp, corners[2].dp, corners[3].dp)
    val said = stringResource(if (outgoing) R.string.coach_you_said else R.string.coach_coach_said, msg.text)
    // A run from one side sits close, a new speaker starts a little further down.
    val top = when {
        row.first -> 0.dp
        row.followsSameSide -> 2.dp
        else -> 8.dp
    }
    val scheme = MaterialTheme.colorScheme
    val fill = if (outgoing) scheme.primaryContainer else scheme.surfaceContainerHighest
    val ink = if (outgoing) scheme.onPrimaryContainer else scheme.onSurface
    val body = MaterialTheme.typography.bodyMedium
    val meta = MaterialTheme.typography.labelSmall

    // The time sits in the bubble's bottom corner, over room the last line of text leaves for it. When
    // the message cannot leave that room (a reply ending in a table or a heading), it gets its own line.
    val time = row.timeMs?.let { clockTime(it) }
    val measurer = rememberTextMeasurer()
    val tail: TextUnit = remember(time, meta, density) {
        if (time == null) TextUnit.Unspecified
        else with(density) { (measurer.measure(time, meta).size.width + 8.dp.roundToPx()).toSp() }
    }
    val inline = time != null && (outgoing || coachMarkdownTakesTail(msg.text))
    val fresh = remember { row.arriving || row.timeMs?.let { System.currentTimeMillis() - it < FRESH_MS } == true }

    Column(Modifier.fillMaxWidth().padding(top = top)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.failed) {
                IconButton(onClick = onShowFailure) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = stringResource(R.string.coach_not_delivered),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            SettleIn(fromEnd = outgoing, animate = fresh) {
                Surface(
                    shape = shape,
                    color = fill,
                    contentColor = ink,
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .clip(shape)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .clearAndSetSemantics { contentDescription = said },
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Column {
                            if (outgoing) {
                                CoachPlainText(msg.text, color = ink, style = body, tail = tail)
                            } else {
                                CoachMarkdown(msg.text, color = ink, body = body, tail = if (inline) tail else TextUnit.Unspecified)
                            }
                            if (time != null && !inline) Spacer(Modifier.height(with(density) { meta.lineHeight.toDp() }))
                        }
                        if (time != null) {
                            Text(time, style = meta, color = ink.copy(alpha = 0.62f), modifier = Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.coach_copy_action)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { clipboard.setText(AnnotatedString(msg.text)); menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.coach_share_action)) },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                        onClick = {
                            menu = false
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, msg.text)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        },
                    )
                    if (!outgoing) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.coach_save_to_journal)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                            onClick = { onSaveToJournal(msg.text); menu = false },
                        )
                        if (canRetry) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.coach_try_again)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { menu = false; onRetryReply() },
                            )
                        }
                    }
                }
            }
        }
        if (row.failed) {
            Text(
                stringResource(R.string.coach_not_delivered),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp),
            )
            if (keyRejected) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onUpdateKey) { Text(stringResource(R.string.coach_update_key)) }
                }
            }
        }
    }
}

/** A failure with no question of its own to mark (the brief, or a reply cut off mid-way). */
@Composable
private fun ErrorLine(text: String, keyRejected: Boolean, onUpdateKey: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (keyRejected) TextButton(onClick = onUpdateKey) { Text(stringResource(R.string.coach_update_key)) }
    }
}

/**
 * The button back to the newest message, shown once the wearer has scrolled away from it. The list is
 * reversed, so its first item is the newest and "away" is any distance from the list's start.
 */
@Composable
private fun JumpToLatest(backdrop: GlassBackdrop, listState: LazyListState, modifier: Modifier = Modifier) {
    val threshold = with(LocalDensity.current) { 240.dp.roundToPx() }
    val away by remember(listState, threshold) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = away,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f),
        modifier = modifier,
    ) {
        GlassIconButton(
            backdrop = backdrop,
            icon = Icons.Filled.KeyboardArrowDown,
            label = stringResource(R.string.coach_jump_to_latest),
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            size = 40.dp,
        )
    }
}

// MARK: - Composer

/** The engine's English prompts, as the screen shows and sends them in the app's language. */
private val PROMPT_RES: Map<String, Int> = mapOf(
    "How's my recovery trending this week?" to R.string.coach_prompt_recovery_week,
    "What should today's training look like?" to R.string.coach_prompt_training_today,
    "Analyse my sleep" to R.string.coach_prompt_analyse_sleep,
    "Why am I run down?" to R.string.coach_prompt_run_down,
    "Active recovery only today — what should I do?" to R.string.coach_prompt_active_recovery,
    "Quality over volume today — plan my session" to R.string.coach_prompt_quality_volume,
    "Green light — how hard can I push today?" to R.string.coach_prompt_green_light,
    "Why is my HRV trending down?" to R.string.coach_prompt_hrv_down,
    "I slept poorly — how do I recover today?" to R.string.coach_prompt_slept_poorly,
    "Have I done enough today, or push more?" to R.string.coach_prompt_done_enough,
    "Tell me more about that" to R.string.coach_prompt_tell_more,
    "What should I do next?" to R.string.coach_prompt_next,
    "How does today compare to this week?" to R.string.coach_prompt_compare_week,
    "Give me a specific action plan" to R.string.coach_prompt_action_plan,
)

internal fun coachPromptText(context: android.content.Context, english: String): String =
    PROMPT_RES[english]?.let { context.getString(it) } ?: english

@Composable
private fun SuggestionRow(backdrop: GlassBackdrop, chips: List<CoachSuggestion>, onChoose: (CoachSuggestion) -> Unit) {
    val context = LocalContext.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        itemsIndexed(chips) { i, chip ->
            val label = when (chip) {
                CoachSuggestion.Brief -> stringResource(R.string.coach_todays_brief)
                is CoachSuggestion.Prompt -> coachPromptText(context, chip.english)
            }
            val a11y = stringResource(R.string.coach_suggested_prompt, label)
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .glass(backdrop, CircleShape)
                    .clickable(role = Role.Button) { onChoose(chip) }
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = a11y },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (i == 0) CoachSpark(size = 14.dp, color = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** What the composer's one button does at this moment. */
private enum class ComposerAction { SEND, STOP, MIC, STOP_VOICE, NONE }

/**
 * The entry row: a glass capsule for the question and one round button beside it, which is whatever
 * can be done now: the mic while the field is empty, Send once there is text, Stop while a reply is
 * being written, and Stop for the mic while it is recording. Dictation APPENDS to what was typed
 * (CR-12) and stays stoppable for the whole recording; the microphone permission is asked on the first
 * tap, and once refused the mic opens the app's settings instead.
 */
@Composable
private fun Composer(
    backdrop: GlassBackdrop,
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var micRefused by remember { mutableStateOf(false) }
    // The draft as it was when dictation started; the live transcript is appended to it, never over it.
    var dictationBase by remember { mutableStateOf("") }
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)

    val voiceInput = remember {
        CoachVoiceInput(
            context = context,
            onPartial = { partial -> currentOnDraftChange(CoachConversationRules.dictationDraft(dictationBase, partial)) },
            onFinal = { final ->
                // The transcript is already in the draft; the final text only settles it.
                if (final.isNotBlank()) currentOnDraftChange(CoachConversationRules.dictationDraft(dictationBase, final))
                recording = false
            },
            onError = { msg -> voiceStatus = msg; recording = false },
        )
    }
    DisposableEffect(voiceInput) { onDispose { voiceInput.destroy() } }

    fun startDictation() {
        dictationBase = draft.trim()
        voiceStatus = null
        voiceInput.start()
        recording = true
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation() else micRefused = true
    }

    val voiceAvailable = remember { voiceInput.isAvailable() }
    val placeholder = stringResource(if (recording) R.string.coach_listening else R.string.coach_ask)
    val questionLabel = stringResource(R.string.coach_question)
    val action = when {
        sending -> ComposerAction.STOP
        recording -> ComposerAction.STOP_VOICE
        draft.isNotBlank() -> ComposerAction.SEND
        voiceAvailable -> ComposerAction.MIC
        else -> ComposerAction.NONE
    }

    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        voiceStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val field = RoundedCornerShape(24.dp)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(field)
                    .glass(backdrop, field)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .semantics { contentDescription = questionLabel },
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                )
            }
            ActionButton(
                backdrop = backdrop,
                action = action,
                onClick = {
                    when (action) {
                        ComposerAction.STOP -> onStop()
                        ComposerAction.SEND -> onSend()
                        ComposerAction.STOP_VOICE -> voiceInput.stop()
                        ComposerAction.MIC -> when {
                            voiceInput.isPermissionGranted() -> startDictation()
                            micRefused -> context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                            else -> permLauncher.launch(voiceInput.requiredPermission)
                        }
                        ComposerAction.NONE -> Unit
                    }
                },
            )
        }
    }
}

/** The composer's round button: glass under the mic, filled once it sends or stops. */
@Composable
private fun ActionButton(backdrop: GlassBackdrop, action: ComposerAction, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The unfilled states fade to the same hue at no opacity, so the change never passes through grey.
    val fill by animateColorAsState(
        when (action) {
            ComposerAction.SEND -> scheme.primary
            ComposerAction.STOP -> scheme.primaryContainer
            ComposerAction.STOP_VOICE -> scheme.errorContainer
            ComposerAction.MIC, ComposerAction.NONE -> scheme.primary.copy(alpha = 0f)
        },
        label = "fill",
    )
    val ink by animateColorAsState(
        when (action) {
            ComposerAction.SEND -> scheme.onPrimary
            ComposerAction.STOP -> scheme.onPrimaryContainer
            ComposerAction.STOP_VOICE -> scheme.onErrorContainer
            ComposerAction.MIC -> scheme.onSurface
            ComposerAction.NONE -> scheme.onSurface.copy(alpha = 0.38f)
        },
        label = "ink",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .glass(backdrop, CircleShape)
            .background(fill)
            .clickable(enabled = action != ComposerAction.NONE, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = action,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.6f)) togetherWith
                    (fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.6f))
            },
            label = "action",
        ) { shown ->
            when (shown) {
                ComposerAction.STOP -> Icon(Icons.Filled.Stop, stringResource(R.string.coach_stop), tint = ink)
                ComposerAction.STOP_VOICE -> Icon(Icons.Filled.StopCircle, stringResource(R.string.coach_stop_voice_input), tint = ink)
                ComposerAction.MIC -> Icon(Icons.Filled.Mic, stringResource(R.string.coach_voice_input), tint = ink)
                ComposerAction.SEND, ComposerAction.NONE ->
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.coach_send), tint = ink)
            }
        }
    }
}

/** The Activity behind a Compose context (which may be wrapped, e.g. for the app language). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
