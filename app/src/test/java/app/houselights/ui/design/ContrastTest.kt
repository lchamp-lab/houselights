package app.houselights.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN.md promises 4.5:1 for text and 3:1 for icons and marks. Translucent layers are composed
 * over what they really sit on, so each ratio is the one on screen. A pair missing from [permitted]
 * is not allowed: add it here, with its ratio passing, before a component uses it.
 */
class ContrastTest {
    private enum class Use(val minimum: Float) { Text(4.5f), Mark(3f) }

    private val c = HouselightsColors

    private val roles = mapOf(
        "content" to c.content,
        "contentSecondary" to c.contentSecondary,
        "contentTertiary" to c.contentTertiary,
        "ember" to c.ember,
        "onFocus" to c.onFocus,
        "onFocusSecondary" to c.onFocusSecondary,
    )

    /** The surfaces the components compose, each over what it sits on. */
    private val surfaces = mapOf(
        "background" to c.background,
        "surface" to c.surface,
        "surfaceRaised" to c.surfaceRaised,
        "QUIET_SURFACE" to c.surface.over(HlAlpha.QUIET_SURFACE, c.background),
        "WELL" to c.content.over(HlAlpha.WELL, c.background),
        "WELL on surfaceRaised" to c.content.over(HlAlpha.WELL, c.surfaceRaised),
        "CONTROL_FILL" to c.content.over(HlAlpha.CONTROL_FILL, c.background),
        "CONTROL_FILL_STRONG" to c.content.over(HlAlpha.CONTROL_FILL_STRONG, c.background),
        // A button in a side sheet.
        "CONTROL_FILL on surfaceRaised" to c.content.over(HlAlpha.CONTROL_FILL, c.surfaceRaised),
        "FOCUS_QUIET on WELL" to c.content.over(HlAlpha.FOCUS_QUIET, c.content.over(HlAlpha.WELL, c.background)),
        "focus" to c.focus,
        // Whatever sits on artwork, on its plate over the worst image: a white one.
        "PLATE on a white image" to c.background.over(HlAlpha.PLATE, Color.White),
        // A text column over a backdrop: the left veil at the floor a white image gets.
        "the veil's floor on a white image" to c.background.over(HlScrim.need(1f), Color.White),
    )

    /** Every pair the system draws, as role, surface, use. */
    private val permitted = listOf(
        // Primary text, the count of unwatched episodes on its plate, a track option under focus.
        "content" on "background", "content" on "surface", "content" on "surfaceRaised",
        "content" on "QUIET_SURFACE", "content" on "WELL", "content" on "WELL on surfaceRaised",
        "content" on "CONTROL_FILL", "content" on "CONTROL_FILL_STRONG", "content" on "FOCUS_QUIET on WELL",
        // On artwork: a round button's icon and a track's options at rest, on their plate.
        "content" on "PLATE on a white image",
        // A title's name, facts (the score and its star), overview and quietest words (genres, the
        // billing block's joins).
        "content" on "the veil's floor on a white image", "contentSecondary" on "the veil's floor on a white image",
        "contentTertiary" on "the veil's floor on a white image",
        // Descriptions, labels, a selected entry's summary, a button's detail at rest.
        "contentSecondary" on "background", "contentSecondary" on "surface", "contentSecondary" on "surfaceRaised",
        "contentSecondary" on "QUIET_SURFACE", "contentSecondary" on "WELL", "contentSecondary" on "WELL on surfaceRaised",
        "contentSecondary" on "CONTROL_FILL", "contentSecondary" on "CONTROL_FILL_STRONG",
        // Hints, placeholders, an entry's summary at rest; never on a raised WELL or a strong fill.
        "contentTertiary" on "background", "contentTertiary" on "surface", "contentTertiary" on "surfaceRaised",
        "contentTertiary" on "QUIET_SURFACE", "contentTertiary" on "WELL",
        // A value the user chose, the check of the current choice, a failed result, the wordmark.
        "ember" on "background", "ember" on "surfaceRaised", "ember" on "QUIET_SURFACE",
        "ember" on "WELL", "ember" on "WELL on surfaceRaised",
        // What needs attention beside what it concerns, over a backdrop (images that cannot be fetched).
        "ember" on "the veil's floor on a white image",
        // A focused control: its name, and the line under it.
        "onFocus" on "focus", "onFocusSecondary" on "focus",
        // Marks: a lit round button's icon (watched, a favourite), the check of your choice and the
        // new dot on their plate, a card's progress on its plate. The watched check is white (above).
        ("ember" on "CONTROL_FILL").mark(),
        ("ember" on "PLATE on a white image").mark(),
        // A meter's inks on its track, a gap apart: projector white, secondary, and tertiary (below).
        ("content" on "WELL").mark(), ("contentSecondary" on "WELL").mark(),
        // A button's detail lit at rest (an active filter), on a secondary or a primary button.
        "ember" on "CONTROL_FILL", "ember" on "CONTROL_FILL_STRONG",
        // A question asked in a sheet: Cancel, and the action asked about, its label ember at rest.
        "content" on "CONTROL_FILL on surfaceRaised", "ember" on "CONTROL_FILL on surfaceRaised",
        // A disabled control on the room: its words (above) and its icon, tertiary in its well; on
        // its plate over an image, a round button's icon stays secondary.
        ("contentTertiary" on "WELL").mark(),
        ("contentSecondary" on "PLATE on a white image").mark(),
    )

    /** Pairs DESIGN.md forbids by name; if one passes one day, the rule there can go. */
    private val forbidden = listOf(
        "contentTertiary" on "CONTROL_FILL_STRONG",
        // On artwork, the options of a track at rest are white: grey on the plate falls to 3.5:1.
        "contentSecondary" on "PLATE on a white image",
    )

    @Test
    fun permittedPairsAreReadable() {
        val failures = permitted.filter { ratio(it) < it.use.minimum }
            .map { "${it.role} on ${it.surface}: ${format(ratio(it))}, needs ${it.use.minimum}:1" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun forbiddenPairsStayForbidden() {
        forbidden.forEach {
            assertTrue("${it.role} on ${it.surface} now reaches ${format(ratio(it))}: update DESIGN.md", ratio(it) < it.use.minimum)
            assertTrue("${it.role} on ${it.surface} is forbidden", it !in permitted)
        }
    }

    private data class Pairing(val role: String, val surface: String, val use: Use = Use.Text) {
        fun mark() = copy(use = Use.Mark)
    }

    private infix fun String.on(surface: String) = Pairing(this, surface)

    private fun ratio(p: Pairing): Float = contrast(roles.getValue(p.role), surfaces.getValue(p.surface))

    /** WCAG contrast ratio of two opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun Color.over(alpha: Float, background: Color) = copy(alpha = alpha).compositeOver(background)

    private fun format(ratio: Float) = "%.2f:1".format(ratio)
}
