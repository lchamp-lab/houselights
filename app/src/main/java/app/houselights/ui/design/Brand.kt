package app.houselights.ui.design

import androidx.compose.ui.geometry.Rect

/**
 * Gradation, the mark of Houselights: three bars of ember whose widths halve, 4:2:1, as the house
 * lights go down one stop at a time, on one gap, as wide together as they are tall. The fourth step
 * is the black, where the name appears ([app.houselights.ui.design.components.Wordmark]).
 *
 * The launcher icon, the TV banner and the launch screen draw it from their vector resources, which
 * keep to this geometry (`BrandTest`); the launch screen's cue puts its bars out one by one
 * ([LaunchCue]), drawn from it.
 */
object Gradation {
    /** The widths of the bars, in units of the thinnest: the light halves at every step. */
    val widths = listOf(4f, 2f, 1f)

    /**
     * The gaps after the first bar and after the second: one gap for the whole sequence, the first
     * opened by 4 % and the second closed by as much, as a gap beside the wide bar reads narrower.
     */
    val gaps = listOf(1.04f, 0.96f)

    /** The mark's width in units of the thinnest bar: 9, as wide as it is tall. */
    val units: Float = widths.sum() + gaps.sum()

    /** How far right of the middle the mark sits, as a share of its height: its heavy side pulls the eye left. */
    const val PULL = 0.5f / 46f

    /** An adaptive icon's layer: 108 units, its safe circle 66 across. */
    const val LAYER = 108f
    const val SAFE_CIRCLE = 66f

    /** The launcher icon's mark: 46 units tall, its corners inside the safe circle. */
    const val ICON = 46f

    /**
     * The launch screen's mark (`splash_mark`): Android 9 lays the layer out 288 dp wide and shows a
     * circle of 72 units (192 dp) of it, as a round launcher shows an icon; the mark is half that
     * circle tall, 96 dp, its corners well inside it.
     */
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
