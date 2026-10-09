package com.noop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.noop.data.WhoopRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// MARK: - Links into the app (twin of iOS WidgetLink)
//
// A tap outside the app — a home-screen widget, the live-workout notification — opens the app where the
// tap promised (audit WG-1). Each surface carries one `noop://<host>` link on an explicit MainActivity
// intent; the activity hands it to [AppLinks] and the shell ([AppRoot]) turns it into a tab selection and a
// push, as a tap on the same row would. The hosts are the iOS `WidgetLink` raw values, so the two
// platforms name a route identically.

/** One place a widget or notification can open. */
internal enum class AppLink(val host: String) {
    /** The rings and compact widgets: the Summary at its root. */
    Today("today"),

    /** The heart-rate widget: the Heart Rate page. */
    HeartRate("heart-rate"),

    /** The stress widget: the Day Stress metric page, pushed on the Summary. */
    Stress("stress"),

    /** The Coach brief widget: Coach. */
    Coach("coach"),

    /** The live-workout notification: the recording screen, as the mini-player opens it. */
    Workout("workout"),

    /** The live-workout notification's Finish action: the recording screen with its own confirmation up,
     *  so a stray tap in the shade ends nothing (#517). */
    WorkoutFinish("workout-finish");

    /** `noop://<host>`. Distinct per link, which also keeps the PendingIntents of two surfaces apart. */
    val uri: String get() = "$SCHEME://$host"

    companion object {
        const val SCHEME = "noop"

        /** The link a `noop://<host>` URI names, or null for anything else (case-insensitive, as iOS). */
        fun from(scheme: String?, host: String?): AppLink? {
            if (scheme?.lowercase() != SCHEME) return null
            val h = host?.lowercase() ?: return null
            return entries.firstOrNull { it.host == h }
        }
    }
}

/**
 * What the shell does for a link. Pure, so the routing is pinned by a JVM test with no NavController.
 */
internal sealed interface AppLinkTarget {
    /** Show [tab] at its root. */
    data class TabRoot(val tab: MainTab) : AppLinkTarget

    /** Open [route] inside [tab], as a tap on its row would. */
    data class Screen(val tab: MainTab, val route: String) : AppLinkTarget

    /** Push a metric's page on [tab]'s root. */
    data class Metric(val tab: MainTab, val key: String, val source: String?) : AppLinkTarget

    /** Open the recording screen; [confirmFinish] raises its Finish confirmation. */
    data class Recording(val confirmFinish: Boolean) : AppLinkTarget

    companion object {
        /**
         * Where [link] lands. A link whose subject is gone resolves to null and the app stays where it
         * was: a workout link after the workout ended, a Coach link while the AI Coach switch is off.
         */
        fun of(link: AppLink, workoutActive: Boolean, coachEnabled: Boolean): AppLinkTarget? = when (link) {
            AppLink.Today -> TabRoot(MainTab.Summary)
            AppLink.HeartRate -> Screen(MainTab.Summary, Destination.Live.route)
            // The strap's own Day Stress (0-3), the curve the widget draws.
            AppLink.Stress -> Metric(MainTab.Summary, "stress", WhoopRepository.WHOOP_SOURCE)
            AppLink.Coach -> if (coachEnabled) TabRoot(MainTab.Coach) else null
            AppLink.Workout -> if (workoutActive) Recording(confirmFinish = false) else null
            AppLink.WorkoutFinish -> if (workoutActive) Recording(confirmFinish = true) else null
        }
    }
}

/** The link waiting for the shell, and the intents that carry one. */
internal object AppLinks {
    private val _pending = MutableStateFlow<AppLink?>(null)

    /** The link the shell has not acted on yet. One slot: a second tap replaces the first. */
    val pending: StateFlow<AppLink?> = _pending.asStateFlow()

    /** Called by the shell once it has acted on (or dropped) the pending link. */
    fun consume() {
        _pending.value = null
    }

    /** Hand the link an activity intent carries, if any, to the shell. */
    fun deliver(intent: Intent?) {
        val data = intent?.data ?: return
        AppLink.from(data.scheme, data.host)?.let { _pending.value = it }
    }

    /**
     * The intent that opens the app at [link]: the launcher's own intent (so the running task is brought
     * forward rather than a second activity stacked on it) carrying the link as its data.
     */
    fun intent(context: Context, link: AppLink): Intent =
        appLaunchIntent(context).setData(Uri.parse(link.uri))
}
