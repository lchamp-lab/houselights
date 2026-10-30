package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.colorStops
import app.houselights.ui.util.toPx
import coil.request.ImageRequest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// What a door of home's grid shows (DESIGN.md, Layout): a still, a band of posters, two sagas, a wall out.

// A backdrop's shape.
internal const val WIDE_W = 16
internal const val WIDE_H = 9

/**
 * A door's still (the next episode's): its backdrop at the width of a door of [span] parts, cropped about its upper
 * middle, black rising under its logo or name at its foot.
 */
@Composable
fun DoorStill(item: MediaItem, span: Int, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val show = item.kind == MediaKind.EPISODE
    Box(modifier.fillMaxSize()) {
        val width = doorWidth(span).toPx()
        ArtworkImage(
            item.backdrop?.url(width),
            null,
            Modifier.fillMaxSize(),
            size = IntSize(width, width * WIDE_H / WIDE_W),
        ) { FallbackArtwork(if (show) item.seriesTitle ?: item.title else item.title, null) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(*HlScrim.STILL_FOOT.colorStops(c.background))))
        TitleArt(
            title = if (show) item.seriesTitle ?: item.title else item.title,
            logo = item.logo,
            maxWidth = HomeGridDefaults.stillLogoWidth,
            maxHeight = HomeGridDefaults.stillLogoHeight,
            style = Hl.type.rowTitle,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = HlSpace.s, bottom = HlSpace.xs),
        )
    }
}

/** The request [DoorStill] makes for [item] on a door of [span] parts, as a preload must make it. */
internal fun stillDoorRequest(context: Context, item: MediaItem, span: Int, px: (Dp) -> Int): ImageRequest? {
    val width = px(doorWidth(span))
    return item.backdrop?.let { artworkRequest(context, it.url(width), width, width * WIDE_H / WIDE_W) }
}

/** A band of [items]' posters side by side from the door's start, as tall as its picture, the last cut by its edge. */
@Composable
fun DoorPosters(items: List<MediaItem>, modifier: Modifier = Modifier) {
    val width = HomeGridDefaults.stripPoster.toPx()
    val decoded = IntSize(width, posterDecode(width))
    Layout(
        content = {
            items.forEach { item ->
                ArtworkImage(item.poster?.url(width), null, Modifier.fillMaxSize(), size = decoded) {
                    FallbackArtwork(item.title, item.year)
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val gap = HlSpace.xxs.roundToPx()
        val h = constraints.maxHeight
        val w = (h * HlSize.POSTER_RATIO).roundToInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
        layout(constraints.maxWidth, h) {
            placeables.forEachIndexed { k, p -> (k * (w + gap)).takeIf { it < constraints.maxWidth }?.let { p.place(it, 0) } }
        }
    }
}

/** Two sagas' posters, the first in front, the second behind it in its shade. */
@Composable
fun DoorSagas(items: List<MediaItem>, modifier: Modifier = Modifier) {
    val width = HomeGridDefaults.sagaPoster.toPx()
    val decoded = IntSize(width, posterDecode(width))
    val shade = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(SAGA, SAGA, SAGA, 1f) }) }
    Box(modifier.fillMaxSize()) {
        items.getOrNull(1)?.let { back ->
            SagaPoster(back, width, decoded, shade, Modifier.offset(HomeGridDefaults.sagaBack, HomeGridDefaults.sagaBackTop))
        }
        items.firstOrNull()?.let { front ->
            SagaPoster(front, width, decoded, null, Modifier.offset(HomeGridDefaults.sagaFront, HomeGridDefaults.sagaFrontTop))
        }
    }
}

private const val SAGA = HlScrim.SAGA_BEHIND

@Composable
private fun SagaPoster(item: MediaItem, px: Int, decoded: IntSize, filter: ColorFilter?, modifier: Modifier = Modifier) {
    ArtworkImage(
        item.poster?.url(px),
        null,
        modifier.width(HomeGridDefaults.sagaPoster).height(HomeGridDefaults.sagaPoster / HlSize.POSTER_RATIO).clip(HlShapes.thumb),
        size = decoded,
        filter = filter,
    ) { FallbackArtwork(item.title, item.year) }
}

/**
 * A wall of [items]' small posters, out, from the door's top, fading into its glass at its foot: what a search reaches.
 * It fills the door, [items] taken again in turn when fewer than it holds ([wallThumbs]).
 */
@Composable
fun DoorWall(items: List<MediaItem>, modifier: Modifier = Modifier) {
    val width = HomeGridDefaults.wallThumb.toPx()
    val decoded = IntSize(width, posterDecode(width))
    val out = remember {
        val m = ColorMatrix().apply { setToSaturation(HlScrim.WALL_THUMB_SATURATION) }
        m *= ColorMatrix().apply { setToScale(WALL, WALL, WALL, 1f) }
        ColorFilter.colorMatrix(m)
    }
    val c = Hl.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val shown = remember(items, maxWidth, maxHeight) { items.cycledTo(wallThumbs(maxWidth, maxHeight)) }
        WallThumbs(shown, width, decoded, out)
        // The wall fades into the door's glass towards its band.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(*HlScrim.WALL_FOOT.colorStops(c.glass))))
    }
}

@Composable
private fun WallThumbs(items: List<MediaItem>, width: Int, decoded: IntSize, out: ColorFilter) {
    Layout(
        content = {
            items.forEach { item ->
                ArtworkImage(item.poster?.url(width), null, Modifier.clip(HlShapes.thumb), size = decoded, filter = out) {
                    Box(Modifier.fillMaxSize().background(Hl.colors.content.copy(alpha = HlAlpha.FILL_FAINT)))
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val gap = HlSpace.xxs.roundToPx()
        val thumb = HomeGridDefaults.wallThumb.roundToPx()
        val columns = ((constraints.maxWidth + gap) / (thumb + gap)).coerceAtLeast(1)
        val w = (constraints.maxWidth - gap * (columns - 1)) / columns
        val h = (w / HlSize.POSTER_RATIO).roundToInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { k, p ->
                val y = (k / columns) * (h + gap)
                if (y < constraints.maxHeight) p.place((k % columns) * (w + gap), y)
            }
        }
    }
}

private const val WALL = HlScrim.WALL_THUMB_LIGHT

/** [count] of these, from the first, taken again in turn when there are fewer; none when there are none. */
internal fun <T> List<T>.cycledTo(count: Int): List<T> = if (isEmpty()) emptyList() else List(count) { this[it % size] }

/** How many small posters [DoorWall] lays across [width] and down [height] (dp). */
internal fun wallThumbs(width: Dp, height: Dp): Int {
    val gap = HlSpace.xxs.value
    val thumb = HomeGridDefaults.wallThumb.value
    val columns = floor((width.value + gap) / (thumb + gap)).toInt().coerceAtLeast(1)
    val w = (width.value - gap * (columns - 1)) / columns
    val rows = ceil((height.value + gap) / (w / HlSize.POSTER_RATIO + gap)).toInt()
    return columns * rows
}
