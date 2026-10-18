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
 * The HOUSELIGHTS wordmark, drawn from `wordmark.xml` rather than typeset (DESIGN.md, Brand), in flat
 * projector white. [height] is its cap height; round letters overshoot it slightly inside the box.
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

/** Viewport of `wordmark.xml`: capitals are 100 units tall; round letters overshoot them. */
private const val WORDMARK_WIDTH = 1196.847f
private const val WORDMARK_HEIGHT = 103.79f
private const val WORDMARK_CAPITALS = 100f

/**
 * The request of a [TitleArt] up to [maxWidthPx] by [maxHeightPx]: [logo] at constant weight ([EvenLogo]),
 * decoded larger so it is never upscaled. Preloads must use it to hit the memory cache.
 */
internal fun titleArtRequest(context: Context, logo: ImageRef, maxWidthPx: Int, maxHeightPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(logo.url(maxWidthPx))
        .size((maxWidthPx * LOGO_DECODE).roundToInt(), (maxHeightPx * LOGO_DECODE).roundToInt())
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .transformations(EvenLogo(maxWidthPx, maxHeightPx))
        .build()

/** Decode size relative to the logo box, so [EvenLogo] sizes it from real pixels. */
private const val LOGO_DECODE = 1.5f

/** The title's clear logo, or its name in condensed type; [measured] balances its lines ([MeasuredTitle]). */
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
    // Drawn at the size EvenLogo gave it, which fits the box.
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
 * A title set to its measure: on one line at [HlType.titleSignature] when it fits ("Ran"), else in [style]
 * on balanced lines ("Les Sept / Samouraïs"), which Android 9 does not do itself. At most four measures.
 */
@Composable
private fun MeasuredTitle(title: String, style: TextStyle, maxWidth: Dp, maxLines: Int, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = TITLE_MEASURES)
    val signature = Hl.type.titleSignature
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.roundToPx() }
    val plan = remember(title, style, signature, widthPx, maxLines) { measurer.titlePlan(title, style, signature, widthPx, maxLines) }
    Text(
        title,
        style = plan.style,
        color = Hl.colors.content,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = with(density) { (plan.widthPx ?: widthPx).toDp() }),
    )
}

/** A title's style and the width that balances its lines (null: the full width). */
private class TitlePlan(val style: TextStyle, val widthPx: Int?)

private fun TextMeasurer.titlePlan(
    title: String,
    style: TextStyle,
    signature: TextStyle,
    widthPx: Int,
    maxLines: Int,
): TitlePlan {
    if (measure(title, signature, maxLines = 1, softWrap = false).size.width <= widthPx) return TitlePlan(signature, null)
    val oneLine = measure(title, style, maxLines = 1, softWrap = false).size.width
    if (oneLine <= widthPx || maxLines < 2) return TitlePlan(style, null)
    // Two lines: the narrowest candidate width that still fits the title on two.
    val even = EVEN_SHARES.map { (oneLine * it).toInt() }.filter { it < widthPx }.firstOrNull { w ->
        val lines = measure(title, style, constraints = Constraints(maxWidth = w), maxLines = maxLines)
        !lines.hasVisualOverflow && lines.lineCount == 2
    }
    return TitlePlan(style, even)
}

/** Fractions of the one-line width tried for two balanced lines, narrowest first. */
private val EVEN_SHARES = listOf(0.55f, 0.65f)

/** Cached measures per title: signature size, one line, two widths. */
private const val TITLE_MEASURES = 4
