package com.noop.ui.journal

import com.noop.ui.m3.SheetBackdropEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import com.noop.ui.TypedNumber
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.JournalEntry
import com.noop.ui.JournalCatalogItem
import com.noop.ui.JournalGroup
import com.noop.ui.JournalKind
import com.noop.ui.addCustomJournalItem
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.M3Dimens
import com.noop.ui.normJournalKey
import com.noop.ui.removeJournalItem
import com.noop.ui.renameJournalItem
import com.noop.ui.setJournalItemGroup
import com.noop.ui.setJournalItemKind

// MARK: - Habits sheet (twin of iOS JournalHabitsSheet)
//
// The day's habits as a form, one row per journal item, grouped: Yes/No or a number. A long press on a row
// renames it, moves it to another group, switches it between Yes/No and a number, or hides it (built-in) /
// deletes it (custom). "New item" adds a custom one. Every edit of the catalog keeps the stored question
// key, so logged and imported history stays joined.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HabitsSheet(
    items: List<JournalCatalogItem>,
    catalog: List<JournalCatalogItem>,
    answers: Map<String, JournalEntry>,
    translate: (Int) -> String,
    onCatalog: (List<JournalCatalogItem>) -> Unit,
    onAnswer: (question: String, yes: Boolean) -> Unit,
    onNumeric: (question: String, value: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf<JournalCatalogItem?>(null) }
    var draft by remember { mutableStateOf("") }
    var draftNumeric by remember { mutableStateOf(false) }
    // Answers chosen while the sheet is open, so a tap shows at once (the page reloads on close).
    var local by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val byKey = remember(answers) { answers.mapKeys { normJournalKey(it.key) } }

    fun add() {
        val t = draft.trim()
        if (t.isEmpty()) return
        onCatalog(addCustomJournalItem(catalog, t, if (draftNumeric) JournalKind.Numeric(null) else JournalKind.Bool, JournalGroup.Other))
        draft = ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        SheetBackdropEffect(MaterialTheme.colorScheme.surface)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.journal_habits),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.journal_done)) }
        }
        LazyColumn(
            modifier = Modifier.imePadding(),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (group in JournalGroup.displayOrder) {
                val rows = items.filter { it.group == group }
                    .sortedWith(compareBy({ it.sortIndex }, { journalItemLabel(it, translate) }))
                if (rows.isEmpty()) continue
                item(key = "g:" + group.name) {
                    ListGroup(header = stringResource(journalGroupTitleRes(group))) {
                        for (row in rows) {
                            item { shape ->
                                val key = normJournalKey(row.canonical)
                                val stored = byKey[key]
                                HabitRow(
                                    item = row,
                                    label = journalItemLabel(row, translate),
                                    shape = shape,
                                    answer = local[key] ?: stored?.takeIf { it.numericValue == null }?.answeredYes,
                                    number = stored?.numericValue,
                                    onAnswer = { yes ->
                                        local = local + (key to yes)
                                        onAnswer(stored?.question ?: row.canonical, yes)
                                    },
                                    onNumeric = { v -> onNumeric(stored?.question ?: row.canonical, v) },
                                    onRename = { renaming = row },
                                    onGroup = { g -> onCatalog(setJournalItemGroup(catalog, row.canonical, g)) },
                                    onKind = { k -> onCatalog(setJournalItemKind(catalog, row.canonical, k)) },
                                    onRemove = { onCatalog(removeJournalItem(catalog, row.canonical)) },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "new") {
                ListGroup(header = stringResource(R.string.journal_new_item)) {
                    item { shape ->
                        Column(
                            Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                label = { Text(stringResource(R.string.journal_new_item)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { add() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(R.string.journal_type),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(false, true).forEachIndexed { i, numeric ->
                                    SegmentedButton(
                                        selected = draftNumeric == numeric,
                                        onClick = { draftNumeric = numeric },
                                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                                    ) {
                                        Text(stringResource(if (numeric) R.string.journal_number else R.string.journal_yes_no))
                                    }
                                }
                            }
                            FilledTonalButton(
                                onClick = { add() },
                                enabled = draft.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.journal_add)) }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { item ->
        var name by remember(item) { mutableStateOf(journalItemLabel(item, translate)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.journal_rename)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    onCatalog(renameJournalItem(catalog, item.canonical, name))
                    renaming = null
                }) { Text(stringResource(R.string.journal_done)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.journal_cancel)) } },
        )
    }
}

/** One habit: its name, then Yes / No (unanswered until one is picked) or a number with its unit. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HabitRow(
    item: JournalCatalogItem,
    label: String,
    shape: Shape,
    answer: Boolean?,
    number: Double?,
    onAnswer: (Boolean) -> Unit,
    onNumeric: (Double?) -> Unit,
    onRename: () -> Unit,
    onGroup: (JournalGroup) -> Unit,
    onKind: (JournalKind) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf(false) }
    val renameLabel = stringResource(R.string.journal_rename)
    val kindLabel = stringResource(if (item.kind.isNumeric) R.string.journal_change_to_yes_no else R.string.journal_change_to_number)
    val removeLabel = stringResource(if (item.custom) R.string.journal_delete else R.string.journal_hide)
    val toggleKind = { onKind(if (item.kind.isNumeric) JournalKind.Bool else JournalKind.Numeric(null)) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = M3Dimens.rowMinHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(renameLabel) { onRename(); true },
                        CustomAccessibilityAction(kindLabel) { toggleKind(); true },
                        CustomAccessibilityAction(removeLabel) { onRemove(); true },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (item.kind.isNumeric) {
                NumberField(number, item.kind.unitLabel, onNumeric)
            } else {
                // At least 148 dp, wider when the two answers need it at a large font size.
                SingleChoiceSegmentedButtonRow(Modifier.widthIn(min = 148.dp)) {
                    listOf(true, false).forEachIndexed { i, yes ->
                        SegmentedButton(
                            selected = answer == yes,
                            onClick = { onAnswer(yes) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            icon = {},
                        ) { Text(stringResource(if (yes) R.string.journal_yes else R.string.journal_no), maxLines = 1) }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false; groups = false }) {
            if (!groups) {
                DropdownMenuItem(text = { Text(renameLabel) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.journal_group)) }, onClick = { groups = true })
                DropdownMenuItem(text = { Text(kindLabel) }, onClick = { menu = false; toggleKind() })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onRemove() },
                )
            } else {
                for (g in JournalGroup.displayOrder) {
                    DropdownMenuItem(
                        text = { Text(stringResource(journalGroupTitleRes(g))) },
                        onClick = { menu = false; groups = false; onGroup(g) },
                    )
                }
            }
        }
    }
}

/** A decimal field for a numeric habit; a blank field clears the day's value. */
@Composable
private fun NumberField(value: Double?, unit: String?, onValue: (Double?) -> Unit) {
    // Not keyed on [value]: a save reloads the page, and the field must keep what is being typed ("3,").
    var text by remember { mutableStateOf(value?.let { journalNumber(it) } ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                val trimmed = t.trim()
                if (trimmed.isEmpty()) onValue(null)
                else TypedNumber.parse(trimmed)?.let(onValue)
            },
            placeholder = { Text("—") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            // Wide enough for a few digits at the reader's font size.
            modifier = Modifier.width(96.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)),
        )
        if (!unit.isNullOrBlank()) {
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
