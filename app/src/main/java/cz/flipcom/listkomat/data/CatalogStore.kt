package cz.flipcom.listkomat.data

import android.content.Context
import cz.flipcom.listkomat.model.TicketCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Holds the ticket catalog with a three-tier source chain:
 * remote (freshest) → on-disk cache (last good) → bundled asset (offline
 * fallback). A wrong/stale code can thus be fixed by editing the public
 * catalog JSON, with no Play Store release. Mirrors the iOS CatalogStore.
 */
class CatalogStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile: File get() = File(context.filesDir, "tickets-cache.json")

    /** Prefer the disk cache, but defer to the bundled copy if it's newer. */
    fun loadCachedOrBundled(): TicketCatalog {
        val bundled = loadBundled()
        val cached = runCatching {
            json.decodeFromString<TicketCatalog>(cacheFile.readText())
        }.getOrNull()
        return if (cached != null && cached.cities.isNotEmpty() && cached.version >= bundled.version) {
            cached
        } else bundled
    }

    fun loadBundled(): TicketCatalog = runCatching {
        context.assets.open("tickets.json").bufferedReader().use { it.readText() }
            .let { json.decodeFromString<TicketCatalog>(it) }
    }.getOrDefault(TicketCatalog.EMPTY)

    /**
     * Fetch the remote catalog; adopt it only if it decodes, is non-empty, and
     * isn't a downgrade below [current]. Returns the catalog to use plus
     * whether the refresh failed (offline etc. — caller keeps showing cached
     * prices and may surface a "prices may be stale" hint).
     */
    suspend fun refresh(current: TicketCatalog): RefreshResult = withContext(Dispatchers.IO) {
        val body = runCatching { fetch(REMOTE_URL) }.getOrNull()
            ?: return@withContext RefreshResult(current, failed = true)
        val fetched = runCatching { json.decodeFromString<TicketCatalog>(body) }.getOrNull()
            ?: return@withContext RefreshResult(current, failed = true)
        if (fetched.cities.isEmpty() || fetched.version < current.version) {
            return@withContext RefreshResult(current, failed = false)   // reachable, just not newer
        }
        runCatching { cacheFile.writeText(body) }
        RefreshResult(fetched, failed = false)
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    data class RefreshResult(val catalog: TicketCatalog, val failed: Boolean)

    companion object {
        private const val REMOTE_URL =
            "https://raw.githubusercontent.com/BugsBunny338/listkomat-catalog/main/tickets.json"
    }
}
