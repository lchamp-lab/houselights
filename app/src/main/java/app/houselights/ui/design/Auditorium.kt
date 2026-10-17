package app.houselights.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/**
 * How far the house lights are down: 0 while browsing, 1 when the film starts. A function, so
 * that layers read it while drawing and the dimming never recomposes the screens.
 */
val LocalHouseLights = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Lights down, in order, as in a theatre: the room goes first (text and controls), while the
 * screen stays lit and its scrims lift so the picture shows whole; then black. Lights up runs
 * the same steps backwards.
 */
object HouseLightCue {
    /** Opacity of the room's content (text, buttons, rows). */
    fun room(down: Float) = 1f - smooth(0f, 0.3f, down)

    /** Strength of the scrims laid over the picture for legibility. */
    fun scrims(down: Float) = 1f - smooth(0.05f, 0.4f, down)

    /** Black over everything, last. */
    fun darkness(down: Float) = smooth(0.58f, 1f, down)

    private fun smooth(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * The launch, on a cold start: the mark alone in the room while home gets ready under it, then the
 * lights go down on the mark and home comes up out of the dark, already whole. One motion of
 * [DURATION], [t] from 0 to 1, in two steps that ease out ([HlMotion.lightsOut]): the mark goes
 * first, the black lifts once it is all but out.
 */
object LaunchCue {
    const val DURATION = HlMotion.SLOW

    /** Opacity of the mark. */
    fun mark(t: Float) = 1f - step(0f, MARK_OUT, t)

    /** Opacity of the black over home. */
    fun darkness(t: Float) = 1f - step(LIFT_FROM, 1f, t)

    private fun step(from: Float, to: Float, t: Float) = HlMotion.lightsOut.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

    /** Where in the cue the mark is out. */
    private const val MARK_OUT = 0.6f

    /** Where in the cue the black starts to lift off home. */
    private const val LIFT_FROM = 0.4f
}

/**
 * One [step] of the cue ([HouseLightCue.room], [HouseLightCue.scrims], [HouseLightCue.darkness])
 * as the [lights] go: a derived state, which tells its readers only when its value changes. The
 * lights move on every frame of [HlMotion.DIM], each step over part of it only: a layer or a
 * drawing reading its step is done once the step is, instead of being drawn again until the cue ends.
 */
fun cueStep(lights: () -> Float, step: (Float) -> Float): State<Float> = derivedStateOf { step(lights()) }

/** [cueStep] of the house lights around: read it while drawing. */
@Composable
fun rememberCueStep(step: (Float) -> Float): State<Float> {
    val lights = LocalHouseLights.current
    return remember(lights, step) { cueStep(lights, step) }
}

/** Content of the room: it fades when the house lights go down. */
fun Modifier.houseLit(lights: () -> Float): Modifier = this then HouseLitElement(lights)

private data class HouseLitElement(val lights: () -> Float) : ModifierNodeElement<HouseLitNode>() {
    override fun create() = HouseLitNode(lights)
    override fun update(node: HouseLitNode) {
        node.lights = lights
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "houseLit"
    }
}

/**
 * A layer at the room's opacity, as `graphicsLayer { }` lays it, through the room's [cueStep]: the
 * layer is updated while the room fades, and left alone for the rest of the cue.
 */
private class HouseLitNode(lights: () -> Float) : Modifier.Node(), LayoutModifierNode {
    var lights by mutableStateOf(lights)
    private val room = cueStep({ this.lights() }, HouseLightCue::room)
    private val layer: GraphicsLayerScope.() -> Unit = { alpha = room.value }

    override val shouldAutoInvalidate = false

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }
}
