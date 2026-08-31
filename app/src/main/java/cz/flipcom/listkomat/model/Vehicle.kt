package cz.flipcom.listkomat.model

import androidx.compose.ui.graphics.Color

/** Kind of transit vehicle, for tinting the map marker. */
enum class VehicleKind { TRAM, METRO, TROLLEYBUS, BUS, TRAIN, FERRY }

/** A live transit vehicle position, normalized across data sources. */
data class Vehicle(
    val id: String,
    val lat: Double,
    val lng: Double,
    val bearing: Double?,        // null when unknown (the feeds send -1)
    val line: String,            // "1", "258"
    val kind: VehicleKind,
    val updatedAtMs: Long,
    val destinationId: Int?,     // Brno: FinalStopID, resolved to a stop name
    val destinationName: String?,// Prague: trip_headsign, used directly
    val delay: Double? = null,   // minutes; Brno stream only
)

/** Fill and glyph colour for one live-map marker. */
data class MarkerStyle(val fill: Color, val glyph: Color)

/**
 * Single source of truth for live-map marker colours, ported 1:1 from the iOS
 * TransitPalette. The hexes are search-derived against a contrast/CVD
 * validator — never hand-edit (see the iOS palette design doc).
 */
object TransitPalette {
    private val metroA = Color(0xFF00A05A)
    private val metroB = Color(0xFFFFCE00)
    private val metroC = Color(0xFFE1252B)
    private val metroUnknown = Color(0xFFE0812B)

    fun fill(kind: VehicleKind, line: String): Color = when (kind) {
        VehicleKind.METRO -> when (line) {
            "A" -> metroA
            "B" -> metroB
            "C" -> metroC
            else -> metroUnknown
        }
        VehicleKind.TRAM -> Color(0xFFD872A5)
        VehicleKind.TROLLEYBUS -> Color(0xFF276F5D)
        VehicleKind.FERRY -> Color(0xFF03AED8)
        VehicleKind.BUS -> Color(0xFF3F72C6)
        VehicleKind.TRAIN -> Color(0xFF7E4587)
    }

    fun style(kind: VehicleKind, line: String): MarkerStyle {
        val fill = fill(kind, line)
        return MarkerStyle(fill, GlyphContrast.readableGlyph(fill))
    }
}
