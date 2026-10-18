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
 * The bars of `splash_mark` in its 108-unit layer, each with its own opacity so [LaunchCue] puts them out one
 * by one. It replaces the splash icon view's drawable, which spans the whole view, so the bars keep their place.
 */
internal class LaunchMarkDrawable(color: Int) : Drawable() {
    private val bars = Gradation.bars(Gradation.LAYER / 2, Gradation.LAYER / 2, Gradation.LAUNCH)
    private val levels = FloatArray(bars.size) { 1f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val rect = RectF()

    /** Sets each bar's opacity to the cue's at [t], from 0 to 1. */
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
