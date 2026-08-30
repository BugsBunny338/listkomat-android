package cz.flipcom.listkomat.model

import kotlinx.serialization.Serializable

/**
 * Catalog models, decoded from tickets.json (bundled snapshot or the remote
 * listkomat-catalog repo). Schema is shared with the iOS app — a two-client
 * contract; additive changes only.
 */
@Serializable
data class TicketCatalog(
    val version: Int,
    val updatedAt: String,
    val cities: List<City>,
) {
    companion object {
        val EMPTY = TicketCatalog(version = 0, updatedAt = "", cities = emptyList())
    }
}

/** Per-locale overrides carried by the catalog (`i18n.en.name` etc.). */
@Serializable
data class LocalizedOverrides(
    val name: String? = null,
    val note: String? = null,
)

@Serializable
data class City(
    val key: String,             // stable id, e.g. "praha"
    val name: String,            // display name, e.g. "Praha"
    val lat: Double,
    val lng: Double,
    val smsNumber: String,       // premium SMS recipient, e.g. "90206"
    val tickets: List<Ticket>,
    val hasLiveMap: Boolean? = null,
    val liveMapDisabled: Boolean? = null,
    val i18n: Map<String, LocalizedOverrides>? = null,
) {
    /** Display name for [language] (ISO 639-1), falling back to the Czech original. */
    fun name(language: String): String =
        i18n?.get(language)?.name ?: name
}

@Serializable
data class Ticket(
    val code: String,            // SMS body to send, e.g. "DPT42"
    val duration: String,        // human label, e.g. "30 min", "24 h"
    val durationMinutes: Int,
    val priceKc: Int,
    val note: String? = null,    // e.g. "zlevněný", "vnitřní zóna"
    val i18n: Map<String, LocalizedOverrides>? = null,
) {
    /** Note for [language] (ISO 639-1), falling back to the Czech original. */
    fun note(language: String): String? =
        i18n?.get(language)?.note ?: note
}
