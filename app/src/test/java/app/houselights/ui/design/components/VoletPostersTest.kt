package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pictureServer
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * A long saga's polyptych asks for the posters of the panels in its view and of a few past it alone (#55), a poster
 * asked for before its panel comes into view as focus goes along, and one asked for staying asked while its panel is
 * near; eight films or fewer, all of them, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class VoletPostersTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** The films whose posters were asked for, by their number. */
    private val asked: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    private var open by mutableIntStateOf(0)

    @After
    fun forgetTheLoader() = Coil.reset()

    /** Eight films, all in view: every poster is asked for at once, as before. */
    @Test
    fun eightFilmsAskForEveryPoster() {
        show(EIGHT, first = 0)
        assertEquals((1..EIGHT).toSet(), asked.toSet())
        assertEquals(0..EIGHT, PosterWindow(plan(EIGHT), POLYPTYCH.value).moveTo(3))
    }

    /**
     * Bond's twenty-five films from the saga's panel to the last and back: every poster in view and two panels past it
     * has been asked for at each step, never the last film's on arrival.
     */
    @Test
    fun aLongSagaAsksForThePostersNearItsView() {
        show(BOND, first = 0)
        assertFalse("the last film, far from the view, on arrival: $asked", BOND in asked)
        assertTrue("fewer than all on arrival: $asked", asked.size < BOND)
        checkNearViewAsked(at = 0)
        ((1..BOND) + (BOND - 1 downTo 0)).forEach { to ->
            open = to
            compose.waitForIdle()
            checkNearViewAsked(at = to)
        }
    }

    /** Going back a step, the posters asked for stay asked; far behind, they go. */
    @Test
    fun aPosterAskedForStaysWhileItsPanelIsNear() {
        val window = PosterWindow(plan(BOND), POLYPTYCH.value)
        val there = window.moveTo(12)
        val on = window.moveTo(13)
        assertTrue("on a step: $there in $on", there.first >= on.first && there.last <= on.last)
        val back = window.moveTo(12)
        assertTrue("back a step: $on in $back", on.first >= back.first && on.last <= back.last)
        (13..BOND).forEach { window.moveTo(it) }
        assertFalse("the saga's panel, far behind", 0 in window.moveTo(BOND))
    }

    /**
     * A jump across the saga, as Up enters on the film Lire plays from the saga's panel, keeps the posters of the view
     * it leaves and asks for those of every panel it slides by, as well as the view it reaches.
     */
    @Test
    fun aJumpAsksForAllItPassesBy() {
        val window = PosterWindow(plan(BOND), POLYPTYCH.value)
        val left = window.moveTo(0)
        assertFalse("on arrival, film 20 is far: $left", 20 in left)
        val jumped = window.moveTo(20)
        assertTrue("from the saga's panel to film 20, then two past it: $jumped", 0 in jumped && 22 in jumped)
    }

    /** Every panel in view, panel [at] open, and two past it on either side, has had its poster asked for. */
    private fun checkNearViewAsked(at: Int) {
        val inView = (1..BOND).filter { film ->
            val bounds = compose.onNodeWithContentDescription(nameOf(film)).getUnclippedBoundsInRoot()
            bounds.right > SIDE && bounds.left < SIDE + POLYPTYCH
        }
        val near = (inView.first() - 2).coerceAtLeast(1)..(inView.last() + 2).coerceAtMost(BOND)
        val missing = near.filterNot { it in asked }
        assertTrue("panel $at open, in view $inView: asked for none of $missing", missing.isEmpty())
    }

    /** [films] and the saga's panel, film [first] open, its posters asked for from a server that counts them. */
    private fun show(films: Int, first: Int) {
        val server = pictureServer { data, _ -> FILM.find(data.toString())?.let { asked += it.groupValues[1].toInt() } }
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(server) }.build())
        open = first
        compose.setContent {
            HouselightsTheme {
                Box(Modifier.fillMaxSize()) {
                    Volets(
                        parts(films),
                        open = open,
                        shown = null,
                        onFocus = {},
                        onOpen = {},
                        modifier = Modifier.padding(start = SIDE, end = SIDE, top = HlLayout.voletTop).fillMaxWidth().height(HEIGHT),
                    ) {}
                }
            }
        }
        compose.waitForIdle()
    }

    private fun plan(films: Int) = VoletPlan.of(films + 1, POLYPTYCH, HEIGHT)

    private fun parts(films: Int): List<VoletPart> =
        listOf(VoletPart("saga", null, null, null, nameOf(0), spine = "James Bond")) +
            (1..films).map { i ->
                VoletPart("film-$i", i, ImageRef("film-$i") { w -> "https://img.test/film-$i?w=$w" }, null, nameOf(i), year = "${1961 + i}")
            }

    private fun nameOf(panel: Int) = if (panel == 0) "La saga" else "Film $panel"

    private companion object {
        val SIDE = HlLayout.voletSide
        val POLYPTYCH = HlLayout.CANVAS_WIDTH.dp - SIDE * 2
        val HEIGHT = 370.5.dp

        const val EIGHT = 8
        const val BOND = 25

        /** A film's number in its poster's address. */
        val FILM = Regex("""film-(\d+)""")
    }
}
