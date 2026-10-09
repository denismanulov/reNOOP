package com.noop.data

import android.content.Context

/**
 * The one pending "I'm awake" mark: the instant the wearer last tapped it, kept until a scoring pass
 * has had the chance to end that night there ([com.noop.analytics.WakeMarkTrim]).
 *
 * A single value in the shared "noop_prefs" store, like the nap review queue, and deliberately not a
 * database row: it is a request waiting to be applied, not a measurement. The mark's own record stays
 * where Phase 1 put it (the `sleep_mark` series and the strap log). A newer tap replaces an older one.
 */
object WakeMarkStore {
    private const val PREFS = "noop_prefs"
    private const val KEY_PENDING_TS = "noop.pendingWakeMarkTs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The pending mark in unix seconds, or null when there is none. */
    fun pending(context: Context): Long? =
        prefs(context).getLong(KEY_PENDING_TS, 0L).takeIf { it > 0L }

    fun setPending(context: Context, wakeTs: Long) {
        prefs(context).edit().putLong(KEY_PENDING_TS, wakeTs).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_TS).apply()
    }
}
