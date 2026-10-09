package com.noop.ui

import android.content.Context
import android.text.format.DateFormat
import com.noop.analytics.ClockFormat
import com.noop.analytics.ClockFormatPreference

/**
 * #1821: the app-side reader for the Clock format setting - the Android twin of Apple's `AppClock`.
 *
 * The label helpers in `SleepTimeLabels` take an `is24h` flag rather than a `Context`, deliberately, so
 * they stay pure and unit-testable (`axisEdgeLabel` already worked that way). This is the one place that
 * turns a Context into that flag, so no caller has to know how the preference is stored or that "system"
 * means the device switch rather than the region default.
 */
object ClockPrefs {
    /**
     * The 12/24-hour clock is the system's (iOS ST-4, Denis 6f27c1c6): the in-app Clock choice (#1821) went
     * with the old Settings screen, so a stored choice is no longer read and the device's own switch applies.
     */
    fun uses24Hour(context: Context): Boolean =
        ClockFormat.uses24Hour(ClockFormatPreference.SYSTEM, DateFormat.is24HourFormat(context))
}
