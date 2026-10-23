package cz.flipcom.listkomat

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import cz.flipcom.listkomat.data.ActiveTicketStore
import cz.flipcom.listkomat.data.CatalogStore
import cz.flipcom.listkomat.model.ActiveTicket
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.TicketCatalog
import cz.flipcom.listkomat.model.title
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
class TicketController private constructor(private val context: Context) : TicketActions {

    private val store = ActiveTicketStore(context)

    private val _active = MutableStateFlow(store.load())
    val active: StateFlow<ActiveTicket?> = _active

    /** Only for the city's display name, so the copy loaded at first use
     *  is good enough: names don't change between catalog refreshes. */
    private val catalog: TicketCatalog by lazy { CatalogStore(context).loadCachedOrBundled() }

    private fun now() = System.currentTimeMillis()

    /** User confirmed they sent the SMS: start the countdown. */
    fun start(ticket: ActiveTicket) {
        save(ticket)
        // Also clears a "Jízdenka vypršela" left over from the previous ticket.
        TicketNotifications.cancelAll(context)
        TicketNotifications.schedule(context, ticket, now())
        showOngoing(ticket)
    }

    /** Confirmation SMS arrived early: re-anchor validity to now. Only while
     *  pending, because a late tap on a stale shade button must not extend
     *  a ticket that is already valid. That tap just reposts. */
    override fun confirmNow() {
        val current = _active.value ?: return
        val nowMs = now()
        if (!current.timeline.isPending(nowMs)) return showOngoing(current)
        val updated = current.copy(timeline = current.timeline.confirmed(nowMs))
        save(updated)
        TicketNotifications.schedule(context, updated, nowMs)
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

    /** The user swiped the notification away (possible since API 34). Don't
     *  bring it back for this ticket; the in-app banner still shows it. */
    override fun dismissed() {
        val ticket = _active.value ?: return
        save(ticket.copy(ongoingDismissed = true))
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
     * [afterBoot]: the reboot also took the expiry alarm and notification,
     * so a ticket that expired meanwhile still gets its "Jízdenka vypršela".
     */
    fun restore(afterBoot: Boolean = false) {
        val ticket = _active.value ?: return
        val nowMs = now()
        if (nowMs >= ticket.timeline.endMs + EXPIRED_KEEP_MS) return end()
        if (ticket.timeline.isExpired(nowMs)) {
            if (afterBoot) TicketNotifications.postExpired(context)
        } else {
            TicketNotifications.schedule(context, ticket, nowMs)
        }
        showOngoing(ticket)
    }

    private fun save(ticket: ActiveTicket) {
        store.save(ticket)
        _active.value = ticket
    }

    private fun showOngoing(ticket: ActiveTicket) {
        if (ticket.ongoingDismissed) return
        val themeId = context.getSharedPreferences(AppViewModel.STATE_PREFS, Context.MODE_PRIVATE)
            .getString(AppViewModel.THEME_KEY, null)
        // The application context's resources are updated in place on a
        // runtime language switch, and on a Play (split) install their plural
        // cache keeps the old language: "Prague · 30 minut" (android#36).
        // A fresh configuration context starts with an empty cache.
        val localized = context.createConfigurationContext(context.resources.configuration)
        TicketOngoing.post(
            localized, ticket,
            title = ticket.title(localized, catalog),
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
