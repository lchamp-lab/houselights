package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.source
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every `HlLayout` measure is read by a screen; one only a component reads belongs in its `…Defaults`. */
class LayoutTokensTest {
    private val sources = File("src/main/java/app/houselights")
    private val design = File(sources, "ui/design")

    /** The catalogue shows tokens; it is not a screen. */
    private val catalog = File(sources, "ui/catalog")
    private val dimens = File(design, "Dimens.kt")

    /** Read only by the theme, by nature: the canvas every screen is laid out on. */
    private val grid = setOf("CANVAS_WIDTH")

    @Test
    fun everyLayoutMeasureIsReadByAScreen() {
        val text = source(dimens).code
        val start = text.indexOf("object HlLayout")
        val layout = text.substring(start, text.indexOf("\n}\n", start))
        // Its members as Dimens.kt indents them: composable getters, which reflection cannot read outside a composition.
        val members = Regex("""^\s{4}(?:const )?(?:val|fun) (\w+)""", RegexOption.MULTILINE).findAll(layout)
            .map { it.groupValues[1] }.filter { it !in grid }.toList()
        assertTrue("HlLayout's measures not found in Dimens.kt: ${members.size}", members.size >= MEASURES_AT_LEAST)
        val screens = kotlinSources(sources)
            .filter { !it.file.startsWith(design) && !it.file.startsWith(catalog) }
            .joinToString("\n") { it.code }
        val unread = members.filter { !Regex("""HlLayout\.$it\b""").containsMatchIn(screens) }
        assertTrue("Measures no screen reads, to move into a component's Defaults: $unread", unread.isEmpty())
    }

    private companion object {
        /** Fewer than HlLayout declares, so a reformat that hides most of them from the parse fails. */
        const val MEASURES_AT_LEAST = 30
    }
}
