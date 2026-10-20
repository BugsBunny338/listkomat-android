package cz.flipcom.listkomat.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.flipcom.listkomat.TicketController

/** What the receivers can ask of the ticket, implemented by [TicketController].
 *  An interface so the routing below is unit-testable without Android. */
interface TicketActions {
    fun confirmNow()
    fun end()
    fun flipped()
    fun expired()
    fun dismissed()
}

object TicketIntents {
    const val CONFIRM_NOW = "cz.flipcom.listkomat.action.CONFIRM_NOW"
    const val END = "cz.flipcom.listkomat.action.END"
    const val FLIP = "cz.flipcom.listkomat.action.FLIP"
    const val DISMISSED = "cz.flipcom.listkomat.action.DISMISSED"

    /**
     * Routes an alarm or notification-button broadcast (both reach only our
     * non-exported receivers) to [target]; false if it isn't ours. The
     * expiry alarm carries no action, exactly as in build 5, so rescheduling
     * replaces an alarm that build armed instead of adding a second one.
     */
    fun route(action: String?, target: TicketActions): Boolean {
        when (action) {
            CONFIRM_NOW -> target.confirmNow()
            END -> target.end()
            FLIP -> target.flipped()
            DISMISSED -> target.dismissed()
            null -> target.expired()
            else -> return false
        }
        return true
    }
}

/** The ongoing notification's buttons ("Potvrdit nyní", "Ukončit") and its
 *  swipe-away. These work with the app dead; the process is started just
 *  for this. */
class TicketActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TicketIntents.route(intent.action, TicketController.get(context))
    }
}

/** Reboot clears alarms and notifications, so put both back. After an app
 *  update, restoring adds the pending→valid alarm and the ongoing
 *  notification to a ticket bought on an older build. */
class TicketBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the system broadcasts, so anyone can send it an
        // explicit intent: act on nothing but those two.
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED ->
                TicketController.get(context).restore(afterBoot = true)
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                TicketController.get(context).restore()
        }
    }
}
