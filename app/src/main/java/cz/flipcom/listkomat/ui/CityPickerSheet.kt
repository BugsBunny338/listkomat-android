package cz.flipcom.listkomat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.model.City
import java.util.Locale

/**
 * Manual city override — the same grid of 2016 landmark icons the iOS
 * CityPickerView shows (accent line art on a 12% tile; white on accent when
 * selected), as a Material bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPickerSheet(
    cities: List<City>,
    selectedKey: String?,
    onSelect: (City) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = Locale.getDefault().language
    // Full height on open — the grid is the whole point of the sheet and
    // there's nothing to see behind it (Jiri's feedback on the half state).
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.city_picker_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cities, key = { it.key }) { city ->
                CityTile(
                    city = city,
                    name = city.name(language),
                    selected = city.key == selectedKey,
                    onClick = { onSelect(city) },
                )
            }
        }
    }
}

@Composable
private fun CityTile(city: City, name: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    // The whole tile — icon box AND label — is one target (tapping the
    // city name must work too).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    if (selected) accent else accent.copy(alpha = 0.12f),
                    RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            cityIcons[city.key]?.let { icon ->
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else accent,
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** iOS ships the same line art (Assets.xcassets/Cities), converted to vectors. */
internal val cityIcons = mapOf(
    "praha" to R.drawable.city_praha,
    "brno" to R.drawable.city_brno,
    "ostrava" to R.drawable.city_ostrava,
    "plzen" to R.drawable.city_plzen,
    "liberec" to R.drawable.city_liberec,
    "olomouc" to R.drawable.city_olomouc,
    "ustinadlabem" to R.drawable.city_ustinadlabem,
    "hradeckralove" to R.drawable.city_hradeckralove,
    "ceskebudejovice" to R.drawable.city_ceskebudejovice,
    "pardubice" to R.drawable.city_pardubice,
)
