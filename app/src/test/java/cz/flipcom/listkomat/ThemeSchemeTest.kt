package cz.flipcom.listkomat

import androidx.compose.ui.graphics.Color
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.GlyphContrast
import cz.flipcom.listkomat.model.GlyphContrast.MIN_TEXT_CONTRAST
import cz.flipcom.listkomat.ui.Brand
import cz.flipcom.listkomat.ui.accentTextSurfaces
import cz.flipcom.listkomat.ui.themedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real schemes the app runs with, every theme in both modes. */
class ThemeSchemeTest {

    private fun ratio(a: Color, b: Color) = GlyphContrast.contrastRatio(
        GlyphContrast.relativeLuminance(a), GlyphContrast.relativeLuminance(b))

    @Test
    fun `accent text reads on every surface it is drawn on`() {
        for (theme in AppTheme.presets) for (dark in listOf(false, true)) {
            val scheme = themedColorScheme(theme, dark)
            for (surface in accentTextSurfaces(scheme)) {
                val r = ratio(scheme.primary, surface)
                assertTrue("${theme.id} dark=$dark on $surface: $r", r >= MIN_TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun `labels on accent fills read too`() {
        for (theme in AppTheme.presets) for (dark in listOf(false, true)) {
            val scheme = themedColorScheme(theme, dark)
            val r = ratio(scheme.onPrimary, scheme.primary)
            assertTrue("${theme.id} dark=$dark: $r", r >= 4.5)
        }
    }

    @Test
    fun `the clean dark teal is untouched and keeps its ink label`() {
        val scheme = themedColorScheme(AppTheme.resolve("clean"), dark = true)
        assertEquals(Brand.teal, scheme.primary)
        assertEquals(Brand.ink, scheme.onPrimary)
    }
}
