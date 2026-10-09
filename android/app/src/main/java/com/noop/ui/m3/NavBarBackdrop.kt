package com.noop.ui.m3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// MARK: - The strip under the gesture bar
//
// Compose sizes a dialog's or a bottom sheet's window to stop at the gesture bar, so the strip below it is
// still the activity: under a full-screen dialog or a sheet it showed the navigation bar's colour as a band
// along the bottom. While one is up it puts its own surface colour here, and the app shell paints the strip
// in it, so the dialog or sheet reads as running to the screen's edge.

/** The surfaces showing over the app, oldest first; the newest is the one the strip matches. */
internal object NavBarBackdrop {
    val colors = mutableStateListOf<Color>()
}

/** Paints the shell's nav-bar strip in [color] for as long as the caller is composed. */
@Composable
private fun NavBarBackdropEffect(color: Color) {
    DisposableEffect(color) {
        NavBarBackdrop.colors += color
        onDispose { NavBarBackdrop.colors -= color }
    }
}

/** Call from a full-screen dialog drawn on the surface colour. */
@Composable
fun FullScreenDialogBackdropEffect() = NavBarBackdropEffect(MaterialTheme.colorScheme.surface)

/**
 * Call first inside a modal bottom sheet's content, with the [containerColor] the sheet was given (the
 * Material default when it was given none). A sheet on the plain surface colour is tinted by its tonal
 * elevation, which is read here from inside the sheet, so the strip takes the tint too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetBackdropEffect(containerColor: Color = BottomSheetDefaults.ContainerColor) {
    val scheme = MaterialTheme.colorScheme
    val shown = if (containerColor == scheme.surface) {
        scheme.surfaceColorAtElevation(LocalAbsoluteTonalElevation.current)
    } else containerColor
    NavBarBackdropEffect(shown)
}

/** The strip itself, for the app shell's root box: nothing while no dialog or sheet is up. */
@Composable
fun BoxScope.NavBarBackdropStrip() {
    val color = NavBarBackdrop.colors.lastOrNull() ?: return
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(color),
    )
}
