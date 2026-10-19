package app.houselights

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.withTranslation
import app.houselights.ui.design.Gradation
import app.houselights.ui.design.HlColors
import app.houselights.ui.design.LaunchCue
import app.houselights.ui.design.drawGradation
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * The bars of `splash_mark` in its 108-unit layer, in the mark's light, each with its own opacity so [LaunchCue]
 * puts them out one by one. It replaces the splash icon view's drawable, which spans the whole view, so the bars
 * keep their place.
 */
internal class LaunchMarkDrawable(colors: HlColors) : Drawable() {
    private val bars = Gradation.bars(Gradation.LAYER / 2, Gradation.LAYER / 2, Gradation.LAUNCH)
    private val light = Gradation.light(bars, colors)
    private val levels = FloatArray(bars.size) { 1f }
    private val scope = CanvasDrawScope()

    /** Sets each bar's opacity to the cue's at [t], from 0 to 1. */
    fun at(t: Float) {
        bars.indices.forEach { levels[it] = LaunchCue.bar(it, t) }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        canvas.withTranslation(bounds.left.toFloat(), bounds.top.toFloat()) {
            scope.draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(this), Size(width, height)) {
                scale(width / Gradation.LAYER, height / Gradation.LAYER, Offset.Zero) {
                    drawGradation(bars, light) { levels[it] }
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
