package app.houselights.ui.design

import androidx.compose.ui.geometry.Rect

/**
 * Gradation, the mark (DESIGN.md, Brand): three ember bars halving in width (4:2:1), together as wide as
 * tall. The launcher icon, banner and splash vectors must match this geometry (`BrandTest`).
 */
object Gradation {
    /** The bars' widths, in units of the thinnest. */
    val widths = listOf(4f, 2f, 1f)

    /** Gaps after the first and second bars, ±4 %: a gap beside the wide bar reads narrower. */
    val gaps = listOf(1.04f, 0.96f)

    /** The mark's width in units of the thinnest bar: 9, as wide as it is tall. */
    val units: Float = widths.sum() + gaps.sum()

    /** How far right of centre the mark sits, as a share of its height: its heavy side pulls the eye left. */
    const val PULL = 0.5f / 46f

    /** An adaptive icon's layer: 108 units, its safe circle 66 across. */
    const val LAYER = 108f
    const val SAFE_CIRCLE = 66f

    /** The launcher icon's mark: 46 units tall, its corners inside the safe circle. */
    const val ICON = 46f

    /** Splash mark: Android 9 lays the layer 288 dp wide, shows a 72-unit (192 dp) circle; the mark is 96 dp. */
    const val LAUNCH = 36f
    const val LAUNCH_CIRCLE = 72f

    /** The bars, widest first, of a mark [height] tall centred on ([x], [y]), pulled right by [PULL]. */
    fun bars(x: Float, y: Float, height: Float): List<Rect> {
        val unit = height / units
        var left = x - height / 2 + PULL * height
        val top = y - height / 2
        return widths.mapIndexed { i, width ->
            Rect(left, top, left + width * unit, top + height).also { left = it.right + (gaps.getOrNull(i) ?: 0f) * unit }
        }
    }
}
