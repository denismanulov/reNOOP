package com.noop.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.ui.NoopPrefs
import com.noop.ui.m3.ListGroup
import com.noop.ui.m3.SwitchRow

// MARK: - Workouts (twin of iOS WorkoutsSettingsPage)
//
// Keep screen on during a recording (#703, key shared verbatim with iOS), and the Android twin of "Heart
// rate in Dynamic Island": the live heart rate in an ongoing notification while a workout records.
// "Auto-detect workouts" is not here: nothing on Android runs the detector any more (its only caller was
// the removed Today nudge), so a switch would change nothing.

/** The live-workout notification switch; the iOS key (`liveActivity.enabled`), default on as there. */
internal object WorkoutLiveNotificationPrefs {
    const val KEY = "liveActivity.enabled"

    fun enabled(context: Context): Boolean = NoopPrefs.of(context).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        NoopPrefs.of(context).edit().putBoolean(KEY, on).apply()
    }
}

/** The key the live-workout screen reads to hold the screen awake (#703), shared with iOS. */
internal const val WORKOUT_KEEP_SCREEN_ON_KEY = "workoutKeepScreenOn"

@Composable
internal fun SettingsWorkoutsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var keepScreenOn by remember { mutableStateOf(NoopPrefs.of(context).getBoolean(WORKOUT_KEEP_SCREEN_ON_KEY, false)) }
    var liveNotification by remember { mutableStateOf(WorkoutLiveNotificationPrefs.enabled(context)) }

    SettingsPage(title = stringResource(R.string.settings_workouts), onBack = onBack) {
        item {
            ListGroup {
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_keep_screen_on), keepScreenOn, {
                        keepScreenOn = it
                        NoopPrefs.of(context).edit().putBoolean(WORKOUT_KEEP_SCREEN_ON_KEY, it).apply()
                    })
                }
                item { shape ->
                    SwitchRow(shape, stringResource(R.string.settings_hr_in_notification), liveNotification, {
                        liveNotification = it
                        WorkoutLiveNotificationPrefs.set(context, it)
                    })
                }
            }
        }
    }
}
