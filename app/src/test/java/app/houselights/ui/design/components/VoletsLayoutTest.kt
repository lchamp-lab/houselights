package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsTheme
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
 * on the canvas), its closed panels as wide as its length makes them, its numbers in one size by that width that never
 * grows with the text, the first and the last panel's frame inside the safe area when open, and the years of two closed
 * panels at least [VoletDefaults.yearsApart] apart under Mission : Impossible's eight films (« greffe 2 »), measured in
 * the app's own font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Text measured as drawn, in the app's font.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoletsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private var case by mutableStateOf(Case(films = 3, step = 1f, open = 0))

    /** The width a year is set at, and the numbers' size, as the case composes them. */
    private var year = 0.dp
    private var numbers = 0.dp

    @Test
    fun thePolyptychSpansTheSafeAreaLessTheFramesReach() = sizes { step ->
        listOf(1, 2, 3, 4, 5, 8).forEach { films ->
            show(Case(films, step, open = 0))
            val panels = panels(films)
            val across = panels.last().right - panels.first().left
            assertEquals("$films films at $step: the polyptych", POLYPTYCH.value, across.value, 1f)
            assertEquals("$films films at $step: the open panel", plan(films).open.value, panels.first().width.value, 1f)
            panels.drop(1).forEach { assertEquals("$films films at $step: a closed panel", plan(films).closed.value, it.width.value, 1f) }
        }
        show(Case(BOND, step, open = 0))
        assertEquals("Bond at $step: the narrowest", VoletDefaults.narrowest.value, panels(BOND)[1].width.value, 1f)
    }

    @Test
    fun theNumbersTakeOneSizeByTheSagasLength() = sizes { step ->
        listOf(3 to 72f, 4 to 48f, 5 to 48f, 6 to 36f, 8 to 36f, BOND to 36f).forEach { (films, size) ->
            show(Case(films, step, open = 0))
            // Compacte sets them smaller, as it does every title; from Standard up they keep their size.
            if (step < 1f) {
                assertTrue("$films films at $step: $numbers", numbers.value <= size)
            } else {
                assertEquals("$films films at $step", size, numbers.value, 0.01f)
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
            show(Case(films, step, open = films))
            val last = panels(films).last()
            assertEquals("$films films at $step: the last panel open", plan(films).open.value, last.width.value, 1f)
            assertTrue("$films films at $step: $last", last.right + HlFocus.reach <= HlLayout.CANVAS_WIDTH.dp - HlSpace.safeH + HALF_PX)
        }
    }

    @Test
    fun theYearsOfEightFilmsStandApart() = sizes { step ->
        show(Case(EIGHT_FILMS, step, open = 0))
        val panels = panels(EIGHT_FILMS)
        assertTrue("at $step the plan keeps the years ($year)", VoletPlan.of(EIGHT_FILMS + 1, POLYPTYCH, HEIGHT, year).years)
        // Each year is centred on its panel: between two, their axes' distance less a year's width.
        panels.drop(1).zipWithNext().forEach { (one, next) ->
            val apart = (next.left + next.right) / 2 - (one.left + one.right) / 2 - year
            assertTrue("at $step two years ($year wide) stand $apart apart", apart >= VoletDefaults.yearsApart)
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
                val style = plan(now.films).numbers.style()
                with(density) {
                    year = YEARS.maxOf { measurer.measure(it, meta, maxLines = 1, softWrap = false, density = density).size.width }.toDp()
                    numbers = style.fontSize.toDp()
                }
                Box(Modifier.fillMaxSize()) {
                    // A saga a page: laid out open on its panel, as the page comes.
                    key(now) {
                        Volets(
                            parts(now.films),
                            open = now.open,
                            shown = null,
                            onFocus = {},
                            onOpen = {},
                            modifier = Modifier.padding(start = SIDE, end = SIDE, top = TOP).fillMaxWidth().height(HEIGHT),
                        ) {}
                    }
                }
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
        (0..films).map { compose.onNodeWithContentDescription(nameOf(it)).getUnclippedBoundsInRoot() }

    private fun plan(films: Int) = VoletPlan.of(films + 1, POLYPTYCH, HEIGHT)

    private fun parts(films: Int): List<VoletPart> =
        listOf(VoletPart("saga", null, null, null, nameOf(0), spine = "Mission : Impossible")) +
            (1..films).map { i ->
                VoletPart("film-$i", i, null, null, nameOf(i), mark = VoletMark.Watched, year = YEARS[(i - 1) % YEARS.size])
            }

    private fun nameOf(panel: Int) = if (panel == 0) "La saga" else "Film $panel"

    /** A saga of [films], at text size [step], panel [open] open. */
    private data class Case(val films: Int, val step: Float, val open: Int)

    private companion object {
        /** The polyptych inside the safe area by a focused frame's reach: its margins, its width and height on the canvas. */
        val SIDE = HlSpace.safeH + HlFocus.reach
        val TOP = HlSpace.safeV + HlFocus.reach
        val POLYPTYCH = HlLayout.CANVAS_WIDTH.dp - SIDE * 2
        val HEIGHT = 370.5.dp

        const val EIGHT_FILMS = 8
        const val BOND = 25

        /** Half a test pixel, where a pixel rounds a frame's reach. */
        val HALF_PX = 0.5.dp

        /** The text sizes the layouts are held to: Compacte, Standard and Android's largest. */
        val STEPS = listOf(0.85f, 1f, 1.3f)

        /** Mission : Impossible's years. */
        val YEARS = listOf("1996", "2000", "2006", "2011", "2015", "2018", "2023", "2025")
    }
}
