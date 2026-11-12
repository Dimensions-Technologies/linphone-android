package org.linphone.utils

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colours from the tenant's branding (GET user branding, cssVariables), as the iOS app's
 * BrandingService applies them.
 */
object BrandColors {
    /** iOS's default accent (App.xaml Colors.accent), used until or unless the brand sets one. */
    const val DEFAULT_ACCENT: Int = 0xFFD4FF38.toInt()

    /**
     * The highlight colour of the selected bottom-bar item and the History tab underline: the
     * brand's "accent" CSS variable, as on iOS (Shell.TabBarForegroundColor, Colors.accent).
     */
    fun accentOf(cssVariables: Map<String, String>?): Int =
        parseCssColor(cssVariables?.get("accent")) ?: DEFAULT_ACCENT

    /** iOS's default primary (App.xaml Colors.primary), used until or unless the brand sets one. */
    const val DEFAULT_PRIMARY: Int = 0xFFC9FF05.toInt()

    /** The brand's "primary" CSS variable: the fill of iOS's Call voicemail button. */
    fun primaryOf(cssVariables: Map<String, String>?): Int =
        parseCssColor(cssVariables?.get("primary")) ?: DEFAULT_PRIMARY

    /**
     * The History rows' direction icons, as iOS's and the web client's: outgoing green, incoming
     * info, missed red, each the brand's, else iOS's defaults (App.xaml).
     */
    data class DirectionColors(val outgoing: Int, val incoming: Int, val missed: Int)

    val DEFAULT_DIRECTION_COLORS = DirectionColors(
        outgoing = 0xFF70CE12.toInt(),
        incoming = 0xFF6A93A9.toInt(),
        missed = 0xFFD61F3D.toInt()
    )

    fun directionColorsOf(cssVariables: Map<String, String>?): DirectionColors = DirectionColors(
        outgoing = parseCssColor(cssVariables?.get("green")) ?: DEFAULT_DIRECTION_COLORS.outgoing,
        incoming = parseCssColor(cssVariables?.get("info")) ?: DEFAULT_DIRECTION_COLORS.incoming,
        missed = parseCssColor(cssVariables?.get("red")) ?: DEFAULT_DIRECTION_COLORS.missed
    )

    /** iOS's default navbar-bg (App.xaml Colors.navbar-bg). */
    const val DEFAULT_NAVBAR_BG: Int = 0xFF1C3440.toInt()

    /**
     * The brand's "navbar-bg": behind the initials or icon of an avatar without a picture, as iOS's
     * CallAvatar (the web client's light theme).
     */
    fun navbarBgOf(cssVariables: Map<String, String>?): Int =
        parseCssColor(cssVariables?.get("navbar-bg")) ?: DEFAULT_NAVBAR_BG

    /** iOS's default navbar-color (App.xaml Colors.navbar-color). */
    const val DEFAULT_NAVBAR_COLOR: Int = 0xFFDEEBED.toInt()

    /** The brand's "navbar-color": icons on the top bar, as iOS's Shell.ForegroundColor. */
    fun navbarColorOf(cssVariables: Map<String, String>?): Int =
        parseCssColor(cssVariables?.get("navbar-color")) ?: DEFAULT_NAVBAR_COLOR

    /** iOS's default dark brand colour (App.xaml Colors.dark), used unless the brand sets "dark". */
    const val DEFAULT_DARK: Int = 0xFF1C3440.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** The brand's "dark" colour, which iOS uses for text and icons on light brand colours. */
    fun darkOf(cssVariables: Map<String, String>?): Int =
        parseCssColor(cssVariables?.get("dark")) ?: DEFAULT_DARK

    /**
     * What reads on [color], as iOS's BrandingService.GetContrastColor: the brand's [dark] colour
     * when [color]'s CIE L* lightness is over half, else white.
     */
    fun contrastOf(color: Int, dark: Int = DEFAULT_DARK): Int = if (lStar(color) > 0.5) dark else WHITE

    private fun lStar(color: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val y = 0.2126 * linear((color shr 16) and 0xFF) +
            0.7152 * linear((color shr 8) and 0xFF) +
            0.0722 * linear(color and 0xFF)
        val l = if (y <= 216.0 / 24389) y * 24389 / 27 else 116 * cbrt(y) - 16
        return l / 100
    }

    /**
     * An ARGB colour from a CSS colour, as tenant brands hold them (e.g. "hsl(73, 100%, 61%)"):
     * #rgb, #rgba, #rrggbb, #rrggbbaa, rgb(), rgba(), hsl() or hsla(). Null otherwise.
     */
    fun parseCssColor(value: String?): Int? {
        val css = value?.trim()?.lowercase() ?: return null
        val function = Regex("""^(rgba?|hsla?)\((.*)\)$""").find(css)
        return if (function != null) {
            parseFunction(function.groupValues[1], function.groupValues[2])
        } else {
            parseHex(css)
        }
    }

    private fun parseFunction(name: String, args: String): Int? {
        // Commas or spaces between the parts, and "/" before the alpha in the newer syntax
        val parts = args.split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size !in 3..4) return null
        val alpha = if (parts.size == 4) fraction(parts[3]) ?: return null else 1.0
        val (r, g, b) = if (name.startsWith("rgb")) {
            val channels = parts.take(3).map { channel(it) ?: return null }
            Triple(channels[0], channels[1], channels[2])
        } else {
            val hue = parts[0].removeSuffix("deg").toDoubleOrNull() ?: return null
            val saturation = percent(parts[1]) ?: return null
            val lightness = percent(parts[2]) ?: return null
            hslToRgb(hue, saturation, lightness)
        }
        return argb((alpha * 255).roundToInt(), r, g, b)
    }

    /** An rgb() channel: 0-255, or a percentage. */
    private fun channel(value: String): Int? =
        if (value.endsWith("%")) {
            percent(value)?.let { (it * 255).roundToInt() }
        } else {
            value.toDoubleOrNull()?.roundToInt()
        }?.coerceIn(0, 255)

    /** An alpha: 0-1, or a percentage. */
    private fun fraction(value: String): Double? =
        (if (value.endsWith("%")) percent(value) else value.toDoubleOrNull())?.coerceIn(0.0, 1.0)

    private fun percent(value: String): Double? =
        value.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toDoubleOrNull()?.let { (it / 100).coerceIn(0.0, 1.0) }

    private fun hslToRgb(hue: Double, saturation: Double, lightness: Double): Triple<Int, Int, Int> {
        val h = ((hue % 360) + 360) % 360 / 60
        val c = (1 - abs(2 * lightness - 1)) * saturation
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = lightness - c / 2
        return Triple(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun parseHex(css: String): Int? {
        if (!css.startsWith("#")) return null
        val hex = css.removePrefix("#")
        if (hex.isEmpty() || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val full = when (hex.length) {
            3, 4 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> return null
        }
        val rgb = full.substring(0, 6).toLong(16)
        val alpha = if (full.length == 8) full.substring(6, 8).toLong(16) else 0xFF
        return ((alpha shl 24) or rgb).toInt()
    }
}
