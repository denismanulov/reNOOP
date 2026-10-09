package com.noop.ui.m3

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-1: a chart's date labels are thinned evenly once they would touch (a large font scale, long weekday
 * names), instead of running into each other or being dropped one here and one there.
 */
class LabelStrideTest {

    @Test fun everyLabelWhenTheyFit() {
        // Seven weekday labels 90 px wide, 150 px apart, 12 px clear between two.
        assertEquals(1, labelStride(widest = 90f, spacing = 150f, gap = 12f))
        // Exactly filling the spacing still fits.
        assertEquals(1, labelStride(widest = 138f, spacing = 150f, gap = 12f))
    }

    @Test fun everyOtherOnceTheyWouldTouch() {
        // At a 2x font scale the same labels are 180 px wide.
        assertEquals(2, labelStride(widest = 180f, spacing = 150f, gap = 12f))
        assertEquals(2, labelStride(widest = 139f, spacing = 150f, gap = 12f))
        assertEquals(3, labelStride(widest = 300f, spacing = 150f, gap = 12f))
    }

    @Test fun nothingToThinWithoutLabelsOrSpacing() {
        assertEquals(1, labelStride(widest = 0f, spacing = 150f, gap = 12f))
        assertEquals(1, labelStride(widest = 90f, spacing = 0f, gap = 12f))
    }
}
