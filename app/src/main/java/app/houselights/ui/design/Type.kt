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
 * One family, Archivo, used at three widths: condensed for titles (poster billing),
 * normal for reading, slightly wide for small labels that must stay legible at 3 m.
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

/** Type scale for a 1080p TV seen from the couch: nothing below 12 sp. */
@Immutable
data class HlType(
    /** Title of a detail page when there is no clear logo. */
    val display: TextStyle,
    /** Home hero title without logo, screen titles. */
    val headline: TextStyle,
    /** Episode names, dialog titles. */
    val title: TextStyle,
    /** Row and section headers. */
    val section: TextStyle,
    /** Overviews and paragraphs. */
    val body: TextStyle,
    val bodySmall: TextStyle,
    /** Buttons, tabs, toggles. */
    val label: TextStyle,
    /** Year, runtime, score. */
    val meta: TextStyle,
    /** Cast roles, hints, secondary lines. */
    val caption: TextStyle,
    /** Technical badges: 4K, HDR, Atmos. */
    val badge: TextStyle,
    /** The Houselights wordmark. */
    val wordmark: TextStyle,
    /** Names in the billing block, set like the credits at the foot of a poster. */
    val billing: TextStyle,
    /** The small words between them: "a film by", "with". */
    val billingJoin: TextStyle,
)

val HouselightsType = HlType(
    display = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Bold, fontSize = 60.sp, lineHeight = 60.sp, letterSpacing = (-0.01).em),
    headline = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 44.sp, letterSpacing = (-0.005).em),
    title = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    section = TextStyle(fontFamily = HlFonts.wide, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.01.em),
    body = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodySmall = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    label = TextStyle(fontFamily = HlFonts.wide, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.01.em),
    meta = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    caption = TextStyle(fontFamily = HlFonts.text, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    badge = TextStyle(fontFamily = HlFonts.wide, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em),
    wordmark = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 24.sp, letterSpacing = 0.02.em),
    billing = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.03.em),
    billingJoin = TextStyle(fontFamily = HlFonts.condensed, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 24.sp, letterSpacing = 0.02.em),
)

val LocalHlType = staticCompositionLocalOf { HouselightsType }
