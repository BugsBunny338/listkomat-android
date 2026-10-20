package cz.flipcom.listkomat

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import cz.flipcom.listkomat.data.ActiveTicketStore
import cz.flipcom.listkomat.data.CatalogStore
import cz.flipcom.listkomat.model.ActiveTicket
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.DurationFormat
import cz.flipcom.listkomat.notify.TicketActions
import cz.flipcom.listkomat.notify.TicketNotifications
import cz.flipcom.listkomat.notify.TicketOngoing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the one active ticket for the whole process, so the notification
 * actions, the alarms and the boot receiver can change it with no Activity
 * alive. The in-app banner observes [active]. It is the source of truth; the
 * ongoing notification only mirrors it.
 *
 * Everything here runs on the main thread (ViewModel calls, broadcast
 * receivers), so no locking.
 */
class TicketController private constructor(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : TicketActions {

    private val store = ActiveTicketStore(context)

    private val _active = MutableStateFlow(store.load())
    val active: StateFlow<ActiveTicket?> = _active

    /** User confirmed they sent the SMS: start the countdown. */
    fun start(ticket: ActiveTicket) {
        store.save(ticket)
        _active.value = ticket
        TicketNotifications.schedule(context, ticket)
        showOngoing(ticket)
    }

    /** Confirmation SMS arrived early: re-anchor validity to now. Only while
     *  pending, because a late tap on a stale shade button must not extend
     *  a ticket that is already valid. */
    override fun confirmNow() {
        val current = _active.value ?: return
        val nowMs = now()
        if (!current.timeline.isPending(nowMs)) return
        val updated = current.copy(timeline = current.timeline.confirmed(nowMs))
        store.save(updated)
        _active.value = updated
        TicketNotifications.schedule(context, updated)
        showOngoing(updated)
    }

    override fun end() {
        store.clear()
        _active.value = null
        TicketNotifications.cancelAll(context)
        TicketOngoing.cancel(context)
    }

    /** The pending→valid alarm fired. */
    override fun flipped() = refresh()

    /** The expiry alarm fired. The ticket stays in the store, so the banner
     *  can show its expired state until the user ends it. */
    override fun expired() {
        val ticket = _active.value ?: return
        // Inexact alarms never fire early, but one left over from before a
        // re-anchor could. Then the ticket is still running, so just repost.
        if (!ticket.timeline.isExpired(now())) return showOngoing(ticket)
        TicketOngoing.cancel(context)
        TicketNotifications.postExpired(context)
    }

    /** Repost the ongoing notification for the current phase (app resumed,
     *  permission granted, theme changed). */
    fun refresh() {
        _active.value?.let(::showOngoing)
    }

    /**
     * App start, boot or app update: alarms may be gone (reboot and
     * force-stop clear them) and the notification may be missing, so
     * reschedule and repost. Drops a ticket that expired long ago.
     */
    fun restore() {
        val ticket = _active.value ?: return
        val nowMs = now()
        if (nowMs >= ticket.timeline.endMs + EXPIRED_KEEP_MS) return end()
        if (!ticket.timeline.isExpired(nowMs)) TicketNotifications.schedule(context, ticket)
        showOngoing(ticket)
    }

    private fun showOngoing(ticket: ActiveTicket) {
        val language = context.resources.configuration.locales[0].language
        // The ticket stores the Czech name and the label from purchase time.
        // Resolve both again so they follow the current app language.
        val cityName = CatalogStore(context).loadCachedOrBundled().cities
            .firstOrNull { it.key == ticket.cityKey }?.name(language) ?: ticket.cityName
        val minutes = (ticket.timeline.durationMs / 60_000).toInt()
        val themeId = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .getString("theme_id", null)
        TicketOngoing.post(
            context, ticket,
            cityName = cityName,
            ticketLabel = DurationFormat.format(context, minutes),
            accent = AppTheme.resolve(themeId).accent.toArgb(),
            nowMs = now(),
        )
    }

    companion object {
        /** How long an expired ticket stays visible after endMs. */
        const val EXPIRED_KEEP_MS: Long = 60 * 60_000

        // Holds the application context only (see get), which lives as long
        // as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: TicketController? = null

        fun get(context: Context): TicketController =
            instance ?: synchronized(this) {
                instance ?: TicketController(context.applicationContext).also { instance = it }
            }
    }
}
