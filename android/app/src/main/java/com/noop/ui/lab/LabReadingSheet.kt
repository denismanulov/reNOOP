package com.noop.ui.lab

import com.noop.ui.m3.SheetBackdropEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.LabBookProjection
import com.noop.analytics.LabMarkerCategory
import com.noop.analytics.MarkerCatalog
import com.noop.analytics.MarkerDefinition
import com.noop.data.LabMarkerRow
import com.noop.ui.ClockPrefs
import com.noop.ui.MarkerUnits
import com.noop.ui.m3.ChevronRight
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.PeriodSegmented
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// MARK: - Add reading (twin of iOS MarkerEditorView)
//
// The Lab Results' "Add reading" sheet, a form with ✕ and ✓: the lab test (picked from the catalogue on a
// searchable page, or a custom name + unit), the value (the marker's canonical unit, with a unit switcher
// where sensible, mmol/L ↔ mg/dL, converted on save), the date, an optional note and an OPTIONAL range typed
// from the user's own report: NEVER a range of NOOP's. Blood pressure is a PAIRED marker (systolic +
// diastolic entered together, stored as two keys) and is validated as it is typed (LB-2).
//
// On save it hands the caller the draft row(s) under the strap device id; the caller persists and reloads.
// NON-CLINICAL: this only captures what the user types.

private const val EDITOR_STRAP_DEVICE_ID = "my-whoop"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabReadingSheet(onDismiss: () -> Unit, onSave: (List<LabMarkerRow>) -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    val translate: (Int) -> String = { context.getString(it) }
    val is24h = remember { ClockPrefs.uses24Hour(context) }

    var selection by remember { mutableStateOf<MarkerDefinition?>(null) }
    var addingCustom by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customUnit by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    var valueText by remember { mutableStateOf("") }
    var diastolicText by remember { mutableStateOf("") }
    var unitChoice by remember { mutableIntStateOf(0) }
    val openedAt = remember { System.currentTimeMillis() }
    var takenAtMillis by remember { mutableLongStateOf(openedAt) }
    var note by remember { mutableStateOf("") }
    var referenceText by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<BpField?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }

    val markerKey = if (addingCustom) MarkerUnits.slug(customName) else selection?.key.orEmpty()
    val isBloodPressure = !addingCustom && markerKey == LabBookProjection.BP_SYSTOLIC_KEY
    val canonicalUnit = if (addingCustom) customUnit else MarkerCatalog.definition(markerKey)?.canonicalUnit.orEmpty()
    val unitOptions = if (addingCustom) listOf(customUnit) else MarkerUnits.options(markerKey, canonicalUnit)
    val activeUnit = unitOptions.getOrElse(unitChoice) { canonicalUnit }

    val hasChanges = selection != null || addingCustom || takenAtMillis != openedAt ||
        listOf(valueText, diastolicText, note, referenceText).any { it.isNotEmpty() }
    val hasChangesNow by rememberUpdatedState(hasChanges)

    // The validated draft row(s): two for blood pressure, one otherwise. Empty while not usable yet.
    val drafts: List<LabMarkerRow> = run {
        val epoch = takenAtMillis / 1000L
        val day = Instant.ofEpochMilli(takenAtMillis).atZone(zone).toLocalDate().toString()
        val trimmedNote = note.trim().ifEmpty { null }
        val trimmedRef = referenceText.trim().ifEmpty { null }
        fun row(key: String, category: LabMarkerCategory, value: Double, unit: String) = LabMarkerRow(
            id = "$key-$epoch-${UUID.randomUUID().toString().take(8)}",
            deviceId = EDITOR_STRAP_DEVICE_ID,
            markerKey = key,
            category = category.raw,
            day = day,
            takenAt = epoch,
            value = value,
            valueText = null,
            unit = unit,
            source = "manual",
            note = trimmedNote,
            referenceText = trimmedRef,
        )
        val sel = selection
        when {
            addingCustom -> {
                val v = LabFormat.parse(valueText)
                if (customName.isBlank() || customUnit.isBlank() || v == null) emptyList()
                else listOf(row(markerKey, LabMarkerCategory.OTHER, v, customUnit.trim()))
            }
            sel == null -> emptyList()
            isBloodPressure -> {
                val sys = LabFormat.parse(valueText)
                val dia = LabFormat.parse(diastolicText)
                if (sys == null || dia == null || bloodPressureErrorRes(sys, dia, null) != null) emptyList()
                else listOf(
                    row(LabBookProjection.BP_SYSTOLIC_KEY, LabMarkerCategory.BLOOD_PRESSURE, sys, "mmHg"),
                    row(LabBookProjection.BP_DIASTOLIC_KEY, LabMarkerCategory.BLOOD_PRESSURE, dia, "mmHg"),
                )
            }
            else -> {
                val raw = LabFormat.parse(valueText)
                if (raw == null) emptyList()
                else listOf(row(markerKey, sel.category, MarkerUnits.toCanonical(markerKey, raw, activeUnit), canonicalUnit))
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // A swipe down with something typed asks before it throws the reading away.
        confirmValueChange = { v ->
            if (v == SheetValue.Hidden && hasChangesNow) {
                askDiscard = true
                false
            } else true
        },
    )
    fun close() { if (hasChanges) askDiscard = true else onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        SheetBackdropEffect(MaterialTheme.colorScheme.surface)
        if (picking) {
            MarkerPicker(
                translate = translate,
                onBack = { picking = false },
                onPick = { def ->
                    selection = def
                    addingCustom = false
                    unitChoice = 0
                    valueText = ""
                    diastolicText = ""
                    picking = false
                },
                onCustom = {
                    selection = null
                    addingCustom = true
                    unitChoice = 0
                    picking = false
                },
            )
            return@ModalBottomSheet
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { close() }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.lab_close))
            }
            Text(
                stringResource(R.string.lab_add_reading),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
            )
            IconButton(onClick = { if (drafts.isNotEmpty()) onSave(drafts) }, enabled = drafts.isNotEmpty()) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.lab_save))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxHeight(0.92f).imePadding(),
            contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap),
        ) {
            item(key = "test") {
                ListGroup {
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = stringResource(R.string.lab_test),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        selection?.let { LabFormat.name(it.key, translate) }
                                            ?: if (addingCustom) stringResource(R.string.lab_category_custom) else "",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    ChevronRight()
                                }
                            },
                            onClick = { picking = true },
                        )
                    }
                }
            }
            if (addingCustom) {
                item(key = "custom") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customName, onValueChange = { customName = it }, singleLine = true,
                            label = { Text(stringResource(R.string.lab_name)) }, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = customUnit, onValueChange = { customUnit = it }, singleLine = true,
                            label = { Text(stringResource(R.string.lab_unit)) }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (selection != null || addingCustom) {
                item(key = "reading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isBloodPressure) {
                            val mmHg = LabFormat.unit("mmHg", translate)
                            NumberField(stringResource(R.string.lab_systolic), valueText, mmHg, { valueText = it }) { on ->
                                focused = if (on) BpField.Systolic else focused.takeIf { it != BpField.Systolic }
                            }
                            NumberField(stringResource(R.string.lab_diastolic), diastolicText, mmHg, { diastolicText = it }) { on ->
                                focused = if (on) BpField.Diastolic else focused.takeIf { it != BpField.Diastolic }
                            }
                        } else {
                            NumberField(stringResource(R.string.lab_value), valueText, LabFormat.unit(activeUnit, translate), { valueText = it }) {}
                            if (unitOptions.size > 1) {
                                // The transparent unit switcher (mmol/L ↔ mg/dL); stored in the canonical unit.
                                PeriodSegmented(
                                    options = unitOptions.map { LabFormat.unit(it, translate) },
                                    selectedIndex = unitChoice,
                                    onSelect = { unitChoice = it },
                                )
                            }
                        }
                        // What is wrong with the pair, else how the value is stored.
                        val bpError = if (isBloodPressure) {
                            bloodPressureErrorRes(LabFormat.parse(valueText), LabFormat.parse(diastolicText), focused)
                        } else null
                        val factor = MarkerUnits.factorToCanonical(markerKey, activeUnit)
                        if (bpError != null) {
                            Text(
                                stringResource(bpError),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        } else if (unitOptions.size > 1 && activeUnit != canonicalUnit && factor != null) {
                            Text(
                                stringResource(R.string.lab_stored_in, LabFormat.unit(canonicalUnit, translate), MarkerUnits.factorLabel(factor)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        ListGroup {
                            item { shape ->
                                ListRow(
                                    shape = shape,
                                    title = stringResource(R.string.lab_date),
                                    trailing = {
                                        Text(
                                            DateTimeFormatter.ofPattern(if (is24h) "d MMM yyyy, HH:mm" else "d MMM yyyy, h:mm a", locale)
                                                .format(Instant.ofEpochMilli(takenAtMillis).atZone(zone)),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = { pickDate = true },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = note, onValueChange = { note = it }, singleLine = true,
                            label = { Text(stringResource(R.string.lab_note)) }, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = referenceText, onValueChange = { referenceText = it }, singleLine = true,
                            label = { Text(stringResource(R.string.lab_range_on_report_field)) }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(takenAtMillis).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // Never a reading from the future.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now(zone))
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val date = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = Instant.ofEpochMilli(takenAtMillis).atZone(zone).toLocalTime()
                        val picked = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                        takenAtMillis = minOf(picked, System.currentTimeMillis())
                    }
                    pickDate = false
                }) { Text(stringResource(R.string.journal_done)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.lab_cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.lab_discard_title)) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; onDismiss() }) { Text(stringResource(R.string.lab_discard)) }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.lab_keep_editing)) } },
        )
    }
}

/** A labelled decimal field with its unit as a suffix. */
@Composable
private fun NumberField(label: String, value: String, unit: String, onValue: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        label = { Text(label) },
        suffix = { if (unit.isNotBlank()) Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
    )
}

/** The marker catalogue as a searchable list, and a custom marker at the foot. */
@Composable
private fun MarkerPicker(
    translate: (Int) -> String,
    onBack: () -> Unit,
    onPick: (MarkerDefinition) -> Unit,
    onCustom: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val q = search.trim().lowercase()
    val filtered = remember(q) {
        if (q.isEmpty()) MarkerCatalog.builtIn
        else MarkerCatalog.builtIn.filter {
            LabFormat.name(it.key, translate).lowercase().contains(q) || it.displayName.lowercase().contains(q) || it.key.contains(q)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
        }
        Text(
            stringResource(R.string.lab_test),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
        )
    }
    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.lab_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = M3Dimens.screenPadding, vertical = 8.dp),
    )
    LazyColumn(
        modifier = Modifier.fillMaxHeight(0.92f).imePadding(),
        contentPadding = PaddingValues(start = M3Dimens.screenPadding, end = M3Dimens.screenPadding, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(M3Dimens.groupGap),
    ) {
        items(filtered, key = { it.key }) { def ->
            val i = filtered.indexOf(def)
            ListRow(
                shape = com.noop.ui.m3.groupItemShape(i, filtered.size),
                title = LabFormat.name(def.key, translate),
                trailing = {
                    Text(
                        LabFormat.unit(def.canonicalUnit, translate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { onPick(def) },
            )
        }
        item(key = "custom-marker") {
            ListRow(
                shape = com.noop.ui.m3.groupItemShape(0, 1),
                title = stringResource(R.string.lab_custom_marker),
                titleColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = M3Dimens.itemGap),
                onClick = onCustom,
            )
        }
    }
}
