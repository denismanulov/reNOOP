package com.noop.ui.m3

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.noop.ui.reNoopColorScheme

// MARK: - Always-dark content (the recording screens)
//
// Fitness records a workout on a black screen whatever the system appearance; the Android twin is the dark
// Material You scheme (wallpaper-derived on Android 12+, the reNOOP seed below), the dark data hues and dark
// tonal icons, for the content inside [AlwaysDark] only. While it is on screen the status- and navigation-bar
// icons are drawn light, and they go back to what the app theme set when it leaves.

/** Draws [content] in the dark scheme regardless of the app's light / dark setting. */
@Composable
fun AlwaysDark(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context) { reNoopColorScheme(context, dark = true) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view) {
            val window = (view.context as? Activity)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val status = controller?.isAppearanceLightStatusBars
            val nav = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                if (status != null) controller.isAppearanceLightStatusBars = status
                if (nav != null) controller.isAppearanceLightNavigationBars = nav
            }
        }
    }
    CompositionLocalProvider(
        LocalHealthColors provides DarkHealthColors,
        LocalTonalIcons provides DarkTonalIcons,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
