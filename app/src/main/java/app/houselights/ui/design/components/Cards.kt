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
import androidx.compose.ui.semantics.onClick

/** True while a card of the enclosing row or grid has focus, so the others recede. */
val LocalGroupFocused = compositionLocalOf { false }

/** A horizontal row of cards under [header], or a custom [heading]; focus returns to the last focused card. */
@Composable
fun MediaRow(
    header: String?,
    modifier: Modifier = Modifier,
    spacing: Dp = HlSpace.m,
    state: LazyListState = rememberLazyListState(),
    heading: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    // Scrolls as one block in the enclosing list: bringing any card into view brings the header too.
    Column(modifier.scrollsAsBlock()) {
        val place = Modifier.padding(start = HlSpace.safeH, bottom = HlSpace.xxs).fadeOutsideSafeArea()
        when {
            heading != null -> Box(place) { heading() }
            header != null -> SectionHeader(header, place)
        }
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
 * The focus treatment of all artwork (DESIGN.md, Cards): it grows, outlines and glows while neighbours
 * dim. Read as one element with [stateDescription]; with [readAs], that text alone, actionable if [acts].
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
    acts: Boolean = false,
    light: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focusedNow by interaction.collectIsFocusedAsState()
    // Cards crossed while an arrow is held neither grow nor glow.
    val crossed = focusedNow && Scrub.active
    val focused = focusedNow && !crossed
    val recede = animateFloatAsState(
        if (LocalGroupFocused.current && !focusedNow) 1f else 0f,
        tween(HlMotion.STANDARD, easing = HlMotion.standard),
        label = "recede",
    )
    val c = Hl.colors
    // The glow takes the card's own tone ([light]) or the ambient light, both read while drawing.
    val own = rememberGlowColor(light, focused)
    val ambient = LocalAmbient.current
    // The card scales in its own layer; the glow, drawn outside it, follows the same scale.
    val grown by animateFloatAsState(
        if (focused) lift.scale else 1f,
        tween(HlMotion.STANDARD, easing = HlMotion.standard),
        label = "grown",
    )
    val reach = with(LocalDensity.current) { HlFocus.glow.toPx() }
    val group = LocalGroupFocus.current
    val member = rememberGroupMember(group)
    val reading = cardReading(readAs, stateDescription, focusedNow, onClick.takeIf { acts })
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
        // Recede is read while drawing, never recomposing the row; content sits on the artwork ground.
        CompositionLocalProvider(LocalArtworkRecede provides { recede.value }, LocalGround provides Ground.Artwork) { content() }
        Box(Modifier.fillMaxSize().artworkEdge(shape, c))
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Draw nothing at rest: even a transparent rectangle costs the GPU a pass over the card.
                val dim = recede.value * HlFocus.SIBLING_DIM
                if (dim > 0f) drawRect(c.background.copy(alpha = dim))
            }
        )
    }
}

/** Clearing semantics for [readAs] drops the click action, so focus is restated and [onClick] re-added. */
private fun cardReading(readAs: String?, stateDescription: String?, focused: Boolean, onClick: (() -> Unit)?): Modifier =
    if (readAs != null) {
        Modifier.clearAndSetSemantics {
            contentDescription = readAs
            this.focused = focused
            onClick?.let { act -> onClick { act(); true } }
        }
    } else {
        Modifier.semantics(mergeDescendants = true) { stateDescription?.let { this.stateDescription = it } }
    }

/**
 * The glow [reach] around a focused card at scale [grown]: each ring is drawn once at the combined alpha
 * of stacked layers, so each layer costs a ring's fill, not a card's, on the player's GPU.
 */
private fun DrawScope.drawGlow(tone: Color, grown: Float, reach: Float, shape: Shape) {
    if (tone.alpha == 0f) return
    val grow = Offset(size.width * (grown - 1f) / 2f, size.height * (grown - 1f) / 2f)
    val corner = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
    val steps = HlScrim.GLOW_STEPS
    val band = reach / steps
    val ring = Stroke(width = band)
    // Transmittance of the stacked layers, accumulated from the outermost ring inward.
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

/** How far a card's artwork recedes: 0 in front, 1 behind the focused card. */
val LocalArtworkRecede = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Card artwork; [fallback] replaces it when missing or failing (NAS asleep), so a card is never blank.
 * [picture] styles the loaded image only. Receding desaturates it through its colour filter, not a layer.
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

/** The faint edge of artwork at rest, so a dark picture keeps its outline on black. */
internal fun Modifier.artworkEdge(shape: Shape, colors: HlColors): Modifier =
    border(HlSize.hairline, colors.content.copy(alpha = HlAlpha.EDGE_ARTWORK), shape)

/** Requested poster width in px: [width], or [CELL_POSTER] when it fills a grid cell. */
@Composable
fun posterPx(width: Dp? = null): Int = (width ?: CELL_POSTER).toPx()

/** Requested width of a poster in a wall cell: a little more than a row's, enough for any cell. */
internal val CELL_POSTER = HlSize.poster * 1.3f

/** Poster aspect ratio, width to height. */
private const val POSTER_SHAPE = 2f / 3f

/** Still aspect ratio ([LandscapeCard]). */
private const val STILL_SHAPE = 16f / 9f

/** The request a [PosterCard] [widthPx] wide makes once laid out: its poster at that size, cropped. */
internal fun posterRequest(context: Context, item: MediaItem, widthPx: Int): ImageRequest? =
    item.poster?.let { artworkRequest(context, it.url(widthPx), widthPx, (widthPx / POSTER_SHAPE).roundToInt()) }

/** The request a [LandscapeCard] [widthPx] wide makes once laid out: its still at that size, cropped. */
internal fun stillRequest(context: Context, item: MediaItem, widthPx: Int): ImageRequest? =
    item.landscape?.let { artworkRequest(context, it.url(widthPx), widthPx, (widthPx / STILL_SHAPE).roundToInt()) }

/** The request an [ArtworkImage] laid out [widthPx] by [heightPx] makes for [url], cropped to that shape. */
private fun artworkRequest(context: Context, url: String, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context).data(url).size(widthPx, heightPx).scale(Scale.FILL).precision(Precision.INEXACT).build()

/** Cards [card] wide, [spacing] apart, visible in a [MediaRow] before it scrolls, the cut one included. */
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

/** 16:9 still with an optional caption: an episode [number], the [title], then [facts]. */
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
            // The caption fades out before the screen edge; the picture above may extend past it.
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

/** A credited person: portrait, name and role; opens their page ([onOpen]) when in several titles. */
@Composable
fun PersonCard(person: Person, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val px = HlSize.avatar.toPx()
    Column(modifier.width(HlSize.personColumn), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtworkSurface(
            onClick = onOpen ?: {},
            modifier = Modifier.size(HlSize.avatar),
            shape = HlShapes.avatar,
            readAs = listOfNotNull(person.name, person.role).joinToString(", "),
            acts = onOpen != null,
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
        // Already read by the portrait.
        Text(
            person.name,
            style = Hl.type.meta,
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

/** A saga film missing from the library: an empty poster frame that says so on focus; OK does nothing. */
@Composable
fun AbsentCard(title: String, year: Int?, modifier: Modifier = Modifier, width: Dp = HlSize.poster) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val absent = stringResource(R.string.saga_absent)
    Surface(
        onClick = {},
        enabled = false,
        modifier = modifier.width(width).aspectRatio(POSTER_SHAPE).clearAndSetSemantics {
            contentDescription = listOfNotNull(title, year?.toString(), absent).joinToString(", ")
            this.focused = focused
        },
        shape = ClickableSurfaceDefaults.shape(HlShapes.artwork),
        scale = FocusStyle.scale(FocusStyle.Fill.Row),
        border = FocusStyle.disabledRing(HlShapes.artwork),
        colors = FocusStyle.still(rest = Color.Transparent, restContent = c.contentTertiary),
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxSize().artworkEdge(HlShapes.artwork, c))
        Column(Modifier.align(Alignment.BottomStart).padding(HlSpace.s)) {
            Box(Modifier.width(HlSpace.l).height(HlSize.hairline).background(c.contentDecorative))
            Spacer(Modifier.height(HlSpace.xs))
            Text(title, style = Hl.type.cardTitle, color = c.contentTertiary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (year != null) {
                Spacer(Modifier.height(HlSpace.xxs))
                Text(year.toString(), style = Hl.type.badge, color = c.contentTertiary)
            }
            // Shown on focus only; at rest the grey conveys it.
            if (focused) {
                Spacer(Modifier.height(HlSpace.xs))
                Text(absent, style = Hl.type.caption, color = c.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
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
            Text(year.toString(), style = Hl.type.badge, color = c.contentTertiary)
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
 * A folder's latest posters in [slots] slots, unfilled ones as empty frames so every strip keeps the same
 * rhythm; with no posters, [name] is set like a one-sheet.
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

/** At most one mark, top right: unwatched episode count, watched check or new-title ember dot. */
@Composable
private fun StatusMark(item: MediaItem, modifier: Modifier) {
    val unplayed = item.user.unplayedCount ?: 0
    val plate = Hl.colors.background.copy(alpha = HlAlpha.VEIL)
    // The card's state description says it in words, so the bare number is not read.
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

/** The check over artwork: projector white for watched, ember for the [choice] in use ([SelectedMark]). */
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

/** The card's state for accessibility ("6 épisodes non vus", "vu", "nouveau, en cours, 40 %"), or null. */
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

/** Ember progress line for titles in progress; on artwork its track is the dark plate ([groundPlate]). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    val track = groundPlate() ?: Hl.colors.content.copy(alpha = HlAlpha.FILL_STRONG)
    Box(modifier.fillMaxWidth().height(HlSize.progress).background(track)) {
        Box(Modifier.fillMaxWidth(progress).height(HlSize.progress).background(Hl.colors.emberLight()))
    }
}

/** A loading card: an empty frame of its footprint, no shimmer; a null [width] fills a grid cell. */
@Composable
fun CardSkeleton(width: Dp?, aspectRatio: Float, modifier: Modifier = Modifier) {
    val sized = if (width != null) modifier.width(width) else modifier
    EmptyFrame(sized.aspectRatio(aspectRatio))
}
