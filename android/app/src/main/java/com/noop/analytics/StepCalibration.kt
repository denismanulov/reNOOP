package com.noop.analytics

import org.json.JSONObject

/**
 * How many firmware step-counter ticks one real step costs, learned from short paired measurements and
 * kept per day.
 *
 * Kotlin twin of the Swift `StepCalibration` (StrandAnalytics/StepCalibration.swift): the same sums in
 * the same order, pinned by `StepCalibrationParityOracleTest` against the Swift build's own output.
 *
 * WHY A LEARNED FACTOR. The WHOOP 4.0 firmware counter is not a 1:1 step count, and its excess is not
 * constant: on one strap and wearer (2026-10-03) it ran 1.14, 1.20, 1.23, 1.26 and 1.27 ticks per step
 * with the arm free and 1.49 with the strap hand in a pocket, at the same walking pace. Nothing in the
 * 1 Hz history separates those cases, so no fixed divisor is right. A measurement pairs the ticks the
 * counter added over some seconds with the steps [GaitCadence] read from the raw accelerometer over the
 * same seconds.
 *
 * THE TWO LAYERS.
 * - A day's own measurements are summed (ticks and steps separately, so a long measurement weighs more
 *   than a short one) and pulled toward the long-run factor with the weight of [PRIOR_STEPS] steps. A day
 *   spent with hands in pockets is therefore scaled mostly by that day's own evidence.
 * - The long-run factor is an exponential moving average over measured days, again of the two sums. It
 *   starts from zero on both sums, so their ratio is an unbiased weighted mean from the first day on and
 *   needs no seed value.
 *
 * A finished day keeps the factor it ended with ([State.frozen]), so later learning never rewrites
 * history. Until anything has been measured, the caller's manual divisor applies unchanged.
 *
 * THE RATIO BAND is what makes an unattended measurement safe to accept. [GaitCadence] fails in one way
 * that matters, by an octave, and an octave error moves the ratio by a factor of two: far outside a band
 * that is itself narrower than a factor of two. Such a measurement is refused, never averaged in.
 *
 * Days are `yyyy-MM-dd` keys and are ordered as strings. Swift orders strings by Unicode scalar and
 * Kotlin by UTF-16 code unit; for the ASCII keys used here the two orders are the same.
 */
object StepCalibration {

    /** Ticks per step a measurement must fall in to be accepted. Observed: 1.14 to 1.49. */
    val RATIO_BAND: ClosedFloatingPointRange<Double> = 1.0..1.7

    /** A measurement shorter than this many steps is too coarse: one tick either way is over 3%. */
    const val MINIMUM_STEPS = 30.0

    /** Weight of one measured day in the long-run average. */
    const val DAY_ALPHA = 0.2

    /** Weight of the long-run factor inside a day's own factor, in steps. */
    const val PRIOR_STEPS = 120.0

    /** Finished days remembered. Older days fall back to the manual divisor if they are ever re-scored. */
    const val FROZEN_DAY_LIMIT = 400

    data class State(
        /**
         * Exponential moving average of measured ticks per day; zero, like [emaSteps], until a measured
         * day has finished.
         */
        val emaTicks: Double = 0.0,
        val emaSteps: Double = 0.0,
        /** The day (`yyyy-MM-dd`) the running sums belong to. */
        val day: String? = null,
        val dayTicks: Double = 0.0,
        val daySteps: Double = 0.0,
        /** Factor each finished day ended with. */
        val frozen: Map<String, Double> = emptyMap(),
        /** Measurements accepted so far, for diagnostics. */
        val accepted: Int = 0,
    ) {
        /**
         * The state as a JSON object under the Swift type's `Codable` keys, so either platform reads what
         * the other wrote. Null when a value has no JSON form (NaN or infinite), where Swift's encoder throws.
         */
        fun toJson(): String? = try {
            val days = JSONObject()
            for ((key, value) in frozen) days.put(key, value)
            val json = JSONObject()
                .put("emaTicks", emaTicks)
                .put("emaSteps", emaSteps)
                .put("dayTicks", dayTicks)
                .put("daySteps", daySteps)
                .put("frozen", days)
                .put("accepted", accepted)
            if (day != null) json.put("day", day)
            json.toString()
        } catch (_: Exception) {
            null
        }

        companion object {
            /**
             * Reads what [toJson] or Swift's `JSONEncoder` wrote. Null when [json] is not such an object:
             * like Swift's synthesized decoder, every key but `day` is required and must hold a number
             * (`frozen` an object of numbers, `accepted` a whole number).
             */
            fun fromJson(json: String): State? = try {
                val root = JSONObject(json)
                val days = root.opt("frozen") as? JSONObject
                val frozen = HashMap<String, Double>()
                var intact = days != null
                if (days != null) {
                    for (key in days.keys()) {
                        val value = number(days, key)
                        if (value == null) intact = false else frozen[key] = value
                    }
                }
                val emaTicks = number(root, "emaTicks")
                val emaSteps = number(root, "emaSteps")
                val dayTicks = number(root, "dayTicks")
                val daySteps = number(root, "daySteps")
                val accepted = number(root, "accepted")
                val day = if (root.isNull("day")) null else root.opt("day")
                if (!intact || emaTicks == null || emaSteps == null || dayTicks == null || daySteps == null ||
                    accepted == null || accepted != accepted.toInt().toDouble() || (day != null && day !is String)
                ) {
                    null
                } else {
                    State(
                        emaTicks = emaTicks, emaSteps = emaSteps, day = day as String?,
                        dayTicks = dayTicks, daySteps = daySteps, frozen = frozen, accepted = accepted.toInt(),
                    )
                }
            } catch (_: Exception) {
                null
            }

            private fun number(json: JSONObject, key: String): Double? = (json.opt(key) as? Number)?.toDouble()
        }
    }

    /**
     * Whether a measurement of [steps] detected steps against [ticks] counter ticks may be averaged in.
     * Swift twin: `StepCalibration.accepts(steps:ticks:)`.
     */
    fun accepts(steps: Double, ticks: Double): Boolean {
        if (!(steps >= MINIMUM_STEPS && ticks > 0)) return false
        return (ticks / steps) in RATIO_BAND
    }

    /**
     * The long-run factor, or null while no measured day has finished.
     * Swift twin: `StepCalibration.longRunFactor(_:)`.
     */
    fun longRunFactor(state: State): Double? =
        if (state.emaSteps > 0) state.emaTicks / state.emaSteps else null

    /** Ticks per step to divide [day]'s counter total by. Swift twin: `StepCalibration.factor(_:day:manual:)`. */
    fun factor(state: State, day: String, manual: Double): Double {
        state.frozen[day]?.let { return it }
        if (day != state.day) return longRunFactorIfLater(state, day) ?: manual
        return runningDayFactor(state) ?: manual
    }

    /**
     * Moves the running day forward to [to], freezing the day it leaves and folding that day's sums
     * into the long-run average. A day that is not later than the running one changes nothing.
     *
     * Swift twin: `StepCalibration.advanced(_:to:)`.
     */
    fun advanced(state: State, to: String): State {
        val current = state.day ?: return state.copy(day = to)
        if (!(to > current)) return state
        val frozen = HashMap(state.frozen)
        runningDayFactor(state)?.let { frozen[current] = it }
        var emaTicks = state.emaTicks
        var emaSteps = state.emaSteps
        if (state.daySteps > 0) {
            emaTicks = (1 - DAY_ALPHA) * state.emaTicks + DAY_ALPHA * state.dayTicks
            emaSteps = (1 - DAY_ALPHA) * state.emaSteps + DAY_ALPHA * state.daySteps
        }
        if (frozen.size > FROZEN_DAY_LIMIT) {
            for (stale in frozen.keys.sorted().take(frozen.size - FROZEN_DAY_LIMIT)) frozen.remove(stale)
        }
        return state.copy(
            emaTicks = emaTicks, emaSteps = emaSteps, day = to, dayTicks = 0.0, daySteps = 0.0, frozen = frozen,
        )
    }

    /**
     * Adds one accepted measurement taken on [day]. A measurement for a day that has already been left
     * (a burst just before midnight, paired after it) is dropped: its day is frozen.
     *
     * Swift twin: `StepCalibration.recorded(_:day:steps:ticks:)`.
     */
    fun recorded(state: State, day: String, steps: Double, ticks: Double): State {
        if (!accepts(steps, ticks)) return state
        val next = advanced(state, to = day)
        if (next.day != day) return next
        return next.copy(
            dayTicks = next.dayTicks + ticks, daySteps = next.daySteps + steps, accepted = next.accepted + 1,
        )
    }

    /**
     * The running day's factor: its own sums pulled toward the long-run factor. Null when there is
     * neither a measurement today nor a long-run factor.
     *
     * Swift twin: `StepCalibration.runningDayFactor(_:)`.
     */
    private fun runningDayFactor(state: State): Double? {
        val longRun = longRunFactor(state)
        if (!(state.daySteps > 0)) return longRun
        if (longRun == null) return state.dayTicks / state.daySteps
        return (state.dayTicks + PRIOR_STEPS * longRun) / (state.daySteps + PRIOR_STEPS)
    }

    /**
     * A day after the running one (the caller has not advanced yet) is scaled by the long-run factor
     * as it would stand once the running day is folded in. An earlier unfrozen day has no factor here.
     *
     * Swift twin: `StepCalibration.longRunFactorIfLater(_:day:)`.
     */
    private fun longRunFactorIfLater(state: State, day: String): Double? {
        val current = state.day ?: return null
        if (!(day > current)) return null
        return longRunFactor(advanced(state, to = day))
    }
}
