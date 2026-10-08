package com.noop.ui

import com.noop.ui.m3.SearchField
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.RowIcon
import com.noop.ui.m3.SectionHeader
import com.noop.ui.m3.color
import com.noop.ui.m3.metricHue
import com.noop.ui.metric.ALL_METRICS_ROUTE
import com.noop.ui.metric.AllMetricsCatalog
import com.noop.ui.metric.MetricCatalog
import com.noop.ui.metric.metricRoute
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// MARK: - Browse (twin of iOS BrowseView)
//
// The fourth tab: every screen outside Summary, Sleep and Workouts, in one searchable list. A docked
// Material search bar over Health's "Categories" group (the places to read and log data) and a second,
// headerless group of tools, each alphabetical by its localized title. Typing lists the matching screens
// first, then the matching metrics of the metric catalogue in their hue, each opening its metric page. Rows push inside the
// Browse tab, so a re-tap of the tab pops back here. Settings is not a row: it opens from the Summary avatar.

/** Which group a Browse row sits in: iOS keeps Health's "Categories" card apart from the tools card. */
internal enum class BrowseGroup { Categories, Tools }

/** The fixed hue of a Browse row's glyph, resolved to a colour at draw time (iOS BrowseView tints). */
internal enum class BrowseTint { Oxygen, Body, Mind, Core, Accent, Temperature, Neutral, Heart, Respiratory }

/** Every screen Browse links to (iOS `MoreDestination`), with the route it pushes inside the Browse tab. */
internal enum class BrowseDestination(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val tint: BrowseTint,
    val group: BrowseGroup,
) {
    AllMetrics(ALL_METRICS_ROUTE, R.string.browse_all_metrics, Icons.Filled.GridView, BrowseTint.Oxygen, BrowseGroup.Categories),
    Journal(Destination.Insights.route, R.string.browse_journal, Icons.AutoMirrored.Filled.MenuBook, BrowseTint.Mind, BrowseGroup.Categories),
    LabResults(Destination.LabBook.route, R.string.browse_lab_results, Icons.AutoMirrored.Filled.Assignment, BrowseTint.Core, BrowseGroup.Categories),
    Trends(Destination.Trends.route, R.string.browse_trends, Icons.AutoMirrored.Filled.TrendingUp, BrowseTint.Accent, BrowseGroup.Categories),
    WhatMovesYou(Destination.InsightsHub.route, R.string.browse_what_moves_you, Icons.Filled.AutoFixHigh, BrowseTint.Temperature, BrowseGroup.Categories),
    Devices(Destination.Devices.route, R.string.browse_devices, Icons.Filled.Watch, BrowseTint.Neutral, BrowseGroup.Tools),
    HeartRate(Destination.Live.route, R.string.browse_heart_rate, Icons.Filled.MonitorHeart, BrowseTint.Heart, BrowseGroup.Tools),
    Mindfulness(Destination.Breathe.route, R.string.browse_mindfulness, Icons.Filled.SelfImprovement, BrowseTint.Respiratory, BrowseGroup.Tools),
}

/** The rows of [group] with no query. Coach is not among them: on Android it is a tab of its own. */
internal fun browseRows(group: BrowseGroup): List<BrowseDestination> =
    BrowseDestination.entries.filter { it.group == group }

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Case- and diacritic-insensitive form of [text] for matching ("Été" and "ete", "Ёж" and "еж"). */
internal fun browseFold(text: String, locale: Locale): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase(locale)

/** True when [text] contains the trimmed [query], ignoring case and diacritics; a blank query matches nothing. */
internal fun browseMatches(text: String, query: String, locale: Locale): Boolean {
    val q = browseFold(query.trim(), locale)
    return q.isNotEmpty() && browseFold(text, locale).contains(q)
}

/** [items] alphabetical by their localized [title] in [locale], as iOS `localizedStandardCompare`. */
internal fun <T> sortedByTitle(items: List<T>, locale: Locale, title: (T) -> String): List<T> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return items.sortedWith { a, b -> collator.compare(title(a), title(b)) }
}

/** One metric Browse search can list: its catalogue key, localized name and score family (for its hue). */
internal data class BrowseMetricEntry(val key: String, val title: String, val category: String)

/** The metric catalogue as Browse search lists it, one entry per key. */
internal fun browseMetricEntries(): List<BrowseMetricEntry> =
    MetricCatalog.all.distinctBy { it.key }.map { BrowseMetricEntry(it.key, it.title, it.category) }

/** What a Browse query found: matching screens first, then matching metrics, each alphabetical. */
internal data class BrowseSearchResult(
    val screens: List<BrowseDestination>,
    val metrics: List<BrowseMetricEntry>,
) {
    val isEmpty: Boolean get() = screens.isEmpty() && metrics.isEmpty()
}

/**
 * Runs a Browse search: the screens whose localized title contains [query],
 * then the catalogue metrics whose title does, one row per metric key.
 */
internal fun browseSearch(
    query: String,
    locale: Locale,
    titleOf: (BrowseDestination) -> String,
    catalogue: List<BrowseMetricEntry>,
): BrowseSearchResult {
    val screens = BrowseGroup.entries
        .flatMap { browseRows(it) }
        .filter { browseMatches(titleOf(it), query, locale) }
    val metrics = catalogue
        .distinctBy { it.key }
        .filter { browseMatches(it.title, query, locale) }
    return BrowseSearchResult(
        screens = sortedByTitle(screens, locale, titleOf),
        metrics = sortedByTitle(metrics, locale) { it.title },
    )
}

/** The Browse tab root. [onOpen] pushes a route inside the Browse tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val locale = LocalConfiguration.current.locales[0]
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    OnScrollToTop { listState.animateScrollToItem(0) }

    val titles = BrowseDestination.entries.associateWith { stringResource(it.titleRes) }
    val titleOf: (BrowseDestination) -> String = { titles.getValue(it) }
    val catalogue = remember(locale) { browseMetricEntries() }
    val trimmed = query.trim()
    val result = remember(trimmed, locale, titles) {
        if (trimmed.isEmpty()) null else browseSearch(trimmed, locale, titleOf, catalogue)
    }
    val open: (String) -> Unit = { route ->
        focusManager.clearFocus()
        onOpen(route)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // The results replace the list below the field (iOS `.searchable`); it never opens a panel of its own.
        SearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.browse_search_placeholder),
            clearLabel = stringResource(R.string.l10n_workouts_screen_clear_search_67300d0f),
            modifier = Modifier.padding(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 16.dp, bottom = 8.dp),
            onSearch = { focusManager.clearFocus() },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding,
                end = M3Dimens.screenPadding,
                bottom = M3Dimens.bottomBarClearance,
            ),
        ) {
            if (result == null) {
                item(key = "categories-header") {
                    SectionHeader(stringResource(R.string.browse_categories), modifier = Modifier.padding(bottom = 8.dp))
                }
                item(key = "categories") {
                    DestinationGroup(sortedByTitle(browseRows(BrowseGroup.Categories), locale, titleOf), titleOf, open)
                }
                item(key = "tools") {
                    Spacer(Modifier.height(16.dp))
                    DestinationGroup(sortedByTitle(browseRows(BrowseGroup.Tools), locale, titleOf), titleOf, open)
                }
            } else if (result.isEmpty) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.browse_no_results, trimmed),
                        message = stringResource(R.string.browse_no_results_hint),
                    )
                }
            } else {
                if (result.screens.isNotEmpty()) {
                    item(key = "hit-screens") {
                        Spacer(Modifier.height(8.dp))
                        DestinationGroup(result.screens, titleOf, open)
                    }
                }
                if (result.metrics.isNotEmpty()) {
                    item(key = "hit-metrics") {
                        Spacer(Modifier.height(16.dp))
                        ListGroup {
                            result.metrics.forEach { metric ->
                                item { shape ->
                                    BrowseRow(
                                        shape = shape,
                                        title = metric.title,
                                        icon = AllMetricsCatalog.category(metric.key, metric.category).icon,
                                        tint = metricHue(metric.key, metric.category).color,
                                        onClick = { open(metricRoute(metric.key)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One segmented group of screen rows. */
@Composable
private fun DestinationGroup(
    rows: List<BrowseDestination>,
    titleOf: (BrowseDestination) -> String,
    onOpen: (String) -> Unit,
) {
    ListGroup {
        rows.forEach { dest ->
            item { shape ->
                BrowseRow(
                    shape = shape,
                    title = titleOf(dest),
                    icon = dest.icon,
                    tint = dest.tint.color(),
                    onClick = { onOpen(dest.route) },
                )
            }
        }
    }
}

/** A Browse row: the glyph in its hue, the title, and the push chevron. */
@Composable
private fun BrowseRow(shape: Shape, title: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    ListRow(
        shape = shape,
        title = title,
        leading = { RowIcon(icon, tint) },
        trailing = { ChevronRight() },
        onClick = onClick,
    )
}

@Composable
private fun BrowseTint.color(): Color {
    val c = Health.colors
    return when (this) {
        BrowseTint.Oxygen -> c.oxygen
        BrowseTint.Body -> c.body
        BrowseTint.Mind -> c.mind
        BrowseTint.Core -> c.stageCore
        BrowseTint.Accent -> MaterialTheme.colorScheme.primary
        BrowseTint.Temperature -> c.temperature
        BrowseTint.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        BrowseTint.Heart -> c.heart
        BrowseTint.Respiratory -> c.respiratory
    }
}
