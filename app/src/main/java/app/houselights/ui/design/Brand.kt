package app.houselights.ui.design

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.round

/**
 * Gradation, the mark (DESIGN.md, Brand): three bars halving in width (4:2:1), together as wide as tall, in
 * one ember gradient ([light]). The launcher icon, banner and splash vectors must match it (`BrandTest`).
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

    /** The lockup's mark, in the name's capitals: it passes them by a fifth above and below, centred on them. */
    const val LOCKUP_MARK = 1.4f

    /** From the lockup's mark to its name, in capitals: the wide bar's width. */
    val lockupGap: Float = LOCKUP_MARK * widths.first() / units

    /** Where the lockup's name starts, in capitals. */
    val lockupIndent: Float = LOCKUP_MARK + lockupGap

    /** The bars, widest first, of a mark [height] tall centred on ([x], [y]), pulled right by [pull] of it. */
    fun bars(x: Float, y: Float, height: Float, pull: Float = PULL): List<Rect> {
        val unit = height / units
        var left = x - height / 2 + pull * height
        val top = y - height / 2
        return widths.mapIndexed { i, width ->
            Rect(left, top, left + width * unit, top + height).also { left = it.right + (gaps.getOrNull(i) ?: 0f) * unit }
        }
    }

    /**
     * The lockup's bars from x = 0, centred on [capitals] whose middle is at [y]; beside a name, they take no pull. On
     * screen ([whole], px), each edge falls on a whole pixel, so a small lockup's thin bar keeps sharp sides.
     */
    fun lockupBars(capitals: Float, y: Float, whole: Boolean = false): List<Rect> {
        val height = capitals * LOCKUP_MARK
        val bars = bars(height / 2, y, height, pull = 0f)
        return if (whole) bars.map { Rect(round(it.left), round(it.top), round(it.right), round(it.bottom)) } else bars
    }

    /** The mark's light: `emberFrom` at the left edge of [bars] to `emberTo` at their right, shared by every bar. */
    fun light(bars: List<Rect>, colors: HlColors): Brush =
        Brush.horizontalGradient(listOf(colors.emberFrom, colors.emberTo), startX = bars.first().left, endX = bars.last().right)

    /** The [light] once per bar: a brush keeps its shader for one size only, so each bar keeps its own. */
    fun lights(bars: List<Rect>, colors: HlColors): List<Brush> = bars.map { light(bars, colors) }
}

/** Draws [bars] in one light across them ([Gradation.lights]), each bar at its own opacity ([level] of its index, 1 lit). */
fun DrawScope.drawGradation(bars: List<Rect>, lights: List<Brush>, level: (Int) -> Float = { 1f }) {
    bars.forEachIndexed { i, bar ->
        val alpha = level(i)
        if (alpha > 0f) drawRect(lights[i], bar.topLeft, bar.size, alpha)
    }
}
