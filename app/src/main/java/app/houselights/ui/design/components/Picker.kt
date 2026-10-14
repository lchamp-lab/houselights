package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.toPx
import coil.compose.AsyncImage

/**
 * One artwork to choose from, at the shape it is used in, with a caption saying what it is.
 * The one in use carries the tungsten check.
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
            Modifier.fillMaxWidth().aspectRatio(aspectRatio),
            focusedScale = HlFocus.CARD_SCALE,
            onFocused = onFocused,
        ) {
            val url = image?.url(width.toPx())
            when {
                url != null -> ArtworkImage(
                    url,
                    title,
                    Modifier.fillMaxSize().padding(if (fit) HlSpace.m else HlSpace.none),
                    contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                )
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

/** The tungsten check of the choice in use. */
@Composable
fun SelectedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(HlSize.mark).clip(HlShapes.avatar).background(Hl.colors.tungsten), contentAlignment = Alignment.Center) {
        Icon(HlIcons.Check, contentDescription = null, tint = Hl.colors.background, modifier = Modifier.size(HlSize.markIcon))
    }
}

/**
 * A title found by a search: poster, name, facts (year, original title), a short overview.
 * [mark] (the title in use) sits next to the name.
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
) {
    val c = Hl.colors
    HlPanelButton(onClick, modifier.fillMaxWidth()) {
        Row(Modifier.padding(HlInset.tight)) {
            PosterThumb(poster)
            Spacer(Modifier.width(HlSpace.m))
            Column(Modifier.weight(1f).padding(vertical = HlSpace.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Hl.type.lead, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (mark != null) {
                        Spacer(Modifier.width(HlSpace.s))
                        Badge(mark, lit = true)
                    }
                }
                Spacer(Modifier.height(HlSpace.xxs))
                FactRow(facts)
                if (overview != null) {
                    Spacer(Modifier.height(HlSpace.xs))
                    Text(overview, style = Hl.type.caption, color = c.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A small poster in a list, at [width]; an empty frame when there is no artwork. */
@Composable
fun PosterThumb(poster: ImageRef?, modifier: Modifier = Modifier, width: Dp = HlSize.posterThumb) {
    Box(modifier.width(width).aspectRatio(2f / 3f).clip(HlShapes.artwork).background(Hl.colors.surfaceRaised)) {
        poster?.url(width.toPx())?.let { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().border(HlSize.hairline, Hl.colors.content.copy(alpha = HlAlpha.ARTWORK_EDGE), HlShapes.artwork))
    }
}
