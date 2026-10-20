package cz.flipcom.listkomat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cz.flipcom.listkomat.R
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.AppearanceMode
import cz.flipcom.listkomat.model.GlyphContrast

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

/** Blend [accent] over [base] the way a 12% tint composites on the surface. */
private fun tintOver(base: Color, accent: Color, alpha: Float): Color = Color(
    red = base.red + (accent.red - base.red) * alpha,
    green = base.green + (accent.green - base.green) * alpha,
    blue = base.blue + (accent.blue - base.blue) * alpha,
    alpha = 1f,
)

@Composable
fun ListkomatTheme(
    theme: AppTheme = AppTheme.default,
    appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (appearanceMode) {
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = remember(theme, dark) { themedColorScheme(theme, dark) }
    MaterialTheme(
        colorScheme = scheme,
        typography = BrandTypography,
        content = content,
    )
}

/**
 * The colour scheme for [theme]. The clean/black looks start from the tuned
 * teal scheme; a coloured band re-tints the accent roles so the whole app
 * reads as one theme (iOS parity). Either way primary is then shaded until it
 * reads as text (prices, links, headings) on every surface it is drawn on:
 * the clean light teal darkens a little, most band colours a lot. The band in
 * the top bar keeps the theme's own colour.
 */
internal fun themedColorScheme(theme: AppTheme, dark: Boolean): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    val accent = theme.accent
    val tinted = if (accent == Brand.teal) base else base.copy(
        primary = accent,
        primaryContainer = tintOver(base.surface, accent, 0.14f),
        onPrimaryContainer = base.onSurface,
        secondary = accent,
    )
    val textAccent = GlyphContrast.readableText(tinted.primary, accentTextSurfaces(tinted))
    // Unchanged clean teal keeps its tuned onPrimary (ink in dark mode).
    if (textAccent == base.primary) return tinted
    return tinted.copy(primary = textAccent, onPrimary = GlyphContrast.readableGlyph(textAccent))
}

/** Where primary appears as text: page, cards, the notice tint, and the
 *  sheet / menu / dialog containers (M3 defaults, not set in the schemes). */
internal fun accentTextSurfaces(scheme: ColorScheme): List<Color> = listOf(
    scheme.background, scheme.surfaceVariant, scheme.primaryContainer,
    scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh,
)

/**
 * Whether the app's surface is dark: the app's own appearance mode and theme,
 * not the system's. The live map's basemap style and its navigation-bar scrim
 * both follow this, so they can never disagree.
 */
@Composable
@ReadOnlyComposable
fun surfaceIsDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
