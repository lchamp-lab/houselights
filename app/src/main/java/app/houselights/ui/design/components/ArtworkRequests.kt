package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.ui.unit.IntSize
import app.houselights.core.MediaItem
import app.houselights.ui.design.HlSize
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlin.math.roundToInt

// What a picture asks the loader for once laid out, which its preload asks too ([PicturesAhead]), so it hits memory.

/**
 * What an [ArtworkImage] asks the loader for: [url], at [size] (px) when given ([artworkRequest]); with the loader's
 * fade, unless its caller brings the picture in itself ([fades] false).
 */
internal fun artworkModel(context: Context, url: String, size: IntSize?, fades: Boolean): Any = when {
    fades -> size?.let { artworkRequest(context, url, it.width, it.height) } ?: url
    size != null -> artworkRequest(context, url, size.width, size.height).newBuilder().crossfade(false).build()
    else -> ImageRequest.Builder(context).data(url).crossfade(false).build()
}

/** The request a [PosterCard] [widthPx] wide makes once laid out: its poster at that size, cropped. */
internal fun posterRequest(context: Context, item: MediaItem, widthPx: Int): ImageRequest? =
    item.poster?.let { artworkRequest(context, it.url(widthPx), widthPx, posterDecode(widthPx)) }

/**
 * The request of a picture laid out [widthPx] by [heightPx] ([ArtworkImage], a door, a backdrop) for [url], cropped to
 * that shape and never decoded larger than its file.
 */
internal fun artworkRequest(context: Context, url: String, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context).data(url).size(widthPx, heightPx).scale(Scale.FILL).precision(Precision.INEXACT).build()

/** The height (px) a poster [px] wide is decoded at, the same for its display and its preload. */
internal fun posterDecode(px: Int): Int = (px / HlSize.POSTER_RATIO).roundToInt()
