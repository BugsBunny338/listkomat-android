package cz.flipcom.listkomat.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import cz.flipcom.listkomat.model.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/** logcat tag for the live-map connection lifecycle (`adb logcat -s Listkomat`). */
const val LOG_TAG = "Listkomat"

/** A per-city live vehicle source the map polls every ~8 s. */
interface VehicleSource {
    @Throws(IOException::class) suspend fun fetch(): List<Vehicle>

    /** Positions this source delivered recently, or null before it has any —
     *  a re-entered map (rotation, quick reopen) paints these at once instead
     *  of claiming "no vehicles" while it reconnects. */
    fun retained(): List<Vehicle>? = null

    /** Close any live connection; the next fetch reconnects. */
    fun shutdown() {}
}

/**
 * One source instance per city for the process lifetime (iOS #12 parity-lite):
 * Brno's stream accumulates vehicles between bursts, so keeping the instance
 * across map close/reopen means the map paints instantly on reopen.
 *
 * Connections are released the iOS way (`Services/LiveSources.swift`): the map
 * holds interest while it is composed, the last release closes the source
 * after a 120 s grace (so close→reopen doesn't reconnect), and the app going
 * to the background closes everything at once.
 */
object LiveSources {
    /** Keep-warm window after the map is left. iOS: `idleShutdownDelay`. */
    const val IDLE_SHUTDOWN_MS = 120_000L

    private val cache = HashMap<String, VehicleSource>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // A call stuck on a dead connection gives up instead of hanging the
            // poll (the WebSocket's timeout ends at the upgrade).
            .callTimeout(20, TimeUnit.SECONDS)
            // HTTP/2 and WebSocket pings: a connection whose route died is
            // failed by a missed pong rather than kept forever (#8's wedge,
            // and the Brno socket's dead-peer detection).
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val interest = InterestTracker(scope, IDLE_SHUTDOWN_MS) { key ->
        val src = synchronized(this) { cache[key] } ?: return@InterestTracker
        Log.d(LOG_TAG, "live source $key idle for ${IDLE_SHUTDOWN_MS / 1000} s, shutting down")
        src.shutdown()
    }

    @Synchronized fun source(cityKey: String): VehicleSource =
        cache.getOrPut(cityKey) {
            when (cityKey) {
                "praha" -> PragueLiveSource(client, evictPool = ::evictConnections)
                else -> BrnoLiveStreamSource(client)
            }
        }

    /** The map is up for [cityKey]: cancels a pending idle shutdown. */
    fun acquire(cityKey: String) = interest.acquire(cityKey)

    /** The map for [cityKey] went away; its source closes after the grace. */
    fun release(cityKey: String) = interest.release(cityKey)

    /** Drop idle pooled connections so the next request opens a fresh one.
     *  Off the main thread: closing a TLS socket writes to the network. */
    fun evictConnections() {
        scope.launch(Dispatchers.IO) { client.connectionPool.evictAll() }
    }

    private var installed = false

    /**
     * Once per process (MainActivity.onCreate; repeat calls are no-ops): evict
     * the pool when the default network changes — URLSession's path monitoring
     * does this for iOS for free — and close everything when the app stops
     * being visible. Never unregistered: both live as long as the process.
     */
    @MainThread fun install(context: Context) {
        if (installed) return
        installed = true
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = evictConnections()
                override fun onLost(network: Network) = evictConnections()
            })
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) closeAllForBackground()
        })
    }

    /** iOS `closeAllForBackground`: interest counts stay (the map is still
     *  composed); its poll loop reconnects once the app is back. */
    private fun closeAllForBackground() {
        Log.d(LOG_TAG, "app stopped: closing live connections")
        interest.cancelPending()
        synchronized(this) { cache.values.toList() }.forEach { it.shutdown() }
        evictConnections()
    }
}

/**
 * Interest counting with a grace period, per key. When the count drops to
 * zero, [onIdle] runs after [graceMs] unless the key is acquired again first.
 */
class InterestTracker(
    private val scope: CoroutineScope,
    private val graceMs: Long,
    private val onIdle: (String) -> Unit,
) {
    private val counts = HashMap<String, Int>()
    private val pending = HashMap<String, Job>()

    @Synchronized fun acquire(key: String) {
        counts[key] = (counts[key] ?: 0) + 1
        pending.remove(key)?.cancel()
    }

    @Synchronized fun release(key: String) {
        val left = ((counts[key] ?: 0) - 1).coerceAtLeast(0)
        counts[key] = left
        if (left > 0) return
        pending.remove(key)?.cancel()
        pending[key] = scope.launch {
            delay(graceMs)
            synchronized(this@InterestTracker) {
                // Cancelled while waiting for the lock = re-acquired meanwhile.
                if (!isActive || (counts[key] ?: 0) > 0) return@launch
                pending.remove(key)
                onIdle(key)
            }
        }
    }

    /** Forget scheduled shutdowns (the caller is closing everything anyway). */
    @Synchronized fun cancelPending() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    @Synchronized fun count(key: String): Int = counts[key] ?: 0
}

/**
 * Consecutive-failure counter: [onTrip] runs on the [threshold]th failure in a
 * row and on every one after it; a success starts over.
 */
class FailureStreak(private val threshold: Int, private val onTrip: () -> Unit) {
    var count = 0
        private set

    @Synchronized fun success() { count = 0 }

    @Synchronized fun failure() {
        if (++count >= threshold) onTrip()
    }
}

/**
 * Poller for the Prague proxy endpoint. Keeps the last good list for
 * [retained]. From the second failure in a row it evicts the connection pool,
 * so a wedged HTTP/2 connection is replaced on the next poll instead of
 * failing forever (#8); the banner still shows from the first failure (iOS).
 */
class PragueLiveSource(
    client: OkHttpClient,
    /** Must not block: fetch resumes on the caller's (main) thread. */
    evictPool: () -> Unit,
    private val download: suspend () -> String = { httpGet(client, PragueVehicleSource.ENDPOINT) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : VehicleSource {
    private val failures = FailureStreak(EVICT_AFTER_FAILURES, evictPool)
    @Volatile private var last: Pair<Long, List<Vehicle>>? = null

    override suspend fun fetch(): List<Vehicle> {
        val vehicles = try {
            PragueVehicleSource.decode(download(), nowMs = nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.failure()
            throw e
        }
        failures.success()
        last = nowMs() to vehicles
        return vehicles
    }

    override fun retained(): List<Vehicle>? =
        last?.takeIf { nowMs() - it.first <= RETAINED_MAX_AGE_MS }?.second

    companion object {
        const val EVICT_AFTER_FAILURES = 2
        /** Old enough positions are worse than the connecting card. */
        const val RETAINED_MAX_AGE_MS = 120_000L

        private suspend fun httpGet(client: OkHttpClient, url: String): String =
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(Request.Builder().url(url).build())
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }
                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        // The body is read here, and OkHttp only logs an
                        // IOException thrown from onResponse — never calls
                        // onFailure — so a body read that times out must fail
                        // the fetch itself, or the poll hangs with no banner.
                        val result = try {
                            response.use {
                                if (!it.isSuccessful) Result.failure(IOException("HTTP ${it.code}"))
                                else Result.success(it.body?.string().orEmpty())
                            }
                        } catch (e: IOException) {
                            Result.failure(e)
                        }
                        if (cont.isActive) cont.resumeWith(result)
                    }
                })
            }
    }
}

/**
 * Brno's GeoEvent WebSocket, accumulated into a snapshot; fetch() (re)connects
 * as needed and returns the current snapshot. The map's poll loop doubles as
 * the reconnect/backoff mechanism (iOS BrnoStreamSource semantics: strict
 * first message, stall and decode-streak reconnects, burst settle).
 *
 * A connect runs in the source's own scope and every fetch joins the one in
 * flight — like iOS `ensureConnected`. A map re-entered mid-connect (rotation)
 * therefore waits for the burst instead of reading an empty snapshot and
 * showing "no vehicles", and never opens a second socket.
 */
class BrnoLiveStreamSource(
    private val openSocket: (WebSocketListener) -> WebSocket,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.d(LOG_TAG, it) },
) : VehicleSource {
    constructor(client: OkHttpClient) : this({ listener ->
        client.newWebSocket(Request.Builder().url(BrnoStream.STREAM_URL).build(), listener)
    })

    private val snapshot = BrnoStreamSnapshot()
    // Guarded by `this`.
    private var socket: WebSocket? = null
    private var connecting: Deferred<Unit>? = null
    private var decodeFailureStreak = 0
    @Volatile private var lastMessageAtMs = 0L
    private var applyCount = 0   // OkHttp's reader thread only

    override suspend fun fetch(): List<Vehicle> {
        ensureConnected()
        return snapshot.vehicles(nowMs())
    }

    override fun retained(): List<Vehicle>? =
        snapshot.vehicles(nowMs()).takeIf { it.isNotEmpty() }

    override fun shutdown() {
        val (ws, job) = synchronized(this) {
            (socket to connecting).also { socket = null; connecting = null }
        }
        job?.cancel()
        if (ws != null) {
            ws.close(1001, "going away")
            log("Brno socket closed (shutdown)")
        }
    }

    private val isStalled: Boolean
        get() = lastMessageAtMs > 0 && nowMs() - lastMessageAtMs > BrnoStream.STALL_TIMEOUT_MS

    private suspend fun ensureConnected() {
        val attempt = synchronized(this) {
            connecting ?: run {
                if (socket != null && !isStalled) return
                scope.async(start = CoroutineStart.LAZY) { openConnection() }.also { job ->
                    connecting = job
                    job.invokeOnCompletion {
                        synchronized(this) { if (connecting === job) connecting = null }
                    }
                    job.start()
                }
            }
        }
        try {
            attempt.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()   // our own cancellation: propagate
            throw IOException("Brno stream: connect cancelled", e)   // a shutdown cancelled it
        }
    }

    private suspend fun openConnection() {
        synchronized(this) {
            socket?.close(1001, "reconnect")
            socket = null
        }
        val first = CompletableDeferred<String>()
        val context = currentCoroutineContext()
        val ws = openSocket(listener(first))
        synchronized(this) {
            // A shutdown() between openSocket and here already cancelled us;
            // publishing ws would let the next fetch skip reconnecting.
            if (!context.isActive) {
                ws.cancel()
                throw CancellationException("Brno connect cancelled")
            }
            socket = ws
            // Fresh socket, fresh streak — otherwise the first bad frame after
            // a cutoff reconnect would tear the new socket down again.
            decodeFailureStreak = 0
        }
        log("Brno socket opened")
        try {
            // A 101 handshake proves NOTHING (learned the hard way, 2026-08-29):
            // only a delivered message does, and it is decoded STRICTLY — a dead
            // feed or a drifted schema fails the fetch (banner), never a
            // silently empty map. Wait out one full batch period.
            val text = withTimeoutOrNull(BrnoStream.FIRST_MESSAGE_TIMEOUT_MS) { first.await() }
                ?: throw IOException("Brno stream: no message within timeout")
            val update = try {
                BrnoStream.decode(text)
            } catch (e: Exception) {
                throw IOException("Brno stream: undecodable first message", e)
            }
            snapshot.apply(update)
        } catch (e: Throwable) {
            drop(ws, "no usable first message")
            throw e
        }
        // Let the burst drain so the first paint shows the whole fleet.
        delay(BrnoStream.BURST_SETTLE_MS)
    }

    private fun listener(first: CompletableDeferred<String>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            lastMessageAtMs = nowMs()
            if (first.complete(text)) return   // openConnection decodes it strictly
            onStreamMessage(webSocket, text)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            first.completeExceptionally(IOException("Brno stream failed", t))
            lost(webSocket, "failure: ${t.message}")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            first.completeExceptionally(IOException("Brno stream closed ($code)"))
            lost(webSocket, "closed by server ($code)")
        }
    }

    /** A message after the first. A lone bad one is dropped; a streak of
     *  [BrnoStream.DECODE_FAILURE_CUTOFF] means schema drift — reconnect, and
     *  let the strict first decode fail the next fetch. */
    internal fun onStreamMessage(webSocket: WebSocket, text: String) {
        val update = try { BrnoStream.decode(text) } catch (e: Exception) { null }
        synchronized(this) {
            if (socket !== webSocket) {   // superseded socket — evict
                webSocket.cancel()
                return
            }
            if (update == null) {
                if (++decodeFailureStreak >= BrnoStream.DECODE_FAILURE_CUTOFF) {
                    socket = null
                    webSocket.close(1001, "undecodable")
                    log("Brno socket closed ($decodeFailureStreak undecodable messages)")
                }
                return
            }
            decodeFailureStreak = 0
        }
        snapshot.apply(update ?: return)
        if (++applyCount % 512 == 0) snapshot.prune()
    }

    private fun drop(ws: WebSocket, why: String) {
        ws.close(1001, why)
        synchronized(this) { if (socket === ws) socket = null }
        log("Brno socket closed ($why)")
    }

    private fun lost(ws: WebSocket, why: String) {
        val wasCurrent = synchronized(this) {
            (socket === ws).also { if (it) socket = null }
        }
        if (wasCurrent) log("Brno socket lost ($why)")
    }
}
