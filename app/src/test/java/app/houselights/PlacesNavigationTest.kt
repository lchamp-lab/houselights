package app.houselights

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.ui.library.LibraryViewModel
import app.houselights.ui.library.WallView
import app.houselights.ui.library.Wall
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: changing place replaces the wall shown over home, so Back from any wall is home; a wall opened
 * as a place lies under the bar, one opened filtered from elsewhere has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class PlacesNavigationTest {
    @get:Rule
    val settle = SettleMainLooper()

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

    private val libraries = listOf(
        Library("films", LibraryKind.MOVIES, "Films"), Library("shows", LibraryKind.SHOWS, "Séries"),
        Library("sagas", LibraryKind.COLLECTIONS, "Sagas"),
    )
    private val source = FakeProvider().apply { wall = (1..20).map { film("m$it", "Film $it") } }

    /**
     * The app's screens, each wall under the bar [homePlaces] gives it, of the libraries home read: the app's own
     * wiring but for the wall's source, a fake one.
     */
    private fun wiredScreens(): NavHostController {
        lateinit var nav: NavHostController
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Home) {
                composable<Route.Home> {}
                composable<Route.Settings> {}
                composable<Route.Library> { entry ->
                    val route = entry.toRoute<Route.Library>()
                    val library = Library(route.id, route.kind, route.title)
                    val sagas = route.sagas?.let { Library(it, LibraryKind.COLLECTIONS, null) }
                    val vm = remember { LibraryViewModel({ source }, library, opensOn = route.filter, sagas = sagas) }
                    val places = homePlaces(nav, entry, shown = route.place) { libraries }
                    Wall(vm, library, route.title.orEmpty(), onOpenItem = {}, places = places)
                }
            }
        }
        compose.waitForIdle()
        return nav
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text))

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

    /** OK on a place of a wall's bar opens it through navigation, in place of the wall. */
    @Test
    fun aWallsBarOpensItsPlaces() {
        val nav = wiredScreens()
        compose.runOnIdle { nav.openPlace(libraries[0], sagas = null) }
        compose.waitForIdle()
        // Up from the posters to the menus, then to the bar, on the wall's place.
        compose.press(Key.DirectionUp, times = 2)
        focusIsOn("Films")
        compose.press(Key.DirectionRight)
        focusIsOn("Séries")
        compose.press(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals("shows", nav.wall())
            assertTrue(nav.currentBackStackEntry?.toRoute<Route.Library>()?.place == true)
            assertTrue("the films' wall gave its place", nav.underIsHome())
        }
    }

    /** A wall opened filtered from Settings > the library in numbers is a page: no bar, whatever focus does. */
    @Test
    fun aWallOpenedFromTheNumbersHasNoBar() {
        val nav = wiredScreens()
        compose.runOnIdle { nav.navigate(Route.Settings) }
        compose.runOnIdle { nav.openWall(libraries[0], LibraryFilter(picture = "Ultra HD")) }
        compose.waitForIdle()
        compose.press(Key.DirectionUp, times = 3)
        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(false, nav.currentBackStackEntry?.toRoute<Route.Library>()?.place)
            assertTrue("Back returns to the numbers", nav.previousBackStackEntry?.destination?.hasRoute<Route.Settings>() == true)
        }
    }

    /** The films' place shows their sagas among them; the same films opened from the numbers show every film. */
    @Test
    fun theFilmsPlaceGroupsItsSagasAWallOpenedElsewhereDoesNot() {
        val nav = wiredScreens()
        compose.runOnIdle { nav.openPlace(libraries[1], sagas = null) }
        compose.waitForIdle()
        compose.press(Key.DirectionUp, times = 2)
        focusIsOn("Séries")
        compose.press(Key.DirectionLeft)
        focusIsOn("Films")
        compose.press(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals("sagas", nav.currentBackStackEntry?.toRoute<Route.Library>()?.sagas)
            assertEquals(FakeProvider.Asked("films", LibraryFilter(), grouped = true), source.pages.last())
        }
        compose.runOnIdle { nav.openWall(libraries[0], LibraryFilter(picture = "Ultra HD")) }
        compose.runOnIdle { assertFalse(source.pages.last().grouped) }
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
    /** Home's sagas door opens the films' wall over home, on its sagas alone, by its route: the wall's saved state starts with it. */
    @Test
    fun theSagasDoorOpensTheFilmsWallOnItsSagasByItsRoute() {
        var wall: LibraryViewModel? = null
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Home) {
                composable<Route.Home> {}
                composable<Route.Library> {
                    // As the app makes it: its saved state from the route's arguments.
                    wall = viewModel(
                        factory = viewModelFactory {
                            initializer { LibraryViewModel({ source }, libraries[0], createSavedStateHandle(), sagas = libraries[2]) }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.openSagas(libraries[0], libraries[2]) }
        compose.waitForIdle()
        compose.runOnIdle {
            val route = checkNotNull(nav.currentBackStackEntry).toRoute<Route.Library>()
            assertEquals("films" to "sagas", route.id to route.sagas)
            assertTrue("a place of the bar, over home", route.place && nav.underIsHome())
            assertEquals(WallView.SAGAS, checkNotNull(wall).state.value.view)
        }
    }
}
