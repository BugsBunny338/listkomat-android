package cz.flipcom.listkomat.data

import cz.flipcom.listkomat.model.Vehicle
import cz.flipcom.listkomat.model.VehicleKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Decoding for Prague's PID vehicle feed, delivered as compact JSON by the
 * Lístkomat proxy. Pure static funcs, unit-testable without the network —
 * a port of the iOS PragueVehicleSource.
 */
object PragueVehicleSource {

    const val ENDPOINT = "https://listkomat-proxy.listkomat.workers.dev/prague/vehicles"

    @Serializable private data class Payload(val vehicles: List<Wire>)
    @Serializable private data class Wire(
        val id: String, val lat: Double, val lng: Double, val brng: Double? = null,
        val line: String, val rt: Int, val ts: String, val dest: String? = null,
    )

    data class BoundingBox(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
        fun contains(lat: Double, lng: Double) =
            lat in minLat..maxLat && lng in minLng..maxLng
        companion object {
            /** Generous — PID includes regional trains + the airport bus. */
            val PRAGUE_AREA = BoundingBox(49.4, 50.8, 12.8, 15.9)
        }
    }

    /** Drop vehicles whose last report is older than this (seconds). */
    const val FRESHNESS_LIMIT = 180L

    /** GTFS route_type → marker kind. */
    fun kind(routeType: Int): VehicleKind = when (routeType) {
        0 -> VehicleKind.TRAM
        1 -> VehicleKind.METRO
        2 -> VehicleKind.TRAIN
        4 -> VehicleKind.FERRY
        11 -> VehicleKind.TROLLEYBUS
        else -> VehicleKind.BUS
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Upstream stamps arrive with and without fractional seconds and offsets. */
    fun parseTimestampMs(ts: String): Long? =
        runCatching { Instant.parse(ts).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(ts).toInstant().toEpochMilli() }.getOrNull()

    fun decode(
        body: String,
        bbox: BoundingBox? = BoundingBox.PRAGUE_AREA,
        fresherThanSec: Long? = FRESHNESS_LIMIT,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Vehicle> =
        json.decodeFromString<Payload>(body).vehicles.mapNotNull { w ->
            if (bbox != null && !bbox.contains(w.lat, w.lng)) return@mapNotNull null
            // An unparseable stamp must NOT default to `now` — that would make
            // the vehicle permanently exempt from the freshness filter.
            val at = parseTimestampMs(w.ts) ?: return@mapNotNull null
            if (fresherThanSec != null && nowMs - at > fresherThanSec * 1000) return@mapNotNull null
            Vehicle(
                id = w.id, lat = w.lat, lng = w.lng,
                bearing = w.brng?.takeIf { it >= 0 },
                line = w.line, kind = kind(w.rt), updatedAtMs = at,
                destinationId = null, destinationName = w.dest,
            )
        }
}
