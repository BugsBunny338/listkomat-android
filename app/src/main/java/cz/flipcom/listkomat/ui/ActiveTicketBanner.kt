package cz.flipcom.listkomat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.model.ActiveTicket
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The in-app countdown for the one active ticket: pending ("waiting for
 * confirmation" + Confirm now) until validFrom, then time-left until endMs,
 * then an expired state. Android counterpart of the iOS banner + Live Activity
 * (a proper ongoing notification is a parity-phase item).
 */
@Composable
fun ActiveTicketBanner(
    ticket: ActiveTicket,
    onConfirmNow: () -> Unit,
    onEnd: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticket) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val pending = ticket.timeline.isPending(now)
    val expired = ticket.timeline.isExpired(now)

    Card(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expired) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "${ticket.cityName} · ${ticket.ticketLabel}",
                    style = MaterialTheme.typography.titleMedium,
                )
                when {
                    expired -> Text(
                        stringResource(R.string.ticket_expired),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    pending -> Text(
                        stringResource(R.string.pending_confirmation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            formatTimeLeft(ticket.timeline.endMs - now),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            stringResource(R.string.ticket_valid_until,
                                DateFormat.getTimeInstance(DateFormat.SHORT)
                                    .format(Date(ticket.timeline.endMs))),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (pending && !expired) {
                    TextButton(onClick = onConfirmNow) {
                        Text(stringResource(R.string.confirm_now))
                    }
                }
                TextButton(onClick = onEnd) { Text(stringResource(R.string.end_ticket)) }
            }
        }
    }
}

/** 90 min ticket → "1:29:59"; last hour → "59:59". */
internal fun formatTimeLeft(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
