package cz.flipcom.listkomat.data

import cz.flipcom.listkomat.model.Vehicle
import cz.flipcom.listkomat.model.VehicleKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Decoding + snapshot for Brno's GeoEvent WebSocket vehicle stream (the
 * queryable FeatureServer was retired upstream 2026-08-10; the year-less
 * `Kordis_stream` is the live feed). Pure logic, unit-testable — the socket
 * itself lives in [LiveSources]. Semantics ported from the iOS
 * BrnoStreamDecoder/BrnoStreamSnapshot.
 */
object BrnoStream {

    const val STREAM_URL =
        "wss://gis.brno.cz/geoevent/ws/services/Kordis_stream/StreamServer/subscribe"

    /** KORDIS bursts the full snapshot every ~28 s — wait at least this long
     *  for the first message before calling the stream dead. */
    const val FIRST_MESSAGE_TIMEOUT_MS = 40_000L

    /** Let the initial burst drain before the first read paints the map. */
    const val BURST_SETTLE_MS = 2_500L

    /** Silence on an established socket that means the feed died under us. */
    const val STALL_TIMEOUT_MS = 70_000L

    /** Keep only vehicles that reported within this window (seconds). */
    const val FRESHNESS_LIMIT_SEC = 120L

    data class BoundingBox(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
        fun contains(lat: Double, lng: Double) = lat in minLat..maxLat && lng in minLng..maxLng
        companion object { val BRNO_AREA = BoundingBox(48.85, 49.55, 16.15, 17.05) }
    }

    /** VType codes sampled live: 0 tram, 1 trolleybus, 5 train, 2/4 bus. */
    fun kind(vType: Int): VehicleKind = when (vType) {
        0 -> VehicleKind.TRAM
        1 -> VehicleKind.TROLLEYBUS
        5 -> VehicleKind.TRAIN
        else -> VehicleKind.BUS
    }

    @Serializable private data class Message(val geometry: Geo, val attributes: Attrs)
    @Serializable private data class Geo(val x: Double, val y: Double)   // x = lng, y = lat
    @Serializable private data class Attrs(
        val ID: Long, val VType: Int, val Bearing: Double, val LineName: String,
        val IsInactive: String, val TimeUpdated: Double, val FinalStopID: Int? = null,
        val Delay: Double? = null,
    )

    /** One stream message: null vehicle = remove (inactive / out of area). */
    data class Update(val id: String, val vehicle: Vehicle?)

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(body: String, bbox: BoundingBox? = BoundingBox.BRNO_AREA): Update {
        val m = json.decodeFromString<Message>(body)
        val id = m.attributes.ID.toString()
        if (m.attributes.IsInactive == "true") return Update(id, null)
        if (bbox != null && !bbox.contains(m.geometry.y, m.geometry.x)) return Update(id, null)
        return Update(id, Vehicle(
            id = id, lat = m.geometry.y, lng = m.geometry.x,
            bearing = m.attributes.Bearing.takeIf { it >= 0 },
            line = m.attributes.LineName,
            kind = kind(m.attributes.VType),
            updatedAtMs = m.attributes.TimeUpdated.toLong(),
            destinationId = m.attributes.FinalStopID?.takeIf { it > 0 },
            destinationName = null,
            delay = m.attributes.Delay,
        ))
    }
}

/** Latest known position per vehicle, accumulated between the map's polls. */
class BrnoStreamSnapshot {
    private val byId = HashMap<String, Vehicle>()

    @Synchronized fun apply(update: BrnoStream.Update) {
        if (update.vehicle == null) byId.remove(update.id) else byId[update.id] = update.vehicle
    }

    /** Freshest first, so any on-screen cap keeps the recent ones. */
    @Synchronized fun vehicles(
        nowMs: Long = System.currentTimeMillis(),
        fresherThanSec: Long = BrnoStream.FRESHNESS_LIMIT_SEC,
    ): List<Vehicle> = byId.values
        .filter { nowMs - it.updatedAtMs <= fresherThanSec * 1000 }
        .sortedByDescending { it.updatedAtMs }

    /** Evict entries that stopped updating so the map doesn't grow stale ghosts. */
    @Synchronized fun prune(nowMs: Long = System.currentTimeMillis(), olderThanSec: Long = 600) {
        byId.entries.removeAll { nowMs - it.value.updatedAtMs > olderThanSec * 1000 }
    }

    @Synchronized fun clear() = byId.clear()
}
