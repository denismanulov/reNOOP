package com.noop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.NapCandidate
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.ListRow
import com.noop.ui.m3.SwitchRow
import com.noop.ui.settings.SettingsPage
import com.noop.ui.settings.clockLabel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// MARK: - Automations (Settings > Automations)
//
// What reNOOP does by itself from the strap's data. The strap's own settings (double-tap, the in-session
// haptics, HR-zone coaching) each have ONE home, Devices > Strap; the reminders and alerts live under
// Settings > Notifications; the alarms under Sleep Schedule. What is left here is the one automation with
// no other home: on-device nap detection and its review queue. The frame and rows are the ones every
// Settings page uses.

/**
 * Nap detection (PR #569): a switch and the review queue. Detection runs on the offload hook
 * (WhoopBleClient.maybeDetectNaps → the pure NapDetector); a confident nap is queued in NapStore and shown
 * here for the reader to KEEP (it becomes a manual nap session, the #508 path) or SKIP. Nothing is written
 * without that tap, and an inconclusive window queues nothing.
 */
@Composable
fun AutomationsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val enabled by viewModel.napDetectionEnabled.collectAsStateWithLifecycle()
    // The queue is written from the BLE layer and is not a flow: read it on entry and after each action.
    // Empty until that read returns, because the read also drops what overlaps recorded sleep.
    var pending by remember { mutableStateOf(emptyList<NapCandidate>()) }
    LaunchedEffect(Unit) { pending = viewModel.pendingNaps() }
    val is24h = ClockPrefs.uses24Hour(context)

    SettingsPage(title = stringResource(R.string.nav_automations), onBack = onBack) {
        item {
            ListGroup(footer = stringResource(R.string.automations_nap_footer)) {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.l10n_automations_screen_detect_short_naps_bbfd136d),
                        checked = enabled,
                        onCheckedChange = {
                            viewModel.setNapDetectionEnabled(it)
                            if (it) scope.launch { pending = viewModel.pendingNaps() }
                        },
                    )
                }
            }
        }
        if (enabled) {
            item {
                ListGroup(
                    header = stringResource(R.string.automations_nap_review),
                    footer = if (pending.isEmpty()) {
                        stringResource(R.string.l10n_automations_screen_no_naps_to_review_detected_naps_2e82e9fc)
                    } else null,
                ) {
                    pending.forEach { nap ->
                        item { shape ->
                            NapReviewRow(
                                shape = shape,
                                nap = nap,
                                is24h = is24h,
                                locale = locale,
                                onKeep = { scope.launch { pending = viewModel.acceptDetectedNap(nap) } },
                                onSkip = { pending = viewModel.dismissDetectedNap(nap) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One nap to review: when it was and how long, its average heart rate when known, then Keep and Skip. */
@Composable
private fun NapReviewRow(
    shape: androidx.compose.ui.graphics.Shape,
    nap: NapCandidate,
    is24h: Boolean,
    locale: Locale,
    onKeep: () -> Unit,
    onSkip: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    fun clock(ts: Long): String {
        val t = Instant.ofEpochSecond(ts).atZone(zone).toLocalTime()
        return clockLabel(t.hour * 60 + t.minute, is24h, locale)
    }
    val window = stringResource(R.string.automations_nap_window, clock(nap.start), clock(nap.end), (nap.durationS / 60).toInt())
    ListRow(
        shape = shape,
        title = window,
        subtitle = nap.meanHr?.let { stringResource(R.string.automations_nap_mean_hr, it) },
        trailing = {
            Row {
                IconButton(onClick = onKeep) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.automations_nap_keep),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onSkip) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.automations_nap_skip),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * Weekday selector for the smart alarm (#539). One tappable circle per weekday, Monday-first. An empty
 * [selected] set means "every day" (all circles read as on). Mirrors the macOS AutomationsView picker.
 */
// internal (not private) so SmartAlarmScreen (the consolidated Alarms surface, #766) can reuse it.
/** Calendar.DAY_OF_WEEK numbers laid out Monday-first (Mon…Sun → 2,3,4,5,6,7,1). */
private val SMART_ALARM_WEEKDAY_ORDER = intArrayOf(2, 3, 4, 5, 6, 7, 1)

/** A day reads as "on" when the set is empty (= every day) or explicitly contains it. Pure for tests. */
internal fun smartAlarmWeekdayIsSelected(dow: Int, days: Set<Int>): Boolean =
    days.isEmpty() || days.contains(dow)

/**
 * Toggle one weekday, normalising "every day" at both ends so the empty set always means every day.
 * Pure + side-effect-free for unit tests. Pulling a day out of the implicit "every day" expands to the
 * explicit other six; selecting the seventh collapses back to the empty "every day" set. Mirrors macOS
 * `AutomationsView.toggledWeekday`.
 */
internal fun toggledSmartAlarmWeekday(dow: Int, days: Set<Int>): Set<Int> {
    val next: MutableSet<Int> = when {
        days.isEmpty() -> (1..7).toMutableSet().also { it.remove(dow) }
        days.contains(dow) -> days.toMutableSet().also { it.remove(dow) }
        else -> days.toMutableSet().also { it.add(dow) }
    }
    return if (next.size == 7) emptySet() else next
}

/** Human-readable summary of the selection. Pure for tests. Mirrors macOS `weekdaySummary`. */
internal fun smartAlarmWeekdaySummary(days: Set<Int>): String = when {
    days.isEmpty() || days.size == 7 -> "Every day"
    days == setOf(2, 3, 4, 5, 6) -> "Weekdays"
    days == setOf(1, 7) -> "Weekends"
    else -> SMART_ALARM_WEEKDAY_ORDER.filter { days.contains(it) }
        .joinToString(", ") { smartAlarmWeekdayName(it) }
}

private fun smartAlarmWeekdayInitial(dow: Int): String = when (dow) {
    1 -> "S"; 2 -> "M"; 3 -> "T"; 4 -> "W"; 5 -> "T"; 6 -> "F"; 7 -> "S"; else -> "?"
}

private fun smartAlarmWeekdayName(dow: Int): String = when (dow) {
    1 -> "Sun"; 2 -> "Mon"; 3 -> "Tue"; 4 -> "Wed"; 5 -> "Thu"; 6 -> "Fri"; 7 -> "Sat"; else -> "?"
}

