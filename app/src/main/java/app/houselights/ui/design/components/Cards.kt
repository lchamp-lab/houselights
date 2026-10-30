package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.Person
import app.houselights.ui.design.Ground
import app.houselights.ui.design.GroupFocus
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlColors
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.LocalGroupFocus
import app.houselights.ui.design.RowScroll
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.fadeOutsideSafeArea
import app.houselights.ui.design.groundPlate
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.keepChildFocus
import app.houselights.ui.design.rememberGroupMember
import app.houselights.ui.design.rememberedInGroup
import app.houselights.ui.design.scrollsAsBlock
import app.houselights.ui.util.toPx
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlin.math.ceil
import kotlin.math.roundToInt

/** True while a card of the enclosing row or grid has focus, so the others recede. */
val LocalGroupFocused = compositionLocalOf { false }

/**
 * A horizontal row of cards under [header] and its [count], or a custom [heading], then how many of its
 * titles are [news] ([wearsNewDot]), and the change under way it is [pending] ("Classement des épisodes…"), by the
 * pending dot, its cards kept meanwhile. Focus returns to the last focused card; one frame travels from card to card.
 * Whatever takes focus in it is a framed card ([ArtworkSurface], [AbsentCard]), as the row's frame is its only focus.
 */
@Composable
fun MediaRow(
    header: String?,
    modifier: Modifier = Modifier,
    spacing: Dp = HlSpace.m,
    state: LazyListState = rememberLazyListState(),
    count: String? = null,
    news: Int = 0,
    heading: (@Composable () -> Unit)? = null,
    pending: String? = null,
    content: LazyListScope.() -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    // Scrolls as one block in the enclosing list: bringing any card into view brings the header too.
    Column(modifier.scrollsAsBlock()) {
        if (heading != null || header != null || pending != null) {
            RowHeading(
                RowNotes(count, news, pending),
                Modifier.padding(start = HlSpace.safeH, bottom = HlSpace.xxs).fadeOutsideSafeArea(),
            ) { line -> if (heading != null) Box(line) { heading() } else if (header != null) SectionHeader(header, line) }
        }
        val group = remember { GroupFocus() }
        val frame = rememberRowFrame(state)
        CompositionLocalProvider(LocalGroupFocused provides rowFocused, LocalGroupFocus provides group, LocalRowFrame provides frame) {
            RowScroll {
                LazyRow(
                    state = state,
                    // Observe the row's focus group itself, so hasFocus covers every card.
                    modifier = Modifier
                        .onFocusChanged {
                            rowFocused = it.hasFocus
                            frame.rowFocused(it.hasFocus)
                        }
                        .keepChildFocus(group)
                        .then(frame.drawn),
                    // Room for a focused card's frame and glow ([HlFocus.room]).
                    contentPadding = PaddingValues(horizontal = HlSpace.safeH, vertical = HlFocus.room),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    content = content,
                )
            }
        }
    }
}

/** What a row's heading says after its title: its [count], its [news], the change it is [pending]. */
private class RowNotes(val count: String?, val news: Int, val pending: String?)

/** A row's title, then its count, its news and what it is pending ([notes]) in captions on the title's baseline. */
@Composable
private fun RowHeading(notes: RowNotes, modifier: Modifier = Modifier, title: @Composable (Modifier) -> Unit) {
    val c = Hl.colors
    val news = notes.news
    Row(modifier) {
        title(Modifier.alignByBaseline())
        notes.count?.let {
            Spacer(Modifier.width(HlSpace.s))
            Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        if (news > 0) {
            Spacer(Modifier.width(HlSpace.s))
            Row(Modifier.alignByBaseline(), verticalAlignment = Alignment.CenterVertically) {
                // The ember dot says new; the words say how many.
                Box(Modifier.size(HlSize.newsDot).background(c.ember, CircleShape))
                Spacer(Modifier.width(HlSpace.xs))
                val words = pluralStringResource(R.plurals.row_news, news, news)
                Text(words, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
            }
        }
        // Quiet, never ember: nothing failed, the row is being changed and keeps its cards until then.
        notes.pending?.let {
            Spacer(Modifier.width(HlSpace.s))
            Row(Modifier.alignByBaseline(), verticalAlignment = Alignment.CenterVertically) {
                StatusLight(StatusState.Pending)
                Spacer(Modifier.width(HlSpace.xs))
                Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
            }
        }
    }
}

/**
 * The focus treatment of all artwork (DESIGN.md, Cards): framed, it glows in its own tone ([light], else the
 * ambient light) while its siblings recede; in a [MediaRow], the row draws its frame. Without [glows], framed alone,
 * as [AbsentCard] is: a film the library lacks has no light of its own to give.
 * Read as one element with [stateDescription]; with [readAs], that text then its state, its click only if [acts].
 * A long OK does [onLongClick], named by [onLongClickLabel]. What its content covers from its top ([covered], px, read
 * while drawing) takes no plate under it, as on [HlFocusSurface].
 */
@Composable
fun ArtworkSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = HlShapes.artwork,
    onFocus: () -> Unit = {},
    stateDescription: String? = null,
    readAs: String? = null,
    acts: Boolean = false,
    light: String? = null,
    glows: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    covered: (() -> Float)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val receding = LocalGroupFocused.current && !focused
    val recede = animateFloatAsState(
        if (receding) 1f else 0f,
        if (receding) tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull) else tween(HlMotion.FOCUS_OUT, easing = HlMotion.fallBack),
        label = "recede",
    )
    val c = Hl.colors
    // The card's own tone, sampled once focus rests on it (not while an arrow is held), else the ambient light.
    val own = rememberGlowColor(light, focused && !Scrub.active)
    val ambient = LocalAmbient.current
    val group = LocalGroupFocus.current
    val member = rememberGroupMember(group)
    val reading = rememberCardReading(readAs, stateDescription, focused, onClick.takeIf { acts }, onLongClick, onLongClickLabel)
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(
            rest = c.content.copy(alpha = HlAlpha.FILL),
            restInk = c.content,
            edge = artworkEdge(c),
        ),
        shape = shape,
        modifier = modifier
            .rememberedInGroup(group, member)
            .then(reading)
            .onFocusChanged { if (it.isFocused) onFocus() },
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        glow = if (glows) ({ if (light != null) own.value else ambient.glow() }) else null,
        interactionSource = interaction,
        row = LocalRowFrame.current,
        covered = covered,
    ) {
        // Recede is read while drawing, never recomposing the row; content sits on the artwork ground.
        CompositionLocalProvider(LocalArtworkRecede provides { recede.value }, LocalGround provides Ground.Artwork) { content() }
    }
}

/** How far a card's artwork recedes: 0 in front, 1 behind the focused card. */
val LocalArtworkRecede = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Card artwork; [fallback] replaces it when missing or failing (NAS asleep); without one, the surface shows.
 * [pictureModifier] styles the loaded image only. Decoded at its laid out size, or at [size] (px) when given, as
 * a preload asks for it, cropped about [alignment]. Receding dims and desaturates it through its colour filter, not
 * a layer, read while drawing ([RecedingPainter]): it never composes again as it steps back. A picture that never
 * recedes may take a [filter] of its own. [onShow] is told once it shows, its picture decoded or its fallback in its
 * place: its caller then brings it in itself, without the loader's fade under it, as the first scan's wall does
 * ([FirstWall]). [onWhole] is told once its picture covers what it lies on: decoded with no see-through part, the
 * loader's fade over ([rememberWhole]); what lies under it need not be drawn then.
 */
@Composable
fun ArtworkImage(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
    pictureModifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    size: IntSize? = null,
    alignment: Alignment = Alignment.Center,
    filter: ColorFilter? = null,
    onShow: (() -> Unit)? = null,
    onWhole: (() -> Unit)? = null,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    var failed by remember(url) { mutableStateOf(false) }
    val recede = LocalArtworkRecede.current
    if (url == null || failed) {
        if (onShow != null) {
            val shown by rememberUpdatedState(onShow)
            LaunchedEffect(url) { shown() }
        }
        Box(modifier.receding(recede, Hl.colors.background), content = fallback)
    } else {
        val context = LocalContext.current
        val fades = onShow == null
        val model = remember(url, size, context, fades) { artworkModel(context, url, size, fades) }
        val whole = onWhole?.let { rememberWhole(url, it) }
        val receding = remember(recede) { { state: AsyncImagePainter.State -> state.receding(recede) } }
        AsyncImage(
            model = model,
            contentDescription = description,
            modifier = modifier.then(pictureModifier),
            transform = if (filter == null) receding else AsyncImagePainter.DefaultTransform,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> {
                        onShow?.invoke()
                        whole?.invoke(state.result)
                    }
                    is AsyncImagePainter.State.Error -> failed = true
                    else -> Unit
                }
            },
            alignment = alignment,
            contentScale = contentScale,
            colorFilter = filter,
        )
    }
}

/**
 * What stands in for an image, or lies on it (a mark's plate), recedes with it at the same step, dimmed by the
 * room drawn over it: no layer.
 */
internal fun Modifier.receding(recede: () -> Float, room: Color): Modifier = drawWithContent {
    drawContent()
    val dim = 1f - HlFocus.brightnessAt(HlFocus.recedeStep(recede()))
    if (dim > 0f) drawRect(room, alpha = dim)
}

/** A receding image at [step]: its colour and light lowered by one matrix, drawn with the image. */
private fun recedeFilter(step: Int): ColorFilter {
    val light = HlFocus.brightnessAt(step)
    val matrix = ColorMatrix().apply { setToSaturation(HlFocus.saturationAt(step)) }
    matrix *= ColorMatrix().apply { setToScale(light, light, light, 1f) }
    return ColorFilter.colorMatrix(matrix)
}

/** The filter of each step, none in front: an image takes it, and what is drawn on it (a dot, progress) too. */
private val recedeFilters: List<ColorFilter?> by lazy { (0..HlFocus.RECEDE_STEPS).map { if (it == 0) null else recedeFilter(it) } }

/** The filter at how far [recede] has gone, read while drawing. */
internal fun recedeFilterAt(recede: Float): ColorFilter? = recedeFilters[HlFocus.recedeStep(recede)]

/** A picture loaded, drawn as far as its card has stepped back ([recede]) ([RecedingPainter]); any other state as it is. */
private fun AsyncImagePainter.State.receding(recede: () -> Float): AsyncImagePainter.State =
    if (this is AsyncImagePainter.State.Success) copy(painter = RecedingPainter(painter, recede)) else this

/**
 * [painter] drawn through the filter of how far its card has stepped back ([recede], [recedeFilterAt]), read while
 * drawing, so the picture only draws again as it steps back, at each of its steps; a filter its drawer gives holds
 * instead, as its alpha applies (the loader's fade). Remembered and forgotten as [painter] would be, an animated
 * picture running then.
 */
internal class RecedingPainter(private val painter: Painter, private val recede: () -> Float) : Painter(), RememberObserver {
    /** The alpha and the filter its drawer gives. */
    private var given = 1f
    private var givenFilter: ColorFilter? = null

    /** Its step back ([HlFocus.recedeStep]): read while drawing, it draws again as it changes, not at each frame. */
    private val step = derivedStateOf { HlFocus.recedeStep(recede()) }

    override val intrinsicSize: Size get() = painter.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        given = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        givenFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        val shown = given
        val through = givenFilter ?: recedeFilters[step.value]
        with(painter) { draw(size, shown, through) }
    }

    override fun onRemembered() {
        (painter as? RememberObserver)?.onRemembered()
    }

    override fun onForgotten() {
        (painter as? RememberObserver)?.onForgotten()
    }

    override fun onAbandoned() {
        (painter as? RememberObserver)?.onAbandoned()
    }
}

/** The faint edge of artwork at rest, so a dark picture keeps its outline on black. */
internal fun Modifier.artworkEdge(shape: Shape, colors: HlColors): Modifier = border(artworkEdge(colors), shape)

private fun artworkEdge(colors: HlColors) = BorderStroke(HlSize.hairline, colors.content.copy(alpha = HlAlpha.EDGE_ARTWORK))

/** Requested poster width in px: [requestedPoster]. */
@Composable
fun posterPx(width: Dp? = null): Int = requestedPoster(width).toPx()

/** The width a poster [width] wide, a wall's cell when null, is requested at. */
internal fun requestedPoster(width: Dp?): Dp = width ?: HlSize.wallPoster

/** Still aspect ratio ([LandscapeCard]). */
private const val STILL_SHAPE = 16f / 9f

/** The request a [LandscapeCard] [widthPx] wide makes once laid out: its still at that size, cropped. */
internal fun stillRequest(context: Context, item: MediaItem, widthPx: Int): ImageRequest? =
    item.landscape?.let { artworkRequest(context, it.url(widthPx), widthPx, (widthPx / STILL_SHAPE).roundToInt()) }

/** Cards [card] wide, [spacing] apart, visible in a [MediaRow] before it scrolls, the cut one included. */
internal fun cardsInView(card: Dp, spacing: Dp): Int =
    ceil((HlLayout.CANVAS_WIDTH - HlSpace.safeH.value) / (card + spacing).value).toInt()

/**
 * 2:3 poster. With [width] null it fills the grid cell. Menu or a long OK opens its options ([onLongClick], named by
 * [onLongClickLabel]), which the Menu key says in its bottom right corner while it shows focus ([CornerKey]), its state
 * mark keeping the top one.
 */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = HlSize.poster,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    val px = posterPx(width)
    val poster = item.poster?.url(px)
    // Decoded at its width, as the preloads ask for it ([posterRequest]).
    val decoded = IntSize(px, posterDecode(px))
    val focused = remember { mutableStateOf(false) }
    val keyed = onLongClick != null
    ArtworkSurface(
        onClick,
        sized
            .aspectRatio(HlSize.POSTER_RATIO)
            .then(if (keyed) Modifier.onFocusChanged { focused.value = it.isFocused } else Modifier),
        onFocus = onFocus,
        stateDescription = cardState(item),
        light = poster,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
    ) {
        ArtworkImage(poster, item.title, Modifier.fillMaxSize(), size = decoded) { FallbackArtwork(item.title, item.year) }
        StatusMark(item, markCorner())
        ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter), HlSize.progressPoster)
        if (keyed) CornerKey({ focused.value }, started = item.progress > 0f, line = HlSize.progressPoster)
    }
}

/**
 * 16:9 still with an optional caption: an episode [number] and the [title], then [facts], beside or under it.
 * Menu or a long OK opens its options ([onLongClick], named by [onLongClickLabel]), which the Menu key says in the
 * still's bottom right corner while it shows focus, as on a poster ([CornerKey]): an episode's, « À regarder »'s, a
 * saga's in search.
 */
@Composable
fun LandscapeCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = HlSize.landscape,
    title: String? = null,
    number: String? = null,
    facts: List<String?> = emptyList(),
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val still = item.landscape?.url(width.toPx())
    val focused = remember { mutableStateOf(false) }
    val keyed = onLongClick != null
    CardColumn(modifier.width(width)) {
        ArtworkSurface(
            onClick,
            Modifier
                .fillMaxWidth()
                .aspectRatio(STILL_SHAPE)
                .then(if (keyed) Modifier.onFocusChanged { focused.value = it.isFocused } else Modifier),
            shape = HlShapes.wide,
            onFocus = onFocus,
            stateDescription = cardState(item),
            light = still,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
        ) {
            ArtworkImage(still, item.title, Modifier.fillMaxSize()) {
                FallbackArtwork(title ?: item.title, item.year.takeIf { title == null })
            }
            StatusMark(item, markCorner())
            ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter), HlSize.progressWide)
            if (keyed) CornerKey({ focused.value }, started = item.progress > 0f, line = HlSize.progressWide)
        }
        val said = facts.any { !it.isNullOrBlank() }
        if (title != null || said) {
            Spacer(Modifier.height(HlSpace.s))
            // The caption fades out before the screen edge; the picture above may extend past it.
            CardCaption(title, number, facts.takeIf { said }, Modifier.fadeOutsideSafeArea())
        }
    }
}

/**
 * The Menu key in a card's bottom right corner while it shows focus ([focused], not under a sheet), coming and going as
 * a tile's label: white on the marks' dark plate, the state mark's opposite, as far from the edges ([HlSpace.xs]), and
 * above the progress line, [line] tall, once the card is [started], so the line stays whole. It is laid on the picture,
 * keeping no room, and its size at every text size, as the marks do.
 */
@Composable
private fun BoxScope.CornerKey(focused: () -> Boolean, started: Boolean, line: Dp) {
    val bottom = HlSpace.xs + if (started) line else HlSpace.none
    PlatedMenuKey(focused, Modifier.align(Alignment.BottomEnd).padding(end = HlSpace.xs, bottom = bottom))
}

/**
 * The Menu key on the marks' dark plate, white, while [shown] says and no sheet is open, coming and going as a tile's
 * label: a card's in its corner ([CornerKey]), and a saga's predella's at its end, after its last action (#48).
 */
@Composable
fun PlatedMenuKey(shown: () -> Boolean, modifier: Modifier = Modifier) {
    val showing = shown() && OpenSheets.count == 0
    val alpha = animateFloatAsState(
        if (showing) 1f else 0f,
        if (showing) tween(HlMotion.PLATE_IN, easing = HlMotion.focusPull) else tween(HlMotion.PLATE_OUT, easing = HlMotion.fallBack),
        label = "menuKey",
    )
    val fading by remember { derivedStateOf { alpha.value > 0f } }
    if (showing || fading) {
        val c = Hl.colors
        MenuKey(
            modifier
                .graphicsLayer {
                    this.alpha = alpha.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .clip(HlShapes.mark)
                .background(c.background.copy(alpha = HlAlpha.VEIL)),
            ink = c.content,
        )
    }
}

/** A wide card's name in the poster's voice, after its episode [number]. */
@Composable
private fun CardName(name: String, number: String?) {
    Row {
        if (number != null) {
            Text(number, style = Hl.type.meta, color = Hl.colors.contentSecondary, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(HlSpace.xs))
        }
        Text(
            name,
            style = Hl.type.cardName,
            color = Hl.colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** A card's [name] after its [number], then its [facts] on the same baseline when both fit whole, else under it. */
@Composable
private fun CardCaption(name: String?, number: String?, facts: List<String?>?, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(
            { if (name != null) CardName(name, number) },
            { if (facts != null) FactRow(facts, style = Hl.type.meta) },
        ),
        modifier = modifier,
    ) { (names, factLines), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = HlSpace.s.roundToPx()
        val nameWants = names.widest(loose.maxHeight)
        val factsWant = factLines.widest(loose.maxHeight)
        val beside = nameWants == 0 || factsWant == 0 || nameWants + gap + factsWant <= loose.maxWidth
        val nameLine = names.firstOrNull()?.measure(loose)
        val after = beside && nameLine.wide > 0
        val factRoom = if (after) (loose.maxWidth - nameLine.wide - gap).coerceAtLeast(0) else loose.maxWidth
        val factLine = factLines.firstOrNull()?.measure(loose.copy(maxWidth = factRoom))
        val factX = if (after) nameLine.wide + gap else 0
        // Beside, the two lines share a baseline; under, the facts start below the name.
        val nameY = if (beside) (factLine.base - nameLine.base).coerceAtLeast(0) else 0
        val factY = if (beside) (nameLine.base - factLine.base).coerceAtLeast(0) else nameLine.tall + HlSpace.xxs.roundToPx()
        val width = maxOf(nameLine.wide, factX + factLine.wide)
        val height = maxOf(nameY + nameLine.tall, factY + factLine.tall)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            nameLine?.placeRelative(0, nameY)
            factLine?.placeRelative(factX, factY)
        }
    }
}

/** The widest the first of these lines would be, [height] tall; 0 without one. */
private fun List<Measurable>.widest(height: Int): Int = firstOrNull()?.maxIntrinsicWidth(height) ?: 0

/** A measured line's width, height and first baseline; 0 for a line that is not there. */
private val Placeable?.wide: Int get() = this?.width ?: 0
private val Placeable?.tall: Int get() = this?.height ?: 0
private val Placeable?.base: Int get() = this?.get(FirstBaseline)?.takeIf { it != AlignmentLine.Unspecified } ?: 0

/**
 * A card's column: its framed surface, then what is written under it. Only siblings are ordered, so the column is
 * drawn above its neighbours as its surface would be ([Raise]).
 */
@Composable
internal fun CardColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val raise = remember { Raise() }
    val raised = remember(raise) { Modifier.raisedBy { raise.level() } }
    Column(modifier.then(raised), horizontalAlignment = horizontalAlignment) {
        CompositionLocalProvider(LocalRaise provides raise) { content() }
    }
}

/** A credited person: portrait, name and role; opens their page ([onOpen]) when in several titles. */
@Composable
fun PersonCard(person: Person, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val px = HlSize.portrait.toPx()
    CardColumn(modifier.width(HlSize.personColumn), Alignment.CenterHorizontally) {
        ArtworkSurface(
            onClick = onOpen ?: {},
            modifier = Modifier.width(HlSize.portrait).aspectRatio(HlSize.PORTRAIT_RATIO),
            readAs = listOfNotNull(person.name, person.role).joinToString(", "),
            acts = onOpen != null,
        ) {
            // Without a portrait, the bare frame: the name is written under it.
            ArtworkImage(person.image?.url(px), person.name, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(HlSpace.s))
        // Already read by the portrait.
        Text(
            person.name,
            style = Hl.type.cardName,
            color = Hl.colors.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Each line fades by its own width before the screen edge; the portrait may extend past it.
            modifier = Modifier.clearAndSetSemantics {}.fadeOutsideSafeArea(),
        )
        person.role?.let {
            Text(
                it,
                style = Hl.type.meta,
                color = Hl.colors.contentTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {}.fadeOutsideSafeArea(),
            )
        }
    }
}

/**
 * A saga film missing from the library, or not out yet: TMDB's [poster] without colour, its light lowered
 * ([HlScrim.ABSENT_SATURATION], [HlScrim.ABSENT_BRIGHTNESS]), else an empty poster frame naming it, which says [said] on
 * focus. Framed without a glow, it steps back further than its siblings; OK does [onClick] (its synopsis), else nothing.
 * Read as its title, its [year] and [said].
 */
@Composable
fun AbsentCard(
    title: String,
    year: Int?,
    modifier: Modifier = Modifier,
    width: Dp = HlSize.poster,
    poster: ImageRef? = null,
    said: String = stringResource(R.string.saga_absent),
    onClick: (() -> Unit)? = null,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val receding = LocalGroupFocused.current && !focused
    val recede = animateFloatAsState(
        if (receding) 1f else 0f,
        if (receding) tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull) else tween(HlMotion.FOCUS_OUT, easing = HlMotion.fallBack),
        label = "recede",
    )
    val step by remember { derivedStateOf { HlFocus.recedeStep(recede.value) } }
    val px = width.toPx()
    val decoded = IntSize(px, posterDecode(px))
    // A card of its row as the films there are: focus coming back into the row comes back to it.
    val group = LocalGroupFocus.current
    val member = rememberGroupMember(group)
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(
            rest = c.content.copy(alpha = HlAlpha.FILL_FAINT),
            restInk = c.contentTertiary,
            edge = artworkEdge(c),
        ),
        shape = HlShapes.artwork,
        modifier = modifier
            .rememberedInGroup(group, member)
            .width(width)
            .aspectRatio(HlSize.POSTER_RATIO)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(title, year?.toString(), said).joinToString(", ")
                this.focused = focused
                onClick?.let { act ->
                    onClick {
                        act()
                        true
                    }
                }
            },
        role = null,
        interactionSource = interaction,
        row = LocalRowFrame.current,
    ) {
        // Artwork carries no text: the poster is the film's, its grey says it is not there.
        ArtworkImage(poster?.url(px), null, Modifier.fillMaxSize(), size = decoded, filter = absentFilters[step]) {
            AbsentFrame(title, year, said.takeIf { focused })
        }
    }
}

/** An absent film without its poster: a short rule, its title and year, tertiary, and [said] once focused. */
@Composable
private fun BoxScope.AbsentFrame(title: String, year: Int?, said: String?) {
    val c = Hl.colors
    Column(Modifier.align(Alignment.BottomStart).padding(HlSpace.s)) {
        Box(Modifier.width(HlSpace.l).height(HlSize.hairline).background(c.contentDecorative))
        Spacer(Modifier.height(HlSpace.xs))
        Text(title, style = Hl.type.cardTitle, color = c.contentTertiary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (year != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(year.toString(), style = Hl.type.badge, color = c.contentTertiary)
        }
        // Shown on focus only; at rest the grey conveys it.
        if (said != null) {
            Spacer(Modifier.height(HlSpace.xs))
            Text(said, style = Hl.type.meta, color = c.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A film's poster the library lacks, at each step back: one cached filter a step, its colour taken
 * ([HlScrim.ABSENT_SATURATION]) and its light lowered ([HlScrim.ABSENT_BRIGHTNESS]) times the siblings' step.
 */
internal val absentFilters: List<ColorFilter> by lazy {
    (0..HlFocus.RECEDE_STEPS).map { step ->
        val light = HlScrim.ABSENT_BRIGHTNESS * HlFocus.brightnessAt(step)
        val matrix = ColorMatrix().apply { setToSaturation(HlScrim.ABSENT_SATURATION) }
        matrix *= ColorMatrix().apply { setToScale(light, light, light, 1f) }
        ColorFilter.colorMatrix(matrix)
    }
}

/** Fallback when artwork is missing or fails: the title set like a one-sheet, with its year. */
@Composable
internal fun BoxScope.FallbackArtwork(title: String, year: Int?) {
    val c = Hl.colors
    Column(Modifier.align(Alignment.BottomStart).padding(HlSpace.s)) {
        Box(Modifier.width(HlSpace.l).height(HlSize.hairline).background(c.contentSecondary))
        Spacer(Modifier.height(HlSpace.xs))
        Text(title, style = Hl.type.cardTitle, color = c.content, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (year != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(year.toString(), style = Hl.type.badge, color = c.contentSecondary)
        }
    }
}

/** Placeholder for a missing or loading picture: one ground step up, edged, with a one-sheet's short rule. */
@Composable
internal fun EmptyFrame(modifier: Modifier = Modifier, shape: Shape = HlShapes.artwork) {
    val c = Hl.colors
    val rule = c.contentDecorative
    Box(
        modifier
            .clip(shape)
            .background(groundStepFill())
            .drawBehind { emptyFrameRule(rule) }
            .artworkEdge(shape, c),
    )
}

/** An [EmptyFrame]'s short rule, low on its start, in [rule] at [alpha]; none in a frame too small to hold it. */
internal fun DrawScope.emptyFrameRule(rule: Color, alpha: Float = 1f) {
    val inset = HlSpace.s.toPx()
    val length = HlSpace.l.toPx()
    if (size.width >= length + 2 * inset && size.height >= length + 2 * inset) {
        val weight = HlSize.hairline.toPx()
        drawRect(rule, Offset(inset, size.height - inset - weight), Size(length, weight), alpha = alpha)
    }
}

/**
 * A folder's latest posters in [slots] slots, unfilled ones as empty frames so every strip keeps the same
 * rhythm, the room between them what it lies on; with no posters, [name] is set like a one-sheet on black.
 */
@Composable
fun PosterStrip(
    posters: List<ImageRef>,
    slots: Int,
    modifier: Modifier = Modifier,
    name: String? = null,
    slotWidth: Dp? = null,
    gap: Dp = HlSize.hairline,
    shape: Shape = RectangleShape,
) {
    val c = Hl.colors
    if (posters.isEmpty() && name != null) {
        Box(modifier.fillMaxWidth().aspectRatio(slots * HlSize.POSTER_RATIO).background(c.background)) { FallbackArtwork(name, null) }
        return
    }
    val px = (slotWidth ?: HlSize.poster).toPx()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(slots) { i ->
            val slot = (if (slotWidth != null) Modifier.width(slotWidth) else Modifier.weight(1f)).aspectRatio(HlSize.POSTER_RATIO)
            val poster = posters.getOrNull(i)
            if (poster == null) {
                EmptyFrame(slot, shape)
            } else {
                ArtworkImage(poster.url(px), null, slot.clip(shape)) { EmptyFrame(Modifier.fillMaxSize(), shape) }
            }
        }
    }
}

/**
 * At most one mark, top right: a title held while its file is missing, by the ring, first; else unwatched episode count,
 * watched check or new-title ember dot.
 */
@Composable
internal fun StatusMark(item: MediaItem, modifier: Modifier = Modifier) {
    val unplayed = item.user.unplayedCount ?: 0
    val c = Hl.colors
    // The card's state description says it in words, so the bare number is not read.
    val mark = modifier.clearAndSetSemantics {}
    when {
        item.held -> HeldMark(mark)
        unplayed > 0 -> MarkPlate(mark) {
            Text(
                "$unplayed",
                style = Hl.type.badge,
                color = c.content,
                modifier = Modifier.padding(horizontal = HlSpace.xs, vertical = HlControl.badgePaddingV),
            )
        }
        item.user.played -> CheckMark(mark)
        item.wearsNewDot() -> NewDot(mark)
    }
}

/**
 * A title held while its file is missing ([MediaItem.held]): the ring of a state that is off, white on the marks' dark
 * plate, never ember, as it may be no failure; it recedes with it.
 */
@Composable
internal fun HeldMark(modifier: Modifier = Modifier) {
    MarkPlate(modifier.size(HlSize.mark)) { StatusLight(StatusState.Off, ink = Hl.colors.content) }
}

/**
 * The marks' dark plate over a picture, on their corners ([HlShapes.mark]), [content] centred: as large as [modifier]
 * makes it, else as its content (a count). It recedes with the picture.
 */
@Composable
internal fun MarkPlate(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Hl.colors
    Box(
        modifier
            .clip(HlShapes.mark)
            .background(c.background.copy(alpha = HlAlpha.VEIL))
            .receding(LocalArtworkRecede.current, c.background),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Where a mark sits on its picture: its top right corner, [HlSpace.xs] in. */
internal fun BoxScope.markCorner(): Modifier = Modifier.align(Alignment.TopEnd).padding(HlSpace.xs)

/**
 * A card wears the dot of a title new to the library unless it counts unwatched episodes or is held instead; a row
 * counts these.
 */
fun MediaItem.wearsNewDot(): Boolean = !held && (user.unplayedCount ?: 0) == 0 && isNew()

/** A title new to the library: a solid ember dot ringed in black, so it stands off any picture; it recedes with it. */
@Composable
internal fun NewDot(modifier: Modifier = Modifier) {
    val c = Hl.colors
    val ring = c.background.copy(alpha = HlAlpha.VEIL)
    val recede = LocalArtworkRecede.current
    // A status dot is the one round thing; its ring is drawn around it, outside its size.
    Spacer(
        modifier.size(HlSize.newDot).drawBehind {
            drawCircle(ring, radius = size.minDimension / 2 + HlSize.newDotRing.toPx())
            drawCircle(c.ember, colorFilter = recedeFilterAt(recede()))
        },
    )
}

/** The check over artwork: projector white for watched, ember for the [choice] in use ([SelectedMark]); it recedes with it. */
@Composable
internal fun CheckMark(modifier: Modifier = Modifier, choice: Boolean = false) {
    val c = Hl.colors
    MarkPlate(modifier.size(HlSize.mark)) {
        val ink = if (choice) c.ember else c.content
        Icon(HlIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(HlSize.markIcon))
    }
}

/** The card's state for accessibility ("introuvable", "6 épisodes non vus", "vu", "nouveau, en cours, 40 %"), or null. */
@Composable
private fun cardState(item: MediaItem): String? {
    val unplayed = item.user.unplayedCount ?: 0
    val mark = when {
        item.held -> stringResource(R.string.card_held)
        unplayed > 0 -> pluralStringResource(R.plurals.card_unwatched, unplayed, unplayed)
        item.user.played -> stringResource(R.string.card_watched)
        item.wearsNewDot() -> stringResource(R.string.card_new)
        else -> null
    }
    val progress = item.progress.takeIf { it > 0f }?.let { stringResource(R.string.card_in_progress, (it * PERCENT).roundToInt()) }
    return listOfNotNull(mark, progress).joinToString(", ").ifEmpty { null }
}

private const val PERCENT = 100

/**
 * Ember progress line for titles in progress, [thickness] tall, the ember light stretched over the part seen.
 * On artwork it burns at the picture's foot over the dark plate ([groundPlate]), cut by the picture's corners,
 * and recedes with it. On a [focused] plate it is a mark in solid raspberry on the plate itself, a track under it
 * would hold it under 3:1; the part to come is pale there, so a count at the line's end reads as a whole gauge's.
 */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, thickness: Dp = HlSize.progress, focused: Boolean = false) {
    if (progress <= 0f) return
    val c = Hl.colors
    val under = if (focused) null else groundPlate() ?: c.content.copy(alpha = HlAlpha.FILL_STRONG)
    val toCome = if (focused) c.onFocus.copy(alpha = HlAlpha.FILL) else null
    val mark = FocusStyle.emberMark(focused).takeIf { focused }
    val seen = progress.coerceAtMost(1f)
    val recede = LocalArtworkRecede.current
    Spacer(
        modifier.fillMaxWidth().height(thickness).drawWithCache {
            val part = Size(size.width * seen, size.height)
            // A brush spans the whole line unless told: the light ends where the part seen does.
            val light = c.emberLight(across = part.width)
            onDrawBehind {
                under?.let { drawRect(it) }
                toCome?.let { drawRect(it, topLeft = Offset(part.width, 0f), size = Size(size.width - part.width, size.height)) }
                if (mark != null) drawRect(mark, size = part) else drawRect(light, size = part, colorFilter = recedeFilterAt(recede()))
            }
        },
    )
}

/** A loading card: an empty frame of its footprint, no shimmer; a null [width] fills a grid cell. */
@Composable
fun CardSkeleton(width: Dp?, aspectRatio: Float, modifier: Modifier = Modifier) {
    val sized = if (width != null) modifier.width(width) else modifier
    EmptyFrame(sized.aspectRatio(aspectRatio))
}
