package com.noop.widget

import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.noop.R
import com.noop.ui.AppLink
import com.noop.ui.AppLinks

// MARK: - The widgets' shared Material You chrome
//
// Every reNOOP widget is one card: the system's widget background and corner radius, the wallpaper's own
// tones for its surface and text on Android 12+ (the reNOOP mint seed scheme below that), type no smaller
// than 12 sp, and one tap target that opens the app where the widget points (AppLink). The colours are
// RESOURCES (values/colors.xml and its -night, -v31, -night-v31 twins), not values resolved in this
// process, so the launcher re-resolves them itself the moment the system theme or the wallpaper changes;
// a widget whose app is not running still turns dark with the home screen.

/** The widget colour roles, each a day/night resource the launcher resolves. */
internal object WidgetColors {
    val surface: ColorProvider = ColorProvider(R.color.widget_surface)
    val onSurface: ColorProvider = ColorProvider(R.color.widget_on_surface)
    val onSurfaceVariant: ColorProvider = ColorProvider(R.color.widget_on_surface_variant)
    val primary: ColorProvider = ColorProvider(R.color.widget_primary)
    val chip: ColorProvider = ColorProvider(R.color.widget_chip)
    val onChip: ColorProvider = ColorProvider(R.color.widget_on_chip)
    val charge: ColorProvider = ColorProvider(R.color.widget_charge)
    val effort: ColorProvider = ColorProvider(R.color.widget_effort)
    val rest: ColorProvider = ColorProvider(R.color.widget_rest)
    val heart: ColorProvider = ColorProvider(R.color.widget_heart)
}

/** The widget type scale. Nothing is set below 12 sp (audit CR-9). */
internal object WidgetType {
    val caption: TextUnit = 12.sp
    val label: TextUnit = 14.sp

    /** A caption: secondary text at the smallest size a widget sets. */
    val captionStyle: TextStyle
        get() = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = caption)

    /** A card title, as "Heart rate" over its figure. */
    val titleStyle: TextStyle
        get() = TextStyle(color = WidgetColors.onSurface, fontSize = label, fontWeight = FontWeight.Medium)

    /** A figure at [size], in the primary text colour (or [color] for a dimmed, carried-over reading). */
    fun figure(size: TextUnit, color: ColorProvider = WidgetColors.onSurface): TextStyle =
        TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold)
}

/**
 * The card every widget is drawn on: the system widget background and radius on Android 12+, a drawn
 * rounded card below it (a RemoteViews view cannot be clipped to an outline there), [padding] inside, and
 * the whole card one tap that opens the app at [link].
 */
@Composable
internal fun WidgetCard(
    link: AppLink,
    padding: Dp = 16.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val card = GlanceModifier.fillMaxSize().appWidgetBackground().let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            it.background(WidgetColors.surface).cornerRadius(android.R.dimen.system_app_widget_background_radius)
        } else {
            it.background(ImageProvider(R.drawable.widget_surface))
        }
    }
    Column(
        modifier = card.clickable(actionStartActivity(AppLinks.intent(context, link))).padding(padding),
        horizontalAlignment = horizontalAlignment,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/**
 * The snapshot as it stands NOW, re-read each time the store asks the widgets to redraw
 * ([WidgetSnapshotStore.revision]). Read inside the composition rather than captured in `provideGlance`,
 * because a live Glance session never runs `provideGlance` again and would draw a captured value forever.
 * A corrupt pref degrades to the empty-state widget rather than throwing mid-composition.
 */
@Composable
internal fun currentWidgetSnapshot(): WidgetSnapshot {
    val context = LocalContext.current
    val revision by WidgetSnapshotStore.revision.collectAsState()
    return remember(revision) {
        runCatching { WidgetSnapshotStore.load(context) }.getOrDefault(WidgetSnapshot())
    }
}

/** One of the three score rings, by the layout that draws it and the id of the bar inside it. */
internal enum class WidgetRing(@LayoutRes val layout: Int, @IdRes val bar: Int) {
    Charge(R.layout.widget_ring_charge, R.id.widget_ring_charge),
    Effort(R.layout.widget_ring_effort, R.id.widget_ring_effort),
    Rest(R.layout.widget_ring_rest, R.id.widget_ring_rest),
}

/**
 * A score ring with its figure inside: the arc is [pct] of the circle over a full track, and an unscored
 * ring is the bare track around a dash, never an arc at zero. One TalkBack element, spoken as [spoken].
 * [figure] is what is printed inside when that is not the whole percent (strain on the 0 to 21 scale).
 */
@Composable
internal fun WidgetScoreRing(
    ring: WidgetRing,
    pct: Int?,
    diameter: Dp,
    figureSize: TextUnit,
    spoken: String,
    figure: String = WidgetCaptions.score(pct),
) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, ring.layout).apply {
        setProgressBar(ring.bar, 100, WidgetCaptions.ringProgress(pct), false)
    }
    Box(
        modifier = GlanceModifier.size(diameter).semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        AndroidRemoteViews(remoteViews = views, modifier = GlanceModifier.fillMaxSize())
        Text(
            text = figure,
            style = WidgetType.figure(
                figureSize,
                if (pct == null) WidgetColors.onSurfaceVariant else WidgetColors.onSurface,
            ),
            maxLines = 1,
        )
    }
}

/**
 * A chart bitmap, stretched to its box. A chart is drawn in the app's process, on an opaque ground in the
 * card's colour, so it cannot re-colour itself as the card's resources do. On Android 12+ the widget
 * therefore carries BOTH drawings and the launcher shows the one for its own theme; below that the single
 * drawing is the one for the theme the widget was composed in, as every other colour there is.
 *
 * [draw] renders the chart for light (`false`) or dark (`true`), or returns null when it cannot.
 */
@Composable
internal fun WidgetChartImage(modifier: GlanceModifier, draw: (dark: Boolean) -> Bitmap?) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val light = draw(false)
        val dark = draw(true)
        if (light == null || dark == null) return
        val views = RemoteViews(context.packageName, R.layout.widget_chart)
        Api31.setDayNightBitmap(views, R.id.widget_chart, light, dark)
        AndroidRemoteViews(remoteViews = views, modifier = modifier)
    } else {
        val bitmap = draw(WidgetTheme.isDark(context)) ?: return
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = modifier,
            // FillBounds, not the default Fit: the bitmap is drawn wider than its box so it downscales,
            // and Fit would letterbox that headroom back into dead space.
            contentScale = ContentScale.FillBounds,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private object Api31 {
    fun setDayNightBitmap(views: RemoteViews, viewId: Int, light: Bitmap, dark: Bitmap) {
        views.setIcon(viewId, "setImageIcon", Icon.createWithBitmap(light), Icon.createWithBitmap(dark))
    }
}
