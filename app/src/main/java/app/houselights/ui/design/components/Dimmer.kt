package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.LocalRoomLight

/**
 * The last step of the house lights going down: black over everything, once the room has
 * faded and the screen has shown its picture whole ([HouseLightCue]).
 */
@Composable
fun HouseLights() {
    val lights = LocalHouseLights.current
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = HouseLightCue.darkness(lights()) }.background(Hl.colors.background))
}

/**
 * The house lights themselves: sconces washing the side walls of the room with warm light.
 * Their strength follows [LocalRoomLight]: up while browsing, down near a film, off for it.
 */
@Composable
fun WallLights(modifier: Modifier = Modifier) {
    val room = LocalRoomLight.current
    val tungsten = Hl.colors.tungsten
    Box(
        modifier.fillMaxSize().drawBehind {
            val light = room()
            if (light <= 0.01f) return@drawBehind
            val radius = size.height * 0.62f
            for (x in floatArrayOf(0f, size.width)) {
                val center = Offset(x, size.height * 0.24f)
                // A sconce throws a tall, narrow wash up and down the wall.
                scale(scaleX = 0.42f, scaleY = 1f, pivot = center) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to tungsten.copy(alpha = 0.34f * light),
                            0.5f to tungsten.copy(alpha = 0.11f * light),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                        // Light adds to what is there: it warms the picture, never greys it.
                        blendMode = BlendMode.Screen,
                    )
                }
            }
        }
    )
}
