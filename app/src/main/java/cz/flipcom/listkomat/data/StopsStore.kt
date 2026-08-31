package cz.flipcom.listkomat.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Stop(val id: String, val name: String, val lat: Double, val lng: Double)

/** Bundled static stops (refreshed at build time in the iOS repo's scripts). */
object StopsStore {
    private val cache = HashMap<String, List<Stop>>()
    private val json = Json { ignoreUnknownKeys = true }

    fun forCity(context: Context, cityKey: String): List<Stop> =
        synchronized(cache) {
            cache.getOrPut(cityKey) {
                val asset = when (cityKey) {
                    "praha" -> "prague-stops.json"
                    "brno" -> "brno-stops.json"
                    else -> return@getOrPut emptyList()
                }
                runCatching {
                    context.assets.open(asset).bufferedReader().use { it.readText() }
                        .let { json.decodeFromString<List<Stop>>(it) }
                }.getOrDefault(emptyList())
            }
        }
}
