package com.noop.ui.m3

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// MARK: - Glass: a surface that blurs what scrolls behind it
//
// Compose 1.6 has no backdrop blur, so this is drawn by hand. The content that lies behind (a
// [glassSource]) records itself into a RenderNode and draws that node; each [glass] surface draws the
// same node a second time, shifted to its own place, through a blur RenderEffect and clipped to its
// shape, then lays a tint, a highlight along its upper edge and a hairline rim over it. RenderEffect
// needs Android 12; below it, and on a software canvas, the surface is the tint alone at a higher
// opacity, so text never shows through sharp.
//
// One limit to know: a blurred copy does not learn by itself that the content moved. HWUI clears a
// node's dirty state the first time it meets the node in a frame, which is the sharp copy, so the
// blurred one would keep its last picture. A surface therefore re-records whenever
// [GlassBackdrop.observe] reads a state that changed (the screen points it at the list's scroll
// position) or the source redraws.

/** What a [glassSource] recorded, shared with the [glass] surfaces drawn over it. */
@Stable
class GlassBackdrop internal constructor() {
    /** False below Android 12: the surfaces are then tinted, not blurred. */
    val blurs: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    internal val content: Any? = if (blurs) GlassApi31.newNode("glassContent") else null
    internal var origin by mutableStateOf(Offset.Zero)
    internal var redraws by mutableIntStateOf(0)

    /**
     * Read inside every surface's draw: the states it reads redraw the surfaces when they change. The
     * screen sets it to whatever moves the content without redrawing the source, which for a lazy
     * list is its scroll position (items are moved as layers, not redrawn).
     */
    var observe: () -> Unit = {}
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

/** Marks the content that [glass] surfaces blur. It should cover the area the surfaces sit over. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier {
    if (!backdrop.blurs) return this
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                drawContent()
            } else {
                GlassApi31.recordAndDraw(this, backdrop.content)
                // The surfaces must record again after the source did. A write during draw reaches
                // them on the next frame, which a blur does not show. Unobserved, or the source would
                // be redrawn by its own write.
                Snapshot.withoutReadObservation { backdrop.redraws++ }
            }
        }
}

/**
 * A glass surface in [shape] over [backdrop]'s content. [tint] is the surface colour laid over the
 * blur; [tintAlpha] how much of the blur it covers.
 */
fun Modifier.glass(
    backdrop: GlassBackdrop,
    shape: Shape,
    tint: Color? = null,
    tintAlpha: Float = 0.48f,
    blurRadius: Dp = 22.dp,
): Modifier = composed {
    val scheme = MaterialTheme.colorScheme
    val base = tint ?: scheme.surfaceContainerHigh
    // The highlight is the scheme's own light: white-ish on a dark surface, and on a light one too,
    // where it reads as the bright edge of the pane.
    val light = if (scheme.surface.luminance() < 0.5f) scheme.onSurface else scheme.surfaceContainerLowest
    val rim = scheme.outlineVariant
    var origin by remember { mutableStateOf(Offset.Zero) }
    val node = remember { if (backdrop.blurs) GlassApi31.newNode("glassSurface") else null }

    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val radiusPx = blurRadius.toPx()
            val sheen = Brush.verticalGradient(
                0f to light.copy(alpha = 0.10f),
                0.45f to light.copy(alpha = 0.02f),
                1f to Color.Transparent,
            )
            val edge = Brush.verticalGradient(listOf(light.copy(alpha = 0.34f), rim.copy(alpha = 0.30f)))
            onDrawWithContent {
                backdrop.observe()
                backdrop.redraws
                val blurred = backdrop.blurs && drawContext.canvas.nativeCanvas.isHardwareAccelerated
                clipPath(path) {
                    if (blurred) {
                        GlassApi31.drawBlurred(this, node, backdrop.content, origin - backdrop.origin, radiusPx)
                    }
                    drawRect(base.copy(alpha = if (blurred) tintAlpha else 0.94f))
                    drawRect(sheen)
                }
                drawOutline(outline, brush = edge, style = Stroke(width = 1.dp.toPx()))
                drawContent()
            }
        }
}

/** The RenderNode and RenderEffect calls, kept in one class so older devices never load it. */
@RequiresApi(Build.VERSION_CODES.S)
private object GlassApi31 {
    fun newNode(name: String): Any = RenderNode(name)

    /** Records the scope's content into [target] and draws it, so later draws can reuse the picture. */
    fun recordAndDraw(scope: ContentDrawScope, target: Any?) {
        val node = target as RenderNode
        val width = scope.size.width.toInt()
        val height = scope.size.height.toInt()
        if (width <= 0 || height <= 0) {
            scope.drawContent()
            return
        }
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        try {
            scope.draw(scope, scope.layoutDirection, Canvas(recording), scope.size) { scope.drawContent() }
        } finally {
            node.endRecording()
        }
        scope.drawContext.canvas.nativeCanvas.drawRenderNode(node)
    }

    /**
     * Draws [source] blurred into the scope, [offset] being where the scope's origin sits inside the
     * source. The blurred node reaches one blur radius past the scope on every side, so the edge of
     * the surface blurs real content instead of a clamped border.
     */
    fun drawBlurred(scope: DrawScope, target: Any?, source: Any?, offset: Offset, radiusPx: Float) {
        val node = target as RenderNode
        val content = source as RenderNode
        if (!content.hasDisplayList()) return
        val pad = radiusPx.toInt()
        val width = scope.size.width.toInt()
        val height = scope.size.height.toInt()
        if (width <= 0 || height <= 0) return
        node.setPosition(-pad, -pad, width + pad, height + pad)
        node.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
        val recording = node.beginRecording(width + 2 * pad, height + 2 * pad)
        try {
            recording.translate(pad - offset.x, pad - offset.y)
            recording.drawRenderNode(content)
        } finally {
            node.endRecording()
        }
        scope.drawContext.canvas.nativeCanvas.drawRenderNode(node)
    }
}
