package app.houselights.ui.design.components

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.Person
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.RowScroll
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
            RowScroll {
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
}

/**
 * Focus treatment shared by every piece of artwork: it grows, gets a thin projector-white
 * outline and glows with the ambient light, while its neighbours dim. At rest, a faint edge keeps
 * dark artwork from dissolving into the black room.
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
    val recede = animateFloatAsState(
        if (LocalGroupFocused.current && !focused) 1f else 0f,
        tween(HlMotion.STANDARD, easing = HlMotion.standard),
        label = "recede",
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
        // Read while drawing, or by the image alone: stepping back never recomposes the row.
        CompositionLocalProvider(LocalArtworkRecede provides { recede.value }) { content() }
        Box(Modifier.fillMaxSize().border(HlSize.hairline, c.content.copy(alpha = HlAlpha.ARTWORK_EDGE), shape))
        Box(Modifier.fillMaxSize().drawBehind { drawRect(c.background.copy(alpha = recede.value * HlFocus.SIBLING_DIM)) })
    }
}

/** How far the artwork of a card steps back: 0 in front, 1 behind the focused card. */
val LocalArtworkRecede = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Artwork as a card shows it. When its card steps back it loses part of its colour
 * ([HlFocus.SIBLING_SATURATION]) through the image's own colour filter: no offscreen layer per
 * card, and only this image recomposes while it steps back.
 */
@Composable
fun ArtworkImage(
    url: String,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val recede = LocalArtworkRecede.current()
    val filter = remember(recede) {
        if (recede <= 0f) null
        else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - recede * (1f - HlFocus.SIBLING_SATURATION)) })
    }
    AsyncImage(model = url, contentDescription = description, contentScale = contentScale, colorFilter = filter, modifier = modifier)
}

/** Width a poster is asked for: its own, or a little more than a row's when it fills a grid cell. */
@Composable
fun posterPx(width: Dp? = null): Int = (width ?: HlSize.poster * 1.3f).toPx()

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
    ArtworkSurface(onClick, sized.aspectRatio(2f / 3f), onFocused = onFocused) {
        val url = item.poster?.url(px)
        if (url == null) FallbackArtwork(item.title, item.year) else ArtworkImage(url, item.title, Modifier.fillMaxSize())
        StatusMark(item, Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
        ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter), onArtwork = true)
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
    Column(modifier.width(width)) {
        ArtworkSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            focusedScale = HlFocus.CARD_SCALE,
            onFocused = onFocused,
        ) {
            val url = item.landscape?.url(width.toPx())
            if (url == null) {
                FallbackArtwork(title ?: item.title, item.year.takeIf { title == null })
            } else {
                ArtworkImage(url, item.title, Modifier.fillMaxSize())
            }
            StatusMark(item, Modifier.align(Alignment.TopEnd).padding(HlSpace.xs))
            ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter), onArtwork = true)
        }
        if (title != null) {
            Spacer(Modifier.height(HlSpace.s))
            Row {
                if (number != null) {
                    Text(number, style = Hl.type.meta, color = Hl.colors.contentTertiary, modifier = Modifier.alignByBaseline())
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
            FactRow(facts, style = Hl.type.detail, color = Hl.colors.contentTertiary)
        }
    }
}

/** Cast member: round portrait, name over two lines at most, and role. Focusable so the row can scroll. */
@Composable
fun PersonCard(person: Person, modifier: Modifier = Modifier) {
    val px = HlSize.avatar.toPx()
    Column(modifier.width(HlSize.personColumn), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtworkSurface(onClick = {}, modifier = Modifier.size(HlSize.avatar), shape = HlShapes.avatar) {
            Text(
                person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                style = Hl.type.lead,
                color = Hl.colors.contentTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
            person.image?.url(px)?.let { ArtworkImage(it, person.name, Modifier.fillMaxSize().clip(HlShapes.avatar)) }
        }
        Spacer(Modifier.height(HlSpace.s))
        Text(
            person.name,
            style = Hl.type.meta,
            color = Hl.colors.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        person.role?.let {
            Text(it, style = Hl.type.caption, color = Hl.colors.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * No artwork: the title set like a one-sheet, in the voice of the poster, low on the card under a
 * short rule, with its year in the technical voice.
 */
@Composable
private fun BoxScope.FallbackArtwork(title: String, year: Int?) {
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
 * One mark at most, at the top right, on a dark plate: the number of unwatched episodes of a show,
 * the line check of a title watched, or the tungsten dot of a title new to the library. Progress
 * has its own line at the foot of the card.
 */
@Composable
private fun StatusMark(item: MediaItem, modifier: Modifier) {
    val unplayed = item.user.unplayedCount ?: 0
    val plate = Hl.colors.background.copy(alpha = HlAlpha.PLATE)
    when {
        unplayed > 0 -> Box(modifier.clip(HlShapes.control).background(plate).padding(horizontal = HlSpace.xs, vertical = HlControl.badgePaddingV)) {
            Text("$unplayed", style = Hl.type.badge, color = Hl.colors.content)
        }
        item.user.played -> Box(modifier.size(HlSize.mark).clip(HlShapes.avatar).background(plate), contentAlignment = Alignment.Center) {
            Icon(HlIcons.Check, contentDescription = null, tint = Hl.colors.content, modifier = Modifier.size(HlSize.markIcon))
        }
        item.isNew() -> Box(modifier.size(HlSize.mark).clip(HlShapes.avatar).background(plate), contentAlignment = Alignment.Center) {
            Box(Modifier.size(HlSize.dot).clip(HlShapes.avatar).background(Hl.colors.tungsten))
        }
    }
}

/**
 * Tungsten progress line, only for titles in progress. On artwork ([onArtwork]: the foot of a
 * card), its track is a dark plate, so the line reads over the lightest image.
 */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    if (progress <= 0f) return
    val track = if (onArtwork) Hl.colors.background.copy(alpha = HlAlpha.PLATE) else Hl.colors.content.copy(alpha = HlAlpha.TRACK)
    Box(modifier.fillMaxWidth().height(HlSize.progress).background(track)) {
        Box(Modifier.fillMaxWidth(progress).height(HlSize.progress).background(Hl.colors.tungsten))
    }
}

/** Placeholder card while a row loads: same footprint, no shimmer. */
@Composable
fun CardSkeleton(width: Dp, aspectRatio: Float, modifier: Modifier = Modifier) {
    Box(modifier.width(width).aspectRatio(aspectRatio).clip(HlShapes.artwork).background(Hl.colors.surface))
}
