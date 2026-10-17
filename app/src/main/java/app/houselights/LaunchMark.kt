package app.houselights

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import app.houselights.ui.design.Gradation
import app.houselights.ui.design.LaunchCue
import kotlin.math.roundToInt

/**
 * The launch mark as the launch screen's cue puts it out ([LaunchCue]): the bars of `splash_mark`,
 * [Gradation] [Gradation.LAUNCH] units tall in its 108-unit layer, each at its own opacity, so they go
 * out one by one. It takes the place of the drawable Android drew in the launch screen's icon view,
 * which lays the layer out over the whole view: the bars stay where they were, in [color].
 */
internal class LaunchMarkDrawable(color: Int) : Drawable() {
    private val bars = Gradation.bars(Gradation.LAYER / 2, Gradation.LAYER / 2, Gradation.LAUNCH)
    private val levels = FloatArray(bars.size) { 1f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val rect = RectF()

    /** The bars as the cue has them [t] into it. */
    fun at(t: Float) {
        bars.indices.forEach { levels[it] = LaunchCue.bar(it, t) }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val x = bounds.width() / Gradation.LAYER
        val y = bounds.height() / Gradation.LAYER
        bars.forEachIndexed { i, bar ->
            if (levels[i] <= 0f) return@forEachIndexed
            paint.alpha = (levels[i] * OPAQUE).roundToInt()
            rect.set(bounds.left + bar.left * x, bounds.top + bar.top * y, bounds.left + bar.right * x, bounds.top + bar.bottom * y)
            canvas.drawRect(rect, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val OPAQUE = 255
    }
}
