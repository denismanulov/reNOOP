package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import com.noop.R

// MARK: - The five primary tabs (the iOS RootTabView tab set, plus the fork's Friends)
//
// Summary, Sleep, Workouts, Friends, Browse, in the bar's order. Friends is a reNOOP fork feature with no
// iOS twin (friends-server/README.md). Settings is not a tab: it is pushed from the Summary's profile
// avatar. Everything outside the main tabs is a row in Browse. Each tab keeps its own back stack inside the
// one NavHost (AppRoot saves and restores it on every tab switch).

/** One primary tab: the route of its root screen, its label, and its selected / unselected icons. */
internal enum class MainTab(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Summary(Destination.Today.route, R.string.nav_summary, Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
    Sleep(Destination.Sleep.route, R.string.nav_sleep, Icons.Filled.Bedtime, Icons.Outlined.Bedtime),
    Workouts(
        Destination.Workouts.route,
        R.string.nav_workouts,
        Icons.AutoMirrored.Filled.DirectionsRun,
        Icons.AutoMirrored.Outlined.DirectionsRun,
    ),
    Friends(Destination.Friends.route, R.string.nav_friends, Icons.Filled.Group, Icons.Outlined.Group),
    Browse(Destination.Browse.route, R.string.nav_browse, Icons.Filled.Search, Icons.Outlined.Search);

    companion object {
        /** The tab whose ROOT screen has this route, or null for a pushed screen. */
        fun forRootRoute(route: String?): MainTab? = entries.firstOrNull { it.route == route }
    }
}

/**
 * The re-tap counter of the tab root this composition belongs to. AppRoot bumps it when the wearer taps
 * the tab that is already selected while it is already at its root (a re-tap with screens pushed pops them
 * instead). Provided per tab-root destination, so a tab switch never changes the value a root reads.
 */
internal val LocalScrollToTopSignal = compositionLocalOf { 0 }

/**
 * Runs [onScrollToTop] each time the enclosing tab root is re-selected, never on first composition: the
 * counter's value when this root (re)appears is the baseline, so returning to a tab keeps its position.
 */
@Composable
internal fun OnScrollToTop(onScrollToTop: suspend () -> Unit) {
    val signal = LocalScrollToTopSignal.current
    val baseline = remember { signal }
    val action by rememberUpdatedState(onScrollToTop)
    LaunchedEffect(signal) {
        if (signal != baseline) action()
    }
}
