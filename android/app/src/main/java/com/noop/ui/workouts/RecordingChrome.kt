package com.noop.ui.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.R
import com.noop.ui.m3.Health

// MARK: - Recording chrome (twin of iOS RecordingChrome.swift)
//
// The pieces every "something is running" screen shares, so a workout and the interval timer look like one
// app: the top bar's ⌄, the large live figures, the page dots, and the panel at the bottom — the activity
// glyph, the running clock in the fitness green, then three round buttons with the main one in the middle.
// Drawn inside `AlwaysDark`, so every colour below is the dark scheme's.

/** The whole recording screen: a top bar, the [pages] area, then the bottom [panel]. */
@Composable
internal fun RecordingScaffold(
    title: String?,
    onMinimize: () -> Unit,
    panel: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            // The screen is drawn over the app: a touch on its empty parts must not reach what is underneath.
            .pointerInput(Unit) { detectTapGestures() }
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        RecordingTopBar(title = title, onMinimize = onMinimize, modifier = Modifier.statusBarsPadding())
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        // The panel reaches the bottom edge; its content keeps clear of the navigation bar.
        panel()
    }
}

/** ⌄ to put the screen away while what it records keeps running, and the activity's name. */
@Composable
internal fun RecordingTopBar(title: String?, onMinimize: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = onMinimize,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.workouts_minimize))
        }
        Text(
            title.orEmpty(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(48.dp))
    }
}

/**
 * One live figure: its small-caps label over a large numeral, with an optional smaller unit after it
 * ("4,29 km"). The label may carry iOS's two-line break; here it is one line above the figure.
 */
@Composable
internal fun LiveFigure(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val caption = label.replace('\n', ' ').uppercase(locale)
    Column(modifier.semantics(mergeDescendants = true) {}) {
        if (caption.isNotBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = tint,
                    maxLines = 1,
                )
                if (!unit.isNullOrEmpty()) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** Fitness's page dots under the figures. */
@Composable
internal fun PageDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
            )
        }
    }
}

/**
 * The recording panel, flush with the bottom edge like a sheet: the activity [glyph] on the green disc, the
 * [clock], an optional [trailing] accessory, then [leading] · [center] · [right] buttons.
 */
@Composable
internal fun RecordingPanel(
    glyph: ImageVector,
    clock: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    center: @Composable () -> Unit,
    right: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val c = Health.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.fitnessContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(glyph, contentDescription = null, tint = c.fitness, modifier = Modifier.size(24.dp))
            }
            Box(Modifier.weight(1f)) { clock() }
            trailing()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            leading()
            center()
            right()
        }
    }
}

/** How a panel button is drawn: the red Finish, the big green main action, or a neutral disc. */
internal enum class RecordingButtonKind { Destructive, Prominent, Neutral }

/** A round control of the panel. The [Prominent] one is the large rounded square in the middle. */
@Composable
internal fun RecordingButton(
    icon: ImageVector,
    label: String,
    kind: RecordingButtonKind,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Health.colors
    val cs = MaterialTheme.colorScheme
    val (size: Dp, shape: Shape, glyph: Dp) = when (kind) {
        RecordingButtonKind.Prominent -> Triple(112.dp, RoundedCornerShape(36.dp), 48.dp)
        else -> Triple(64.dp, CircleShape, 28.dp)
    }
    val (container, content) = when (kind) {
        RecordingButtonKind.Destructive -> cs.errorContainer to cs.onErrorContainer
        // A near-black glyph on the green, as Fitness draws its main control.
        RecordingButtonKind.Prominent -> c.fitness to cs.surfaceContainerLowest
        RecordingButtonKind.Neutral -> cs.surfaceContainerHighest to cs.onSurface
    }
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        shape = shape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = content.copy(alpha = 0.38f),
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(glyph))
    }
}

/**
 * The panel's clock face: large digits in the fitness green (or [tint]). Paused, it turns the paused yellow
 * and says so under the digits, so the state is not left to the centre glyph alone.
 */
@Composable
internal fun RecordingClock(text: String, paused: Boolean, spoken: String, tint: Color = Health.colors.fitness) {
    val color = if (paused) Health.colors.paused else tint
    val pausedWord = stringResource(R.string.workout_action_paused)
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = if (paused) "$spoken, $pausedWord" else spoken
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (paused) {
            Text(
                pausedWord.uppercase(locale),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = color,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
