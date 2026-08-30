package cz.flipcom.listkomat.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cz.flipcom.listkomat.AppViewModel
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.data.SmsPurchase
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.Ticket

/**
 * Single-activity app: city list → ticket list, with the active-ticket banner
 * pinned above both. Navigation is a plain state variable — two screens don't
 * need a nav library yet.
 */
@Composable
fun ListkomatApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val catalog by viewModel.catalog.collectAsState()
    val refreshFailed by viewModel.refreshFailed.collectAsState()
    val activeTicket by viewModel.activeTicket.collectAsState()
    val pendingPurchase by viewModel.pendingPurchase.collectAsState()

    var selectedCityKey by remember { mutableStateOf<String?>(null) }
    val selectedCity = catalog.cities.firstOrNull { it.key == selectedCityKey }

    ListkomatTheme {
        Scaffold { padding ->
            Column(Modifier.padding(padding)) {
                activeTicket?.let { active ->
                    ActiveTicketBanner(
                        ticket = active,
                        onConfirmNow = viewModel::confirmNow,
                        onEnd = viewModel::endTicket,
                    )
                }
                if (selectedCity == null) {
                    CityListScreen(
                        cities = catalog.cities,
                        staleHint = refreshFailed,
                        onCitySelected = { selectedCityKey = it.key },
                    )
                } else {
                    BackHandler { selectedCityKey = null }
                    TicketListScreen(
                        city = selectedCity,
                        onBack = { selectedCityKey = null },
                        onBuy = { ticket ->
                            try {
                                context.startActivity(
                                    SmsPurchase.intent(selectedCity.smsNumber, ticket.code))
                                viewModel.smsHandedOff(selectedCity, ticket)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context,
                                    context.getString(R.string.no_sms_app), Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }
        pendingPurchase?.let { pending ->
            SmsSentDialog(
                city = pending.city,
                ticket = pending.ticket,
                onSent = viewModel::purchaseConfirmed,
                onNotSent = viewModel::purchaseDismissed,
            )
        }
    }
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

@Composable
fun ListkomatTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
