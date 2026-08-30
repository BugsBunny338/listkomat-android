package cz.flipcom.listkomat.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nearest-city math for the GPS default, mirroring the iOS LocationManager:
 * auto-pick the nearest supported city, but only if it's plausibly where the
 * user actually is — anywhere in CZ is within ~100 km of a supported city,
 * abroad is obviously not. Pure and unit-testable.
 */
object NearestCity {

    /** Don't auto-select a city if the nearest one is farther than this. */
    const val MAX_DEFAULT_DISTANCE_KM = 100.0

    data class Result(val city: City, val distanceKm: Double)

    fun nearest(lat: Double, lng: Double, cities: List<City>): Result? =
        cities.minByOrNull { haversineKm(lat, lng, it.lat, it.lng) }
            ?.let { Result(it, haversineKm(lat, lng, it.lat, it.lng)) }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
