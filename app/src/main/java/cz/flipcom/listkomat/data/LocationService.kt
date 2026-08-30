package cz.flipcom.listkomat.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One coarse fix for the nearest-city default; city-level accuracy is all
 * that's needed. Every enabled provider is asked in parallel and the first
 * answer wins — on real devices the network provider is quick, on the
 * emulator only GPS (fed by `geo fix`) ever responds. Falls back to the
 * freshest last-known fix. Caller must hold ACCESS_COARSE_LOCATION.
 */
object LocationService {

    @SuppressLint("MissingPermission")
    suspend fun coarseFix(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val wanted = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        val providers = wanted.filter { lm.allProviders.contains(it) && lm.isProviderEnabled(it) }
        val current = withTimeoutOrNull(15_000) {
            if (providers.isEmpty()) return@withTimeoutOrNull null
            coroutineScope {
                val answers = Channel<Location?>(providers.size)
                val jobs = providers.map { p ->
                    launch {
                        answers.send(currentFrom(lm, p, context))
                    }
                }
                var first: Location? = null
                repeat(providers.size) {
                    val fix = answers.receive()
                    if (fix != null) { first = fix; jobs.forEach { it.cancel() }; return@coroutineScope first }
                }
                first
            }
        }
        return current ?: lastKnown(lm)
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFrom(lm: LocationManager, provider: String, context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
}
