package cz.flipcom.listkomat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.R
import androidx.compose.ui.platform.LocalContext
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.DurationFormat
import java.util.Locale

@Composable
fun CityListScreen(
    cities: List<City>,
    staleHint: Boolean,
    onCitySelected: (City) -> Unit,
) {
    val language = Locale.getDefault().language
    val context = LocalContext.current
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
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    cityIcons[city.key]?.let { icon ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(icon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Column {
                        Text(city.name(language), style = MaterialTheme.typography.titleMedium)
                        Text(
                            city.tickets.joinToString(" · ") {
                                DurationFormat.format(context, it.durationMinutes)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** iOS ships the same line art (Assets.xcassets/Cities), converted to vectors. */
private val cityIcons = mapOf(
    "praha" to cz.flipcom.listkomat.R.drawable.city_praha,
    "brno" to cz.flipcom.listkomat.R.drawable.city_brno,
    "ostrava" to cz.flipcom.listkomat.R.drawable.city_ostrava,
    "plzen" to cz.flipcom.listkomat.R.drawable.city_plzen,
    "liberec" to cz.flipcom.listkomat.R.drawable.city_liberec,
    "olomouc" to cz.flipcom.listkomat.R.drawable.city_olomouc,
    "ustinadlabem" to cz.flipcom.listkomat.R.drawable.city_ustinadlabem,
    "hradeckralove" to cz.flipcom.listkomat.R.drawable.city_hradeckralove,
    "ceskebudejovice" to cz.flipcom.listkomat.R.drawable.city_ceskebudejovice,
    "pardubice" to cz.flipcom.listkomat.R.drawable.city_pardubice,
)
