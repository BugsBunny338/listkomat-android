package cz.flipcom.listkomat

import androidx.compose.ui.graphics.Color
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.GlyphContrast
import cz.flipcom.listkomat.model.GlyphContrast.MIN_TEXT_CONTRAST
import cz.flipcom.listkomat.ui.Brand
import cz.flipcom.listkomat.ui.Warning
import cz.flipcom.listkomat.ui.accentTextSurfaces
import cz.flipcom.listkomat.ui.simNoticeColors
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

    @Test
    fun `the foreign-SIM notice reads on its orange tint on every theme`() {
        for (theme in AppTheme.presets) for (dark in listOf(false, true)) {
            val scheme = themedColorScheme(theme, dark)
            val notice = simNoticeColors(scheme)
            for ((what, fg) in listOf(
                "icon" to notice.content, "title" to scheme.onSurface,
                "body" to scheme.onSurfaceVariant, "link" to scheme.primary,
            )) {
                val r = ratio(fg, notice.container)
                assertTrue("${theme.id} dark=$dark $what: $r", r >= MIN_TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun `the notice is orange, not the theme colour`() {
        val light = simNoticeColors(themedColorScheme(AppTheme.resolve("pink"), dark = false))
        val dark = simNoticeColors(themedColorScheme(AppTheme.resolve("pink"), dark = true))
        // Dark mode's orange already reads on its tint and is left alone.
        assertEquals(Warning.orangeDark, dark.content)
        // Light mode keeps the orange hue, only darker.
        val (h, _, l) = GlyphContrast.toHsl(light.content)
        val (oh, _, ol) = GlyphContrast.toHsl(Warning.orange)
        assertTrue("hue $h vs $oh", kotlin.math.abs(h - oh) < 0.01f)
        assertTrue("lightness $l vs $ol", l < ol)
        assertTrue(light.container.red > light.container.blue)
    }
}
