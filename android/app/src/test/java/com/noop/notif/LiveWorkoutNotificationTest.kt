package com.noop.notif

import com.noop.analytics.WorkoutSport
import com.noop.ui.ActiveWorkoutClock
import com.noop.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the live workout notification's content and its re-post rule.
 *
 * The notification is a third readout of facts the recording screen and the mini-player already show, so
 * the first thing pinned is that its clock IS theirs: the chronometer base it hands the system reads
 * [ActiveWorkoutClock.activeElapsedSeconds] at every instant, and a paused workout stands at the time that
 * clock froze on.
 */
class LiveWorkoutNotificationTest {

    private val start = 1_700_000_000_000L

    private fun content(
        pausedAtS: Long? = null,
        pausedDurationS: Long = 0,
        bpm: Int? = 120,
        sport: String = "Running",
    ) = LiveWorkoutContent(
        sportName = sport,
        startMs = start,
        pausedAtMs = pausedAtS?.let { start + it * 1000 },
        pausedDurationMs = pausedDurationS * 1000,
        bpm = bpm,
    )

    // MARK: - The clock

    @Test
    fun theChronometerCountsTheSameActiveTimeAsTheRecordingScreen() {
        for (pausedDurationS in listOf(0L, 20L, 600L)) {
            val c = content(pausedDurationS = pausedDurationS)
            for (nowS in listOf(pausedDurationS, pausedDurationS + 1, 65L + pausedDurationS, 7_265L + pausedDurationS)) {
                val nowMs = start + nowS * 1000
                assertEquals(
                    ActiveWorkoutClock.activeElapsedSeconds(c.startMs, null, c.pausedDurationMs, nowMs),
                    (nowMs - c.chronometerBaseMs) / 1000,
                )
            }
        }
    }

    @Test
    fun aPausedWorkoutStandsAtTheTimeItWasPausedAt() {
        // Paused 30 s in, after an earlier 10 s pause: 20 s of active time, however long the pause lasts.
        val c = content(pausedAtS = 30, pausedDurationS = 10)
        assertTrue(c.paused)
        assertEquals(20L, c.pausedElapsedSeconds)
        assertEquals(
            ActiveWorkoutClock.activeElapsedSeconds(c.startMs, c.pausedAtMs, c.pausedDurationMs, start + 9_999_000),
            c.pausedElapsedSeconds,
        )
    }

    @Test
    fun aRunningWorkoutIsNotPaused() {
        assertFalse(content().paused)
    }

    // MARK: - What there is to show

    private fun workout(pausedAtMs: Long? = null) = AppViewModel.ActiveWorkout(
        startMs = start, sport = WorkoutSport.default, gpsEnabled = false,
        pausedAtMs = pausedAtMs, pausedDurationMs = 5_000,
    )

    @Test
    fun noWorkoutMeansNoNotification() {
        assertNull(LiveWorkoutContent.of(null, 120, enabled = true))
    }

    @Test
    fun theSettingsSwitchTurnsItOff() {
        assertNull(LiveWorkoutContent.of(workout(), 120, enabled = false))
    }

    @Test
    fun theContentIsTheWorkoutsOwnFields() {
        val c = LiveWorkoutContent.of(workout(pausedAtMs = start + 40_000), 133, enabled = true)!!
        assertEquals(WorkoutSport.default.name, c.sportName)
        assertEquals(start, c.startMs)
        assertEquals(start + 40_000, c.pausedAtMs)
        assertEquals(5_000L, c.pausedDurationMs)
        assertEquals(133, c.bpm)
    }

    @Test
    fun aWorkoutWithNoHeartRateStillHasANotification() {
        // The strap off the wrist: the sport and the clock stand, the heart rate is the dash.
        assertNull(LiveWorkoutContent.of(workout(), null, enabled = true)!!.bpm)
    }

    // MARK: - When to re-post

    private fun delay(shown: LiveWorkoutContent?, next: LiveWorkoutContent, sinceMs: Long) =
        LiveWorkoutNotificationPolicy.delayBeforePostMs(shown, next, sinceMs)

    @Test
    fun theFirstPostGoesOutAtOnce() {
        assertEquals(0L, delay(null, content(), sinceMs = 0))
    }

    @Test
    fun aMovingNumberWaitsOutTheSpacing() {
        val spacing = LiveWorkoutNotificationPolicy.MIN_SPACING_MS
        assertEquals(spacing - 500, delay(content(bpm = 120), content(bpm = 121), sinceMs = 500))
        assertEquals(0L, delay(content(bpm = 120), content(bpm = 121), sinceMs = spacing))
        assertEquals(0L, delay(content(bpm = 120), content(bpm = 121), sinceMs = spacing + 10_000))
    }

    @Test
    fun aPauseOrResumeIsPostedAtOnce() {
        // The wearer just pressed the button and is looking at the notification.
        assertEquals(0L, delay(content(), content(pausedAtS = 30), sinceMs = 100))
        assertEquals(0L, delay(content(pausedAtS = 30), content(pausedDurationS = 12), sinceMs = 100))
    }

    @Test
    fun theNumberGivingWayToTheDashIsPostedAtOnce() {
        // Often the last thing that happens before a strap goes quiet: no later tick would retry it.
        assertEquals(0L, delay(content(bpm = 120), content(bpm = null), sinceMs = 100))
        assertEquals(0L, delay(content(bpm = null), content(bpm = 96), sinceMs = 100))
    }

    @Test
    fun anUnchangedNotificationIsRepostedOnlyToStayAlive() {
        val keepAlive = LiveWorkoutNotificationPolicy.KEEP_ALIVE_MS
        assertEquals(keepAlive - 1_000, delay(content(), content(), sinceMs = 1_000))
        assertEquals(0L, delay(content(), content(), sinceMs = keepAlive + 1))
    }

    @Test
    fun theKeepAliveOutrunsTheTimeout() {
        // The timeout removes a notification whose process died; a live one must be re-posted well inside
        // it, or it would vanish from the lock screen mid-workout.
        assertTrue(LiveWorkoutNotificationPolicy.KEEP_ALIVE_MS * 3 <= LiveWorkoutNotificationPolicy.TIMEOUT_MS)
    }
}
