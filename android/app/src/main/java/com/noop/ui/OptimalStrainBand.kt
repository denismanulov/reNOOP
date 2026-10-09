package com.noop.ui

// MARK: - OPTIMAL strain range (task #43) — pure display-only recovery->strain mapping
//
// Suggests a Day-Strain target BAND from today's recovery: a green day earns a higher optimal band, a red
// day a lower one. PRESENTATION ONLY, never fed back into any score. These are the APPROVED bands and MUST
// stay byte-identical to the Swift StrainTargetNotifier.optimalStrainRange:
//   recovery >= 67 (green)        -> 14-18 of 21
//   34 <= recovery <= 66 (yellow) -> 10-14
//   recovery < 34 (red)           -> 4-10
// null recovery (calibrating / unscored day) -> null, the caller renders no band, never a guessed one.
// (Kept from the removed Coupled view: Today's Effort ring target arc and the strain-target nudge read it.)

internal data class OptimalStrainRange(val low: Int, val high: Int)

/** The pure recovery->optimal-strain band, or null when recovery is unknown. */
internal fun optimalStrainRange(recovery: Double?): OptimalStrainRange? {
    val r = recovery ?: return null
    return when {
        r >= 67 -> OptimalStrainRange(14, 18)
        r >= 34 -> OptimalStrainRange(10, 14)
        else -> OptimalStrainRange(4, 10)
    }
}

