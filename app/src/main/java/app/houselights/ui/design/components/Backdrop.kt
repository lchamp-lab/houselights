package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.houselights.core.ImageRef
import app.houselights.ui.design.AmbientLight
import app.houselights.ui.design.BackdropDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.Veils
import app.houselights.ui.design.colorStops
import app.houselights.ui.design.drawnWhole
import app.houselights.ui.design.greyOf
import app.houselights.ui.design.rememberCueStep
import app.houselights.ui.util.screenSizePx
import app.houselights.ui.util.screenWidthPx
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlin.math.max

/** How a backdrop shows its picture: its veils (DESIGN.md, Veils) and what it draws them over. */
enum class BackdropStyle(
    val veils: Veils,
    /** How far past the text column the left veil fades out, in proportion to its last stop. */
    val leftFade: Dp,
    /** Widest image to request; null requests the screen width. */
    val maxWidthPx: Int? = null,
    /** Drawn from the picture's diffuse field ([diffuseField]), never decoding the picture itself. */
    val diffuse: Boolean = false,
) {
    /** « À regarder », a person's page with few titles: the picture fills the top of the screen; rows sit on black below. */
    Hero(Veils(1f, HlScrim.LEFT_HOME, HlScrim.TOP, HlScrim.BOTTOM_HOME), BackdropDefaults.homeFade),

    /** Detail: the picture fills the screen behind the text and fades to black under its rows. */
    Full(Veils(1f, HlScrim.LEFT_DETAIL, HlScrim.TOP, HlScrim.BOTTOM_DETAIL), BackdropDefaults.detailFade),

    /** A person's titles and the artwork editor: a dimmed picture down to the bottom; a smaller file suffices. */
    Quiet(
        Veils(HlScrim.QUIET_PICTURE, HlScrim.LEFT_QUIET, HlScrim.TOP_QUIET, HlScrim.BOTTOM_QUIET),
        BackdropDefaults.quietFade,
        maxWidthPx = 1280,
    ),

    /** A library wall: the picture's light as a diffuse field under black veils, no column to veil. */
    Diffuse(Veils(1f, emptyList(), HlScrim.DIFFUSE_TOP, HlScrim.DIFFUSE_BOTTOM, tinted = false), 0.dp, diffuse = true),
}

/**
 * A full-screen picture on black, veiled where text sits (DESIGN.md, Veils); [recede] (0 to 1) dims it as
 * a detail page scrolls. Each picture and its veils render once into a cached layer, since redrawing them
 * every frame exceeds the player's GPU budget; pictures crossfade unless [showsAtOnce].
 */
@Composable
fun Backdrop(image: ImageRef?, style: BackdropStyle, modifier: Modifier = Modifier, recede: (() -> Float)? = null) =
    BackdropOf(rememberBackdropState(), image, style, recede, modifier)

/** A backdrop's state, starting from the previous screen's picture when a [BackdropRelay] hands one on. */
@Composable
private fun rememberBackdropState(): BackdropState {
    val relay = LocalBackdropRelay.current
    return remember { BackdropState().also { it.startFrom(relay?.claim(it)) } }
}

/** A [Backdrop] showing [state], which reports when the picture of [image] is prepared. */
@Composable
private fun BackdropOf(
    state: BackdropState,
    image: ImageRef?,
    style: BackdropStyle,
    recede: (() -> Float)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launch = LocalLaunchScreen.current
    val screen = screenWidthPx()
    val url = image?.let { style.urlOf(it, screen) }
    LaunchedEffect(url, style) {
        // An arriving screen may set its picture a moment later: a handed-on picture waits for it.
        if (url == null && state.shown?.handedOn == true) delay(HlMotion.HOLD)
        val prepared = if (image == null) null else prepareBackdrop(context, image, style, screen)
        state.show(image, prepared?.let { Shot(it, style, url) }, covered = launch.covers)
    }
    // Offer the shown picture to the next screen's backdrop; if none claims it, it fades out on release.
    val relay = LocalBackdropRelay.current
    val shown = state.shown
    SideEffect { relay?.shows(state, shown) }
    DisposableEffect(relay) { onDispose { relay?.release(state) } }
    BackdropShots(shown, recede, modifier, lights = state)
}

/**
 * A backdrop's pictures, crossfading ([FadeBetween]); null is the room. [level] scales the whole backdrop's
 * opacity. Each one drawn lends its light to [lights], at the alpha it is drawn at.
 */
@Composable
internal fun BackdropShots(
    shown: Shot?,
    recede: (() -> Float)?,
    modifier: Modifier = Modifier,
    level: () -> Float = { 1f },
    lights: BackdropState? = null,
) {
    val density = LocalDensity.current
    // The text column of heroes and detail pages, which the veil follows at any screen width.
    val column = with(density) { (HlSpace.safeH + HlLayout.readableNarrow).toPx() }
    // The screen's until laid out: a backdrop fills it, so its first frame is veiled as the next.
    val screen = screenSizePx()
    var bounds by remember { mutableStateOf(screen.toSize()) }
    val sized = modifier.fillMaxSize().onSizeChanged { bounds = it.toSize() }
    FadeBetween(shown, sized, atOnce = { it?.atOnce == true }, empty = { it == null }) { shot, alpha ->
        if (shot != null) BackdropPicture(shot.prepared, shot.style, bounds, column, { alpha() * level() }, recede, lights)
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

/**
 * What a [Backdrop] shows; the current picture stays until the next one is prepared. Its light is that of the
 * pictures drawn, composited as their layers are, so plates follow a fade to the pixel.
 */
@Stable
internal class BackdropState : AmbientLight {
    var shown by mutableStateOf<Shot?>(null)
        private set

    /** The pictures drawn, bottom to top. */
    val layers = mutableStateListOf<LitLayer>()

    override fun deep(): Color {
        var deep = HouselightsColors.ambientDeep
        for (i in layers.indices) layers[i].let { deep = it.light.deep.copy(alpha = it.alpha()).compositeOver(deep) }
        return deep
    }

    override fun plateAlpha(): Float {
        var plate = HlAlpha.PLATE
        for (i in layers.indices) layers[i].let { plate += (it.plateAlpha - plate) * it.alpha() }
        return plate
    }

    override fun glow(): Color {
        var glow = Color.Transparent
        for (i in layers.indices) layers[i].let { glow = it.light.glow.copy(alpha = it.alpha()).compositeOver(glow) }
        return glow
    }

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

/** A picture drawn: its light, plates over it at [plateAlpha], its layer at [alpha]. */
internal class LitLayer(val light: ImageLight, plateAlpha: Float, var alpha: () -> Float) {
    var plateAlpha by mutableFloatStateOf(plateAlpha)
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
    BackdropShots(leaving, recede = null, level = { out.value })
}

/** Only the first picture, preloaded ([inMemory]) while the launch screen [covered] it, shows without a fade. */
internal fun showsAtOnce(first: Boolean, inMemory: Boolean, covered: Boolean): Boolean = first && inMemory && covered

/**
 * The [Backdrop] of [image] under [content], providing its light as [LocalAmbient]. [ready]: the content is
 * loaded; a covering launch screen leaves once it and the picture are drawn ([drawnWhole]).
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
    val backdrop = rememberBackdropState()
    val settled = remember(backdrop) { { shown: ImageRef? -> backdrop.settledOn(shown) } }
    CompositionLocalProvider(LocalAmbient provides backdrop, LocalBackdropSettled provides settled) {
        Box(modifier.fillMaxSize().drawnWhole(ready && backdrop.settledOn(image))) {
            BackdropOf(backdrop, image, style, recede)
            content()
        }
    }
}

/** Aspect ratio of a requested backdrop: the screen's. */
private const val BACKDROP_WIDTH = 16
private const val BACKDROP_HEIGHT = 9

/** The URL of [image] as a backdrop in this style, on a screen [screenPx] wide; a diffuse field's is its sample's. */
private fun BackdropStyle.urlOf(image: ImageRef, screenPx: Int): String =
    image.url(if (diffuse) SAMPLE_PX else maxWidthPx?.coerceAtMost(screenPx) ?: screenPx)

/** The height a backdrop is asked for on a screen [screenPx] wide. */
private fun backdropHeight(screenPx: Int) = screenPx * BACKDROP_HEIGHT / BACKDROP_WIDTH

/** The request for [image] in this style on a screen [screenPx] wide: its picture, or its diffuse field. */
internal fun BackdropStyle.pictureRequest(context: Context, image: ImageRef, screenPx: Int): ImageRequest =
    if (diffuse) diffuseRequest(context, image) else artworkRequest(context, urlOf(image, screenPx), screenPx, backdropHeight(screenPx))

/** The requests a [Backdrop] makes on a screen [screenPx] wide: the picture (or its diffuse field), then its sample. */
internal fun backdropRequests(context: Context, image: ImageRef, style: BackdropStyle, screenPx: Int): List<ImageRequest> =
    listOf(style.pictureRequest(context, image, screenPx), backdropSampleRequest(context, image))

/**
 * One picture and its veils as a single cached layer at [alpha], redrawn only while the house lights move;
 * the left and top veils in the picture's dark, dithered. Its light joins [lights] while it is drawn.
 */
@Composable
private fun BackdropPicture(
    prepared: PreparedBackdrop,
    style: BackdropStyle,
    bounds: Size,
    column: Float,
    alpha: () -> Float,
    recede: (() -> Float)?,
    lights: BackdropState?,
) {
    val black = Hl.colors.background
    // Read only this cue step, so the layer redraws while the veils lift, not for the whole cue.
    val scrims = rememberCueStep(HouseLightCue::scrims)
    val veils = style.veils
    val deep = prepared.light.deep
    val tint = greyOf(deep)
    val veil = remember(prepared, bounds, style) { prepared.sample.leftVeil(bounds, column, veils, tint) }
    // When receding, the picture takes the veil text needs over its whole area, at least VEIL.
    val receded = remember(prepared, bounds, style, recede != null) {
        if (recede == null) 0f else max(HlAlpha.VEIL, prepared.sample.floorAcross(bounds, veils, tint))
    }
    if (lights != null) {
        val band = with(LocalDensity.current) { BackdropDefaults.plateBand.toPx() }
        // A diffuse field is too dark to raise a plate.
        val plate = remember(prepared, bounds, style, band) {
            if (style.diffuse) HlAlpha.PLATE else prepared.sample.plateFloor(bounds, column, band, veils, tint)
        }
        LendsItsLight(lights, prepared.light, plate, alpha)
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
        if (!style.diffuse) {
            Image(
                prepared.picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd,
                alpha = veils.imageAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().drawWithCache {
                // Built once per picture; the house lights only change the veils' alpha.
                if (style.diffuse) return@drawWithCache drawField(prepared.picture, veils.down, scrims)
                val across = if (veil.isEmpty()) null else leftVeil(deep, veil, column, style.leftFade.toPx())
                val top = if (veils.tinted) deep else black
                val topEnd = veils.top.last().at
                val down = Brush.verticalGradient(*veils.down.colorStops { if (it <= topEnd) top else black })
                // Dithered where the GPU does it.
                val paint = Paint().apply { nativePaint.isDither = true }
                val whole = size.toRect()
                onDrawBehind {
                    val lift = scrims.value
                    drawIntoCanvas { canvas ->
                        if (across != null) {
                            across.applyTo(size, paint, lift)
                            canvas.drawRect(whole, paint)
                        }
                        down.applyTo(size, paint, lift)
                        canvas.drawRect(whole, paint)
                    }
                }
            },
        )
    }
}

/** A wall's diffuse [field] under its veils [down] lifted by [scrims], in one fill that dithers itself. */
private fun CacheDrawScope.drawField(field: ImageBitmap, down: List<VeilStop>, scrims: State<Float>): DrawResult {
    val paint = DiffusePaint(field.asAndroidBitmap(), size, down)
    return onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint.at(scrims.value)) } }
}

/** Keeps [light] in [lights] while this picture is drawn, at [alpha], plates over it at [plateAlpha]. */
@Composable
private fun LendsItsLight(lights: BackdropState, light: ImageLight, plateAlpha: Float, alpha: () -> Float) {
    val layer = remember(lights, light) { LitLayer(light, plateAlpha, alpha) }
    SideEffect {
        layer.alpha = alpha
        layer.plateAlpha = plateAlpha
    }
    DisposableEffect(layer) {
        lights.layers += layer
        onDispose { lights.layers -= layer }
    }
}

/**
 * Fades to [target] one fade at a time, handing [content] its alpha so a cached layer fades as one texture:
 * the coming one over `TINT`, linear, over the one before, held whole until it is in, then released. One coming
 * during a fade waits for its end, in place of any waiting. [atOnce] targets appear opaque; an [empty] one
 * draws nothing, what shows going out into the room as it comes.
 */
@Composable
internal fun <T> FadeBetween(
    target: T,
    modifier: Modifier = Modifier,

    atOnce: (T) -> Boolean = { false },
    empty: (T) -> Boolean = { false },
    content: @Composable (T, alpha: () -> Float) -> Unit,
) {
    val fade = remember { Fade(target) }
    val latest = rememberUpdatedState(target)
    val opaque = rememberUpdatedState(atOnce)
    val blank = rememberUpdatedState(empty)
    LaunchedEffect(fade) {
        // Conflated: whatever came last while a fade ran is the next.
        snapshotFlow { latest.value }.conflate().collect { fade.to(it, opaque.value(it), blank.value(it)) }
    }
    // Unless a fade runs, a target shown at once is drawn in the frame that brings it: a launch screen waits for it.
    val whole = fade.shown.size == 1 && target != fade.shown[0] && atOnce(target)
    Box(modifier) {
        (if (whole) listOf(target) else fade.shown).forEach { shown ->
            if (!empty(shown)) key(shown) { content(shown) { if (whole) 1f else fade.alphaOf(shown) } }
        }
    }
}

/** What a [FadeBetween] shows, bottom to top: two targets at most, the top one coming in at [progress]. */
@Stable
private class Fade<T>(first: T) {
    val shown = mutableStateListOf(first)
    private var progress by mutableFloatStateOf(1f)

    /** The top one is empty: the one under it goes out as it comes. */
    private var intoTheRoom by mutableStateOf(false)

    /** [layer]'s alpha, read while drawing: the top one's progress, the one under it whole or going out, 0 once released. */
    fun alphaOf(layer: T): Float = when (layer) {
        shown.last() -> progress
        shown.first() -> if (intoTheRoom) 1f - progress else 1f
        else -> 0f
    }

    /** Shows [next] at once, or fades it in over what shows and then releases that: one target shows between calls. */
    suspend fun to(next: T, atOnce: Boolean, empty: Boolean) {
        if (next == shown[0]) return
        if (atOnce) {
            shown[0] = next
            return
        }
        intoTheRoom = empty
        progress = 0f
        shown += next
        animate(0f, 1f, animationSpec = tween(HlMotion.TINT, easing = LinearEasing)) { value, _ -> progress = value }
        shown.removeAt(0)
    }
}

/** The left veil in [color] across [column] px, then faded out over up to [fade] px in proportion to its last stop. */
private fun leftVeil(color: Color, veil: List<VeilStop>, column: Float, fade: Float): Brush {
    val end = column + fade * veil.last().alpha
    val stops = veil.map { VeilStop(it.at * column / end, it.alpha) } + VeilStop(1f, 0f)
    return Brush.horizontalGradient(*stops.colorStops(color), startX = 0f, endX = end)
}
