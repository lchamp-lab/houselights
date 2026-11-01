package app.houselights.ui.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.houselights.settings.TextSize
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.TEXT_SCALE_MAX
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.testing.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A saga's polyptych as laid out at text sizes 0.85, 1 and 1.3: it spans the safe area less a focused frame's reach (853
 * on the canvas, 473 down it), its closed panels 96 wide whatever its length, the open one taking the rest down to 428,
 * past which a long saga's films scroll, the saga's spine pinned at the start; its numbers in `display`, which never
 * grows with the text; the first and the last panel's frame inside the safe area when open; the years of two closed
 * panels well apart at every text size, measured in the app's own font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Text measured as drawn, in the app's font.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoletsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private var case by mutableStateOf(Case(films = 3, step = 1f, open = 0))

    /** The width a year is set at, and the numbers' size, as the case composes them, and their style's in sp. */
    private var year = 0.dp
    private var numbers = 0.dp
    private var numbersSp = 0f

    @Test
    fun thePolyptychSpansTheSafeAreaLessTheFramesReach() = sizes { step ->
        listOf(1, 2, 3, 4).forEach { films ->
            show(Case(films, step, open = 0))
            val panels = panels(films)
            val across = panels.last().right - panels.first().left
            assertEquals("$films films at $step: the polyptych", POLYPTYCH.value, across.value, 1f)
            assertEquals("$films films at $step: the open panel", plan(films).open.value, panels.first().width.value, 1f)
            panels.drop(1).forEach { assertEquals("$films films at $step: a closed panel", CLOSED, it.width.value, 1f) }
            panels.forEach { assertEquals("$films films at $step: tall", POLYPTYCH_HEIGHT.value, it.height.value, 1f) }
        }
        listOf(5, 8, BOND).forEach { films ->
            show(Case(films, step, open = 0))
            val panels = panels(films)
            assertEquals("$films films at $step: the open panel at its narrowest", VoletDefaults.open.value, panels[0].width.value, 1f)
            panels.drop(1).forEach { assertEquals("$films films at $step: a closed panel", CLOSED, it.width.value, 1f) }
            assertTrue("$films films at $step: past the edge", panels.last().right > VOLET_SIDE + POLYPTYCH)
        }
    }

    /** A long saga's films scroll after the saga's spine, pinned at the start, the open one two closed panels from it. */
    @Test
    fun theSpineStaysPinnedAsTheFilmsScroll() = sizes { step ->
        show(Case(BOND, step, open = 20))
        val panels = panels(BOND)
        assertEquals("at $step: the spine at the start", VOLET_SIDE.value, panels[0].left.value, 1f)
        assertEquals("at $step: the spine closed", CLOSED, panels[0].width.value, 1f)
        assertEquals("at $step: film 18 past the spine", (panels[0].right + VoletDefaults.gap).value, panels[18].left.value, 1f)
        assertEquals("at $step: film 20 open", VoletDefaults.open.value, panels[20].width.value, 1f)
        assertEquals(
            "at $step: two closed panels between",
            (panels[18].left + (CLOSED.dp + VoletDefaults.gap) * 2).value,
            panels[20].left.value,
            1f,
        )
    }

    /** The numbers keep `display`'s size at every text size from Standard up, whatever the saga's length. */
    @Test
    fun theNumbersNeverGrowWithTheText() = sizes { step ->
        listOf(3, 4, 6, BOND).forEach { films ->
            show(Case(films, step, open = 0))
            // Compacte sets them smaller, as it does every title.
            if (step < 1f) {
                assertTrue("$films films at $step: $numbers", numbers.value <= numbersSp)
            } else {
                assertEquals("$films films at $step", numbersSp, numbers.value, 0.01f)
            }
        }
    }

    @Test
    fun theFirstAndTheLastFrameStayInsideTheSafeArea() = sizes { step ->
        listOf(1, 3, 8).forEach { films ->
            show(Case(films, step, open = 0))
            val first = panels(films).first()
            assertTrue("$films films at $step: $first", first.left - HlFocus.reach >= HlSpace.safeH - HALF_PX)
            assertTrue("$films films at $step: $first", first.top - HlFocus.reach >= HlSpace.safeV - HALF_PX)
            assertTrue("$films films at $step: $first", first.bottom + HlFocus.reach <= CANVAS_HEIGHT - HlSpace.safeV + HALF_PX)
            show(Case(films, step, open = films))
            val last = panels(films).last()
            assertEquals("$films films at $step: the last panel open", plan(films).open.value, last.width.value, 1f)
            assertTrue("$films films at $step: $last", last.right + HlFocus.reach <= HlLayout.CANVAS_WIDTH.dp - HlSpace.safeH + HALF_PX)
        }
    }

    /** Every closed panel keeps its year, two of them far apart at every text size: no year ever goes for want of room. */
    @Test
    fun theYearsOfClosedPanelsStandFarApart() = sizes { step ->
        show(Case(EIGHT_FILMS, step, open = 0))
        val panels = panels(EIGHT_FILMS)
        // Each year is centred on its panel: between two, their axes' distance less a year's width.
        panels.drop(1).zipWithNext().forEach { (one, next) ->
            val apart = (next.left + next.right) / 2 - (one.left + one.right) / 2 - year
            assertTrue("at $step two years ($year wide) stand $apart apart", apart >= HlSpace.xl)
        }
    }

    /** Each check at the text sizes the layouts are held to. */
    private fun sizes(check: (step: Float) -> Unit) {
        compose.setContent {
            val now = case
            HouselightsTheme(now.step) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val meta = Hl.type.meta
                val style = Hl.type.display
                with(density) {
                    year = YEARS.maxOf { measurer.measure(it, meta, maxLines = 1, softWrap = false, density = density).size.width }.toDp()
                    numbers = style.fontSize.toDp()
                    numbersSp = style.fontSize.value
                }
                // A saga a page: laid out open on its panel, as the page comes.
                key(now) { VoletsOnTheCanvas(parts(now.films), now.open) }
            }
        }
        STEPS.forEach(check)
    }

    private fun show(shown: Case) {
        case = shown
        compose.waitForIdle()
    }

    /** The saga's panel, then its films', as laid out. */
    private fun panels(films: Int): List<DpRect> =
        (0..films).map { compose.onNodeWithContentDescription(panelName(it)).getUnclippedBoundsInRoot() }

    private fun plan(films: Int) = voletPlan(films)

    private fun parts(films: Int) = voletParts(films, spine = "Mission : Impossible") { i ->
        VoletFilm(year = YEARS[(i - 1) % YEARS.size], mark = VoletMark.Watched)
    }

    /** A saga of [films], at text size [step], panel [open] open. */
    private data class Case(val films: Int, val step: Float, val open: Int)

    private companion object {
        const val EIGHT_FILMS = 8

        /** A closed panel's width on the canvas: a portrait's. */
        const val CLOSED = 96f

        /** The canvas's height. */
        val CANVAS_HEIGHT = 540.dp
        const val BOND = 25

        /** Half a test pixel, where a pixel rounds a frame's reach. */
        val HALF_PX = 0.5.dp

        /** The text sizes the layouts are held to: Compacte, Standard and Android's largest. */
        val STEPS = listOf(TextSize.COMPACT.step, TextSize.STANDARD.step, TEXT_SCALE_MAX)

        /** Mission : Impossible's years. */
        val YEARS = listOf("1996", "2000", "2006", "2011", "2015", "2018", "2023", "2025")
    }
}
