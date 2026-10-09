package com.noop.ui

import com.noop.R
import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #1617: the Blood Oxygen empty state must not promise a reading that is not coming.
 *
 * Both WHOOP generations bank a strap-computed percentage - the 5/MG at `@82` of a v18 record, the 4.0
 * at `@86` of a 104-byte v24 record - and both ship it as an unverified estimate that is off by default.
 * So an empty screen on either strap means the switch is off, or it is on and there are not yet two
 * nights. The 4.0 used to be told that waiting could never help; that was before its result byte was
 * found, and the copy for it is gone.
 */
class Spo2EmptyStateTest {

    private val notEnoughHistory = R.string.l10n_health_screen_not_enough_history_yet_0e2f93b6

    /** A 4.0 with the estimate off: actionable, so name the switch, exactly as on a 5/MG. */
    @Test fun whoop4WithTheEstimateOffPointsAtTheToggle() {
        val s = spo2EmptyState(key = "spo2", family = DeviceFamily.WHOOP4, candidateDisplayOn = false)
        assertEquals(R.string.l10n_health_screen_the_blood_oxygen_estimate_is_turned_4c403ab2, s.titleRes)
        assertEquals(R.string.l10n_health_screen_your_strap_reports_a_blood_oxygen_349fe34a, s.bodyRes)
        assertNotEquals("with the switch off, waiting cannot help", notEnoughHistory, s.titleRes)
    }

    /** A 4.0 with it on genuinely just needs nights. */
    @Test fun whoop4WithTheEstimateOnKeepsTheDefaultCopy() {
        val s = spo2EmptyState("spo2", family = DeviceFamily.WHOOP4, candidateDisplayOn = true)
        assertEquals(notEnoughHistory, s.titleRes)
    }

    /**
     * The bug this nearly shipped with. A null family means a positively non-WHOOP brand (an Oura ring,
     * which DOES produce SpO2 via the 0x6F ceiling transform) or a registry row not yet loaded. Either
     * way it has no strap-estimate switch to be pointed at, whatever the stored toggle says.
     */
    @Test fun aNonWhoopOrUnresolvedDeviceIsNeverPointedAtTheStrapEstimateSwitch() {
        for (on in listOf(true, false)) {
            val s = spo2EmptyState("spo2", family = null, candidateDisplayOn = on)
            assertEquals(notEnoughHistory, s.titleRes)
        }
    }

    /** 5/MG with the estimate off: actionable, so name the switch rather than implying more nights. */
    @Test fun whoop5WithTheEstimateOffPointsAtTheToggle() {
        val s = spo2EmptyState("spo2", family = DeviceFamily.WHOOP5, candidateDisplayOn = false)
        assertEquals(R.string.l10n_health_screen_the_blood_oxygen_estimate_is_turned_4c403ab2, s.titleRes)
        assertEquals(R.string.l10n_health_screen_your_strap_reports_a_blood_oxygen_349fe34a, s.bodyRes)
    }

    /** 5/MG with it on genuinely just needs nights, so the default copy is correct there. */
    @Test fun whoop5WithTheEstimateOnKeepsTheDefaultCopy() {
        val s = spo2EmptyState("spo2", family = DeviceFamily.WHOOP5, candidateDisplayOn = true)
        assertEquals(notEnoughHistory, s.titleRes)
    }

    /** Every other vital is untouched on both strap generations — this is a Blood Oxygen carve-out. */
    @Test fun otherVitalsKeepTheDefaultOnEitherStrap() {
        for (key in listOf("hrv", "resting_hr", "skin_temp", "resp_rate", "fitness_age")) {
            for (w5 in listOf(true, false)) {
                for (on in listOf(true, false)) {
                    assertEquals(
                        "$key must keep the default empty state",
                        notEnoughHistory,
                        spo2EmptyState(key, family = if (w5) DeviceFamily.WHOOP5 else DeviceFamily.WHOOP4, candidateDisplayOn = on).titleRes,
                    )
                }
            }
        }
    }
}
