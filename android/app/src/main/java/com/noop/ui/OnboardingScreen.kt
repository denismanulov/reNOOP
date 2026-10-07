package com.noop.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.ble.OtherStrapApps
import com.noop.ble.WhoopModel
import com.noop.data.ImportSummary
import com.noop.ingest.HealthConnectImporter
import com.noop.ingest.WhoopCsvImporter
import com.noop.ui.m3.Health
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.M3Dimens
import com.noop.ui.m3.ChevronRight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// MARK: - First run (after the terms gate)
//
// Four steps, five when another strap app can reach the strap, laid out as Pixel's setup wizard pages
// (mockup 12, twin of Swift `OnboardingWizard`): a step bar, a big glyph, a headline with one line under
// it, the step's own content, and a bottom bar with a text button on the left and a filled button on the
// right.
//
//  1 Welcome
//  · Other strap apps    — only when NOOP / WHOOP is installed beside reNOOP and may use Bluetooth: two
//                          apps split the history
//  2 Find your strap     — pick the model, Scan; becomes "Connected" once the strap bonds
//  3 About you           — date of birth / sex / height / weight / units; only answered rows are written
//  4 Bring your history  — optional WHOOP export / Health Connect import; Done finishes
//
// The notification permission is left to the features that need it (their switches ask), and Bluetooth is
// asked for on the first Scan, never on top of a screen that does not explain it. It uses the same
// AppViewModel, BLE client, profile store and importers as the app itself. Rendered alone (the host
// returns before the app shell), so TalkBack never reaches anything behind it.

/** A page of the wizard. [OtherApps] is in the path only when another strap app can reach the strap. */
internal enum class SetupStep { Welcome, OtherApps, Scan, Profile, Import }

/** Where a page sits in the wizard, as the step bar draws it: [number] of [count]. */
internal data class StepPosition(val number: Int, val count: Int)

@Composable
fun OnboardingScreen(viewModel: AppViewModel, onFinished: () -> Unit) {
    val context = LocalContext.current
    // Whether the other-strap-apps step is in the path: another strap app is installed and may use
    // Bluetooth. Read when the wizard opens, so the step bar has its length from the first page, and again
    // on leaving Welcome, where the Swift twin decides it. Most people never see the step.
    var otherApps by rememberSaveable { mutableStateOf(OtherStrapApps.ableToSync(context).isNotEmpty()) }
    // rememberSaveable so a configuration change (rotation, dark mode, font scale, locale) keeps the step.
    var index by rememberSaveable { mutableIntStateOf(0) }
    // Which About You rows the user has answered, kept here so going back and forth doesn't reset them.
    val answers = rememberSaveable(saver = ProfileAnswers.Saver) { ProfileAnswers() }

    val path = OnboardingRules.path(otherApps)
    val step = path[index.coerceIn(0, path.lastIndex)]
    val position = StepPosition(path.indexOf(step) + 1, path.size)

    fun complete() {
        // Onboarding deferred the foreground promotion; do it now if a strap is live.
        viewModel.promoteBackgroundConnectionIfActive()
        onFinished()
    }
    fun next() {
        // Only Welcome may change the path: it is the first page, so no index behind it can shift.
        if (step == SetupStep.Welcome) otherApps = OtherStrapApps.ableToSync(context).isNotEmpty()
        if (index >= OnboardingRules.path(otherApps).lastIndex) complete() else index++
    }

    BackHandler(enabled = index > 0) { index-- }

    when (step) {
        SetupStep.Welcome -> WelcomeStep(position, ::next)
        SetupStep.OtherApps -> OtherAppsStep(position, ::next)
        SetupStep.Scan -> ScanStep(viewModel, position, ::next)
        SetupStep.Profile -> ProfileStep(answers, position, ::next)
        SetupStep.Import -> ImportStep(viewModel, position, ::next)
    }
}

// MARK: - Setup page

/**
 * One setup page, as Pixel's setup wizard draws it: the step bar ([step], none on the terms gate), the
 * glyph in a 64 dp tonal tile, a headline and one line under it, the content, and the bottom bar with
 * [secondary] on the left and [primary] on the right.
 */
@Composable
internal fun SetupPage(
    step: StepPosition?,
    title: String,
    message: String?,
    glyph: @Composable () -> Unit,
    primary: SetupAction?,
    secondary: SetupAction? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (step != null) StepBar(step)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp),
        ) {
            glyph()
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (message != null) {
                Spacer(Modifier.height(16.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(M3Dimens.itemGap), content = content)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (secondary != null) {
                TextButton(onClick = secondary.onClick, enabled = secondary.enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(secondary.label, style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            if (primary != null) {
                Button(onClick = primary.onClick, enabled = primary.enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(primary.label, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** A bottom-bar button of a setup page. */
internal class SetupAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** The step bar: one segment per step, the steps reached filled. */
@Composable
private fun StepBar(step: StepPosition) {
    val label = stringResource(R.string.onboarding_step, step.number, step.count)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 1..step.count) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= step.number) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/** The 64 dp tonal tile a setup page leads with; [positive] and [warning] tint the glyph. */
@Composable
internal fun SetupGlyph(icon: ImageVector, positive: Boolean = false, warning: Boolean = false) {
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                positive -> Health.colors.positive
                warning -> Health.colors.warning
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.size(36.dp),
        )
    }
}

// MARK: - 1 · Welcome

@Composable
private fun WelcomeStep(position: StepPosition, next: () -> Unit) {
    SetupPage(
        step = position,
        title = stringResource(R.string.onboarding_welcome_title),
        message = stringResource(R.string.onboarding_welcome_message),
        glyph = { BrandMark(size = 64.dp) },
        primary = SetupAction(stringResource(R.string.onboarding_get_started), onClick = next),
    )
}

// MARK: - 1½ · Other strap apps

/**
 * Shown only when another app that syncs WHOOP straps is installed and may use Bluetooth
 * ([OtherStrapApps]). The strap keeps one history queue and drops each chunk as soon as any app acks it,
 * so two apps on one strap each end up with holes. Continue is never blocked: the choice is the user's,
 * this step only makes sure it is made knowingly. Twin of Swift `OtherAppsStep`.
 *
 * The Swift step cannot tell whether its advice was followed, since iOS reports only that the other app
 * is installed, and says so in a footnote. Here the list is what Android reports after the advice: an
 * app that was uninstalled, or lost its Nearby devices permission, is gone from it on the way back.
 */
@Composable
private fun OtherAppsStep(position: StepPosition, next: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var others by remember { mutableStateOf(OtherStrapApps.ableToSync(context)) }

    // Back from Settings or the launcher after uninstalling the other app or taking its permission away:
    // read the list again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { others = OtherStrapApps.ableToSync(context) }
    }

    val names = OtherStrapApps.phrase(others, locale)
    // Android 12 made Bluetooth a permission the user can take away again; before it, uninstalling is the
    // only advice that works.
    val revocable = OtherStrapApps.nearbyDevicesRevocable()

    SetupPage(
        step = position,
        title = stringResource(if (names == null) R.string.onboarding_no_other_apps_title else R.string.onboarding_one_app_title),
        message = when {
            names == null -> stringResource(R.string.onboarding_no_other_apps_message)
            revocable -> stringResource(R.string.onboarding_other_apps_message, names)
            else -> stringResource(R.string.onboarding_other_apps_message_uninstall, names)
        },
        glyph = {
            if (names == null) SetupGlyph(Icons.Filled.CheckCircle, positive = true)
            else SetupGlyph(Icons.Filled.Warning, warning = true)
        },
        primary = SetupAction(stringResource(R.string.onboarding_continue), onClick = next),
        secondary = if (others.isEmpty()) null else SetupAction(stringResource(R.string.onboarding_check_again)) {
            others = OtherStrapApps.ableToSync(context)
        },
    ) {
        if (others.isNotEmpty()) {
            ListGroup {
                others.forEach { name ->
                    item { shape ->
                        ListRow(
                            shape = shape,
                            title = name,
                            trailing = {
                                Text(
                                    stringResource(R.string.onboarding_other_app_installed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// MARK: - 2 · Find your strap

@Composable
private fun ScanStep(viewModel: AppViewModel, position: StepPosition, next: () -> Unit) {
    val context = LocalContext.current
    val live by viewModel.live.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()

    var scanning by rememberSaveable { mutableStateOf(false) }
    // Set when a scan ran its calm beat without bonding.
    var notFound by rememberSaveable { mutableStateOf(false) }
    // A new Scan restarts the calm beat.
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    // Bluetooth refused for reNOOP: say so, and send the user where it can be given, rather than blame the strap.
    var asked by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(blePermissionsGranted(context)) }
    val bluetoothDenied = asked && !granted

    // Back from Settings (or the system prompt): read the permission again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { granted = blePermissionsGranted(context) }
    }

    // The explicit user scan, through the same permission gate Live and Devices use. Onboarding connects
    // without promoting the foreground service; the flow promotes it on completion.
    val requestConnect = rememberRequestScan {
        asked = true
        granted = blePermissionsGranted(context)
        if (granted) {
            scanning = true
            notFound = false
            attempt++
            viewModel.connect(promoteService = false)
        }
    }

    // After a calm beat without a bond, say what usually helps.
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        delay(12_000)
        if (!viewModel.live.value.bonded) {
            scanning = false
            notFound = true
        }
    }
    LaunchedEffect(live.bonded) { if (live.bonded) { scanning = false; notFound = false } }

    val message = when (
        OnboardingRules.scanMessage(
            live.bonded, live.batteryPct != null, bluetoothDenied, notFound, selectedModel,
            otherAppSyncing = live.otherAppSyncingAtMs != null,
        )
    ) {
        ScanMessage.BONDED_OTHER_APP -> stringResource(R.string.onboarding_bonded_other_app)
        ScanMessage.BONDED_BATTERY -> stringResource(R.string.onboarding_bonded_battery, live.batteryPct?.toInt() ?: 0)
        ScanMessage.BONDED -> stringResource(R.string.onboarding_bonded)
        ScanMessage.BLUETOOTH_OFF -> stringResource(R.string.onboarding_bt_off)
        ScanMessage.NOT_FOUND_5 -> stringResource(R.string.onboarding_not_found_5)
        ScanMessage.NOT_FOUND_4 -> stringResource(R.string.onboarding_not_found_4)
        ScanMessage.WEAR_IT -> stringResource(R.string.onboarding_wear_it)
    }

    val primary = when {
        live.bonded -> SetupAction(stringResource(R.string.onboarding_continue), onClick = next)
        bluetoothDenied -> SetupAction(stringResource(R.string.onboarding_open_settings)) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)),
            )
        }
        else -> SetupAction(stringResource(R.string.onboarding_scan), enabled = !scanning, onClick = requestConnect)
    }
    // WHOOP leads, but isn't required: other straps and imports live under Devices.
    val secondary = if (live.bonded) null else SetupAction(stringResource(R.string.onboarding_set_up_later), onClick = next)

    SetupPage(
        step = position,
        title = stringResource(if (live.bonded) R.string.onboarding_connected else R.string.onboarding_find_strap),
        message = message,
        glyph = { SetupGlyph(if (live.bonded) Icons.Filled.CheckCircle else Icons.Filled.Watch, positive = live.bonded) },
        primary = primary,
        secondary = secondary,
    ) {
        if (!live.bonded) {
            ListGroup {
                WhoopModel.entries.forEach { model ->
                    item { shape ->
                        val chosen = model == selectedModel
                        ListRow(
                            shape = shape,
                            title = model.displayName,
                            modifier = Modifier.semantics { selected = chosen },
                            leading = {
                                Icon(
                                    if (chosen) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            role = Role.RadioButton,
                            onClick = {
                                // Switching family while not bonded restarts the scan for the new one.
                                if (model != selectedModel) {
                                    viewModel.setSelectedModel(model)
                                    if (scanning || live.connected) {
                                        viewModel.disconnect()
                                        requestConnect()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        // "Searching…" beside a spinner while a scan runs, as setup looks for nearby devices.
        if (!live.bonded && !bluetoothDenied && (scanning || live.connected)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(
                    stringResource(if (live.connected) R.string.onboarding_connecting else R.string.onboarding_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun blePermissionsGranted(context: android.content.Context): Boolean =
    blePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

// MARK: - 3 · About you

/** The About You rows the user has answered. A row nobody touched shows "Not Set" and is never written. */
internal class ProfileAnswers(
    birth: Boolean = false,
    height: Boolean = false,
    weight: Boolean = false,
    sex: String? = null,
) {
    var birth by mutableStateOf(birth)
    var height by mutableStateOf(height)
    var weight by mutableStateOf(weight)
    /** Written on Continue, so picking Not Set again leaves the profile as it was. */
    var sex by mutableStateOf(sex)

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<ProfileAnswers, List<Any?>>(
            save = { listOf(it.birth, it.height, it.weight, it.sex) },
            restore = { ProfileAnswers(it[0] as Boolean, it[1] as Boolean, it[2] as Boolean, it[3] as String?) },
        )
    }
}

private val SEXES = listOf(
    "female" to R.string.onboarding_female,
    "male" to R.string.onboarding_male,
    "nonbinary" to R.string.onboarding_sex_other,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileStep(answers: ProfileAnswers, position: StepPosition, next: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    // ProfileStore wraps SharedPreferences rather than snapshot state; this counter repaints after a write.
    var rev by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = rev
    // The stored profile is always SI. Body measurements and exercise distance follow regional conventions
    // independently; an unset distance choice follows the body choice for compatibility.
    var unitSystem by remember { mutableStateOf(UnitPrefs.system(context)) }
    var distanceRaw by remember {
        mutableStateOf(NoopPrefs.of(context).getString(NoopPrefs.KEY_DISTANCE_UNIT_SYSTEM, "") ?: "")
    }
    val distanceSystem = UnitPrefs.resolveDistance(unitSystem, distanceRaw)
    val locale = LocalConfiguration.current.locales[0]
    var open by remember { mutableStateOf<String?>(null) }
    val notSet = stringResource(R.string.onboarding_not_set)

    SetupPage(
        step = position,
        title = stringResource(R.string.onboarding_about_you),
        message = stringResource(R.string.onboarding_about_you_message),
        // The profile photo when there is one, else the person glyph on the setup tile.
        glyph = { if (ProfileAvatarStore.bitmap != null) ProfileAvatar(size = 64.dp) else SetupGlyph(Icons.Filled.Person) },
        primary = SetupAction(stringResource(R.string.onboarding_continue)) {
            answers.sex?.let { profile.sex = it }
            next()
        },
    ) {
        ListGroup {
            item { shape ->
                // #146: a date of birth, so age advances on its own instead of going stale.
                val dob = Instant.ofEpochMilli(profile.dateOfBirthMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                ValueRow(shape, stringResource(R.string.onboarding_dob),
                    if (answers.birth) dob.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) else notSet) {
                    open = "dob"
                }
            }
            item { shape ->
                Box {
                    ValueRow(shape, stringResource(R.string.onboarding_sex_label),
                        answers.sex?.let { key -> SEXES.firstOrNull { it.first == key }?.let { stringResource(it.second) } } ?: notSet) {
                        open = "sex"
                    }
                    androidx.compose.material3.DropdownMenu(expanded = open == "sex", onDismissRequest = { open = null }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text(notSet) }, onClick = { answers.sex = null; open = null })
                        SEXES.forEach { (key, label) ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { answers.sex = key; open = null })
                        }
                    }
                }
            }
            item { shape ->
                ValueRow(shape, stringResource(R.string.onboarding_height),
                    if (answers.height) UnitFormatter.heightFromCentimeters(profile.heightCm, unitSystem) else notSet) { open = "height" }
            }
            item { shape ->
                ValueRow(shape, stringResource(R.string.onboarding_weight),
                    if (answers.weight) UnitFormatter.massFromKilograms(profile.weightKg, unitSystem) else notSet) { open = "weight" }
            }
        }
        // Two explicit choices: "Metric/Imperial" alone cannot describe mixed conventions such as Canadian
        // pounds with kilometres.
        ListGroup {
            item { shape ->
                MenuValueRow(
                    shape = shape,
                    title = stringResource(R.string.onboarding_body_measurements),
                    value = stringResource(if (unitSystem == UnitSystem.METRIC) R.string.onboarding_metric_units else R.string.onboarding_imperial_units),
                    options = listOf(
                        UnitSystem.METRIC to stringResource(R.string.onboarding_metric_units),
                        UnitSystem.IMPERIAL to stringResource(R.string.onboarding_imperial_units),
                    ),
                    onPick = { unitSystem = it; NoopPrefs.setUnitSystem(context, it) },
                )
            }
            item { shape ->
                MenuValueRow(
                    shape = shape,
                    title = stringResource(R.string.onboarding_distance),
                    value = stringResource(if (distanceSystem == UnitSystem.METRIC) R.string.onboarding_kilometres else R.string.onboarding_miles),
                    options = listOf(
                        UnitSystem.METRIC to stringResource(R.string.onboarding_kilometres),
                        UnitSystem.IMPERIAL to stringResource(R.string.onboarding_miles),
                    ),
                    onPick = { distanceRaw = it.raw; NoopPrefs.setDistanceUnitSystem(context, it) },
                )
            }
        }
    }

    when (open) {
        "dob" -> {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = OnboardingRules.pickerMillisForDob(profile.dateOfBirthMillis),
                yearRange = OnboardingRules.dobYearRange(),
            )
            DatePickerDialog(
                onDismissRequest = { open = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            profile.dateOfBirthMillis = OnboardingRules.dobFromPicker(it)
                            answers.birth = true
                            rev++
                        }
                        open = null
                    }) { Text(stringResource(R.string.onboarding_ok)) }
                },
                dismissButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.onboarding_cancel)) } },
            ) { DatePicker(state = state, showModeToggle = false) }
        }
        "height" -> {
            val steps = remember { (120..230).toList() }
            WheelDialog(
                title = stringResource(R.string.onboarding_height),
                options = steps.map { UnitFormatter.heightFromCentimeters(it.toDouble(), unitSystem) },
                selected = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - profile.heightCm) } ?: 0,
                onPick = { profile.heightCm = steps[it].toDouble(); answers.height = true; rev++; open = null },
                onDismiss = { open = null },
            )
        }
        "weight" -> {
            val steps = remember { generateSequence(30.0) { it + 0.5 }.takeWhile { it <= 250.0001 }.toList() }
            WheelDialog(
                title = stringResource(R.string.onboarding_weight),
                options = steps.map { UnitFormatter.massFromKilograms(it, unitSystem) },
                selected = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - profile.weightKg) } ?: 0,
                onPick = { profile.weightKg = steps[it]; answers.weight = true; rev++; open = null },
                onDismiss = { open = null },
            )
        }
    }
}

@Composable
private fun ValueRow(shape: androidx.compose.ui.graphics.Shape, title: String, value: String, onClick: () -> Unit) {
    ListRow(
        shape = shape,
        title = title,
        trailing = { Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

/** A wheel in a dialog, for the bounded height / weight values. */
@Composable
private fun WheelDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { WheelPicker(options = options, selectedIndex = selected, onSelectedIndexChange = { index = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onPick(index.coerceIn(0, options.lastIndex)) }) { Text(stringResource(R.string.onboarding_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.onboarding_cancel)) } },
    )
}

// MARK: - 4 · Your history (optional)

/** As setup's "Copy apps & data": the sources as rows of one group. */
@Composable
private fun ImportStep(viewModel: AppViewModel, position: StepPosition, next: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // busy stays transient: a configuration change cancels the import coroutine.
    var busy by remember { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val importLabel = stringResource(R.string.onboarding_import_label)
    val importFailed = stringResource(R.string.onboarding_failed)
    val hcDenied = stringResource(R.string.onboarding_health_connect_denied)

    fun runImport(which: String, block: suspend () -> ImportSummary) {
        busy = which
        failed = false
        scope.launch {
            var threw = false
            val summary = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { threw = true; ImportSummary.failure(importLabel, it.message ?: importFailed) }
            }
            // Import & Data Ingest test mode (Test Centre): the same trace the Import page emits.
            emitImportTrace(context, viewModel, summary)
            busy = null
            failed = threw
            status = summary.message
        }
    }

    val whoopLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport("whoop") { WhoopCsvImporter.importZip(context, uri, viewModel.repo) }
    }
    val hcCategories = remember { HealthConnectImporter.selectedCategories(context) }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.any { it in HealthConnectImporter.permissionsFor(hcCategories) }) {
            runImport("hc") { HealthConnectImporter.import(context, viewModel.repo, ProfileStore.from(context).heightCm) }
        } else {
            failed = true
            status = hcDenied
        }
    }
    val hcAvailable = remember { HealthConnectImporter.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }

    fun startHealthConnect() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            // #645: recover a pre-selector user's real scope from what Android already grants.
            HealthConnectImporter.migrateSelectionFromGrants(context, granted)
            val categories = HealthConnectImporter.selectedCategories(context)
            val wanted = HealthConnectImporter.permissionsFor(categories)
            if (granted.any { it in wanted } && !HealthConnectImporter.hasUnaskedPermissions(context, categories)) {
                runImport("hc") { HealthConnectImporter.import(context, viewModel.repo, ProfileStore.from(context).heightCm) }
            } else {
                // Asked ONCE per permission set: a user who declines is not asked again on every visit (#949).
                HealthConnectImporter.markPermissionsAsked(context, categories)
                hcLauncher.launch(wanted)
            }
        }
    }

    SetupPage(
        step = position,
        title = stringResource(R.string.onboarding_history_title),
        message = stringResource(R.string.onboarding_history_message),
        glyph = { SetupGlyph(Icons.Filled.Download) },
        primary = SetupAction(stringResource(R.string.onboarding_done), onClick = next),
    ) {
        ListGroup {
            item { shape ->
                ImportRow(shape, Icons.Filled.FolderZip, stringResource(R.string.onboarding_whoop_export), busy == "whoop", busy == null) {
                    whoopLauncher.launch(arrayOf("*/*"))
                }
            }
            item { shape ->
                ImportRow(shape, Icons.Filled.MonitorHeart, stringResource(R.string.onboarding_health_connect), busy == "hc",
                    busy == null && hcAvailable) { startHealthConnect() }
            }
        }
        val line = status
        if (busy == null && line != null) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ImportRow(
    shape: androidx.compose.ui.graphics.Shape,
    icon: ImageVector,
    title: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListRow(
        shape = shape,
        title = title,
        enabled = enabled,
        leading = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailing = {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else ChevronRight()
        },
        onClick = onClick,
    )
}

// MARK: - Pure rules

/** What the Find Your Strap step says under its title. */
internal enum class ScanMessage { BONDED_OTHER_APP, BONDED_BATTERY, BONDED, BLUETOOTH_OFF, NOT_FOUND_5, NOT_FOUND_4, WEAR_IT }

internal object OnboardingRules {
    /**
     * The wizard's pages in order. The other-strap-apps page follows Welcome only when another strap app
     * can reach the strap ([otherStrapApps]). Twin of Swift `OnboardingWizard.push(after:)`.
     */
    fun path(otherStrapApps: Boolean): List<SetupStep> =
        SetupStep.entries.filter { it != SetupStep.OtherApps || otherStrapApps }

    /**
     * One sentence for where the search stands. #130: a 5.0/MG bonds to one host at a time, so the WHOOP
     * app holding it hides it from a scan; its not-found line says to unpair it there. [otherAppSyncing]:
     * `ForeignOffloadDetector` saw another app pull this strap's history while we are connected. Twin of
     * Swift `ScanStep.message`.
     */
    fun scanMessage(
        bonded: Boolean,
        hasBattery: Boolean,
        bluetoothDenied: Boolean,
        notFound: Boolean,
        model: WhoopModel,
        otherAppSyncing: Boolean = false,
    ): ScanMessage = when {
        bonded && otherAppSyncing -> ScanMessage.BONDED_OTHER_APP
        bonded -> if (hasBattery) ScanMessage.BONDED_BATTERY else ScanMessage.BONDED
        bluetoothDenied -> ScanMessage.BLUETOOTH_OFF
        notFound -> if (model == WhoopModel.WHOOP5_MG) ScanMessage.NOT_FOUND_5 else ScanMessage.NOT_FOUND_4
        else -> ScanMessage.WEAR_IT
    }

    /** The date picker works in UTC midnights; the profile stores a local start of day. */
    fun pickerMillisForDob(dobMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(dobMillis).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** A picked date (UTC midnight) as the local start of that day, kept within the profile's 13–100 years. */
    fun dobFromPicker(utcMillis: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Long {
        val picked = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val clamped = when {
            picked.isAfter(today.minusYears(13)) -> today.minusYears(13)
            picked.isBefore(today.minusYears(100)) -> today.minusYears(100)
            else -> picked
        }
        return clamped.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The years the date-of-birth picker offers: ages 13 to 100. */
    fun dobYearRange(today: LocalDate = LocalDate.now()): IntRange = (today.year - 100)..(today.year - 13)
}

// MARK: - Brand mark

/**
 * The reNOOP mark: an OPEN recovery ring (≈80 % arc, round caps, from 12 o'clock, clockwise) in the
 * Charge colour with a solid core dot. Decorative, so it carries no content label.
 */
@Composable
internal fun BrandMark(size: Dp = 22.dp) {
    val ring = Health.colors.charge
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val core = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.13f
        val radius = (this.size.minDimension - stroke) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val capStroke = Stroke(width = stroke, cap = StrokeCap.Round)
        drawCircle(color = track, radius = radius, center = center, style = capStroke)
        drawArc(color = ring, startAngle = -90f, sweepAngle = 288f, useCenter = false, topLeft = topLeft, size = arcSize, style = capStroke)
        drawCircle(color = core, radius = stroke * 0.62f, center = center)
    }
}
