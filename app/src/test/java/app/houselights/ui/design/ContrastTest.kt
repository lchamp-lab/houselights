package app.houselights.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.houselights.ui.testing.contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN.md's 4.5:1 for text and 3:1 for marks, for each pair the system draws over what it sits on; forbidden pairs stay below. */
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
        "onEmber" to c.onEmber,
        "emberOnLight" to c.emberOnLight,
        "onFocus" to c.onFocus,
        "onFocusSecondary" to c.onFocusSecondary,
        "focus" to c.focus,
        "background" to c.background,
    )

    /** The surfaces the components compose, each over what it sits on. */
    private val surfaces = mapOf(
        "background" to c.background,
        // Glass: side sheets, menus, dialogs.
        "glass" to c.glass,
        // A thing on the room, one step up: a secondary control at rest, a quiet panel, a field.
        "FILL" to c.content.over(HlAlpha.FILL, c.background),
        // The same step on glass, where it is fainter, and a row or a well on the room.
        "FILL_FAINT" to c.content.over(HlAlpha.FILL_FAINT, c.background),
        "FILL_FAINT on glass" to c.content.over(HlAlpha.FILL_FAINT, c.glass),
        // A primary control at rest, a progress line's track off the artwork.
        "FILL_STRONG" to c.content.over(HlAlpha.FILL_STRONG, c.background),
        // A button in a side sheet.
        "FILL on glass" to c.content.over(HlAlpha.FILL, c.glass),
        // The current option of a track: its quiet pill in the track's well, or on the track's plate over an image.
        "FILL_STRONG on FILL_FAINT" to c.content.over(HlAlpha.FILL_STRONG, c.content.over(HlAlpha.FILL_FAINT, c.background)),
        "FILL_STRONG on VEIL on a white image" to c.content.over(HlAlpha.FILL_STRONG, c.background.over(HlAlpha.VEIL, Color.White)),
        "focus" to c.focus,
        // The one light ground: a code to scan.
        "content" to c.content,
        // Whatever sits on artwork, on its plate over the worst image: a white one.
        "VEIL on a white image" to c.background.over(HlAlpha.VEIL, Color.White),
        // A text column over a backdrop: the left veil at the floor a white image gets.
        "the veil's floor on a white image" to c.background.over(HlScrim.need(1f), Color.White),
        // The main action, filled with the ember light, at each end and its middle.
        "emberFrom" to c.emberFrom, "ember" to c.ember, "emberTo" to c.emberTo,
        // A picture's own dark where it is whole: a veil or a plate at its floor.
        "ambientDeep" to c.ambientDeep,
    )

    /** Every pair the system draws, as role, surface, use. */
    private val permitted = listOf(
        // Primary text, the count of unwatched episodes on its plate, the current option of a track.
        "content" on "background", "content" on "FILL", "content" on "glass",
        "content" on "FILL_FAINT", "content" on "FILL_FAINT on glass",
        "content" on "FILL_STRONG", "content" on "FILL_STRONG on FILL_FAINT",
        // On artwork: a round button's icon, the current option of a track, on their plate.
        "content" on "VEIL on a white image", "content" on "FILL_STRONG on VEIL on a white image",
        // The other options of a track at rest, in its well or on its plate over an image.
        "contentSecondary" on "VEIL on a white image",
        // A title's name, facts, overview and quietest words.
        "content" on "the veil's floor on a white image", "contentSecondary" on "the veil's floor on a white image",
        "contentTertiary" on "the veil's floor on a white image",
        // Descriptions, labels, a selected entry's summary, a button's detail at rest.
        "contentSecondary" on "background", "contentSecondary" on "FILL", "contentSecondary" on "glass",
        "contentSecondary" on "FILL_FAINT", "contentSecondary" on "FILL_FAINT on glass",
        "contentSecondary" on "FILL_STRONG",
        // Hints, placeholders, an entry's summary at rest; never on a strong fill.
        "contentTertiary" on "background", "contentTertiary" on "FILL", "contentTertiary" on "glass",
        "contentTertiary" on "FILL_FAINT",
        // A value the user chose, the check of the current choice, a failed result, the mark at the launch.
        "ember" on "background", "ember" on "glass", "ember" on "FILL",
        "ember" on "FILL_FAINT", "ember" on "FILL_FAINT on glass",
        // What needs attention beside what it concerns, over a backdrop (images that cannot be fetched).
        "ember" on "the veil's floor on a white image",
        // A focused control: its name, and the line under it.
        "onFocus" on "focus", "onFocusSecondary" on "focus",
        // Focus, a white fill or outline, against its surroundings (WCAG 2.4.13 asks 3:1).
        ("focus" on "FILL_FAINT").mark(), ("focus" on "FILL_STRONG on FILL_FAINT").mark(),
        ("focus" on "VEIL on a white image").mark(), ("focus" on "background").mark(),
        // A row read in a sheet: no well at rest, white on the sheet itself.
        ("focus" on "glass").mark(),
        // A code to scan: its black modules on projector white, and that ground against the sheet.
        ("background" on "content").mark(), ("content" on "glass").mark(),
        // Marks on their plate: a lit favourite icon, the chosen check, the new dot, a card's progress.
        ("ember" on "FILL").mark(),
        ("ember" on "VEIL on a white image").mark(),
        // A meter's inks on its track, a gap apart: projector white, secondary, and tertiary (below).
        ("content" on "FILL_FAINT").mark(), ("contentSecondary" on "FILL_FAINT").mark(),
        // A button's detail lit at rest (an active filter), on a primary button as on a secondary one (above).
        "ember" on "FILL_STRONG",
        // A question asked in a sheet: Cancel, and the action asked about, its label ember at rest.
        "content" on "FILL on glass", "ember" on "FILL on glass",
        // A disabled control's icon, tertiary in its well.
        ("contentTertiary" on "FILL_FAINT").mark(),
        ("contentSecondary" on "VEIL on a white image").mark(),
        // The ember light (progress line, lit edge, scan counter): both ends of its gradient.
        ("emberFrom" on "background").mark(), ("emberTo" on "background").mark(),
        ("emberFrom" on "glass").mark(), ("emberTo" on "glass").mark(),
        ("emberFrom" on "FILL_STRONG").mark(), ("emberTo" on "FILL_STRONG").mark(),
        ("emberFrom" on "VEIL on a white image").mark(), ("emberTo" on "VEIL on a white image").mark(),
        // The main action's words and icon on the ember light, at both ends and in its middle.
        "onEmber" on "emberFrom", "onEmber" on "ember", "onEmber" on "emberTo",
        // What stays lit on a focused plate: a chosen check, the current place's dot.
        ("emberOnLight" on "focus").mark(),
        // On a picture's own dark: a title's words, the quietest, and the place in ember.
        "content" on "ambientDeep", "contentSecondary" on "ambientDeep", "contentTertiary" on "ambientDeep",
        "ember" on "ambientDeep", "emberTo" on "ambientDeep",
        // A state's light: pending, and the ring of off, on the room, in a sheet and in a busy button.
        ("contentTertiary" on "background").mark(), ("contentTertiary" on "glass").mark(),
        ("contentTertiary" on "FILL_STRONG").mark(), ("contentTertiary" on "FILL").mark(),
    )

    private val forbidden = listOf(
        "contentTertiary" on "FILL_STRONG",
        // White on ember: the main action's ink is black.
        "content" on "emberFrom", "content" on "ember", "content" on "emberTo",
        // Raspberry on white marks, never words.
        "emberOnLight" on "focus",
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

    private fun Color.over(alpha: Float, background: Color) = copy(alpha = alpha).compositeOver(background)

    private fun format(ratio: Float) = "%.2f:1".format(ratio)
}
