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
import cz.flipcom.listkomat.TicketController
import cz.flipcom.listkomat.model.ActiveTicket

/**
 * The ticket's two alarms and the ticket-expiry notification. When a
 * countdown starts (or re-anchors via Confirm now), alarms are scheduled at
 * validFromMs (the ongoing notification flips pending → valid) and at endMs
 * ("Jízdenka vypršela"). Ending the ticket cancels both.
 *
 * Uses inexact setAndAllowWhileIdle deliberately — exact alarms need the
 * SCHEDULE_EXACT_ALARM special permission (denied by default since API 33)
 * and Play policy fences USE_EXACT_ALARM to alarm-clock apps. A few minutes
 * of doze slack on an already-expired ticket is an acceptable trade for
 * staying permission-light. In-app, the banner flips to "expired" on time
 * regardless, and the ongoing notification times itself out at endMs.
 */
object TicketNotifications {

    private const val CHANNEL_ID = "ticket"
    private const val NOTIFICATION_ID = 1
    private const val REQUEST_CODE = 1
    private const val FLIP_REQUEST_CODE = 2

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

    /** (Re)arms both alarms for [ticket]; the flip alarm only while pending. */
    fun schedule(context: Context, ticket: ActiveTicket, nowMs: Long) {
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, ticket.timeline.endMs, expiryIntent(context))
        if (ticket.timeline.isPending(nowMs)) {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, ticket.timeline.validFromMs, flipIntent(context))
        } else {
            am.cancel(flipIntent(context))
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(expiryIntent(context))
        am.cancel(flipIntent(context))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun postExpired(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_expired_title))
            .setContentText(context.getString(R.string.notification_expired_body))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        // POST_NOTIFICATIONS may have been declined — NotificationManager
        // silently drops it then, which is exactly the user's choice.
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    /** Brings the existing task forward like the launcher icon does, rather
     *  than stacking a second MainActivity on top of it. */
    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // No action, as in build 5: same PendingIntent, so a reschedule replaces
    // an expiry alarm armed before the update (see TicketIntents.route).
    private fun expiryIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun flipIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, FLIP_REQUEST_CODE,
            Intent(context, ExpiryReceiver::class.java).setAction(TicketIntents.FLIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Both alarms land here: the flip (action FLIP) and expiry (no action). */
    class ExpiryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            TicketIntents.route(intent.action, TicketController.get(context))
        }
    }
}
