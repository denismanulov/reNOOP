package com.noop.ui.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ui.AppViewModel
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.RowIcon
import com.noop.ui.m3.SectionHeader
import com.noop.ui.metric.MetricDescriptor
import com.noop.ui.uiString

// MARK: - Highlights pages (twin of iOS HighlightsPages)
//
// The two pages behind "Show All": one subject's highlights filed by how often each is renewed, and every
// subject that has any, grouped by Health category.

/** The row at the foot of a list that opens the next one: a title and a chevron in a card of its own. */
@Composable
fun HighlightsLinkRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListGroup(modifier) {
        item { shape -> ListRow(shape, title, trailing = { ChevronRight() }, onClick = onClick) }
    }
}

/** A subject's highlights under "Daily", "Weekly", "Monthly" and "Yearly", each section only when it has one. */
fun LazyListScope.highlightSections(group: HighlightGroup) {
    HighlightCadence.entries.forEach { cadence ->
        val items = group.items(cadence)
        if (items.isEmpty()) return@forEach
        item(key = "section-$cadence") { SectionHeader(stringResource(cadence.sectionTitleRes)) }
        items(items, key = { it.id }) { HighlightItemCard(it) }
    }
}

/** Re-read whenever the day history or the workouts change, as the Summary does. */
@Composable
private fun highlightsRevision(vm: AppViewModel): Any {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    return listOf(days.size, days.lastOrNull(), workouts.size, workouts.firstOrNull()?.endTs)
}

// MARK: One subject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectHighlightsScreen(
    vm: AppViewModel,
    subject: HighlightSubject,
    metric: MetricDescriptor?,
    onBack: () -> Unit,
    onOpenAll: () -> Unit,
) {
    val context = LocalContext.current
    val revision = highlightsRevision(vm)
    var group by remember { mutableStateOf<HighlightGroup?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(subject, metric, revision) {
        group = HealthHighlightLoader.group(subject, vm, context, metric = metric)
        loaded = true
    }
    val name = group?.title ?: uiString(subject.titleRes)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = HighlightCopy.title(name), onBack = onBack, large = true, scrollBehavior = scrollBehavior)
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            if (!loaded) item(key = "loading") { Loading() }
            group?.let { highlightSections(it) }
            if (loaded && group == null) {
                item(key = "empty") { EmptyState(icon = Icons.Outlined.AutoAwesome, title = stringResource(R.string.hl_none)) }
            }
            if (loaded) {
                item(key = "all") {
                    HighlightsLinkRow(stringResource(R.string.hl_show_all), onOpenAll, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

// MARK: Every subject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllHighlightsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenSubject: (HighlightSubject) -> Unit) {
    val context = LocalContext.current
    val revision = highlightsRevision(vm)
    var groups by remember { mutableStateOf<List<HighlightGroup>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        groups = HealthHighlightLoader.all(vm, context)
        loaded = true
    }
    // The categories that have a subject, by name as Health lists them, each with its subjects.
    val collator = remember { java.text.Collator.getInstance() }
    val sections = groups.groupBy { it.subject.category }
        .entries.sortedWith { a, b -> collator.compare(uiString(a.key.titleRes), uiString(b.key.titleRes)) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = stringResource(R.string.hl_all_title), onBack = onBack, large = true, scrollBehavior = scrollBehavior)
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "hero") {
                Text(
                    stringResource(R.string.hl_health_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (!loaded) item(key = "loading") { Loading() }
            if (loaded && groups.isEmpty()) {
                item(key = "empty") { EmptyState(icon = Icons.Outlined.AutoAwesome, title = stringResource(R.string.hl_none)) }
            }
            sections.forEach { (category, subjects) ->
                item(key = "category-$category") {
                    ListGroup(header = uiString(category.titleRes)) {
                        subjects.forEach { group ->
                            item { shape ->
                                ListRow(
                                    shape, group.title,
                                    leading = { RowIcon(category.icon, tint = MaterialTheme.colorScheme.primary) },
                                    trailing = { ChevronRight() },
                                    onClick = { onOpenSubject(group.subject) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * The navigation route of a subject's Highlights page; [metric] names the one catalogue entry a metric
 * page's "Show All" reads.
 */
fun highlightsRoute(subject: HighlightSubject, metric: MetricDescriptor? = null): String =
    if (metric == null) "highlights/${subject.segment}"
    else "highlights/${subject.segment}?key=${android.net.Uri.encode(metric.key)}&source=${android.net.Uri.encode(metric.source)}"

const val ALL_HIGHLIGHTS_ROUTE = "all_highlights"
