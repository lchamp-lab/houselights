package app.houselights.ui.design.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativePaint

/**
 * The one way a veil's gradient reaches a picture: dithered, as the GPU dithers the backdrop's, since a dark ramp of a
 * few levels over a couple of hundred pixels shows its steps on the player otherwise. Robolectric's software canvas
 * draws it all the same: the dithering is seen on the player only. One per drawing cache; it holds its paint.
 */
internal class DitheredVeil {
    private val paint = Paint().apply { nativePaint.isDither = true }

    /** [brush] laid over [size] from [topLeft] (the scope's whole by default), its gradient set out over the scope. */
    fun draw(
        scope: DrawScope,
        brush: Brush,
        topLeft: Offset = Offset.Zero,
        size: Size = scope.size,
        alpha: Float = 1f,
        colorFilter: ColorFilter? = null,
    ) = with(scope) {
        brush.applyTo(this.size, paint, alpha)
        paint.colorFilter = colorFilter
        drawIntoCanvas { it.drawRect(Rect(topLeft, size), paint) }
    }
}

/** [brush] over the whole of what it modifies, behind its content, dithered ([DitheredVeil]). */
internal fun Modifier.veil(brush: Brush): Modifier = drawWithCache {
    val veil = DitheredVeil()
    onDrawBehind { veil.draw(this, brush) }
}
