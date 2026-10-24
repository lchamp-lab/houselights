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
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Gradation
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.drawGradation
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
import app.houselights.ui.design.emberLit
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** Row and section header in the poster's voice, sentence case. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.rowTitle, color = Hl.colors.content, modifier = modifier)
}

/** The title of a place (a wall, Réglages, Rechercher, a person) in the ember light; never a film's title. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    val colors = Hl.colors
    val headline = Hl.type.headline
    val lit = remember(colors, headline) { headline.emberLit(colors) }
    Text(text, style = lit, color = colors.content, modifier = modifier)
}

/** The title of a task or a form (Identifier, Ajouter un dossier) in white: ember says where you are, not what you do. */
@Composable
fun TaskTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = Hl.type.headline,
        color = Hl.colors.content,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The lockup (DESIGN.md, Brand): Gradation in its ember light, then the [Wordmark], read as the name. [capitals]
 * is the name's cap height; the box is the name's, the mark hanging past it by a fifth above and below.
 */
@Composable
fun Lockup(capitals: Dp, modifier: Modifier = Modifier) {
    val colors = Hl.colors
    Wordmark(
        capitals,
        modifier
            .drawWithCache {
                // The name's box has its capitals in the middle: round letters overshoot alike above and below.
                val bars = Gradation.lockupBars(capitals.toPx(), size.height / 2)
                val lights = Gradation.lights(bars, colors)
                onDrawBehind { drawGradation(bars, lights) }
            }
            .absolutePadding(left = capitals * Gradation.lockupIndent),
    )
}

/**
 * The HOUSELIGHTS wordmark, drawn from `wordmark.xml` rather than typeset (DESIGN.md, Brand), in flat
 * projector white. [height] is its cap height; round letters overshoot it slightly inside the box.
 */
@Composable
private fun Wordmark(height: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.wordmark),
        contentDescription = stringResource(R.string.wordmark),
        colorFilter = ColorFilter.tint(Hl.colors.content),
        contentScale = ContentScale.Fit,
        // Its exact size: an aspect ratio rounds to whole pixels and may widen the box, moving the name off the mark.
        modifier = modifier.size(height * WORDMARK_WIDTH / WORDMARK_CAPITALS, height * WORDMARK_HEIGHT / WORDMARK_CAPITALS),
    )
}

/** Viewport of `wordmark.xml`: capitals are 100 units tall; round letters overshoot them. */
private const val WORDMARK_WIDTH = 1196.847f
private const val WORDMARK_HEIGHT = 103.79f
private const val WORDMARK_CAPITALS = 100f

/**
 * The request of a [TitleArt] up to [maxWidth] by [maxHeight] at [density]: [logo] at constant weight ([EvenLogo]),
 * decoded larger; enlarged past its file's pixels only above 1080p, to the size it has there. Preloads must use it
 * to hit the memory cache.
 */
internal fun titleArtRequest(context: Context, logo: ImageRef, maxWidth: Dp, maxHeight: Dp, density: Density): ImageRequest {
    val widthPx = with(density) { maxWidth.roundToPx() }
    val heightPx = with(density) { maxHeight.roundToPx() }
    return ImageRequest.Builder(context)
        .data(logo.url(widthPx))
        .size((widthPx * LOGO_DECODE).roundToInt(), (heightPx * LOGO_DECODE).roundToInt())
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .transformations(EvenLogo(widthPx, heightPx, enlarge = maxOf(1f, density.density / FULL_HD_DENSITY)))
        .build()
}

/** Decode size relative to the logo box, so [EvenLogo] sizes it from real pixels. */
private const val LOGO_DECODE = 1.5f

/** The canvas's density at 1080p (1920 px), above which a logo may be enlarged to the size it has there. */
private const val FULL_HD_DENSITY = 2f

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
    val density = LocalDensity.current
    // Drawn at the size EvenLogo gave it, which fits the box.
    val request = remember(logo, maxWidth, maxHeight, density) { logo?.let { titleArtRequest(context, it, maxWidth, maxHeight, density) } }
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
