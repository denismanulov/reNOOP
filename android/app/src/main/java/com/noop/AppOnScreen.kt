package com.noop

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether one of this app's activities is in front of the wearer right now: the Android reading of the
 * iOS `UIApplication.shared.applicationState == .active` that WHOOP 4.0 step auto-calibration checks
 * before a measurement. A wearer looking at the app holds that arm still, and the ticks-per-step factor
 * measured so is not the one the day's other steps were walked with (see
 * [com.noop.ble.StepAutoCalibrator.offloadSettled]).
 *
 * "In front" is "resumed": between `onResume` and `onPause`. The screen going off pauses the activity,
 * so a locked phone in a pocket reads as off screen, as it does on iOS. Registered once by
 * [NoopApplication] before any activity exists (no lifecycle-process dependency is needed for this).
 * The callbacks arrive on the main thread; the flag may be read from any.
 */
object AppOnScreen : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed = 0

    val isOnScreen: Boolean get() = resumed > 0

    internal fun noteResumed() {
        resumed += 1
    }

    internal fun notePaused() {
        if (resumed > 0) resumed -= 1
    }

    override fun onActivityResumed(activity: Activity) = noteResumed()
    override fun onActivityPaused(activity: Activity) = notePaused()
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
