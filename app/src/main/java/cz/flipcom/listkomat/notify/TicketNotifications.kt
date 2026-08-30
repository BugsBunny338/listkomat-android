package cz.flipcom.listkomat.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cz.flipcom.listkomat.MainActivity
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.model.ActiveTicket

/**
 * The ticket-expiry notification: scheduled at endMs when a countdown starts
 * (or re-anchors via Confirm now), cancelled when the user ends the ticket.
 *
 * Uses inexact setAndAllowWhileIdle deliberately — exact alarms need the
 * SCHEDULE_EXACT_ALARM special permission (denied by default since API 33)
 * and Play policy fences USE_EXACT_ALARM to alarm-clock apps. A few minutes
 * of doze slack on an already-expired ticket is an acceptable trade for
 * staying permission-light. In-app, the banner flips to "expired" on time
 * regardless.
 */
object TicketNotifications {

    private const val CHANNEL_ID = "ticket"
    private const val NOTIFICATION_ID = 1
    private const val REQUEST_CODE = 1

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_ticket),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    fun scheduleExpiry(context: Context, ticket: ActiveTicket) {
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, ticket.timeline.endMs, expiryIntent(context))
    }

    fun cancelExpiry(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(expiryIntent(context))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun expiryIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    class ExpiryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ensureChannel(context)
            val tap = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_expired_title))
                .setContentText(context.getString(R.string.notification_expired_body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            // POST_NOTIFICATIONS may have been declined — NotificationManager
            // silently drops it then, which is exactly the user's choice.
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }
}
