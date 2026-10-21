package app.houselights.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import app.houselights.ui.design.components.ImageLight
import app.houselights.ui.testing.contrast
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/** DESIGN.md's 4.5:1 for text and 3:1 for marks, for each pair the system draws over what it sits on; forbidden pairs stay below. */
class ContrastTest {
    private enum class Use(val minimum: Float) { Text(4.5f), Mark(3f) }

    private val c = HouselightsColors

    /** The left veil in the neutral dark at the floor it takes over white. */
    private val veilFloorOnWhite = c.ambientDeep.over(HlScrim.need(1f, tint = greyOf(c.ambientDeep)), Color.White)

    /** The welcome wall under its veil where it is lightest: a white poster at `WALL`, the veil at its least across. */
    private val welcomeWall = c.background.over(HlScrim.WALL_ACROSS.minOf { it.alpha }, Color.White.over(HlScrim.WALL, c.background))

    /** The lightest glow a film lends a sheet: a grey picture's. */
    private val lightestGlow = object : AmbientLight {
        override fun deep() = c.ambientDeep
        override fun plateAlpha() = HlAlpha.PLATE
        override fun glow() = ImageLight.Grey.glow
    }

    /** A wall's diffuse field at its brightest, one level over its cap as dithering may draw it. */
    private val fieldBrightest = grey((floor(srgb(HlScrim.FIELD_LUMINANCE_MAX) * 255f) + 1f) / 255f)

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
        // A progress line's track beside text, and the one it would take on focus's white plate.
        "FILL_STRONG" to c.content.over(HlAlpha.FILL_STRONG, c.background),
        "FILL_STRONG on focus" to c.onFocus.over(HlAlpha.FILL_STRONG, c.focus),
        // A button in a side sheet.
        "FILL on glass" to c.content.over(HlAlpha.FILL, c.glass),
        "focus" to c.focus,
        // The one light ground: a code to scan.
        "content" to c.content,
        // Whatever sits on artwork, on its plate over the worst image: a white one.
        "VEIL on a white image" to c.background.over(HlAlpha.VEIL, Color.White),
        // A text column over a backdrop: the left veil, in the picture's dark, at the floor a white image gets.
        "the veil's floor on a white image" to veilFloorOnWhite,
        // The main action, filled with the ember light, at each end and its middle.
        "emberFrom" to c.emberFrom, "ember" to c.ember, "emberTo" to c.emberTo,
        // A picture's own dark where it is whole: a veil or a plate at its floor.
        "ambientDeep" to c.ambientDeep,
        // A control's plate over a picture, where controls sit: on the veil at its floor over a white image.
        "plate on the veil's floor on a white image" to c.plate().compositeOver(veilFloorOnWhite),
        // A plate on the bare picture: at its own opacity over white, and at the most its floor raises it to.
        "plate on a white image" to c.plate().compositeOver(Color.White),
        "plate at its ceiling on a white image" to c.plate(alpha = HlAlpha.PLATE_MAX).compositeOver(Color.White),
        "the diffuse field's brightest" to fieldBrightest,
        // A wall's title at the foot of its band (the safe area and a headline, 70 dp down), under the field's top veil.
        "the title's foot on the diffuse field" to c.background.over(HlScrim.DIFFUSE_TOP.alphaAt(TITLE_FOOT), fieldBrightest),
        "the welcome wall" to welcomeWall,
        // A sheet's plate, a step up from its glass in the film's glow, and a showcase's or a sheet's glass in its light.
        "glassPlate in the lightest glow" to c.glassPlate(lightestGlow),
        "glass tinted by the lightest glow" to c.tintedGlass(ImageLight.Grey.glow),
    )

    /** Every pair the system draws, as role, surface, use. */
    private val permitted = listOf(
        // Primary text, the count of unwatched episodes on its plate, the current option of a track.
        "content" on "background", "content" on "FILL", "content" on "glass",
        "content" on "FILL_FAINT", "content" on "FILL_FAINT on glass",
        // On artwork, on a card's plate: the count of unwatched episodes.
        "content" on "VEIL on a white image", "contentSecondary" on "VEIL on a white image",
        // A title's name, facts, overview and quietest words.
        "content" on "the veil's floor on a white image", "contentSecondary" on "the veil's floor on a white image",
        "contentTertiary" on "the veil's floor on a white image",
        // Descriptions, labels, a selected entry's summary, a button's detail at rest.
        "contentSecondary" on "background", "contentSecondary" on "FILL", "contentSecondary" on "glass",
        "contentSecondary" on "FILL_FAINT", "contentSecondary" on "FILL_FAINT on glass",
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
        ("focus" on "FILL_FAINT").mark(),
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
        // What stays lit on a focused plate: a chosen check, the current place's dot, home's Gradation, a scan's line
        // (on the plate itself, with no track).
        ("emberOnLight" on "focus").mark(),
        // On a picture's own dark: a title's words, the quietest, and the place in ember.
        "content" on "ambientDeep", "contentSecondary" on "ambientDeep", "contentTertiary" on "ambientDeep",
        "ember" on "ambientDeep", "emberTo" on "ambientDeep",
        // A state's light: pending, and the ring of off, on the room, in a sheet and in a busy button.
        ("contentTertiary" on "background").mark(), ("contentTertiary" on "glass").mark(),
        ("contentTertiary" on "FILL").mark(),
        // A control's plate over a picture: its words, its detail, its words disabled or a tile's hint, a chosen glyph, a lit value.
        "content" on "plate on the veil's floor on a white image", "contentSecondary" on "plate on the veil's floor on a white image",
        "contentTertiary" on "plate on the veil's floor on a white image",
        ("ember" on "plate on the veil's floor on a white image").mark(),
        "emberFrom" on "plate on the veil's floor on a white image", "emberTo" on "plate on the veil's floor on a white image",
        // Words in the ember light: a screen's title, a value chosen on a glass plate, and the place in home's bar,
        // where the top and left veils meet at the picture's own dark.
        "emberFrom" on "background", "emberTo" on "background", "emberFrom" on "glass", "emberTo" on "glass",
        "emberFrom" on "ambientDeep",
        // On a plate raised by its floor over the lightest picture: words, and ember as marks only.
        "content" on "plate at its ceiling on a white image", "contentSecondary" on "plate at its ceiling on a white image",
        "contentTertiary" on "plate at its ceiling on a white image",
        ("ember" on "plate at its ceiling on a white image").mark(), ("emberTo" on "plate at its ceiling on a white image").mark(),
        // On a wall's field, without a veil: names and facts; the quietest text only on a plate or in the title's band.
        "content" on "the diffuse field's brightest", "contentSecondary" on "the diffuse field's brightest",
        // A wall's title, in the ember light, and the quietest text in its band.
        "emberFrom" on "the title's foot on the diffuse field", "emberTo" on "the title's foot on the diffuse field",
        "contentTertiary" on "the title's foot on the diffuse field",
        // Over the welcome wall: the task's title, the tagline, an attention note; the lockup's light and focus's frame.
        "content" on "the welcome wall", "contentSecondary" on "the welcome wall", "ember" on "the welcome wall",
        ("emberFrom" on "the welcome wall").mark(), ("emberTo" on "the welcome wall").mark(), ("focus" on "the welcome wall").mark(),
        // A disabled row in its well on a pane's plate.
        "contentTertiary" on "FILL_FAINT on glass",
        // On the settings' field: a place's icon, and the open one's lit.
        ("contentTertiary" on "the diffuse field's brightest").mark(), ("ember" on "the diffuse field's brightest").mark(),
        // On a sheet's plate: its words, a value of your choice, a check or a line, and focus's plate.
        "content" on "glassPlate in the lightest glow", "contentSecondary" on "glassPlate in the lightest glow",
        "contentTertiary" on "glassPlate in the lightest glow", "ember" on "glassPlate in the lightest glow",
        "emberFrom" on "glassPlate in the lightest glow", ("emberTo" on "glassPlate in the lightest glow").mark(),
        ("focus" on "glassPlate in the lightest glow").mark(),
        // On a showcase's glass or a sheet's: its name and facts, its quietest words, and focus's frame; a sheet's
        // place lit (an NFO's view) and an attention mark.
        "content" on "glass tinted by the lightest glow", "contentSecondary" on "glass tinted by the lightest glow",
        "contentTertiary" on "glass tinted by the lightest glow", ("focus" on "glass tinted by the lightest glow").mark(),
        "emberFrom" on "glass tinted by the lightest glow", "emberTo" on "glass tinted by the lightest glow",
        ("ember" on "glass tinted by the lightest glow").mark(),
    )

    private val forbidden = listOf(
        "contentTertiary" on "FILL_STRONG",
        // White on ember: the main action's ink is black.
        "content" on "emberFrom", "content" on "ember", "content" on "emberTo",
        // Raspberry on white marks, never words; a line on a track there, 2.05:1, so a focused line has none.
        "emberOnLight" on "focus",
        ("emberOnLight" on "FILL_STRONG on focus").mark(),
        // A plate on the bare picture without its floor; ember words on a plate there even with it.
        "contentSecondary" on "plate on a white image", "contentTertiary" on "plate on a white image",
        "emberTo" on "plate at its ceiling on a white image",
        // The quietest text on a diffuse field, out of the title's band.
        "contentTertiary" on "the diffuse field's brightest",
        // Raspberry words on a sheet's plate: a mark only there.
        "emberTo" on "glassPlate in the lightest glow",
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

    private fun grey(level: Float) = Color(level, level, level)

    private fun srgb(luminance: Float) = ColorSpaces.Srgb.oetf(luminance.toDouble()).toFloat()

    private fun format(ratio: Float) = "%.2f:1".format(ratio)

    private companion object {
        /** 70 dp of the TV's 540 dp high screen. */
        const val TITLE_FOOT = 70f / 540f
    }
}
