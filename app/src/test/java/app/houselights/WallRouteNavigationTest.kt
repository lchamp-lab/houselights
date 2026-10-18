package app.houselights

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A wall opened through navigation reads its filter back from its saved state, under its ViewModel's keys. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WallRouteNavigationTest {
    class Kept(val handle: SavedStateHandle) : ViewModel()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aWallsFilterCrossesNavigationAsItsViewModelKeepsIt() {
        val filter = LibraryFilter(quality = Quality.HDR, picture = "Dolby Vision", person = "p7")
        val start = Route.Library.of(Library("movies", LibraryKind.MOVIES, null), filter, personName = "Denis Villeneuve")
        var read: Route.Library? = null
        var saved: List<Any?> = emptyList()
        compose.setContent {
            NavHost(rememberNavController(), startDestination = start) {
                composable<Route.Library> { entry ->
                    read = entry.toRoute<Route.Library>()
                    // The saved state a wall's ViewModel is given, as the wall's factory makes it.
                    val kept = viewModel { Kept(createSavedStateHandle()) }.handle
                    saved = listOf("quality", "picture", "sound", "person").map { kept.get<Any?>(it) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(filter, read?.filter)
        assertEquals("Denis Villeneuve", read?.personName)
        assertEquals(listOf("HDR", "Dolby Vision", null, "p7"), saved)
    }
}
