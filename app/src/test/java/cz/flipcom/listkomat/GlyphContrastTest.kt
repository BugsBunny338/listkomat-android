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
        val green = Color(0xFF4CC76A)
        val text = GlyphContrast.readableText(green, lightSurfaces)
        assertTrue("still green: $text", text.green > text.red && text.green > text.blue)
    }

    @Test
    fun `the change is the smallest that passes`() {
        // One step less dark would fail: the default teal moves only as far as it must.
        val tealDim = Color(0xFF2E8792)
        val text = GlyphContrast.readableText(tealDim, lightSurfaces)
        assertTrue(worst(text, lightSurfaces) < MIN_TEXT_CONTRAST + 0.25)
    }
}
