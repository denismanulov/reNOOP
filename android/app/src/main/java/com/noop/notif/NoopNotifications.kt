package com.noop.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.noop.R

/**
 * The Material 3 chrome every reNOOP notification shares, so the notifiers differ only in what they say
 * and when: a small icon that names the kind of notice (a battery, an alarm, a runner — not one heart for
 * all of them), the accent the icon is drawn in (the wallpaper's on Android 12+), a body that expands to
 * its full text instead of being cut at one line, and a channel whose name and description are in the
 * reader's language.
 *
 * Nothing here decides WHETHER or WHEN a notification is posted; that stays with each notifier's policy.
 */
internal object NoopNotifications {

    /**
     * Create the channel, or refresh its name and description when it already exists.
     *
     * Deliberately NOT skipped when the channel exists. `createNotificationChannel` is idempotent, and
     * re-creating is the only way a channel's user-visible name follows a language change: it is set once
     * at creation and shown in system Settings, so an early return left it in the install-time language
     * (several channels were plain English literals). The OS ignores every other field of an existing
     * channel — importance, sound, vibration — so what the user or the first creation set stays as it is.
     *
     * [configure] sets what only the FIRST creation can: sound, vibration, badge.
     */
    fun ensureChannel(
        context: Context,
        id: String,
        @StringRes name: Int,
        @StringRes description: Int,
        importance: Int,
        configure: NotificationChannel.() -> Unit = {},
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Defensive: channel creation can throw on some OEM ROMs / under memory pressure; a notifier must
        // never take its caller (a collector, a broadcast, the foreground service) down with it.
        runCatching {
            val channel = NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
                configure()
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /**
     * A builder with the shared chrome applied: [icon], the accent colour, [title], and [body] both as the
     * one-line text and as the expanded text, so a long body (a battery estimate, a brief, any sentence in
     * a longer language) is readable in full when the notification is pulled open.
     */
    fun builder(
        context: Context,
        channelId: String,
        @DrawableRes icon: Int,
        title: CharSequence,
        body: CharSequence?,
        expandedBody: CharSequence? = body,
    ): NotificationCompat.Builder {
        val b = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
        if (body != null) b.setContentText(body)
        if (expandedBody != null) b.setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
        return b
    }
}
