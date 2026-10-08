package com.noop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.m3.Health
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

// MARK: - The Coach chat's own look
//
// The pieces that give the conversation its character and carry no behaviour: the Coach's mark (an
// eight-lobed burst), the avatar built on it, the line it shows while a reply is being thought out, and
// the colour wash the transcript scrolls over, which is what the glass bars have to blur.

/**
 * The Coach's mark: a soft eight-lobed burst. [alive] turns it slowly while its lobes breathe in and
 * out, which is how the Coach shows it is working; still, and under Remove animations or battery saver
 * (#909), it is drawn at rest.
 */
@Composable
internal fun CoachSpark(size: Dp, color: Color, modifier: Modifier = Modifier, alive: Boolean = false) {
    val transition = if (alive && !rememberPoseStill()) rememberInfiniteTransition(label = "spark") else null
    val turn = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "turn",
    )
    val breath = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        val depth = 0.20f + 0.22f * (breath?.value ?: 0.55f)
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val radius = min(cx, cy) / (1f + depth)
        val steps = SPARK_LOBES * 16
        path.rewind()
        for (i in 0..steps) {
            val angle = 2.0 * PI * i / steps
            val r = radius * (1f + depth * cos(SPARK_LOBES * angle)).toFloat()
            val x = cx + r * sin(angle).toFloat()
            val y = cy - r * cos(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        rotate(turn?.value ?: 0f) { drawPath(path, color) }
    }
}

private const val SPARK_LOBES = 8

/** The Coach's picture: its mark over the three ring hues (Rest, Effort, Charge) swept round a circle. */
@Composable
internal fun CoachAvatar(size: Dp = 40.dp, alive: Boolean = false) {
    val hues = Health.colors
    val scheme = MaterialTheme.colorScheme
    // The lighter of the two surface tones, so the mark is light over the hues in either theme.
    val mark = if (scheme.surface.luminance() > scheme.onSurface.luminance()) scheme.surface else scheme.onSurface
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.sweepGradient(listOf(hues.rest, hues.effort, hues.charge, hues.rest))),
        contentAlignment = Alignment.Center,
    ) {
        CoachSpark(size = size * 0.6f, color = mark, alive = alive)
    }
}

/**
 * What stands where the reply will be while none of it has arrived: the turning mark and a short verb
 * that changes every few seconds, a band of light passing over it. The verbs describe thinking and
 * nothing else; none of them claims a step (reading sleep, checking recovery) the app cannot show is
 * happening. Under Remove animations or battery saver it is the mark at rest and the first verb.
 */
@Composable
internal fun CoachThinking(modifier: Modifier = Modifier) {
    val phrases = listOf(
        stringResource(R.string.coach_phrase_thinking),
        stringResource(R.string.coach_phrase_pondering),
        stringResource(R.string.coach_phrase_mulling),
        stringResource(R.string.coach_phrase_connecting),
        stringResource(R.string.coach_phrase_weighing),
        stringResource(R.string.coach_phrase_wording),
    )
    val label = stringResource(R.string.coach_thinking)
    val still = rememberPoseStill()
    var index by remember { mutableIntStateOf(0) }
    if (!still) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(PHRASE_HOLD_MS)
                index = (index + 1) % phrases.size
            }
        }
    }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoachSpark(size = 18.dp, color = MaterialTheme.colorScheme.primary, alive = true)
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "phrase",
        ) { shown ->
            Text(
                phrases[shown],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (still) Modifier else Modifier.shimmer(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

private const val PHRASE_HOLD_MS = 2600L

/** A band of [highlight] that crosses the content left to right, colouring only what is drawn there. */
private fun Modifier.shimmer(highlight: Color): Modifier = composed {
    val sweep = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing)),
        label = "sweep",
    )
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = size.width * 0.5f
            val centre = sweep.value * size.width
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    startX = centre - band / 2f,
                    endX = centre + band / 2f,
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/**
 * The transcript's ground: the surface colour with two faint pools of the scheme's own colour, one high
 * on the leading side and one low on the trailing side. A flat ground gives glass nothing to show.
 */
@Composable
internal fun Modifier.coachWash(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val low = Health.colors.rest
    return drawBehind {
        drawRect(scheme.surface)
        val reach = size.maxDimension * 0.7f
        drawRect(
            Brush.radialGradient(
                colors = listOf(scheme.primary.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.08f, size.height * 0.10f),
                radius = reach,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(low.copy(alpha = 0.13f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.88f),
                radius = reach,
            ),
        )
    }
}

/**
 * Lets the transcript dissolve into the ground towards the screen's top and foot, over the height of
 * the bars floating there ([top], [bottom]) and a little past them. Without it, sharp lines of text
 * show in the gaps between the glass pieces and above them; with it, what reaches the glass is
 * already fading, and the glass blurs the rest.
 */
@Composable
internal fun Modifier.coachFade(top: Dp, bottom: Dp): Modifier {
    val ground = MaterialTheme.colorScheme.surface
    return drawWithContent {
        drawContent()
        val reach = 24.dp.toPx()
        val head = top.toPx() + reach
        drawRect(
            brush = Brush.verticalGradient(
                0f to ground.copy(alpha = 0.92f),
                0.55f to ground.copy(alpha = 0.55f),
                1f to ground.copy(alpha = 0f),
                startY = 0f,
                endY = head,
            ),
            size = Size(size.width, head),
        )
        val foot = bottom.toPx() + reach
        drawRect(
            brush = Brush.verticalGradient(
                0f to ground.copy(alpha = 0f),
                0.45f to ground.copy(alpha = 0.55f),
                1f to ground.copy(alpha = 0.92f),
                startY = size.height - foot,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - foot),
            size = Size(size.width, foot),
        )
    }
}
