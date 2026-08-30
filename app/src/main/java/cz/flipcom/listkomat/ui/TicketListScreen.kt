package cz.flipcom.listkomat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.R
import androidx.compose.ui.platform.LocalContext
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.DurationFormat
import cz.flipcom.listkomat.model.Ticket
import java.util.Locale

@Composable
fun TicketListScreen(
    city: City,
    onBack: () -> Unit,
    onBuy: (Ticket) -> Unit,
) {
    val language = Locale.getDefault().language
    val context = LocalContext.current
    var confirming by remember { mutableStateOf<Ticket?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                city.name(language),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            )
        }
        items(city.tickets, key = { it.code }) { ticket ->
            Card(
                Modifier.fillMaxWidth().clickable { confirming = ticket },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(DurationFormat.format(context, ticket.durationMinutes),
                            style = MaterialTheme.typography.titleMedium)
                        ticket.note(language)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.price_format, ticket.priceKc),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    confirming?.let { ticket ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.buy_confirm_title)) },
            text = {
                Text(stringResource(
                    R.string.buy_confirm_message, ticket.code, city.smsNumber, ticket.priceKc))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onBuy(ticket)
                }) { Text(stringResource(R.string.buy_confirm_send)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
