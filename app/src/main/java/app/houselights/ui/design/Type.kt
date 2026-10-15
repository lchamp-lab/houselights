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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.houselights.R

/**
 * One family, Archivo, whose widths are the voices of the interface:
 * - condensed, the voice of the poster: titles, names in the billing block, large figures;
 * - normal, the voice of reading: overviews, descriptions, names, values;
 * - wide, the voice of controls: buttons and the options of a track;
 * - expanded, the technical voice: formats and specifications, short and set apart;
 * - the brand, expanded and heavy: the wordmark alone.
 *
 * Each width declares only the weights its styles use, so none is ever faked. The one other face
 * is the system's monospaced one, for raw text that must line up as written ([mono]).
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
    val text = archivo(100f, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
    val wide = archivo(112f, FontWeight.SemiBold)
    val expanded = archivo(125f, FontWeight.SemiBold)
    /** The weight of the wordmark, between Bold and ExtraBold. */
    val brandWeight = FontWeight(720)

    /** The wordmark's: expanded, at [brandWeight], as the banner draws it. */
    val brand = archivo(125f, brandWeight)

    /** Code read as written (NFO XML): one weight on Android 9, and every character already the same width. */
    val mono = FontFamily.Monospace
}

/** The wordmark's letters, drawn a little closer, as on the banner. */
private val BRAND_TRACKING = (-0.016).em

/** Tabular figures: numbers keep the same width, so they line up in columns and do not jitter. */
private const val FIGURES = "tnum"

/** Capitals: punctuation and dashes set at the height of capitals, for the billing block. */
private const val CAPITALS = "case, tnum"

/**
 * Type scale for a TV seen from the couch, eight sizes with clear steps:
 * 12 · 14 · 16 · 20 · 24 · 32 · 44 · 64 sp, line heights on a 2 sp grid. Nothing below 12 sp, and
 * no content below 14 sp. Each style has one role.
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
    /** Large figures that state something (storage used, a film's definition). */
    val figure: TextStyle,
    /** What a panel or a sheet is about: "Modifier", "Fiche technique". */
    val subject: TextStyle,
    /** A title set on a card that has no artwork, like a plain one-sheet. */
    val cardTitle: TextStyle,
    /** Names in the billing block, set like the credits at the foot of a poster. */
    val billing: TextStyle,
    /** The small words between them: "a film by", "with", "and". */
    val billingJoin: TextStyle,
    /** The Houselights wordmark, at the size of a subject: what About is about. */
    val wordmark: TextStyle,
    /** The wordmark over the welcome wall, the one place it is large. */
    val wordmarkHero: TextStyle,

    // The voice of reading (normal width).
    /** Pane and dialog titles, taglines. */
    val title: TextStyle,
    /** Row headers, card and episode names. */
    val lead: TextStyle,
    /** Overviews, descriptions, paragraphs. */
    val body: TextStyle,
    /** A name to read in a list: a setting, a menu option, a navigation entry, a title in a line. */
    val entry: TextStyle,
    /** Facts and values: year, runtime, score, sizes; tabular figures. */
    val meta: TextStyle,
    /** Secondary lines: hints, roles, paths, group titles; tabular figures (time left, counts). */
    val caption: TextStyle,
    /**
     * The line under a name in a row, an entry or a choice: what it does, what it is set to.
     * Tighter than [caption], so that over two lines it reads as one block under its name.
     * Tabular figures: a count or a time under a name does not jitter as it changes.
     */
    val detail: TextStyle,
    /** Raw text to read as written (NFO XML). */
    val code: TextStyle,

    // The voice of controls (wide).
    /** Buttons and the options of a track. */
    val label: TextStyle,

    // The technical voice (expanded, tabular figures).
    /** The headline of a technical sheet: definition, ratio, codec. */
    val spec: TextStyle,
    /** Format and rating badges: Ultra HD, Dolby Vision, 12. */
    val badge: TextStyle,
)

val HouselightsType = HlType(
    display = TextStyle(
        fontFamily = HlFonts.condensed, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 72.sp, letterSpacing = (-0.01).em,
    ),
    headline = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.005).em),
    figure = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp, fontFeatureSettings = FIGURES),
    subject = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp),
    cardTitle = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),
    billing = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.04.em, fontFeatureSettings = CAPITALS),
    billingJoin = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 24.sp, letterSpacing = 0.02.em),
    // As wide on screen as the condensed name it replaces: 136 dp in About, 352 over the welcome wall.
    wordmark = TextStyle(
        fontFamily = HlFonts.brand, fontWeight = HlFonts.brandWeight, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = BRAND_TRACKING,
    ),
    wordmarkHero = TextStyle(
        fontFamily = HlFonts.brand, fontWeight = HlFonts.brandWeight, fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = BRAND_TRACKING,
    ),

    title = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    lead = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    body = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    entry = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    meta = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp, fontFeatureSettings = FIGURES),
    caption = TextStyle(
        fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = FIGURES,
    ),
    detail = TextStyle(
        fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, fontFeatureSettings = FIGURES,
    ),
    code = TextStyle(fontFamily = HlFonts.mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),

    label = TextStyle(fontFamily = HlFonts.wide, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.01.em, fontFeatureSettings = FIGURES),

    spec = TextStyle(
        fontFamily = HlFonts.expanded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp,
        letterSpacing = 0.01.em, fontFeatureSettings = FIGURES,
    ),
    badge = TextStyle(
        fontFamily = HlFonts.expanded, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.06.em, fontFeatureSettings = FIGURES,
    ),
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
