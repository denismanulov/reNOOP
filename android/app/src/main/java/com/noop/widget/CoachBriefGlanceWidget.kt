package com.noop.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.noop.R
import com.noop.ui.AppLink
import com.noop.ui.uiString
import java.text.DateFormat
import java.util.Date

/**
 * K10: Home-screen widget showing the stored Coach morning brief (Android twin of the iOS
 * `CoachBriefWidget`). Renders purely from the `noop_widget` SharedPreferences — no network,
 * no DB. The brief is generated on a schedule by [com.noop.ui.CoachBriefScheduler] (K5) and
 * mirrored into the widget's prefs via `publishToWidget`. Tapping it opens Coach.
 *
 * Design contract (PRD-K10 + D8): the widget reads STORED brief text, NEVER calls the network.
 * The brief text is generated on-device on a schedule, not live-networked in the widget.
 */
class CoachBriefGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read inside the composition, keyed on the store's redraw counter, for the reason
            // [currentWidgetSnapshot] gives: a brief captured here would outlive the update that replaced it.
            val revision by WidgetSnapshotStore.revision.collectAsState()
            val brief = remember(revision) {
                val prefs = context.getSharedPreferences("noop_widget", Context.MODE_PRIVATE)
                prefs.getString("coachBriefText", null) to prefs.getLong("coachBriefDateMs", 0L)
            }
            CoachBriefWidgetContent(brief.first, brief.second)
        }
    }
}

@Composable
private fun CoachBriefWidgetContent(briefText: String?, briefDateMs: Long) {
    WidgetCard(link = AppLink.Coach) {
        // Title in the wallpaper's accent, and when the brief was written beside it.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = uiString(R.string.coach_brief_widget_title),
                style = TextStyle(
                    color = WidgetColors.primary, fontSize = WidgetType.label, fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (briefDateMs > 0L) {
                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(briefDateMs)),
                    style = WidgetType.captionStyle,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (briefText == null) {
            Text(text = uiString(R.string.coach_brief_widget_empty), style = WidgetType.titleStyle)
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(text = uiString(R.string.coach_brief_widget_enable), style = WidgetType.captionStyle)
        } else {
            // The first few lines of the brief, capped so it fits the card.
            Text(
                text = WidgetCaptions.briefExcerpt(briefText),
                style = TextStyle(color = WidgetColors.onSurface, fontSize = WidgetType.label),
            )
        }
    }
}
