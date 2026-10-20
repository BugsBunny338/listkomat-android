package cz.flipcom.listkomat.model

import androidx.compose.ui.graphics.Color

/**
 * A pickable look for the app's top bar: band colour + a contrast colour for
 * the bar's text/icons + an optional emoji mascot. Curated presets ported 1:1
 * from the iOS AppTheme (same ids, colours and mascots — the names Bláhovka,
 * Zajíc, Slim… are proper nouns / family nicknames and stay untranslated).
 * Persisted by [id].
 */
data class AppTheme(
    val id: String,
    /** String resource for the localizable names (Čistý/Černá/Růžová)… */
    val nameRes: Int? = null,
    /** …or the literal proper-noun name. Exactly one of the two is set. */
    val nameLiteral: String? = null,
    val band: Color?,        // null = plain bar (the clean look)
    val onBand: Color,
    val mascot: String?,
    val isDark: Boolean,
    val accentOverride: Color? = null,
) {
    /** Accent used throughout the app; defaults to the band colour. */
    val accent: Color get() = accentOverride ?: band ?: BrandColors.teal
    val hasBand: Boolean get() = band != null

    companion object {
        val presets: List<AppTheme> = listOf(
            AppTheme("clean", nameRes = cz.flipcom.listkomat.R.string.theme_clean,
                band = null, onBand = BrandColors.ink, mascot = null, isDark = false),
            AppTheme("black", nameRes = cz.flipcom.listkomat.R.string.theme_black,
                band = Color.Black, onBand = Color.White, mascot = null, isDark = true,
                accentOverride = BrandColors.teal),
            AppTheme("pink", nameRes = cz.flipcom.listkomat.R.string.theme_pink,
                band = Color(0xFFFF7EB6), onBand = BrandColors.ink, mascot = "🦄", isDark = false),
            AppTheme("blahovka", nameLiteral = "Bláhovka",
                band = Color(0xFFE6A52C), onBand = BrandColors.ink, mascot = "🍺", isDark = false),
            AppTheme("bomba", nameLiteral = "Bomba",
                band = Color(0xFFE6A52C), onBand = BrandColors.ink, mascot = "💣", isDark = false),
            AppTheme("zajic", nameLiteral = "Zajíc",
                band = Color(0xFFAFA79E), onBand = BrandColors.ink, mascot = "🐰", isDark = false),
            AppTheme("zaba", nameLiteral = "Žába",
                band = Color(0xFF4CC76A), onBand = BrandColors.ink, mascot = "🐸", isDark = false),
            AppTheme("meda", nameLiteral = "Méďa",
                band = Color(0xFF8B5E3C), onBand = Color.White, mascot = "🐻", isDark = true),
            AppTheme("slim", nameLiteral = "Slim",
                band = Color(0xFF74B84A), onBand = BrandColors.ink, mascot = "🐌", isDark = false),
            AppTheme("evelina", nameLiteral = "Evelína",
                band = Color(0xFFE0B04A), onBand = BrandColors.ink, mascot = "🐐", isDark = false),
            AppTheme("brno", nameLiteral = "Brno",
                band = Color(0xFFC8102E), onBand = Color.White, mascot = "🐉", isDark = true),
            AppTheme("usa", nameLiteral = "USA",
                band = Color(0xFF3C3B6E), onBand = Color.White, mascot = "🇺🇸", isDark = true),
        )

        val default: AppTheme get() = resolve("clean")

        /** Resolve a stored id, falling back to the clean look if unknown. */
        fun resolve(id: String?): AppTheme =
            presets.firstOrNull { it.id == id } ?: presets.first { it.id == "clean" }
    }
}

/** Raw brand colours, Compose-free of the ui module so models can use them. */
object BrandColors {
    val teal = Color(0xFF56C4CF)
    val ink = Color(0xFF1F1F1F)
}

/** Light / system / dark override for the whole app. */
enum class AppearanceMode { LIGHT, SYSTEM, DARK;
    companion object {
        fun from(raw: String?): AppearanceMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** WCAG relative luminance + readable glyph choice, ported from iOS. */
object GlyphContrast {
    fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    fun contrastRatio(l1: Double, l2: Double): Double {
        val hi = maxOf(l1, l2); val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Black or white — whichever has more contrast against [c]. */
    fun readableGlyph(c: Color): Color {
        val l = relativeLuminance(c)
        return if (contrastRatio(l, 1.0) >= contrastRatio(l, 0.0)) Color.White else Color.Black
    }

    /** WCAG AA for body text is 4.5:1; a hair more because the Accessibility
     *  Scanner rounds and anti-aliased glyphs measure a little lower. */
    const val MIN_TEXT_CONTRAST = 4.6

    /**
     * [accent] as text: darkened on light [surfaces], lightened on dark ones,
     * hue and saturation kept, by the smallest step that reads at
     * [MIN_TEXT_CONTRAST] on every surface. Most theme colours are band
     * colours, too light (or, in dark mode, too dark) to be text as they are.
     */
    fun readableText(accent: Color, surfaces: List<Color>): Color {
        val lums = surfaces.map(::relativeLuminance)
        fun reads(c: Color) = relativeLuminance(c).let { l -> lums.all { contrastRatio(l, it) >= MIN_TEXT_CONTRAST } }
        if (reads(accent)) return accent
        val darken = lums.average() > 0.5
        val (h, s, l) = toHsl(accent)
        var step = 1
        while (step <= 200) {
            val lightness = (if (darken) l - step * 0.005f else l + step * 0.005f).coerceIn(0f, 1f)
            val candidate = fromHsl(h, s, lightness)
            if (reads(candidate) || lightness == 0f || lightness == 1f) return candidate
            step++
        }
        return accent
    }

    private fun toHsl(c: Color): Triple<Float, Float, Float> {
        val max = maxOf(c.red, c.green, c.blue); val min = minOf(c.red, c.green, c.blue)
        val l = (max + min) / 2
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
        val h = when (max) {
            c.red -> (c.green - c.blue) / d + (if (c.green < c.blue) 6 else 0)
            c.green -> (c.blue - c.red) / d + 2
            else -> (c.red - c.green) / d + 4
        } / 6
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Color {
        if (s == 0f) return Color(l, l, l)
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Float): Float {
            val t = (t0 + 1) % 1
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return Color(channel(h + 1f / 3), channel(h), channel(h - 1f / 3))
    }
}
