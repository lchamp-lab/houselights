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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import app.houselights.ui.design.HlType
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

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
 * What a [TitleArt] [maxWidthPx] by [maxHeightPx] at most asks Coil for: its [logo] at a constant
 * weight in that room ([EvenLogo]), decoded with room to spare so that a logo grown to its weight
 * is never enlarged. Anything asking for it ahead asks this, or it is not found in memory.
 */
internal fun titleArtRequest(context: Context, logo: ImageRef, maxWidthPx: Int, maxHeightPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(logo.url(maxWidthPx))
        .size((maxWidthPx * LOGO_DECODE).roundToInt(), (maxHeightPx * LOGO_DECODE).roundToInt())
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .transformations(EvenLogo(maxWidthPx, maxHeightPx))
        .build()

/** How much larger than its room a logo is decoded, to be set at its weight from its own pixels. */
private const val LOGO_DECODE = 1.5f

/**
 * The title's clear logo, or the title set in condensed type when there is none; [measured], set to
 * its measure as a poster sets it (a detail page: [MeasuredTitle]).
 */
@Composable
fun TitleArt(
    title: String,
    logo: ImageRef?,
    maxWidth: Dp,
    maxHeight: Dp,
    style: TextStyle = Hl.type.display,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    measured: Boolean = false,
) {
    val context = LocalContext.current
    val widthPx = maxWidth.toPx()
    val heightPx = maxHeight.toPx()
    // Set at its weight: drawn at its own size, which never leaves the room.
    val request = remember(logo, widthPx, heightPx) { logo?.let { titleArtRequest(context, it, widthPx, heightPx) } }
    var failed by remember(request) { mutableStateOf(false) }
    if (request != null && !failed) {
        AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
        )
    } else if (measured) {
        MeasuredTitle(title, style, maxWidth + HlSpace.xxl * 3, maxLines, modifier)
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

/**
 * A title set to its measure, as a poster sets it: on one line at the title's own size
 * ([HlType.titleSignature]) when it fits ("Ran", "Heat"), else in [style] on lines of even length
 * ("Les Sept / Samouraïs", not "Les Sept Samouraïs / x"), which Android 9 does not balance itself.
 * At most four measures of the text, once per title and width.
 */
@Composable
private fun MeasuredTitle(title: String, style: TextStyle, maxWidth: Dp, maxLines: Int, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = TITLE_MEASURES)
    val signature = Hl.type.titleSignature
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.roundToPx() }
    val plan = remember(title, style, signature, widthPx, maxLines) { titlePlan(measurer, title, style, signature, widthPx, maxLines) }
    Text(
        title,
        style = plan.style,
        color = Hl.colors.content,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = with(density) { (plan.widthPx ?: widthPx).toDp() }),
    )
}

/** How a title is set: its style, and the width that evens its lines (null: the room it has). */
private class TitlePlan(val style: TextStyle, val widthPx: Int?)

private fun titlePlan(
    measurer: TextMeasurer,
    title: String,
    style: TextStyle,
    signature: TextStyle,
    widthPx: Int,
    maxLines: Int,
): TitlePlan {
    if (measurer.measure(title, signature, maxLines = 1, softWrap = false).size.width <= widthPx) return TitlePlan(signature, null)
    val oneLine = measurer.measure(title, style, maxLines = 1, softWrap = false).size.width
    if (oneLine <= widthPx || maxLines < 2) return TitlePlan(style, null)
    // Two lines: the narrowest of a few widths past half the line that still holds it in two.
    val even = EVEN_SHARES.map { (oneLine * it).toInt() }.filter { it < widthPx }.firstOrNull { w ->
        measurer.measure(title, style, constraints = Constraints(maxWidth = w), maxLines = maxLines).let { !it.hasVisualOverflow && it.lineCount == 2 }
    }
    return TitlePlan(style, even)
}

/** Shares of a title's one-line width tried to even its two lines, narrowest first. */
private val EVEN_SHARES = listOf(0.55f, 0.65f)

/** Measures a title keeps: its signature size, its line, two widths. */
private const val TITLE_MEASURES = 4
