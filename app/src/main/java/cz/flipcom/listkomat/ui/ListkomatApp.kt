package cz.flipcom.listkomat.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.AppViewModel
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.data.SmsPurchase
import cz.flipcom.listkomat.AppViewModel.LocationState
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.DurationFormat
import cz.flipcom.listkomat.model.NearestCity
import cz.flipcom.listkomat.model.Ticket
import java.util.Locale

/**
 * Mirrors the iOS flow: the home screen IS the current city's ticket list
 * (manual selection, persisted; the GPS-nearest default is a future feature),
 * with the city picker behind a top-bar action. First launch, with no city
 * picked yet, shows an empty state that opens the picker. iOS also has a
 * themes button up there — that arrives with the theme system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListkomatApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val catalog by viewModel.catalog.collectAsState()
    val refreshFailed by viewModel.refreshFailed.collectAsState()
    val activeTicket by viewModel.activeTicket.collectAsState()
    val pendingPurchase by viewModel.pendingPurchase.collectAsState()
    val selectedCityKey by viewModel.selectedCityKey.collectAsState()
    val simNoticeDismissed by viewModel.simNoticeDismissed.collectAsState()

    val locationState by viewModel.locationState.collectAsState()

    // Manual selection wins; otherwise the GPS-nearest city, but only if it's
    // close enough to plausibly be where the user actually is (iOS parity).
    val locatedKey = (locationState as? LocationState.Located)
        ?.takeIf { it.distanceKm <= NearestCity.MAX_DEFAULT_DISTANCE_KM }?.cityKey
    val currentCity = catalog.cities.firstOrNull { it.key == selectedCityKey }
        ?: catalog.cities.firstOrNull { it.key == locatedKey }
    var showingPicker by remember { mutableStateOf(false) }
    var showingPrimer by remember { mutableStateOf(false) }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onLocationPermission(granted)
    }
    // First run with no manual city: prime, then ask (iOS shows the primer
    // whenever authorization is still undetermined).
    androidx.compose.runtime.LaunchedEffect(selectedCityKey) {
        if (selectedCityKey == null && locationState is LocationState.Idle) {
            val ctx = context
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.onLocationPermission(true) else showingPrimer = true
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { /* result irrelevant */ }
    val confirmPurchase = {
        viewModel.purchaseConfirmed()
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ListkomatTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = { showingPicker = true }) {
                            Icon(Icons.Filled.Place,
                                contentDescription = stringResource(R.string.pick_city_button),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                activeTicket?.let { active ->
                    ActiveTicketBanner(
                        ticket = active,
                        onConfirmNow = viewModel::confirmNow,
                        onEnd = viewModel::endTicket,
                    )
                }
                if (currentCity == null) {
                    EmptyState(
                        state = locationState,
                        cityName = { key -> catalog.cities.firstOrNull { it.key == key }
                            ?.name(Locale.getDefault().language) },
                        onPick = { showingPicker = true },
                    )
                } else {
                    TicketListScreen(
                        city = currentCity,
                        updatedAt = catalog.updatedAt,
                        staleHint = refreshFailed,
                        showSimNotice = !simNoticeDismissed &&
                            viewModel.shouldShowSimNotice(Locale.getDefault().language),
                        onDismissSimNotice = viewModel::dismissSimNotice,
                        onBuy = { ticket ->
                            try {
                                context.startActivity(
                                    SmsPurchase.intent(currentCity.smsNumber, ticket.code))
                                viewModel.smsHandedOff(currentCity, ticket)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context,
                                    context.getString(R.string.no_sms_app), Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }
        if (showingPrimer) {
            LocationPrimer(onContinue = {
                showingPrimer = false
                locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            })
        }
        if (showingPicker) {
            CityPickerSheet(
                cities = catalog.cities,
                selectedKey = currentCity?.key,
                onSelect = { city ->
                    viewModel.selectCity(city.key)
                    showingPicker = false
                },
                onDismiss = { showingPicker = false },
            )
        }
        pendingPurchase?.let { pending ->
            SmsSentDialog(
                city = pending.city,
                ticket = pending.ticket,
                onSent = confirmPurchase,
                onNotSent = viewModel::purchaseDismissed,
            )
        }
    }
}

/** iOS parity: searching / denied / too-far each explain themselves. */
@Composable
private fun EmptyState(
    state: LocationState,
    cityName: (String) -> String?,
    onPick: () -> Unit,
) {
    val far = (state as? LocationState.Located)
        ?.takeIf { it.distanceKm > NearestCity.MAX_DEFAULT_DISTANCE_KM }
    val title = when {
        far != null -> stringResource(R.string.empty_far)
        state is LocationState.Denied -> stringResource(R.string.empty_denied)
        state is LocationState.Searching -> stringResource(R.string.empty_searching)
        else -> stringResource(R.string.empty_pick_city)
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            far?.let {
                Text(
                    stringResource(R.string.empty_far_subtitle,
                        cityName(it.cityKey) ?: it.cityKey, it.distanceKm.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Button(onClick = onPick) { Text(stringResource(R.string.pick_city_button)) }
        }
    }
}

/** Friendly priming dialog before the system prompt, so the ask makes sense. */
@Composable
private fun LocationPrimer(onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.primer_title)) },
        text = { Text(stringResource(R.string.primer_body)) },
        confirmButton = {
            Button(onClick = onContinue) { Text(stringResource(R.string.primer_continue)) }
        },
    )
}

/** Asked when the user comes back from the SMS app — ACTION_SENDTO reports nothing. */
@Composable
private fun SmsSentDialog(city: City, ticket: Ticket, onSent: () -> Unit, onNotSent: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotSent,
        title = { Text(stringResource(R.string.sms_sent_title)) },
        text = { Text(stringResource(R.string.sms_sent_message)) },
        confirmButton = {
            TextButton(onClick = onSent) { Text(stringResource(R.string.sms_sent_yes)) }
        },
        dismissButton = {
            TextButton(onClick = onNotSent) { Text(stringResource(R.string.sms_sent_no)) }
        },
    )
}
