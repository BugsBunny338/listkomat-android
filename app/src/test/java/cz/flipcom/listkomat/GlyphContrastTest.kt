package cz.flipcom.listkomat

import androidx.compose.ui.graphics.Color
import cz.flipcom.listkomat.model.AppTheme
import cz.flipcom.listkomat.model.GlyphContrast
import cz.flipcom.listkomat.model.GlyphContrast.MIN_TEXT_CONTRAST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphContrastTest {

    // The surfaces accent text sits on (ui/Theme.kt): page, card, sheet.
    private val lightSurfaces = listOf(Color(0xFFFCFDFD), Color(0xFFF6F8F8), Color(0xFFF7F2FA))
    private val darkSurfaces = listOf(Color(0xFF121415), Color(0xFF1D2122), Color(0xFF1D1B20))

    private fun ratio(a: Color, b: Color) = GlyphContrast.contrastRatio(
        GlyphContrast.relativeLuminance(a), GlyphContrast.relativeLuminance(b))

    private fun worst(c: Color, surfaces: List<Color>) = surfaces.minOf { ratio(c, it) }

    @Test
    fun `every theme's accent reads as text on light surfaces`() {
        for (theme in AppTheme.presets) {
            val text = GlyphContrast.readableText(theme.accent, lightSurfaces)
            assertTrue("${theme.id}: ${worst(text, lightSurfaces)}", worst(text, lightSurfaces) >= MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `every theme's accent reads as text on dark surfaces`() {
        for (theme in AppTheme.presets) {
            val text = GlyphContrast.readableText(theme.accent, darkSurfaces)
            assertTrue("${theme.id}: ${worst(text, darkSurfaces)}", worst(text, darkSurfaces) >= MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `an accent that already reads is left alone`() {
        val navy = Color(0xFF3C3B6E)  // USA on light: ~10:1
        assertEquals(navy, GlyphContrast.readableText(navy, lightSurfaces))
    }

    @Test
    fun `light surfaces darken the accent, dark ones lighten it`() {
        val pink = Color(0xFFFF7EB6)
        val brnoRed = Color(0xFFC8102E)
        val lum = GlyphContrast::relativeLuminance
        assertTrue(lum(GlyphContrast.readableText(pink, lightSurfaces)) < lum(pink))
        assertTrue(lum(GlyphContrast.readableText(brnoRed, darkSurfaces)) > lum(brnoRed))
    }

    @Test
    fun `the hue survives the adjustment`() {
        for (theme in AppTheme.presets) {
            val (h0, s0, _) = GlyphContrast.toHsl(theme.accent)
            val (h1, s1, _) = GlyphContrast.toHsl(GlyphContrast.readableText(theme.accent, lightSurfaces))
            if (s0 == 0f) continue  // greys have no hue
            assertEquals("${theme.id} hue", h0, h1, 0.005f)
            assertEquals("${theme.id} saturation", s0, s1, 0.02f)
        }
    }

    @Test
    fun `the change is the smallest that passes`() {
        // One lightness step back towards the original must fail again.
        for (theme in AppTheme.presets) for (surfaces in listOf(lightSurfaces, darkSurfaces)) {
            val text = GlyphContrast.readableText(theme.accent, surfaces)
            if (text == theme.accent) continue
            val (h, sat, l) = GlyphContrast.toHsl(text)
            val (_, _, l0) = GlyphContrast.toHsl(theme.accent)
            val back = l + if (l0 > l) GlyphContrast.LIGHTNESS_STEP else -GlyphContrast.LIGHTNESS_STEP
            val oneLess = GlyphContrast.fromHsl(h, sat, back)
            assertTrue("${theme.id}: one step less still reads", worst(oneLess, surfaces) < MIN_TEXT_CONTRAST)
        }
    }
}
