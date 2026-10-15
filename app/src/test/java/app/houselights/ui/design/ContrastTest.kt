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
        "emberFrom" to c.emberFrom,
        "emberTo" to c.emberTo,
        "onFocus" to c.onFocus,
        "onFocusSecondary" to c.onFocusSecondary,
        "focus" to c.focus,
    )

    /** The surfaces the components compose, each over what it sits on. */
    private val surfaces = mapOf(
        "background" to c.background,
        // Glass: side sheets, menus, dialogs.
        "surfaceRaised" to c.surfaceRaised,
        // A thing on the room, one step up: a secondary control at rest, a quiet panel, a field.
        "FILL" to c.content.over(HlAlpha.FILL, c.background),
        // The same step on glass, where it is fainter, and a row or a well on the room.
        "FILL_FAINT" to c.content.over(HlAlpha.FILL_FAINT, c.background),
        "FILL_FAINT on surfaceRaised" to c.content.over(HlAlpha.FILL_FAINT, c.surfaceRaised),
        // A primary control at rest, a progress line's track off the artwork.
        "FILL_STRONG" to c.content.over(HlAlpha.FILL_STRONG, c.background),
        // A button in a side sheet.
        "FILL on surfaceRaised" to c.content.over(HlAlpha.FILL, c.surfaceRaised),
        // The current option of a track: its quiet pill in the track's well, or on the track's plate over an image.
        "FILL_STRONG on FILL_FAINT" to c.content.over(HlAlpha.FILL_STRONG, c.content.over(HlAlpha.FILL_FAINT, c.background)),
        "FILL_STRONG on VEIL on a white image" to c.content.over(HlAlpha.FILL_STRONG, c.background.over(HlAlpha.VEIL, Color.White)),
        "focus" to c.focus,
        // Whatever sits on artwork, on its plate over the worst image: a white one.
        "VEIL on a white image" to c.background.over(HlAlpha.VEIL, Color.White),
        // A text column over a backdrop: the left veil at the floor a white image gets.
        "the veil's floor on a white image" to c.background.over(HlScrim.need(1f), Color.White),
    )

    /** Every pair the system draws, as role, surface, use. */
    private val permitted = listOf(
        // Primary text, the count of unwatched episodes on its plate, the current option of a track.
        "content" on "background", "content" on "FILL", "content" on "surfaceRaised",
        "content" on "FILL", "content" on "FILL_FAINT", "content" on "FILL_FAINT on surfaceRaised",
        "content" on "FILL", "content" on "FILL_STRONG", "content" on "FILL_STRONG on FILL_FAINT",
        // On artwork: a round button's icon, the current option of a track, on their plate.
        "content" on "VEIL on a white image", "content" on "FILL_STRONG on VEIL on a white image",
        // The other options of a track at rest, in its well or on its plate over an image.
        "contentSecondary" on "VEIL on a white image",
        // A title's name, facts (the score and its star), overview and quietest words (genres, the
        // billing block's joins).
        "content" on "the veil's floor on a white image", "contentSecondary" on "the veil's floor on a white image",
        "contentTertiary" on "the veil's floor on a white image",
        // Descriptions, labels, a selected entry's summary, a button's detail at rest.
        "contentSecondary" on "background", "contentSecondary" on "FILL", "contentSecondary" on "surfaceRaised",
        "contentSecondary" on "FILL", "contentSecondary" on "FILL_FAINT", "contentSecondary" on "FILL_FAINT on surfaceRaised",
        "contentSecondary" on "FILL", "contentSecondary" on "FILL_STRONG",
        // Hints, placeholders, an entry's summary at rest; never on a strong fill.
        "contentTertiary" on "background", "contentTertiary" on "FILL", "contentTertiary" on "surfaceRaised",
        "contentTertiary" on "FILL", "contentTertiary" on "FILL_FAINT",
        // A value the user chose, the check of the current choice, a failed result, the wordmark.
        "ember" on "background", "ember" on "surfaceRaised", "ember" on "FILL",
        "ember" on "FILL_FAINT", "ember" on "FILL_FAINT on surfaceRaised",
        // What needs attention beside what it concerns, over a backdrop (images that cannot be fetched).
        "ember" on "the veil's floor on a white image",
        // A focused control: its name, and the line under it.
        "onFocus" on "focus", "onFocusSecondary" on "focus",
        // Focus itself, a white fill or outline, against what is around it: a track's well and its
        // current pill, a plate over an image, the room (WCAG 2.4.13 asks 3:1 of a focus indicator).
        ("focus" on "FILL_FAINT").mark(), ("focus" on "FILL_STRONG on FILL_FAINT").mark(),
        ("focus" on "VEIL on a white image").mark(), ("focus" on "background").mark(),
        // Marks: a lit round button's icon (a favourite), the check of your choice and the
        // new dot on their plate, a card's progress on its plate. The watched check is white (above).
        ("ember" on "FILL").mark(),
        ("ember" on "VEIL on a white image").mark(),
        // A meter's inks on its track, a gap apart: projector white, secondary, and tertiary (below).
        ("content" on "FILL_FAINT").mark(), ("contentSecondary" on "FILL_FAINT").mark(),
        // A button's detail lit at rest (an active filter), on a secondary or a primary button.
        "ember" on "FILL", "ember" on "FILL_STRONG",
        // A question asked in a sheet: Cancel, and the action asked about, its label ember at rest.
        "content" on "FILL on surfaceRaised", "ember" on "FILL on surfaceRaised",
        // A disabled control on the room: its words (above) and its icon, tertiary in its well; on
        // its plate over an image, a round button's icon stays secondary.
        ("contentTertiary" on "FILL_FAINT").mark(),
        ("contentSecondary" on "VEIL on a white image").mark(),
        // The ember light where it is drawn: the wordmark, a progress line on its track or plate,
        // a lit edge, the counter of a scan. Both ends, since a gradient shows all of it.
        ("emberFrom" on "background").mark(), ("emberTo" on "background").mark(),
        ("emberFrom" on "surfaceRaised").mark(), ("emberTo" on "surfaceRaised").mark(),
        ("emberFrom" on "FILL_STRONG").mark(), ("emberTo" on "FILL_STRONG").mark(),
        ("emberFrom" on "VEIL on a white image").mark(), ("emberTo" on "VEIL on a white image").mark(),
        // A state's light: pending, and the ring of off, on the room, in a sheet and in a busy button.
        ("contentTertiary" on "background").mark(), ("contentTertiary" on "surfaceRaised").mark(),
        ("contentTertiary" on "FILL_STRONG").mark(), ("contentTertiary" on "FILL").mark(),
    )

    private val forbidden = listOf(
        "contentTertiary" on "FILL_STRONG",
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
