package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Saga page: a film's page keeps its saga's row, now the whole saga in release order, a film held as its
 * poster and the one the library lacks in grey; the row's heading never changes, and OK on a grey poster opens its
 * synopsis.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailSagaRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stop() = work.cancel()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    /** A film held's poster in the row, named by its picture, else by its title set in its place. */
    private fun poster(title: String) = compose.onNode((hasContentDescription(title) or hasText(title)) and hasClickAction())

    private val absent get() = compose.onNodeWithContentDescription("${duneThree.title}, ${text(R.string.saga_release_on, out())}")

    /** The row's heading: the saga's name, then its films held of all. */
    private val count get() = compose.onNodeWithText(context.resources.getQuantityString(R.plurals.film_count_of, 2, 2, 3))

    @Test
    fun aFilmsPageHoldsItsSagaWholeTheHeadingUnchanged() {
        val page = SagaPage(compose, duneSource(), work)
        page.open(duneTwo.id)
        val order = listOf(poster(dune.title), poster(duneTwo.title), absent).map { it.getUnclippedBoundsInRoot().left }
        assertEquals("in release order", order.sorted(), order)
        assertTrue("three apart", order.zipWithNext().all { (a, b) -> b > a })
        count.assertExists()
        // Down from Lire to the row, on its first film, then the one the library lacks.
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionRight, times = 2)
        absent.assertIsFocused()
        count.assertExists()
        compose.onAllNodesWithText(duneThree.title, substring = true).assertCountEquals(0)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(duneThree.overview.orEmpty()).assertExists()
        assertEquals(emptyList<String>(), page.opened)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        absent.assertIsFocused()
    }

    /** The release date in full, as the page writes it in the viewer's language. */
    private fun out() = longDate(duneThreeOut, context.resources.configuration.locales[0])
}
