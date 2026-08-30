package cz.flipcom.listkomat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cz.flipcom.listkomat.R

/**
 * Lístkomat brand, ported from the iOS app (Shared/BrandColor.swift,
 * Theme.swift): teal #56C4CF sampled from the original 2016 app icon, "ink"
 * near-black instead of pure black, and Alte Haas Grotesk for titles and
 * headlines only — body text stays the system font for legibility.
 */
object Brand {
    val teal = Color(0xFF56C4CF)
    val ink = Color(0xFF1F1F1F)          // Color(white: 0.12) on iOS
    val tealDim = Color(0xFF2E8792)      // darker teal: accents on light surfaces
    val tealContainer = Color(0xFFD3F0F3) // soft teal tint: active-ticket banner
    val tealContainerDark = Color(0xFF12474D)
}

val AlteHaas = FontFamily(
    Font(R.font.alte_haas_grotesk, FontWeight.Normal),
    Font(R.font.alte_haas_grotesk_bold, FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Brand.tealDim,             // legible on white (raw teal is too light)
    onPrimary = Color.White,
    primaryContainer = Brand.tealContainer,
    onPrimaryContainer = Brand.ink,
    secondary = Brand.teal,
    surface = Color(0xFFFCFDFD),
    onSurface = Brand.ink,
    surfaceVariant = Color(0xFFF6F8F8),  // Actual card fill; near-white, teal-tinged
    onSurfaceVariant = Color(0xFF5A6465),
    background = Color(0xFFFCFDFD),
    onBackground = Brand.ink,
    outlineVariant = Color(0xFFE2EAEA),
)

private val DarkColors = darkColorScheme(
    primary = Brand.teal,
    onPrimary = Brand.ink,
    primaryContainer = Brand.tealContainerDark,
    onPrimaryContainer = Color(0xFFCFEDF0),
    secondary = Brand.teal,
    surface = Color(0xFF121415),
    onSurface = Color(0xFFE8EAEA),
    surfaceVariant = Color(0xFF1D2122),
    onSurfaceVariant = Color(0xFF9AA5A6),
    background = Color(0xFF121415),
    onBackground = Color(0xFFE8EAEA),
    outlineVariant = Color(0xFF2B3233),
)

/** Titles/headlines in the brand font; everything else default (system). */
private val BrandTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontFamily = AlteHaas, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontFamily = AlteHaas, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = AlteHaas, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontFamily = AlteHaas, fontWeight = FontWeight.Bold),
    )
}

@Composable
fun ListkomatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = BrandTypography,
        content = content,
    )
}
