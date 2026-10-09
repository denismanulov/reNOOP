package com.noop.ui.lab

import com.noop.ui.m3.labelBand
import com.noop.ui.m3.axisBand
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.LabBookProjection
import com.noop.analytics.LabMarkerCategory
import com.noop.analytics.WindowedPair
import com.noop.data.ImportSummary
import com.noop.data.LabMarkerRow
import com.noop.data.WhoopDao
import com.noop.ingest.LabMarkerCsvImport
import com.noop.ui.AppViewModel
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.EmptyState
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PeriodSegmented
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.metric.MetricDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

// MARK: - Lab Results (twin of iOS LabBookView, laid out as Health's Lab Results)
//
// "Your own logbook": markers by category, each with its latest value, date and the range printed on the
// user's own report; a marker opens its page (latest, chart, compare with a wearable signal, every reading).
// "+" adds a reading or imports a markers CSV. Everything stays on this device: readings live in the
// `labMarker` table under the strap device id, and every write also projects a daily series under the
// `lab-book` source so Coach and the metric pages see markers.
//
// NON-CLINICAL (load-bearing): no word here asserts a clinical judgement, never "abnormal/high/low/normal"
// as NOOP's own statement; any range shown is EXACTLY what the user typed from their own report; comparison
// copy says association, not cause. The ⓘ in the bar says so.

private const val LAB_STRAP_DEVICE_ID = "my-whoop"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabResultsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val translate: (Int) -> String = { context.getString(it) }

    var markers by remember { mutableStateOf<List<LabMarkerRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadSeq by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Markers CSV import (LabMarkerCsvImport).
    var csvImporting by remember { mutableStateOf(false) }
    var csvSummary by remember { mutableStateOf<String?>(null) }
    var csvFailed by remember { mutableStateOf(false) }

    LaunchedEffect(reloadSeq) {
        val all = ArrayList<LabMarkerRow>()
        for (category in LabMarkerCategory.entries) {
            all += runCatching { vm.repo.labMarkersByCategory(LAB_STRAP_DEVICE_ID, category.raw) }.getOrDefault(emptyList())
        }
        markers = all.sortedBy { it.takenAt }
        loaded = true
    }

    // The same OpenDocument + "*/*" idiom as the Data Sources importers (csv mime filtering through the
    // system picker is unreliable across providers).
    val csvImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        csvImporting = true
        csvSummary = null
        csvFailed = false
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { LabMarkerCsvImport.importCsv(context, uri, vm.repo) }
                    .getOrElse { ImportSummary.failure("Lab Book CSV", it.message ?: "failed") }
            }
            // Mirrored into the exported strap log (#421 parity): counts only on success, the human reason
            // on failure; never a file name, a path or a value.
            if (summary.totalRows > 0) {
                vm.ble.externalLog("Import ${summary.source}: labMarker=${summary.totalRows}")
            } else {
                vm.ble.externalLog("Import ${summary.source} failed: ${summary.message}")
            }
            csvSummary = summary.message
            csvFailed = summary.totalRows == 0
            csvImporting = false
            reloadSeq++
        }
    }

    if (showEditor) {
        LabReadingSheet(
            onDismiss = { showEditor = false },
            onSave = { drafts ->
                scope.launch {
                    vm.repo.upsertLabMarkers(drafts)
                    reloadSeq++
                }
                showEditor = false
            },
        )
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.lab_about_title)) },
            text = { Text(stringResource(R.string.lab_about_body)) },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.journal_done)) } },
        )
    }

    BackHandler(enabled = detailKey != null) { detailKey = null }
    val openKey = detailKey
    if (openKey != null) {
        val readings = markers.filter { it.markerKey == openKey }
        if (readings.isNotEmpty() || !loaded) {
            MarkerPage(
                vm = vm,
                markerKey = openKey,
                readings = readings,
                onDelete = { id ->
                    scope.launch {
                        vm.repo.deleteLabMarker(id)
                        reloadSeq++
                    }
                },
                onBack = { detailKey = null },
            )
            return
        }
        // Its last reading was deleted: back to the list.
        LaunchedEffect(openKey) { detailKey = null }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(
            title = stringResource(R.string.lab_title),
            onBack = onBack,
            actions = {
                IconButton(onClick = { showAbout = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.lab_about_title))
                }
                Box {
                    IconButton(onClick = { addMenu = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.lab_add))
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lab_add_reading)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = { addMenu = false; showEditor = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lab_import_csv)) },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                            enabled = !csvImporting,
                            onClick = { addMenu = false; csvImportLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding,
                end = M3Dimens.screenPadding,
                top = 8.dp,
                bottom = M3Dimens.bottomBarClearance + 16.dp,
            ),
        ) {
            csvSummary?.let { s ->
                item(key = "csv") {
                    Text(
                        s,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (csvFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            when {
                !loaded || csvImporting -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                markers.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Assignment,
                        title = stringResource(R.string.lab_empty_title),
                        message = stringResource(R.string.lab_empty_message),
                        action = stringResource(R.string.lab_add_reading),
                        onAction = { showEditor = true },
                        modifier = Modifier.padding(top = 60.dp),
                    )
                }
                else -> for (category in LabFormat.orderedCategories(markers)) {
                    val keys = LabFormat.markerKeys(markers, category, translate)
                    item(key = "h:" + category.raw) { SectionHeader(stringResource(LabFormat.categoryRes(category))) }
                    item(key = "c:" + category.raw) {
                        ListGroup(Modifier.padding(top = 8.dp)) {
                            for (markerKey in keys) {
                                item { shape ->
                                    MarkerRow(
                                        shape = shape,
                                        name = LabFormat.name(markerKey, translate),
                                        latest = markers.lastOrNull { it.markerKey == markerKey },
                                        markerKey = markerKey,
                                        locale = locale,
                                        translate = translate,
                                        onClick = { detailKey = markerKey },
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

/** Health's lab-result row: the marker and when it was taken; the latest value and the report's range. */
@Composable
private fun MarkerRow(
    shape: Shape,
    name: String,
    latest: LabMarkerRow?,
    markerKey: String,
    locale: Locale,
    translate: (Int) -> String,
    onClick: () -> Unit,
) {
    ListRow(
        shape = shape,
        title = name,
        subtitle = latest?.let { LabFormat.dayFromKey(it.day, locale) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    LabValueText(latest, markerKey, locale, translate, MaterialTheme.typography.titleLarge)
                    latest?.referenceText?.takeIf { it.isNotEmpty() }?.let { ref ->
                        Text(
                            stringResource(R.string.lab_range, ref),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ChevronRight()
            }
        },
        onClick = onClick,
    )
}

/** A reading's value in the Health idiom: the figure bold, the unit smaller and quieter. */
@Composable
private fun LabValueText(row: LabMarkerRow?, markerKey: String, locale: Locale, translate: (Int) -> String, style: TextStyle) {
    val v = row?.value
    if (row != null && v != null) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                LabFormat.displayValue(v, markerKey, locale),
                style = style.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val unit = LabFormat.unit(row.unit, translate)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    } else {
        Text(row?.valueText ?: "—", style = style.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The Health hue a category's chart is drawn in. */
@Composable
private fun categoryTint(category: LabMarkerCategory?): Color = when (category) {
    LabMarkerCategory.BLOOD_PANEL, LabMarkerCategory.BLOOD_PRESSURE -> Health.colors.heart
    LabMarkerCategory.BODY_MEASUREMENT -> Health.colors.body
    LabMarkerCategory.IMAGING, LabMarkerCategory.APPOINTMENT_NOTE -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// MARK: - Marker page (latest, chart, compare with a signal, every reading)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MarkerPage(
    vm: AppViewModel,
    markerKey: String,
    readings: List<LabMarkerRow>,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val translate: (Int) -> String = { context.getString(it) }
    val name = LabFormat.name(markerKey, translate)
    val numeric = readings.filter { it.value != null }
    val tint = categoryTint(readings.lastOrNull()?.let { LabMarkerCategory.fromRaw(it.category) })

    var signalKey by rememberSaveable { mutableStateOf("") }
    var windowIndex by rememberSaveable { mutableStateOf(LabWindow.Fortnight.ordinal) }
    val window = LabWindow.entries[windowIndex]
    val signal: MetricDescriptor? = LabSignals.options.firstOrNull { it.key == signalKey }
    var pairs by remember { mutableStateOf<List<WindowedPair>>(emptyList()) }
    var correlation by remember { mutableStateOf<Double?>(null) }
    var computing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LabMarkerRow?>(null) }
    var signalMenu by remember { mutableStateOf(false) }

    LaunchedEffect(signalKey, windowIndex, readings.size) {
        val s = signal
        if (s == null) {
            pairs = emptyList()
            correlation = null
            return@LaunchedEffect
        }
        computing = true
        val today = LocalDate.now(ZoneOffset.UTC)
        val to = today.plusDays(1).toString()
        val from = today.minusDays(4000).toString()
        // The marker series from the projected `lab-book` daily series (numeric only); the wearable series
        // freshest-wins through the resolver.
        val markerSeries = runCatching {
            vm.repo.metricSeries(WhoopDao.LAB_BOOK_SOURCE_ID, markerKey, from, to).map { it.day to it.value }
        }.getOrDefault(emptyList())
        val wearable = runCatching {
            vm.repo.resolvedSeries(s.key, s.source, from, to, strapDeviceId = vm.activeStrapId).values
        }.getOrDefault(emptyList())
        val built = LabBookProjection.pairMarkerToWearable(markerSeries, wearable, window.days)
        pairs = built
        correlation = if (built.size >= LabSignals.FLOOR) LabSignals.pearson(LabBookProjection.correlationInput(built)) else null
        computing = false
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.lab_delete_reading)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(row.id) }) {
                    Text(stringResource(R.string.lab_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.lab_cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PushedTopBar(title = name, onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(
                start = M3Dimens.screenPadding,
                end = M3Dimens.screenPadding,
                top = 8.dp,
                bottom = M3Dimens.bottomBarClearance + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "latest") {
                HealthCard(verticalSpacing = 2.dp) {
                    readings.lastOrNull()?.let { latest ->
                        Text(
                            stringResource(R.string.lab_latest),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LabValueText(latest, markerKey, locale, translate, MaterialTheme.typography.headlineLarge)
                        Text(
                            LabFormat.dayFromKey(latest.day, locale),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (numeric.size > 1) {
                        ReadingsChart(numeric, markerKey, tint, locale, Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp))
                    }
                }
            }
            readings.lastOrNull { !it.referenceText.isNullOrEmpty() }?.referenceText?.let { ref ->
                item(key = "range") {
                    Text(
                        stringResource(R.string.lab_range_on_report, ref),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (numeric.isNotEmpty()) {
                item(key = "compare") {
                    ListGroup(
                        header = stringResource(R.string.lab_compare),
                        footer = if (signal != null) {
                            stringResource(R.string.lab_compare_footer, stringResource(window.phraseRes))
                        } else null,
                    ) {
                        item { shape ->
                            ListRow(
                                shape = shape,
                                title = stringResource(R.string.lab_signal),
                                onClick = { signalMenu = true },
                                // The menu hangs from the value it changes, at the row's trailing edge.
                                trailing = {
                                    Box {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                signal?.let { stringResource(it.titleRes) } ?: stringResource(R.string.lab_none),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        DropdownMenu(expanded = signalMenu, onDismissRequest = { signalMenu = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.lab_none)) },
                                                onClick = { signalMenu = false; signalKey = "" },
                                            )
                                            for (option in LabSignals.options) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(option.titleRes)) },
                                                    onClick = { signalMenu = false; signalKey = option.key },
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        if (signal != null) {
                            item { shape ->
                                Column(
                                    Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PeriodSegmented(
                                        options = LabWindow.entries.map { stringResource(it.labelRes) },
                                        selectedIndex = windowIndex,
                                        onSelect = { windowIndex = it },
                                        contentDescriptions = LabWindow.entries.map { stringResource(it.phraseRes) },
                                    )
                                    CompareResult(name, stringResource(signal.titleRes), pairs.size, correlation, computing, locale)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "all") {
                ListGroup(header = stringResource(R.string.lab_all_readings)) {
                    for (row in readings.reversed()) {
                        item { shape ->
                            key(row.id) {
                                ReadingRow(row, markerKey, shape, locale, translate) { deleting = row }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Below the floor the points exist and the conclusion is withheld; above it, one restrained sentence. */
@Composable
private fun CompareResult(markerName: String, signalName: String, n: Int, r: Double?, computing: Boolean, locale: Locale) {
    when {
        computing -> CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
        n < LabSignals.FLOOR -> Text(
            stringResource(R.string.lab_compare_floor, n, LabSignals.FLOOR),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r != null -> Text(
            stringResource(LabSignals.insightRes(r), markerName, signalName.lowercase(locale)),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        else -> Text(
            stringResource(R.string.lab_compare_flat),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One reading: value (and its note), the date; a swipe left or a long press asks before deleting (LB-2). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ReadingRow(
    row: LabMarkerRow,
    markerKey: String,
    shape: Shape,
    locale: Locale,
    translate: (Int) -> String,
    onAskDelete: () -> Unit,
) {
    val deleteLabel = stringResource(R.string.lab_delete)
    // The row never slides away on its own: the question is answered first.
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) onAskDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.clip(shape),
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = M3Dimens.rowMinHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .combinedClickable(onClick = {}, onLongClick = onAskDelete)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onAskDelete(); true })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LabValueText(row, markerKey, locale, translate, MaterialTheme.typography.bodyLarge)
                row.note?.takeIf { it.isNotEmpty() }?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                LabFormat.dayFromKey(row.day, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every numeric reading over time: hollow points joined by a line, the value axis on the trailing edge. */
@Composable
private fun ReadingsChart(numeric: List<LabMarkerRow>, markerKey: String, tint: Color, locale: Locale, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val hole = MaterialTheme.colorScheme.surfaceContainerLow
    val values = numeric.mapNotNull { it.value }
    val lo = values.min()
    val hi = values.max()
    val pad = ((hi - lo).takeIf { it > 0 } ?: maxOf(kotlin.math.abs(hi) * 0.1, 1.0)) * 0.15
    val yMin = lo - pad
    val yMax = hi + pad
    val t0 = numeric.first().takenAt
    val t1 = numeric.last().takenAt
    val first = LabFormat.dayFromKey(numeric.first().day, locale)
    val last = LabFormat.dayFromKey(numeric.last().day, locale)
    Canvas(modifier.clearAndSetSemantics {}) {
        val tickValues = listOf(lo, (lo + hi) / 2, hi).distinct()
        val axisW = axisBand(measurer, tickValues.map { LabFormat.displayValue(it, markerKey, locale) }, labelStyle, floor = 44.dp)
        val labelH = labelBand(measurer, labelStyle, floor = 18.dp)
        val inset = 6.dp.toPx()
        val plotW = size.width - axisW
        val plotH = size.height - labelH
        fun x(t: Long): Float = if (t1 == t0) plotW / 2 else inset + (t - t0).toFloat() / (t1 - t0).toFloat() * (plotW - inset * 2)
        fun y(v: Double): Float = (plotH - (v - yMin) / (yMax - yMin) * plotH).toFloat()
        for (tick in tickValues) {
            val yy = y(tick)
            drawLine(grid, Offset(0f, yy), Offset(plotW, yy), strokeWidth = 0.5.dp.toPx())
            val layout = measurer.measure(LabFormat.displayValue(tick, markerKey, locale), labelStyle)
            drawText(layout, topLeft = Offset(plotW + 6.dp.toPx(), yy - layout.size.height / 2f))
        }
        val path = Path()
        numeric.forEachIndexed { i, row ->
            val p = Offset(x(row.takenAt), y(row.value ?: 0.0))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, tint, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (row in numeric) {
            val p = Offset(x(row.takenAt), y(row.value ?: 0.0))
            drawCircle(hole, radius = 4.5.dp.toPx(), center = p)
            drawCircle(tint, radius = 4.5.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
        }
        val firstLayout = measurer.measure(first, labelStyle)
        drawText(firstLayout, topLeft = Offset(0f, plotH + 4.dp.toPx()))
        val lastLayout = measurer.measure(last, labelStyle)
        drawText(lastLayout, topLeft = Offset((plotW - lastLayout.size.width).coerceAtLeast(firstLayout.size.width + 8.dp.toPx()), plotH + 4.dp.toPx()))
    }
}
