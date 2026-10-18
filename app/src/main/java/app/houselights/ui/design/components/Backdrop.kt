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
    /** Where the bottom fade starts and ends, as fractions of the height. */
    val fadeFrom: Float,
    val fadeTo: Float,
    /** Base left-veil strength at the screen edge, raised where the picture needs more. */
    val leftScrim: Float,
    /** Widest image to request; null requests the screen width. */
    val maxWidthPx: Int? = null,
) {
    /** Home: the picture fills the top of the screen; rows sit on black below. */
    Hero(1f, 0.22f, 0.68f, 0.85f),
    /** Detail: the picture fills the screen behind the text and fades to black under its rows. */
    Full(1f, 0.45f, 1f, 0.85f),
    /** Library wall and settings: a dimmed picture down to the bottom; a smaller file suffices. */
    Quiet(0.28f, 0.2f, 0.75f, 0.6f, maxWidthPx = 1280),
    ;

    /** End opacity of the bottom fade: full under an opaque picture, only what text needs under a dimmed one. */
    val foot: Float = if (imageAlpha < 1f) HlScrim.need(imageAlpha) else 1f

    /** The vertical veil stops: the top veil, then the bottom fade. */
    val down: List<VeilStop> = HlScrim.down(fadeFrom, fadeTo, foot)
}

/**
 * A full-screen picture on black, veiled where text sits (DESIGN.md, Veils); [recede] (0 to 1) dims it as
 * a detail page scrolls. Each picture and its veils render once into a cached layer, since redrawing them
 * every frame exceeds the player's GPU budget; pictures crossfade unless [showsAtOnce].
 */
@Composable
fun Backdrop(image: ImageRef?, style: BackdropStyle, modifier: Modifier = Modifier, recede: (() -> Float)? = null) =
    BackdropOf(rememberBackdropState(), image, style, modifier, recede)

/** A backdrop's state, starting from the previous screen's picture when a [BackdropRelay] hands one on. */
@Composable
private fun rememberBackdropState(): BackdropState {
    val relay = LocalBackdropRelay.current
    return remember { BackdropState().also { it.startFrom(relay?.claim(it)) } }
}

/** A [Backdrop] showing [state], which reports when the picture of [image] is prepared. */
@Composable
private fun BackdropOf(state: BackdropState, image: ImageRef?, style: BackdropStyle, modifier: Modifier, recede: (() -> Float)?) {
    val context = LocalContext.current
    val launch = LocalLaunchScreen.current
    val screen = screenWidthPx()
    val url = image?.let { style.urlOf(it, screen) }
    LaunchedEffect(url) {
        // An arriving screen may set its picture a moment later: a handed-on picture waits for it.
        if (url == null && state.shown?.handedOn == true) delay(HlMotion.HOLD)
        val prepared = if (image == null || url == null) null else prepareBackdrop(context, image, url, screen, backdropHeight(screen))
        state.show(image, prepared?.let { Shot(it, style, url) }, covered = launch.covers)
    }
    // Offer the shown picture to the next screen's backdrop; if none claims it, it fades out on release.
    val relay = LocalBackdropRelay.current
    val shown = state.shown
    SideEffect { relay?.shows(state, shown) }
    DisposableEffect(relay) { onDispose { relay?.release(state) } }
    BackdropShots(shown, modifier, recede, holds = { leaving, coming -> leaving?.handedOn == true && coming != null })
}

/** A backdrop's pictures, crossfading ([FadeBetween]); [level] scales the whole backdrop's opacity. */
@Composable
private fun BackdropShots(
    shown: Shot?,
    modifier: Modifier,
    recede: (() -> Float)?,
    holds: (leaving: Shot?, coming: Shot?) -> Boolean = { _, _ -> false },
    level: () -> Float = { 1f },
) {
    val density = LocalDensity.current
    // The text column of heroes and detail pages, which the veil follows at any screen width.
    val column = with(density) { (HlSpace.safeH + HlLayout.readableNarrow).toPx() }
    val fade = with(density) { BackdropDefaults.veilFade.toPx() }
    var bounds by remember { mutableStateOf(Size.Zero) }
    val sized = modifier.fillMaxSize().onSizeChanged { bounds = it.toSize() }
    FadeBetween(shown, sized, atOnce = { it?.atOnce == true }, holds = holds) { shot, alpha ->
        if (shot != null) BackdropPicture(shot.prepared, shot.style, bounds, column, fade, { alpha() * level() }, recede)
    }
}

/** One backdrop picture: [prepared] from [url] in [style]; [handedOn] marks the previous screen's. */
@Immutable
internal class Shot(
    val prepared: PreparedBackdrop,
    val style: BackdropStyle,
    val url: String?,
    val atOnce: Boolean = false,
    val handedOn: Boolean = false,
) {
    /** Same picture and style as [shot], so it needs no fade. */
    fun continues(shot: Shot?) = shot != null && url == shot.url && style == shot.style

    fun shownAtOnce() = Shot(prepared, style, url, atOnce = true, handedOn = handedOn)

    /** This picture as the next screen's backdrop starts from it. */
    fun handedOn() = Shot(prepared, style, url, atOnce = true, handedOn = true)
}

/** What a [Backdrop] shows; the current picture stays until the next one is prepared. */
@Stable
internal class BackdropState {
    var shown by mutableStateOf<Shot?>(null)
        private set

    /** The image it last finished preparing (null: none to show), or [NOTHING_YET]. */
    private var preparedFor by mutableStateOf<Any?>(NOTHING_YET)

    private var shownOnce = false

    /** Starts from [shot], the previous screen's handed-on picture. */
    fun startFrom(shot: Shot?) {
        if (shot == null) return
        shown = shot
        shownOnce = true
    }

    /** Whether the picture of [image] is prepared, or known to be missing. */
    fun settledOn(image: ImageRef?): Boolean = preparedFor == image

    /** Shows [shot] for [image] (null: none or failed), without a fade per [showsAtOnce] or [Shot.continues]. */
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
 * Hands a screen's backdrop picture to the next screen's across the cut, so only the veils or the picture
 * change; a screen without a backdrop lets it fade out ([LeavingBackdrop]).
 */
@Stable
class BackdropRelay {
    private var holder: Any? = null
    private var last: Shot? = null

    /** The previous screen's picture, fading out on black until [gone]. */
    internal var leaving by mutableStateOf<Shot?>(null)
        private set

    /** An arriving backdrop takes the relay and gets the previous picture. */
    internal fun claim(owner: Any): Shot? {
        holder = owner
        return last?.handedOn()
    }

    internal fun shows(owner: Any, shot: Shot?) {
        if (holder === owner) last = shot
    }

    /** A leaving backdrop's picture fades out unless another has taken the relay. */
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

/** Whether the screen's backdrop has settled on an image's picture ([WithItsPicture]); true without one. */
val LocalBackdropSettled = staticCompositionLocalOf<(ImageRef?) -> Boolean> { { true } }

/** The app's [BackdropRelay]; null (a standalone backdrop, as in the catalogue) starts empty. */
val LocalBackdropRelay = staticCompositionLocalOf<BackdropRelay?> { null }

/** Under every screen: the previous picture fading out on black when the new screen has no backdrop. */
@Composable
fun LeavingBackdrop(relay: BackdropRelay) {
    val leaving = relay.leaving ?: return
    val out = remember(leaving) { Animatable(1f) }
    LaunchedEffect(leaving) {
        out.animateTo(0f, tween(HlMotion.SLOW, easing = HlMotion.lightsOut))
        relay.gone(leaving)
    }
    // Fades through the layer's alpha, without redrawing the picture.
    BackdropShots(leaving, Modifier, recede = null, level = { out.value })
}

/** Only the first picture, preloaded ([inMemory]) while the launch screen [covered] it, shows without a fade. */
internal fun showsAtOnce(first: Boolean, inMemory: Boolean, covered: Boolean): Boolean = first && inMemory && covered

/**
 * The [Backdrop] of [image] under [content], providing its ambient light as [LocalAmbient]. [ready]: the
 * content is loaded; a covering launch screen leaves once it and the picture are drawn ([drawnWhole]).
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

/** Aspect ratio of a requested backdrop: the screen's. */
private const val BACKDROP_WIDTH = 16
private const val BACKDROP_HEIGHT = 9

/** The URL of [image] as a backdrop in this style, on a screen [screenPx] wide. */
private fun BackdropStyle.urlOf(image: ImageRef, screenPx: Int): String = image.url(maxWidthPx?.coerceAtMost(screenPx) ?: screenPx)

/** The height a backdrop is asked for on a screen [screenPx] wide. */
private fun backdropHeight(screenPx: Int) = screenPx * BACKDROP_HEIGHT / BACKDROP_WIDTH

/** The requests a [Backdrop] makes on a screen [screenPx] wide: the picture, then its sample. */
internal fun backdropRequests(context: Context, image: ImageRef, style: BackdropStyle, screenPx: Int): List<ImageRequest> = listOf(
    backdropRequest(context, style.urlOf(image, screenPx), screenPx, backdropHeight(screenPx)),
    backdropSampleRequest(context, image),
)

/** One picture and its veils as a single cached layer at [alpha], redrawn only while the house lights move. */
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
    // Read only this cue step, so the layer redraws while the veils lift, not for the whole cue.
    val scrims = rememberCueStep(HouseLightCue::scrims)
    val veil = remember(prepared, bounds, style) {
        prepared.sample.leftVeil(bounds, column, style.imageAlpha, style.leftScrim, style.down)
    }
    // When receding, the picture takes the veil text needs over its whole area, at least VEIL.
    val receded = remember(prepared, bounds, style, recede != null) {
        if (recede == null) 0f else max(HlAlpha.VEIL, prepared.sample.floorAcross(bounds, style.imageAlpha, style.down))
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                // On black, receding is just the layer's alpha: free, where a veil would redraw the layer.
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
                // Brushes built once per picture; the house lights only change their alpha.
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
 * A [Crossfade] that hands the alpha to [content], so a cached layer fades as one texture, not two;
 * [atOnce] targets appear opaque, and [holds] keeps the leaving one opaque until the next is in.
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
                // Opaque from the first frame it is drawn, before the transition's first step.
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

/** The left veil across [column] px, then faded out over up to [fade] px in proportion to its strength. */
private fun leftVeil(black: Color, veil: List<VeilStop>, column: Float, fade: Float): Brush {
    val end = column + fade * veil.last().alpha
    val stops = veil.map { VeilStop(it.at * column / end, it.alpha) } + VeilStop(1f, 0f)
    return Brush.horizontalGradient(*stops.colorStops(black), startX = 0f, endX = end)
}
