package app.houselights.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.houselights.Route
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.openPlace
import app.houselights.ui.design.HlNavBarDefaults
import app.houselights.ui.design.HlSpace
import app.houselights.ui.home.Places
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

/**
 * DESIGN.md, Layout and Focus: on a wall, the bar shows while focus is in it, the wall under it, and folds away once
 * focus enters the posters; Down and Up join it to the header; Back goes from the wall to the bar, then home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class BarFoldTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val films = Library("films", LibraryKind.MOVIES, "Films")
    private val shows = Library("shows", LibraryKind.SHOWS, "Séries")
    private val provider = FakeProvider().apply { wall = (1..40).map { film("m$it", "Film $it") } }
    private val places = Places(listOf(films, shows), null, { _, _ -> }, onSettings = {})

    @Composable
    private fun FilmsWall(heading: WallHeading = WallHeading("Films")) {
        val vm = remember { LibraryViewModel({ provider }, films) }
        Wall(vm, films, heading, onOpenItem = {}, places = places)
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text, substring = true))

    /** The bar's home, a word only the bar says. */
    private fun home() = compose.onNodeWithText("Home").getUnclippedBoundsInRoot()

    private fun posterTop(): Dp = compose.onNode(hasText("Film 1") and hasClickAction()).getUnclippedBoundsInRoot().top

    private fun assertOpen() = assertEquals("the bar in its band", HlSpace.safeV, home().top)

    /** Folded, the bar is gone: neither the remote nor a screen reader finds it off the screen. */
    private fun assertFolded() = compose.onNodeWithText("Home").assertDoesNotExist()

    /** The wall's name in its header, not the bar's place of the same name. */
    private fun titled() = compose.onAllNodes(hasText("Films") and !isSelectable()).fetchSemanticsNodes().size == 1

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun theBarShowsWhileFocusIsInItAndFoldsOnceFocusIsInThePosters() {
        compose.setTvContent { FilmsWall() }
        compose.waitForIdle()
        focusIsOn("Film 1")
        assertFolded()
        assertTrue("the wall named as today", titled())
        val atRest = posterTop()

        // Up to the menus: the bar stays where it is.
        compose.press(Key.DirectionUp)
        focusIsOn("Filter")
        assertFolded()
        // Up again: the bar comes down, on the wall's place, the wall under it without its name.
        compose.press(Key.DirectionUp)
        focusIsOn("Films")
        assertOpen()
        assertTrue("the bar names the place", !titled())
        assertEquals(atRest + HlNavBarDefaults.fold, posterTop())
        // The count comes first in the header, where the name was.
        assertEquals(HlSpace.safeH, compose.onNodeWithText("40\u00A0titles").getUnclippedBoundsInRoot().left)

        // Down: the header first, the bar still there; then the posters, and it folds away.
        compose.press(Key.DirectionDown)
        focusIsOn("Filter")
        assertOpen()
        compose.press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasClickAction() and hasText("Film", substring = true))
        assertFolded()
        assertTrue(titled())
        assertEquals(atRest, posterTop())
    }

    @Test
    fun withSagasDownFromTheBarLandsOnTheirSwitchAndUpComesBack() {
        compose.setTvContent { FilmsWall(WallHeading("Films", switchTo = "Sagas")) }
        compose.waitForIdle()
        compose.press(Key.DirectionUp, times = 2)
        focusIsOn("Films")
        compose.press(Key.DirectionRight)
        focusIsOn("Séries")
        compose.press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasContentDescription("Sagas"))
        assertOpen()
        // Up from the header: the bar on the wall's place, whichever place focus left it on.
        compose.press(Key.DirectionUp)
        focusIsOn("Films")
    }

    @Test
    fun backFromTheWallGoesToTheBarThenHome() {
        lateinit var nav: NavHostController
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Home) {
                composable<Route.Home> {}
                composable<Route.Library> { FilmsWall() }
            }
        }
        compose.runOnIdle { nav.openPlace(Route.Library(films.id, films.kind, films.title, place = true)) }
        compose.waitForIdle()
        focusIsOn("Film 1")
        val atRest = posterTop()
        // Deep in the wall: Back goes to the bar, on the wall's place, the wall back at its top.
        compose.press(Key.DirectionDown, times = 2)
        back()
        focusIsOn("Films")
        assertOpen()
        assertEquals(atRest + HlNavBarDefaults.fold, posterTop())
        assertTrue("still on the wall", nav.currentBackStackEntry?.destination?.hasRoute<Route.Library>() == true)
        // From the bar: home.
        back()
        assertTrue(nav.currentBackStackEntry?.destination?.hasRoute<Route.Home>() == true)
    }
}
