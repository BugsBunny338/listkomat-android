package cz.flipcom.listkomat.model

/** What GPS knows about where the user is, mirroring the iOS LocationManager. */
sealed interface LocationState {
    data object Idle : LocationState
    data object Searching : LocationState
    data object Denied : LocationState
    data class Located(val cityKey: String, val distanceKm: Double) : LocationState
}

/**
 * Which city the home screen shows (decided 2026-09-23, iOS follows in G5):
 * GPS wins whenever it delivers. A manual pick overrides it for the session
 * only, and is persisted solely as the fallback for when GPS can't deliver
 * (denied, no fix, nearest city farther than [NearestCity.MAX_DEFAULT_DISTANCE_KM]).
 * Coming back to the app is a fresh context, except within [MANUAL_GRACE_MS]
 * of the pick, so answering a text doesn't flip the city. Pure for unit tests.
 */
object CitySelection {

    const val MANUAL_GRACE_MS = 5 * 60 * 1000L

    fun cityKey(manual: String?, location: LocationState, fallback: String?): String? =
        manual ?: gpsCityKey(location) ?: fallback

    /** The GPS city, if it's close enough to plausibly be where the user is. */
    fun gpsCityKey(location: LocationState): String? =
        (location as? LocationState.Located)
            ?.takeIf { it.distanceKm <= NearestCity.MAX_DEFAULT_DISTANCE_KM }?.cityKey

    /** The manual override after the app comes to the foreground: the last
     *  pick ([fallback]) while it is younger than the grace window, else none. */
    fun overrideAfterStart(fallback: String?, pickedAtMs: Long?, nowMs: Long): String? {
        val age = nowMs - (pickedAtMs ?: return null)
        return fallback.takeIf { age in 0 until MANUAL_GRACE_MS }
    }
}
