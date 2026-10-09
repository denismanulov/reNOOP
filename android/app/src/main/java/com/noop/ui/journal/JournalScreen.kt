package com.noop.ui.journal

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.JournalEntry
import com.noop.data.MoodStore
import com.noop.ui.AppViewModel
import com.noop.ui.ClockPrefs
import com.noop.ui.JOURNAL_DEVICE_ID
import com.noop.ui.addCaffeineIntake
import com.noop.ui.journalDayKey
import com.noop.ui.loadCaffeineIntakes
import com.noop.ui.loadJournalCatalogItems
import com.noop.ui.m3.Health
import com.noop.ui.m3.HealthCard
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PushedTopBar
import com.noop.ui.m3.SectionHeader
import com.noop.ui.mergeJournalEntries
import com.noop.ui.normJournalKey
import com.noop.ui.removeCaffeineIntake
import com.noop.ui.resolveJournalItems
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Journal (twin of iOS JournalView, laid out as Health's Medications log)
//
// The day's name, a strip of days with a ring per day (how much of it was logged), "To log" cards with a
// "+", then what was logged. The strip runs from six days back to tomorrow (journal answers feed the
// effect ranker, so backfill is bounded, #656); habits answer in a sheet, mood and caffeine log straight
// from their card's "+" menu. A Logged row swipes away, and the snackbar's Undo brings it back.
//
// Writes go through the same repository calls the old Insights journal used, under the native
// `noop-journal` source, so imported WHOOP rows are never touched.

/** What the page shows for the selected day, loaded together. */
private data class JournalState(
    val entries: List<JournalEntry> = emptyList(),
    val importedQuestions: List<String> = emptyList(),
    /** The selected day's native rows (the ones the habits sheet edits and Logged can delete). */
    val native: List<JournalEntry> = emptyList(),
    /** Mood (1–5 step) by day key, for the strip's days. */
    val moods: Map<String, Int> = emptyMap(),
    val loaded: Boolean = false,
)

/** One Logged row: what it is, what was logged, and how to remove (null: imported, not removable). */
private data class LoggedRow(
    val id: String,
    val title: String,
    val value: String,
    val done: Boolean,
    val remove: (() -> Unit)?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    val moodStore = remember { MoodStore(vm.repo) }
    val snackbar = remember { SnackbarHostState() }
    val tint = Health.colors.mind

    var offset by remember { mutableLongStateOf(0L) }
    var seq by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(JournalState()) }
    var catalog by remember { mutableStateOf(loadJournalCatalogItems(context)) }
    var caffeine by remember { mutableStateOf(loadCaffeineIntakes(context)) }
    var showHabits by remember { mutableStateOf(false) }

    // #656: a widget that opens a specific day; consumed once.
    val pendingDay by vm.pendingJournalDayOffset.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDay) {
        pendingDay?.let { offset = if (it in JOURNAL_STRIP_OFFSETS) it else 0L; vm.requestJournalDay(null) }
    }
    // The date can roll over while the page is open in the background: re-stamp on resume (#860).
    var today by remember { mutableStateOf(LocalDate.now(zone)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = LocalDate.now(zone)
                if (now != today) today = now
                caffeine = loadCaffeineIntakes(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun dayKey(off: Long): String = journalDayKey(off, today)
    val selectedKey = dayKey(offset)

    LaunchedEffect(seq, offset, today) {
        val from = dayKey(13L)
        val to = dayKey(-1L)
        val imported = runCatching { vm.repo.journal("my-whoop", from, to) }.getOrDefault(emptyList())
        val native = runCatching { vm.repo.journal(JOURNAL_DEVICE_ID, from, to) }.getOrDefault(emptyList())
        val importedAll = runCatching { vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31") }.getOrDefault(imported)
        val moods = HashMap<String, Int>()
        for (off in JOURNAL_STRIP_OFFSETS) {
            val key = dayKey(off)
            runCatching { moodStore.mood(key) }.getOrNull()?.let { moods[key] = moodStep(it) }
        }
        state = JournalState(
            entries = mergeJournalEntries(imported, native),
            importedQuestions = importedAll.map { it.question }.distinct(),
            native = native.filter { it.day == selectedKey },
            moods = moods,
            loaded = true,
        )
    }

    val items = remember(state.importedQuestions, catalog) {
        resolveJournalItems(state.importedQuestions, catalog, includeHidden = false)
    }
    val translate: (Int) -> String = { context.getString(it) }

    /** Shows "Deleted" with Undo; [restore] runs only when Undo is tapped. */
    fun offerUndo(restore: suspend () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = context.getString(R.string.journal_deleted),
                actionLabel = context.getString(R.string.journal_undo),
                // CR-6: an undo never times out by itself; it stays until Undo, its close button, or the
                // next delete.
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) {
                restore()
                caffeine = loadCaffeineIntakes(context)
                seq++
            }
        }
    }

    val is24h = remember { ClockPrefs.uses24Hour(context) }
    val timeFormat = remember(locale, is24h) { DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale) }

    val logged: List<LoggedRow> = run {
        val rows = ArrayList<LoggedRow>()
        val day = selectedKey
        state.moods[day]?.let { mood ->
            rows += LoggedRow(
                id = "mood",
                title = stringResource(R.string.journal_mood),
                value = moodFace(mood) + " " + stringResource(moodLabelRes(mood)),
                done = true,
                remove = {
                    scope.launch {
                        vm.repo.deleteMetricSeriesPoint(MoodStore.MOOD_DEVICE_ID, day, MoodStore.MOOD_KEY)
                        seq++
                    }
                    offerUndo { moodStore.setMood(day, mood.toDouble()) }
                },
            )
        }
        val nativeKeys = state.native.map { normJournalKey(it.question) }.toSet()
        for (e in state.entries.filter { it.day == day }.sortedBy { it.question }) {
            val item = items.firstOrNull { normJournalKey(it.canonical) == normJournalKey(e.question) }
            val value = e.numericValue?.let { v ->
                listOfNotNull(journalNumber(v), item?.kind?.unitLabel?.takeIf { it.isNotBlank() }).joinToString(" ")
            } ?: stringResource(if (e.answeredYes) R.string.journal_yes else R.string.journal_no)
            val native = normJournalKey(e.question) in nativeKeys
            val nativeRow = state.native.firstOrNull { normJournalKey(it.question) == normJournalKey(e.question) }
            rows += LoggedRow(
                id = "j:" + e.question,
                title = journalQuestionLabel(e.question, items, translate),
                value = value,
                done = e.answeredYes,
                // Only the native row can be cleared; an imported answer belongs to its export.
                remove = if (native && nativeRow != null) {
                    {
                        scope.launch {
                            vm.repo.deleteJournalEntry(JOURNAL_DEVICE_ID, day, nativeRow.question)
                            seq++
                        }
                        offerUndo { vm.repo.upsertJournal(listOf(nativeRow)) }
                    }
                } else null,
            )
        }
        for (intake in caffeine.filter { journalLocalDay(it.atEpochSec, zone) == day }.sortedBy { it.atEpochSec }) {
            val time = timeFormat.format(Instant.ofEpochSecond(intake.atEpochSec).atZone(zone))
            val mg = intake.mg?.let { "${kotlin.math.round(it).toInt()} " + stringResource(R.string.journal_mg) }
            rows += LoggedRow(
                id = "c:" + intake.id,
                title = stringResource(R.string.journal_caffeine),
                value = listOfNotNull(mg, time).joinToString(" · "),
                done = true,
                remove = {
                    caffeine = removeCaffeineIntake(context, intake.id)
                    offerUndo { addCaffeineIntake(context, intake.atEpochSec, intake.mg?.toString()) }
                },
            )
        }
        rows
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            PushedTopBar(title = stringResource(R.string.journal_title), onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(
                    start = M3Dimens.screenPadding,
                    end = M3Dimens.screenPadding,
                    top = 4.dp,
                    bottom = M3Dimens.bottomBarClearance + 72.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
            ) {
                item(key = "day") {
                    Text(
                        dayTitle(offset, today, locale),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                    )
                }
                item(key = "strip") {
                    DayStrip(
                        selected = offset,
                        today = today,
                        locale = locale,
                        tint = tint,
                        fraction = { off ->
                            val key = dayKey(off)
                            journalDayFraction(journalAnsweredCount(state.entries, key), state.moods[key] != null, items.size)
                        },
                        onSelect = { offset = it },
                    )
                }
                item(key = "to-log") { SectionHeader(stringResource(R.string.journal_to_log)) }
                item(key = "habits") {
                    LogCard(Icons.Filled.Checklist, stringResource(R.string.journal_habits), tint) {
                        PlusButton(stringResource(R.string.journal_habits), tint) { showHabits = true }
                    }
                }
                item(key = "mood") {
                    LogCard(Icons.Outlined.EmojiEmotions, stringResource(R.string.journal_mood), tint) {
                        MenuPlus(stringResource(R.string.journal_mood), tint) { close ->
                            for (step in 5 downTo 1) {
                                DropdownMenuItem(
                                    text = { Text(moodFace(step) + "  " + stringResource(moodLabelRes(step))) },
                                    onClick = {
                                        close()
                                        val day = selectedKey
                                        state = state.copy(moods = state.moods + (day to step))
                                        scope.launch { moodStore.setMood(day, step.toDouble()); seq++ }
                                    },
                                )
                            }
                        }
                    }
                }
                // Caffeine is logged for today or an earlier day, never ahead.
                if (offset >= 0) {
                    item(key = "caffeine") {
                        LogCard(Icons.Filled.Coffee, stringResource(R.string.journal_caffeine), tint) {
                            MenuPlus(stringResource(R.string.journal_caffeine), tint) { close ->
                                if (offset == 0L) {
                                    for (h in 0..3) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (h == 0) stringResource(R.string.journal_now)
                                                    else stringResource(R.string.journal_hours_ago, h),
                                                )
                                            },
                                            onClick = {
                                                close()
                                                val at = System.currentTimeMillis() / 1000L - h * 3600L
                                                caffeine = addCaffeineIntake(context, at, null)
                                            },
                                        )
                                    }
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.journal_add)) },
                                        onClick = {
                                            close()
                                            val day = LocalDate.parse(selectedKey)
                                            caffeine = addCaffeineIntake(context, journalNoonEpoch(day, zone), null)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (logged.isNotEmpty()) {
                    item(key = "logged") { LoggedCard(logged, tint) }
                }
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
    }

    if (showHabits) {
        HabitsSheet(
            items = items,
            catalog = catalog,
            answers = state.native.associate { it.question to it },
            translate = translate,
            onCatalog = { next ->
                com.noop.ui.saveJournalCatalogItems(context, next)
                catalog = next
            },
            onAnswer = { q, yes ->
                scope.launch {
                    vm.repo.upsertJournal(listOf(JournalEntry(JOURNAL_DEVICE_ID, selectedKey, q, yes)))
                    seq++
                }
            },
            onNumeric = { q, value ->
                scope.launch {
                    if (value == null) {
                        vm.repo.deleteJournalEntry(JOURNAL_DEVICE_ID, selectedKey, q)
                    } else {
                        // A numeric log writes answeredYes = true AND the value (#322).
                        vm.repo.upsertJournal(
                            listOf(JournalEntry(JOURNAL_DEVICE_ID, selectedKey, q, answeredYes = true, numericValue = value)),
                        )
                    }
                    seq++
                }
            },
            onDismiss = { showHabits = false; seq++ },
        )
    }
}

/** "Today, 27 September" / "Yesterday, …" / "Thursday, 24 September". */
@Composable
private fun dayTitle(offset: Long, today: LocalDate, locale: Locale): String {
    val d = today.minusDays(offset)
    val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale).format(d)
    return when (offset) {
        -1L -> stringResource(R.string.journal_tomorrow) + ", " + dayMonth
        0L -> stringResource(R.string.journal_today) + ", " + dayMonth
        1L -> stringResource(R.string.journal_yesterday) + ", " + dayMonth
        else -> DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(d)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

// MARK: - Day strip

@Composable
private fun DayStrip(
    selected: Long,
    today: LocalDate,
    locale: Locale,
    tint: Color,
    fraction: (Long) -> Float,
    onSelect: (Long) -> Unit,
) {
    val letterFormat = remember(locale) { DateTimeFormatter.ofPattern("EEEEE", locale) }
    val a11yFormat = remember(locale) { DateTimeFormatter.ofPattern("EEEE, d MMMM", locale) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // The weekday letter's disc holds the letter at the reader's font size (28 dp at the default).
    val letterDot = with(LocalDensity.current) { maxOf(28.dp, MaterialTheme.typography.labelLarge.lineHeight.toDp() + 6.dp) }
    Row(Modifier.fillMaxWidth()) {
        for (off in JOURNAL_STRIP_OFFSETS) {
            val isSelected = off == selected
            val date = today.minusDays(off)
            val f = fraction(off)
            val a11yName = a11yFormat.format(date)
            val a11yValue = "${(f * 100).toInt()}%"
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(off) }
                    .padding(vertical = 4.dp)
                    .clearAndSetSemantics {
                        contentDescription = a11yName
                        stateDescription = a11yValue
                        this.selected = isSelected
                        role = Role.Tab
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    modifier = Modifier.size(18.dp),
                )
                Box(
                    Modifier
                        .size(letterDot)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letterFormat.format(date),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Canvas(Modifier.size(36.dp)) {
                    drawCircle(track)
                    if (f > 0f) {
                        val h = size.height * f
                        clipRectBottom(h) { drawCircle(tint) }
                    }
                }
            }
        }
    }
}

/** Clips drawing to the bottom [height] of the canvas: the day's ring fills from the bottom up. */
private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRectBottom(
    height: Float,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    val s: Size = size
    clipRect(top = s.height - height, block = block)
}

// MARK: - To log

/** One tinted "log" card, as Health's scheduled-dose card: icon and title, a "+" on the right. */
@Composable
private fun LogCard(icon: ImageVector, title: String, tint: Color, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(M3Dimens.cardRadius))
            .background(tint.copy(alpha = 0.12f))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun PlusButton(label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Add, contentDescription = label, tint = tint)
    }
}

/** A "+" that opens a menu; [content] gets a close callback for its items. */
@Composable
private fun MenuPlus(label: String, tint: Color, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PlusButton(label, tint) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

// MARK: - Logged

/** Health's "Logged" card: its title inside above a divider; a row swipes left to delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoggedCard(rows: List<LoggedRow>, tint: Color) {
    HealthCard(verticalSpacing = 0.dp, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        Text(
            stringResource(R.string.journal_logged),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEachIndexed { i, row ->
            androidx.compose.runtime.key(row.id) {
                val remove = row.remove
                if (remove != null) {
                    val dismiss = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            if (v == SwipeToDismissBoxValue.EndToStart) remove()
                            v == SwipeToDismissBoxValue.EndToStart
                        },
                    )
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
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
                        LoggedLine(row, tint, onDelete = remove)
                    }
                } else {
                    LoggedLine(row, tint, onDelete = null)
                }
            }
            if (i < rows.lastIndex) {
                HorizontalDivider(Modifier.padding(start = 30.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun LoggedLine(row: LoggedRow, tint: Color, onDelete: (() -> Unit)?) {
    val deleteLabel = stringResource(R.string.journal_delete)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                if (onDelete != null) {
                    customActions = listOf(
                        androidx.compose.ui.semantics.CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (row.done) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
            contentDescription = null,
            tint = if (row.done) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            row.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            row.value,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
