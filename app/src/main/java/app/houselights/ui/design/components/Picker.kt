package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
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
                url != null -> AsyncImage(
                    model = url,
                    contentDescription = title,
                    contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (fit) HlSpace.m else 0.dp),
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
        Text(title, style = Hl.type.label, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) {
            Text(caption, style = Hl.type.caption, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The tungsten check of the choice in use. */
@Composable
fun SelectedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(26.dp).clip(HlShapes.avatar).background(Hl.colors.tungsten), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = Hl.colors.background, modifier = Modifier.size(16.dp))
    }
}

/**
 * A title found by a search: poster, name, year and original title, a short overview. Styled
 * like [HlListItem]; [mark] (the title in use) sits next to the name.
 */
@Composable
fun HlResultItem(
    poster: ImageRef?,
    title: String,
    meta: String?,
    overview: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mark: String? = null,
) {
    val c = Hl.colors
    val posterWidth = 64.dp
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(HlShapes.panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(HlSize.hairline, c.outline.copy(alpha = 0.6f)), shape = HlShapes.panel),
            focusedBorder = Border(BorderStroke(HlSize.focusBorder, c.focus), shape = HlShapes.panel),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface.copy(alpha = 0.72f),
            contentColor = c.content,
            focusedContainerColor = c.surfaceRaised,
            focusedContentColor = c.content,
            pressedContainerColor = c.surfaceRaised,
            pressedContentColor = c.content,
        ),
    ) {
        Row(Modifier.padding(HlSpace.s)) {
            Box(Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(HlShapes.artwork).background(c.surfaceRaised)) {
                poster?.url(posterWidth.toPx())?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(HlSpace.m))
            Column(Modifier.weight(1f).padding(vertical = HlSpace.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Hl.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (mark != null) {
                        Spacer(Modifier.width(HlSpace.s))
                        Badge(mark, lit = true)
                    }
                }
                if (meta != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(meta, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (overview != null) {
                    Spacer(Modifier.height(HlSpace.xs))
                    Text(overview, style = Hl.type.bodySmall, color = c.contentTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
