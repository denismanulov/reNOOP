package com.noop.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.noop.R
import com.noop.analytics.ClockFormat
import com.noop.ui.AppLink
import com.noop.ui.ClockPrefs
import com.noop.ui.EffortScale
import com.noop.ui.UnitPrefs
import com.noop.ui.uiString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget: today's three scores as rings (Charge · Effort · Rest, the Summary's own order and
 * hues), with the heart rate and the strap battery under them (#516). Twin of the iOS `NOOPWidget`.
 *
 * Renders purely from the [WidgetSnapshotStore] SharedPreferences snapshot — no BLE, no DB — so it costs
 * nothing and survives process death. Tapping it opens the Summary. Each ring is honest-null (a bare track
 * around a dash) until NOOP has scored it; it never fabricates a number.
 *
 * Material You: the card, its text and the rings' tracks are the system's own tones ([WidgetColors]); only
 * the three arcs carry the fixed ring hues, which mean the same score on every wallpaper.
 */
class NoopGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent(currentWidgetSnapshot()) }
    }

    /** Defence-in-depth, NOT a crash fix: Glance 1.1.0's default already contains composition errors
     *  (it renders its built-in error layout; verified in bytecode while investigating #82 — which we
     *  could not reproduce). This override only swaps that generic layout for our own friendlier one.
     *  The widget heals on the next successful push. */
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

@Composable
private fun WidgetContent(snap: WidgetSnapshot) {
    WidgetCard(link = AppLink.Today, padding = 12.dp, horizontalAlignment = Alignment.CenterHorizontally) {
        // The vitals sit on the card's bottom edge and the rings in the middle of the room above them, so
        // a taller placement spends its spare height around the rings rather than under them.
        Spacer(GlanceModifier.defaultWeight())
        // The three scores in the Summary's order. Each cell is honest-null until that score exists.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            ScoreCell(WidgetRing.Charge, uiString(R.string.metric_title_charge), snap.recoveryPct, GlanceModifier.defaultWeight())
            ScoreCell(
                WidgetRing.Effort, uiString(R.string.metric_title_effort), snap.effortPct, GlanceModifier.defaultWeight(),
                figure = effortFigure(snap),
            )
            ScoreCell(WidgetRing.Rest, uiString(R.string.metric_title_rest), snap.restPct, GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.defaultWeight())
        WidgetVitalsRow(snap)
    }
}

/**
 * The strain figure when it is NOT the whole percent, which is when the wearer reads strain on the 0 to
 * 21 scale; null on the app's own 0 to 100 axis, where the cell prints the percent like its neighbours.
 * Shared by the rings and the compact widget so the two cannot print different numbers.
 */
@Composable
internal fun effortFigure(snap: WidgetSnapshot): String? {
    val scale = UnitPrefs.effortScale(LocalContext.current)
    return if (scale == EffortScale.WHOOP) WidgetCaptions.effort(snap.effortPct, snap.effort, scale) else null
}

/** One score: its ring with the figure inside, over its caption in the reader's language (WG-5). */
@Composable
private fun ScoreCell(ring: WidgetRing, label: String, pct: Int?, modifier: GlanceModifier, figure: String? = null) {
    // Built OUT here rather than inside a semantics lambda: #571 recorded that the i18n audit cannot see
    // copy assigned inside one.
    val noData = uiString(R.string.widget_no_data)
    // A figure that is not the percent is spoken as itself: "Strain, 4.0", never "Strain, 19%".
    val spoken = WidgetCaptions.spoken(
        label, pct?.let { figure ?: uiString(R.string.l10n_today_screen_pct_ee63e247, it) }, noData,
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        WidgetScoreRing(
            ring, pct, diameter = 52.dp, figureSize = 16.sp, spoken = spoken,
            figure = figure ?: WidgetCaptions.score(pct),
        )
        Spacer(GlanceModifier.height(2.dp))
        // Decorative to TalkBack in effect: the ring above already speaks "Charge, 68%".
        Text(text = label, style = WidgetType.captionStyle, maxLines = 1)
    }
}

/**
 * The line under the scores: the heart rate, the strap battery, and — once the strap has gone quiet —
 * when the figures are from. Shared by the rings and the compact widget.
 *
 * No connection dot and no "Connected" (audit WG-4): a red/green dot is a colour-only channel, and a
 * reading too old to stand for the wearer already shows as a dash ([HrDisplay]).
 */
@Composable
internal fun WidgetVitalsRow(snap: WidgetSnapshot) {
    val context = LocalContext.current
    val noData = uiString(R.string.widget_no_data)
    val hrLabel = uiString(R.string.l10n_noop_glance_widget_heart_rate_410aa15c)
    val batteryLabel = uiString(R.string.l10n_noop_glance_widget_strap_battery_a6c7f09c)
    // The stale/live distinction is drawn ONLY by dimming the text below, which is a colour-only channel:
    // TalkBack, and anyone who cannot perceive the dim, was told a carried-over reading was current. Marked
    // on the LIVE side rather than the stale one, so a stale value simply carries no claim (#1799).
    val liveSuffix =
        if (snap.heartRateStale) "" else " " + uiString(R.string.l10n_today_screen_sync_chip_live_98aadb37)
    val hrSpoken = WidgetCaptions.spoken(
        hrLabel,
        snap.heartRate?.let { uiString(R.string.l10n_today_screen_value_bpm_8f3a90c3, it) + liveSuffix },
        noData,
    )
    val batteryText = snap.batteryPct?.let { uiString(R.string.l10n_today_screen_pct_ee63e247, it) }
    val batterySpoken = WidgetCaptions.spoken(batteryLabel, batteryText, noData)
    // When the figures are from, shown only once nothing is arriving: while the strap streams, the time
    // would be "now" on every push and say nothing.
    val updated = if (!snap.connected && snap.updatedAtMs > 0L) {
        SimpleDateFormat(   // #1821: the reader's chosen clock
            ClockFormat.hourMinutePattern(ClockPrefs.uses24Hour(context)), Locale.getDefault(),
        ).format(Date(snap.updatedAtMs))
    } else {
        null
    }
    val updatedSpoken = updated?.let { uiString(R.string.l10n_hr_glance_widget_updated_time_1b5feedb, it) }

    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = GlanceModifier.semantics { contentDescription = hrSpoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_stat_heart),
                contentDescription = null,
                modifier = GlanceModifier.size(12.dp),
                colorFilter = ColorFilter.tint(WidgetColors.heart),
            )
            Spacer(GlanceModifier.width(3.dp))
            Text(
                text = WidgetCaptions.heartRate(snap.heartRate),
                // Dim a carried-over reading so a stale HR can't masquerade as a live one.
                style = TextStyle(
                    color = if (snap.heartRateStale || snap.heartRate == null) WidgetColors.onSurfaceVariant
                    else WidgetColors.onSurface,
                    fontSize = WidgetType.caption,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        if (updated != null && updatedSpoken != null) {
            Text(
                text = updated,
                style = WidgetType.captionStyle,
                maxLines = 1,
                modifier = GlanceModifier.semantics { contentDescription = updatedSpoken },
            )
            Spacer(GlanceModifier.defaultWeight())
        }
        Row(
            modifier = GlanceModifier.semantics { contentDescription = batterySpoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_strap_battery),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(WidgetColors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.width(3.dp))
            Text(
                text = batteryText ?: WidgetCaptions.DASH,
                style = TextStyle(
                    color = if (batteryText == null) WidgetColors.onSurfaceVariant else WidgetColors.onSurface,
                    fontSize = WidgetType.caption,
                ),
                maxLines = 1,
            )
        }
    }
}
