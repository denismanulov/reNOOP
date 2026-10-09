package com.noop.data

import com.noop.analytics.StepCalibration

/**
 * Persistence for the learned WHOOP 4.0 ticks-per-step factor ([StepCalibration]) and the one place the
 * app resolves "which divisor does this day use". Kotlin twin of the Swift `StepCalibrationStore`
 * (Strand/Data/StepCalibrationStore.swift), with the same key names.
 *
 * The feature is an Experimental opt-in, default off. While it is off every day uses the manual Step
 * calibration divisor, exactly as before, whatever has been learned, and nothing is read from or written
 * to the stored state. The state lives in preferences: it is small, re-learned within days if lost, and
 * deliberately not part of the `.noopbak` contract.
 *
 * ONE DIFFERENCE FROM SWIFT. There every reader and the writer run on the main actor. Here the scoring
 * pass reads on a worker thread while a measurement is recorded on the main one, so each
 * read-modify-write of the stored state happens under [lock]: without it a pass that moves the running
 * day forward could write back a state read before a measurement landed, and lose that measurement.
 */
object StepCalibrationStore {
    const val ENABLED_KEY = "stepAutoCalibration.enabled"
    const val STATE_KEY = "stepAutoCalibration.state"

    private val lock = Any()

    fun isEnabled(prefs: KeyValuePrefs): Boolean = prefs.getBoolean(ENABLED_KEY)

    /** The Settings switch. Swift writes the same key through `@AppStorage`. */
    fun setEnabled(prefs: KeyValuePrefs, enabled: Boolean) = prefs.putBoolean(ENABLED_KEY, enabled)

    /** The stored state, or an empty one when nothing readable is stored. */
    fun load(prefs: KeyValuePrefs): StepCalibration.State =
        prefs.getString(STATE_KEY)?.let { StepCalibration.State.fromJson(it) } ?: StepCalibration.State()

    fun save(state: StepCalibration.State, prefs: KeyValuePrefs) {
        val json = state.toJson() ?: return
        prefs.putString(STATE_KEY, json)
    }

    /** The divisors of one scoring pass, resolved once so every reader of the pass sees the same values. */
    data class Snapshot(val state: StepCalibration.State?, val manual: Double) {
        /** Ticks per step for [day] (`yyyy-MM-dd`, local). */
        fun factor(day: String): Double {
            val learned = state ?: return manual
            return StepCalibration.factor(learned, day, manual)
        }
    }

    /**
     * [today] moves the running day forward first, so a day that ended without a measurement is frozen
     * at the factor it ended with instead of following later learning.
     */
    fun snapshot(manual: Double, today: String, prefs: KeyValuePrefs): Snapshot {
        if (!isEnabled(prefs)) return Snapshot(state = null, manual = manual)
        synchronized(lock) {
            val stored = load(prefs)
            val advanced = StepCalibration.advanced(stored, to = today)
            if (advanced != stored) save(advanced, prefs)
            return Snapshot(state = advanced, manual = manual)
        }
    }

    /**
     * Adds one accepted measurement taken on [day] and returns the state it left. Swift does the same
     * load, `StepCalibration.recorded`, save in `StepAutoCalibrator.resolvePending`; it is a function
     * here so the three steps share [lock] with [snapshot].
     */
    fun record(prefs: KeyValuePrefs, day: String, steps: Double, ticks: Double): StepCalibration.State =
        synchronized(lock) {
            val state = StepCalibration.recorded(load(prefs), day = day, steps = steps, ticks = ticks)
            save(state, prefs)
            state
        }
}
