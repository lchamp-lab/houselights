package app.houselights.ui.search

import app.houselights.core.SearchResults
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A search: what is typed shows at once, what it finds comes `WAIT_MS` after the last key, once. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val catalog = FakeCatalog().apply { found = SearchResults(titles = listOf(film("amelie", "Amélie"))) }
    private val provider = FakeProvider().apply { catalog = this@SearchViewModelTest.catalog }

    @Test
    fun itSearchesOnceTheKeysStop() = runTest {
        val vm = SearchViewModel { provider }
        backgroundScope.launch { vm.found.collect {} }
        "AME".forEach { vm.type(it); advanceTimeBy(SearchViewModel.WAIT_MS / 2) }
        assertEquals("AME", vm.query.value)
        assertEquals("not while typing", emptyList<String>(), catalog.calls)
        advanceUntilIdle()
        assertEquals(listOf("search AME"), catalog.calls)
        assertEquals("amelie", vm.found.value?.results?.titles?.single()?.id)
    }

    @Test
    fun spacesSeparateWordsOnly() = runTest {
        val vm = SearchViewModel { provider }
        vm.space()
        assertEquals("", vm.query.value)
        vm.type('A'); vm.space(); vm.space()
        assertEquals("A ", vm.query.value)
        vm.delete(); vm.delete()
        assertEquals("", vm.query.value)
    }

    @Test
    fun nothingTypedFindsNothing() = runTest {
        val vm = SearchViewModel { provider }
        backgroundScope.launch { vm.found.collect {} }
        vm.type('A'); vm.delete()
        advanceUntilIdle()
        assertNull(vm.found.value)
        assertEquals(emptyList<String>(), catalog.calls)
    }
}
