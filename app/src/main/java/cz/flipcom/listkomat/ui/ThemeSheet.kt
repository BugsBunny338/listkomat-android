package cz.flipcom.listkomat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.AppearanceMode

/**
 * The appearance sheet, ported from the iOS ThemeSheet: light/system/dark
 * segmented mode, the theme presets with a miniature of the bar, and the app
 * version in the conventional bottom-of-settings spot. Tapping a theme applies
 * it live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(
    themeId: String,
    appearanceMode: AppearanceMode,
    onTheme: (String) -> Unit,
    onAppearance: (AppearanceMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.appearance_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            val modes = listOf(
                AppearanceMode.LIGHT to R.string.appearance_light,
                AppearanceMode.SYSTEM to R.string.appearance_system,
                AppearanceMode.DARK to R.string.appearance_dark,
            )
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = appearanceMode == mode,
                    onClick = { onAppearance(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                ) { Text(stringResource(label)) }
            }
        }
        Text(
            stringResource(R.string.theme_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        val context = LocalContext.current
        val version = remembered@ run {
            runCatching {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                "Lístkomat ${pi.versionName}"
            }.getOrDefault("Lístkomat")
        }
        LazyColumn {
            items(AppTheme.presets, key = { it.id }) { theme ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onTheme(theme.id) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Miniature of the bar: band colour with mascot / accent hint.
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 34.dp)
                            .background(theme.band ?: MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (theme.mascot != null) {
                            Text(theme.mascot, style = MaterialTheme.typography.titleMedium)
                        } else {
                            Text("Aa", style = MaterialTheme.typography.labelMedium,
                                color = theme.accent)
                        }
                    }
                    Text(
                        theme.nameRes?.let { stringResource(it) } ?: theme.nameLiteral.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (theme.id == themeId) {
                        Icon(Icons.Filled.Check, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Text(
                    version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
        }
    }
}
