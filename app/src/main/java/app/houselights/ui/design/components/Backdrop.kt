package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.Animatable
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.design.drawnWhole
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize
import app.houselights.core.ImageRef
import app.houselights.ui.design.BackdropDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.rememberCueStep
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.colorStops
import app.houselights.ui.util.screenWidthPx
import coil.request.ImageRequest
import kotlin.math.max
import kotlinx.coroutines.delay

enum class BackdropStyle(
    /** Opacity of the picture itself. */
    val imageAlpha: Float,
    /** Where the bottom fade starts, and where it is done (shares of the height). */
    val fadeFrom: Float,
    val fadeTo: Float,
    /** Strength of the left veil behind text, at the screen's edge, before the picture asks for more. */
    val leftScrim: Float,
    /** Widest file worth asking for; null: as wide as the screen. */
    val maxWidthPx: Int? = null,
) {
    /** Home: the picture owns the top of the screen, rows sit on black below. */
    Hero(1f, 0.22f, 0.68f, 0.85f),
    /** Detail: the picture fills the screen behind the text, and melts into the black of its rows. */
    Full(1f, 0.45f, 1f, 0.85f),
    /**
     * Library (the wall of posters), settings: a quiet reminder of the picture behind content, down
     * to the foot of the screen; dimmed this much, a lighter file does.
     */
    Quiet(0.28f, 0.2f, 0.75f, 0.6f, maxWidthPx = 1280),
    ;

    /**
     * How dark the bottom fade ends. A picture shown whole melts into black. A dimmed one is already
     * quiet: it only takes what the quietest text needs over the lightest picture at its dimness, so
     * it stays in the room down to the foot of the screen instead of leaving it black.
     */
    val foot: Float = if (imageAlpha < 1f) HlScrim.need(imageAlpha) else 1f

    /** Its veils down the screen: the top veil, then its bottom fade. */
    val down: List<VeilStop> = HlScrim.down(fadeFrom, fadeTo, foot)
}

/**
 * Full-screen picture on pure black, veiled where text sits. The veils are [HlScrim]'s; under the
 * text column, the left veil takes, band by band, what the picture needs, so the quietest text reads
 * on the lightest image while a dark one keeps its veil. Nothing glows under it: the picture melts
 * into the room. When the house lights go down, the veils lift and the picture shows whole. Without
 * a picture ([image] null) it is the black room: nothing glows in its place. A page whose picture
 * steps back as it scrolls (a detail page) says how far through [recede], 0 in front, 1 behind; only
 * then is the veil the whole picture needs reckoned.
 *
 * A picture and its veils are drawn once, into a layer of their own, and each frame then
 * lays that one texture: on the player's small graphics chip, drawn layer by layer each frame, they
 * filled its whole budget. One picture gives way to the next by the alpha of those layers; the
 * first one fades in too, unless the launch screen still hides it and it was in memory
 * ([showsAtOnce]), or a screen left hands its picture on ([BackdropRelay]).
 */
@Composable
fun Backdrop(image: ImageRef?, style: BackdropStyle, modifier: Modifier = Modifier, recede: (() -> Float)? = null) =
    BackdropOf(rememberBackdropState(), image, style, modifier, recede)

/** A backdrop's state, starting from the picture of the screen left when there is a [BackdropRelay]. */
@Composable
private fun rememberBackdropState(): BackdropState {
    val relay = LocalBackdropRelay.current
    return remember { BackdropState().also { it.startFrom(relay?.claim(it)) } }
}

/** A [Backdrop] showing what [state] holds, which says when it has prepared the picture of [image]. */
@Composable
private fun BackdropOf(state: BackdropState, image: ImageRef?, style: BackdropStyle, modifier: Modifier, recede: (() -> Float)?) {
    val context = LocalContext.current
    val launch = LocalLaunchScreen.current
    val screen = screenWidthPx()
    val url = image?.let { style.urlOf(it, screen) }
    LaunchedEffect(url) {
        // A screen arriving may say its picture a moment later (its title is read): the picture
        // handed on waits for it, rather than going out and coming back.
        if (url == null && state.shown?.handedOn == true) delay(HlMotion.HOLD)
        val prepared = if (image == null || url == null) null else prepareBackdrop(context, image, url, screen, backdropHeight(screen))
        state.show(image, prepared?.let { Shot(it, style, url) }, covered = launch.covers)
    }
    // What shows is handed on to the next screen's backdrop, taken back if this one leaves last.
    val relay = LocalBackdropRelay.current
    val shown = state.shown
    SideEffect { relay?.shows(state, shown) }
    DisposableEffect(relay) { onDispose { relay?.release(state) } }
    BackdropShots(shown, modifier, recede, holds = { leaving, coming -> leaving?.handedOn == true && coming != null })
}

/**
 * The pictures of a backdrop, fading one into the next ([FadeBetween]); a shot that [holds] stays
 * whole under the next until it is in, so a picture handed on never dims on its way. [level]: how
 * far the whole backdrop is lit.
 */
@Composable
private fun BackdropShots(
    shown: Shot?,
    modifier: Modifier,
    recede: (() -> Float)?,
    holds: (leaving: Shot?, coming: Shot?) -> Boolean = { _, _ -> false },
    level: () -> Float = { 1f },
) {
    val density = LocalDensity.current
    // The text column of heroes and detail pages; the veil follows it, whatever the screen's width.
    val column = with(density) { (HlSpace.safeH + HlLayout.readableNarrow).toPx() }
    val fade = with(density) { BackdropDefaults.veilFade.toPx() }
    var bounds by remember { mutableStateOf(Size.Zero) }
    val sized = modifier.fillMaxSize().onSizeChanged { bounds = it.toSize() }
    FadeBetween(shown, sized, atOnce = { it?.atOnce == true }, holds = holds) { shot, alpha ->
        if (shot != null) BackdropPicture(shot.prepared, shot.style, bounds, column, fade, { alpha() * level() }, recede)
    }
}

/**
 * A picture as a backdrop shows it: [prepared] from [url], in [style]. [atOnce]: whole from its
 * first frame. [handedOn]: the picture of the screen left, the one an arriving backdrop starts from.
 */
@Immutable
internal class Shot(
    val prepared: PreparedBackdrop,
    val style: BackdropStyle,
    val url: String?,
    val atOnce: Boolean = false,
    val handedOn: Boolean = false,
) {
    /** It continues [shot]: the same picture in the same style, which a change would only redraw. */
    fun continues(shot: Shot?) = shot != null && url == shot.url && style == shot.style

    fun shownAtOnce() = Shot(prepared, style, url, atOnce = true, handedOn = handedOn)

    /** This picture as the next screen's backdrop starts from it: whole, then giving way. */
    fun handedOn() = Shot(prepared, style, url, atOnce = true, handedOn = true)
}

/**
 * What a [Backdrop] shows, and the picture it has settled on: the next picture is prepared before it
 * shows, and until then the last one stays.
 */
@Stable
internal class BackdropState {
    var shown by mutableStateOf<Shot?>(null)
        private set

    /** The image it last finished preparing (null: none to show), or [NOTHING_YET]. */
    private var preparedFor by mutableStateOf<Any?>(NOTHING_YET)

    private var shownOnce = false

    /** Shows first [shot], the picture of the screen left, handed on. */
    fun startFrom(shot: Shot?) {
        if (shot == null) return
        shown = shot
        shownOnce = true
    }

    /** It has prepared the picture of [image], or found it has none to show: what shows is what was asked for. */
    fun settledOn(image: ImageRef?): Boolean = preparedFor == image

    /**
     * Shows [shot], the picture of [image] (null: none, or it could not be loaded), at once when
     * [showsAtOnce] says so, or when it continues the picture handed on.
     */
    fun show(image: ImageRef?, shot: Shot?, covered: Boolean) {
        val first = !shownOnce
        val atOnce = shot != null &&
            (showsAtOnce(first = first, inMemory = shot.prepared.fromMemory, covered = covered) || shot.continues(shown))
        shown = if (atOnce) shot?.shownAtOnce() else shot
        if (shot != null) shownOnce = true
        preparedFor = image
    }

    private companion object {
        val NOTHING_YET = Any()
    }
}

/**
 * The picture a screen's backdrop shows, handed on to the next screen's across a cut (screens never
 * fade into one another): the arriving backdrop starts from it, whole, and only its veils or its
 * picture change, home's band opening onto a detail page, a wall's dimmed picture coming up to full
 * light. A screen without a backdrop lets it go out on the black room ([LeavingBackdrop]). Two
 * layers at most, as for any change of picture.
 */
@Stable
class BackdropRelay {
    private var holder: Any? = null
    private var last: Shot? = null

    /** The picture of the screen left, going out on the black room until [gone]. */
    internal var leaving by mutableStateOf<Shot?>(null)
        private set

    /** A backdrop arriving: it holds the relay, and starts from the picture of the screen left. */
    internal fun claim(owner: Any): Shot? {
        holder = owner
        return last?.handedOn()
    }

    internal fun shows(owner: Any, shot: Shot?) {
        if (holder === owner) last = shot
    }

    /** A backdrop leaving: when no other took the relay, its picture goes out. */
    internal fun release(owner: Any) {
        if (holder !== owner) return
        holder = null
        leaving = last
        last = null
    }

    internal fun gone(shot: Shot) {
        if (leaving === shot) leaving = null
    }
}

/**
 * Whether the backdrop of the screen has come to the picture of an image, or found it has none:
 * read while it is waited for, what is said of a title waits for its picture ([WithItsPicture]).
 * Without a backdrop, always.
 */
val LocalBackdropSettled = staticCompositionLocalOf<(ImageRef?) -> Boolean> { { true } }

/** The app's [BackdropRelay]; none (a backdrop on its own, as in the catalogue) starts from nothing. */
val LocalBackdropRelay = staticCompositionLocalOf<BackdropRelay?> { null }

/**
 * Under every screen: the picture of a screen left for one without a backdrop, going out on the
 * black room over `SLOW`, as the lights go out.
 */
@Composable
fun LeavingBackdrop(relay: BackdropRelay) {
    val leaving = relay.leaving ?: return
    val out = remember(leaving) { Animatable(1f) }
    LaunchedEffect(leaving) {
        out.animateTo(0f, tween(HlMotion.SLOW, easing = HlMotion.lightsOut))
        relay.gone(leaving)
    }
    // The fade is the layer's own alpha: the picture is not drawn again for it.
    BackdropShots(leaving, Modifier, recede = null, level = { out.value })
}

/**
 * Whether a picture about to show in a backdrop shows at once, without fading in: the [first] it
 * shows, [inMemory] when it was asked for (loaded ahead, while the launch screen held), while the
 * launch screen still hides the screen ([covered]), whose cue then reveals it whole. Every other
 * picture fades in, the first ones included.
 */
internal fun showsAtOnce(first: Boolean, inMemory: Boolean, covered: Boolean): Boolean = first && inMemory && covered

/**
 * A screen on its picture: the [Backdrop] of [image] under [content], which the picture lights
 * ([rememberAmbientColor], provided as [LocalAmbient]): the card that has focus glows with it. The
 * light changes over `SLOW` with the picture, read while drawing, so nothing recomposes for it.
 * [ready]: the content is in (home's rows); a launch screen covering the screen leaves once a frame
 * is drawn with it and the picture prepared ([drawnWhole]).
 */
@Composable
fun AmbientBackdrop(
    image: ImageRef?,
    style: BackdropStyle,
    modifier: Modifier = Modifier,
    recede: (() -> Float)? = null,
    ready: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val ambient = rememberAmbientColor(image)
    val backdrop = rememberBackdropState()
    val settled = remember(backdrop) { { shown: ImageRef? -> backdrop.settledOn(shown) } }
    CompositionLocalProvider(LocalAmbient provides { ambient.value }, LocalBackdropSettled provides settled) {
        Box(modifier.fillMaxSize().drawnWhole(ready && backdrop.settledOn(image))) {
            BackdropOf(backdrop, image, style, Modifier, recede)
            content()
        }
    }
}

/** The shape a backdrop is asked for: the screen's. */
private const val BACKDROP_WIDTH = 16
private const val BACKDROP_HEIGHT = 9

/** The address of [image] as a backdrop in this style, on a screen [screenPx] wide. */
private fun BackdropStyle.urlOf(image: ImageRef, screenPx: Int): String = image.url(maxWidthPx?.coerceAtMost(screenPx) ?: screenPx)

/** The height a backdrop is asked for on a screen [screenPx] wide. */
private fun backdropHeight(screenPx: Int) = screenPx * BACKDROP_HEIGHT / BACKDROP_WIDTH

/**
 * What a [Backdrop] of [image] in [style] asks Coil for on a screen [screenPx] wide, as it prepares
 * the picture: the picture itself, then the tiny copy its veil and the room's light are read from.
 */
internal fun backdropRequests(context: Context, image: ImageRef, style: BackdropStyle, screenPx: Int): List<ImageRequest> = listOf(
    backdropRequest(context, style.urlOf(image, screenPx), screenPx, backdropHeight(screenPx)),
    backdropSampleRequest(context, image),
)

/**
 * One picture of a [Backdrop], with its veils, as one cached layer at [alpha]. All of
 * it is ready before it shows, so the layer is drawn once; only the house lights draw it again.
 */
@Composable
private fun BackdropPicture(
    prepared: PreparedBackdrop,
    style: BackdropStyle,
    bounds: Size,
    column: Float,
    fade: Float,
    alpha: () -> Float,
    recede: (() -> Float)?,
) {
    val black = Hl.colors.background
    // The veils lift as the house lights go down: read while drawing, through the step alone, so
    // the layer is drawn again only while they lift, not for the whole cue.
    val scrims = rememberCueStep(HouseLightCue::scrims)
    val veil = remember(prepared, bounds, style) {
        prepared.sample.leftVeil(bounds, column, style.imageAlpha, style.leftScrim, style.down)
    }
    // Stepping back, the picture takes the veil text needs over all of it, never less than VEIL.
    val receded = remember(prepared, bounds, style, recede != null) {
        if (recede == null) 0f else max(HlAlpha.VEIL, prepared.sample.floorAcross(bounds, style.imageAlpha, style.down))
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                // Stepping back is black laid over the picture; on the black room that is the
                // layer's own alpha, which costs nothing, where a veil would draw the layer again.
                this.alpha = alpha() * (1f - (recede?.invoke() ?: 0f) * receded * scrims.value)
            }
            .background(black),
    ) {
        Image(
            prepared.picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd,
            alpha = style.imageAlpha,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().drawWithCache {
                // Laid out once for the picture: the house lights only change how strongly they draw.
                val across = leftVeil(black, veil, column, fade)
                val down = Brush.verticalGradient(*style.down.colorStops(black))
                onDrawBehind {
                    val lift = scrims.value
                    drawRect(across, alpha = lift)
                    drawRect(down, alpha = lift)
                }
            }
        )
    }
}

/**
 * [Crossfade], with the alpha handed to the content instead of laid on a layer of its own: a
 * content that is a cached layer takes it as that layer's alpha, and fades for the cost of one
 * texture rather than two. A target that shows [atOnce] is whole from its first frame, and what it
 * replaces goes at once. One that [holds] given what comes stays whole under it until it is in: the
 * two then mix as one, never darker between.
 */
@Composable
internal fun <T> FadeBetween(
    target: T,
    modifier: Modifier,
    atOnce: (T) -> Boolean = { false },
    holds: (leaving: T, coming: T) -> Boolean = { _, _ -> false },
    content: @Composable (T, alpha: () -> Float) -> Unit,
) {
    val transition = updateTransition(target, label = "backdrop")
    val shown = remember { mutableStateListOf(transition.currentState) }
    if (transition.currentState == transition.targetState && (shown.size != 1 || shown[0] != transition.targetState)) {
        shown.removeAll { it != transition.targetState }
    }
    if (transition.targetState !in shown) shown.add(transition.targetState)
    Box(modifier) {
        shown.forEach { state ->
            key(state) {
                val alpha = transition.animateFloat(
                    transitionSpec = { if (atOnce(targetState)) snap() else tween(HlMotion.SLOW, easing = HlMotion.standard) },
                    label = "backdropAlpha",
                ) { if (it == state) 1f else 0f }
                // Whole from the first frame that draws it, before the transition takes its first step.
                content(state) {
                    when {
                        state == transition.targetState && atOnce(state) -> 1f
                        state != transition.targetState && holds(state, transition.targetState) -> 1f
                        else -> alpha.value
                    }
                }
            }
        }
    }
}

/**
 * The left veil: [veil] along the [column] (px), its stops as shares of it, then fading out at one
 * pace whatever its strength, over [fade] px for a full one: a light veil is gone sooner, a dark one
 * takes the room it needs to fade without an edge.
 */
private fun leftVeil(black: Color, veil: List<VeilStop>, column: Float, fade: Float): Brush {
    val end = column + fade * veil.last().alpha
    val stops = veil.map { VeilStop(it.at * column / end, it.alpha) } + VeilStop(1f, 0f)
    return Brush.horizontalGradient(*stops.colorStops(black), startX = 0f, endX = end)
}
