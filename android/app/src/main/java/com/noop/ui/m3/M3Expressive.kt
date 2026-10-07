package com.noop.ui.m3

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.noop.ui.rememberReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// MARK: - Material 3 Expressive pieces, drawn by hand
//
// The module builds against material3 1.2.1, which ships none of the Expressive components (they arrive with
// material3 1.4+, Kotlin 2 and a newer Compose). These are the pieces the redesigned screens need, drawn here
// from the same tokens: two shapes of the Expressive shape set (the scalloped "cookie" and the four-lobed
// "clover"), the thick linear indicator with its gap and stop, and a tonal badge for a one-word level.
// Motion is one spring settle, and under reduce-motion a piece is drawn at its final frame.

/**
 * A circle whose edge rises and falls [lobes] times, each lobe [depth] of the radius: twelve shallow lobes
 * read as the Expressive "cookie", four deep ones as its "clover". The shape fills its bounds' inscribed
 * circle, with a lobe's crest at 12 o'clock.
 */
class ScallopShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy) / (1f + depth)
        val steps = lobes * 24
        val path = Path()
        for (i in 0..steps) {
            val angle = 2.0 * PI * i / steps
            val r = radius * (1f + depth * cos(lobes * angle)).toFloat()
            val x = cx + r * sin(angle).toFloat()
            val y = cy - r * cos(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** The Expressive twelve-lobed cookie: the frame of a headline figure. */
val CookieShape: Shape = ScallopShape(lobes = 12, depth = 0.05f)

/** The Expressive four-lobed clover: a second icon frame, so two kinds of row are told apart by outline. */
val CloverShape: Shape = ScallopShape(lobes = 4, depth = 0.14f)

/**
 * The Expressive linear indicator: a thick rounded bar for the part reached, a gap, the rest of the track,
 * and a stop dot at the track's end. [fraction] is 0…1. It settles to its value on a spring when it first
 * appears and when the value changes; under reduce-motion it is simply drawn there. Decorative to TalkBack:
 * the figure it pictures stands beside it as text.
 */
@Composable
fun ExpressiveBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val target = fraction.coerceIn(0f, 1f)
    val reduce = rememberReduceMotion()
    val shown = remember { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(target, reduce) {
        if (reduce) shown.snapTo(target)
        else shown.animateTo(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics {}) {
        val h = size.height
        val round = CornerRadius(h / 2, h / 2)
        val gap = 4.dp.toPx()
        val f = shown.value.coerceIn(0f, 1f)
        val active = if (f <= 0f) 0f else max(h, size.width * f)
        if (active > 0f) drawRoundRect(color, Offset.Zero, Size(active, h), round)
        val trackStart = if (active > 0f) active + gap else 0f
        val trackW = size.width - trackStart
        if (trackW >= h) {
            drawRoundRect(track, Offset(trackStart, 0f), Size(trackW, h), round)
            drawCircle(color, radius = h * 0.2f, center = Offset(size.width - h / 2, h / 2))
        }
    }
}

/**
 * A one-word level ("Optimal", "Typical") as a filled badge: the word in [ink] on a pill of [fill]. The
 * default ink is the page colour, which a data hue is pinned to read against at 4.5:1 (see
 * HealthColorsContrastTest); a pale wash of the hue under the hue itself would fall short of that.
 */
@Composable
fun LevelBadge(
    text: String,
    fill: Color,
    modifier: Modifier = Modifier,
    ink: Color = MaterialTheme.colorScheme.surface,
) {
    Box(modifier.clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1)
    }
}
