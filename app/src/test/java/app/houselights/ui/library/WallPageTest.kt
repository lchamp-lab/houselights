package app.houselights.ui.library

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.SortOption
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.requestedPoster
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
        val vm = LibraryViewModel({ provider }, films)
        compose.setTvContent { Wall(vm, films, "Films", onOpenItem = {}) }
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
        val second = poster(HlLayout.WALL_COLUMNS + 1)
        assertTrue("a second line under the first", second.top > first.bottom)
        val last = poster(2 * HlLayout.WALL_COLUMNS)
        assertTrue("the second line whole on the screen: ${last.bottom}", last.bottom <= 540.dp)
    }

    /** Sorted anew from its menus, the wall opens at its top, on its new first poster, not where its first poster went. */
    @Test
    fun sortedAnewItOpensAtItsTop() {
        val vm = LibraryViewModel({ provider }, films)
        compose.setTvContent { Wall(vm, films, "Films", onOpenItem = {}) }
        compose.waitForIdle()
        compose.press(Key.DirectionUp)
        focusIsOn("Filter")

        // By Ajout, thirty films added since come before the first one by title.
        provider.wall = (41..70).map { film("m$it", "Film $it") } + provider.wall
        compose.runOnIdle { vm.setSort(SortOption.ADDED) }
        compose.waitForIdle()
        compose.onNode(hasText("Film 41") and hasClickAction()).assertIsDisplayed()
    }

    /** A poster fills its cell at the width its picture is decoded and loaded ahead at, a pixel's rounding aside. */
    @Test
    fun aPosterIsAsWideAsItsPictureIsRequested() {
        open()
        (1..HlLayout.WALL_COLUMNS).map(::poster).forEach { cell ->
            assertEquals(requestedPoster(null).value, (cell.right - cell.left).value, 1f)
        }
    }
}
