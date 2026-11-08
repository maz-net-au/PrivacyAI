package au.net.maz.privacyai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * A character's message colours for the current theme, derived from its one base colour:
 * keep the hue, set the lightness for the theme, then adjust until the text contrast is
 * readable (WCAG 4.5:1 or better).
 */
data class BubbleColours(val background: Color, val text: Color, val name: Color)

object CharacterColours {
    private val darkText = Color(0xFF1B1B1F).toArgb()
    private val lightText = Color(0xFFE4E1E6).toArgb()

    fun forTheme(base: Int, dark: Boolean): BubbleColours {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(base, hsl)
        val sat = hsl[1].coerceAtMost(0.65f)
        return if (!dark) {
            val bg = adjust(hsl[0], sat, 0.88f, darkText, step = 0.01f)
            val name = adjust(hsl[0], sat, 0.35f, bg, step = -0.02f)
            BubbleColours(Color(bg), Color(darkText), Color(name))
        } else {
            val bg = adjust(hsl[0], sat * 0.8f, 0.24f, lightText, step = -0.01f)
            val name = adjust(hsl[0], sat, 0.75f, bg, step = 0.02f)
            BubbleColours(Color(bg), Color(lightText), Color(name))
        }
    }

    /** Move lightness by [step] from [start] until [against] is readable on it. */
    private fun adjust(h: Float, s: Float, start: Float, against: Int, step: Float): Int {
        var l = start
        var c = ColorUtils.HSLToColor(floatArrayOf(h, s, l))
        var guard = 0
        while (ColorUtils.calculateContrast(against, c or 0xFF000000.toInt()) < 4.5 && guard++ < 100) {
            l = (l + step).coerceIn(0f, 1f)
            c = ColorUtils.HSLToColor(floatArrayOf(h, s, l))
            if (l == 0f || l == 1f) break
        }
        return c
    }
}

@Composable
fun bubbleColours(base: Int): BubbleColours = CharacterColours.forTheme(base, isSystemInDarkTheme())
