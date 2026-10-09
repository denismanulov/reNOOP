package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.NotifPrefs
import com.noop.ui.appLaunchIntent

/**
 * #577 — posts the inactivity (sedentary) wrist nudge as a real system notification, mirroring the iOS
 * `AppModel.postInactivity`. A pocketed phone can't show the strap buzz on screen the way the Mac does,
 * so a wrist buzz the user might miss is ALSO surfaced as a local notification. Called from
 * `WhoopBleClient.maybeBuzzInactivity` right after the buzz fires.
 *
 * Gated on the SAME wrist-alerts master ([NotifPrefs.MASTER]) the SedentaryDetector reads, so turning
 * wrist alerts off silences this too — belt-and-suspenders, since the engine's `mayBuzz` already checks
 * the master before reporting `shouldBuzz`.
 */
object InactivityNotifier {
    private const val CHANNEL_ID = "noop_inactivity"
    private const val NOTIF_ID = 4203   // 4201 = ongoing connection, 4202 = illness watch

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun onNudged(context: Context, minutes: Int) {
        // Mirror the iOS master gate: the engine already honours it before buzzing, re-check anyway.
        if (!NotifPrefs.getBool(context, NotifPrefs.MASTER, false)) return
        val body = if (minutes > 0) {
            context.getString(R.string.inactivity_body_minutes, minutes)
        } else {
            context.getString(R.string.inactivity_body)
        }
        // Defensive: never let a notify() throw (revoked POST_NOTIFICATIONS, OEM quirk) crash the offload.
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val openApp = PendingIntent.getActivity(
                context, 5,
                appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NoopNotifications.builder(
                context, CHANNEL_ID, R.drawable.ic_stat_move,
                context.getString(R.string.inactivity_title),
                body,
            )
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        }
    }

    private fun ensureChannel(context: Context) {
        NoopNotifications.ensureChannel(
            context, CHANNEL_ID,
            R.string.notif_channel_inactivity_name, R.string.notif_channel_inactivity_desc,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }
}
