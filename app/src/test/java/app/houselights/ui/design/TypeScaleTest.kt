package app.houselights.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The type scale: three voices and weights, one scale, tracking by size, trimmed boxes, monospace for code. */
class TypeScaleTest {
    private val roles: Map<String, TextStyle> = with(HouselightsType) {
        mapOf(
            "display" to display, "headline" to headline, "subject" to subject, "place" to place,
            "placeCurrent" to placeCurrent, "rowTitle" to rowTitle, "cardName" to cardName, "cardTitle" to cardTitle,
            "title" to title, "lead" to lead, "read" to read, "body" to body, "entry" to entry, "meta" to meta,
            "caption" to caption, "group" to group, "code" to code,
            "spec" to spec, "jaquette" to jaquette, "badge" to badge,
        )
    }

    /** Signatures set to their own measure: their size, weight and tracking are their own. */
    private val signatures: Map<String, TextStyle> = with(HouselightsType) {
        mapOf(
            "titleSignature" to titleSignature, "billing" to billing, "billingJoin" to billingJoin, "codeSignature" to codeSignature,
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
        // Regular reads, SemiBold names, Bold announces.
        val weights = setOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)
        roles.forEach { (name, style) -> assertTrue("$name is set in ${style.fontWeight}", style.fontWeight in weights) }
    }

    @Test
    fun sizesStayOnTheScale() {
        // The signatures are set to their own measure; every role uses the scale.
        val scale = setOf(12f, 14f, 16f, 20f, 24f, 26f, 32f, 44f, 64f)
        roles.forEach { (name, style) ->
            assertTrue("$name is ${style.fontSize}", style.fontSize.value in scale)
            assertEquals("$name: line height on the 2 sp grid", 0f, style.lineHeight.value % 2f)
        }
    }

    @Test
    fun trackingFollowsSize() {
        roles.forEach { (name, style) -> assertEquals(name, trackingFor(style.fontSize), style.letterSpacing) }
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
        listOf("display", "headline", "subject", "rowTitle", "cardName", "cardTitle", "title", "lead").forEach {
            assertEquals(it, LineBreak.Heading, roles.getValue(it).lineBreak)
        }
        listOf("read", "body", "caption").forEach { assertEquals(it, LineBreak.Paragraph, roles.getValue(it).lineBreak) }
    }

    @Test
    fun secondaryScreensReadFromTheCouch() {
        // Running text at 20 on 32, Regular; a group's title is entry under another name; a row's name in lead.
        with(HouselightsType) {
            assertEquals(20f to 32f, read.fontSize.value to read.lineHeight.value)
            assertEquals(FontWeight.Normal, read.fontWeight)
            assertEquals(HlFonts.text, read.fontFamily)
            assertSame(entry, group)
            assertEquals(20f, lead.fontSize.value)
        }
    }

    @Test
    fun theVoicesKeepTheirWidths() {
        // The poster's voice names places, rows and cards; the technical one, formats and printed marks.
        listOf("place", "placeCurrent", "rowTitle", "cardName").forEach {
            assertEquals(it, HlFonts.condensed, roles.getValue(it).fontFamily)
        }
        listOf("jaquette", "badge").forEach { assertEquals(it, HlFonts.expanded, roles.getValue(it).fontFamily) }
        assertEquals(FontWeight.Bold, HouselightsType.placeCurrent.fontWeight)
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
