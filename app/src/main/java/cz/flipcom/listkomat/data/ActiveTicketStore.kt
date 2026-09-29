package cz.flipcom.listkomat.data

import android.content.Context
import cz.flipcom.listkomat.model.ActiveTicket
import cz.flipcom.listkomat.model.PendingTicket
import cz.flipcom.listkomat.model.PurchaseHandoff
import kotlinx.serialization.json.Json

/**
 * Persists the single active ticket in SharedPreferences so the countdown
 * survives process death (the validity window outlives the app by design),
 * and the purchase handed to the SMS app but not yet answered, so a process
 * death while the user is in Messages still asks on return.
 */
class ActiveTicketStore(context: Context) : PurchaseHandoff.Store {

    private val prefs = context.getSharedPreferences("active_ticket", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): ActiveTicket? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<ActiveTicket>(raw) }.getOrNull()
    }

    fun save(ticket: ActiveTicket) {
        prefs.edit().putString(KEY, json.encodeToString(ActiveTicket.serializer(), ticket)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    override fun loadPending(): PendingTicket? {
        val raw = prefs.getString(PENDING_KEY, null) ?: return null
        return runCatching { json.decodeFromString<PendingTicket>(raw) }.getOrNull()
    }

    // commit(), not apply(): the process may die moments after the hand-off,
    // and this write is the only record that the user is buying a ticket.
    override fun savePending(pending: PendingTicket) {
        prefs.edit().putString(PENDING_KEY, json.encodeToString(PendingTicket.serializer(), pending)).commit()
    }

    override fun clearPending() {
        prefs.edit().remove(PENDING_KEY).apply()
    }

    private companion object {
        const val KEY = "ticket"
        const val PENDING_KEY = "pending"
    }
}
