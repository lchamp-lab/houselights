package app.houselights.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.AlignmentLine
import kotlin.math.roundToInt

/**
 * One family, Archivo, whose widths are the voices of the interface:
 * - condensed, the voice of the poster: titles, what a panel is about, the billing block;
 * - normal, the voice of reading: everything read or chosen, from overviews to buttons;
 * - expanded, the technical voice: formats and specifications, short and set apart.
 * The name, HOUSELIGHTS, is drawn, not set ([app.houselights.ui.design.components.Wordmark]).
 *
 * Three weights carry the scale, each with one meaning: Regular reads, SemiBold names, Bold
 * announces (the titles of the poster). Medium is the billing block's alone. Each width declares
 * only the weights its styles use, so none is ever faked. The one other face is the system's
 * monospaced one, for raw text that must line up as written ([mono]).
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
        }
    )

    val condensed = archivo(70f, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
    val text = archivo(100f, FontWeight.Normal, FontWeight.SemiBold)
    val expanded = archivo(125f, FontWeight.SemiBold)

    /** Code read as written (NFO XML): one weight on Android 9, and every character already the same width. */
    val mono = FontFamily.Monospace
}

/**
 * Tracking follows size: large type is drawn a little closer, small type a little more open.
 * Light text on a black room spreads on a television, and a small letter needs the air to stay
 * apart from the next one; a large one has more than it needs.
 */
internal fun trackingFor(size: TextUnit): TextUnit =
    TRACKING_FROM.firstOrNull { (from, _) -> size.value >= from }?.second ?: TRACKING_SMALLEST

/** From which size, in sp, each tracking holds; below the last, [TRACKING_SMALLEST]. */
private val TRACKING_FROM = listOf(44f to (-0.01).em, 16f to 0.em, 14f to 0.01.em)
private val TRACKING_SMALLEST = 0.02.em

/**
 * A text's box keeps to its letters: the room a line height adds goes between lines only, never
 * above the first or under the last, so the space between two texts is measured from the type, as
 * the spacing tokens are, and a capital sits in the middle of a pill, a badge or a button.
 * Compose's own default today, written down so that an update cannot move every text on screen.
 */
private val TRIMMED = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.Both)

/** How tall Archivo's capitals stand, as a share of its size (`sCapHeight` 686 of 1000). */
const val CAP_HEIGHT = 0.686f

/**
 * A line set beside a picture with its capitals flush with the picture's top: the room its font
 * keeps above the capitals, for accents, taken away, read from where its first baseline falls.
 */
fun Modifier.capitalsAtTop(fontSize: TextUnit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[FirstBaseline]
    val above = if (baseline == AlignmentLine.Unspecified) 0 else (baseline - fontSize.toPx() * CAP_HEIGHT).roundToInt().coerceAtLeast(0)
    layout(placeable.width, placeable.height - above) { placeable.place(0, -above) }
}

/** Titles break into balanced lines: two lines of a title are about as long, never one word left over. */
private val TITLE = LineBreak.Heading

/** Paragraphs break for reading: the whole paragraph is weighed, so the lines end evenly. Never hyphenated. */
private val PROSE = LineBreak.Paragraph

/** Tabular figures: numbers keep the same width, so they line up in columns and do not jitter. */
private const val FIGURES = "tnum"

/**
 * Capitals: punctuation and dashes set at the height of capitals, for the billing block. Never
 * with [FIGURES]: Archivo's `case` comes after `tnum` and sets proportional figures again.
 */
private const val CAPITALS = "case"

/** The capitals of the billing block need more air than words; its small words, condensed, a little. */
private val CAPITALS_TRACKING = 0.04.em
private val BILLING_JOIN_TRACKING = 0.02.em

/** A style of the scale: its tracking follows its size, its box keeps to its letters. */
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

/** A signature set to its own measure: the billing block. */
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
 * Type scale for a TV seen from the couch, eight sizes with clear steps:
 * 12 · 14 · 16 · 20 · 24 · 32 · 44 · 64 sp, line heights on a 2 sp grid. Nothing below 12 sp, and
 * no content below 14 sp. Fourteen roles, one each; then signatures set to their own measure, a short
 * title's 96 sp and the billing block, which only their components use.
 *
 * Two lines of a title set in the voice of the poster ([display], [cardTitle]) leave room for
 * Vietnamese: in Archivo condensed bold, a stacked mark (ổ, ế) rises to 0.92 em and a descender
 * (g, y) falls to 0.20 em, 1.12 em together, so their lines are 1.125 and 1.2 em apart. A capital
 * with a stacked mark (Ổ, 1.05 em) can still touch a descender right above it.
 */
@Immutable
data class HlType(
    // The voice of the poster (condensed).
    /** Title of a detail page when there is no clear logo. */
    val display: TextStyle,
    /** Screen titles, the home hero title without logo. */
    val headline: TextStyle,
    /**
     * What a panel or a sheet is about ("Modifier", "Fiche technique"), the line of an
     * intertitle, and a figure that states something (storage used); tabular figures.
     */
    val subject: TextStyle,
    /** A title set on a card that has no artwork, like a plain one-sheet. */
    val cardTitle: TextStyle,

    // The voice of reading (normal width).
    /** Taglines, an episode's name in its heading, dialog titles. */
    val title: TextStyle,
    /** Row headers, card and episode names. */
    val lead: TextStyle,
    /** Overviews, descriptions, paragraphs. */
    val body: TextStyle,
    /**
     * A name to read or to choose: a setting, a menu option, a navigation entry, a title in a
     * line, the words of a button and of the options of a track.
     */
    val entry: TextStyle,
    /** Facts and values: year, runtime, score, sizes; tabular figures. */
    val meta: TextStyle,
    /**
     * Secondary lines: hints, roles, paths, labels of a value, and the line under a name in a row,
     * an entry or a choice. Its lines are close, so that over two lines it reads as one block under
     * its name. Tabular figures: a count or a time does not jitter as it changes.
     */
    val caption: TextStyle,
    /** The title over a group of facts, settings or options ("Vidéo", "État"): above its labels, not among them. */
    val group: TextStyle,
    /** Raw text to read as written (NFO XML). */
    val code: TextStyle,

    // The technical voice (expanded, tabular figures).
    /** The headline of a technical sheet: definition, ratio, codec. */
    val spec: TextStyle,
    /** Format and rating badges: Ultra HD, Dolby Vision, 12. */
    val badge: TextStyle,

    // Signatures, each set to its own measure.
    /**
     * A title without its clear logo, short enough for one line of a detail page: the one size kept
     * for it, as a poster sets a short title large ([TitleArt]). Never a role a screen picks.
     */
    val titleSignature: TextStyle,
    /** Names in the billing block, set like the credits at the foot of a poster. */
    val billing: TextStyle,
    /** The small words between them: "a film by", "with", "and". */
    val billingJoin: TextStyle,
)

val HouselightsType = HlType(
    display = scale(HlFonts.condensed, FontWeight.Bold, 64, 72, lineBreak = TITLE),
    headline = scale(HlFonts.condensed, FontWeight.Bold, 44, 48, lineBreak = TITLE),
    subject = scale(HlFonts.condensed, FontWeight.SemiBold, 32, 36, FIGURES, TITLE),
    cardTitle = scale(HlFonts.condensed, FontWeight.Bold, 20, 24, lineBreak = TITLE),

    title = scale(HlFonts.text, FontWeight.SemiBold, 24, 30, lineBreak = TITLE),
    lead = scale(HlFonts.text, FontWeight.SemiBold, 20, 26, lineBreak = TITLE),
    body = scale(HlFonts.text, FontWeight.Normal, 16, 24, lineBreak = PROSE),
    entry = scale(HlFonts.text, FontWeight.SemiBold, 16, 20),
    meta = scale(HlFonts.text, FontWeight.Normal, 16, 20, FIGURES),
    caption = scale(HlFonts.text, FontWeight.Normal, 14, 18, FIGURES, PROSE),
    group = scale(HlFonts.text, FontWeight.SemiBold, 14, 18),
    code = scale(HlFonts.mono, FontWeight.Normal, 14, 22),

    spec = scale(HlFonts.expanded, FontWeight.SemiBold, 20, 26, FIGURES),
    badge = scale(HlFonts.expanded, FontWeight.SemiBold, 12, 16, FIGURES),

    titleSignature = scale(HlFonts.condensed, FontWeight.Bold, 96, 96, lineBreak = TITLE),
    billing = signature(HlFonts.condensed, FontWeight.Medium, 18, 24, CAPITALS_TRACKING, CAPITALS),
    billingJoin = signature(HlFonts.condensed, FontWeight.Normal, 14, 24, BILLING_JOIN_TRACKING),
)

val LocalHlType = staticCompositionLocalOf { HouselightsType }

/**
 * Room kept for text, given at the default text size: it grows with the viewer's text scale as the
 * text does, so a frame that holds text (an episode's overview, the home hero) neither cuts it nor
 * moves with what it shows.
 */
@Composable
@ReadOnlyComposable
fun textRoom(height: Dp): Dp = with(LocalDensity.current) { height.value.sp.toDp() }

/** Room kept for one line of [style], at the viewer's text size: its line height (`lead`: 26 at 1). */
@Composable
@ReadOnlyComposable
fun textRoom(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }
