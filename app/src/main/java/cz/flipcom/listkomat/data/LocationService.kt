package cz.flipcom.listkomat.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
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
        // getProviders(true) is enabled AND permitted. Before Android 12 a
        // coarse-only app may not use "gps", and asking it throws; this filter
        // skips it where the OS says so, the catch below guards the rest.
        val usable = lm.getProviders(true)
        val providers = wanted.filter { it in usable }
        val current = withTimeoutOrNull(15_000) {
            if (providers.isEmpty()) return@withTimeoutOrNull null
            coroutineScope {
                val answers = Channel<Location?>(providers.size)
                val jobs = providers.map { p ->
                    launch {
                        // One provider refusing must not cancel the others.
                        val fix = try { currentFrom(lm, p, context) }
                            catch (e: SecurityException) { null }
                            catch (e: IllegalArgumentException) { null }
                        answers.send(fix)
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

    private suspend fun currentFrom(lm: LocationManager, provider: String, context: Context): Location? =
        if (Build.VERSION.SDK_INT >= 30) currentFromApi30(lm, provider, context)
        else singleUpdateFrom(lm, provider)

    @SuppressLint("MissingPermission")
    @RequiresApi(30)
    private suspend fun currentFromApi30(lm: LocationManager, provider: String, context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        }

    /**
     * Android 8–10: `getCurrentLocation` doesn't exist yet, so ask for one
     * update. Every listener method is overridden on purpose — they only
     * became interface defaults in API 30, and a missing one throws
     * AbstractMethodError on these releases.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun singleUpdateFrom(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }
                override fun onProviderDisabled(provider: String) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
}
