package com.noop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.Baselines
import com.noop.data.StepCalibrationStore
import com.noop.ui.AppViewModel
import com.noop.ui.Destination
import com.noop.ui.EffortScale
import com.noop.ui.HrvWindow
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.ScoringGuideScreen
import com.noop.ui.UnitPrefs
import com.noop.ui.m3.ChoiceDialog
import com.noop.ui.m3.ConfirmDialog
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.SwitchRow
import java.util.Locale

// MARK: - Scores (twin of iOS ScoresSettingsPage)
//
// Effort: the display scale (0-100 or WHOOP's 0-21, display only) and the exponential (Banister) recipe,
// which re-scores the window. Steps: the counter divisor, the Experimental WHOOP 4.0 auto step calibration
// (default off; a switch re-scores) with today's learned divisor, and the WHOOP 4.0 steps estimate. Charge:
// the HRV window (changes the number, so a switch re-scores) and the baseline reset, confirmed first.

private enum class ScoresDialog { EFFORT_SCALE, HRV_WINDOW, RESET }

@Composable
internal fun SettingsScoresScreen(vm: AppViewModel, open: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context) }
    var rev by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = rev
    var dialog by remember { mutableStateOf<ScoresDialog?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    var effortScale by remember { mutableStateOf(UnitPrefs.effortScale(context)) }
    var banister by remember { mutableStateOf(NoopPrefs.banisterEffort(context)) }
    var hrvWindow by remember { mutableStateOf(UnitPrefs.hrvWindow(context)) }
    // Experimental, default off: learn the WHOOP 4.0 ticks-per-step divisor from short raw-accelerometer
    // measurements instead of using the manual one. Changes each day's step total, so a switch re-scores.
    val stepPrefs = remember { NoopPrefs.stepCalibrationPrefs(context) }
    var stepAutoCalibration by remember { mutableStateOf(StepCalibrationStore.isEnabled(stepPrefs)) }

    val scaleLabels = listOf("0-100", "0-21")
    val windowLabels = listOf(stringResource(R.string.settings_hrv_night), stringResource(R.string.settings_hrv_deep_sleep))
    val rescoring = stringResource(R.string.settings_rescoring)
    val baselineReset = stringResource(R.string.settings_baseline_reset)
    val stepsSummary = when {
        profile.stepsManualCoefficient > 0 -> stringResource(R.string.settings_manual)
        profile.stepsCalibrationCoefficient > 0 -> stringResource(
            R.string.settings_steps_auto_confidence,
            stringResource(
                when {
                    profile.stepsCalibrationConfidence < 0.34 -> R.string.settings_confidence_low
                    profile.stepsCalibrationConfidence < 0.67 -> R.string.settings_confidence_medium
                    else -> R.string.settings_confidence_high
                },
            ),
        )
        else -> stringResource(R.string.settings_not_calibrated)
    }
    // Today's learned divisor and how many measurements stand behind the calibration so far. Resolved
    // through the same store the scoring pass reads, with the manual divisor shown in the row above.
    val learnedDivisor = remember(stepAutoCalibration, rev) {
        val today = localToday()
        learnedDivisorSummary(
            StepCalibrationStore.snapshot(manual = profile.stepTicksPerStep, today = today, prefs = stepPrefs),
            today,
        )
    }

    SettingsPage(title = stringResource(R.string.settings_scores), onBack = onBack) {
        item {
            ListGroup(header = stringResource(R.string.settings_effort)) {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_effort_scale_81afa9ef),
                        scaleLabels[if (effortScale == EffortScale.HUNDRED) 0 else 1]) { dialog = ScoresDialog.EFFORT_SCALE }
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_exponential_scale), banister, {
                        banister = it
                        vm.setBanisterEffort(it)
                    })
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_steps)) {
                item { shape ->
                    // Two places: the grid is 0.01 below 1.5 (a WHOOP 4.0 counter runs near 1.26 ticks per step).
                    val value = String.format(Locale.getDefault(), "%.2f", profile.stepTicksPerStep)
                    ListRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_settings_screen_step_calibration_351c09bf),
                        subtitle = value,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val less = stringResource(R.string.settings_decrease)
                                val more = stringResource(R.string.settings_increase)
                                FilledTonalIconButton(
                                    onClick = {
                                        profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = false)
                                        rev++
                                    },
                                    modifier = Modifier.semantics { contentDescription = less },
                                ) { Icon(Icons.Filled.Remove, contentDescription = null) }
                                FilledTonalIconButton(
                                    onClick = {
                                        profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = true)
                                        rev++
                                    },
                                    modifier = Modifier.semantics { contentDescription = more },
                                ) { Icon(Icons.Filled.Add, contentDescription = null) }
                            }
                        },
                    )
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_steps_auto_calibration), stepAutoCalibration, {
                        stepAutoCalibration = it
                        vm.setStepAutoCalibration(it)
                    })
                }
                if (stepAutoCalibration) {
                    item { shape ->
                        ValueRow(shape, stringResource(R.string.settings_steps_learned_divisor_today),
                            learnedDivisor, onClick = null)
                    }
                }
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_steps_estimate_ce7a604d), stepsSummary) {
                        open(Destination.StepsCalibration.route)
                    }
                }
            }
        }
        item {
            ListGroup(header = stringResource(R.string.settings_charge)) {
                item { shape ->
                    ValueRow(shape, stringResource(R.string.l10n_settings_screen_hrv_window_e74320b8),
                        windowLabels[if (hrvWindow == HrvWindow.WHOLE_NIGHT) 0 else 1]) { dialog = ScoresDialog.HRV_WINDOW }
                }
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.settings_reset_baseline), onClick = { dialog = ScoresDialog.RESET })
                }
            }
        }
        item {
            ListGroup {
                item { shape ->
                    ListRow(shape = shape, title = stringResource(R.string.settings_how_scores_work), onClick = { showGuide = true })
                }
            }
        }
    }

    when (dialog) {
        ScoresDialog.EFFORT_SCALE -> ChoiceDialog(
            title = stringResource(R.string.l10n_settings_screen_effort_scale_81afa9ef),
            options = scaleLabels,
            selectedIndex = if (effortScale == EffortScale.HUNDRED) 0 else 1,
            onPick = { i ->
                dialog = null
                effortScale = if (i == 0) EffortScale.HUNDRED else EffortScale.WHOOP
                UnitPrefs.setEffortScale(context, effortScale)
            },
            onDismiss = { dialog = null },
        )
        ScoresDialog.HRV_WINDOW -> ChoiceDialog(
            title = stringResource(R.string.l10n_settings_screen_hrv_window_e74320b8),
            options = windowLabels,
            selectedIndex = if (hrvWindow == HrvWindow.WHOLE_NIGHT) 0 else 1,
            onPick = { i ->
                dialog = null
                val picked = if (i == 0) HrvWindow.WHOLE_NIGHT else HrvWindow.DEEP_SLEEP
                if (picked != hrvWindow) {
                    hrvWindow = picked
                    UnitPrefs.setHrvWindow(context, picked)
                    // #201: a plain re-score re-folds the HRV baseline from the re-scored nights, so the
                    // baseline epoch stays; clearing the watermark makes the next pass run the re-score.
                    NoopPrefs.setAnalyzeWatermark(context, "")
                    vm.syncNow()
                    confirm(context, rescoring)
                }
            },
            onDismiss = { dialog = null },
        )
        ScoresDialog.RESET -> ConfirmDialog(
            title = stringResource(R.string.settings_reset_baseline_title),
            message = stringResource(R.string.settings_reset_baseline_message),
            confirmLabel = stringResource(R.string.settings_reset_baseline),
            destructive = true,
            onConfirm = {
                dialog = null
                // Re-anchors every baseline that feeds Charge from now (both shared epoch keys, whole
                // seconds); no stored day is deleted. The next analyze pass re-folds them.
                val editor = NoopPrefs.of(context).edit()
                Baselines.recalibrateRecoveryBaselines(editor, System.currentTimeMillis() / 1000L)
                editor.apply()
                vm.syncNow()
                confirm(context, baselineReset)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    if (showGuide) {
        FullScreenSheet(onDismiss = { showGuide = false }) { ScoringGuideScreen(onClose = { showGuide = false }) }
    }
}

/** The local day (`yyyy-MM-dd`) the scoring pass calls today. */
private fun localToday(): String {
    val now = System.currentTimeMillis() / 1000L
    return AnalyticsEngine.dayString(now, java.util.TimeZone.getDefault().getOffset(now * 1_000L) / 1_000L)
}

/** "1.26 (3)": [today]'s divisor to two places, then the measurements accepted so far. */
private fun learnedDivisorSummary(snapshot: StepCalibrationStore.Snapshot, today: String): String {
    val divisor = String.format(Locale.getDefault(), "%.2f", snapshot.factor(today))
    return "$divisor (${snapshot.state?.accepted ?: 0})"
}
