package com.noop.ui.settings

import android.content.SharedPreferences
import kotlin.math.roundToInt

// MARK: - Pure logic behind the Settings tree (unit-tested)

/**
 * The Developer page's unlock (iOS ST-9): hidden until the version row in About reNOOP is tapped [TAPS]
 * times, as Android's build number unlocks its developer options. Stored, so the page stays once found.
 * Same key as the iOS `DeveloperUnlock.key`, in the `noop_prefs` file.
 */
internal object DeveloperUnlock {
    const val KEY = "noop.developerUnlocked"
    const val TAPS = 7

    fun isUnlocked(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, false)

    /** The outcome of one tap: the running count, and whether this tap is the one that unlocked. */
    data class Tap(val count: Int, val unlockedNow: Boolean)

    /**
     * Count one tap on the version row. Once unlocked (now or before) taps change nothing; the [TAPS]th
     * tap writes the unlock. [tapsSoFar] is the screen's own counter, so leaving About starts over.
     */
    fun tap(prefs: SharedPreferences, tapsSoFar: Int): Tap {
        if (isUnlocked(prefs)) return Tap(tapsSoFar, unlockedNow = false)
        val next = tapsSoFar + 1
        if (next >= TAPS) {
            prefs.edit().putBoolean(KEY, true).apply()
            return Tap(next, unlockedNow = true)
        }
        return Tap(next, unlockedNow = false)
    }
}

/** How the root's strap row words the active strap's link. */
internal enum class StrapLink { CONNECTED, SEARCHING, NOT_CONNECTED }

/** Connected wins over a scan in flight (a re-scan of a live link still has a link). */
internal fun strapLink(connected: Boolean, scanning: Boolean): StrapLink = when {
    connected -> StrapLink.CONNECTED
    scanning -> StrapLink.SEARCHING
    else -> StrapLink.NOT_CONNECTED
}

/** The battery shown beside "Connected", only while connected and known. */
internal fun strapBatteryPercent(connected: Boolean, batteryPct: Double?): Int? =
    if (connected && batteryPct != null) batteryPct.roundToInt().coerceIn(0, 100) else null

/** What the Language row does on this Android version. */
internal enum class LanguageRowMode {
    /** Android 13+: the system's per-app language page owns the choice. */
    SYSTEM_PER_APP,
    /** Older Android: the in-app picker, the only way to pick a language other than the phone's. */
    IN_APP_PICKER,
}

internal fun languageRowMode(sdkInt: Int): LanguageRowMode =
    if (sdkInt >= 33) LanguageRowMode.SYSTEM_PER_APP else LanguageRowMode.IN_APP_PICKER
