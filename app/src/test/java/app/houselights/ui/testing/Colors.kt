package app.houselights.ui.testing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** [color] as a resource writes it: #AARRGGBB. */
fun argb(color: Color) = "#%08X".format(color.toArgb())

/** A colour resource's [value] (#RRGGBB or #AARRGGBB) as #AARRGGBB: opaque when no alpha is written. */
fun resourceArgb(value: String) = "#" + value.trim().removePrefix("#").uppercase().padStart(ARGB_DIGITS, 'F')

/** WCAG contrast ratio of two opaque colours. */
fun contrast(a: Color, b: Color): Float {
    val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (light + 0.05f) / (dark + 0.05f)
}

private const val ARGB_DIGITS = 8
