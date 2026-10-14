package app.houselights.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.houselights.R

/**
 * One family, Archivo, at three widths that are three voices:
 * - condensed, the voice of the poster: titles and the billing block;
 * - normal, the voice of reading: overviews, descriptions, values;
 * - wide, the technical voice: formats, controls and every number.
 */
object HlFonts {
    @OptIn(ExperimentalTextApi::class)
    private fun archivo(width: Float) = FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            Font(
                R.font.archivo,
                weight = FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.width(width)),
            )
        }
    )

    val condensed = archivo(70f)
    val text = archivo(100f)
    val wide = archivo(112f)
}

/** Tabular figures: numbers keep the same width, so they line up in columns and do not jitter. */
private const val FIGURES = "tnum"

/** Capitals: punctuation and dashes set at the height of capitals, for the billing block. */
private const val CAPITALS = "case, tnum"

/**
 * Type scale for a TV seen from the couch, eight sizes with clear steps:
 * 12 · 14 · 16 · 20 · 24 · 32 · 44 · 64 sp, line heights on a 2 sp grid. Nothing below 12 sp, and
 * no content below 14 sp. Each style has one role.
 */
@Immutable
data class HlType(
    // The voice of the poster (condensed).
    /** Title of a detail page when there is no clear logo. */
    val display: TextStyle,
    /** Screen titles, the home hero title without logo. */
    val headline: TextStyle,
    /** Names in the billing block, set like the credits at the foot of a poster. */
    val billing: TextStyle,
    /** The small words between them: "a film by", "with", "and". */
    val billingJoin: TextStyle,
    /** The Houselights wordmark. */
    val wordmark: TextStyle,
    /** The wordmark as the subject of a screen (welcome, about). */
    val wordmarkHero: TextStyle,

    // The voice of reading (normal width).
    /** Pane and dialog titles, taglines. */
    val title: TextStyle,
    /** Row headers, card and episode names. */
    val lead: TextStyle,
    /** Overviews, descriptions, paragraphs. */
    val body: TextStyle,
    /** Secondary lines: hints, roles, paths, short descriptions. */
    val caption: TextStyle,

    // The technical voice (wide, tabular figures).
    /** Buttons, tabs, menu entries, setting names. */
    val label: TextStyle,
    /** Facts and values: year, runtime, score, sizes. */
    val meta: TextStyle,
    /** Large figures that state something (storage used, a film's definition). */
    val figure: TextStyle,
    /** The headline of a technical sheet: definition, ratio, codec. */
    val spec: TextStyle,
    /** Format and rating badges: Ultra HD, Dolby Vision, 12. */
    val badge: TextStyle,
    /** Raw text to read as written (NFO XML). */
    val code: TextStyle,
)

val HouselightsType = HlType(
    display = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 70.sp, letterSpacing = (-0.01).em),
    headline = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.005).em),
    billing = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.04.em, fontFeatureSettings = CAPITALS),
    billingJoin = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 24.sp, letterSpacing = 0.02.em),
    wordmark = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 24.sp, letterSpacing = 0.02.em),
    wordmarkHero = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 80.sp, lineHeight = 84.sp, letterSpacing = 0.01.em),

    title = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    lead = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    body = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    caption = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),

    label = TextStyle(fontFamily = HlFonts.wide, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.01.em, fontFeatureSettings = FIGURES),
    meta = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp, fontFeatureSettings = FIGURES),
    figure = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp, fontFeatureSettings = FIGURES),
    spec = TextStyle(fontFamily = HlFonts.wide, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.01.em, fontFeatureSettings = FIGURES),
    badge = TextStyle(fontFamily = HlFonts.wide, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em, fontFeatureSettings = FIGURES),
    code = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp, fontFeatureSettings = FIGURES),
)

val LocalHlType = staticCompositionLocalOf { HouselightsType }
