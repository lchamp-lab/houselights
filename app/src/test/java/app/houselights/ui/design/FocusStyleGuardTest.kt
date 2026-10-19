package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import app.houselights.ui.design.components.Lift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Focus is said in one place, `FocusStyle`, in two gestures (frame, fill) and the ring, and drawn by one
 * surface, `HlFocusSurface`, whose frame a row of cards takes over (`RowFrame`), so a row holds framed cards only:
 * no component builds its own white, frame or growth, and tv-material's focus (its scale, glow and Material
 * timing) is never drawn.
 */
class FocusStyleGuardTest {
    private val main = File("src/main/java/app/houselights")
    private val components = File("src/main/java/app/houselights/ui/design/components")
    private val home = setOf("FocusStyle.kt", "FocusSurface.kt", "RowFrame.kt")

    /** What only FocusStyle and its surface may write. */
    private val rules = listOf(
        Regex("""\bFocusLook\("""),
        Regex("""\bGesture\.(Frame|Fill)\b"""),
        Regex("""\b(c|colors|Hl\.colors)\.focus\b"""),
        Regex("""\bHlFocus\.([A-Z]+_SCALE|frame|frameGap|glow)\b"""),
    )

    private fun sources() = kotlinSources(components).filter { it.name !in home }

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
        val found = kotlinSources(ui).filter { !it.startsWith(design) }
            .linesWhere(ui) { "FocusStyle." in it || "HlFocusSurface(" in it }
        assertTrue("Focus is drawn by the design system's components:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun everyFocusableSurfaceTakesAGestureFromFocusStyle() {
        // A surface's arguments run to the opening of its content lambda: `) {`.
        val surface = Regex("""(?<![A-Za-z]|fun )HlFocusSurface\(""")
        val calls = sources().flatMap { file ->
            val text = file.readText()
            surface.findAll(text).map { match ->
                val end = text.indexOf(") {", match.range.last).takeIf { it > 0 } ?: text.length
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.name}:$line" to text.substring(match.range.first, end)
            }.toList()
        }
        assertTrue("No HlFocusSurface found", calls.isNotEmpty())
        val missing = calls.filter { (_, arguments) -> "look = FocusStyle." !in arguments }.map { it.first }
        assertTrue("A focusable surface without a FocusStyle gesture:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** A row's one frame is the focus of its cards: a control in a row would show focus twice, its own and the frame left behind. */
    @Test
    fun aRowHoldsOnlyFramedCards() {
        val ui = File("src/main/java/app/houselights/ui")
        val row = Regex("""(?<!fun )\bMediaRow\(""")
        val controls = listOf(
            "HlButton", "HlIconButton", "HlTextField", "HlSettingRow", "HlListItem",
            "HlNavItem", "HlSegmented", "HlMenuButton", "HlTitleSwitch",
        )
        val control = Regex("""\b(${controls.joinToString("|")})\(""")
        val found = kotlinSources(ui).flatMap { file ->
            val text = file.readText()
            row.findAll(text).mapNotNull { call ->
                control.find(text.callFrom(call.range.last))?.let { "${file.name}: ${it.value} in a MediaRow" }
            }.toList()
        }
        assertTrue("Rows hold framed cards only:\n" + found.joinToString("\n"), found.isEmpty())
        val inside = """MediaRow(header = null) { item { HlButton("Lire", {}) } }"""
        val after = """MediaRow(header = null) { item { PosterCard(item, {}) } }""" + "\n" + """HlButton("Lire", {})"""
        assertTrue("A control inside a row is seen", control.containsMatchIn(inside.callFrom(inside.indexOf('('))))
        assertTrue("A control after a row is not", !control.containsMatchIn(after.callFrom(after.indexOf('('))))
    }

    /** The call whose arguments open at [open]: its arguments, then its trailing lambda, brackets balanced. */
    private fun String.callFrom(open: Int): String {
        var end = closing(open, '(', ')')
        val lambda = (end + 1 until length).firstOrNull { !this[it].isWhitespace() } ?: -1
        if (lambda >= 0 && this[lambda] == '{') end = closing(lambda, '{', '}')
        return substring(open, end + 1)
    }

    private fun String.closing(open: Int, opens: Char, closes: Char): Int {
        var depth = 0
        for (i in open until length) {
            if (this[i] == opens) depth++
            if (this[i] == closes && --depth == 0) return i
        }
        return length - 1
    }

    /** Only a picture grows: a plate's white shows focus, so a fill keeps its size. */
    @Test
    fun controlsDoNotGrow() {
        assertEquals(1f, Lift.Still.scale, 0f)
        val style = components.resolve("FocusStyle.kt").readText()
        val fill = style.substring(style.indexOf("fun fill("), style.indexOf("fun ring("))
        assertTrue("A fill grows", "Lift.Still" in fill)
    }

    /** tv-material's surfaces bring Google TV's focus (scale, glow, 300 ms Material curves): only its theme stays. */
    @Test
    fun onlyTheThemeOfTvMaterial() {
        val allowed = setOf("Text", "Icon", "LocalContentColor", "LocalTextStyle", "MaterialTheme", "darkColorScheme", "Typography")
        val use = Regex("""androidx\.tv\.material3\.([\w*]+)""")
        val found = kotlinSources(main).linesWhere(main) { line ->
            use.findAll(line).any { it.groupValues[1] !in allowed }
        }
        assertTrue("Focus is drawn by HlFocusSurface; only tv-material's theme stays:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
