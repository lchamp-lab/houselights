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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/** House lights down, 0 browsing to 1 film; a function read while drawing, so dimming never recomposes. */
val LocalHouseLights = staticCompositionLocalOf<() -> Float> { { 0f } }

/** Lights down: the room fades first as the scrims lift, then black covers everything; lights up reverses it. */
object HouseLightCue {
    /** Opacity of the room's content (text, buttons, rows). */
    fun room(down: Float) = 1f - smooth(0f, ROOM_OUT, down)

    /** Strength of the scrims laid over the picture for legibility. */
    fun scrims(down: Float) = 1f - smooth(SCRIMS_FROM, SCRIMS_OUT, down)

    /** Opacity of the black over everything, the last step. */
    fun darkness(down: Float) = smooth(DARK_FROM, 1f, down)

    /** Where each step starts and ends, 0 browsing to 1 film. */
    private const val ROOM_OUT = 0.3f
    private const val SCRIMS_FROM = 0.05f
    private const val SCRIMS_OUT = 0.4f
    private const val DARK_FROM = 0.58f

    @Suppress("MagicNumber") // Smoothstep, t²(3 − 2t): the curve's own coefficients.
    private fun smooth(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * Cold-start launch: the mark's bars go out widest first, each over [BAR], [NEXT] apart, then the black
 * lifts off home over [LIFT]. [t] runs 0 to 1 over [DURATION], every step easing out ([HlMotion.lightsOut]).
 */
object LaunchCue {
    const val DURATION = HlMotion.SLOW

    val bars = Gradation.widths.size

    /** Opacity of bar [bar], 0 the widest. */
    fun bar(bar: Int, t: Float) = 1f - step(bar * NEXT, bar * NEXT + BAR, t)

    /** Opacity of the black over home. */
    fun darkness(t: Float) = 1f - step(LIFT_FROM, DURATION, t)

    private fun step(fromMs: Int, toMs: Int, t: Float) =
        HlMotion.lightsOut.transform(((t * DURATION - fromMs) / (toMs - fromMs)).coerceIn(0f, 1f))

    private const val BAR = 150

    private const val LIFT = 250

    private const val LIFT_FROM = DURATION - LIFT

    /** Delay between two bars, so that the last is out as the black starts to lift. */
    private val NEXT = (LIFT_FROM - BAR) / (bars - 1)
}

/** The cold-start launch screen: it covers the app until home has drawn a complete first frame ([drawn]). */
interface LaunchScreen {
    /** True while the app is hidden: what appears meanwhile shows at once when it leaves, without fading in. */
    val covers: Boolean

    /** Called once the screen underneath has drawn a complete frame. */
    fun drawn()
}

/** No launch screen: a warm start, a screen opened later, a test. */
private object NoLaunchScreen : LaunchScreen {
    override val covers = false

    override fun drawn() = Unit
}

/** The launch screen over the app, provided by the activity. */
val LocalLaunchScreen = staticCompositionLocalOf<LaunchScreen> { NoLaunchScreen }

/** Tells the covering launch screen, on the first frame drawn once [whole], that this content is complete. */
@Composable
fun Modifier.drawnWhole(whole: Boolean): Modifier {
    val launch = LocalLaunchScreen.current
    return if (!whole || !launch.covers) {
        this
    } else {
        drawWithContent {
            drawContent()
            launch.drawn()
        }
    }
}

/**
 * One [step] of the cue for [lights], as a derived state: each step spans only part of [HlMotion.DIM], so
 * a layer reading it stops redrawing once its step is done.
 */
fun cueStep(lights: () -> Float, step: (Float) -> Float): State<Float> = derivedStateOf { step(lights()) }

/** [cueStep] of [LocalHouseLights]; read it while drawing. */
@Composable
fun rememberCueStep(step: (Float) -> Float): State<Float> {
    val lights = LocalHouseLights.current
    return remember(lights, step) { cueStep(lights, step) }
}

/** Marks content as part of the room: it fades as the house lights go down ([HouseLightCue.room]). */
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

/** A layer at the room's opacity, read through [cueStep] so it updates only while the room fades. */
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
