package app.houselights.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The type scale: three voices and weights, eight sizes and a signature, lines on the grid, a floor per voice, tracking by size. */
class TypeScaleTest {
    /** Every style of the scale, read off [HlType] so a new one is checked as it is declared. */
    private val styles: Map<String, TextStyle> = propertiesOf(HouselightsType, TextStyle::class.java)

    /** Signatures set to their own measure: their size, weight and tracking are their own. */
    private val signatures = setOf("titleSignature", "billing", "billingJoin", "codeSignature", "figureSignature")

    private val roles = styles - signatures

    @Test
    fun theSignaturesAreStyles() {
        assertTrue("No styles found", roles.isNotEmpty())
        assertEquals(emptySet<String>(), signatures - styles.keys)
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
    fun eightSizesAndTheSignature() {
        val sizes = roles.values.map { it.fontSize.value }.toSet()
        assertTrue("The roles take ${sizes.sorted()}", sizes.size <= WORKING_SIZES)
        // The signatures take the roles' sizes, but the title's, alone above them all.
        val above = scaled.filterValues { it.fontSize.value !in sizes }
        assertEquals(setOf("titleSignature"), above.keys)
        assertTrue(above.values.single().fontSize.value > sizes.max())
    }

    @Test
    fun linesSitOnTheGrid() {
        scaled.forEach { (name, style) ->
            assertEquals("$name: line height ${style.lineHeight}", 0f, style.lineHeight.value % GRID, 0f)
            assertTrue("$name: its lines overlap", style.lineHeight.value >= style.fontSize.value)
        }
        val lines = scaled.values.map { it.lineHeight.value }.toSet()
        assertTrue("Line heights ${lines.sorted()}", lines.size <= LINE_HEIGHTS)
        // A paragraph's size and a tagline's also set a name, on a name's line: two lines there, one a size elsewhere.
        with(HouselightsType) {
            val bySize = scaled.values.groupBy({ it.fontSize }, { it.lineHeight }).mapValues { it.value.toSet() }
            assertEquals(setOf(body.fontSize, lead.fontSize), bySize.filterValues { it.size > 1 }.keys)
            assertEquals(setOf(entry.lineHeight, body.lineHeight), bySize.getValue(body.fontSize))
            assertEquals(setOf(cardName.lineHeight, lead.lineHeight), bySize.getValue(lead.fontSize))
            assertEquals(entry.lineHeight, cardName.lineHeight)
        }
    }

    @Test
    fun eachVoiceKeepsToItsFloor() {
        // What is read keeps to 13, formats included; 12 in a mark's box alone.
        assertEquals(setOf("badge"), scaled.filterValues { it.fontSize.value < READING_FLOOR }.keys)
        // The poster's voice keeps to 16, but the billing block's small words, smaller than its names.
        val poster = scaled.filter { (name, style) -> style.fontFamily == HlFonts.condensed && name != "billingJoin" }
        poster.forEach { (name, style) -> assertTrue("$name is ${style.fontSize}", style.fontSize.value >= POSTER_FLOOR) }
        assertTrue(HouselightsType.billingJoin.fontSize < HouselightsType.billing.fontSize)
        // Raw text in one size.
        assertEquals(1, scaled.values.filter { it.fontFamily == HlFonts.mono }.map { it.fontSize }.distinct().size)
    }

    @Test
    fun framesMeasuredFromTheTypeHoldItsLines() {
        // A hero's band, written in dp: its logo, a line of facts or an episode's heading, two overview lines.
        with(HouselightsType) {
            val words = HeroDefaults.titleHeight.value + HlSpace.s.value + maxOf(meta.lineHeight.value, entry.lineHeight.value) +
                HlSpace.s.value + HERO_OVERVIEW_LINES * body.lineHeight.value
            assertEquals(words, HlLayout.hero.value, 0f)
            // An episode's overview, whole lines of body.
            assertEquals(0f, HlLayout.episodeOverview.value % body.lineHeight.value, 0f)
        }
    }

    @Test
    fun trackingFollowsSize() {
        roles.forEach { (name, style) -> assertEquals(name, trackingFor(style.fontSize), style.letterSpacing) }
        assertEquals(trackingFor(HouselightsType.titleSignature.fontSize), HouselightsType.titleSignature.letterSpacing)
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
    fun theCoreAndWhatSurroundsIt() {
        with(HouselightsType) {
            // Paragraphs and names to choose at one size, a name weighing more than what is read.
            assertEquals(body.fontSize, entry.fontSize)
            assertTrue(checkNotNull(entry.fontWeight) > checkNotNull(body.fontWeight))
            // Around them a step smaller: facts, a group's title, formats; a group's title a step under the names it heads.
            listOf(meta, group, jaquette).forEach { assertTrue(it.fontSize < body.fontSize) }
            assertNotSame(entry, group)
            assertEquals("a group's title names, as the names it heads do", entry.fontWeight, group.fontWeight)
            // A tagline, an episode's name, the keyboard's letters: a step over the names.
            assertTrue(lead.fontSize > entry.fontSize)
        }
    }

    @Test
    fun tvMaterialsRolesAreTheScales() {
        val material = with(materialTypography) {
            listOf(
                displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall,
            )
        }
        material.forEach { role -> assertTrue("$role is not on the scale", role in roles.values) }
        // Unstyled text is body: the theme provides bodyLarge to text that names no style.
        assertEquals(HouselightsType.body, materialTypography.bodyLarge)
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

    private companion object {
        /** DESIGN.md's eight working sizes, the signature aside, and its eight line heights on the 4 sp grid. */
        const val WORKING_SIZES = 8
        const val LINE_HEIGHTS = 8
        const val GRID = 4f

        /** DESIGN.md's floor a voice: what is read, the poster's. */
        const val READING_FLOOR = 13f
        const val POSTER_FLOOR = 16f

        /** The overview lines `HeroInfo` sets under the facts. */
        const val HERO_OVERVIEW_LINES = 2
    }
}
