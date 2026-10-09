package com.noop.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.ProfileAvatarStore
import com.noop.ui.ProfileStore
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import com.noop.ui.m3.ChoiceDialog
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.WheelDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// MARK: - Health Details (twin of iOS ProfileDetailsView) and Heart rate zones (HeartRateZonesPage)
//
// Health's "Health Details": a large photo, then plain rows whose values sit under the title. A row opens
// its picker (date, list or wheel) in a dialog. The same ProfileStore fields the old Settings card wrote;
// stored values stay SI, the wheels show the reader's units and number format.

private enum class DetailsDialog { NAME, BIRTH, SEX, HEIGHT, WEIGHT, WAIST, HR_MAX }

@Composable
internal fun HealthDetailsScreen(open: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = context.resources.configuration.locales[0]
    val profile = remember { ProfileStore.from(context) }
    // ProfileStore is SharedPreferences, not snapshot state: every write bumps this so the rows re-read.
    var rev by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = rev
    fun write(block: () -> Unit) { block(); rev++ }

    var dialog by remember { mutableStateOf<DetailsDialog?>(null) }
    var photoMenu by remember { mutableStateOf(false) }
    val imperial = UnitPrefs.system(context) == UnitSystem.IMPERIAL
    val cm = stringResource(R.string.settings_unit_cm)
    val inch = stringResource(R.string.settings_unit_in)
    val kg = stringResource(R.string.metric_unit_kg)
    val lb = stringResource(R.string.metric_unit_lb)
    val bpm = stringResource(R.string.metric_unit_bpm)
    val notSet = stringResource(R.string.settings_not_set)
    val photoFailed = stringResource(R.string.settings_photo_failed)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ProfileAvatarStore.setAvatarFromUri(context, uri) }
            if (!ok) Toast.makeText(context, photoFailed, Toast.LENGTH_LONG).show()
        }
    }
    fun pickPhoto() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val dob = Instant.ofEpochMilli(profile.dateOfBirthMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val sexTags = listOf("female", "male", "nonbinary")
    val sexLabels = listOf(
        stringResource(R.string.settings_sex_female),
        stringResource(R.string.settings_sex_male),
        stringResource(R.string.settings_sex_other),
    )
    val autoHrMax = stringResource(R.string.settings_hr_max_auto, profile.hrMaxAuto)

    SettingsPage(title = stringResource(R.string.settings_health_details), onBack = onBack) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProfileMonogram(initials = profile.initials, size = 96.dp, textStyle = MaterialTheme.typography.headlineMedium)
                Box {
                    TextButton(onClick = { if (ProfileAvatarStore.hasAvatar) photoMenu = true else pickPhoto() }) {
                        Text(
                            stringResource(
                                if (ProfileAvatarStore.hasAvatar) R.string.settings_change_photo else R.string.settings_add_photo,
                            ),
                        )
                    }
                    DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_choose_photo)) },
                            onClick = { photoMenu = false; pickPhoto() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.l10n_settings_screen_remove_photo_c8f5eda8), color = MaterialTheme.colorScheme.error) },
                            onClick = { photoMenu = false; ProfileAvatarStore.clearAvatar(context) },
                        )
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.settings_name), profile.displayName.ifEmpty { notSet }) { dialog = DetailsDialog.NAME }
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.settings_date_of_birth), HealthDetailsFormat.birth(dob, profile.age, locale)) {
                        dialog = DetailsDialog.BIRTH
                    }
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_sex_e301dd60), sexLabels[sexTags.indexOf(profile.sex).coerceAtLeast(0)]) {
                        dialog = DetailsDialog.SEX
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_height_3f608b49), HealthDetailsFormat.height(profile.heightCm, imperial, cm)) {
                        dialog = DetailsDialog.HEIGHT
                    }
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_weight_69c0b815), HealthDetailsFormat.weight(profile.weightKg, imperial, locale, kg, lb)) {
                        dialog = DetailsDialog.WEIGHT
                    }
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.settings_waist), HealthDetailsFormat.waist(profile.waistCm, imperial, cm, inch) ?: notSet) {
                        dialog = DetailsDialog.WAIST
                    }
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ValueRow(
                        shape,
                        stringResource(R.string.l10n_settings_screen_max_heart_rate_3d4ed858),
                        if (profile.hrMaxOverride > 0) "${profile.hrMaxOverride} $bpm" else autoHrMax,
                    ) { dialog = DetailsDialog.HR_MAX }
                }
                item { shape ->
                    ValueRow(
                        shape,
                        stringResource(R.string.settings_hr_zones),
                        stringResource(if (profile.hasCustomHrZones) R.string.settings_manual else R.string.settings_automatic),
                        chevron = true,
                    ) { open(SettingsRoutes.HR_ZONES) }
                }
            }
        }
    }

    when (dialog) {
        DetailsDialog.NAME -> NameDialog(
            initial = profile.displayName,
            onSave = { write { profile.displayName = it }; dialog = null },
            onDismiss = { dialog = null },
        )
        DetailsDialog.BIRTH -> BirthDialog(
            selected = dob,
            onPick = { day ->
                write { profile.dateOfBirthMillis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DetailsDialog.SEX -> ChoiceDialog(
            title = stringResource(R.string.l10n_settings_screen_sex_e301dd60),
            options = sexLabels,
            selectedIndex = sexTags.indexOf(profile.sex),
            onPick = { i -> write { profile.sex = sexTags[i] }; dialog = null },
            onDismiss = { dialog = null },
        )
        DetailsDialog.HEIGHT -> WheelDialog(
            title = stringResource(R.string.l10n_settings_screen_height_3f608b49),
            options = HealthDetailsFormat.heightOptions(imperial, cm),
            selectedIndex = HealthDetailsFormat.heightIndex(profile.heightCm, imperial),
            onPick = { i -> write { profile.heightCm = HealthDetailsFormat.heightCm(i, imperial) }; dialog = null },
            onDismiss = { dialog = null },
        )
        DetailsDialog.WEIGHT -> WheelDialog(
            title = stringResource(R.string.l10n_settings_screen_weight_69c0b815),
            options = HealthDetailsFormat.weightOptions(imperial, locale, kg, lb),
            selectedIndex = HealthDetailsFormat.weightIndex(profile.weightKg, imperial),
            onPick = { i -> write { profile.weightKg = HealthDetailsFormat.weightKg(i, imperial) }; dialog = null },
            onDismiss = { dialog = null },
        )
        DetailsDialog.WAIST -> WheelDialog(
            title = stringResource(R.string.settings_waist),
            options = HealthDetailsFormat.waistOptions(imperial, notSet, cm, inch),
            selectedIndex = HealthDetailsFormat.waistIndex(profile.waistCm, imperial),
            onPick = { i -> write { profile.waistCm = HealthDetailsFormat.waistCm(i, imperial) }; dialog = null },
            onDismiss = { dialog = null },
        )
        DetailsDialog.HR_MAX -> WheelDialog(
            title = stringResource(R.string.l10n_settings_screen_max_heart_rate_3d4ed858),
            options = HealthDetailsFormat.hrMaxOptions(autoHrMax, bpm),
            selectedIndex = HealthDetailsFormat.hrMaxIndex(profile.hrMaxOverride),
            onPick = { i -> write { profile.hrMaxOverride = HealthDetailsFormat.hrMaxOverride(i) }; dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** The name, typed in a dialog; Save stores it trimmed (blank clears it). */
@Composable
private fun NameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_name)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.text.length <= 60) text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_not_set)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.text) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    )
}

/** The date of birth: a Material date picker limited to ages 13…100, with typed entry available. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDialog(selected: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val range = ProfileStore.dateOfBirthRange(LocalDate.now())
    fun utc(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val first = utc(range.start)
    val last = utc(range.endInclusive)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utc(selected.coerceIn(range.start, range.endInclusive)),
        yearRange = range.start.year..range.endInclusive.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in first..last
            override fun isSelectableYear(year: Int): Boolean = year in range.start.year..range.endInclusive.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis == null) onDismiss() else onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text(stringResource(R.string.summary_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_cancel)) } },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    stringResource(R.string.settings_date_of_birth),
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

// MARK: - Heart rate zones

@Composable
internal fun HeartRateZonesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context) }
    var rev by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = rev
    fun write(block: () -> Unit) { block(); rev++ }
    val bpm = stringResource(R.string.metric_unit_bpm)
    val manual = profile.hasCustomHrZones
    val zones = profile.hrZoneSet.zones
    val thresholds = profile.hrZoneThresholds

    SettingsPage(title = stringResource(R.string.settings_hr_zones), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.settings_automatic),
                        trailing = { androidx.compose.material3.RadioButton(selected = !manual, onClick = null) },
                        role = androidx.compose.ui.semantics.Role.RadioButton,
                        onClick = { if (manual) write { profile.setCustomHrZonesEnabled(false) } },
                    )
                }
                item { shape ->
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.settings_manual),
                        trailing = { androidx.compose.material3.RadioButton(selected = manual, onClick = null) },
                        role = androidx.compose.ui.semantics.Role.RadioButton,
                        onClick = { if (!manual) write { profile.setCustomHrZonesEnabled(true) } },
                    )
                }
            }
        }
        item {
            ListGroup(footer = stringResource(R.string.settings_hr_max_footer, profile.hrMax)) {
                zones.forEach { zone ->
                    item { shape ->
                        val index = zone.number - 1
                        val zoneLabel = stringResource(R.string.settings_zone_n, zone.number)
                        val range = HealthDetailsFormat.zoneRange(zone.number, zone.lower, zone.upper, bpm)
                        ListRow(
                            shape = shape,
                            title = zoneLabel,
                            subtitle = range,
                            leading = {
                                Box(
                                    Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Health.colors.zone(zone.number)),
                                )
                            },
                            trailing = if (manual && thresholds != null && index in thresholds.indices) {
                                {
                                    ZoneStepper(
                                        decreaseLabel = stringResource(R.string.settings_zone_lower, zone.number),
                                        increaseLabel = stringResource(R.string.settings_zone_higher, zone.number),
                                        onDecrease = { write { profile.stepHrZoneThreshold(index, up = false) } },
                                        onIncrease = { write { profile.stepHrZoneThreshold(index, up = true) } },
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

/** The − / + pair that moves one zone's lower bound by a beat (neighbour-aware, in ProfileStore). */
@Composable
private fun ZoneStepper(decreaseLabel: String, increaseLabel: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = onDecrease, modifier = Modifier.semantics { contentDescription = decreaseLabel }) {
            Icon(Icons.Filled.Remove, contentDescription = null)
        }
        FilledTonalIconButton(onClick = onIncrease, modifier = Modifier.semantics { contentDescription = increaseLabel }) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}
