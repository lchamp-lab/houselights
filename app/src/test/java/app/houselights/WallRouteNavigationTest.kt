package app.houselights

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.ProviderCapabilities
import app.houselights.core.Quality
import app.houselights.ui.library.LibraryViewModel
import app.houselights.ui.library.WallView
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.afterProcessDeath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A wall opened through navigation, its ViewModel made from its route and the saved state navigation gives it, with
 * the arguments the wall's factory passes: it opens on the route's filter and view, and a ViewModel made on what that
 * saved state keeps for a new process reads the filter back.
 */
@RunWith(RobolectricTestRunner::class)
class WallRouteNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val provider = FakeProvider().apply {
        capabilities = ProviderCapabilities(
            markPlayed = true,
            favorites = true,
            similar = true,
            pathMapping = true,
            formatFilters = true,
            personFilter = true,
        )
    }

    /** Where the walls' ViewModels live, cleared after each test as a screen's are. */
    private val walls = ViewModelStore()

    @After
    fun clear() = walls.clear()

    /** The route [start] opens, and the saved state its wall's ViewModel is given. */
    private fun open(start: Route.Library): Pair<Route.Library, SavedStateHandle> {
        var read: Route.Library? = null
        var saved: SavedStateHandle? = null
        compose.setContent {
            NavHost(rememberNavController(), startDestination = start) {
                composable<Route.Library> { entry ->
                    read = entry.toRoute<Route.Library>()
                    saved = viewModel { Kept(createSavedStateHandle()) }.handle
                }
            }
        }
        compose.waitForIdle()
        return checkNotNull(read) to checkNotNull(saved)
    }

    /** The wall's ViewModel on [saved], with the arguments its factory passes; [opensOn] null, as a wall read back from it. */
    private fun wall(route: Route.Library, saved: SavedStateHandle, opensOn: LibraryFilter? = route.filter): LibraryViewModel {
        val factory = viewModelFactory {
            initializer { LibraryViewModel({ provider }, route.wallLibrary, saved, opensOn = opensOn, sagas = route.sagasLibrary) }
        }
        return ViewModelProvider(walls, factory)["${walls.keys().size}", LibraryViewModel::class.java]
    }

    @Test
    fun aWallsFilterCrossesNavigationIntoItsViewModel() {
        val filter =
            LibraryFilter(quality = Quality.HDR, picture = "Dolby Vision", sound = "TrueHD Atmos", fileType = "bdmv", person = "p7")
        val start = Route.Library.of(Library("movies", LibraryKind.MOVIES, null), filter, personName = "Denis Villeneuve")
        val (route, saved) = open(start)
        assertEquals("Denis Villeneuve", route.personName)
        assertEquals(filter, wall(route, saved).state.value.filter)
        assertEquals("after a stopped process", filter, wall(route, saved.afterProcessDeath(), opensOn = null).state.value.filter)
    }

    /** The sagas alone, as a films wall's route asks: its saved state starts with that view. */
    @Test
    fun aWallsViewCrossesNavigationIntoItsViewModel() {
        val start = Route.Library("movies", LibraryKind.MOVIES, sagas = "sagas", view = WallView.SAGAS.name)
        val (route, saved) = open(start)
        assertEquals(WallView.SAGAS, wall(route, saved).state.value.view)
    }

    /** Holds the saved state navigation gives a destination's ViewModel, as it gives the wall's. */
    class Kept(val handle: SavedStateHandle) : ViewModel()
}
