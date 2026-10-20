package cz.flipcom.listkomat.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.model.ActiveTicket
import cz.flipcom.listkomat.model.TicketPhase
import android.text.format.DateFormat
import java.util.Date

/**
 * The silent, ongoing notification that carries the active ticket outside
 * the app. It is the Android counterpart of the iOS Live Activity. A
 * system chronometer counts down to endMs in both phases, so nothing in the
 * app ticks. The flip between the phases is a repost from the validFrom
 * alarm (or Confirm now) that changes only the text. The inexact alarm lands
 * up to ~90 s after validFrom (0.75 × the 120 s buffer, measured 82–84 s).
 * A countdown to validFrom would run past zero ("−1:24") in that gap, so
 * the pending text names the start time instead. Both phases time
 * themselves out at endMs, so the notification is gone on the dot even when
 * the inexact alarms lag (or an OEM battery manager drops them). No
 * foreground service, no exact alarms, no per-minute updates.
 *
 * On API 36 it also asks to be promoted to a Live Update (status-bar chip,
 * top of the shade, lock screen). ProgressStyle was considered and left out:
 * its progress is a static value, so it would stand still between reposts
 * while the chronometer next to it keeps moving.
 */
object TicketOngoing {

    private const val CHANNEL_ID = "ticket_ongoing"
    private const val NOTIFICATION_ID = 2
    private const val CONFIRM_REQUEST_CODE = 10
    private const val END_REQUEST_CODE = 11
    private const val DISMISSED_REQUEST_CODE = 12

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING. androidx.core 1.17's
     *  setRequestPromotedOngoing() sets this same extra. That version is not
     *  used here because it isn't verified against the Kotlin 2.0 pin. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_ongoing),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    /**
     * Posts (or replaces) the notification for [ticket]'s phase at [nowMs];
     * cancels it once expired. [accent] is the resolved theme accent (ARGB).
     */
    fun post(
        context: Context,
        ticket: ActiveTicket,
        cityName: String,
        ticketLabel: String,
        accent: Int,
        nowMs: Long,
    ) {
        val timeline = ticket.timeline
        val phase = timeline.phase(nowMs)
        if (phase == TicketPhase.EXPIRED) return cancel(context)
        ensureChannel(context)

        val pending = phase == TicketPhase.PENDING
        val label = "$cityName · $ticketLabel"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(TicketNotifications.openApp(context))
            // Swipeable since API 34; remember it so reposts respect it.
            .setDeleteIntent(action(context, TicketIntents.DISMISSED, DISMISSED_REQUEST_CODE))
            .setTimeoutAfter(timeline.endMs - nowMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(timeline.endMs)

        val timeFormat = DateFormat.getTimeFormat(context)
        if (pending) {
            val from = timeFormat.format(Date(timeline.validFromMs))
            builder
                .setContentTitle(context.getString(R.string.ongoing_pending_title))
                .setContentText(context.getString(R.string.ongoing_pending_text, label, from))
                .addAction(0, context.getString(R.string.confirm_now),
                    action(context, TicketIntents.CONFIRM_NOW, CONFIRM_REQUEST_CODE))
        } else {
            val until = timeFormat.format(Date(timeline.endMs))
            builder
                .setContentTitle(label)
                .setContentText(context.getString(R.string.ticket_valid_until, until))
        }
        builder.addAction(0, context.getString(R.string.end_ticket),
            action(context, TicketIntents.END, END_REQUEST_CODE))

        if (Build.VERSION.SDK_INT >= 36) {
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }

        // Dropped silently if POST_NOTIFICATIONS was declined; the in-app
        // banner is the source of truth either way.
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, TicketActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
