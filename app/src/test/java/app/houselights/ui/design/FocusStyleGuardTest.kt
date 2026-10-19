package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Focus is drawn only by `FocusStyle`, in three gestures (fill, lift, ring); no component builds its own. */
class FocusStyleGuardTest {
    private val components = File("src/main/java/app/houselights/ui/design/components")
    private val home = "FocusStyle.kt"

    /** What only FocusStyle may write. */
    private val rules = listOf(
        Regex("""\bfocused(Disabled)?(ContainerColor|ContentColor|Border|Scale)\s*="""),
        Regex("""\bpressed(ContainerColor|ContentColor)\s*="""),
        Regex("""\b(c|colors|Hl\.colors)\.focus\b"""),
        Regex("""\bHlFocus\.[A-Z]+_SCALE\b"""),
    )

    private fun sources() = kotlinSources(components).filter { it.name != home }

    @Test
    fun noComponentBuildsItsOwnFocus() {
        val found = sources().linesWhere(components) { line -> rules.any { it.containsMatchIn(line) } }
        assertTrue("Focus belongs to FocusStyle:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A screen drawing focus would be a component copied out of this guard's sight. */
    @Test
    fun noScreenDrawsFocus() {
        val ui = File("src/main/java/app/houselights/ui")
        val design = ui.resolve("design")
        val found = kotlinSources(ui).filter { !it.startsWith(design) }.linesWhere(ui) { "FocusStyle." in it }
        assertTrue("Focus is drawn by the design system's components:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun everyFocusableSurfaceTakesAGestureFromFocusStyle() {
        // A clickable Surface's arguments run to the opening of its content lambda: `) {`.
        val surface = Regex("""(?<![A-Za-z])Surface\(\s*\n?\s*onClick""")
        val missing = sources().flatMap { file ->
            val text = file.readText()
            surface.findAll(text).mapNotNull { match ->
                val end = text.indexOf(") {", match.range.last).takeIf { it > 0 } ?: text.length
                val arguments = text.substring(match.range.first, end)
                if ("FocusStyle." in arguments) null else "${file.name}:${text.substring(0, match.range.first).count { it == '\n' } + 1}"
            }.toList()
        }
        assertTrue("A focusable surface without a FocusStyle gesture:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
