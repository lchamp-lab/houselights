package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.LocalHouseLights

/**
 * The last step of the house lights going down: black over everything, once the room has
 * faded and the screen has shown its picture whole ([HouseLightCue]).
 */
@Composable
fun HouseLights() {
    val lights = LocalHouseLights.current
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = HouseLightCue.darkness(lights()) }.background(Hl.colors.background))
}
