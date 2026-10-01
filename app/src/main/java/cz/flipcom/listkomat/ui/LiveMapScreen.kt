package cz.flipcom.listkomat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.data.LiveSources
import cz.flipcom.listkomat.data.StopNamesStore
import cz.flipcom.listkomat.data.StopsStore
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.Vehicle
import cz.flipcom.listkomat.model.VehicleKind
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Full-screen live map for a city: vehicles coloured by the shared
 * TransitPalette, a selection card, a recenter button and a failure banner.
 * OpenFreeMap vector tiles via MapLibre (TransitMap.kt; no API key,
 * attribution shown) — the vehicle data flows exactly like iOS: poll the
 * shared source every 8 s while the screen is started, keep the last
 * positions on failure.
 *
 * Native chrome (brief B1): an opaque themed top bar with back + title +
 * data sources, the map below it running edge to edge under the gesture
 * bar (scrimmed), and every overlay kept inside the safe drawing area.
 */
private const val POLL_INTERVAL_MS = 8_000L

/** iOS parity (LiveMapView.slowConnectHint): long enough that a warm reopen
 *  never shows the hint, short enough to land well inside Brno's burst wait. */
private const val SLOW_CONNECT_HINT_MS = 5_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMapScreen(city: City, theme: AppTheme, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val source = remember(city.key) { LiveSources.source(city.key) }
    // Re-entry (rotation, quick reopen) paints the source's recent positions
    // at once; with none yet it is "connecting", never "no vehicles".
    val seeded = remember(city.key) { source.retained() }
    var vehicles by remember(city.key) { mutableStateOf(seeded ?: emptyList()) }
    var loadFailed by rememberSaveable { mutableStateOf(false) }
    var didLoadOnce by remember(city.key) { mutableStateOf(seeded != null) }
    var selected by remember { mutableStateOf<Vehicle?>(null) }
    val stopNames = remember(city.key) {
        if (city.key == "brno") StopNamesStore.brno(context) else emptyMap()
    }
    val stops = remember(city.key) { StopsStore.forCity(context, city.key) }
    val accent = MaterialTheme.colorScheme.primary
    var recenter by remember { mutableIntStateOf(0) }
    var showingSources by remember { mutableStateOf(false) }

    // Interest keeps the source's connection up; leaving (or rotating) starts
    // LiveSources' 120 s grace, which a re-acquire within it cancels.
    DisposableEffect(city.key) {
        LiveSources.acquire(city.key)
        onDispose { LiveSources.release(city.key) }
    }
    // Poll only while visible (iOS pauses on background); a return resumes it.
    LaunchedEffect(city.key, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    vehicles = source.fetch()
                    loadFailed = false
                } catch (e: CancellationException) {
                    throw e              // paused, not failed
                } catch (e: Exception) {
                    loadFailed = true    // keep last vehicles on screen
                }
                didLoadOnce = true
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // Same band as the home screen's bar, so the status-bar tint set in
    // ListkomatApp stays right while the map is up.
    val band = theme.band
    val barIcons = if (band != null) theme.onBand else MaterialTheme.colorScheme.primary
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.map_title, city.name(Locale.getDefault().language)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.map_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showingSources = true }) {
                        Icon(Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.map_sources_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = band ?: MaterialTheme.colorScheme.background,
                    titleContentColor = if (band != null) theme.onBand
                                        else MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = barIcons,
                    actionIconContentColor = barIcons,
                ),
            )
        },
    ) { padding ->
        // Only the top inset is consumed: the map itself runs under the
        // gesture bar, and the overlays below pad for it instead.
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // A city change while the map is up builds a fresh map for it.
            key(city.key) {
                TransitMap(
                    city = city,
                    vehicles = vehicles,
                    stops = stops,
                    accent = accent,
                    recenter = recenter,
                    // A tap on empty map clears the selection (iOS dismisses the same way).
                    onSelect = { selected = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            NavigationBarScrim(Modifier.align(Alignment.BottomCenter))
            MapOverlays(
                city = city,
                accent = accent,
                didLoadOnce = didLoadOnce,
                noVehicles = vehicles.isEmpty(),
                loadFailed = loadFailed,
                selected = selected,
                stopNames = stopNames,
                onDismissSelection = { selected = null },
                onRecenter = { recenter++ },
            )
        }
    }
    if (showingSources) {
        AlertDialog(
            onDismissRequest = { showingSources = false },
            title = { Text(stringResource(R.string.map_sources_title)) },
            text = { Text(stringResource(
                if (city.key == "brno") R.string.map_sources_brno
                else R.string.map_sources_praha)) },
            confirmButton = {
                TextButton(onClick = { showingSources = false }) { Text("OK") }
            },
        )
    }
}

/**
 * Keeps the gesture pill / nav buttons legible over the tiles;
 * ListkomatApp sets their light/dark appearance to match.
 */
@Composable
private fun NavigationBarScrim(modifier: Modifier) {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val surface = MaterialTheme.colorScheme.surface
    val scrim = if (surface.luminance() < 0.5f) Color.Black.copy(alpha = 0.35f)
                else surface.copy(alpha = 0.6f)
    // A thin gesture inset gets the fade above the pill; the tall 3-button bar
    // already has the system's contrast scrim, so it only needs its own height.
    val height = if (inset < 32.dp) inset * 2 else inset
    Box(
        modifier.fillMaxWidth().height(height).background(
            Brush.verticalGradient(0f to Color.Transparent, 0.5f to scrim, 1f to scrim))
    )
}

/** Everything floating over the map, inside the safe drawing area. */
@Composable
private fun MapOverlays(
    city: City,
    accent: Color,
    didLoadOnce: Boolean,
    noVehicles: Boolean,
    loadFailed: Boolean,
    selected: Vehicle?,
    stopNames: Map<Int, String>,
    onDismissSelection: () -> Unit,
    onRecenter: () -> Unit,
) {
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(0.dp) }
    // AnimatedVisibility keeps drawing the card while it slides out, after
    // the selection itself is already gone.
    val lastSelected = remember { LastValue<Vehicle>() }
    selected?.let { lastSelected.value = it }
    val brno = city.key == "brno"

    Box(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    ) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp)) {
            // Attribution — required by OpenFreeMap, OpenMapTiles and OSM.
            // MapLibre's own logo and attribution button are off; this is it.
            Text(
                "© OpenFreeMap © OpenMapTiles © OpenStreetMap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (loadFailed) {
                Card(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(stringResource(R.string.map_load_failed),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // Success shows nothing (iOS parity) — only waiting or emptiness explain themselves.
        if (!didLoadOnce) {
            ConnectingCard(brno = brno, accent = accent, modifier = Modifier.align(Alignment.Center))
        } else if (noVehicles && !loadFailed) {
            MapCard(Modifier.align(Alignment.Center)) {
                Text(stringResource(R.string.map_no_vehicles), Modifier.padding(16.dp))
            }
        }

        val fabBottom by animateDpAsState(
            // Wait for the card's first measurement rather than aiming at 24 dp.
            targetValue = if (selected != null && cardHeight > 0.dp) cardHeight + 24.dp else 16.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow),
            label = "fabBottom",
        )
        SmallFloatingActionButton(
            onClick = onRecenter,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = accent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = fabBottom),
        ) {
            // TODO(D3): back to Icons.Filled.MyLocation once recenter goes to the
            // user's position; today it recentres on the city, and that icon would lie.
            Icon(Icons.Outlined.CenterFocusWeak,
                contentDescription = stringResource(R.string.map_recenter))
        }

        AnimatedVisibility(
            visible = selected != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val sel = selected ?: lastSelected.value ?: return@AnimatedVisibility
            MapCard(
                Modifier.fillMaxWidth().padding(12.dp)
                    .onSizeChanged { cardHeight = with(density) { it.height.toDp() } },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 14.dp, top = 14.dp, bottom = 14.dp)) {
                        Text("${kindDisplayName(sel.kind, brno = brno)} ${sel.line}",
                            style = MaterialTheme.typography.titleMedium, color = accent)
                        val dest = sel.destinationName
                            ?: sel.destinationId?.let { stopNames[it] }
                        dest?.let {
                            Text(stringResource(R.string.map_towards, it),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    IconButton(onClick = onDismissSelection) {
                        Icon(Icons.Filled.Close,
                            contentDescription = stringResource(R.string.map_close_card),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Spinner + text until the first fetch returns. Brno's stream broadcasts the
 * whole fleet in one burst about every 30 s, so a connect landing just after
 * a burst genuinely waits — after a few seconds we say why (iOS parity).
 * The card leaves composition on the first result, cancelling the timer.
 */
@Composable
private fun ConnectingCard(brno: Boolean, accent: Color, modifier: Modifier) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_CONNECT_HINT_MS)
        slow = true
    }
    MapCard(modifier.widthIn(max = 280.dp)) {
        Column(
            Modifier.padding(16.dp).animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                Text(stringResource(R.string.map_connecting))
            }
            if (slow && brno) {
                Text(
                    stringResource(R.string.map_connecting_brno_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Floating surface over the tiles: opaque and lifted so it reads on any map. */
@Composable
private fun MapCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        content = content,
    )
}

/** Plain (non-snapshot) holder: written during composition, never observed. */
private class LastValue<T : Any> { var value: T? = null }


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
