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
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import cz.flipcom.listkomat.model.Ticket
import java.util.Locale

@Composable
fun TicketListScreen(
    city: City,
    updatedAt: String,
    staleHint: Boolean,
    showSimNotice: Boolean,
    onDismissSimNotice: () -> Unit,
    onBuy: (Ticket) -> Unit,
) {
    val language = Locale.getDefault().language
    val context = LocalContext.current
    var confirming by remember { mutableStateOf<Ticket?>(null) }
    var showingSheet by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                cityIcons[city.key]?.let { icon ->
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Text(city.name(language), style = MaterialTheme.typography.headlineMedium)
            }
        }

        if (showSimNotice) {
            item(key = "sim-notice") {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.sim_notice_title),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.sim_notice_body),
                            style = MaterialTheme.typography.bodySmall)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showingSheet = true }) {
                                Text(stringResource(R.string.sim_notice_more))
                            }
                            TextButton(onClick = onDismissSimNotice) {
                                Text(stringResource(R.string.sim_notice_dismiss))
                            }
                        }
                    }
                }
            }
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
        item(key = "footer") {
            Column(Modifier.padding(top = 8.dp)) {
                val secondary = MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(R.string.footer_how, city.smsNumber),
                    style = MaterialTheme.typography.bodySmall, color = secondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.footer_sim),
                        style = MaterialTheme.typography.bodySmall, color = secondary)
                    TextButton(onClick = { showingSheet = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 4.dp, top = 0.dp, end = 4.dp, bottom = 0.dp)) {
                        Text(stringResource(R.string.footer_sim_more),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    if (staleHint) stringResource(R.string.footer_offline, formatCatalogDate(updatedAt))
                    else stringResource(R.string.footer_prices_as_of, formatCatalogDate(updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (staleHint) MaterialTheme.colorScheme.error else secondary,
                )
            }
        }
    }

    if (showingSheet) {
        SimRequirementSheet(onDismiss = { showingSheet = false })
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

/** "2026-08-30" -> "30. 8. 2026", tolerant of anything else (shown verbatim). */
internal fun formatCatalogDate(updatedAt: String): String {
    val p = updatedAt.split("-")
    if (p.size != 3) return updatedAt
    val (y, m, d) = p
    val di = d.toIntOrNull() ?: return updatedAt
    val mi = m.toIntOrNull() ?: return updatedAt
    return "$di. $mi. $y"
}
