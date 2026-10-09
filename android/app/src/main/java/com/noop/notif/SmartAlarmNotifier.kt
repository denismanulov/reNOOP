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
 * #577 — posts the smart-alarm wake as a local notification, mirroring the iOS `AppModel.postSmartAlarm`.
 * The strap buzzes your wrist at the smart-alarm time; a pocketed phone surfaces it as a notification too
 * so a missed wrist buzz still reaches you. Called from `AppViewModel` when the strap reports
 * `STRAP_DRIVEN_ALARM_EXECUTED` (WhoopBleClient.onSmartAlarmFired).
 *
 * Gated on the SAME wrist-alerts master ([NotifPrefs.MASTER]) the inactivity/illness posters use, so
 * turning wrist alerts off silences it. Twin of [InactivityNotifier].
 */
object SmartAlarmNotifier {
    private const val CHANNEL_ID = "noop_smart_alarm"
    private const val NOTIF_ID = 4204   // 4201 = connection, 4202 = illness, 4203 = inactivity

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun onFired(context: Context) {
        if (!NotifPrefs.getBool(context, NotifPrefs.MASTER, false)) return
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val openApp = PendingIntent.getActivity(
                context, 6,
                appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NoopNotifications.builder(
                context, CHANNEL_ID, R.drawable.ic_stat_alarm,
                context.getString(R.string.smart_alarm_title),
                context.getString(R.string.smart_alarm_body),
            )
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        }
    }

    private fun ensureChannel(context: Context) {
        // The same channel id, name and description as the phone's own wake alarm
        // (com.noop.alarm.SmartAlarmReceiver): two owners of one channel must name it identically, or
        // its name in system Settings would flip with whichever posted last.
        NoopNotifications.ensureChannel(
            context, CHANNEL_ID,
            R.string.notif_channel_alarm_name, R.string.notif_channel_alarm_desc,
            NotificationManager.IMPORTANCE_HIGH,
        )
    }
}
