package com.noop.ui.sleep

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #2534 on the rebuilt Sleep pages: every list of the four stages reads in chart depth, top to bottom, the
 * hypnogram convention: awake, REM, core (light), deep.
 *
 * One enum carries that order now. The stages chart places a row at its [SleepStageRow.ordinal], and the
 * chart's legend rows, the Sleep tile, the Stages highlight and More Sleep Data's Stages rows all walk
 * [SleepStageRow.entries], so two screens cannot list the same night's stages differently. Main's version of
 * this test was a source tripwire over the old Sleep screen and iOS files the redesign deleted; this pins the
 * one place the order lives.
 */
class SleepStageRowOrderTest {

    @Test
    fun `stage rows run in chart depth`() {
        assertEquals(
            listOf(SleepStageRow.AWAKE, SleepStageRow.REM, SleepStageRow.CORE, SleepStageRow.DEEP),
            SleepStageRow.entries.toList(),
        )
    }

    @Test
    fun `stored stage names land on their rows, light on core`() {
        assertEquals(SleepStageRow.AWAKE, SleepStageRow.of("awake"))
        assertEquals(SleepStageRow.REM, SleepStageRow.of("rem"))
        assertEquals(SleepStageRow.CORE, SleepStageRow.of("light"))
        assertEquals(SleepStageRow.DEEP, SleepStageRow.of("deep"))
    }
}
