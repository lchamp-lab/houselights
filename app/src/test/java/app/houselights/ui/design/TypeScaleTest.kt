package app.houselights.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The type scale: three voices and weights, nine sizes, eight line heights, a floor per voice, tracking by size. */
class TypeScaleTest {
    private val roles: Map<String, TextStyle> = with(HouselightsType) {
        mapOf(
            "display" to display, "headline" to headline, "subject" to subject, "place" to place,
            "placeCurrent" to placeCurrent, "rowTitle" to rowTitle, "cardName" to cardName, "cardTitle" to cardTitle,
            "lead" to lead, "body" to body, "entry" to entry, "meta" to meta, "group" to group, "code" to code,
            "spec" to spec, "jaquette" to jaquette, "badge" to badge,
        )
    }

    /** Signatures set to their own measure: their size, weight and tracking are their own. */
    private val signatures: Map<String, TextStyle> = with(HouselightsType) {
        mapOf(
            "titleSignature" to titleSignature,
            "billing" to billing,
            "billingJoin" to billingJoin,
            "codeSignature" to codeSignature,
            "figureSignature" to figureSignature,
        )
    }

    private val styles = roles + signatures

    @Test
    fun everyStyleIsListed() {
        // A new style must be added here too, so the checks below cover it.
        assertEquals(HlType::class.java.declaredFields.count { it.type == TextStyle::class.java }, styles.size)
    }

    @Test
    fun noWeightIsFaked() {
        styles.forEach { (name, style) ->
            val family = style.fontFamily as? FontListFontFamily ?: return@forEach
            val weights = family.fonts.map { it.weight }
            assertTrue("$name asks for ${style.fontWeight}, its width only has $weights", style.fontWeight in weights)
        }
    }

    @Test
    fun onlyCodeIsMonospaced() {
        styles.forEach { (name, style) ->
            assertEquals(name, name == "code", style.fontFamily == FontFamily.Monospace)
        }
        assertEquals(FontWeight.Normal, HouselightsType.code.fontWeight)
    }

    @Test
    fun theScaleSpeaksInThreeVoices() {
        val voices = setOf(HlFonts.condensed, HlFonts.text, HlFonts.expanded, HlFonts.mono)
        roles.forEach { (name, style) -> assertTrue("$name is in a fourth voice", style.fontFamily in voices) }
    }

    @Test
    fun theScaleUsesThreeWeights() {
        // Regular reads, Medium names, SemiBold announces; Bold is the signatures' alone.
        val weights = setOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
        roles.forEach { (name, style) -> assertTrue("$name is set in ${style.fontWeight}", style.fontWeight in weights) }
    }

    /** Every style but the doors' count, which is sized to them. */
    private val scaled = styles - "figureSignature"

    @Test
    fun nineSizes() {
        // Eight working sizes and the signature; the doors' count is sized to the doors.
        val scale = setOf(12f, 13f, 15f, 16f, 20f, 26f, 32f, 44f)
        roles.forEach { (name, style) -> assertTrue("$name is ${style.fontSize}", style.fontSize.value in scale) }
        scaled.forEach { (name, style) -> assertTrue("$name is ${style.fontSize}", style.fontSize.value in scale + 72f) }
        assertEquals(listOf("titleSignature"), scaled.filterValues { it.fontSize == 72.sp }.keys.toList())
    }

    @Test
    fun eightLineHeights() {
        // On the 4 dp grid, one a size, but 15's and 16's: a name's 20, a paragraph's 24.
        val lines = setOf(16f, 20f, 24f, 28f, 32f, 40f, 52f, 72f)
        scaled.forEach { (name, style) -> assertTrue("$name: line height ${style.lineHeight}", style.lineHeight.value in lines) }
        val bySize = scaled.values.groupBy({ it.fontSize.value }, { it.lineHeight.value })
        bySize.forEach { (size, heights) ->
            val expected = if (size == 15f || size == 16f) setOf(20f, 24f) else setOf(heights.first())
            assertEquals("lines of $size", expected, heights.toSet())
        }
    }

    @Test
    fun eachVoiceHasItsSizes() {
        // A floor a voice: 13 for what is read, formats included; 12 in a mark's box alone; 16 for the poster, the
        // billing block's small words (13) aside.
        fun sizes(family: FontFamily, but: Set<String> = emptySet()) =
            scaled.filter { (name, style) -> style.fontFamily == family && name !in but }.values.map { it.fontSize.value }.toSet()
        assertEquals(setOf(16f, 20f, 26f, 32f, 44f, 72f), sizes(HlFonts.condensed, but = setOf("billingJoin")))
        assertEquals(setOf(13f, 15f, 16f), sizes(HlFonts.text))
        assertEquals(setOf(12f, 13f, 15f), sizes(HlFonts.expanded))
        assertEquals(setOf(15f), sizes(HlFonts.mono))
        assertEquals(16f to 13f, HouselightsType.billing.fontSize.value to HouselightsType.billingJoin.fontSize.value)
        assertEquals(listOf("badge"), scaled.filterValues { it.fontSize == 12.sp }.keys.toList())
    }

    @Test
    fun framesKeepTheirLines() {
        // Home's grid and its doors' band, a row's head and a hero are measured from these.
        with(HouselightsType) {
            assertEquals(listOf(20f, 20f, 28f, 24f, 20f), listOf(meta, cardName, rowTitle, body, entry).map { it.lineHeight.value })
        }
    }

    @Test
    fun trackingFollowsSize() {
        roles.forEach { (name, style) -> assertEquals(name, trackingFor(style.fontSize), style.letterSpacing) }
        assertEquals(trackingFor(HouselightsType.titleSignature.fontSize), HouselightsType.titleSignature.letterSpacing)
        // Tighter from 32, none from 15 to 26, more open at 13 and 12.
        assertEquals(listOf((-0.01).em, 0.em, 0.em, 0.01.em, 0.02.em), listOf(32, 26, 15, 13, 12).map { trackingFor(it.sp) })
        // Closer as it grows, more open as it shrinks.
        val sizes = roles.values.map { it.fontSize }.distinct().sortedBy { it.value }
        sizes.zipWithNext().forEach { (small, large) ->
            assertTrue("$small is set closer than $large", trackingFor(small).value >= trackingFor(large).value)
        }
    }

    @Test
    fun boxesKeepToTheirLetters() {
        // Line height goes between lines only: the layouts are measured from the type.
        val trimmed = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.Both)
        styles.forEach { (name, style) -> assertEquals(name, trimmed, style.lineHeightStyle) }
    }

    @Test
    fun titlesBalanceAndParagraphsBreakForReading() {
        val titles = listOf("display", "headline", "subject", "rowTitle", "cardName", "cardTitle", "lead", "titleSignature")
        titles.forEach { assertEquals(it, LineBreak.Heading, styles.getValue(it).lineBreak) }
        listOf("body", "meta").forEach { assertEquals(it, LineBreak.Paragraph, roles.getValue(it).lineBreak) }
    }

    @Test
    fun theCoreReadsAtFifteen() {
        // Overviews, button words and names to choose at 15; around them, 13; a group's title a step under its names.
        with(HouselightsType) {
            assertEquals(15f to 24f, body.fontSize.value to body.lineHeight.value)
            assertEquals(FontWeight.Normal, body.fontWeight)
            assertEquals(15f to 20f, entry.fontSize.value to entry.lineHeight.value)
            assertEquals(FontWeight.Medium, entry.fontWeight)
            assertEquals(13f to 20f, meta.fontSize.value to meta.lineHeight.value)
            assertNotSame(entry, group)
            assertEquals(13f to FontWeight.Medium, group.fontSize.value to group.fontWeight)
            assertEquals(16f to 24f, lead.fontSize.value to lead.lineHeight.value)
        }
    }

    @Test
    fun tvMaterialsRolesAreTheScales() {
        with(HouselightsType) {
            val expected = listOf(
                display, headline, subject, headline, subject, rowTitle, lead, entry, entry, body, meta, meta, entry, group, badge,
            )
            val roles = with(materialTypography) {
                listOf(
                    displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
                    titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall,
                )
            }
            assertEquals(expected, roles)
        }
    }

    @Test
    fun theVoicesKeepTheirWidths() {
        // The poster's voice names places, rows and cards; the technical one, formats and printed marks.
        listOf("place", "placeCurrent", "rowTitle", "cardName").forEach {
            assertEquals(it, HlFonts.condensed, roles.getValue(it).fontFamily)
        }
        listOf("jaquette", "badge").forEach { assertEquals(it, HlFonts.expanded, roles.getValue(it).fontFamily) }
        assertEquals(FontWeight.SemiBold, HouselightsType.placeCurrent.fontWeight)
        assertEquals(HouselightsType.place.fontSize, HouselightsType.placeCurrent.fontSize)
    }

    @Test
    fun theEmberLightLiesOnTheWordsOnly() {
        // A gradient over the glyphs, nothing else of the style changed.
        val lit = HouselightsType.placeCurrent.emberLit(HouselightsColors)
        assertEquals(HouselightsColors.emberLight(), lit.brush)
        assertEquals(HouselightsType.placeCurrent.copy(brush = lit.brush), lit)
    }

    @Test
    fun capitalsKeepNoTabularFigures() {
        // Archivo's `case` runs after `tnum` and sets proportional figures again: asking for both fails.
        styles.forEach { (name, style) ->
            val features = style.fontFeatureSettings.orEmpty()
            assertFalse(name, "case" in features && "tnum" in features)
        }
    }
}
