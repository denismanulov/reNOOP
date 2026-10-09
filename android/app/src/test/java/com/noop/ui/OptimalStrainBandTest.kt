package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the display-only recovery->optimal-strain band (task #43), byte-identical to the Swift
 * StrainTargetNotifier.optimalStrainRange: green >= 67 -> 14-18, yellow 34-66 -> 10-14, red < 34 -> 4-10.
 * Today's Effort ring target arc and the strain-target nudge read it; these values ARE the contract.
 */
class OptimalStrainBandTest {

    @Test fun greenDaySuggests14to18() {
        assertEquals(OptimalStrainRange(14, 18), optimalStrainRange(90.0))
        assertEquals(OptimalStrainRange(14, 18), optimalStrainRange(67.0)) // green's lower edge is inclusive
    }

    @Test fun yellowDaySuggests10to14() {
        assertEquals(OptimalStrainRange(10, 14), optimalStrainRange(66.9))
        assertEquals(OptimalStrainRange(10, 14), optimalStrainRange(50.0))
        assertEquals(OptimalStrainRange(10, 14), optimalStrainRange(34.0)) // yellow's lower edge is inclusive
    }

    @Test fun redDaySuggests4to10() {
        assertEquals(OptimalStrainRange(4, 10), optimalStrainRange(33.9))
        assertEquals(OptimalStrainRange(4, 10), optimalStrainRange(0.0))
    }

}
