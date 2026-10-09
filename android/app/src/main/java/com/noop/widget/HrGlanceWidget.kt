package com.noop.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.noop.R
import com.noop.ui.AppLink
import com.noop.ui.uiString
import java.text.DateFormat
import java.util.Date

/**
 * Home-screen widget: the heart rate with the last [HrTrace.WINDOW_SEC] drawn as a trace (#1957).
 *
 * Renders purely from the [WidgetSnapshotStore] snapshot, like its siblings — no BLE, no DB — so it
 * costs nothing and survives process death. Tapping it opens the Heart Rate page.
 *
 * The trace is an IMAGE because Glance compiles to RemoteViews, which cannot draw. [HrTrace] decides
 * where the ink goes and [HrTraceRenderer] puts it on a Bitmap; the labels around it stay Glance `Text`
 * so they remain crisp, themed and readable to TalkBack.
 *
 * Honest-blank throughout: no reading means "—" and no chart, never a flat line at zero. A trace with a
 * single point draws a dot rather than nothing, because a widget placed this minute has exactly one.
 */
class HrGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { HrWidgetContent(currentWidgetSnapshot()) }
    }

    /** Same defence as [NoopGlanceWidget.onCompositionError]: swap Glance's built-in error layout for
     *  ours. The widget heals on the next successful push. */
    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        runCatching {
            val rv = android.widget.RemoteViews(context.packageName, R.layout.noop_widget_error)
            android.appwidget.AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, rv)
        }
    }
}

/** Card padding, one side. The chart and the axis under it must subtract the SAME figure or the
 *  labels drift out of line with the trace they annotate. */
private const val HR_CARD_PADDING_DP = 16f

/** The bpm scale column plus its gap. Same reasoning: one number, read by both. Two copies of a layout
 *  constant is how a chart and its axis end up a few pixels out of step. Wide enough for three digits at
 *  the 12 sp the scale is now set in. */
private const val HR_SCALE_WIDTH_DP = 30f
private const val HR_SCALE_COLUMN_DP = HR_SCALE_WIDTH_DP + 6f

/**
 * The height the trace BITMAP is drawn at.
 *
 * The chart box takes the card's leftover height by weight, so its real height is not knowable here —
 * the same situation as the width. This is the figure the bitmap is drawn at and the `Image` scales
 * from, chosen generously so the common case downscales: a 4x2 cell left roughly 36dp unspent when the
 * chart was pinned at 56, and that slack is what the graph looked small for.
 */
private const val HR_CHART_TARGET_DP = 92f

/** The chart width for a given widget width — the one place that arithmetic happens. */
private fun hrChartWidthDp(widthDp: Float): Float =
    (widthDp - 2 * HR_CARD_PADDING_DP - HR_SCALE_COLUMN_DP).coerceAtLeast(24f)

@Composable
private fun HrWidgetContent(snap: WidgetSnapshot) {
    val size = LocalSize.current
    val stats = HrTrace.stats(snap.hrSeries)

    WidgetCard(link = AppLink.HeartRate, padding = HR_CARD_PADDING_DP.dp) {
        // Built OUT here, not inside the semantics lambda: #571 recorded that the i18n audit cannot see
        // copy assigned inside one, so a literal written there ships English to every locale.
        val hrLabel = uiString(R.string.l10n_noop_glance_widget_heart_rate_410aa15c)
        val updatedTime = snap.updatedAtMs.takeIf { it > 0 }
            ?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
        val updatedSpoken = updatedTime?.let { uiString(R.string.l10n_hr_glance_widget_updated_time_1b5feedb, it) }

        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            // The notification heart, reused. It is authored as a solid alpha mask for exactly this kind
            // of single-colour tinting, so it takes the hue cleanly.
            Image(
                provider = ImageProvider(R.drawable.ic_stat_heart),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(WidgetColors.heart),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(text = hrLabel, style = WidgetType.titleStyle, maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            // When the reading is from, in the title row: it used to be a fourth line under the chart, and
            // a line is what the chart has least of.
            if (updatedTime != null && updatedSpoken != null) {
                Text(
                    text = updatedTime,
                    style = WidgetType.captionStyle,
                    maxLines = 1,
                    modifier = GlanceModifier.semantics { contentDescription = updatedSpoken },
                )
            }
        }
        Spacer(GlanceModifier.height(4.dp))

        // Staleness is drawn ONLY by dimming the number, which is a colour-only channel — so TalkBack,
        // and anyone who cannot perceive the dim, was told a carried-over reading was current. Marked on
        // the LIVE side, exactly as the sibling widget settled it (#1799): a stale value then carries no
        // claim rather than a contradicted one.
        val liveSuffix =
            if (snap.heartRateStale) "" else " " + uiString(R.string.l10n_today_screen_sync_chip_live_98aadb37)
        val hrSpoken = snap.heartRate
            ?.let {
                "$hrLabel " + uiString(R.string.l10n_today_screen_value_bpm_complete_ab58df4e, it.toString(), liveSuffix)
            }
            ?: WidgetCaptions.spoken(hrLabel, null, uiString(R.string.widget_no_data))

        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(
                text = WidgetCaptions.heartRate(snap.heartRate),
                style = WidgetType.figure(
                    32.sp,
                    if (snap.heartRateStale || snap.heartRate == null) WidgetColors.onSurfaceVariant
                    else WidgetColors.onSurface,
                ),
                maxLines = 1,
                modifier = GlanceModifier.semantics { contentDescription = hrSpoken },
            )
            if (snap.heartRate != null) {
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    // The existing unit resource, not a literal.
                    text = uiString(R.string.today_unit_bpm),
                    style = WidgetType.captionStyle,
                    modifier = GlanceModifier.padding(bottom = 5.dp),
                )
            }
            if (stats != null) {
                Spacer(GlanceModifier.width(10.dp))
                // A chip, not loose text: it is a summary OF the chart, and the tonal rounded ground is
                // what separates it from the unit label sitting next to it.
                Text(
                    text = uiString(R.string.l10n_hr_glance_widget_min_lo_max_hi_ef900e49, stats.min, stats.max),
                    style = TextStyle(color = WidgetColors.onChip, fontSize = WidgetType.caption),
                    maxLines = 1,
                    modifier = GlanceModifier
                        .padding(bottom = 4.dp)
                        .background(WidgetColors.chip)
                        .cornerRadius(10.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        // No series, no chart row at all. On upgrade every existing install has a heart rate in prefs
        // but no trace yet, and reserving the height for it would show a blank rectangle for the first
        // few minutes — a void reads as broken where a shorter widget reads as new.
        if (snap.hrSeries.isNotEmpty()) {
            Spacer(GlanceModifier.height(6.dp))
            HrTraceImage(snap, widthDp = size.width.value, stats = stats,
                         modifier = GlanceModifier.defaultWeight())
            HrTimeAxis(snap, hasScale = stats != null && stats.max > stats.min)
        }
    }
}

/**
 * The trace, plus the bpm scale down its right edge.
 *
 * Bitmap construction is wrapped: an OOM or a hostile size must leave the widget without a chart, not
 * without a widget. `size.width` is the WIDGET's width, so the chart is sized from what the launcher
 * actually gave us rather than from a guess.
 */
@Composable
private fun HrTraceImage(
    snap: WidgetSnapshot,
    widthDp: Float,
    // Passed in rather than recomputed: the caller already scanned the series for it, and a second scan
    // per render also meant two places deciding what the scale describes.
    stats: HrTrace.Stats?,
    // Weighted by the CALLER: Glance scopes defaultWeight() to Row/ColumnScope, so a composable
    // cannot claim its own share of the parent from in here.
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    // Identifies THIS placed widget, so the redundant-draw memo below cannot confuse two of them.
    val glanceInstance = LocalGlanceId.current.toString()
    // Leave room for the scale column so the trace is not drawn under its own labels.
    val chartWidthDp = hrChartWidthDp(widthDp)
    // ONE box for both the geometry and the bitmap. Sizing them separately let the trace be drawn to
    // coordinates the bitmap did not have room for, clipping its right-hand end (#1957).
    // Height exactly as displayed, width with headroom so the Image DOWNSCALES rather than stretching
    // up: LocalSize under-reports on some launchers, and an upscale here is horizontal-only, which
    // turns the stroke elliptical (#1957).
    val hPx = (HR_CHART_TARGET_DP * density).toInt().coerceAtLeast(1)
    val wPx = HrTrace.widestAtHeight((chartWidthDp * density).toInt(), hPx)
    val points = HrTrace.points(snap.hrSeries, wPx.toFloat(), hPx.toFloat())

    // A push carrying no live sample appends no point, so this draw reproduced the previous bitmap
    // exactly. Counting them sizes the saving a future cache would take; nothing is skipped here.
    // Keyed by the PLACED WIDGET, not globally: two HR widgets would otherwise answer for each
    // other, and two of the same size would make each one's necessary draw look like a repeat.
    if (HrTraceSeen.repeat(glanceInstance, snap.hrSeries, wPx, hPx, WidgetTheme.isDark(context))) {
        WidgetTelemetry.noteRedundantRender()
    }

    // The chart takes the ROW's remaining width by weight rather than a width computed from
    // LocalSize. On a One UI launcher LocalSize reported a size smaller than the card actually
    // occupied, so the chart and its scale sat in the left half with dead space beside them. The
    // bitmap is still sized in pixels, but only to be drawn and then stretched — a smooth line
    // survives that, and the layout is now the launcher's business rather than my arithmetic.
    Row(modifier = modifier.fillMaxWidth()) {
        Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight()) {
            WidgetChartImage(GlanceModifier.fillMaxSize()) { dark ->
                renderHrTrace(context, points, wPx, hPx, density, dark)
            }
        }
        // A scale of one repeated number says nothing the headline has not: with no range there is
        // nothing to scale against, and 78/78/78 beside a flat line is three labels of noise.
        if (stats != null && stats.max > stats.min) {
            Spacer(GlanceModifier.width(6.dp))
            // Spread across the chart's height so max sits level with the top of the trace and min with
            // the bottom, which is what makes it a SCALE. Stacked from the top with fixed gaps they were
            // just three numbers near the chart, aligned to nothing.
            Column(
                modifier = GlanceModifier.width(HR_SCALE_WIDTH_DP.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.Horizontal.End,
            ) {
                val ticks = HrTrace.bpmTicks(stats)
                // Spoken WITH the unit. The bare number is exactly as useful as none: TalkBack announced
                // "84, 72, 59" against a chart it cannot see. Glance has no way to mark a Text
                // decorative, so the next best thing is to make each one say what it measures.
                val spoken = ticks.map { uiString(R.string.l10n_today_screen_value_bpm_8f3a90c3, it.toString()) }
                ticks.forEachIndexed { i, tick ->
                    Text(
                        text = tick.toString(),
                        style = WidgetType.captionStyle,
                        maxLines = 1,
                        modifier = GlanceModifier.semantics { contentDescription = spoken[i] },
                    )
                    if (i < ticks.size - 1) Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/**
 * One drawing of the trace, for the light or the dark card: the heart hue over that card's own surface
 * colour, which the bitmap needs as its ground because it has no alpha to show the card through.
 *
 * Measured, because "the widget drains the battery" was not decidable from an export: this is the only
 * widget family that ships a BITMAP rather than a few KB of text, and nothing counted what that cost. Each
 * drawing is counted, so the Android 12+ pair shows as the two renders it is.
 */
private fun renderHrTrace(
    context: Context,
    points: List<HrTrace.Pt>,
    wPx: Int,
    hPx: Int,
    density: Float,
    dark: Boolean,
): Bitmap? {
    val line = Color(WidgetTheme.color(context, R.color.widget_heart, dark))
    val ground = WidgetTheme.color(context, R.color.widget_surface, dark)
    val startedNs = System.nanoTime()
    val bmp = runCatching {
        HrTraceRenderer.render(
            points = points,
            widthPx = wPx,
            heightPx = hPx,
            lineColor = line.toArgb(),
            fillTopColor = line.copy(alpha = 0.35f).toArgb(),
            backgroundColor = ground,
            strokePx = 2f * density,
        )
    }.getOrNull()
    if (bmp != null) {
        WidgetTelemetry.noteRender(
            bytes = wPx * hPx * HrTrace.BYTES_PER_PIXEL,
            elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
        )
    }
    return bmp
}

/**
 * The time labels under the trace: first, middle and last of the DATA (see [HrTrace.timeTicks]).
 *
 * Spread with weighted spacers rather than fixed gaps, so the middle label sits over the middle of the
 * chart whatever width the launcher gave the widget. Formatted through the locale's short time format,
 * so a 12-hour device reads as one — a widget is not the place to impose a clock convention.
 *
 * Degrades with the ticks: under a minute of history there is one instant to name, and naming it three
 * times would suggest a span that was never sampled.
 */
@Composable
private fun HrTimeAxis(snap: WidgetSnapshot, hasScale: Boolean) {
    val ticks = HrTrace.timeTicks(snap.hrSeries)
    if (ticks.isEmpty()) return
    // Under a minute of history names one instant, and one label pinned to the left edge reads as a
    // stray rather than an axis — so the axis only appears once there is a span to label.
    if (ticks.size < 2) return
    val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    Spacer(GlanceModifier.height(2.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        ticks.forEachIndexed { i, ts ->
            Text(text = fmt.format(Date(ts * 1000)), style = WidgetType.captionStyle, maxLines = 1)
            if (i < ticks.size - 1) Spacer(GlanceModifier.defaultWeight())
        }
        if (hasScale) Spacer(GlanceModifier.width(HR_SCALE_COLUMN_DP.dp))
    }
}
