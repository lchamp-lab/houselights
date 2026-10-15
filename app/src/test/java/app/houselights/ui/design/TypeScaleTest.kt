package app.houselights.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type scale keeps its promises: three voices, three weights, real weights only, one scale,
 * tracking that follows size, boxes kept to their letters, titles and paragraphs broken for reading, monospace
 * only for code.
 */
class TypeScaleTest {
    private val roles: Map<String, TextStyle> = with(HouselightsType) {
        mapOf(
            "display" to display, "headline" to headline, "subject" to subject, "cardTitle" to cardTitle,
            "title" to title, "lead" to lead, "body" to body, "entry" to entry, "meta" to meta,
            "caption" to caption, "group" to group, "code" to code,
            "spec" to spec, "badge" to badge,
        )
    }

    /** Set to their own measure: their size, weight and tracking are theirs. */
    private val signatures: Map<String, TextStyle> = with(HouselightsType) {
        mapOf("billing" to billing, "billingJoin" to billingJoin, "wordmark" to wordmark, "wordmarkHero" to wordmarkHero)
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
        val scale = setOf(12f, 14f, 16f, 20f, 24f, 32f, 44f, 64f)
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
        listOf("display", "headline", "subject", "cardTitle", "title", "lead").forEach {
            assertEquals(it, LineBreak.Heading, roles.getValue(it).lineBreak)
        }
        listOf("body", "caption").forEach { assertEquals(it, LineBreak.Paragraph, roles.getValue(it).lineBreak) }
    }

    @Test
    fun capitalsKeepNoTabularFigures() {
        // Archivo's `case` runs after `tnum` and sets proportional figures again: asking for both lies.
        styles.forEach { (name, style) ->
            val features = style.fontFeatureSettings.orEmpty()
            assertFalse(name, "case" in features && "tnum" in features)
        }
    }
}
