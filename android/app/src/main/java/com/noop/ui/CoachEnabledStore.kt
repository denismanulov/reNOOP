package com.noop.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The AI Coach master switch (`noop.coachEnabled`), the twin of iOS `@AppStorage("noop.coachEnabled")`.
 * Default ON, so every install that already had Coach keeps it.
 *
 * Snapshot-backed rather than read from prefs at each call site because the surfaces that depend on it
 * (the Browse "Coach" row, the Today launcher card) must RECOMPOSE when it flips: a plain prefs read would
 * be a one-off snapshot, and the row would not appear or vanish until the next process start.
 */
object CoachEnabledStore {
    var enabled by mutableStateOf(true)
        private set

    fun load(ctx: Context) {
        enabled = NoopPrefs.coachEnabled(ctx.applicationContext)
    }

    /**
     * Flip the switch.
     *
     * Reschedules the daily brief here rather than leaving each surface to notice, because the brief is the
     * one Coach surface that runs with no UI attached: it calls a provider from the background and posts a
     * notification. `reschedule` reads this switch first and the brief's own flag second, so off cancels
     * the work and clears the widget's last brief, and on re-arms it only if briefs were switched on.
     */
    fun set(ctx: Context, value: Boolean) {
        enabled = value
        val app = ctx.applicationContext
        NoopPrefs.setCoachEnabled(app, value)
        CoachBriefScheduler.reschedule(app)
    }
}
