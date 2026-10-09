package com.noop.ui.mind

import android.view.ViewGroup
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ui.AppViewModel
import com.noop.ui.localizedBreathStageLabel
import com.noop.ui.m3.DarkHealthColors
import com.noop.ui.m3.DarkTonalIcons
import com.noop.ui.m3.Health
import com.noop.ui.m3.LocalHealthColors
import com.noop.ui.m3.LocalTonalIcons
import com.noop.ui.reNoopColorScheme
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Session screen (twin of iOS BreathSessionView / BreathSummaryView / BreathFlower)
//
// The running session: always dark, the flower in the middle (it opens on the inhale and closes on the
// exhale), the phase word under it, heart rate and HRV small at the bottom; ⌄ puts it away, ✕ ends it. After
// the end, one summary card. Full screen means full screen: the status bar hides while it is up.

/** Presents the session full screen whenever the hub asks for it. Back puts a running session away. */
@Composable
internal fun BreathSessionHost(hub: BreathHub, vm: AppViewModel) {
    if (!hub.presented) return
    val summary = hub.summary
    Dialog(
        onDismissRequest = { if (summary != null) hub.closeSummary() else hub.presented = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            if (window != null) {
                // Compose caps a dialog at the screen minus the system bars (ui 1.6), so the strips it cannot
                // reach are dimmed to full black: with the black canvas the session reads as one dark screen.
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.setDimAmount(1f)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.statusBars())
                }
            }
            // The hands are still and the eyes may be closed: hold the screen awake through the session.
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        DarkSessionTheme {
            Box(
                // Black, as the dimmed strips around the dialog are: the one place the scheme's own near-black
                // would show a seam.
                Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(),
            ) {
                if (summary != null) {
                    BreathSummaryView(summary, onDone = { hub.closeSummary() })
                } else {
                    BreathRunningView(hub, vm)
                }
            }
        }
    }
}

/** The session is dark whatever the app's appearance: the dark scheme and the dark data hues. */
@Composable
private fun DarkSessionTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context) { reNoopColorScheme(context, dark = true) }
    CompositionLocalProvider(
        LocalHealthColors provides DarkHealthColors,
        LocalTonalIcons provides DarkTonalIcons,
    ) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
    }
}

@Composable
private fun BreathRunningView(hub: BreathHub, vm: AppViewModel) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val progress by animateFloatAsState(
        targetValue = hub.flower,
        animationSpec = tween(durationMillis = hub.flowerMs, easing = FastOutSlowInEasing),
        label = "flower",
    )
    val light by animateFloatAsState(targetValue = hub.flowerAlpha, animationSpec = tween(200), label = "flower-light")
    val word = when {
        hub.kind == BreathKind.Calm -> stringResource(R.string.mind_follow_wrist)
        hub.kind == null -> ""
        !hub.phaseLabel.isNullOrEmpty() -> localizedBreathStageLabel(hub.phaseLabel.orEmpty())
        else -> stringResource(breathPhaseRes(hub.phase))
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilledTonalIconButton(onClick = { hub.presented = false }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.mind_minimize))
            }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onClick = { hub.end() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mind_end))
            }
        }
        Spacer(Modifier.weight(1f))
        BreathFlower(
            progress = progress,
            tint = Health.colors.respiratory,
            additive = true,
            modifier = Modifier.size(280.dp).alpha(light),
        )
        Text(
            word,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 36.dp, start = 24.dp, end = 24.dp)
                .heightIn(min = 80.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (hub.kind == BreathKind.Sweep) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    hub.sweepBpm?.let { stringResource(R.string.mind_testing_pace, breathPaceText(it, locale)) }
                        ?: stringResource(R.string.mind_sweeping),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { hub.sweepProgress },
                    modifier = Modifier.width(180.dp),
                    color = Health.colors.mind,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        BreathReadouts(hub, vm)
    }
}

/** Heart rate and HRV in small type with the session clock. */
@Composable
private fun BreathReadouts(hub: BreathHub, vm: AppViewModel) {
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val figure = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    Column(
        modifier = Modifier.padding(bottom = 24.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.mind_heart_rate),
                    tint = Health.colors.heart,
                    modifier = Modifier.size(18.dp),
                )
                Text(bpm?.toString() ?: "--", style = figure, color = MaterialTheme.colorScheme.onSurface)
                val target = hub.calmTargetBpm
                if (hub.kind == BreathKind.Calm && target != null) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = quiet, modifier = Modifier.size(14.dp))
                    Text("${target.roundToInt()}", style = figure, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (hub.kind != BreathKind.Calm) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.mind_hrv), style = figure, color = quiet)
                    Text(hub.rmssd?.let { "${it.roundToInt()}" } ?: "--", style = figure, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.mind_ms), style = figure, color = quiet)
                }
            }
        }
        Text(
            hub.clockLine,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = quiet,
        )
    }
}

/** The one card a session ends on: how long, the heart rate before and after, and what the session found. */
@Composable
private fun BreathSummaryView(summary: BreathSummary, onDone: () -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val rows = ArrayList<Pair<String, String>>()
    rows += stringResource(R.string.mind_time) to breathClock(summary.seconds)
    val start = summary.hrStart
    val end = summary.hrEnd
    if (start != null && end != null) {
        rows += stringResource(R.string.mind_heart_rate) to "$start → $end ${stringResource(R.string.mind_bpm)}"
    }
    summary.sweep?.let { result ->
        rows += stringResource(R.string.mind_your_pace) to
            if (result.didLock) "${breathPaceText(result.lockedBpm, locale)} ${stringResource(R.string.mind_br_min)}"
            else stringResource(R.string.mind_no_pace_found)
    }
    summary.calm?.let { rows += stringResource(R.string.mind_calm) to calmOutcomeText(it) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        BreathFlower(progress = 1f, tint = Health.colors.respiratory, additive = true, modifier = Modifier.size(72.dp))
        Text(
            summary.title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Column(
            Modifier
                .padding(top = 28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp),
        ) {
            rows.forEachIndexed { i, (title, value) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 13.dp).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Health.colors.respiratory,
                contentColor = Color.Black,
            ),
        ) { Text(stringResource(R.string.mind_done), style = MaterialTheme.typography.titleMedium) }
    }
}

/** Calm's outcome in the reader's language. */
@Composable
private fun calmOutcomeText(outcome: CalmOutcome): String = when (outcome) {
    is CalmOutcome.Settled ->
        if (outcome.start != null && outcome.end != null) {
            stringResource(R.string.mind_calm_settled_range, outcome.start, outcome.end, breathClock(outcome.seconds))
        } else stringResource(R.string.mind_calm_settled, breathClock(outcome.seconds))
    is CalmOutcome.Eased -> stringResource(R.string.mind_calm_eased, outcome.start, outcome.end, breathClock(outcome.seconds))
    is CalmOutcome.Steady -> stringResource(R.string.mind_calm_steady, outcome.start, outcome.end)
    CalmOutcome.Ended -> stringResource(R.string.mind_calm_ended)
    CalmOutcome.CouldNotStart -> stringResource(R.string.mind_calm_could_not_start)
}

// MARK: - The flower

/**
 * The Mindfulness flower: six translucent petals around the centre. At 0 they fold into one small bud; at 1
 * they open into the full flower, turned a sixth. Drawn in one Canvas from an animated [progress], so a
 * breath is a redraw of six circles.
 *
 * [additive]: petals add up to light where they overlap on the dark session; on a light page that would wash
 * out to white, so there they simply layer.
 */
@Composable
internal fun BreathFlower(progress: Float, tint: Color, additive: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clearAndSetSemantics {},
    ) {
        val side = minOf(size.width, size.height)
        val petal = side * (0.28f + 0.22f * progress)
        // Never fully closed: at the end of an exhale the petals still show as a small bud.
        val reach = side * (0.05f + 0.2f * progress)
        val c = Offset(size.width / 2, size.height / 2)
        val petalCentre = Offset(c.x, c.y - reach)
        val brush = Brush.verticalGradient(
            colors = listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.55f)),
            startY = petalCentre.y - petal / 2,
            endY = petalCentre.y + petal / 2,
        )
        for (i in 0 until 6) {
            rotate(degrees = i * 60f + progress * 60f, pivot = c) {
                drawCircle(
                    brush = brush,
                    radius = petal / 2,
                    center = petalCentre,
                    alpha = if (additive) 0.42f else 0.4f,
                    blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver,
                )
            }
        }
    }
}
