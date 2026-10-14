package app.houselights.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The type scale keeps its promises: real weights only, one scale, monospace only for code. */
class TypeScaleTest {
    private val styles: Map<String, TextStyle> = with(HouselightsType) {
        mapOf(
            "display" to display, "headline" to headline, "figure" to figure, "subject" to subject, "cardTitle" to cardTitle,
            "billing" to billing, "billingJoin" to billingJoin, "wordmark" to wordmark, "wordmarkHero" to wordmarkHero,
            "title" to title, "lead" to lead, "body" to body, "entry" to entry, "meta" to meta,
            "caption" to caption, "detail" to detail, "code" to code,
            "label" to label, "spec" to spec, "badge" to badge,
        )
    }

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
    fun sizesStayOnTheScale() {
        // Billing and the welcome's wordmark are set to their own measure; everything else uses the scale.
        val scale = setOf(12f, 14f, 16f, 20f, 24f, 32f, 44f, 64f)
        val ownMeasure = setOf("billing", "wordmarkHero")
        styles.filterKeys { it !in ownMeasure }.forEach { (name, style) ->
            assertTrue("$name is ${style.fontSize}", style.fontSize.value in scale)
            assertEquals("$name: line height on the 2 sp grid", 0f, style.lineHeight.value % 2f)
        }
    }
}
