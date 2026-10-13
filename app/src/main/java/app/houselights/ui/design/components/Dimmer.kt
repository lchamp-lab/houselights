package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion

/** The house lights: the whole room fades to black before the film starts, and back after. */
@Composable
fun HouseLights(down: Boolean) {
    val darkness by animateFloatAsState(
        if (down) 1f else 0f,
        tween(HlMotion.DIM, easing = HlMotion.emphasized),
        label = "houselights",
    )
    if (darkness > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = darkness }.background(Hl.colors.background))
    }
}
