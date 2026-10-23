package app.houselights

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** DESIGN.md, Focus: changing place replaces the wall shown over home, so Back from any wall is home. */
@RunWith(RobolectricTestRunner::class)
class PlacesNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val films = Route.Library("films", LibraryKind.MOVIES, place = true)
    private val shows = Route.Library("shows", LibraryKind.SHOWS, place = true)

    /** The app's screens as navigation holds them, home first. */
    private fun screens(): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Home) {
                composable<Route.Home> {}
                composable<Route.Library> {}
                composable<Route.Settings> {}
                composable<Route.Search> {}
            }
        }
        compose.waitForIdle()
        return nav
    }

    private fun NavHostController.wall(): String? = currentBackStackEntry?.takeIf { it.destination.hasRoute<Route.Library>() }
        ?.toRoute<Route.Library>()?.id

    private fun NavHostController.atHome() = currentBackStackEntry?.destination?.hasRoute<Route.Home>() == true

    private fun NavHostController.underIsHome() = previousBackStackEntry?.destination?.hasRoute<Route.Home>() == true

    @Test
    fun changingPlacePilesUpNoHistory() {
        val nav = screens()
        compose.runOnIdle { nav.openPlace(films) }
        compose.runOnIdle { nav.openPlace(shows) }
        compose.runOnIdle {
            assertEquals("shows", nav.wall())
            assertTrue("the films' wall gave its place", nav.underIsHome())
        }
        // Back from the shows is home, never the films.
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle {
            assertTrue(nav.atHome())
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test
    fun homeAsAPlaceIsTheHomeBeneath() {
        val nav = screens()
        val home = nav.currentBackStackEntry?.id
        compose.runOnIdle { nav.openPlace(films) }
        compose.runOnIdle { nav.openPlace(Route.Home) }
        compose.runOnIdle {
            assertTrue(nav.atHome())
            assertEquals("the same home, with its focus and rows", home, nav.currentBackStackEntry?.id)
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test
    fun aPlaceChosenOverPagesOpenedOnTopLeavesThemBehind() {
        val nav = screens()
        compose.runOnIdle { nav.navigate(Route.Settings) }
        // A wall opened filtered from the library in numbers is a page, not a place.
        val ultraHd = Route.Library.of(Library("films", LibraryKind.MOVIES, null), LibraryFilter(picture = "Ultra HD"))
        compose.runOnIdle { nav.navigate(ultraHd) }
        compose.runOnIdle { nav.openPlace(shows) }
        compose.runOnIdle {
            assertEquals("shows", nav.wall())
            assertTrue(nav.underIsHome())
        }
    }
}
