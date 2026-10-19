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
        }
    )

    val condensed = archivo(70f, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
    val text = archivo(100f, FontWeight.Normal, FontWeight.SemiBold)
    val expanded = archivo(125f, FontWeight.SemiBold, FontWeight.Bold)

    /** Code read as written (NFO XML): fixed-width already, and a single weight on Android 9. */
    val mono = FontFamily.Monospace
}

/** Tracking by size: light text spreads on a TV, so small type opens up and large type tightens. */
internal fun trackingFor(size: TextUnit): TextUnit =
    TRACKING_FROM.firstOrNull { (from, _) -> size.value >= from }?.second ?: TRACKING_SMALLEST

/** From which size, in sp, each tracking holds; below the last, [TRACKING_SMALLEST]. */
private val TRACKING_FROM = listOf(44f to (-0.01).em, 16f to 0.em, 14f to 0.01.em)
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
 * Type scale for a TV seen from the couch (DESIGN.md, Type): 12 to 64 sp, line heights on a 2 sp grid, no
 * content below 14 sp. Poster titles ([display], [cardTitle]) fit Vietnamese: in condensed bold a stacked
 * mark rises to 0.92 em and a descender falls to 0.20 em, hence lines 1.125 and 1.2 em apart.
 */
@Immutable
data class HlType(
    // The voice of the poster (condensed).
    /** Title of a detail page when there is no clear logo. */
    val display: TextStyle,
    /** Screen titles, the home hero title without logo. */
    val headline: TextStyle,
    /** What a panel or sheet is about ("Modifier"), an intertitle's line, a figure that states something. */
    val subject: TextStyle,
    /** The places of the app in its bar (Accueil, Films, Séries); [placeCurrent] for the one you are in. */
    val place: TextStyle,
    val placeCurrent: TextStyle,
    /** A row's title over its cards; the selected title over a wall. */
    val rowTitle: TextStyle,
    /** A name under a card. */
    val cardName: TextStyle,
    /** A title set on a card without artwork, like a plain one-sheet. */
    val cardTitle: TextStyle,

    // The voice of reading (normal width).
    /** Taglines, an episode's name in its heading, dialog titles. */
    val title: TextStyle,
    /** A tagline, a name heading a block: a source, a form. */
    val lead: TextStyle,
    /** Overviews, descriptions, paragraphs. */
    val body: TextStyle,
    /** A name to read or choose: a setting, a menu option, a navigation entry, a button's words. */
    val entry: TextStyle,
    /** Facts and values: year, runtime, score, sizes; tabular figures. */
    val meta: TextStyle,
    /** Secondary lines: hints, roles, paths, the line under a name; set tight so two lines read as one block. */
    val caption: TextStyle,
    /** Title over a group of facts or settings ("Vidéo", "État"): above its labels, not among them. */
    val group: TextStyle,
    /** Raw text to read as written (NFO XML). */
    val code: TextStyle,

    // The technical voice (expanded, tabular figures).
    /** The headline of a technical sheet: definition, ratio, codec. */
    val spec: TextStyle,
    /** Formats set as on a case's back, a duration inside a button. */
    val jaquette: TextStyle,
    /** Printed marks: an age rating, a protocol. */
    val badge: TextStyle,

    // Signatures, each set to its own measure.
    /** A short title without a logo, set large on one detail-page line ([TitleArt]); never a screen's pick. */
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
    place = scale(HlFonts.condensed, FontWeight.SemiBold, 26, 30),
    placeCurrent = scale(HlFonts.condensed, FontWeight.Bold, 26, 30),
    rowTitle = scale(HlFonts.condensed, FontWeight.SemiBold, 24, 28, lineBreak = TITLE),
    cardName = scale(HlFonts.condensed, FontWeight.SemiBold, 20, 24, lineBreak = TITLE),
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
    jaquette = scale(HlFonts.expanded, FontWeight.SemiBold, 14, 18, FIGURES),
    badge = scale(HlFonts.expanded, FontWeight.Bold, 12, 14, FIGURES),

    titleSignature = scale(HlFonts.condensed, FontWeight.Bold, 96, 96, lineBreak = TITLE),
    billing = signature(HlFonts.condensed, FontWeight.Medium, 18, 24, CAPITALS_TRACKING, CAPITALS),
    billingJoin = signature(HlFonts.condensed, FontWeight.Normal, 14, 24, BILLING_JOIN_TRACKING),
)

val LocalHlType = staticCompositionLocalOf { HouselightsType }

/** This style lit across its words by the ember light: the place you are in, a screen's title. Components only. */
fun TextStyle.emberLit(colors: HlColors): TextStyle = copy(brush = colors.emberLight())

/** Room for text, given at the default text size and scaled with the viewer's, so a frame never cuts it. */
@Composable
@ReadOnlyComposable
fun textRoom(height: Dp): Dp = with(LocalDensity.current) { height.value.sp.toDp() }

/** Room kept for one line of [style], at the viewer's text size: its line height (`lead`: 26 at 1). */
@Composable
@ReadOnlyComposable
fun textRoom(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }
