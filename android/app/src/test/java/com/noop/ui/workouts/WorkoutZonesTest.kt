package com.noop.ui.workouts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the workout page's imported zone-split parsing to the real stored shapes:
 *   - WhoopCsvImporter.zonesJson → {"zone1".."zone5"} (this platform's own rows)
 *   - the macOS importer/backfill → {"z1".."z5"} (same data, other platform's key shape)
 * Percentages are 0–100 of each workout's duration and may sum to less than 100
 * (below-Z1 time is not exported).
 */
class WorkoutZonesTest {

    @Test
    fun parsesAndroidKeyShape() {
        assertEquals(
            listOf(10.0, 20.0, 30.0, 25.0, 15.0),
            parseZonePercents("""{"zone1":10.0,"zone2":20.0,"zone3":30.0,"zone4":25.0,"zone5":15.0}"""),
        )
    }

    @Test
    fun parsesMacKeyShape_missingZonesAreZero() {
        assertEquals(listOf(12.5, 0.0, 0.0, 0.0, 4.5), parseZonePercents("""{"z1":12.5,"z5":4.5}"""))
    }

    @Test
    fun rejectsNullBlankEmptyAndAllZero() {
        assertNull(parseZonePercents(null))
        assertNull(parseZonePercents(""))
        assertNull(parseZonePercents("{}"))
        assertNull(parseZonePercents("""{"zone1":0}"""))
    }
}
