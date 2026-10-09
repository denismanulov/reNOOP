package com.noop.ui.mind

import com.noop.R
import com.noop.analytics.BreathPacer
import com.noop.analytics.BreathPhase
import com.noop.analytics.BreathProtocolCatalog
import com.noop.analytics.BreathProtocolMode
import com.noop.analytics.BreathStage
import com.noop.analytics.HrDownPacer
import com.noop.analytics.Hrv
import com.noop.analytics.ResonanceEngine
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Mindfulness (twin of iOS BreathingView's pure parts)
//
// The choices (pace, length), what a paced session walks, the words under the flower, and the honest
// outcome of Calm. Free of Compose so the JVM tests pin them.

/** A pace from the catalog, or the pace the Resonance sweep locked. */
internal sealed interface BreathPace {
    data class Catalog(val id: String) : BreathPace
    data object Resonance : BreathPace
}

/** How long a paced session runs. */
internal enum class BreathLength(val targetSeconds: Int?, val labelRes: Int) {
    Open(null, R.string.mind_no_limit),
    Five(5 * 60, R.string.mind_5_min),
    Ten(10 * 60, R.string.mind_10_min),
    Fifteen(15 * 60, R.string.mind_15_min),
    ;

    companion object {
        /** The length a protocol's recommended duration maps onto. */
        fun fromRecommended(recommendedMs: Int): BreathLength = when {
            recommendedMs < 7 * 60_000 -> Five
            recommendedMs < 12 * 60_000 -> Ten
            else -> Fifteen
        }
    }
}

/** Which session runs: the fixed-pace trainer, a one-minute resonance cue, the find-your-pace sweep, Calm. */
internal enum class BreathKind { Paced, Resonance, Sweep, Calm }

/** What a paced session walks: its stages, and whether it is a guided protocol (name + clock only). */
internal data class PacedPlan(val stages: List<BreathStage>, val guided: Boolean)

/** The locked resonance pace as two stages, 40:60 inhale to exhale. */
internal fun resonanceStages(bpm: Double): List<BreathStage> {
    val cycleMs = (60_000.0 / bpm).roundToInt()
    val inhaleMs = (cycleMs * BreathPacer.DEFAULT_INHALE_FRACTION).roundToInt()
    return listOf(
        BreathStage(BreathPhase.INHALE, inhaleMs),
        BreathStage(BreathPhase.EXHALE, maxOf(1, cycleMs - inhaleMs)),
    )
}

/** The stages of [pace]: the catalog protocol's (zero-length stages dropped), or the locked resonance pace. */
internal fun pacedPlan(pace: BreathPace, lockedBpm: Double?): PacedPlan = when (pace) {
    BreathPace.Resonance -> PacedPlan(resonanceStages(lockedBpm ?: ResonanceEngine.FALLBACK_BPM), guided = false)
    is BreathPace.Catalog -> {
        val proto = BreathProtocolCatalog.protocolById(pace.id)
        PacedPlan(
            stages = proto?.stages?.filter { it.durationMs > 0 }.orEmpty(),
            guided = proto?.mode == BreathProtocolMode.GUIDED,
        )
    }
}

/** "0:00", "1:05", "10:00". */
internal fun breathClock(total: Int): String = String.format(Locale.US, "%d:%02d", total / 60, total % 60)

/** "5.5": a breathing pace with one decimal, in the reader's number format. */
internal fun breathPaceText(bpm: Double, locale: Locale): String = String.format(locale, "%.1f", bpm)

/** RMSSD over the latest R-R window, for the live readout only; null with fewer than two beats. */
internal fun breathRmssd(intervals: List<Int>): Double? = if (intervals.size >= 2) Hrv.rmssd(intervals) else null

/** The word under the flower for a phase (a stage's own label wins, resolved by the caller). */
internal fun breathPhaseRes(phase: BreathPhase): Int = when (phase) {
    BreathPhase.INHALE -> R.string.mind_breathe_in
    BreathPhase.HOLD -> R.string.mind_hold
    BreathPhase.EXHALE -> R.string.mind_breathe_out
    BreathPhase.TEXT_ONLY -> R.string.mind_follow_cue
}

/** Why Calm can or cannot run (CR-8: it needs breathing haptics on, not three silent minutes). */
internal enum class CalmGate(val detailRes: Int) {
    NeedsStrap(R.string.mind_calm_needs_strap),
    NeedsHaptics(R.string.mind_calm_needs_haptics),
    WaitingHeartRate(R.string.mind_calm_waiting_hr),
    Ready(R.string.mind_calm_detail),
}

/** Calm is a felt rhythm just below the heart: a bonded strap, breathing haptics on, a resting-band HR. */
internal fun calmGate(canBuzz: Boolean, breathingHaptics: Boolean, bpm: Int?): CalmGate = when {
    !canBuzz -> CalmGate.NeedsStrap
    !breathingHaptics -> CalmGate.NeedsHaptics
    bpm == null || bpm !in 55..120 -> CalmGate.WaitingHeartRate
    else -> CalmGate.Ready
}

/** The honest outcome of a Calm session: settled, eased, held steady, or simply ended. */
internal sealed interface CalmOutcome {
    data class Settled(val start: Int?, val end: Int?, val seconds: Int) : CalmOutcome
    data class Eased(val start: Int, val end: Int, val seconds: Int) : CalmOutcome
    data class Steady(val start: Int, val end: Int) : CalmOutcome
    data object Ended : CalmOutcome
    data object CouldNotStart : CalmOutcome
}

/** "Settled" → the heart reached the calm target; otherwise say plainly whether it fell or held steady. */
internal fun calmOutcome(reason: HrDownPacer.StopReason?, startHr: Int?, endHr: Int?, seconds: Int): CalmOutcome =
    when {
        reason == HrDownPacer.StopReason.SETTLED ->
            if (startHr != null && endHr != null) CalmOutcome.Settled(startHr, endHr, seconds)
            else CalmOutcome.Settled(null, null, seconds)
        startHr != null && endHr != null && endHr < startHr -> CalmOutcome.Eased(startHr, endHr, seconds)
        startHr != null && endHr != null -> CalmOutcome.Steady(startHr, endHr)
        else -> CalmOutcome.Ended
    }
