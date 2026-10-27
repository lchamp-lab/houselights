package app.houselights.ui.settings

import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.LibraryNumbers
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** The library in numbers: counted once, with its walls; a failure recounts on demand. */
@RunWith(RobolectricTestRunner::class)
class NumbersViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val films = Library("movies", LibraryKind.MOVIES, null)
    private val shows = Library("shows", LibraryKind.SHOWS, null)
    private val catalog = FakeCatalog().apply {
        numbers = LibraryNumbers(films = 412, shows = 38, episodes = 1204, sizeBytes = null, runtimeMs = null)
    }
    private val provider = FakeProvider().apply {
        libraries = listOf(films, shows, Library("sagas", LibraryKind.COLLECTIONS, null))
        catalog = this@NumbersViewModelTest.catalog
    }

    @Test
    fun theLibraryIsCountedOnceHoweverOftenItsPaneShows() = runTest {
        val vm = NumbersViewModel { provider }
        vm.count()
        vm.count()
        advanceUntilIdle()
        vm.count()
        advanceUntilIdle()
        assertEquals(listOf("numbers"), catalog.calls)
        val counted = (vm.state as LoadState.Ready).value
        assertEquals(412, counted.numbers.films)
        assertEquals(mapOf(LibraryKind.MOVIES to films, LibraryKind.SHOWS to shows), counted.walls)
    }

    @Test
    fun aLibraryThatCouldNotBeCountedSaysWhyThenIsCountedAgain() = runTest {
        catalog.fail = { IOException("NAS away") }
        val vm = NumbersViewModel { provider }
        vm.count()
        advanceUntilIdle()
        assertEquals(LoadState.Failed(UiError.OFFLINE), vm.state)

        catalog.fail = { null }
        vm.retry()
        assertEquals(LoadState.Failed(UiError.OFFLINE, retrying = true), vm.state)
        advanceUntilIdle()
        assertEquals(412, (vm.state as LoadState.Ready).value.numbers.films)
    }

    /** A source gone meanwhile (signed out) is a failure said, not a crash. */
    @Test
    fun aSourceGoneIsSaid() = runTest {
        val vm = NumbersViewModel { error("No library source configured") }
        vm.count()
        advanceUntilIdle()
        assertEquals(LoadState.Failed(UiError.FAILED), vm.state)
    }
}
