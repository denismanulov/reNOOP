package com.noop.ui

import com.noop.ui.lab.LabFormat
import com.noop.ui.workouts.WorkoutNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CR-11: one reader for every typed number. A comma keyboard and a point keyboard give the same value in a
 * manual workout, a lab reading and a journal habit, and nothing that is not a number is stored as one.
 */
class TypedNumberTest {

    @Test fun commaAndPointAreTheSameNumber() {
        assertEquals(5.2, TypedNumber.parse("5,2")!!, 1e-9)
        assertEquals(5.2, TypedNumber.parse("5.2")!!, 1e-9)
        assertEquals(12.0, TypedNumber.parse(" 12 ")!!, 1e-9)
        assertEquals(5.0, TypedNumber.parse("5,")!!, 1e-9)
        assertEquals(-0.4, TypedNumber.parse("-0,4")!!, 1e-9)
    }

    @Test fun groupingIsDroppedWhicheverWayRound() {
        assertEquals(1234.5, TypedNumber.parse("1 234,5")!!, 1e-9)
        assertEquals(1234.5, TypedNumber.parse("1 234,5")!!, 1e-9)
        assertEquals(1234.5, TypedNumber.parse("1 234,5")!!, 1e-9)
        assertEquals(1234.5, TypedNumber.parse("1,234.5")!!, 1e-9)
        assertEquals(1234.5, TypedNumber.parse("1.234,5")!!, 1e-9)
    }

    @Test fun whatIsNotANumberIsNotStored() {
        for (bad in listOf("", "   ", "abc", "1.2.3", "1,2,3", "5 km", "1e3", "--")) {
            assertNull(bad, TypedNumber.parse(bad))
        }
    }

    @Test fun everyFieldReadsThroughTheSameReader() {
        for (typed in listOf("5,2", "5.2", " 3,1 ", "120", "1.234,5", "1 234,5", "abc", "", "1.2.3", "1e3")) {
            val expected = TypedNumber.parse(typed)
            assertEquals("workout: $typed", expected, WorkoutNumbers.parse(typed))
            assertEquals("lab: $typed", expected, LabFormat.parse(typed))
        }
    }
}
