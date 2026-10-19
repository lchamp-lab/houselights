package app.houselights.ui.library

import androidx.lifecycle.SavedStateHandle
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun aChangeReloadsWhatIsShownInPlace() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        provider.calls.clear()

        vm.onLibraryChanged()
        advanceUntilIdle()
        // In one go, as much as was shown: the grid keeps its place.
        assertEquals(listOf("page 0 240", "facets"), provider.calls)
        assertEquals(240, vm.state.value.items.size)
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
            status = WatchStatus.UNWATCHED, genre = "Drame", decade = 1990, quality = Quality.UHD_4K,
            picture = "Dolby Vision", sound = "TrueHD Atmos", fileType = "mkv", person = "p7",
        )
        val before = LibraryViewModel({ provider }, library, saved)
        before.setSort(SortOption.ADDED)
        before.setFilter(filter)
        advanceUntilIdle()

        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath())
        assertEquals(SortOption.ADDED, after.state.value.sort)
        assertEquals(filter, after.state.value.filter)
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
