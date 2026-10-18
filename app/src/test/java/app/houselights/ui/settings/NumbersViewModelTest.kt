package app.houselights.ui.settings

import app.houselights.core.Catalog
import app.houselights.core.Credits
import app.houselights.core.Job
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.LibraryNumbers
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.Saga
import app.houselights.core.SearchResults
import app.houselights.core.Version
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** The library in numbers: counted once off the main thread, with its walls; a failure recounts on demand. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NumbersViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val films = Library("movies", LibraryKind.MOVIES, null)
    private val shows = Library("shows", LibraryKind.SHOWS, null)
    private val catalog = CountingCatalog()
    private val fake = FakeProvider().apply { libraries = listOf(films, shows, Library("sagas", LibraryKind.COLLECTIONS, null)) }
    private val provider = object : MediaProvider by fake {
        override val catalog: Catalog = this@NumbersViewModelTest.catalog
    }

    @Test
    fun theLibraryIsCountedOnceHoweverOftenItsPaneShows() = runTest {
        val vm = NumbersViewModel { provider }
        vm.count()
        vm.count()
        advanceUntilIdle()
        vm.count()
        advanceUntilIdle()
        assertEquals(1, catalog.counted)
        val counted = vm.state as NumbersState.Counted
        assertEquals(412, counted.numbers.films)
        assertEquals(mapOf(LibraryKind.MOVIES to films, LibraryKind.SHOWS to shows), counted.walls)
    }

    @Test
    fun aLibraryThatCouldNotBeCountedSaysWhyThenIsCountedAgain() = runTest {
        catalog.fail = IOException("NAS away")
        val vm = NumbersViewModel { provider }
        vm.count()
        advanceUntilIdle()
        assertEquals(NumbersState.Failed(UiError.OFFLINE), vm.state)

        catalog.fail = null
        vm.retry()
        assertEquals(NumbersState.Failed(UiError.OFFLINE, retrying = true), vm.state)
        advanceUntilIdle()
        assertEquals(412, (vm.state as NumbersState.Counted).numbers.films)
    }

    /** A catalog that counts, and tells nothing else. */
    private class CountingCatalog : Catalog {
        var counted = 0
        var fail: Throwable? = null

        override suspend fun numbers(): LibraryNumbers {
            counted++
            fail?.let { throw it }
            return LibraryNumbers(films = 412, shows = 38, episodes = 1204, sizeBytes = null, runtimeMs = null)
        }

        override suspend fun credits(item: MediaItem) = Credits()
        override suspend fun person(id: String): PersonPage? = null
        override suspend fun people(job: Job, kind: MediaKind?, minTitles: Int, limit: Int) = emptyList<Person>()
        override suspend fun bestRated(kind: MediaKind, minVotes: Int, limit: Int) = emptyList<MediaItem>()
        override suspend fun saga(collection: MediaItem): Saga? = null
        override suspend fun versions(item: MediaItem) = emptyList<Version>()
        override suspend fun chooseVersion(item: MediaItem, version: Version?) = Unit
        override suspend fun playback(version: Version, resume: Boolean, options: PlaybackOptions): PlaybackPlan = error("Not played here")
        override suspend fun search(query: String, limit: Int) = SearchResults()
    }
}
