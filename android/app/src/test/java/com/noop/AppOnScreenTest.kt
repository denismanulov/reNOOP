package com.noop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AppOnScreen]: "on screen" is "an activity is resumed". */
class AppOnScreenTest {
    @Test
    fun onScreenBetweenResumeAndPause() {
        assertFalse("a process started for the background connection has no activity", AppOnScreen.isOnScreen)
        AppOnScreen.noteResumed()
        assertTrue(AppOnScreen.isOnScreen)
        AppOnScreen.notePaused()
        assertFalse(AppOnScreen.isOnScreen)
        // A pause with nothing resumed (a callback registered late) cannot push the count below zero
        // and leave the next resume reading as off screen.
        AppOnScreen.notePaused()
        AppOnScreen.noteResumed()
        assertTrue(AppOnScreen.isOnScreen)
        AppOnScreen.notePaused()
        assertFalse(AppOnScreen.isOnScreen)
    }
}
