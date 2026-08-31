package cz.flipcom.listkomat.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Brno FinalStopID → stop name, from the bundled snapshot (iOS parity). */
object StopNamesStore {
    @Volatile private var cache: Map<Int, String>? = null

    fun brno(context: Context): Map<Int, String> =
        cache ?: synchronized(this) {
            cache ?: load(context).also { cache = it }
        }

    private fun load(context: Context): Map<Int, String> = runCatching {
        val text = context.assets.open("brno-stop-names.json")
            .bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.jsonPrimitive.content } }
            .toMap()
    }.getOrDefault(emptyMap())
}
