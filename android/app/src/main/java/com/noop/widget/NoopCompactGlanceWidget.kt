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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.noop.R
import com.noop.ui.AppLink
import com.noop.ui.uiString

/**
 * Compact home-screen widget: Charge, Effort and Rest as a glyph over its figure, with the heart rate and
 * the strap battery under them. The one-row companion of [NoopGlanceWidget], for a home screen with no
 * room for rings; the same snapshot, the same order and hues, and the same tap to the Summary.
 */
class NoopCompactGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { CompactWidgetContent(currentWidgetSnapshot()) }
    }

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
private fun CompactWidgetContent(snap: WidgetSnapshot) {
    WidgetCard(link = AppLink.Today, padding = 10.dp, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CompactScoreCell(
                uiString(R.string.metric_title_charge), R.drawable.ic_widget_charge, snap.recoveryPct,
                WidgetColors.charge, GlanceModifier.defaultWeight(),
            )
            CompactScoreCell(
                uiString(R.string.metric_title_effort), R.drawable.ic_widget_effort, snap.effortPct,
                WidgetColors.effort, GlanceModifier.defaultWeight(),
                figure = effortFigure(snap),
            )
            CompactScoreCell(
                uiString(R.string.metric_title_rest), R.drawable.ic_widget_rest, snap.restPct,
                WidgetColors.rest, GlanceModifier.defaultWeight(),
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        WidgetVitalsRow(snap)
    }
}

/**
 * One score: its glyph in the score's hue over the figure in the card's text colour. The hue names the
 * score and the glyph names it again for anyone the hue does not reach; the figure itself stays in the
 * primary text colour, where it keeps its contrast on every wallpaper. Spoken as one element.
 */
@Composable
private fun CompactScoreCell(
    label: String,
    iconRes: Int,
    pct: Int?,
    hue: ColorProvider,
    modifier: GlanceModifier,
    figure: String? = null,
) {
    val spoken = WidgetCaptions.spoken(
        label,
        pct?.let { figure ?: uiString(R.string.l10n_today_screen_pct_ee63e247, it) },
        uiString(R.string.widget_no_data),
    )
    Column(
        modifier = modifier.semantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(if (pct == null) WidgetColors.onSurfaceVariant else hue),
        )
        Text(
            text = figure ?: WidgetCaptions.score(pct),
            style = WidgetType.figure(
                20.sp, if (pct == null) WidgetColors.onSurfaceVariant else WidgetColors.onSurface,
            ),
            maxLines = 1,
        )
    }
}
