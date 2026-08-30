package cz.flipcom.listkomat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.model.City
import java.util.Locale

@Composable
fun CityListScreen(
    cities: List<City>,
    staleHint: Boolean,
    onCitySelected: (City) -> Unit,
) {
    val language = Locale.getDefault().language
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.cities_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            )
        }
        if (staleHint) {
            item {
                Text(
                    stringResource(R.string.stale_prices_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        items(cities, key = { it.key }) { city ->
            Card(
                Modifier.fillMaxWidth().clickable { onCitySelected(city) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(city.name(language), style = MaterialTheme.typography.titleMedium)
                    Text(
                        ticketSummary(city),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** e.g. "30 min · 90 min · 24 h · 72 h" */
private fun ticketSummary(city: City): String =
    city.tickets.joinToString(" · ") { it.duration }
