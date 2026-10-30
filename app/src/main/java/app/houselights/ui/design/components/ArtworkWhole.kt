package app.houselights.ui.design.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import coil.decode.DataSource
import coil.request.SuccessResult
import coil.transition.CrossfadeTransition
import kotlinx.coroutines.delay

// When an [ArtworkImage]'s picture covers what it lies on, so that what lies under it need not be drawn.

/**
 * Tells [onWhole] once the picture [url] that came covers what it lies on: with no see-through part, at once from
 * memory, else once the loader's fade has run ([fadeMillis]), counted from the frame after it came, the first it is
 * drawn in. Never for a picture with see-through parts. What it returns is told what came.
 */
@Composable
internal fun rememberWhole(url: String, onWhole: () -> Unit): (SuccessResult) -> Unit {
    val told by rememberUpdatedState(onWhole)
    var came by remember(url) { mutableStateOf<SuccessResult?>(null) }
    LaunchedEffect(came) {
        val result = came?.takeIf { it.isOpaque() } ?: return@LaunchedEffect
        val fade = result.fadeMillis()
        if (fade > 0L) {
            withFrameNanos {}
            delay(fade)
        }
        told()
    }
    return remember(url) { { result: SuccessResult -> came = result } }
}

/** Whether a picture has no see-through part (a JPEG's): drawn whole, it covers what it lies on. */
internal fun SuccessResult.isOpaque(): Boolean = (drawable as? BitmapDrawable)?.bitmap?.hasAlpha() == false

/** How long the loader fades a picture in (ms), as Coil's crossfade does: not at all from memory. */
internal fun SuccessResult.fadeMillis(): Long = when {
    dataSource == DataSource.MEMORY_CACHE -> 0L
    else -> (request.transitionFactory as? CrossfadeTransition.Factory)?.durationMillis?.toLong() ?: 0L
}
