package com.noop.data

import com.noop.analytics.StepCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepCalibrationStore]: which divisor a day uses. The first test is the twin of the Swift
 * `StepAutoCalibratorTests.testTheStoreAnswersWithTheManualDivisorUnlessOptedIn`, with the same inputs
 * and expectations. The rest pin what the opt-in being off (the default) must leave alone.
 */
class StepCalibrationStoreTest {

    private val learned =
        StepCalibration.recorded(StepCalibration.State(), day = "2026-10-03", steps = 100.0, ticks = 130.0)

    @Test
    fun theStoreAnswersWithTheManualDivisorUnlessOptedIn() {
        val prefs = MemoryKeyValuePrefs()
        StepCalibrationStore.save(learned, prefs)
        assertEquals(
            1.0,
            StepCalibrationStore.snapshot(manual = 1.0, today = "2026-10-03", prefs = prefs).factor("2026-10-03"),
            0.0,
        )

        StepCalibrationStore.setEnabled(prefs, true)
        val snapshot = StepCalibrationStore.snapshot(manual = 1.0, today = "2026-10-04", prefs = prefs)
        assertEquals(1.30, snapshot.factor("2026-10-03"), 1e-9)   // frozen on the way
        assertEquals(1.30, snapshot.factor("2026-10-04"), 1e-9)   // the long-run factor
        assertEquals(1.0, snapshot.factor("2026-09-01"), 0.0)     // before anything was learned
    }

    /** The opt-in is off unless something turned it on: an install that never saw the switch is off. */
    @Test
    fun theOptInDefaultsToOff() {
        assertFalse(StepCalibrationStore.isEnabled(MemoryKeyValuePrefs()))
    }

    /**
     * Off means off for the stored state too: a snapshot neither parses it nor moves its running day
     * forward, on any day and for any manual divisor. Every day then answers the manual divisor, bit
     * for bit, whatever was learned while the opt-in was on.
     */
    @Test
    fun offReadsAndWritesNothingAndEveryDayIsTheManualDivisor() {
        val prefs = MemoryKeyValuePrefs()
        StepCalibrationStore.save(learned, prefs)
        val stored = prefs.strings.getValue(StepCalibrationStore.STATE_KEY)
        val writesBefore = prefs.writes

        for (manual in listOf(1.0, 1.26f.toDouble(), 0.5, 24.0, 30.0)) {
            for (today in listOf("2026-10-03", "2026-10-04", "2026-11-20")) {
                val snapshot = StepCalibrationStore.snapshot(manual = manual, today = today, prefs = prefs)
                assertNull("no learned state reaches the pass", snapshot.state)
                for (day in listOf("2026-09-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-12-31")) {
                    assertEquals(manual.toRawBits(), snapshot.factor(day).toRawBits())
                }
            }
        }
        assertEquals("nothing was written while off", writesBefore, prefs.writes)
        assertEquals(stored, prefs.strings.getValue(StepCalibrationStore.STATE_KEY))
    }

    /** On with nothing learned yet: still the manual divisor for every day (the feature is inert). */
    @Test
    fun onWithNoMeasurementIsStillTheManualDivisor() {
        val prefs = MemoryKeyValuePrefs()
        StepCalibrationStore.setEnabled(prefs, true)
        for (today in listOf("2026-10-03", "2026-10-04", "2026-10-09")) {
            val snapshot = StepCalibrationStore.snapshot(manual = 1.26, today = today, prefs = prefs)
            for (day in listOf("2026-09-01", "2026-10-03", "2026-10-04", "2026-10-09", "2026-10-10")) {
                assertEquals(1.26.toRawBits(), snapshot.factor(day).toRawBits())
            }
        }
        assertEquals(0, StepCalibrationStore.load(prefs).accepted)
    }

    /** `record` is the calibrator's load, `StepCalibration.recorded`, save in one step. */
    @Test
    fun recordAddsOneMeasurementToTheStoredState() {
        val prefs = MemoryKeyValuePrefs()
        val first = StepCalibrationStore.record(prefs, day = "2026-10-03", steps = 100.0, ticks = 130.0)
        assertEquals(learned, first)
        assertEquals(learned, StepCalibrationStore.load(prefs))
        // A refused ratio leaves the stored state as it was.
        val refused = StepCalibrationStore.record(prefs, day = "2026-10-03", steps = 60.0, ticks = 130.0)
        assertEquals(learned, refused)
        assertEquals(learned, StepCalibrationStore.load(prefs))
    }

    /** Anything unreadable under the state key is an empty state, like Swift's failed decode. */
    @Test
    fun anUnreadableStoredStateIsAnEmptyOne() {
        val prefs = MemoryKeyValuePrefs()
        assertEquals(StepCalibration.State(), StepCalibrationStore.load(prefs))
        prefs.putString(StepCalibrationStore.STATE_KEY, "not json")
        assertEquals(StepCalibration.State(), StepCalibrationStore.load(prefs))
        prefs.putString(StepCalibrationStore.STATE_KEY, """{"emaTicks":1}""")
        assertEquals(StepCalibration.State(), StepCalibrationStore.load(prefs))
    }

    /** The Swift key names, and none of them in the `.noopbak` whitelist. */
    @Test
    fun theKeysAreSwiftsAndStayOutOfTheBackup() {
        assertEquals("stepAutoCalibration.enabled", StepCalibrationStore.ENABLED_KEY)
        assertEquals("stepAutoCalibration.state", StepCalibrationStore.STATE_KEY)
        assertTrue(BackupSettingsCodec.WHITELIST.keys.none {
            it.startsWith("stepAutoCalibration") || it.startsWith("rawStreamProbe")
        })
    }
}
