package app.houselights.ui.library

import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** The wall: pages, reloads in place and failures, on a fake source. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
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
}
