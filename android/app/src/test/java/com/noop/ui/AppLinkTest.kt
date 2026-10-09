package com.noop.ui

import com.noop.data.WhoopRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins where a widget or notification tap lands (audit WG-1): each surface carries one `noop://<host>`
 * link, and the shell turns it into a tab and a screen. The hosts are the iOS `WidgetLink` raw values,
 * so the two platforms name a route identically; the targets are what the task's brief asks of each
 * widget (rings and compact: Summary; heart rate: the Heart Rate page; stress: the Day Stress metric
 * page on the Summary stack).
 */
class AppLinkTest {

    private fun target(link: AppLink, workoutActive: Boolean = false, coachEnabled: Boolean = true) =
        AppLinkTarget.of(link, workoutActive, coachEnabled)

    @Test
    fun hostsAreTheIosWidgetLinkRoutes() {
        assertEquals("today", AppLink.Today.host)
        assertEquals("heart-rate", AppLink.HeartRate.host)
        assertEquals("stress", AppLink.Stress.host)
        assertEquals("coach", AppLink.Coach.host)
        assertEquals("workout", AppLink.Workout.host)
        assertEquals("noop://heart-rate", AppLink.HeartRate.uri)
    }

    @Test
    fun everyLinkHasItsOwnUri() {
        // Two links with one URI would share a PendingIntent, and the later one would overwrite the
        // earlier one's destination.
        assertEquals(AppLink.entries.size, AppLink.entries.map { it.uri }.distinct().size)
    }

    @Test
    fun aUriRoundTripsToItsLink() {
        AppLink.entries.forEach { assertEquals(it, AppLink.from(AppLink.SCHEME, it.host)) }
    }

    @Test
    fun schemeAndHostAreCaseInsensitive() {
        assertEquals(AppLink.HeartRate, AppLink.from("NOOP", "Heart-Rate"))
    }

    @Test
    fun anythingElseIsNotALink() {
        assertNull(AppLink.from(null, null))
        assertNull(AppLink.from("noop", null))
        assertNull(AppLink.from("https", "today"))
        // The other users of the scheme on iOS keep their own handling.
        assertNull(AppLink.from("noop", "import-health"))
        assertNull(AppLink.from("noop", "oura"))
    }

    @Test
    fun theRingsWidgetOpensTheSummaryAtItsRoot() {
        assertEquals(AppLinkTarget.TabRoot(MainTab.Summary), target(AppLink.Today))
    }

    @Test
    fun theHeartRateWidgetOpensTheHeartRatePage() {
        assertEquals(AppLinkTarget.Screen(MainTab.Summary, Destination.Live.route), target(AppLink.HeartRate))
    }

    @Test
    fun theStressWidgetOpensDayStressOnTheSummary() {
        // The strap's own 0-3 Day Stress, which is the curve the widget draws.
        assertEquals(
            AppLinkTarget.Metric(MainTab.Summary, "stress", WhoopRepository.WHOOP_SOURCE),
            target(AppLink.Stress),
        )
    }

    @Test
    fun theCoachWidgetOpensCoachOnlyWhileCoachIsOn() {
        assertEquals(AppLinkTarget.TabRoot(MainTab.Coach), target(AppLink.Coach))
        assertNull(target(AppLink.Coach, coachEnabled = false))
    }

    @Test
    fun theWorkoutNotificationOpensTheRecordingScreen() {
        assertEquals(AppLinkTarget.Recording(confirmFinish = false), target(AppLink.Workout, workoutActive = true))
    }

    @Test
    fun finishOpensTheRecordingScreenWithItsConfirmationUp() {
        assertEquals(
            AppLinkTarget.Recording(confirmFinish = true),
            target(AppLink.WorkoutFinish, workoutActive = true),
        )
    }

    @Test
    fun aWorkoutLinkAfterTheWorkoutEndedOpensNothing() {
        // A notification tapped a moment after the workout was finished elsewhere: the app stays put.
        assertNull(target(AppLink.Workout, workoutActive = false))
        assertNull(target(AppLink.WorkoutFinish, workoutActive = false))
    }
}
