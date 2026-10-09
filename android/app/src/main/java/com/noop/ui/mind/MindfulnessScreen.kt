package com.noop.ui.mind

import com.noop.ui.m3.SheetBackdropEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.BreathProtocolCatalog
import com.noop.analytics.BreathProtocolCategory
import com.noop.analytics.ResonanceEngine
import com.noop.ui.AppViewModel
import com.noop.ui.BiofeedbackPrefs
import com.noop.ui.HapticPrefs
import com.noop.ui.NoopPrefs
import com.noop.ui.StressNudgeCenter
import com.noop.ui.breathPresenceIntroBodyRes
import com.noop.ui.breathPresenceIntroTitleRes
import com.noop.ui.breathProtocolCopyIds
import com.noop.ui.localizedBreathTitle
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.SwitchRow
import com.noop.ui.rememberReduceMotion
import com.noop.ui.rrPackets
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Mindfulness (twin of iOS BreathingView, laid out as Apple's Mindfulness / Fitbit's Relax)
//
// A card per mode (Breathe, Resonance, Calm) with a ▶, and the options under them. A session runs full
// screen and dark around the flower, with ⌄ to put it away (it then shows as a row here) and ✕ to end; it
// ends on one summary card. Resonance (find your pace) and Calm (a metronome just below the heart) are
// walked by the same hub. The passive stress check-in asks its one question in a short sheet.
//
// Leaving this page ends a running session and its strap pattern.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MindfulnessScreen(vm: AppViewModel, onBack: () -> Unit, onOpenDevices: () -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val scope = rememberCoroutineScope()
    val live by vm.live.collectAsStateWithLifecycle()
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()

    val tones = remember { BreathTonePlayer(context) }
    val hub = remember { BreathHub(vm, scope, context.applicationContext, tones) }

    var pace by remember { mutableStateOf<BreathPace>(BreathPace.Catalog("coherence_5_5")) }
    var length by remember { mutableStateOf(BreathLength.Ten) }
    // Opt-in soft tone on each phase; the player honours the ringer mode, so silent stays silent.
    var audioCues by remember { mutableStateOf(NoopPrefs.of(context).getBoolean(KEY_BREATHE_AUDIO_CUES, false)) }
    // The locked resonance pace (br/min), or null; the sweep writes it.
    var lockedBpm by remember { mutableStateOf(BiofeedbackPrefs.lockedPace(context)) }
    var breathingHaptics by remember { mutableStateOf(HapticPrefs.enabled(context, HapticPrefs.BREATHING)) }
    var showEdu by remember { mutableStateOf(false) }
    var askSweep by remember { mutableStateOf(false) }
    var paceMenu by remember { mutableStateOf(false) }
    var lengthMenu by remember { mutableStateOf(false) }

    // One realtime-HR count while the page is up; leaving ends the session, its strap pattern and the tones.
    DisposableEffect(Unit) {
        vm.requestRealtimeHr()
        onDispose {
            hub.stopAll()
            tones.release()
            vm.releaseRealtimeHr()
        }
    }
    // rrSeq-keyed: equal consecutive packets both count (see LiveRrPackets.kt).
    LaunchedEffect(Unit) { vm.live.rrPackets().collect { hub.ingest(it) } }
    // #769: the strap dropping in the middle of a paced session ends it and clears the strap pattern.
    LaunchedEffect(live.bonded) { if (!live.bonded && hub.kind == BreathKind.Paced) hub.end() }
    // A finished sweep may have locked a pace; the haptics switch may have changed in Settings meanwhile.
    LaunchedEffect(hub.summary, hub.presented) {
        lockedBpm = BiofeedbackPrefs.lockedPace(context)
        breathingHaptics = HapticPrefs.enabled(context, HapticPrefs.BREATHING)
    }
    // A session put away with ⌄ keeps the screen awake on the page too.
    val view = LocalView.current
    val running = hub.kind != null
    DisposableEffect(running) {
        if (running) view.keepScreenOn = true
        onDispose { if (running) view.keepScreenOn = false }
    }

    val breatheTitle = stringResource(R.string.mind_breathe)
    val resonanceTitle = stringResource(R.string.mind_resonance)
    val calmTitle = stringResource(R.string.mind_calm)
    fun paceLabel(p: BreathPace): String = when (p) {
        is BreathPace.Catalog -> localizedBreathTitle(p.id)
        BreathPace.Resonance -> resonanceTitle
    }
    fun startPaced(p: BreathPace) {
        hub.startPaced(paceLabel(p), pacedPlan(p, lockedBpm), length.targetSeconds, reduceMotion, audioCues)
    }

    val canBuzz = live.bonded && live.encryptedBond
    // Petals add up to light on a dark page; on a light one they simply layer.
    val darkPage = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val gate = calmGate(canBuzz, breathingHaptics, bpm)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.browse_mindfulness), onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding,
                end = M3Dimens.screenPadding,
                top = 8.dp,
                bottom = M3Dimens.bottomBarClearance + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (hub.kind != null && !hub.presented) {
                item(key = "running") { RunningRow(hub) }
            }
            item(key = "breathe") {
                ModeCard(
                    title = breatheTitle,
                    detail = paceLabel(pace) + " · " + stringResource(length.labelRes),
                    tint = Health.colors.respiratory,
                    enabled = true,
                    glyph = { BreathFlower(1f, Health.colors.respiratory, additive = darkPage, modifier = Modifier.size(36.dp)) },
                    onClick = { startPaced(pace) },
                )
            }
            item(key = "resonance") {
                ModeCard(
                    title = resonanceTitle,
                    detail = lockedBpm?.let { stringResource(R.string.mind_your_pace_bpm, breathPaceText(it, locale)) }
                        ?: stringResource(R.string.mind_find_your_pace),
                    tint = Health.colors.mind,
                    enabled = true,
                    glyph = { Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Health.colors.mind, modifier = Modifier.size(28.dp)) },
                    onClick = { askSweep = true },
                )
            }
            item(key = "calm") {
                // An unavailable mode dims its name and ▶ only: the line under it says why, and stays readable.
                ModeCard(
                    title = calmTitle,
                    detail = stringResource(gate.detailRes),
                    tint = Health.colors.body,
                    enabled = gate == CalmGate.Ready,
                    glyph = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = Health.colors.body, modifier = Modifier.size(28.dp)) },
                    // Without a strap the card opens Devices; waiting for haptics or a resting pulse it does nothing.
                    onClick = when (gate) {
                        CalmGate.Ready -> ({ hub.startCalm(calmTitle, reduceMotion) })
                        CalmGate.NeedsStrap -> onOpenDevices
                        else -> null
                    },
                )
            }
            item(key = "options-title") { SectionHeader(stringResource(R.string.mind_options)) }
            item(key = "options") {
                ListGroup {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.mind_pace),
                            onClick = { paceMenu = true },
                            // The menu hangs from the value it changes, at the row's trailing edge.
                            trailing = {
                                Box {
                                    MenuValue(paceLabel(pace))
                                    DropdownMenu(expanded = paceMenu, onDismissRequest = { paceMenu = false }) {
                                        val protocols = BreathProtocolCatalog.pickerProtocols
                                        fun pick(next: BreathPace) {
                                            paceMenu = false
                                            if (next != pace) {
                                                pace = next
                                                if (next is BreathPace.Catalog) {
                                                    BreathProtocolCatalog.protocolById(next.id)?.let {
                                                        length = BreathLength.fromRecommended(it.recommendedDurationMs)
                                                    }
                                                }
                                            }
                                        }
                                        for (p in protocols.filter { it.category != BreathProtocolCategory.PRESENCE }) {
                                            DropdownMenuItem(text = { Text(localizedBreathTitle(p.id)) }, onClick = { pick(BreathPace.Catalog(p.id)) })
                                        }
                                        HorizontalDivider()
                                        for (p in protocols.filter { it.category == BreathProtocolCategory.PRESENCE }) {
                                            DropdownMenuItem(text = { Text(localizedBreathTitle(p.id)) }, onClick = { pick(BreathPace.Catalog(p.id)) })
                                        }
                                        if (lockedBpm != null) {
                                            DropdownMenuItem(text = { Text(resonanceTitle) }, onClick = { pick(BreathPace.Resonance) })
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.mind_about_pace)) },
                                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                            onClick = { paceMenu = false; showEdu = true },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.mind_duration),
                            onClick = { lengthMenu = true },
                            trailing = {
                                Box {
                                    MenuValue(stringResource(length.labelRes))
                                    DropdownMenu(expanded = lengthMenu, onDismissRequest = { lengthMenu = false }) {
                                        for (l in BreathLength.entries) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(l.labelRes)) },
                                                onClick = { lengthMenu = false; length = l },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    item { shape ->
                        SwitchRow(
                            shape = shape,
                            title = stringResource(R.string.mind_audio_cues),
                            checked = audioCues,
                            onCheckedChange = {
                                audioCues = it
                                NoopPrefs.of(context).edit().putBoolean(KEY_BREATHE_AUDIO_CUES, it).apply()
                            },
                        )
                    }
                }
            }
        }
    }

    BreathSessionHost(hub, vm)

    if (showEdu) PaceEduDialog(pace) { showEdu = false }

    if (askSweep) {
        ModalBottomSheet(
            onDismissRequest = { askSweep = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SheetBackdropEffect(MaterialTheme.colorScheme.surface)
            Column(Modifier.padding(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp)) {
                Text(
                    resonanceTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
                )
                ListGroup {
                    item { shape ->
                        ListRow(shape = shape, title = stringResource(R.string.mind_sweep_quick), onClick = {
                            askSweep = false
                            hub.startSweep(resonanceTitle, quick = true, reduceMotion = reduceMotion)
                        })
                    }
                    item { shape ->
                        ListRow(shape = shape, title = stringResource(R.string.mind_sweep_full), onClick = {
                            askSweep = false
                            hub.startSweep(resonanceTitle, quick = false, reduceMotion = reduceMotion)
                        })
                    }
                    lockedBpm?.let { locked ->
                        item { shape ->
                            ListRow(
                                shape = shape,
                                title = stringResource(R.string.mind_breathe_at, breathPaceText(locked, locale)),
                                onClick = {
                                    askSweep = false
                                    pace = BreathPace.Resonance
                                    startPaced(BreathPace.Resonance)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // The passive stress check-in: one prominent action and one plain one. "Breathe now" is one minute at
    // the locked resonance pace (or 5.5, coherence). Turning check-ins off lives in Settings.
    val nudge by StressNudgeCenter.pending.collectAsStateWithLifecycle()
    if (nudge != null) {
        ModalBottomSheet(
            onDismissRequest = { StressNudgeCenter.dismiss() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SheetBackdropEffect(MaterialTheme.colorScheme.surface)
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Air, contentDescription = null, tint = Health.colors.respiratory, modifier = Modifier.size(32.dp))
                Text(
                    stringResource(R.string.l10n_breathe_screen_your_hrv_dipped_while_you_were_231d3c7a),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp, bottom = 24.dp),
                )
                Button(
                    onClick = {
                        StressNudgeCenter.dismiss()
                        val pulse = lockedBpm ?: ResonanceEngine.FALLBACK_BPM
                        hub.startResonanceCue(resonanceTitle, pulse, maxOf(1, pulse.roundToInt()), reduceMotion)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Health.colors.respiratory,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                ) { Text(stringResource(R.string.l10n_breathe_screen_breathe_now_98d6c341), style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { StressNudgeCenter.dismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(stringResource(R.string.l10n_breathe_screen_not_now_e4571490))
                }
            }
        }
    }
}

/** One Mindfulness card: the mode's glyph, its name and a line under it, and the ▶ that starts it. */
@Composable
private fun ModeCard(
    title: String,
    detail: String,
    tint: Color,
    enabled: Boolean,
    glyph: @Composable () -> Unit,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(M3Dimens.cardRadius)
    val base = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
    Row(
        modifier = (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).alpha(if (enabled) 1f else 0.5f), contentAlignment = Alignment.Center) { glyph() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

/** A session put away with ⌄: its name and running clock, tap to bring it back. */
@Composable
private fun RunningRow(hub: BreathHub) {
    val label = stringResource(R.string.mind_return_to_session)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(M3Dimens.cardRadius))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button) { hub.presented = true }
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BreathFlower(
            1f, Health.colors.respiratory,
            additive = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
            modifier = Modifier.size(28.dp),
        )
        Text(
            hub.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            breathClock(hub.seconds),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
        ChevronRight()
    }
}

/** A row's current choice, with the up/down mark of a menu. */
@Composable
private fun MenuValue(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.size(4.dp))
        Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** "About this pace": the protocol's name, what it is for, how to do it, and any caution. */
@Composable
private fun PaceEduDialog(pace: BreathPace, onDismiss: () -> Unit) {
    val proto = (pace as? BreathPace.Catalog)?.let { BreathProtocolCatalog.protocolById(it.id) }
    val copy = proto?.let { breathProtocolCopyIds(it.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mind_about_pace)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (proto != null && copy != null) {
                    Text(stringResource(copy.title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(copy.subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (proto.category == BreathProtocolCategory.PRESENCE) {
                        Text(stringResource(breathPresenceIntroTitleRes()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(breathPresenceIntroBodyRes()), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(copy.edu), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    copy.sessionHint?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    copy.caution?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Health.colors.warning)
                    }
                } else {
                    Text(stringResource(R.string.l10n_breathe_screen_resonance_edu_s9t0u1v2), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mind_done)) } },
    )
}
