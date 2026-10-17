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
 * lights go down on the mark, bar by bar, and home comes up out of the dark, already whole. One
 * motion of [DURATION], [t] from 0 to 1, every step easing out ([HlMotion.lightsOut]).
 *
 * The bars go out one after the other, the widest first, in the order the mark reads: the light it
 * shows halves at each step, 7, then 3, then 1, then none, a large drop first that settles into
 * the dark, as a light going out does; the thinnest glimmer goes last, and the black, the fourth
 * step, lifts off home, where the screen takes over. Each bar goes out over `FAST`, the next one
 * starting [NEXT] after it; the black lifts over `STANDARD` once the last is out.
 */
object LaunchCue {
    const val DURATION = HlMotion.SLOW

    /** How many bars go out: the mark's, widest first. */
    val bars = Gradation.widths.size

    /** Opacity of bar [bar], 0 the widest. */
    fun bar(bar: Int, t: Float) = 1f - step(bar * NEXT, bar * NEXT + BAR, t)

    /** Opacity of the black over home. */
    fun darkness(t: Float) = 1f - step(LIFT_FROM, DURATION, t)

    /** A step of the cue, from [fromMs] to [toMs] into it, easing out. */
    private fun step(fromMs: Int, toMs: Int, t: Float) =
        HlMotion.lightsOut.transform(((t * DURATION - fromMs) / (toMs - fromMs)).coerceIn(0f, 1f))

    /** How long a bar takes to go out. */
    private const val BAR = HlMotion.FAST

    /** When the black starts to lift off home: what the cue leaves of itself for `STANDARD`. */
    private const val LIFT_FROM = DURATION - HlMotion.STANDARD

    /** How long after a bar the next one starts to go out: the last is out as the black starts to lift. */
    private val NEXT = (LIFT_FROM - BAR) / (bars - 1)
}

/**
 * The launch screen as the screen under it sees it: at a cold start it covers the app, the mark alone
 * on black, while home is composed, laid out and drawn under it, and it leaves once home has drawn a
 * first frame whole ([drawn], `Modifier.drawnWhole`). Nothing covers a screen opened later.
 */
interface LaunchScreen {
    /**
     * True while the launch screen hides the app whole: what shows then shows whole when it leaves,
     * without fading in (a backdrop's first picture, the first light of the room).
     */
    val covers: Boolean

    /** The screen under it drew a frame whole: its content in, its picture prepared. */
    fun drawn()
}

/** No launch screen: a warm start, a screen opened later, a test. */
private object NoLaunchScreen : LaunchScreen {
    override val covers = false

    override fun drawn() = Unit
}

/** The launch screen over the app, provided by the activity. */
val LocalLaunchScreen = staticCompositionLocalOf<LaunchScreen> { NoLaunchScreen }

/**
 * Tells the launch screen covering the app that this content was drawn [whole]: in the first frame
 * drawn once it is, so the launch screen leaves on that frame's heels. Nothing while the content is
 * still being prepared, nor once nothing covers it.
 */
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
