package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.rememberCueStep

/** The last house lights step: black over everything once the room has faded ([HouseLightCue]). */
@Composable
fun HouseLights(modifier: Modifier = Modifier) {
    // Reads only its own step, so the layer updates while the black comes in, not for the whole cue.
    val darkness = rememberCueStep(HouseLightCue::darkness)
    Box(modifier.fillMaxSize().graphicsLayer { alpha = darkness.value }.background(Hl.colors.background))
}
