package app.houselights.ui.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Layout: a wall is a page, no bar above it: its header at the safe area's top, two whole lines of posters. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class WallPageTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createComposeRule()

    private val films = Library("films", LibraryKind.MOVIES, "Films")
    private val provider = FakeProvider().apply { wall = (1..40).map { film("m$it", "Film $it") } }

    private fun open() {
        compose.setTvContent { Wall(LibraryViewModel({ provider }, films), films, "Films", onOpenItem = {}) }
        compose.waitForIdle()
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text, substring = true))

    private fun poster(n: Int) = compose.onNode(hasText("Film $n") and hasClickAction()).getUnclippedBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 0.5f)

    @Test
    fun itsHeaderStandsAtTheSafeAreasTop() {
        open()
        focusIsOn("Film 1")
        val title = compose.onNode(hasText("Films") and !hasClickAction()).getUnclippedBoundsInRoot()
        val filter = compose.onNode(hasText("Filter", substring = true) and hasClickAction()).getUnclippedBoundsInRoot()
        // The header's line starts at the safe area's top: the taller of the name and the menus stands on it.
        assertNear(HlSpace.safeV, minOf(title.top, filter.top))
        assertNear(HlSpace.safeH, title.left)
        // No tile of a bar: neither search nor the settings.
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }

    @Test
    fun nothingLiesAboveItsMenus() {
        open()
        compose.press(Key.DirectionUp)
        focusIsOn("Filter")
        compose.press(Key.DirectionUp)
        focusIsOn("Filter")
    }

    @Test
    fun twoWholeLinesOfPostersShowFromTheStart() {
        open()
        val first = poster(1)
        val second = poster(COLUMNS + 1)
        assertTrue("a second line under the first", second.top > first.bottom)
        assertTrue("the second line whole on the screen: ${poster(2 * COLUMNS).bottom}", poster(2 * COLUMNS).bottom <= 540.dp)
    }
}
