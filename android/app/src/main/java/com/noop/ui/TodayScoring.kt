package com.noop.ui

import com.noop.analytics.Baselines
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The recovery baseline's real seed count while it still cold-starts, the honest "calibrating N of
 * <seed>" progress shown in place of "No Data"; null once recovery exists or the baseline has crossed
 * the seed gate. N is the HRV baseline's `nValid` from folding the SAME day-keyed, epoch-aware history
 * the recovery engine folds ([Baselines.foldHistory] with [hrvBaselineEpoch]), NOT a looser per-night
 * bounds count.
 *
 * The old count advanced on every in-range night, including nights the engine's fold DROPS after a
 * manual "Recalibrate HRV baseline" (each night dated before the epoch is discarded, not skip-and-held).
 * A genuinely-calibrating user who had >= seed old in-range nights therefore read `count >= seed -> null`,
 * and the score side fell through to "needs strap" while the post-recalibration baseline
 * was still seeding (Bug B, #393 follow-up). `nValid` is the exact count Baselines.computeStatus gates
 * CALIBRATING on, so N now tracks the baseline the Charge ring rides and can never over-state it.
 * [days] is oldest->newest (same order the engine folds). Pure + unit-tested (RecoveryCalibrationTest).
 * (PR #85)
 */
internal fun recoveryCalibrationNights(
    days: List<DailyMetric>,
    hasRecovery: Boolean,
    hrvBaselineEpoch: Double,
    seed: Int = Baselines.minNightsSeed,
): Int? {
    if (hasRecovery) return null
    val n = Baselines.foldHistory(
        days.map { it.avgHrv }, days.map { it.day }, Baselines.hrvCfg, hrvBaselineEpoch,
    ).nValid
    // Include 0: a brand-new user (no banked nights) reads "Calibrating, 0 of N" on Charge, not a
    // bare "No data" that looks broken (#335). Caller gates past days to null; >= seed -> null.
    return n.takeIf { it in 0 until seed }
}

/**
 * The most recent fully-SCORED recovery day to carry over on TODAY while tonight's recovery hasn't been
 * scored yet (#543), the ONE prior row every recovery-derived read-out (Charge ring, HRV / resting-HR /
 * respiratory / SpO2 tiles, Synthesis, Contributors, Readiness) carries over from at the rollover. Pure +
 * unit-tested (TodayMetricTilesTest). [days] is oldest->newest; the chosen row is the last with a non-null
 * recovery that isn't today's (still-null) [selectedDayKey]. Returns null unless it's today, today itself
 * isn't scored, and we're not mid-calibration (calibration owns its own copy), so past days / a scored
 * today / a calibrating today carry nothing and live behaviour is unchanged. Mirrors iOS.
 */
internal fun lastScoredRecoveryDay(
    days: List<DailyMetric>,
    selectedDayKey: String,
    isToday: Boolean,
    todayScored: Boolean,
    isCalibrating: Boolean,
    // #547 carry-over guard: the local "today" key ("yyyy-MM-dd"). A stray FUTURE-dated row (a bad strap
    // clock wrote a day past today) must NEVER be picked as "last night", that's how #547's Today header
    // read "12 Jul". Cheap belt-and-suspenders alongside the ingest gate + heal: filter candidates to
    // day <= today so even a future row that slipped through can't surface here. ISO date keys sort
    // chronologically, so a plain string compare is correct. Defaulted to MAX so an un-updated call site
    // keeps the prior behaviour; the Today call site passes the real local today.
    today: String = "9999-12-31",
): DailyMetric? {
    if (!isToday || todayScored || isCalibrating) return null
    return days.lastOrNull { it.recovery != null && it.day != selectedDayKey && it.day <= today }
}

/** Carry-over recency cap (#779): the "Last night" framing only holds when the carried scored day is
 *  within this many days of today. Mirrors iOS TodayView.carryFreshnessDays. */
internal const val CARRY_FRESHNESS_DAYS = 2L

/** True when the carried scored day is OLDER than the freshness cap (#779), which drives the "Latest
 *  sleep" relabel. Pure + unit-testable. Both keys are "yyyy-MM-dd"; an unparseable key (or non-positive gap)
 *  reads as fresh so we never over-claim staleness. [today] is today's key (carry-over is today-only),
 *  defaulted to the device's current date for the composable call sites. Mirrors iOS isCarryStale. */
internal fun isCarryStale(priorDayKey: String, today: String = LocalDate.now().toString()): Boolean =
    runCatching {
        ChronoUnit.DAYS.between(LocalDate.parse(priorDayKey), LocalDate.parse(today)) > CARRY_FRESHNESS_DAYS
    }.getOrDefault(false)

/** #977 - HONEST Rest resolution for the selected day. Today's own scored Rest wins; otherwise, ONLY on
 *  today, tail-fall-back to the last scored night, but ONLY when that night is within the carry-freshness
 *  window ([isCarryStale] == false). A live 5.0 whose sleep never scores (no overnight gravity => no
 *  `sleep_performance` point ever written) used to pin Rest to a weeks-old scored night while Charge kept
 *  advancing; gating the tail-fallback lets the Rest ring fall through to its needs-a-tracked-night state
 *  instead of freezing on a stale number. The legitimate morning carry of last night's Rest (before today
 *  scores) is preserved unchanged. Pure + unit-testable. Mirrors iOS TodayView.freshRestScore. */
internal fun freshRestScore(
    todayValue: Double?, lastDay: String?, lastValue: Double?,
    isTodaySelected: Boolean, today: String = LocalDate.now().toString(),
): Double? {
    if (todayValue != null) return todayValue
    if (!isTodaySelected || lastDay == null || lastValue == null) return null
    return if (isCarryStale(lastDay, today)) null else lastValue
}

