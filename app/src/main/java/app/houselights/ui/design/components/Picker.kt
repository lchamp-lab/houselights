package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * One artwork to choose from, at the shape it is used in, with a caption saying what it is.
 * The one in use carries the check of your choice, in ember ([SelectedMark]).
 */
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
    /** Logos keep their transparent outline on the plain card; pictures fill it. */
    fit: Boolean = false,
    /** Set in type when there is no picture (a title without logo). */
    placeholder: String? = null,
    onFocused: () -> Unit = {},
) {
    val c = Hl.colors
    Column(modifier.width(width)) {
        ArtworkSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(aspectRatio).semantics { this.selected = selected },
            lift = Lift.Card,
            onFocused = onFocused,
        ) {
            val url = image?.url(width.toPx())
            when {
                // An image that fails says which choice it was, set like a one-sheet.
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
            Text(caption, style = Hl.type.caption, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The choice in use: the watched mark's check, in ember, on the same dark plate. */
@Composable
fun SelectedMark(modifier: Modifier = Modifier) {
    CheckMark(modifier, choice = true)
}

/**
 * A title found by a search, set like a small one-sheet: its poster, its name in the voice of the
 * poster, facts (year, original title), three lines of overview. The title in use ([mark]) is
 * edged in ember and says so next to its name, with the light of a state ("Actuel"). While it
 * is being chosen, [busy] says so there instead ("Association…").
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
                if (overview != null) {
                    Spacer(Modifier.height(HlSpace.xs))
                    Text(overview, style = Hl.type.caption, color = c.contentSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A small poster in a list, at [width]; an empty frame when there is none, or it fails to load. */
@Composable
fun PosterThumb(poster: ImageRef?, modifier: Modifier = Modifier, width: Dp = HlSize.posterThumb) {
    ArtworkImage(
        poster?.url(width.toPx()),
        description = null,
        modifier = modifier.width(width).aspectRatio(2f / 3f),
        // Loading, the poster's place shows on its ground, edged as artwork at rest.
        picture = Modifier.clip(HlShapes.artwork).background(groundStepFill()).artworkEdge(HlShapes.artwork, Hl.colors),
    ) { EmptyFrame(Modifier.fillMaxSize()) }
}
