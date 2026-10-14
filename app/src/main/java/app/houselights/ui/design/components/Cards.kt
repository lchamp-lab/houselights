package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.Person
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.toPx
import coil.compose.AsyncImage

/** True while one card of the enclosing row or grid has focus: the others step back. */
val LocalGroupFocused = compositionLocalOf { false }

/** A horizontal row of cards with an optional header; restores focus to the last card. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaRow(
    header: String?,
    modifier: Modifier = Modifier,
    spacing: Dp = HlSpace.m,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    Column(modifier.revealOnFocus()) {
        if (header != null) SectionHeader(header, Modifier.padding(start = HlSpace.safeH, bottom = HlSpace.xxs))
        CompositionLocalProvider(LocalGroupFocused provides rowFocused) {
            LazyRow(
                state = state,
                // Observe the row's focus group itself, so hasFocus covers every card.
                modifier = Modifier
                    .onFocusChanged { rowFocused = it.hasFocus }
                    .focusRestorer(),
                contentPadding = PaddingValues(horizontal = HlSpace.safeH, vertical = HlSpace.m),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

/**
 * Focus treatment shared by every piece of artwork: it grows, gets a thin projector-white
 * outline and glows with the ambient light, while its neighbours dim.
 */
@Composable
fun ArtworkSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = HlShapes.artwork,
    focusedScale: Float = HlFocus.ARTWORK_SCALE,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val dim by animateFloatAsState(
        if (LocalGroupFocused.current && !focused) HlFocus.SIBLING_DIM else 0f,
        tween(HlMotion.STANDARD, easing = HlMotion.standard),
        label = "dim",
    )
    val c = Hl.colors
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(HlSize.focusBorder, c.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(LocalAmbient.current, HlFocus.glow)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface,
            focusedContainerColor = c.surface,
            pressedContainerColor = c.surface,
        ),
        interactionSource = interaction,
    ) {
        content()
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

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
    val px = (width ?: HlSize.poster * 1.3f).toPx()
    ArtworkSurface(onClick, sized.aspectRatio(2f / 3f), onFocused = onFocused) {
        val url = item.poster?.url(px)
        if (url == null) FallbackTitle(item.title) else Artwork(url, item.title)
        StatusMark(item, Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
        ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter))
    }
}

/** 16:9 still or thumb, with an optional caption below. */
@Composable
fun LandscapeCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = HlSize.landscape,
    title: String? = null,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
) {
    Column(modifier.width(width)) {
        ArtworkSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            focusedScale = HlFocus.CARD_SCALE,
            onFocused = onFocused,
        ) {
            val url = item.landscape?.url(width.toPx())
            if (url == null) FallbackTitle(title ?: item.title) else Artwork(url, item.title)
            StatusMark(item, Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
            ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter))
        }
        if (title != null) {
            Spacer(Modifier.height(HlSpace.s))
            Text(title, style = Hl.type.label, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (subtitle != null) {
            Text(subtitle, style = Hl.type.caption, color = Hl.colors.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Cast member: round portrait, name and role. Focusable so the row can scroll. */
@Composable
fun PersonCard(person: Person, modifier: Modifier = Modifier) {
    val px = HlSize.avatar.toPx()
    Column(modifier.width(HlSize.avatar + 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtworkSurface(onClick = {}, modifier = Modifier.size(HlSize.avatar), shape = HlShapes.avatar) {
            Text(
                person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                style = Hl.type.title,
                color = Hl.colors.contentTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
            person.image?.url(px)?.let { Artwork(it, person.name, Modifier.clip(HlShapes.avatar)) }
        }
        Spacer(Modifier.height(HlSpace.s))
        Text(person.name, style = Hl.type.caption, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        person.role?.let {
            Text(it, style = Hl.type.caption, color = Hl.colors.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Artwork(url: String, description: String, modifier: Modifier = Modifier) {
    AsyncImage(model = url, contentDescription = description, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize())
}

/** No artwork: the title, set like a plain one-sheet. */
@Composable
private fun BoxScope.FallbackTitle(title: String) {
    Text(
        title,
        style = Hl.type.title.copy(fontFamily = Hl.type.headline.fontFamily),
        color = Hl.colors.contentSecondary,
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.Center).padding(HlSpace.s),
    )
}

/** Watched check, or the number of unwatched episodes of a show. */
@Composable
private fun StatusMark(item: MediaItem, modifier: Modifier) {
    val unplayed = item.user.unplayedCount ?: 0
    val plate = Color.Black.copy(alpha = 0.72f)
    when {
        item.user.played -> Box(
            modifier.size(24.dp).clip(HlShapes.avatar).background(plate),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Hl.colors.content, modifier = Modifier.size(15.dp))
        }
        unplayed > 0 -> Box(modifier.clip(HlShapes.control).background(plate).padding(horizontal = HlSpace.xs, vertical = 3.dp)) {
            Text("$unplayed", style = Hl.type.badge, color = Hl.colors.content)
        }
    }
}

/** Tungsten progress line, only for titles in progress. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    Box(modifier.fillMaxWidth().height(HlSize.progress).background(Color.White.copy(alpha = 0.22f))) {
        Box(Modifier.fillMaxWidth(progress).height(HlSize.progress).background(Hl.colors.tungsten))
    }
}

/** Placeholder card while a row loads: same footprint, no shimmer. */
@Composable
fun CardSkeleton(width: Dp, aspectRatio: Float, modifier: Modifier = Modifier) {
    Box(modifier.width(width).aspectRatio(aspectRatio).clip(HlShapes.artwork).background(Hl.colors.surface))
}
