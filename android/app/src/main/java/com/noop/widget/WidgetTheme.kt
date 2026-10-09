package com.noop.widget

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/**
 * The light/dark state a home-screen widget follows, resolved the way every widget in this package
 * resolves it.
 *
 * It is the SYSTEM's: a widget sits on the home screen, whose appearance is the system's, and the app
 * itself follows the system too (its in-app Appearance controls are gone and a stored theme mode is
 * ignored, see `NoopTheme`). It used to honour that stored mode, which left a widget in the colours of a
 * setting nobody can reach any more.
 *
 * On Android 12+ the widgets do not need this to colour themselves: their surface and text are day/night
 * resources the launcher resolves, and a chart carries a drawing for each ([WidgetChartImage]). It is what
 * a widget composed below Android 12 is coloured by, and it stays in [RenderedGate]'s key for that reason:
 * there, a theme change reaches the screen only through a push.
 */
internal object WidgetTheme {

    /** Whether the system is in dark mode. Light on any failure, which is the resources' own default. */
    fun isDark(context: Context): Boolean = runCatching {
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }.getOrDefault(false)

    /**
     * [id] as it resolves in light or in dark, whatever the system is in now. A chart bitmap is drawn once
     * for each, so both need their colours regardless of the mode the app's process happens to see.
     */
    fun color(context: Context, @ColorRes id: Int, dark: Boolean): Int {
        val config = Configuration(context.resources.configuration)
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return ContextCompat.getColor(context.createConfigurationContext(config), id)
    }
}
