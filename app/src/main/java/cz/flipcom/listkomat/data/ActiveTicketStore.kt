package cz.flipcom.listkomat.data

import android.content.Context
import cz.flipcom.listkomat.model.ActiveTicket
import kotlinx.serialization.json.Json

/**
 * Persists the single active ticket in SharedPreferences so the countdown
 * survives process death (the validity window outlives the app by design).
 */
class ActiveTicketStore(context: Context) {

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

    private companion object {
        const val KEY = "ticket"
    }
}
