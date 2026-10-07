package com.noop.ui

import com.noop.ble.WhoopModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pins the first-run rules ([OnboardingRules]): which sentence step 2 shows, and the date-of-birth round
 * trip between the Material date picker (UTC midnights) and the profile (a local start of day), kept to the
 * profile's 13–100 years. Twin of the Swift `ScanStep.message` decision.
 */
class OnboardingRulesTest {

    @Test
    fun `bonded wins, then Bluetooth refused, then not found by family, else the wear line`() {
        assertEquals(ScanMessage.BONDED_BATTERY, OnboardingRules.scanMessage(true, true, true, true, WhoopModel.WHOOP4))
        assertEquals(ScanMessage.BONDED, OnboardingRules.scanMessage(true, false, false, false, WhoopModel.WHOOP4))
        assertEquals(ScanMessage.BLUETOOTH_OFF, OnboardingRules.scanMessage(false, false, true, true, WhoopModel.WHOOP4))
        // #130: a 5.0/MG held by the WHOOP app hides from a scan, so its line says to unpair it there.
        assertEquals(ScanMessage.NOT_FOUND_5, OnboardingRules.scanMessage(false, false, false, true, WhoopModel.WHOOP5_MG))
        assertEquals(ScanMessage.NOT_FOUND_4, OnboardingRules.scanMessage(false, false, false, true, WhoopModel.WHOOP4))
        assertEquals(ScanMessage.WEAR_IT, OnboardingRules.scanMessage(false, false, false, false, WhoopModel.WHOOP5_MG))
    }

    /** Another app seen pulling the strap's history outranks the battery line, but only once bonded. */
    @Test
    fun `a bonded strap another app is syncing says so`() {
        assertEquals(
            ScanMessage.BONDED_OTHER_APP,
            OnboardingRules.scanMessage(true, true, false, false, WhoopModel.WHOOP4, otherAppSyncing = true),
        )
        assertEquals(
            ScanMessage.BONDED_OTHER_APP,
            OnboardingRules.scanMessage(true, false, false, false, WhoopModel.WHOOP5_MG, otherAppSyncing = true),
        )
        assertEquals(
            ScanMessage.WEAR_IT,
            OnboardingRules.scanMessage(false, false, false, false, WhoopModel.WHOOP4, otherAppSyncing = true),
        )
    }

    /** The other-strap-apps page is in the path only when one can reach the strap, right after Welcome. */
    @Test
    fun `the other apps page follows Welcome only when another strap app is there`() {
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.Scan, SetupStep.Profile, SetupStep.Import),
            OnboardingRules.path(otherStrapApps = false),
        )
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.OtherApps, SetupStep.Scan, SetupStep.Profile, SetupStep.Import),
            OnboardingRules.path(otherStrapApps = true),
        )
    }

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 10, 1)

    private fun localMidnight(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun utcMidnight(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `the stored date of birth opens the picker on the same calendar day`() {
        val dob = LocalDate.of(1990, 3, 12)
        val picker = OnboardingRules.pickerMillisForDob(localMidnight(dob, berlin), berlin)
        assertEquals(dob, Instant.ofEpochMilli(picker).atZone(ZoneOffset.UTC).toLocalDate())
    }

    @Test
    fun `a picked day is stored as that local day, whatever the zone offset`() {
        val picked = LocalDate.of(1990, 3, 12)
        val stored = OnboardingRules.dobFromPicker(utcMidnight(picked), berlin, today)
        assertEquals(picked, Instant.ofEpochMilli(stored).atZone(berlin).toLocalDate())
        // West of UTC too: the UTC midnight is the previous local evening, and must not slip a day.
        val ny = ZoneId.of("America/New_York")
        val storedNy = OnboardingRules.dobFromPicker(utcMidnight(picked), ny, today)
        assertEquals(picked, Instant.ofEpochMilli(storedNy).atZone(ny).toLocalDate())
    }

    @Test
    fun `a date of birth stays within the profile's 13 to 100 years`() {
        val tooYoung = OnboardingRules.dobFromPicker(utcMidnight(LocalDate.of(2020, 1, 1)), berlin, today)
        assertEquals(today.minusYears(13), Instant.ofEpochMilli(tooYoung).atZone(berlin).toLocalDate())
        val tooOld = OnboardingRules.dobFromPicker(utcMidnight(LocalDate.of(1900, 1, 1)), berlin, today)
        assertEquals(today.minusYears(100), Instant.ofEpochMilli(tooOld).atZone(berlin).toLocalDate())
        assertEquals(1926..2013, OnboardingRules.dobYearRange(today))
    }
}
