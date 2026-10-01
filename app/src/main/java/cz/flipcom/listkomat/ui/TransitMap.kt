package cz.flipcom.listkomat.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.flipcom.listkomat.data.LiveSources
import cz.flipcom.listkomat.data.Stop
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.TransitPalette
import cz.flipcom.listkomat.model.Vehicle
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log2
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/**
 * The live map's basemap and markers: MapLibre Native rendering OpenFreeMap
 * vector tiles, with vehicles and stops drawn as style layers on the GPU.
 * The Android counterpart of iOS TransitMapView; everything floating over the
 * map (cards, FAB, banners) stays in LiveMapScreen.
 */

// MapLibre + OpenFreeMap: keyless, and MapLibre sends nothing but tile
// requests. The Google Maps SDK was rejected 2026-09-30: it would end "no data
// collected" on the Play Data safety form. These URLs are the single switch
// for self-hosted tiles later.
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

/** Visible latitude span (iOS TransitMapView): first framing, recenter, and
 *  the span below which stops appear. */
internal const val INITIAL_LAT_SPAN = 0.05
internal const val RECENTER_LAT_SPAN = 0.03
internal const val STOP_LAT_SPAN = 0.035

/** The A14's map area in portrait (856 dp screen minus status and top
 *  bars): the height assumed until the map view has been laid out. */
internal const val REFERENCE_MAP_HEIGHT_DP = 764.0

/** How often a map stuck on the offline fallback style checks whether the
 *  real style is reachable again. */
private const val STYLE_RETRY_MS = 10_000L

/**
 * Zoom at which [latSpan] degrees of latitude fill [heightDp] around [lat].
 * MapLibre's world is 512 dp wide at zoom 0, and a degree of latitude is
 * 1/cos(lat) times taller than a degree of longitude in Web Mercator.
 */
internal fun zoomForLatSpan(latSpan: Double, lat: Double, heightDp: Double): Double =
    log2(heightDp * 360.0 * cos(Math.toRadians(lat)) / (512.0 * latSpan))

private const val VEHICLE_SOURCE = "listkomat-vehicles"
private const val VEHICLE_LAYER = "listkomat-vehicles"
private const val STOP_SOURCE = "listkomat-stops"
private const val STOP_LAYER = "listkomat-stops"

/** Where the map is looking; saved so rotation doesn't snap back to the city.
 *  `zoom` is NaN until the first framing, which needs the view's height. */
private class MapCamera(var lat: Double, var lng: Double, var zoom: Double, var bearing: Double) {
    companion object {
        val Saver = listSaver<MapCamera, Double>(
            save = { listOf(it.lat, it.lng, it.zoom, it.bearing) },
            restore = { MapCamera(it[0], it[1], it[2], it[3]) },
        )
    }
}

/**
 * @param recenter bump to fly back to the city (the FAB); the value at first
 *   composition never moves the camera.
 * @param onSelect the tapped vehicle, or null for a tap on empty map.
 */
@Composable
fun TransitMap(
    city: City,
    vehicles: List<Vehicle>,
    stops: List<Stop>,
    accent: Color,
    recenter: Int,
    onSelect: (Vehicle?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = rememberSaveable(city.key, saver = MapCamera.Saver) {
        MapCamera(city.lat, city.lng, Double.NaN, 0.0)
    }
    // The app's own appearance mode, not the system's (same signal as the
    // navigation-bar scrim).
    val styleUrl = if (surfaceIsDark()) STYLE_DARK else STYLE_LIGHT
    val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
    val accentArgb = accent.toArgb()
    // The map runs edge to edge, so MapLibre's compass must clear a side
    // navigation bar or cutout in landscape by itself.
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val endInsetPx = if (dir == LayoutDirection.Rtl) WindowInsets.safeDrawing.getLeft(density, dir)
                     else WindowInsets.safeDrawing.getRight(density, dir)

    AndroidView(
        factory = { ctx ->
            MapLibre.getInstance(ctx)
            val options = MapLibreMapOptions.createFromAttributes(ctx)
                // A TextureView fades and slides with the AnimatedVisibility
                // the map lives in; a SurfaceView would ignore both.
                .textureMode(true)
                .foregroundLoadColor(surfaceArgb)
            val view = MapView(ctx, options)
            view.tag = TransitMapController(view, city, camera, recenter, lifecycleOwner)
            view
        },
        // Every input is pushed to the map here. Each poll hands TransitMap a
        // new vehicle list, the recomposition passes AndroidView a new update
        // lambda, and AndroidView re-runs it, so the fleet reaches the map on
        // every poll. setGeoJson repaints by itself; nothing may wait for a
        // touch to show the fleet (issue #2 — a warm reopen with nothing else
        // to redraw left it invisible). Keep the vehicles flowing through
        // this lambda's inputs.
        update = { view ->
            (view.tag as TransitMapController).apply {
                this.onSelect = onSelect
                fallbackBackground = surfaceArgb
                setCompassEndInset(endInsetPx)
                setStyleUrl(styleUrl)
                setStops(stops)
                setAccent(accentArgb)
                setVehicles(vehicles)
                recenterIfBumped(recenter)
            }
        },
        onRelease = { view -> (view.tag as TransitMapController).destroy() },
        modifier = modifier,
    )
}

/**
 * Owns one MapView: forwards the host lifecycle to it, keeps the map's
 * sources and layers in step with the latest inputs, and rebuilds them
 * whenever a style (re)loads — setStyle drops every source, layer and image.
 */
private class TransitMapController(
    private val view: MapView,
    private val city: City,
    private val camera: MapCamera,
    private var lastRecenter: Int,
    private val lifecycleOwner: LifecycleOwner,
) {
    var onSelect: (Vehicle?) -> Unit = {}

    /** Background of the offline fallback style: the app's surface. */
    var fallbackBackground = 0

    private val density = view.resources.displayMetrics.density
    private val densityDpi = view.resources.displayMetrics.densityDpi

    private var map: MapLibreMap? = null
    private var styleUrl: String? = null
    /** The loaded style matching [styleUrl]; null while one is loading. */
    private var style: Style? = null
    /** Vehicle icons registered with [style]; they vanish with it. */
    private val images = HashSet<String>()
    private var framed = false
    /** On the blank local style because the real one failed to load. */
    private var onFallback = false
    private val retryStyle = Runnable { preflightStyle() }
    private var recenterNonce = lastRecenter
    private var compassEndInset = 0

    private var vehicles: List<Vehicle> = emptyList()
    /** Lists, because a feed may carry an id twice; a tap tests each. */
    private var byId: Map<String, List<Vehicle>> = emptyMap()
    /** Icon name per (kind, line), so a poll formats no strings. */
    private val iconNames = HashMap<String, String>()
    private var stops: List<Stop> = emptyList()
    private var accent = 0

    private val lifecycle = MapViewLifecycle(view)
    private val memory = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = view.onLowMemory()
        override fun onTrimMemory(level: Int) {}
    }

    init {
        lifecycleOwner.lifecycle.addObserver(lifecycle)   // replays up to the current state
        view.context.applicationContext.registerComponentCallbacks(memory)
        view.getMapAsync { m ->
            map = m
            configure(m)
            styleUrl?.let { load(m, it) }
            applyRecenter()   // a press made before the map was ready
        }
        // Stops appear below a visible latitude span (iOS), so their minZoom
        // follows the map's height — rotation included.
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                style?.getLayerAs<CircleLayer>(STOP_LAYER)?.minZoom = stopMinZoom()
            }
        }
    }

    fun destroy() {
        lifecycleOwner.lifecycle.removeObserver(lifecycle)
        view.context.applicationContext.unregisterComponentCallbacks(memory)
        view.removeCallbacks(retryStyle)
        lifecycle.moveTo(Lifecycle.State.DESTROYED)
        map = null
        style = null
    }

    private fun configure(m: MapLibreMap) {
        m.uiSettings.apply {
            // Our own attribution pill credits the map; it must stay visible.
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = true
            isCompassEnabled = true
        }
        placeCompass(m)
        if (!camera.zoom.isNaN()) {
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(camera.lat, camera.lng))
                .zoom(camera.zoom)
                .bearing(camera.bearing)
                .build()
            framed = true
        } else {
            view.doOnLayout {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(city.lat, city.lng), zoomFor(INITIAL_LAT_SPAN)))
                framed = true
                record(m)
            }
        }
        m.addOnCameraIdleListener { record(m) }
        m.addOnMapClickListener { tap(m, it) }
        view.addOnDidFailLoadingMapListener { styleFailed(m) }
    }

    fun setCompassEndInset(px: Int) {
        if (px == compassEndInset) return
        compassEndInset = px
        map?.let { placeCompass(it) }
    }

    private fun placeCompass(m: MapLibreMap) {
        val margin = (8 * density).toInt()
        val end = margin + compassEndInset
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        m.uiSettings.setCompassMargins(if (rtl) end else margin, margin, if (rtl) margin else end, margin)
    }

    /** Before the first framing the camera is MapLibre's default, not ours. */
    private fun record(m: MapLibreMap) {
        if (!framed) return
        val p = m.cameraPosition
        val target = p.target ?: return
        camera.lat = target.latitude
        camera.lng = target.longitude
        camera.zoom = p.zoom
        camera.bearing = p.bearing
    }

    private fun mapHeightDp(): Double =
        if (view.height > 0) view.height / density.toDouble() else REFERENCE_MAP_HEIGHT_DP

    private fun zoomFor(latSpan: Double): Double = zoomForLatSpan(latSpan, city.lat, mapHeightDp())

    private fun stopMinZoom(): Float = zoomFor(STOP_LAT_SPAN).toFloat()

    fun recenterIfBumped(nonce: Int) {
        recenterNonce = nonce
        applyRecenter()
    }

    /** Back to the city, north up (the FAB). */
    private fun applyRecenter() {
        val m = map ?: return
        if (recenterNonce == lastRecenter) return
        lastRecenter = recenterNonce
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(city.lat, city.lng))
            .zoom(zoomFor(RECENTER_LAT_SPAN))
            .bearing(0.0)
            .build()), 400)
    }

    fun setStyleUrl(url: String) {
        if (url == styleUrl) return
        styleUrl = url
        map?.let { load(it, url) }
    }

    private fun load(m: MapLibreMap, url: String) {
        style = null
        images.clear()
        onFallback = false
        view.removeCallbacks(retryStyle)
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            // A newer switch may have overtaken this load.
            if (url != styleUrl || map == null || onFallback) return@setStyle
            hidePointsOfInterest(s)
            adopt(s)
        }
    }

    /** Sources and layers onto a freshly loaded style, then the current data. */
    private fun adopt(s: Style) {
        style = s
        // Placement fades re-fade symbols the 8 s update re-places, so
        // some vehicles blinked on every poll; positions just swap now.
        s.transition = TransitionOptions(300, 0, false)
        addLayers(s)
        pushStops(s)
        pushVehicles(s)
    }

    /**
     * The real style didn't load (offline with nothing cached, or OpenFreeMap
     * down — it has no uptime guarantee). Vehicles must still show, as they
     * did over osmdroid's blank tiles: switch to a local blank style carrying
     * our layers, and go back to the real one once it answers again.
     */
    private fun styleFailed(m: MapLibreMap) {
        if (style != null || onFallback) return   // a loaded style keeps working
        onFallback = true
        images.clear()
        val bg = String.format("#%06X", fallbackBackground and 0xFFFFFF)
        val json = """{"version":8,"sources":{},"layers":[""" +
            """{"id":"background","type":"background","paint":{"background-color":"$bg"}}]}"""
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            if (!onFallback || map == null) return@setStyle
            adopt(s)
        }
        view.postDelayed(retryStyle, STYLE_RETRY_MS)
    }

    /** One cheap request to the style URL; only a success swaps the style
     *  back (a failing setStyle would blank the vehicles while it tries). */
    private fun preflightStyle() {
        val url = styleUrl ?: return
        if (!onFallback || map == null) return
        LiveSources.client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                view.post { retryLater() }
            }
            override fun onResponse(call: Call, response: Response) {
                // Status only; the body stays unread. Nothing here may throw,
                // or OkHttp swallows it and the retry loop stops.
                val ok = runCatching { response.use { it.isSuccessful } }.getOrDefault(false)
                view.post { if (ok) reloadReal() else retryLater() }
            }
        })
    }

    private fun retryLater() {
        if (onFallback && map != null) view.postDelayed(retryStyle, STYLE_RETRY_MS)
    }

    private fun reloadReal() {
        val m = map ?: return
        val url = styleUrl ?: return
        if (onFallback) load(m, url)
    }

    fun setVehicles(list: List<Vehicle>) {
        if (list === vehicles) return
        vehicles = list
        byId = list.groupBy { it.id }
        style?.let { pushVehicles(it) }
    }

    fun setStops(list: List<Stop>) {
        if (list === stops) return
        stops = list
        style?.let { pushStops(it) }
    }

    fun setAccent(argb: Int) {
        if (argb == accent) return
        accent = argb
        style?.getLayerAs<CircleLayer>(STOP_LAYER)
            ?.setProperties(PropertyFactory.circleStrokeColor(argb))
    }

    /** Shops, restaurants and the like (iOS: pointOfInterestFilter = .excludingAll).
     *  Positron and Dark carry none today; Bright and Liberty do. */
    private fun hidePointsOfInterest(s: Style) {
        for (layer in s.layers) {
            if (layer is SymbolLayer && layer.sourceLayer == "poi") {
                layer.setProperties(PropertyFactory.visibility(Property.NONE))
            }
        }
    }

    private fun addLayers(s: Style) {
        s.addSource(GeoJsonSource(STOP_SOURCE))
        s.addLayer(CircleLayer(STOP_LAYER, STOP_SOURCE).withProperties(
            // Canvas-era metrics: a 3.5 dp circle with a centred 1.5 dp ring.
            // MapLibre strokes outside the radius, hence 3.5 − 0.75.
            PropertyFactory.circleRadius(2.75f),
            PropertyFactory.circleColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleStrokeColor(accent),
        ).apply { minZoom = stopMinZoom() })
        s.addSource(GeoJsonSource(VEHICLE_SOURCE))
        s.addLayer(SymbolLayer(VEHICLE_LAYER, VEHICLE_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            // Otherwise MapLibre hides vehicles that collide.
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            // Higher draws later: the freshest position sits on top.
            PropertyFactory.symbolSortKey(Expression.get("rank")),
        ))
    }

    private fun pushStops(s: Style) {
        val features = stops.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) }
        s.getSourceAs<GeoJsonSource>(STOP_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun pushVehicles(s: Style) {
        val fresh = HashMap<String, Bitmap>()
        val n = vehicles.size
        // Freshest-first list: rank n for the freshest, 1 for the oldest.
        val features = vehicles.mapIndexed { i, v ->
            Feature.fromGeometry(Point.fromLngLat(v.lng, v.lat)).apply {
                addStringProperty("id", v.id)
                addStringProperty("icon", icon(v, fresh))
                addNumberProperty("rank", n - i)
            }
        }
        if (fresh.isNotEmpty()) {
            s.addImages(fresh)
            images.addAll(fresh.keys)
        }
        s.getSourceAs<GeoJsonSource>(VEHICLE_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** The image name for [v]'s disc, rendering it once per (fill, glyph, text). */
    private fun icon(v: Vehicle, fresh: HashMap<String, Bitmap>): String {
        val name = iconNames.getOrPut("${v.kind}|${v.line}") {
            val marker = TransitPalette.style(v.kind, v.line)
            "v:%08x:%08x:%s".format(marker.fill.toArgb(), marker.glyph.toArgb(), v.line.take(3))
        }
        if (name !in images && name !in fresh) {
            val marker = TransitPalette.style(v.kind, v.line)
            fresh[name] = disc(marker.fill.toArgb(), marker.glyph.toArgb(), v.line.take(3))
        }
        return name
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** A 9 dp palette disc, 1.5 dp white ring, bold 8.5 dp line glyph. */
    private fun disc(fill: Int, glyph: Int, text: String): Bitmap {
        val r = 9f * density
        ringPaint.strokeWidth = 1.5f * density
        textPaint.textSize = 8.5f * density
        val size = ceil(2f * r + ringPaint.strokeWidth).toInt() + 2
        val bitmap = createBitmap(size, size)
        // MapLibre sizes the icon by the bitmap's density: one dp per dp.
        bitmap.density = densityDpi
        val c = Canvas(bitmap)
        val mid = size / 2f
        fillPaint.color = fill
        c.drawCircle(mid, mid, r, fillPaint)
        c.drawCircle(mid, mid, r, ringPaint)
        textPaint.color = glyph
        c.drawText(text, mid, mid - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        return bitmap
    }

    /** Nearest vehicle within 20 dp selects it; anything else clears the
     *  selection. The camera never moves on select (iOS parity). */
    private fun tap(m: MapLibreMap, at: LatLng): Boolean {
        val proj = m.projection
        val p = proj.toScreenLocation(at)
        val r = 20f * density
        val hits = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), VEHICLE_LAYER)
        var best: Vehicle? = null
        var bestD = r * r
        for (f in hits) {
            val candidates = f.getStringProperty("id")?.let { byId[it] } ?: continue
            for (v in candidates) {
                val q = proj.toScreenLocation(LatLng(v.lat, v.lng))
                val dx = q.x - p.x
                val dy = q.y - p.y
                val d = dx * dx + dy * dy
                if (d <= bestD) { bestD = d; best = v }
            }
        }
        onSelect(best)
        return true
    }
}

/**
 * Drives a MapView through its lifecycle one step at a time, so every
 * onStart is paired with an onStop and the view is destroyed exactly once —
 * whether the host goes away or the map leaves composition first. A view left
 * started leaks its render thread; one never started stays black.
 */
private class MapViewLifecycle(private val view: MapView) : LifecycleEventObserver {
    private var state = Lifecycle.State.INITIALIZED

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) =
        moveTo(event.targetState)

    fun moveTo(target: Lifecycle.State) {
        while (state != Lifecycle.State.DESTROYED && state != target) {
            state = if (state < target) {
                when (state) {
                    Lifecycle.State.INITIALIZED -> { view.onCreate(null); Lifecycle.State.CREATED }
                    Lifecycle.State.CREATED -> { view.onStart(); Lifecycle.State.STARTED }
                    else -> { view.onResume(); Lifecycle.State.RESUMED }
                }
            } else {
                when (state) {
                    Lifecycle.State.RESUMED -> { view.onPause(); Lifecycle.State.STARTED }
                    Lifecycle.State.STARTED -> { view.onStop(); Lifecycle.State.CREATED }
                    Lifecycle.State.CREATED -> { view.onDestroy(); Lifecycle.State.DESTROYED }
                    else -> Lifecycle.State.DESTROYED   // never created: nothing to tear down
                }
            }
        }
    }
}
