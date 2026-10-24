package app.houselights

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.ui.design.components.forgetCutPosters
import app.houselights.ui.home.HomeGrid
import app.houselights.ui.home.films
import app.houselights.ui.home.fullHome
import app.houselights.ui.home.sagaLibrary
import app.houselights.ui.library.LibraryViewModel
import app.houselights.ui.library.Wall
import app.houselights.ui.library.WallView
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * DESIGN.md, Focus: a wall, search and the settings open as pages over the screen that opens them. Back from one
 * opened from home lands on the door it came from; from one opened elsewhere, on that screen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class PagesNavigationTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = FakeProvider().apply { wall = (1..30).map { film("m$it", "Film $it") } }

    /** A count cut by an earlier test is drawn at once: each test starts as the app does. */
    @Before
    fun forgetTheCuts() = forgetCutPosters()

    /** Home's grid with everything, its doors wired by the app's [homeActions]; each wall a real one, of a fake source. */
    private fun app(): NavHostController {
        lateinit var nav: NavHostController
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Home) {
                composable<Route.Home> { HomeGrid(fullHome(), nav.homeActions()) }
                composable<Route.Library> { WallAt(it) { item -> nav.navigate(Route.Item(item.id)) } }
                composable<Route.Item> {}
                composable<Route.Watch> {}
                composable<Route.Search> {}
                composable<Route.Settings> {}
                composable<Route.Person> {}
            }
        }
        compose.waitForIdle()
        return nav
    }

    /** The wall at [entry], its saved state from the route's arguments as the app's factory makes it. */
    @Composable
    private fun WallAt(entry: NavBackStackEntry, onOpenItem: (MediaItem) -> Unit) {
        val route = entry.toRoute<Route.Library>()
        val library = Library(route.id, route.kind, route.title)
        val sagas = route.sagas?.let { Library(it, LibraryKind.COLLECTIONS, null) }
        val vm: LibraryViewModel = viewModel(
            factory = viewModelFactory {
                initializer { LibraryViewModel({ source }, library, createSavedStateHandle(), opensOn = route.filter, sagas = sagas) }
            },
        )
        Wall(vm, library, route.title ?: "Wall", onOpenItem)
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text, substring = true))

    /** Focus on the poster of "Film [n]", by its whole name: "Film 1" is not "Film 16". */
    private fun focusIsOnFilm(n: Int) = compose.onNode(isFocused()).assert(hasText("Film $n"))

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun NavHostController.on(screen: KClass<*>) = currentBackStackEntry?.destination?.hasRoute(screen) == true

    private fun NavHostController.overHome() = previousBackStackEntry?.destination?.hasRoute<Route.Home>() == true

    private fun NavHostController.wall() = checkNotNull(currentBackStackEntry).toRoute<Route.Library>()

    @Test
    fun backFromEachDoorsPageLandsOnThatDoor() {
        val nav = app()
        focusIsOn("Resume")
        // From the door before, the keys to the door, its name and the page it opens.
        val doors = listOf(
            Triple(listOf(Key.DirectionRight, Key.DirectionUp), "Next up", Route.Watch::class),
            Triple(listOf(Key.DirectionRight), "New", Route.Watch::class),
            Triple(listOf(Key.DirectionDown), "TV shows", Route.Library::class),
            Triple(listOf(Key.DirectionLeft), "Movies", Route.Library::class),
            Triple(listOf(Key.DirectionDown), "Sagas", Route.Library::class),
            Triple(listOf(Key.DirectionRight), "Search", Route.Search::class),
            Triple(listOf(Key.DirectionRight), "Settings", Route.Settings::class),
        )
        doors.forEach { (keys, door, page) ->
            keys.forEach { compose.press(it) }
            focusIsOn(door)
            compose.press(Key.DirectionCenter)
            compose.runOnIdle { assertTrue("$door opens its page over home", nav.on(page) && nav.overHome()) }
            back()
            compose.runOnIdle { assertTrue("one Back from $door's page", nav.on(Route.Home::class)) }
            focusIsOn(door)
        }
    }

    /** OK pressed twice before a door's page shows opens it once: one Back from it is home. */
    @Test
    fun okTwiceOpensADoorsPageOnce() {
        val nav = app()
        // From the door before, the keys to the door, and the page it opens.
        val doors = listOf(
            Triple(listOf(Key.DirectionRight, Key.DirectionUp), "Next up", Route.Watch::class),
            Triple(listOf(Key.DirectionDown), "Movies", Route.Library::class),
            Triple(listOf(Key.DirectionDown), "Sagas", Route.Library::class),
            Triple(listOf(Key.DirectionRight), "Search", Route.Search::class),
            Triple(listOf(Key.DirectionRight), "Settings", Route.Settings::class),
        )
        doors.forEach { (keys, door, page) ->
            keys.forEach { compose.press(it) }
            focusIsOn(door)
            compose.onRoot().performKeyInput {
                pressKey(Key.DirectionCenter)
                pressKey(Key.DirectionCenter)
            }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("$door opens its page", nav.on(page) && nav.overHome()) }
            back()
            compose.runOnIdle { assertTrue("one Back from $door's page", nav.on(Route.Home::class)) }
            focusIsOn(door)
        }
    }

    /** A wall opened from home: deep in it, Back returns to its first poster, then home, on its door. */
    @Test
    fun aWallFromHomeReturnsToItsFirstPosterThenHome() {
        val nav = app()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        focusIsOnFilm(1)
        compose.runOnIdle { assertEquals("movies" to "collections", nav.wall().id to nav.wall().sagas) }
        compose.press(Key.DirectionDown, times = 2)
        back()
        focusIsOnFilm(1)
        back()
        compose.runOnIdle { assertTrue(nav.on(Route.Home::class)) }
        focusIsOn("Movies")
    }

    /** Back from a title's page opened from a wall, focus is on its poster again. */
    @Test
    fun aTitlesPageGoesBackToItsPoster() {
        val nav = app()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        compose.press(Key.DirectionDown, times = 2)
        compose.press(Key.DirectionRight)
        focusIsOnFilm(16)
        compose.press(Key.DirectionCenter)
        compose.runOnIdle { assertTrue(nav.on(Route.Item::class)) }
        back()
        focusIsOnFilm(16)
    }

    /** A wall opened from a person's page or from the library in numbers goes back there, not home. */
    @Test
    fun aWallOpenedElsewhereGoesBackThere() {
        val nav = app()
        compose.runOnIdle { nav.navigate(Route.Person("p7")) }
        compose.runOnIdle { nav.navigate(Route.Library.of(films, LibraryFilter(person = "p7"), personName = "Denis Villeneuve")) }
        focusIsOnFilm(1)
        back()
        compose.runOnIdle { assertTrue("the filmography's person", nav.on(Route.Person::class)) }

        compose.runOnIdle { nav.navigate(Route.Settings) }
        compose.runOnIdle { nav.openWall(films, LibraryFilter(picture = "Ultra HD")) }
        focusIsOnFilm(1)
        back()
        compose.runOnIdle { assertTrue("the library in numbers", nav.on(Route.Settings::class)) }
    }

    /** The films' wall from home shows their sagas among them; the same films opened filtered show every film. */
    @Test
    fun theFilmsWallFromHomeGroupsItsSagasAWallOpenedFilteredDoesNot() {
        val nav = app()
        compose.runOnIdle { nav.openLibrary(films, sagaLibrary) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(FakeProvider.Asked("movies", LibraryFilter(), grouped = true), source.pages.last()) }
        compose.runOnIdle { nav.openWall(films, LibraryFilter(picture = "Ultra HD")) }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(source.pages.last().grouped) }
    }

    /** Home's sagas door opens the films' wall over home, on its sagas alone, by its route: the wall's saved state starts with it. */
    @Test
    fun theSagasDoorOpensTheFilmsWallOnItsSagasByItsRoute() {
        var wall: LibraryViewModel? = null
        lateinit var nav: NavHostController
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Home) {
                composable<Route.Home> {}
                composable<Route.Library> {
                    wall = viewModel(
                        factory = viewModelFactory {
                            initializer { LibraryViewModel({ source }, films, createSavedStateHandle(), sagas = sagaLibrary) }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.openSagas(films, sagaLibrary) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("movies" to "collections", nav.wall().id to nav.wall().sagas)
            assertTrue("a page over home", nav.overHome())
            assertEquals(WallView.SAGAS, checkNotNull(wall).state.value.view)
        }
    }
}
