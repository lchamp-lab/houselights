package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.toPx
import app.houselights.core.ImageRef
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import androidx.compose.ui.res.stringResource

/** Row and section header, sentence case. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.lead, color = Hl.colors.content, modifier = modifier)
}

/** Screen title (library name, settings). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.headline, color = Hl.colors.content, modifier = modifier)
}

/**
 * The name, HOUSELIGHTS, as the brand draws it (`wordmark.xml`): expanded capitals of Archivo whose
 * weight goes down letter by letter, from 900 to 350, as the house lights go down. Drawn, never set,
 * so its letters, weights and spacing stay the brand's; in projector white, flat, with no halo or
 * shadow: the light in the room comes from the films. [height] is how tall its capitals stand
 * (`HlSize.wordmark` in About, `HlSize.wordmarkHero` over the welcome wall); its round letters pass
 * them a little, as type does, inside its box.
 */
@Composable
fun Wordmark(height: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.wordmark),
        contentDescription = stringResource(R.string.wordmark),
        colorFilter = ColorFilter.tint(Hl.colors.content),
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height * WORDMARK_HEIGHT / WORDMARK_CAPITALS).aspectRatio(WORDMARK_WIDTH / WORDMARK_HEIGHT),
    )
}

/** The wordmark's drawing (`wordmark.xml`): its capitals 100 units tall, its round letters passing them above and below. */
private const val WORDMARK_WIDTH = 1196.847f
private const val WORDMARK_HEIGHT = 103.79f
private const val WORDMARK_CAPITALS = 100f

/**
 * What a [TitleArt] laid out [maxWidthPx] by [maxHeightPx] at most asks Coil for once laid out: its
 * [logo], fitted in that room.
 */
internal fun titleArtRequest(context: Context, logo: ImageRef, maxWidthPx: Int, maxHeightPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(logo.url(maxWidthPx))
        .size(maxWidthPx, maxHeightPx)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .build()

/** The title's clear logo, or the title set in condensed type when there is none. */
@Composable
fun TitleArt(
    title: String,
    logo: ImageRef?,
    maxWidth: Dp,
    maxHeight: Dp,
    style: TextStyle = Hl.type.display,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    val url = logo?.url(maxWidth.toPx())
    var failed by remember(url) { mutableStateOf(false) }
    if (url != null && !failed) {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
        )
    } else {
        Text(
            title,
            style = style,
            color = Hl.colors.content,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.widthIn(max = maxWidth + HlSpace.xxl * 3),
        )
    }
}
