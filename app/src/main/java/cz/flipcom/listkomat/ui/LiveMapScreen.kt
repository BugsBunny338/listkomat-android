package cz.flipcom.listkomat.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.data.LiveSources
import cz.flipcom.listkomat.data.StopNamesStore
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.TransitPalette
import cz.flipcom.listkomat.model.Vehicle
import cz.flipcom.listkomat.model.VehicleKind
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Full-screen live map for a city: vehicles coloured by the shared
 * TransitPalette, a selection card, a recenter button and a failure banner.
 * OpenStreetMap tiles via osmdroid (no API key; attribution shown) — the
 * vehicle data flows exactly like iOS: poll the shared source every 8 s,
 * keep the last positions on failure.
 */
private const val POLL_INTERVAL_MS = 8_000L

@Composable
fun LiveMapScreen(city: City) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var vehicles by remember { mutableStateOf(listOf<Vehicle>()) }
    var loadFailed by remember { mutableStateOf(false) }
    var didLoadOnce by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Vehicle?>(null) }
    val stopNames = remember(city.key) {
        if (city.key == "brno") StopNamesStore.brno(context) else emptyMap()
    }
    val accent = MaterialTheme.colorScheme.primary

    LaunchedEffect(city.key) {
        val source = LiveSources.source(city.key)
        while (true) {
            try {
                vehicles = source.fetch()
                loadFailed = false
            } catch (e: Exception) {
                loadFailed = true    // keep last vehicles on screen
            }
            didLoadOnce = true
            delay(POLL_INTERVAL_MS)
        }
    }

    val overlay = remember {
        VehiclesOverlay(density.density) { selected = it }
    }
    overlay.vehicles = vehicles

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(13.2)
                    controller.setCenter(GeoPoint(city.lat, city.lng))
                    overlays.add(overlay)
                }
            },
            update = { it.invalidate() },
            onRelease = { it.onDetach() },
            modifier = Modifier.fillMaxSize(),
        )
        // © attribution — required by the OSM tile policy.
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        if (!didLoadOnce) {
            Card(Modifier.align(Alignment.Center)) {
                Text(stringResource(R.string.map_connecting), Modifier.padding(16.dp))
            }
        } else if (vehicles.isEmpty() && !loadFailed) {
            Card(Modifier.align(Alignment.Center)) {
                Text(stringResource(R.string.map_no_vehicles), Modifier.padding(16.dp))
            }
        }
        if (loadFailed) {
            Card(
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(stringResource(R.string.map_load_failed),
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        selected?.let { sel ->
            Card(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    val kindName = kindDisplayName(sel.kind, brno = city.key == "brno")
                    Text("$kindName ${sel.line}",
                        style = MaterialTheme.typography.titleMedium, color = accent)
                    val dest = sel.destinationName
                        ?: sel.destinationId?.let { stopNames[it] }
                    dest?.let {
                        Text(stringResource(R.string.map_towards, it),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun kindDisplayName(kind: VehicleKind, brno: Boolean): String {
    // Easter egg (iOS parity): in Brno a tram is a "Šalina", in every language.
    if (kind == VehicleKind.TRAM && brno) return "Šalina"
    return stringResource(when (kind) {
        VehicleKind.TRAM -> R.string.kind_tram
        VehicleKind.METRO -> R.string.kind_metro
        VehicleKind.TROLLEYBUS -> R.string.kind_trolleybus
        VehicleKind.BUS -> R.string.kind_bus
        VehicleKind.TRAIN -> R.string.kind_train
        VehicleKind.FERRY -> R.string.kind_ferry
    })
}

/**
 * Draws every vehicle as a palette-coloured disc with its line glyph — one
 * canvas pass, no per-vehicle marker objects (hundreds of vehicles at 8 s
 * cadence would churn osmdroid's marker machinery).
 */
private class VehiclesOverlay(
    private val density: Float,
    private val onSelect: (Vehicle) -> Unit,
) : Overlay() {
    var vehicles: List<Vehicle> = emptyList()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pt = android.graphics.Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val r = 9f * density
        ringPaint.strokeWidth = 1.5f * density
        textPaint.textSize = 8.5f * density
        val proj = mapView.projection
        // Freshest-first list: draw oldest first so fresh markers sit on top.
        for (v in vehicles.asReversed()) {
            proj.toPixels(GeoPoint(v.lat, v.lng), pt)
            if (pt.x < -50 || pt.y < -50 ||
                pt.x > canvas.width + 50 || pt.y > canvas.height + 50) continue
            val style = TransitPalette.style(v.kind, v.line)
            fillPaint.color = style.fill.toArgb()
            textPaint.color = style.glyph.toArgb()
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fillPaint)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, ringPaint)
            canvas.drawText(v.line.take(3), pt.x.toFloat(),
                pt.y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val touchR = 20f * density
        var best: Vehicle? = null
        var bestD = Float.MAX_VALUE
        for (v in vehicles) {
            proj.toPixels(GeoPoint(v.lat, v.lng), pt)
            val dx = e.x - pt.x; val dy = e.y - pt.y
            val d = dx * dx + dy * dy
            if (d < bestD && d <= touchR * touchR) { bestD = d; best = v }
        }
        return best?.let { onSelect(it); true } ?: false
    }
}
