package cz.flipcom.listkomat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.R

/**
 * The permanent explanation of the Czech-SIM requirement. One sheet, two entry
 * points (the one-time note and the ticket-list footer link), so the copy
 * never drifts apart. iOS has a third — the send-failure alert — which can't
 * exist here: ACTION_SENDTO reports nothing back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimRequirementSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.sim_sheet_title),
                style = MaterialTheme.typography.headlineSmall)
            SheetSection(R.string.sim_sheet_how_title, R.string.sim_sheet_how_body)
            SheetSection(R.string.sim_sheet_czech_title, R.string.sim_sheet_czech_body)
            SheetSection(R.string.sim_sheet_roaming_title, R.string.sim_sheet_roaming_body)
        }
    }
}

@Composable
private fun SheetSection(title: Int, body: Int) {
    Text(stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
}
