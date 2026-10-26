package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.util.toPx

/** An artwork option at its use's aspect ratio, captioned; the one in use carries [SelectedMark]. */
@Composable
fun ArtworkChoiceCard(
    image: ImageRef?,
    selected: Boolean,
    title: String,
    caption: String?,
    onClick: () -> Unit,
    width: Dp,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    /** Fits a logo inside the card, keeping its transparency; pictures are cropped to fill it. */
    fit: Boolean = false,
    /** Text shown when there is no picture (a title without a logo). */
    placeholder: String? = null,
    onFocused: () -> Unit = {},
) {
    val c = Hl.colors
    CardColumn(modifier.width(width)) {
        ArtworkSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(aspectRatio).semantics { this.selected = selected },
            shape = if (aspectRatio > 1f) HlShapes.wide else HlShapes.artwork,
            onFocused = onFocused,
        ) {
            val url = image?.url(width.toPx())
            when {
                // A failed image shows which choice it was, set like a one-sheet.
                url != null -> ArtworkImage(
                    url,
                    title,
                    Modifier.fillMaxSize().padding(if (fit) HlSpace.m else HlSpace.none),
                    contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                ) { FallbackArtwork(title, null) }
                placeholder != null -> Text(
                    placeholder,
                    style = Hl.type.headline,
                    color = c.content,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(HlSpace.s),
                )
            }
            if (selected) SelectedMark(Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
        }
        Spacer(Modifier.height(HlSpace.s))
        Text(title, style = Hl.type.meta, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) {
            Text(caption, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onFocused: () -> Unit = {},
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
            onFocused = onFocused,
            readAs = name,
            acts = true,
        ) {
            ArtworkImage(
                image?.url(width.toPx()),
                null,
                Modifier.fillMaxSize(),
                contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
                picture = if (logo) Modifier.padding(horizontal = HlSpace.l, vertical = HlSpace.m) else Modifier,
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
 * ("Actuel"), replaced by [busy] while it is being chosen ("Association…").
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
) {
    val c = Hl.colors
    HlPanelButton(onClick, modifier.fillMaxWidth().semantics { selected = mark != null }, lit = mark != null) {
        Row(Modifier.padding(HlInset.tight)) {
            PosterThumb(poster, width = HlSize.posterThumbLarge)
            Spacer(Modifier.width(HlSpace.m))
            Column(Modifier.weight(1f).padding(top = HlSpace.xs, end = HlSpace.s, bottom = HlSpace.xs)) {
                Row(verticalAlignment = Alignment.Top) {
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
        picture = Modifier.clip(HlShapes.thumb).background(groundStepFill()).artworkEdge(HlShapes.thumb, Hl.colors),
    ) { EmptyFrame(Modifier.fillMaxSize(), HlShapes.thumb) }
}

/** The shape of an image at its use: a poster, a backdrop, a logo's box. */
enum class PreviewShape { Poster, Backdrop, Logo }
