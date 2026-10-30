package app.houselights.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.houselights.R
import kotlin.math.roundToInt

/**
 * Archivo in three widths, the interface's voices (DESIGN.md, Identity): condensed for the poster, normal
 * for reading, expanded for technical facts. Each width declares only the weights its styles use.
 */
object HlFonts {
    @OptIn(ExperimentalTextApi::class)
    private fun archivo(width: Float, vararg weights: FontWeight) = FontFamily(
        weights.map { w ->
            Font(
                R.font.archivo,
                weight = w,
                variationSettings = FontVariation.Settings(FontVariation.weight(w.weight), FontVariation.width(width)),
            )
        },
    )

    val condensed = archivo(70f, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
    val text = archivo(100f, FontWeight.Normal, FontWeight.Medium)
    val expanded = archivo(125f, FontWeight.Medium, FontWeight.SemiBold)

    /** A count cut in posters ([HlType.figureSignature]): the poster's voice drawn tighter, its one weight. */
    val figures = archivo(62f, FontWeight.Bold)

    /** Code read as written (NFO XML): fixed-width already, and a single weight on Android 9. */
    val mono = FontFamily.Monospace
}

/** Tracking by size: light text spreads on a TV, so small type opens up and large type tightens. */
internal fun trackingFor(size: TextUnit): TextUnit =
    TRACKING_FROM.firstOrNull { (from, _) -> size.value >= from }?.second ?: TRACKING_SMALLEST

/** From which size, in sp, each tracking holds: titles tighten, what is read opens, a mark's 12 most. */
private val TRACKING_FROM = listOf(32f to (-0.01).em, 15f to 0.em, 13f to 0.01.em)
private val TRACKING_SMALLEST = 0.02.em

/**
 * Line-height room goes between lines only, so spacing is measured from the type and capitals centre in
 * plates; this is Compose's default, set explicitly so that a library update cannot move every text.
 */
private val TRIMMED = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.Both)

/** How tall Archivo's capitals stand, as a share of its size (`sCapHeight` 686 of 1000). */
const val CAP_HEIGHT = 0.686f

/** Aligns a line's capitals with the top of the picture beside it, trimming the font's room above them. */
fun Modifier.capitalsAtTop(fontSize: TextUnit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[FirstBaseline]
    val above = if (baseline == AlignmentLine.Unspecified) 0 else (baseline - fontSize.toPx() * CAP_HEIGHT).roundToInt().coerceAtLeast(0)
    layout(placeable.width, placeable.height - above) { placeable.place(0, -above) }
}

/** Titles break into balanced lines, never leaving one word alone. */
private val TITLE = LineBreak.Heading

/** Paragraphs break evenly, weighing the whole paragraph; never hyphenated. */
private val PROSE = LineBreak.Paragraph

/** Tabular figures, so numbers line up and do not jitter. */
private const val FIGURES = "tnum"

/** Capital-height punctuation for the billing block; never with [FIGURES], as Archivo's `case` undoes `tnum`. */
private const val CAPITALS = "case"

/** The billing block's capitals need more air than words; its small condensed words, a little. */
private val CAPITALS_TRACKING = 0.04.em
private val BILLING_JOIN_TRACKING = 0.02.em

/** A code to type elsewhere, its letters set apart to be read from across the room. */
private val CODE_TRACKING = 0.06.em

/** A count cut in posters: its digits close, each as wide as its own shape, its lines as tall as its figures. */
private val FIGURE_TRACKING = (-0.01).em
private const val PROPORTIONAL = "pnum"
private const val FIGURE_LINE = 0.74f

private fun scale(
    family: FontFamily,
    weight: FontWeight,
    size: Int,
    line: Int,
    features: String? = null,
    lineBreak: LineBreak = LineBreak.Simple,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = trackingFor(size.sp),
    fontFeatureSettings = features,
    lineBreak = lineBreak,
    lineHeightStyle = TRIMMED,
)

private fun signature(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: TextUnit, features: String? = null) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking,
        fontFeatureSettings = features,
        lineHeightStyle = TRIMMED,
    )

/**
 * Type scale for a TV seen from the couch (DESIGN.md, Type): eight sizes from 12 to 44 sp and the signature 72, a line
 * height a size on the 4 sp grid (15 and 16 have a name's and a paragraph's). Three weights, Regular, Medium and
 * SemiBold; Bold is the signatures' alone. A floor a voice: 13 for what is read, 12 in a mark's box, 16 for the poster.
 * Poster titles fit Vietnamese: in condensed a stacked mark rises to 0.92 em and a descender falls to 0.20 em, hence
 * [display] and [cardTitle] lines at least 1.125 em apart.
 */
@Immutable
data class HlType(
    // The voice of the poster (condensed).
    /** Title of a detail page when there is no clear logo: two lines fill the logo's box. */
    val display: TextStyle,
    /** Screen and task titles; a title without its logo in a hero or on home's case. */
    val headline: TextStyle,
    /** What a pane or sheet is about, an intertitle's line, a folder's name, a figure or a name stated. */
    val subject: TextStyle,
    /** The words of a track (a season, an NFO's view); [placeCurrent] for the one you are in. */
    val place: TextStyle,
    val placeCurrent: TextStyle,
    /** A row's title over its cards; the selected title over a wall; a door's title without its logo. */
    val rowTitle: TextStyle,
    /** A name under a card or on a door, home's case's action, a result's name. */
    val cardName: TextStyle,
    /** A title set on a card without artwork, like a plain one-sheet. */
    val cardTitle: TextStyle,

    // The voice of reading (normal width).
    /** A tagline, an episode's name in its heading, a typed query and the keyboard's letters. */
    val lead: TextStyle,
    /** Overviews, paragraphs, a synopsis, an NFO's values, what is typed in a field. */
    val body: TextStyle,
    /** A name to choose: a button's or a menu's words, a setting, a place, a version, a value in words. */
    val entry: TextStyle,
    /** Facts, descriptions, values, notes, counts, time left; tabular figures. */
    val meta: TextStyle,
    /** Title over a group of facts or settings ("Image", "État"), a field's name: a step under the names it heads. */
    val group: TextStyle,
    /** Raw text to read as written (NFO XML). */
    val code: TextStyle,

    // The technical voice (expanded, tabular figures).
    /** A technical value a pane or a plate states: definition, ratio, codec, an address. */
    val spec: TextStyle,
    /** Formats set as on a case's back, a duration inside a button, a door's labels, an address under a QR code. */
    val jaquette: TextStyle,
    /** Printed marks in their box: an age rating, a protocol, a card's year. */
    val badge: TextStyle,

    // Signatures, each set to its own measure.
    /** A short title without a logo, set large on one detail-page line ([TitleArt]); never a screen's pick. */
    val titleSignature: TextStyle,
    /** Names in the billing block, set like the credits at the foot of a poster. */
    val billing: TextStyle,
    /** The small words between them: "a film by", "with", "and". */
    val billingJoin: TextStyle,
    /** A code to type elsewhere (Trakt's): [display] with its letters set apart. */
    val codeSignature: TextStyle,
    /** A library's count on its home door, each digit cut in a poster; sized to the doors ([figureAt]). */
    val figureSignature: TextStyle,
)

val HouselightsType = HlType(
    display = scale(HlFonts.condensed, FontWeight.SemiBold, 44, 52, lineBreak = TITLE),
    headline = scale(HlFonts.condensed, FontWeight.SemiBold, 32, 40, lineBreak = TITLE),
    subject = scale(HlFonts.condensed, FontWeight.Medium, 26, 32, FIGURES, TITLE),
    place = scale(HlFonts.condensed, FontWeight.Medium, 20, 28),
    placeCurrent = scale(HlFonts.condensed, FontWeight.SemiBold, 20, 28),
    rowTitle = scale(HlFonts.condensed, FontWeight.Medium, 20, 28, lineBreak = TITLE),
    cardName = scale(HlFonts.condensed, FontWeight.Medium, 16, 20, lineBreak = TITLE),
    cardTitle = scale(HlFonts.condensed, FontWeight.SemiBold, 16, 20, lineBreak = TITLE),

    lead = scale(HlFonts.text, FontWeight.Medium, 16, 24, lineBreak = TITLE),
    body = scale(HlFonts.text, FontWeight.Normal, 15, 24, lineBreak = PROSE),
    entry = scale(HlFonts.text, FontWeight.Medium, 15, 20),
    meta = scale(HlFonts.text, FontWeight.Normal, 13, 20, FIGURES, PROSE),
    group = scale(HlFonts.text, FontWeight.Medium, 13, 20),
    code = scale(HlFonts.mono, FontWeight.Normal, 15, 24),

    spec = scale(HlFonts.expanded, FontWeight.Medium, 15, 20, FIGURES),
    jaquette = scale(HlFonts.expanded, FontWeight.Medium, 13, 20, FIGURES),
    badge = scale(HlFonts.expanded, FontWeight.SemiBold, 12, 16, FIGURES),

    titleSignature = scale(HlFonts.condensed, FontWeight.Bold, 72, 72, lineBreak = TITLE),
    billing = signature(HlFonts.condensed, FontWeight.SemiBold, 16, 20, CAPITALS_TRACKING, CAPITALS),
    billingJoin = signature(HlFonts.condensed, FontWeight.Normal, 13, 20, BILLING_JOIN_TRACKING),
    codeSignature = signature(HlFonts.condensed, FontWeight.SemiBold, 44, 52, CODE_TRACKING),
    figureSignature = signature(HlFonts.figures, FontWeight.Bold, 120, 88, FIGURE_TRACKING, PROPORTIONAL),
)

/** [HlType.figureSignature] at [size], the one the doors share, its lines as tall as its figures. */
fun TextStyle.figureAt(size: TextUnit): TextStyle = copy(fontSize = size, lineHeight = size * FIGURE_LINE)

val LocalHlType = staticCompositionLocalOf { HouselightsType }

/**
 * Styles for the lines of one text laid out apart (a lazy list): only the first is trimmed above and the last below
 * ([TRIMMED]), so each line stands where the whole text would set it.
 */
internal class TextLines(private val style: TextStyle) {
    private val first = trimmed(LineHeightStyle.Trim.FirstLineTop)
    private val middle = trimmed(LineHeightStyle.Trim.None)
    private val last = trimmed(LineHeightStyle.Trim.LastLineBottom)

    private fun trimmed(trim: LineHeightStyle.Trim) = style.copy(lineHeightStyle = LineHeightStyle(TRIMMED.alignment, trim))

    /** The style of line [index] of [count]. */
    fun of(index: Int, count: Int): TextStyle = when {
        count == 1 -> style
        index == 0 -> first
        index == count - 1 -> last
        else -> middle
    }
}

/** This style lit across its words by the ember light: the place you are in, a screen's title. Components only. */
fun TextStyle.emberLit(colors: HlColors): TextStyle = copy(brush = colors.emberLight())

/** Room for text, given at the default text size and scaled with the viewer's, so a frame never cuts it. */
@Composable
@ReadOnlyComposable
fun textRoom(height: Dp): Dp = with(LocalDensity.current) { height.value.sp.toDp() }

/** Room kept for one line of [style], at the viewer's text size: its line height (`lead`: 24 at 1). */
@Composable
@ReadOnlyComposable
fun textRoom(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }
