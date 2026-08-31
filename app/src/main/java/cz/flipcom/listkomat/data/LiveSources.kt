package cz.flipcom.listkomat.data

import cz.flipcom.listkomat.model.Vehicle
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** A per-city live vehicle source the map polls every ~8 s. */
interface VehicleSource {
    @Throws(IOException::class) suspend fun fetch(): List<Vehicle>
    fun shutdown() {}
}

/**
 * One source instance per city for the process lifetime (iOS #12 parity-lite):
 * Brno's stream accumulates vehicles between bursts, so keeping the instance
 * across map close/reopen means the map paints instantly on reopen.
 */
object LiveSources {
    private val cache = HashMap<String, VehicleSource>()
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build()
    }

    @Synchronized fun source(cityKey: String): VehicleSource =
        cache.getOrPut(cityKey) {
            when (cityKey) {
                "praha" -> PragueLiveSource(client)
                else -> BrnoLiveStreamSource(client)
            }
        }
}

/** Stateless poller for the Prague proxy endpoint. */
class PragueLiveSource(private val client: OkHttpClient) : VehicleSource {
    override suspend fun fetch(): List<Vehicle> {
        val req = Request.Builder().url(PragueVehicleSource.ENDPOINT).build()
        val body = suspendCancellableCoroutine<String> { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (cont.isActive) cont.resumeWith(Result.failure(IOException("HTTP ${it.code}")))
                        } else {
                            val text = it.body?.string().orEmpty()
                            if (cont.isActive) cont.resume(text)
                        }
                    }
                }
            })
        }
        return PragueVehicleSource.decode(body)
    }
}

/**
 * Brno's GeoEvent WebSocket, accumulated into a snapshot; fetch() (re)connects
 * as needed and returns the current snapshot. The map's poll loop doubles as
 * the reconnect/backoff mechanism (iOS BrnoStreamSource semantics, simplified:
 * same first-message/stall/settle budgets).
 */
class BrnoLiveStreamSource(private val client: OkHttpClient) : VehicleSource {
    private val snapshot = BrnoStreamSnapshot()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastMessageAtMs = 0L
    @Volatile private var gotFirstMessage = false
    private var applyCount = 0

    override suspend fun fetch(): List<Vehicle> {
        ensureConnected()
        return snapshot.vehicles()
    }

    override fun shutdown() {
        socket?.close(1001, "going away")
        socket = null
    }

    private val isStalled: Boolean
        get() = lastMessageAtMs > 0 &&
            System.currentTimeMillis() - lastMessageAtMs > BrnoStream.STALL_TIMEOUT_MS

    @Synchronized private fun currentHealthySocket(): WebSocket? =
        socket?.takeIf { !isStalled }

    private suspend fun ensureConnected() {
        if (currentHealthySocket() != null) return
        socket?.close(1001, "stalled")
        socket = null
        gotFirstMessage = false
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageAtMs = System.currentTimeMillis()
                gotFirstMessage = true
                runCatching { BrnoStream.decode(text) }.getOrNull()?.let {
                    snapshot.apply(it)
                    if (++applyCount % 512 == 0) snapshot.prune()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
            }
        }
        val ws = client.newWebSocket(Request.Builder().url(BrnoStream.STREAM_URL).build(), listener)
        socket = ws
        // A 101 handshake proves NOTHING (learned the hard way, 2026-08-29):
        // only a delivered message does. Wait out one full batch period.
        val ok = withTimeoutOrNull(BrnoStream.FIRST_MESSAGE_TIMEOUT_MS) {
            while (!gotFirstMessage && socket === ws) delay(200)
            gotFirstMessage
        } ?: false
        if (!ok) {
            ws.close(1001, "no data")
            if (socket === ws) socket = null
            throw IOException("Brno stream: no message within timeout")
        }
        // Let the burst drain so the first paint shows the whole fleet.
        delay(BrnoStream.BURST_SETTLE_MS)
    }
}
