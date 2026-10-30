package app.houselights.ui.library

import androidx.lifecycle.SavedStateHandle
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.FakeProvider.Asked
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** The wall: pages, reloads in place, failures, and the sort and filter it opens on or comes back to, on a fake source. */
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val provider = FakeProvider().apply { wall = (1..300).map { film("m$it") } }
    private val library = Library("films", LibraryKind.MOVIES, "Films")

    @Test
    fun theFirstPageLoadsThenTheNextAppendsWithoutDuplicates() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        assertEquals(300, vm.state.value.total)

        // A title added meanwhile shifts the next page by one: it is not shown twice.
        provider.wall = listOf(film("new")) + provider.wall
        vm.loadMore()
        advanceUntilIdle()
        val ids = vm.state.value.items.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(239, ids.size)
    }

    /** A wall restored before its source could be read (its connection unreadable): an error, never a crash. */
    @Test
    fun withoutASourceTheWallOpensOnAnError() = runTest {
        val vm = LibraryViewModel({ null }, library, SavedStateHandle(), opensOn = LibraryFilter(picture = "Dolby Vision"))
        assertFalse(vm.capabilities.formatFilters)
        advanceUntilIdle()
        assertEquals(UiError.FAILED, vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun aChangeReloadsWhatIsShownInPlace() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        provider.calls.clear()

        vm.onLibraryChanged()
        advanceUntilIdle()
        // In one go, as much as was shown: the grid keeps its place. The films wall first reads whether it has sagas.
        assertEquals(listOf("libraries", "facets", "page 0 240"), provider.calls)
        assertEquals(240, vm.state.value.items.size)
    }

    /**
     * A scan's progress while the next page loads, its reload cancelling that page: the reload reads it too, so the
     * wall goes on past it, never stopping at its last row with no foot.
     */
    @Test
    fun aReloadWhileTheNextPageLoadsReadsItToo() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.gate("page 120 120")
        vm.loadMore()
        advanceUntilIdle()
        provider.calls.clear()

        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals(listOf("page 0 240"), provider.calls)
        assertEquals(240, vm.state.value.items.size)
    }

    /** A scan's progress reloads the titles shown; what the filters offer and the sagas wait for its end. */
    @Test
    fun aScansProgressReloadsOnlyTheTitles() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.calls.clear()

        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals(listOf("page 0 120"), provider.calls)
    }

    @Test
    fun aFailedReloadKeepsWhatIsShown() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page")) IOException("NAS away") else null }

        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        assertNull(vm.state.value.error)
    }

    @Test
    fun retryStartsAgainFromTheFirstPage() = runTest {
        provider.fail = { IOException("NAS away") }
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        provider.calls.clear()
        vm.retry()
        assertNull(vm.state.value.error)
        advanceUntilIdle()
        assertEquals("page 0 120", provider.calls.first())
        assertEquals(120, vm.state.value.items.size)
    }

    @Test
    fun aFailedNextPageWaitsForRetryAtTheFootOfTheWall() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page 120")) IOException("Wi-Fi gone") else null }
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.pageError)
        // The wall stays, and says nothing of an error of its own.
        assertEquals(120, vm.state.value.items.size)
        assertNull(vm.state.value.error)

        // Focus along the last lines does not try again by itself.
        provider.calls.clear()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), provider.calls)

        provider.fail = { null }
        vm.retryPage()
        assertTrue(vm.state.value.pageRetrying)
        advanceUntilIdle()
        assertNull(vm.state.value.pageError)
        assertFalse(vm.state.value.pageRetrying)
        assertEquals(240, vm.state.value.items.size)
    }

    /** Réessayer at the foot, then a change whose reload fails: the foot can be pressed again. */
    @Test
    fun aFailedReloadFreesTheRetryAtTheFoot() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page 120")) IOException("Wi-Fi gone") else null }
        vm.loadMore()
        advanceUntilIdle()
        provider.gate("page 120 120")
        vm.retryPage()
        advanceUntilIdle()
        assertTrue(vm.state.value.pageRetrying)

        provider.fail = { if (it.startsWith("page")) IOException("Wi-Fi gone") else null }
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertFalse("Réessayer stayed at work", vm.state.value.pageRetrying)
        assertFalse(vm.state.value.loading)
        assertEquals(UiError.OFFLINE, vm.state.value.pageError)

        provider.gates.clear()
        provider.fail = { null }
        vm.retryPage()
        advanceUntilIdle()
        assertEquals(240, vm.state.value.items.size)
    }

    /** A change while the first page loads, whose reload fails: the wall says why instead of waiting forever. */
    @Test
    fun aFailedReloadOfTheFirstPageSaysWhy() = runTest {
        val first = provider.gate("page 0 120")
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        vm.onLibraryChanged()
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page")) IOException("NAS away") else null }
        first.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(UiError.OFFLINE, vm.state.value.error)
    }

    /** Android killed the app while a film played: the wall comes back sorted and filtered as it was left, by every field. */
    @Test
    fun theSortAndFilterOutliveAStoppedProcess() = runTest {
        val saved = SavedStateHandle()
        val filter = LibraryFilter(
            status = WatchStatus.UNWATCHED,
            genre = "Drame",
            decade = 1990,
            quality = Quality.UHD_4K,
            picture = "Dolby Vision",
            sound = "TrueHD Atmos",
            fileType = "mkv",
            person = "p7",
        )
        val before = LibraryViewModel({ provider }, library, saved)
        before.setSort(SortOption.ADDED)
        before.setFilter(filter)
        advanceUntilIdle()

        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath())
        assertEquals(SortOption.ADDED, after.state.value.sort)
        assertEquals(filter, after.state.value.filter)
    }

    /**
     * Android killed the app with 300 titles shown: the wall comes back with as many, read in one page, so that focus
     * finds the title it left; sorted anew, it starts again from its first page.
     */
    @Test
    fun aWallBroughtBackReadsAsFarAsItWasLeft() = runTest {
        val saved = SavedStateHandle()
        val before = LibraryViewModel({ provider }, library, saved)
        advanceUntilIdle()
        repeat(2) {
            before.loadMore()
            advanceUntilIdle()
        }
        assertEquals(300, before.state.value.items.size)

        provider.calls.clear()
        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath())
        advanceUntilIdle()
        assertEquals(300, after.state.value.items.size)
        assertEquals(listOf("page 0 300"), provider.calls.filter { it.startsWith("page") })

        provider.calls.clear()
        after.setSort(SortOption.ADDED)
        advanceUntilIdle()
        assertEquals(listOf("page 0 120"), provider.calls.filter { it.startsWith("page") })
    }

    /**
     * Brought back with 300 titles shown, the wall reads as many though its first read does not end: cut by a scan's
     * progress, failed then retried, or cut by Android stopping the app again.
     */
    @Test
    fun aWallBroughtBackReadsAsFarAsItWasLeftThoughItsFirstReadDoesNotEnd() = runTest {
        val saved = SavedStateHandle()
        val before = LibraryViewModel({ provider }, library, saved)
        advanceUntilIdle()
        repeat(2) {
            before.loadMore()
            advanceUntilIdle()
        }
        fun pages() = provider.calls.filter { it.startsWith("page") }

        // A scan's progress reloads the wall while its first page is read.
        val first = provider.gate("page 0 300")
        provider.calls.clear()
        val cut = LibraryViewModel({ provider }, library, saved.afterProcessDeath())
        advanceUntilIdle()
        cut.onLibraryChanged(scanOnly = true)
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals("page 0 300", pages().last())
        assertEquals(300, cut.state.value.items.size)

        // Its first page fails, then Réessayer, after Android stopped the app once more while it was read.
        provider.gates.clear()
        provider.fail = { if (it.startsWith("page")) IOException("NAS away") else null }
        val failing = saved.afterProcessDeath()
        val failed = LibraryViewModel({ provider }, library, failing)
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, failed.state.value.error)
        provider.fail = { null }
        provider.calls.clear()
        val again = LibraryViewModel({ provider }, library, failing.afterProcessDeath())
        advanceUntilIdle()
        assertEquals(listOf("page 0 300"), pages())
        assertEquals(300, again.state.value.items.size)
        provider.calls.clear()
        failed.retry()
        advanceUntilIdle()
        assertEquals(listOf("page 0 300"), pages())
        assertEquals(300, failed.state.value.items.size)
    }

    /** Opened from the library in numbers on Dolby Vision films: the wall and its first page start filtered. */
    @Test
    fun aWallOpenedOnPartOfTheLibraryStartsFilteredByIt() = runTest {
        provider.capabilities = provider.capabilities.copy(formatFilters = true, personFilter = true)
        val vm = LibraryViewModel({ provider }, library, SavedStateHandle(), opensOn = LibraryFilter(picture = "Dolby Vision"))
        assertEquals(LibraryFilter(picture = "Dolby Vision"), vm.state.value.filter)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
    }

    @Test
    fun aWallBroughtBackIsAsItWasLeftNotAsItWasOpened() = runTest {
        provider.capabilities = provider.capabilities.copy(formatFilters = true)
        val saved = SavedStateHandle()
        val opensOn = LibraryFilter(picture = "Dolby Vision")
        val changed = LibraryFilter(picture = "Dolby Vision", sound = "E-AC3")
        LibraryViewModel({ provider }, library, saved, opensOn = opensOn).setFilter(changed)
        advanceUntilIdle()

        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), opensOn = opensOn)
        assertEquals(changed, after.state.value.filter)
    }

    /** A source that cannot filter by format or person (Jellyfin's formats) never opens a wall on them. */
    @Test
    fun aWallNeverStartsOnWhatItsSourceCannotFilterBy() = runTest {
        provider.capabilities = provider.capabilities.copy(formatFilters = false, personFilter = true)
        val vm = LibraryViewModel({ provider }, library, SavedStateHandle(), opensOn = LibraryFilter(sound = "E-AC3", person = "p7"))
        assertEquals(LibraryFilter(person = "p7"), vm.state.value.filter)
    }

    private val sagas = Library("sagas", LibraryKind.COLLECTIONS, null)

    /** The films wall of a source with sagas: two films and a saga, and the saga alone. */
    private fun withSagas() {
        val alien = MediaItem("c1", MediaKind.COLLECTION, "Alien")
        provider.walls = mapOf(library.id to listOf(alien, film("m1"), film("m2")), sagas.id to listOf(alien))
    }

    @Test
    fun aFilmsWallWithSagasShowsThemAmongTheFilmsAndCountsThem() = runTest {
        withSagas()
        val vm = LibraryViewModel({ provider }, library, sagas = sagas)
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS, vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = true), provider.pages.single())
        assertEquals(3 to 1, vm.state.value.total to vm.state.value.sagas)
    }

    @Test
    fun theSagasAloneAreAskedOfTheSagasWithTheFilmsFilters() = runTest {
        withSagas()
        provider.capabilities = provider.capabilities.copy(sagaFilters = true)
        val vm = LibraryViewModel({ provider }, library, sagas = sagas)
        val unwatched = LibraryFilter(status = WatchStatus.UNWATCHED)
        vm.setFilter(unwatched)
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        assertEquals(WallView.SAGAS, vm.state.value.view)
        assertEquals(Asked("sagas", unwatched, grouped = false), provider.pages.last())
        assertEquals(listOf("c1"), vm.state.value.items.map { it.id })
        assertEquals("the films' choices, asked once", 1, provider.calls.count { it == "facets" })
    }

    /** Jellyfin cannot filter a collection by its films: the sagas show whole, the filters wait for the films. */
    @Test
    fun aSourceThatCannotFilterSagasShowsThemWholeAndKeepsTheFilters() = runTest {
        withSagas()
        val vm = LibraryViewModel({ provider }, library, sagas = sagas)
        val unwatched = LibraryFilter(status = WatchStatus.UNWATCHED)
        vm.setFilter(unwatched)
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        assertEquals(Asked("sagas", LibraryFilter(), grouped = false), provider.pages.last())
        assertEquals(unwatched, vm.state.value.filter)

        vm.setView(WallView.FILMS_AND_SAGAS)
        advanceUntilIdle()
        assertEquals(Asked("films", unwatched, grouped = true), provider.pages.last())
    }

    @Test
    fun theViewOutlivesAStoppedProcess() = runTest {
        withSagas()
        val saved = SavedStateHandle()
        LibraryViewModel({ provider }, library, saved, sagas = sagas).setView(WallView.SAGAS)
        advanceUntilIdle()
        provider.pages.clear()

        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), sagas = sagas)
        assertEquals(WallView.SAGAS, after.state.value.view)
        advanceUntilIdle()
        assertEquals("sagas", provider.pages.single().library)
    }

    /** Clearing the filters goes back to the films and their sagas, unfiltered, in one load, and stays so. */
    @Test
    fun clearingTheFiltersGoesBackToTheFilmsAndSagas() = runTest {
        withSagas()
        val saved = SavedStateHandle()
        val vm = LibraryViewModel({ provider }, library, saved, sagas = sagas)
        vm.setFilter(LibraryFilter(genre = "Drame"))
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        provider.pages.clear()

        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS to LibraryFilter(), vm.state.value.view to vm.state.value.filter)
        assertEquals(listOf(Asked("films", LibraryFilter(), grouped = true)), provider.pages)
        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), sagas = sagas)
        assertEquals(WallView.FILMS_AND_SAGAS to LibraryFilter(), after.state.value.view to after.state.value.filter)
    }

    /** A person's films, or a format's, opened from elsewhere: every film on its own, no view to choose. */
    @Test
    fun aWallOpenedOnPartOfTheLibraryShowsEveryFilm() = runTest {
        withSagas()
        provider.capabilities = provider.capabilities.copy(personFilter = true)
        val vm = LibraryViewModel({ provider }, library, SavedStateHandle(), opensOn = LibraryFilter(person = "p7"), sagas = sagas)
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(person = "p7"), grouped = false), provider.pages.single())
        assertNull(vm.state.value.sagas)
        // Nothing to turn to, even once the source shows sagas.
        vm.setView(WallView.SAGAS)
        assertNull(vm.state.value.view)
        provider.libraries = listOf(library, sagas)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertFalse(provider.pages.last().grouped)
    }

    /** The Sagas setting changed while the wall is open: hidden, its films alone and nothing to choose; back, grouped. */
    @Test
    fun theWallFollowsTheSagasTheSourceShows() = runTest {
        withSagas()
        provider.libraries = listOf(library, sagas)
        val saved = SavedStateHandle()
        val vm = LibraryViewModel({ provider }, library, saved, sagas = sagas)
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        provider.pages.clear()

        provider.libraries = listOf(library)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertEquals(listOf(Asked("films", LibraryFilter(), grouped = false)), provider.pages)
        // The sagas alone forgotten: a wall brought back by its route, which names the sagas, opens on the films.
        val restored = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), sagas = sagas)
        assertEquals(WallView.FILMS_AND_SAGAS, restored.state.value.view)

        // Back, on the films and their sagas: the sagas alone were left with them.
        provider.libraries = listOf(library, sagas)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS, vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = true), provider.pages.last())
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        assertEquals("sagas", provider.pages.last().library)
    }

    /** A wall opened while the sagas were hidden groups them once the setting shows them. */
    @Test
    fun aWallOpenedWithoutSagasGroupsThemOnceShown() = runTest {
        withSagas()
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertNull(vm.state.value.view)

        provider.libraries = listOf(library, sagas)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS, vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = true), provider.pages.last())
    }

    @Test
    fun aWallWithoutSagasGroupsNothing() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = false), provider.pages.single())
    }

    @Test
    fun theLatestFacetsWin() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        val older = provider.gate("facets")
        vm.onLibraryChanged()
        advanceUntilIdle()
        val newer = provider.gate("facets")
        vm.onLibraryChanged()
        advanceUntilIdle()
        provider.facets = LibraryFacets(genres = listOf("Drame"))
        newer.complete(Unit)
        advanceUntilIdle()
        provider.facets = LibraryFacets(genres = listOf("Western"))
        older.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Drame"), vm.state.value.facets.genres)
    }
}
