package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.fadeOutsideSafeArea
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.textRoom
import app.houselights.ui.util.toPx
import kotlin.math.roundToInt

/**
 * An artwork option on the contact sheet (« la planche-contact », DESIGN.md, Layout), [width] wide at its use's
 * [aspectRatio], read as [title], the name a failed picture is set in type with; the one in use carries [SelectedMark].
 * Under it nothing, the section it lies in saying its language, but a [name] in `cardName` (« Automatique », a saga's
 * film) and its [detail] in secondary `meta` (that film's year), [captionWidth] wide, as a person's name is wider than
 * their portrait: its column's gap with it, or the next column too when that one has no words. A logo is fitted inside,
 * on the ground's step; without a picture, the title set in type ([placeholder]).
 */
@Composable
fun ArtworkChoiceCard(
    image: ImageRef?,
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    width: Dp,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    name: String? = null,
    detail: String? = null,
    captionWidth: Dp = width,
    /** Fits a logo inside the card, keeping its transparency; pictures are cropped to fill it. */
    fit: Boolean = false,
    /** Text shown when there is no picture (a title without a logo). */
    placeholder: String? = null,
    onFocus: () -> Unit = {},
) {
    val c = Hl.colors
    CardColumn(modifier.width(width)) {
        ArtworkSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(aspectRatio).semantics { this.selected = selected },
            shape = if (aspectRatio > 1f) HlShapes.wide else HlShapes.artwork,
            onFocus = onFocus,
        ) {
            val url = image?.url(width.toPx())
            when {
                // A failed image shows which choice it was, set like a one-sheet.
                url != null -> ArtworkImage(
                    url,
                    title,
                    Modifier.fillMaxSize().padding(if (fit) LogoInset else PaddingValues()),
                    contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                ) { FallbackArtwork(title, null) }
                placeholder != null -> Text(
                    placeholder,
                    style = Hl.type.cardTitle,
                    color = c.content,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(HlSpace.s),
                )
            }
            if (selected) SelectedMark(Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
        }
        if (name != null || detail != null) {
            Spacer(Modifier.height(HlSpace.xs))
            // Past its column when the next has no words; faded across the safe area's margin, as a card's name is.
            val words = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(captionWidth)
            ChoiceWords(name, detail, words.fadeOutsideSafeArea())
        }
    }
}

/** A choice's name in `cardName`, white, on two lines at most, then its detail in secondary `meta` (a film's year). */
@Composable
private fun ChoiceWords(name: String?, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (name != null) Text(name, style = Hl.type.cardName, color = Hl.colors.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Text(detail, style = Hl.type.meta, color = Hl.colors.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A logo's room inside its choice: the choice small, its box shallow. */
private val LogoInset = PaddingValues(horizontal = HlSpace.m, vertical = HlSpace.s)

/**
 * The print (« l'épreuve ») of the contact sheet: the choice with focus shown large beside it, at most [width] wide at
 * [aspectRatio] and as tall as its column leaves it (a larger text size takes some, focus never), never focused, its
 * picture decoded at [width]. The one it showed stays whole until the next is decoded, then is cut: nothing blinks,
 * nothing fades, nothing grows. The choice in use carries [SelectedMark], as its card does. Under it one line, its room
 * kept when empty so nothing moves as focus does, as a result's under its row ([ResultName]): its [name] in `cardName`,
 * then its [detail] in secondary `meta` on its baseline (« Automatique » and the film whose picture it is, a saga's
 * film and its year), the longer of the two giving way.
 */
@Composable
fun ArtworkPrint(
    image: ImageRef?,
    selected: Boolean,
    width: Dp,
    aspectRatio: Float,
    name: String?,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    val px = width.toPx()
    val decoded = IntSize(px, (px / aspectRatio).roundToInt())
    val url = image?.url(px)
    // The picture last shown, under the next one until that one is decoded.
    var shown by remember { mutableStateOf<String?>(null) }
    Column(modifier.width(width)) {
        Box(
            Modifier
                .weight(1f, fill = false)
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
                .clip(HlShapes.artwork)
                .background(groundStepFill())
                .artworkEdge(HlShapes.artwork, Hl.colors),
        ) {
            val layers = if (url == null) emptyList() else listOfNotNull(shown?.takeIf { it != url }, url)
            layers.forEach { layer ->
                key(layer) { ArtworkImage(layer, null, Modifier.fillMaxSize(), size = decoded, onShow = { shown = layer }) }
            }
            if (selected) SelectedMark(Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
        }
        Spacer(Modifier.height(HlSpace.xs))
        Row(Modifier.fillMaxWidth().height(textRoom(Hl.type.cardName))) {
            // The shorter keeps its width: a film's year after its name, a film's name after « Automatique ».
            val nameGivesWay = name.orEmpty().length >= detail.orEmpty().length
            if (name != null) {
                Text(
                    name,
                    style = Hl.type.cardName,
                    color = Hl.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().then(if (nameGivesWay) Modifier.weight(1f, fill = false) else Modifier),
                )
            }
            if (detail != null) {
                if (name != null) Spacer(Modifier.width(HlSpace.s))
                Text(
                    detail,
                    style = Hl.type.meta,
                    color = Hl.colors.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().then(if (nameGivesWay) Modifier else Modifier.weight(1f, fill = false)),
                )
            }
        }
    }
}

/**
 * One of a title's images in a gallery (DESIGN.md, Secondary screens), [width] wide at its shape of use: a poster at
 * 2:3, a backdrop at 16:9, a logo fitted on a neutral step, in a box at [aspectRatio]; its [name] under it in
 * `cardName`, white while focused. Focus frames it and lights it in the film's glow.
 */
@Composable
fun GalleryTile(
    image: ImageRef?,
    shape: PreviewShape,
    name: String,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    aspectRatio: Float = shape.ratio,
    onFocus: () -> Unit = {},
) {
    val c = Hl.colors
    var focused by remember { mutableStateOf(false) }
    val artwork = if (shape == PreviewShape.Backdrop) HlShapes.wide else HlShapes.artwork
    val logo = shape == PreviewShape.Logo
    CardColumn(modifier.width(width).onFocusChanged { focused = it.hasFocus }) {
        // Read once, by its name, as what OK opens.
        ArtworkSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(aspectRatio),
            shape = artwork,
            onFocus = onFocus,
            readAs = name,
            acts = true,
        ) {
            ArtworkImage(
                image?.url(width.toPx()),
                null,
                Modifier.fillMaxSize(),
                contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
                pictureModifier = if (logo) Modifier.padding(horizontal = HlSpace.l, vertical = HlSpace.m) else Modifier,
            ) { EmptyFrame(Modifier.fillMaxSize(), artwork) }
        }
        Spacer(Modifier.height(HlSpace.xs))
        Text(
            name,
            style = Hl.type.cardName,
            color = if (focused) c.content else c.contentSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A title's images side by side ([GalleryTile]), [HlSpace.m] apart: while one has focus, the others step back. */
@Composable
fun Gallery(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalGroupFocused provides focused) {
        Row(modifier.onFocusChanged { focused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(HlSpace.m), content = content)
    }
}

/** The shape of an image's use, width to height; a logo's box beside a poster. */
private val PreviewShape.ratio: Float
    get() = when (this) {
        PreviewShape.Poster -> HlSize.POSTER_RATIO
        PreviewShape.Backdrop -> HlSize.PREVIEW_RATIO
        PreviewShape.Logo -> HlSize.LOGO_BOX_RATIO
    }

/** The choice in use: the watched mark's check, in ember, on the same dark plate. */
@Composable
fun SelectedMark(modifier: Modifier = Modifier) {
    CheckMark(modifier, choice = true)
}

/**
 * A search result set like a small one-sheet: poster, name, facts, a [note] on a line of its own, which no fact gives way
 * to (the title it may be, "Peut-être « Dune » (2021)"), and overview; the title in use is edged in ember with [mark]
 * ("Actuel"), replaced by [busy] while it is being chosen ("Association…"). With options ([onLongClick]: Menu, or OK
 * held), the Menu key says so at the end of its name's line while it has focus, its room kept so nothing moves.
 */
@Composable
fun HlResultItem(
    poster: ImageRef?,
    title: String,
    facts: List<String?>,
    overview: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mark: String? = null,
    busy: String? = null,
    note: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val c = Hl.colors
    HlPanelButton(
        onClick,
        modifier.fillMaxWidth().semantics { selected = mark != null },
        lit = mark != null,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
    ) { focused ->
        Row(Modifier.padding(HlInset.tight)) {
            PosterThumb(poster, width = HlSize.posterThumbLarge)
            Spacer(Modifier.width(HlSpace.m))
            Column(Modifier.weight(1f).padding(top = HlSpace.xs, end = HlSpace.s, bottom = HlSpace.xs)) {
                Row(verticalAlignment = Alignment.Top) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                        Text(
                            title,
                            style = Hl.type.cardTitle,
                            color = c.content,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (busy != null || mark != null) Spacer(Modifier.width(HlSpace.s))
                        when {
                            busy != null -> HlStatus(StatusState.Pending, busy)
                            mark != null -> HlStatus(StatusState.On, mark)
                        }
                    }
                    // Its room kept, so nothing moves as focus comes and goes.
                    if (onLongClick != null) {
                        Spacer(Modifier.width(HlSpace.s))
                        Box(Modifier.size(HlSize.menuKeyWidth, HlSize.menuKeyHeight)) { if (focused) MenuKey() }
                    }
                }
                Spacer(Modifier.height(HlSpace.xxs))
                FactRow(facts)
                if (note != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(note, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (overview != null) {
                    Spacer(Modifier.height(HlSpace.xs))
                    Text(overview, style = Hl.type.meta, color = c.contentSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * A small poster in a list, at [width], or as tall as it is given when [width] is null (a sheet's header); an
 * empty frame when there is none or it fails to load.
 */
@Composable
fun PosterThumb(poster: ImageRef?, modifier: Modifier = Modifier, width: Dp? = HlSize.posterThumb) {
    val sized = if (width != null) {
        modifier.width(width).aspectRatio(HlSize.POSTER_RATIO)
    } else {
        modifier.fillMaxHeight().aspectRatio(HlSize.POSTER_RATIO, matchHeightConstraintsFirst = true)
    }
    ArtworkImage(
        poster?.url((width ?: HlSize.posterThumb).toPx()),
        description = null,
        modifier = sized,
        // While loading, the poster's slot shows its ground, edged like artwork at rest.
        pictureModifier = Modifier.clip(HlShapes.thumb).background(groundStepFill()).artworkEdge(HlShapes.thumb, Hl.colors),
    ) { EmptyFrame(Modifier.fillMaxSize(), HlShapes.thumb) }
}

/** The shape of an image at its use: a poster, a backdrop, a logo's box. */
enum class PreviewShape { Poster, Backdrop, Logo }
