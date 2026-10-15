package app.houselights.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `HlLayout` is the screens' grid: every measure in it is read by a screen. A measure only a
 * component reads belongs with that component, in its `…Defaults` (Dimens.kt), and a measure no
 * one reads goes.
 */
class LayoutTokensTest {
    private val sources = File("src/main/java/app/houselights")
    private val design = File(sources, "ui/design")
    private val dimens = File(design, "Dimens.kt")

    /** Read by the theme alone, by nature: the canvas every screen is laid out on. */
    private val grid = setOf("CANVAS_WIDTH")

    @Test
    fun everyLayoutMeasureIsReadByAScreen() {
        assertTrue("Sources not found from ${File(".").absolutePath}", dimens.isFile)
        val text = dimens.readText()
        val layout = text.substring(text.indexOf("object HlLayout"), text.indexOf("\n}\n", text.indexOf("object HlLayout")))
        val members = Regex("""^\s{4}(?:const )?(?:val|fun) (\w+)""", RegexOption.MULTILINE).findAll(layout)
            .map { it.groupValues[1] }.filter { it !in grid }.toList()
        val screens = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.startsWith(design.path) }
            .joinToString("\n") { it.readText() }
        val unread = members.filter { !Regex("""HlLayout\.$it\b""").containsMatchIn(screens) }
        assertTrue("Measures no screen reads, to move into a component's Defaults: $unread", unread.isEmpty())
    }
}
