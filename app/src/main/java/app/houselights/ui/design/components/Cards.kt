package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.Person
import app.houselights.ui.design.GroupFocus
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlColors
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.groundPlate
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.Ground
import app.houselights.ui.design.fadeOutsideSafeArea
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalGroupFocus
import app.houselights.ui.design.RowScroll
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.keepChildFocus
import app.houselights.ui.design.rememberGroupMember
import app.houselights.ui.design.rememberedInGroup
import app.houselights.ui.design.scrollsAsBlock
import app.houselights.ui.util.toPx
import app.houselights.ui.design.HlLayout
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlin.math.ceil
import kotlin.math.roundToInt
import app.houselights.ui.design.Scrub

/** True while one card of the enclosing row or grid has focus: the others step back. */
val LocalGroupFocused = compositionLocalOf { false }

/** A horizontal row of cards with an optional header; focus comes back into it on the last card. */
@Composable
fun MediaRow(
    header: String?,
    modifier: Modifier = Modifier,
    spacing: Dp = HlSpace.m,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    // One block for the list around it: whichever card asks to be seen, the row comes with its header.
    Column(modifier.scrollsAsBlock()) {
        if (header != null) SectionHeader(header, Modifier.padding(start = HlSpace.safeH, bottom = HlSpace.xxs).fadeOutsideSafeArea())
        val group = remember { GroupFocus() }
        CompositionLocalProvider(LocalGroupFocused provides rowFocused, LocalGroupFocus provides group) {
            RowScroll {
                LazyRow(
                    state = state,
                    // Observe the row's focus group itself, so hasFocus covers every card.
                    modifier = Modifier
                        .onFocusChanged { rowFocused = it.hasFocus }
                        .keepChildFocus(group),
                    // Room for a focused card to grow: a poster grows by 8.4 dp at the top and the bottom.
                    contentPadding = PaddingValues(horizontal = HlSpace.safeH, vertical = HlSpace.s),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    content = content,
                )
            }
        }
    }
}

/**
 * Focus treatment shared by every piece of artwork: it grows, gets a thin projector-white
 * outline and glows with the ambient light, while its neighbours dim. At rest, a faint edge keeps
 * dark artwork from dissolving into the black room. To accessibility services it is one element:
 * what it holds, then [stateDescription] (watched, new, in progress). A card that does nothing
 * when chosen, focusable only so its row scrolls, says [readAs] alone and announces no action.
 */
@Composable
fun ArtworkSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = HlShapes.artwork,
    lift: Lift = Lift.Artwork,
    onFocused: () -> Unit = {},
    stateDescription: String? = null,
    readAs: String? = null,
    light: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focusedNow by interaction.collectIsFocusedAsState()
    // Crossed while an arrow is held, a card neither grows nor lights: only the one focus stops on.
    val crossed = focusedNow && Scrub.active
    val focused = focusedNow && !crossed
    val recede = animateFloatAsState(
        if (LocalGroupFocused.current && !focusedNow) 1f else 0f,
        tween(HlMotion.STANDARD, easing = HlMotion.standard),
        label = "recede",
    )
    val c = Hl.colors
    // With focus, a card with its own picture ([light]) glows with that picture's colour; the others
    // with the room's light. Both are read while drawing: the light comes and goes without
    // recomposing the card.
    val own = rememberGlowColor(light, focused)
    val ambient = LocalAmbient.current
    // The card grows inside its own layer; the light around it grows with it.
    val grown by animateFloatAsState(
        if (focused) lift.scale else 1f,
        tween(HlMotion.STANDARD, easing = HlMotion.standard),
        label = "grown",
    )
    val reach = with(LocalDensity.current) { HlFocus.glow.toPx() }
    // Coming back into its row, focus returns to the card that had it last.
    val group = LocalGroupFocus.current
    val member = rememberGroupMember(group)
    val reading = cardReading(readAs, stateDescription, focusedNow)
    Surface(
        onClick = onClick,
        modifier = modifier
            .rememberedInGroup(group, member)
            .then(reading)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .drawBehind {
                val tone = when {
                    light != null -> own.value
                    focused -> ambient()
                    else -> Color.Transparent
                }
                drawGlow(tone, grown, reach, shape)
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = if (crossed) FocusStyle.crossed() else FocusStyle.scale(lift),
        border = FocusStyle.lift(shape),
        colors = FocusStyle.still(rest = c.content.copy(alpha = HlAlpha.FILL), restContent = c.content),
        interactionSource = interaction,
    ) {
        // Read while drawing, or by the image alone: stepping back never recomposes the row.
        // What a card holds lies on its picture: its marks and progress take their plate.
        CompositionLocalProvider(LocalArtworkRecede provides { recede.value }, LocalGround provides Ground.Artwork) { content() }
        Box(Modifier.fillMaxSize().artworkEdge(shape, c))
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Nothing drawn in front: a clear rectangle still costs the chip a pass over the card.
                val dim = recede.value * HlFocus.SIBLING_DIM
                if (dim > 0f) drawRect(c.background.copy(alpha = dim))
            }
        )
    }
}

/**
 * How a card reads to accessibility services: what it holds and its [stateDescription], or [readAs]
 * alone, outside the surface's own semantics (clearing them drops its click, keeping its focus said).
 */
private fun cardReading(readAs: String?, stateDescription: String?, focused: Boolean): Modifier = if (readAs != null) {
    Modifier.clearAndSetSemantics {
        contentDescription = readAs
        this.focused = focused
    }
} else {
    Modifier.semantics(mergeDescendants = true) { stateDescription?.let { this.stateDescription = it } }
}

/**
 * The light around a focused card: rings of [tone] out to [reach] past the card grown to [grown],
 * fading with the distance. Drawn outside the card's layer, so it scales by hand. Each ring is
 * drawn once, as a band, at the light all the rings stacked from it outward would give: the same
 * light as rounded rectangles laid one over another, for the area of a ring rather than of a card
 * each time (on the player's graphics chip, sixteen cards' worth of pixels per frame).
 */
private fun DrawScope.drawGlow(tone: Color, grown: Float, reach: Float, shape: Shape) {
    if (tone.alpha == 0f) return
    val grow = Offset(size.width * (grown - 1f) / 2f, size.height * (grown - 1f) / 2f)
    val corner = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
    val steps = HlScrim.GLOW_STEPS
    val band = reach / steps
    val ring = Stroke(width = band)
    // How much of what is under the light still shows through the rings, from the outermost in.
    var through = 1f
    for (k in steps downTo 1) {
        through *= 1f - tone.alpha * HlScrim.GLOW_LAYER * (1f - k / steps.toFloat())
        // The band's middle line: a stroke is drawn half inside, half outside it.
        val middle = band * k - band / 2
        drawRoundRect(
            color = tone.copy(alpha = 1f - through),
            topLeft = Offset(-grow.x - middle, -grow.y - middle),
            size = Size(size.width + 2 * (grow.x + middle), size.height + 2 * (grow.y + middle)),
            cornerRadius = CornerRadius(corner + middle),
            style = ring,
        )
    }
}

/** How far the artwork of a card steps back: 0 in front, 1 behind the focused card. */
val LocalArtworkRecede = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Artwork as a card shows it; [fallback] takes its place when there is none, or when it fails to
 * load (a NAS asleep, a file gone), so a card is never left blank. [picture] is the picture's alone,
 * once there is one to show: the ground it loads on, its edge, its margin ([PosterThumb],
 * [RowPreview]); [modifier] places either. When its card steps back it loses part of its colour
 * ([HlFocus.SIBLING_SATURATION]) through the image's own colour filter: no offscreen layer per card,
 * and only this image recomposes, once per step ([HlFocus.RECEDE_STEPS]).
 */
@Composable
fun ArtworkImage(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    picture: Modifier = Modifier,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url == null || failed) {
        Box(modifier, content = fallback)
    } else {
        val recede = LocalArtworkRecede.current
        val step by remember(recede) { derivedStateOf { HlFocus.recedeStep(recede()) } }
        val filter = remember(step) {
            if (step == 0) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(HlFocus.saturationAt(step)) })
        }
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = contentScale,
            colorFilter = filter,
            onError = { failed = true },
            modifier = modifier.then(picture),
        )
    }
}

/** The faint edge of artwork at rest, so a dark picture keeps its outline on the black room. */
internal fun Modifier.artworkEdge(shape: Shape, colors: HlColors): Modifier =
    border(HlSize.hairline, colors.content.copy(alpha = HlAlpha.EDGE_ARTWORK), shape)

/** Width a poster is asked for: its own, or a little more than a row's when it fills a grid cell. */
@Composable
fun posterPx(width: Dp? = null): Int = (width ?: CELL_POSTER).toPx()

/** A poster filling a grid cell (a wall's) is asked this wide: a little more than a row's, enough for any cell. */
internal val CELL_POSTER = HlSize.poster * 1.3f

/** A poster's shape, its width to its height. */
private const val POSTER_SHAPE = 2f / 3f

/** A still's shape ([LandscapeCard]). */
private const val STILL_SHAPE = 16f / 9f

/** What a [PosterCard] [widthPx] wide in a row asks Coil for once laid out: its poster at its size, cropped. */
internal fun posterRequest(context: Context, item: MediaItem, widthPx: Int): ImageRequest? =
    item.poster?.let { artworkRequest(context, it.url(widthPx), widthPx, (widthPx / POSTER_SHAPE).roundToInt()) }

/** What a [LandscapeCard] [widthPx] wide asks Coil for once laid out: its still at its size, cropped. */
internal fun stillRequest(context: Context, item: MediaItem, widthPx: Int): ImageRequest? =
    item.landscape?.let { artworkRequest(context, it.url(widthPx), widthPx, (widthPx / STILL_SHAPE).roundToInt()) }

/** What an [ArtworkImage] laid out [widthPx] by [heightPx] asks Coil for [url]: cropped to its shape. */
private fun artworkRequest(context: Context, url: String, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context).data(url).size(widthPx, heightPx).scale(Scale.FILL).precision(Precision.INEXACT).build()

/**
 * How many cards [card] wide, [spacing] apart, a [MediaRow] shows before it scrolls: those that start
 * on the screen, the last one cut by its edge.
 */
internal fun cardsInView(card: Dp, spacing: Dp): Int =
    ceil((HlLayout.CANVAS_WIDTH - HlSpace.safeH.value) / (card + spacing).value).toInt()

/** 2:3 poster. With [width] null it fills the grid cell. */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = HlSize.poster,
    onFocused: () -> Unit = {},
) {
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    val px = posterPx(width)
    val poster = item.poster?.url(px)
    ArtworkSurface(onClick, sized.aspectRatio(POSTER_SHAPE), onFocused = onFocused, stateDescription = cardState(item), light = poster) {
        ArtworkImage(poster, item.title, Modifier.fillMaxSize()) { FallbackArtwork(item.title, item.year) }
        StatusMark(item, Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
        ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * 16:9 still or thumb, with an optional caption below: a [number] in tabular figures (an
 * episode's), its [title], then [facts] (length, where it stands).
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
    onFocused: () -> Unit = {},
) {
    val still = item.landscape?.url(width.toPx())
    Column(modifier.width(width)) {
        ArtworkSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(STILL_SHAPE),
            lift = Lift.Card,
            onFocused = onFocused,
            stateDescription = cardState(item),
            light = still,
        ) {
            ArtworkImage(still, item.title, Modifier.fillMaxSize()) {
                FallbackArtwork(title ?: item.title, item.year.takeIf { title == null })
            }
            StatusMark(item, Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
            ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter))
        }
        if (title != null) {
            Spacer(Modifier.height(HlSpace.s))
            // A caption fades before the screen's edge; the picture above it may peek.
            Row(Modifier.fadeOutsideSafeArea()) {
                if (number != null) {
                    Text(number, style = Hl.type.meta, color = Hl.colors.contentSecondary, modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(HlSpace.xs))
                }
                Text(
                    title,
                    style = Hl.type.entry,
                    color = Hl.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (facts.any { !it.isNullOrBlank() }) {
            Spacer(Modifier.height(HlSpace.xxs))
            FactRow(facts, Modifier.fadeOutsideSafeArea(), style = Hl.type.caption)
        }
    }
}

/**
 * Cast member: round portrait, then under it the name (two lines at most) and the role, so every
 * name of a row starts at the same height. Focusable so the row can scroll, but it does nothing:
 * it is read "name, role", once, and not as a button.
 */
@Composable
fun PersonCard(person: Person, modifier: Modifier = Modifier) {
    val px = HlSize.avatar.toPx()
    Column(modifier.width(HlSize.personColumn), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtworkSurface(
            onClick = {},
            modifier = Modifier.size(HlSize.avatar),
            shape = HlShapes.avatar,
            readAs = listOfNotNull(person.name, person.role).joinToString(", "),
        ) {
            Text(
                person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                style = Hl.type.lead,
                color = Hl.colors.contentTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
            person.image?.url(px)?.let { ArtworkImage(it, person.name, Modifier.fillMaxSize().clip(HlShapes.avatar)) }
        }
        Spacer(Modifier.height(HlSpace.s))
        // Said by the portrait already.
        Text(
            person.name,
            style = Hl.type.meta,
            color = Hl.colors.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Each line fades by its own width before the screen's edge; the portrait may peek.
            modifier = Modifier.clearAndSetSemantics {}.fadeOutsideSafeArea(),
        )
        person.role?.let {
            Text(
                it,
                style = Hl.type.caption,
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
 * No artwork, or none that loads: the title set like a one-sheet, in the voice of the poster, low
 * on the card under a short rule, with its year in the technical voice.
 */
@Composable
internal fun BoxScope.FallbackArtwork(title: String, year: Int?) {
    val c = Hl.colors
    Column(Modifier.align(Alignment.BottomStart).padding(HlSpace.s)) {
        Box(Modifier.width(HlSpace.l).height(HlSize.hairline).background(c.contentSecondary))
        Spacer(Modifier.height(HlSpace.xs))
        Text(title, style = Hl.type.cardTitle, color = c.content, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (year != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(year.toString(), style = Hl.type.badge, color = c.contentTertiary)
        }
    }
}

/**
 * Where a picture would be and is not yet, or failed to load: an empty frame in a foyer, one step
 * up from its ground so a TV still shows it, edged like artwork at rest, with the short rule of a
 * one-sheet at its foot waiting for a title, in the quietest grey; a frame too small to hold the
 * rule keeps its edge alone. Loading cards, the free places of a strip and
 * the thumbnails with nothing to show are all this frame.
 */
@Composable
internal fun EmptyFrame(modifier: Modifier = Modifier, shape: Shape = HlShapes.artwork) {
    val c = Hl.colors
    val rule = c.contentDecorative
    Box(
        modifier
            .clip(shape)
            .background(groundStepFill())
            .drawBehind {
                val inset = HlSpace.s.toPx()
                val length = HlSpace.l.toPx()
                if (size.width >= length + 2 * inset && size.height >= length + 2 * inset) {
                    val weight = HlSize.hairline.toPx()
                    drawRect(rule, Offset(inset, size.height - inset - weight), Size(length, weight))
                }
            }
            .artworkEdge(shape, c),
    )
}

/**
 * The latest posters of a folder side by side, at the head of its card or its sheet: [slots]
 * places parted by [gap], each [slotWidth] wide or sharing the strip's width, the places still free
 * left as empty frames, so every strip keeps the same rhythm. With no poster at all, [name] is set
 * across the strip like a one-sheet. A poster that fails to load leaves an empty frame.
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
        Box(modifier.fillMaxWidth().aspectRatio(slots * POSTER_RATIO).background(c.background)) { FallbackArtwork(name, null) }
        return
    }
    val px = (slotWidth ?: HlSize.poster).toPx()
    Row(modifier.background(c.background), horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(slots) { i ->
            val slot = (if (slotWidth != null) Modifier.width(slotWidth) else Modifier.weight(1f)).aspectRatio(POSTER_RATIO)
            val poster = posters.getOrNull(i)
            if (poster == null) {
                EmptyFrame(slot, shape)
            } else {
                ArtworkImage(poster.url(px), null, slot.clip(shape)) { EmptyFrame(Modifier.fillMaxSize(), shape) }
            }
        }
    }
}

private const val POSTER_RATIO = 2f / 3f

/**
 * One mark at most, at the top right, on a dark plate: the number of unwatched episodes of a show,
 * the line check of a title watched, or the ember dot of a title new to the library. Progress
 * has its own line at the foot of the card.
 */
@Composable
private fun StatusMark(item: MediaItem, modifier: Modifier) {
    val unplayed = item.user.unplayedCount ?: 0
    val plate = Hl.colors.background.copy(alpha = HlAlpha.VEIL)
    // The card's state description says it in words: the bare number is not read.
    val mark = modifier.clearAndSetSemantics {}
    when {
        unplayed > 0 -> Box(mark.clip(HlShapes.control).background(plate).padding(horizontal = HlSpace.xs, vertical = HlControl.badgePaddingV)) {
            Text("$unplayed", style = Hl.type.badge, color = Hl.colors.content)
        }
        item.user.played -> CheckMark(mark)
        item.isNew() -> Box(mark.size(HlSize.mark).clip(HlShapes.avatar).background(plate), contentAlignment = Alignment.Center) {
            Box(Modifier.size(HlSize.dot).clip(HlShapes.avatar).background(Hl.colors.ember))
        }
    }
}

/**
 * The line check over artwork, on the dark plate of a mark: projector white for done (watched),
 * ember for [choice], the one in use ([SelectedMark]). The same glyph, only its ink differs.
 */
@Composable
internal fun CheckMark(modifier: Modifier = Modifier, choice: Boolean = false) {
    val c = Hl.colors
    Box(
        modifier.size(HlSize.mark).clip(HlShapes.avatar).background(c.background.copy(alpha = HlAlpha.VEIL)),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (choice) c.ember else c.content
        Icon(HlIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(HlSize.markIcon))
    }
}

/**
 * Where a card stands, for accessibility services, as its mark and progress line show it:
 * "6 épisodes non vus", "vu", "nouveau, en cours, 40 %". Null when it shows nothing.
 */
@Composable
private fun cardState(item: MediaItem): String? {
    val unplayed = item.user.unplayedCount ?: 0
    val mark = when {
        unplayed > 0 -> pluralStringResource(R.plurals.card_unwatched, unplayed, unplayed)
        item.user.played -> stringResource(R.string.card_watched)
        item.isNew() -> stringResource(R.string.card_new)
        else -> null
    }
    val progress = item.progress.takeIf { it > 0f }?.let { stringResource(R.string.card_in_progress, (it * PERCENT).roundToInt()) }
    return listOfNotNull(mark, progress).joinToString(", ").ifEmpty { null }
}

private const val PERCENT = 100

/**
 * Progress line in the ember light, its whole gradient across what is done, only for titles in
 * progress. On a picture (the foot of a card, [OnArtwork]), its track is the ground's dark plate
 * ([groundPlate]), so the line reads over the lightest image.
 */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    val track = groundPlate() ?: Hl.colors.content.copy(alpha = HlAlpha.FILL_STRONG)
    Box(modifier.fillMaxWidth().height(HlSize.progress).background(track)) {
        Box(Modifier.fillMaxWidth(progress).height(HlSize.progress).background(Hl.colors.emberLight()))
    }
}

/**
 * A card while its row loads: an empty frame with the card's footprint, no shimmer. A null [width]
 * fills a grid cell.
 */
@Composable
fun CardSkeleton(width: Dp?, aspectRatio: Float, modifier: Modifier = Modifier) {
    val sized = if (width != null) modifier.width(width) else modifier
    EmptyFrame(sized.aspectRatio(aspectRatio))
}
