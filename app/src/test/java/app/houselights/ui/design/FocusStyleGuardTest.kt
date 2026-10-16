package app.houselights.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Focus is said in one place, `FocusStyle`: projector white in three gestures (fill, lift, ring).
 * No component builds its own (a white of its own, a scale of its own, an outline of its own), so a
 * new component cannot invent a fourth gesture, and every focusable surface takes one of the three.
 */
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

    private fun sources() = components.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != home }.toList()

    @Test
    fun noComponentBuildsItsOwnFocus() {
        assertTrue("Sources not found from ${File(".").absolutePath}", components.isDirectory)
        val found = sources().flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                rules.firstOrNull { it.containsMatchIn(line) }?.let { "${file.name}:${i + 1}: ${line.trim()}" }
            }
        }
        assertTrue("Focus belongs to FocusStyle:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /**
     * A screen draws no focus at all: it composes the components that take theirs from FocusStyle.
     * One that reached for it would be a component copied into a screen, out of this guard's sight.
     */
    @Test
    fun noScreenDrawsFocus() {
        val ui = File("src/main/java/app/houselights/ui")
        val design = ui.resolve("design")
        val found = ui.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.startsWith(design) }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if ("FocusStyle." in line) "${file.relativeTo(ui).path}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
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
