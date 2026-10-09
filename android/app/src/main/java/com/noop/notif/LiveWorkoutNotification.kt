package com.noop.notif

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.noop.R
import com.noop.ble.WhoopConnectionService
import com.noop.ui.ActiveWorkoutClock
import com.noop.ui.AppLink
import com.noop.ui.AppLinks
import com.noop.ui.AppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.elapsedClock
import com.noop.ui.settings.WorkoutLiveNotificationPrefs
import com.noop.ui.workouts.localizedSport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// MARK: - The live workout's ongoing notification (Android twin of the iOS workout Live Activity)
//
// While a workout records, one ongoing notification carries it outside the app: the sport, the running
// clock, the heart rate, and Pause / Resume and Finish. It is what the recording screen and the mini-player
// show, read from the same two flows they read, so the three cannot disagree; its clock is the system's own
// chronometer counting from the workout's pause-adjusted start, and a paused workout shows its frozen time
// and no chronometer at all. Settings > Workouts > "Heart rate in a notification" turns it off.
//
// There is ONE ongoing reNOOP notification, not two. While the connection service is up, its "strap
// connected" notification becomes the workout's for as long as the workout records and returns to the
// connection when it ends (both post under the service's notification id); with the service down, the
// workout's notification stands alone under that same id.

/** What the notification shows: the workout's identity and clock state, and the live heart rate. */
internal data class LiveWorkoutContent(
    /** The catalogue (English, locale-stable) sport name; translated when the notification is built. */
    val sportName: String,
    val startMs: Long,
    val pausedAtMs: Long?,
    val pausedDurationMs: Long,
    /** The smoothed live heart rate the recording screen shows, or null when the strap gives none. */
    val bpm: Int?,
) {
    val paused: Boolean get() = pausedAtMs != null

    /**
     * The wall-clock instant a chronometer counts up from to read the workout's ACTIVE time: the start,
     * moved later by every completed pause. Counting from it is [ActiveWorkoutClock.activeElapsedSeconds]
     * for a running workout at every instant, so the notification's clock is the recording screen's.
     */
    val chronometerBaseMs: Long get() = startMs + pausedDurationMs

    /** The active time a PAUSED workout stands at. It does not move while the pause lasts. */
    val pausedElapsedSeconds: Long
        get() = ActiveWorkoutClock.activeElapsedSeconds(
            startMs = startMs,
            pausedAtMs = pausedAtMs,
            pausedDurationMs = pausedDurationMs,
            nowMs = pausedAtMs ?: startMs,
        )

    companion object {
        /**
         * The notification's content for the app's state, or null when there is nothing to show: no
         * workout is recording, or its switch is off.
         */
        fun of(workout: AppViewModel.ActiveWorkout?, bpm: Int?, enabled: Boolean): LiveWorkoutContent? {
            if (workout == null || !enabled) return null
            return LiveWorkoutContent(
                sportName = workout.sport.name,
                startMs = workout.startMs,
                pausedAtMs = workout.pausedAtMs,
                pausedDurationMs = workout.pausedDurationMs,
                bpm = bpm,
            )
        }
    }
}

/**
 * When the notification is worth re-posting. Pure, so it is pinned by a JVM test.
 *
 * The heart rate ticks about once a second for as long as the strap streams, and re-posting on every tick
 * would re-inflate the shade and wake the lock screen a few thousand times an hour for a number that mostly
 * holds still. So a moving number is posted no more often than [MIN_SPACING_MS] (the spacing of the iOS
 * banner, `LiveHRBannerPushPolicy.minimumSpacing`), while anything else is posted at once: the workout
 * starting, a pause or a resume (the wearer just pressed a button and is looking at the result), and the
 * number giving way to the dash or coming back, which is often the LAST thing that happens before a strap
 * goes quiet and so cannot wait for a tick that will not come.
 */
internal object LiveWorkoutNotificationPolicy {
    const val MIN_SPACING_MS = 2_000L

    /** An unchanged notification is re-posted this often, which is what keeps [TIMEOUT_MS] from firing. */
    const val KEEP_ALIVE_MS = 60_000L

    /**
     * The notification removes itself this long after its last post. It is the safety net for a process
     * that can no longer speak for its own notification (killed mid-workout with no service holding it):
     * a heart rate nobody is measuring any more must not stand on the lock screen as live. The same cap
     * the widgets drop a reading at ([com.noop.widget.HrDisplay.STALE_CAP_MS]) and the iOS banner goes
     * stale at.
     */
    const val TIMEOUT_MS = 15 * 60_000L

    /** How long to wait before posting [next], given what is [shown] and how long ago it was posted. */
    fun delayBeforePostMs(shown: LiveWorkoutContent?, next: LiveWorkoutContent, sinceLastPostMs: Long): Long {
        if (shown == null) return 0L
        if (shown == next) return (KEEP_ALIVE_MS - sinceLastPostMs).coerceAtLeast(0L)
        val onlyTheNumberMoved = shown.copy(bpm = next.bpm) == next && (shown.bpm == null) == (next.bpm == null)
        if (!onlyTheNumberMoved) return 0L
        return (MIN_SPACING_MS - sinceLastPostMs).coerceAtLeast(0L)
    }
}

/** Posts, updates and removes the live workout's notification, and runs its Pause / Resume action. */
internal object LiveWorkoutNotifier {
    private const val CHANNEL_ID = "noop_live_workout"

    /** Shared with [WhoopConnectionService] on purpose: one ongoing notification, whoever is showing. */
    private const val NOTIF_ID = WhoopConnectionService.NOTIF_ID

    const val ACTION_TOGGLE_PAUSE = "com.noop.notif.action.WORKOUT_TOGGLE_PAUSE"

    /** Android 16's promoted ongoing notification ("Live Update": the status-bar chip and the pinned
     *  lock-screen card, the platform's own Live Activity). The key is a literal because this module
     *  compiles against an SDK that predates the constant; older systems ignore an extra they do not
     *  know. The matching permission is declared in the manifest. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /** What the notification is showing now, or null when the workout has no notification. Read by the
     *  connection service (its own thread) to decide whose content its notification carries. */
    @Volatile
    private var shown: LiveWorkoutContent? = null
    private var lastPostAtMs = 0L

    /** The recording screen's own pause toggle, registered by the ViewModel that owns the workout. Null
     *  when no ViewModel is alive: the action then has nothing to act on. Twin of the iOS
     *  `LiveActivityActions.handler`. */
    @Volatile
    private var togglePause: (() -> Unit)? = null

    /** True while the workout owns the ongoing notification. */
    val showing: Boolean get() = shown != null

    /**
     * The workout's notification as it stands, or null: what the connection service posts in place of
     * its own while a workout records. Null too if its channel could not be made: a FOREGROUND notification
     * on a channel that does not exist takes the app down, so the service then keeps its own.
     */
    fun notification(context: Context): Notification? {
        val content = shown ?: return null
        return runCatching {
            if (!ensureChannel(context)) return null
            build(context, content)
        }.getOrNull()
    }

    /** The language the channel was last named in. The channel is re-made when it changes (the only way
     *  its name follows a language change), not on every post: a post can be two seconds after the last. */
    @Volatile
    private var channelLanguage: String? = null

    /** Make the channel (or re-name it after a language change). Returns whether it exists. */
    private fun ensureChannel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val language = context.resources.configuration.locales[0].toLanguageTag()
        if (channelLanguage != language) {
            NoopNotifications.ensureChannel(
                context, CHANNEL_ID,
                R.string.notif_channel_workout_name, R.string.notif_channel_workout_desc,
                NotificationManager.IMPORTANCE_LOW,
            ) {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val made = runCatching {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .getNotificationChannel(CHANNEL_ID) != null
            }.getOrDefault(false)
            if (!made) return false
            channelLanguage = language
        }
        return true
    }

    /**
     * Follow the workout for the life of [scope] (the ViewModel that owns it): up with the workout, the
     * heart rate as the recording screen has it, down when the workout ends or its switch goes off, and
     * gone with the scope, since nothing feeds it after that.
     */
    fun follow(
        context: Context,
        scope: CoroutineScope,
        workout: Flow<AppViewModel.ActiveWorkout?>,
        bpm: Flow<Int?>,
        onTogglePause: () -> Unit,
    ) {
        val app = context.applicationContext
        togglePause = onTogglePause
        val job = scope.launch {
            combine(workout, bpm, enabled(app)) { w, hr, on -> LiveWorkoutContent.of(w, hr, on) }
                .distinctUntilChanged()
                // collectLatest: a newer state replaces one still waiting out its spacing, and the wait is
                // measured from the last POST, so a number that keeps moving is posted every MIN_SPACING_MS
                // and the last value always lands. (Nothing in here suspends mid-post.)
                .collectLatest { content ->
                    if (content == null) {
                        clear(app)
                        return@collectLatest
                    }
                    delay(
                        LiveWorkoutNotificationPolicy.delayBeforePostMs(
                            shown, content, System.currentTimeMillis() - lastPostAtMs,
                        ),
                    )
                    while (true) {
                        post(app, content)
                        delay(LiveWorkoutNotificationPolicy.KEEP_ALIVE_MS)
                    }
                }
        }
        job.invokeOnCompletion {
            // Only if this follower is still the one in charge: a ViewModel cleared after its successor
            // started must not take the successor's notification down with it.
            if (togglePause === onTogglePause) {
                togglePause = null
                clear(app)
            }
        }
    }

    /** The notification's Pause / Resume action. With no ViewModel alive there is no workout in this
     *  process to pause, so the notification that offered it is stale and is removed. */
    fun onTogglePauseAction(context: Context) {
        val toggle = togglePause
        if (toggle != null) toggle() else clear(context.applicationContext)
    }

    /** The connection service stopped while the workout still records: its notification went with it, so
     *  the workout's is posted again, on its own. After a beat, so the re-post lands after the system has
     *  finished removing the service's. */
    fun onConnectionServiceStopped(context: Context) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ shown?.let { post(app, it) } }, 500L)
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    private fun post(context: Context, content: LiveWorkoutContent) {
        // The channel BEFORE the content is published: the connection service may build this notification
        // for startForeground the moment [shown] is set, on its own thread.
        val channelReady = ensureChannel(context)
        lastPostAtMs = System.currentTimeMillis()
        // No channel, no notification: the workout does not claim the ongoing notification it cannot
        // show, so the connection service keeps posting its own.
        if (!channelReady) return
        shown = content
        // Defensive: a notify() throw (OEM quirk, revoked POST_NOTIFICATIONS) must not reach the workout.
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            NotificationManagerCompat.from(context).notify(NOTIF_ID, build(context, content))
        }
    }

    /** Take the workout's content off the ongoing notification: back to the connection service's own when
     *  it is running, removed otherwise (which also clears one a killed process left behind). */
    private fun clear(context: Context) {
        val wasShowing = shown != null
        shown = null
        if (WhoopConnectionService.isRunning) {
            if (wasShowing) WhoopConnectionService.repostNotification()
        } else {
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
        }
    }

    private fun build(context: Context, content: LiveWorkoutContent): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Tapping it re-opens the recording screen, as the mini-player does.
        val open = PendingIntent.getActivity(context, 10, AppLinks.intent(context, AppLink.Workout), immutable)
        val toggle = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, LiveWorkoutActionReceiver::class.java).setAction(ACTION_TOGGLE_PAUSE),
            immutable,
        )
        // Finish opens the recording screen with its own confirmation up rather than ending the workout
        // from the shade: that screen confirms first and offers Discard (#517), and the notification's
        // button is its button.
        val finish = PendingIntent.getActivity(
            context, 12, AppLinks.intent(context, AppLink.WorkoutFinish), immutable,
        )

        val unit = context.getString(R.string.metric_unit_bpm)
        val heartRate = "${content.bpm?.toString() ?: "—"} $unit"
        val text = if (content.paused) {
            context.getString(R.string.workout_action_paused) + " · " + elapsedClock(content.pausedElapsedSeconds)
        } else {
            heartRate
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setColor(ContextCompat.getColor(context, R.color.notification_workout))
            .setContentTitle(localizedSport(context.resources, content.sportName))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setTimeoutAfter(LiveWorkoutNotificationPolicy.TIMEOUT_MS)
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        if (content.paused) {
            // Honest about pause: no ticking clock, the frozen time in the text instead.
            builder.setShowWhen(false).setUsesChronometer(false)
        } else {
            builder.setWhen(content.chronometerBaseMs).setShowWhen(true).setUsesChronometer(true)
        }
        builder.addAction(
            if (content.paused) R.drawable.ic_stat_play else R.drawable.ic_stat_pause,
            context.getString(if (content.paused) R.string.workout_action_resume else R.string.workout_action_pause),
            toggle,
        )
        builder.addAction(R.drawable.ic_stat_finish, context.getString(R.string.workouts_finish), finish)
        return builder.build()
    }

    /** The switch in Settings > Workouts, as it is now and whenever it changes: it acts at once, not at
     *  the next heart-rate tick. */
    private fun enabled(context: Context): Flow<Boolean> = callbackFlow {
        val prefs = NoopPrefs.of(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WorkoutLiveNotificationPrefs.KEY) trySend(WorkoutLiveNotificationPrefs.enabled(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(WorkoutLiveNotificationPrefs.enabled(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/**
 * The notification's Pause / Resume button. Not exported: only the app's own PendingIntent reaches it.
 * It calls the same toggle the recording screen's button calls; Finish is not here because it is an
 * activity intent ([AppLink.WorkoutFinish]).
 */
class LiveWorkoutActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == LiveWorkoutNotifier.ACTION_TOGGLE_PAUSE) {
            LiveWorkoutNotifier.onTogglePauseAction(context)
        }
    }
}
