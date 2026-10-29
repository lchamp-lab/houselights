package app.houselights.ui.search

import androidx.lifecycle.SavedStateHandle
import app.houselights.core.SearchResults
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.favoredAs
import app.houselights.ui.library.markedAs
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import app.houselights.ui.util.errorOrNull
import app.houselights.ui.util.valueOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import java.net.ConnectException

/** A search: what is typed shows at once, what it finds comes `WAIT_MS` after the last key, once. */
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
        val vm = SearchViewModel({ provider })
        backgroundScope.launch { vm.found.collect {} }
        "AME".forEach {
            vm.type(it)
            advanceTimeBy(SearchViewModel.WAIT_MS / 2)
        }
        assertEquals("AME", vm.query.value)
        assertEquals("not while typing", emptyList<String>(), catalog.calls)
        advanceUntilIdle()
        assertEquals(listOf("search AME"), catalog.calls)
        assertEquals("amelie", vm.found.value?.results?.valueOrNull?.titles?.single()?.id)
    }

    @Test
    fun spacesSeparateWordsOnly() = runTest {
        val vm = SearchViewModel({ provider })
        vm.space()
        assertEquals("", vm.query.value)
        vm.type('A')
        vm.space()
        vm.space()
        assertEquals("A ", vm.query.value)
        vm.delete()
        vm.delete()
        assertEquals("", vm.query.value)
    }

    @Test
    fun nothingTypedFindsNothing() = runTest {
        val vm = SearchViewModel({ provider })
        backgroundScope.launch { vm.found.collect {} }
        vm.type('A')
        vm.delete()
        advanceUntilIdle()
        assertNull(vm.found.value)
        assertEquals(emptyList<String>(), catalog.calls)
    }

    @Test
    fun aSearchThatFailsSaysWhyAndRetryRunsItAgain() = runTest {
        catalog.fail = { ConnectException("Server away") }
        val vm = SearchViewModel({ provider })
        backgroundScope.launch { vm.found.collect {} }
        "AME".forEach(vm::type)
        advanceUntilIdle()
        assertEquals("not that nothing matches", UiError.OFFLINE, vm.found.value?.results?.errorOrNull)

        catalog.fail = { null }
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.found.value?.results?.errorOrNull)
        assertEquals("amelie", vm.found.value?.results?.valueOrNull?.titles?.single()?.id)
        assertEquals(listOf("search AME", "search AME"), catalog.calls)

        vm.retry()
        advanceUntilIdle()
        assertEquals("a search that found is not run again", 2, catalog.calls.size)
    }

    /** Typed key by key on the remote: a film played meanwhile, the process stopped, Back finds the query and its results. */
    @Test
    fun theQueryOutlivesAStoppedProcess() = runTest {
        val saved = SavedStateHandle()
        val before = SearchViewModel({ provider }, saved)
        "AME".forEach(before::type)
        before.space()
        before.delete()

        val after = SearchViewModel({ provider }, saved.afterProcessDeath())
        backgroundScope.launch { after.found.collect {} }
        assertEquals("AME", after.query.value)
        advanceUntilIdle()
        assertEquals("amelie", after.found.value?.results?.valueOrNull?.titles?.single()?.id)
    }

    /** A title marked from its result shows so at once, the result staying; then it is saved, as from its page, and told. */
    @Test
    fun aResultMarkedShowsAtOnceThenIsSavedAndTold() = runTest {
        val events = FakeEvents()
        val vm = SearchViewModel({ provider }, events = events, work = this)
        backgroundScope.launch { vm.found.collect {} }
        "AME".forEach(vm::type)
        advanceUntilIdle()
        vm.mark(found(vm).single())
        advanceUntilIdle()
        assertTrue(found(vm).single().user.played)
        assertTrue("played amelie true" in provider.calls)
        assertEquals(1L, events.libraryVersion.value)

        vm.favorite(found(vm).single())
        advanceUntilIdle()
        assertTrue(found(vm).single().user.favorite)
        assertTrue("favorite amelie true" in provider.calls)
    }

    /** A mark not saved comes undone, the result as found, and why is said for it; another search shows what it finds. */
    @Test
    fun aResultsMarkNotSavedIsUndoneAndSaid() = runTest {
        provider.fail = { if (it.startsWith("played")) IOException("NAS away") else null }
        val vm = SearchViewModel({ provider }, work = this)
        backgroundScope.launch { vm.found.collect {} }
        "AME".forEach(vm::type)
        advanceUntilIdle()
        vm.mark(found(vm).single())
        advanceUntilIdle()
        assertFalse(found(vm).single().user.played)
        assertEquals(NotSaved("amelie", UiError.OFFLINE), vm.notSaved.value)
    }

    /**
     * Read again, its query typed anew, a search shows what the source says then: what a result's options changed over
     * the read before is no longer laid over it, the title marked again on its page meanwhile.
     */
    @Test
    fun aSearchReadAgainShowsWhatTheSourceSaysThen() = runTest {
        val vm = SearchViewModel({ provider }, work = this)
        backgroundScope.launch { vm.found.collect {} }
        "AME".forEach(vm::type)
        advanceUntilIdle()
        vm.mark(found(vm).single())
        advanceUntilIdle()
        assertTrue(found(vm).single().user.played)

        // Its page marks it unwatched again; a letter typed, then erased, the same query is read again.
        vm.type('L')
        advanceUntilIdle()
        vm.delete()
        advanceUntilIdle()
        assertEquals(listOf("search AME", "search AMEL", "search AME"), catalog.calls)
        assertFalse(found(vm).single().user.played)
    }

    /**
     * Two changes of one result, neither saved, show it as found whichever fails first, the one still saving showing
     * meanwhile: marked then made a favourite, or marked then unmarked.
     */
    @Test
    fun twoChangesOfAResultNotSavedLeaveItAsFound() = runTest {
        provider.fail = { if (it.startsWith("played") || it.startsWith("favorite")) IOException("NAS away") else null }
        listOf(true, false).forEach { markFailsFirst ->
            val vm = SearchViewModel({ provider }, work = this)
            val collecting = backgroundScope.launch { vm.found.collect {} }
            "AME".forEach(vm::type)
            advanceUntilIdle()
            val amelie = found(vm).single()
            val marking = provider.gate("played amelie true")
            val favouring = provider.gate("favorite amelie true")
            vm.mark(amelie)
            runCurrent()
            vm.favorite(found(vm).single())
            runCurrent()
            assertEquals(amelie.markedAs(true).favoredAs(true), found(vm).single())
            (if (markFailsFirst) marking else favouring).complete(Unit)
            advanceUntilIdle()
            val other = if (markFailsFirst) amelie.favoredAs(true) else amelie.markedAs(true)
            assertEquals("$markFailsFirst: the other still saving shows", other, found(vm).single())
            (if (markFailsFirst) favouring else marking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", amelie, found(vm).single())
            assertEquals(NotSaved("amelie", UiError.OFFLINE), vm.notSaved.value)

            val marking2 = provider.gate("played amelie true")
            val unmarking = provider.gate("played amelie false")
            vm.mark(amelie)
            runCurrent()
            vm.mark(found(vm).single())
            runCurrent()
            assertEquals(amelie.markedAs(true).markedAs(false), found(vm).single())
            (if (markFailsFirst) marking2 else unmarking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", !markFailsFirst, found(vm).single().user.played)
            (if (markFailsFirst) unmarking else marking2).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", amelie, found(vm).single())
            collecting.cancel()
        }
    }

    /** A result's mark not saved leaves a favourite asked after it showing, saved before or after the mark fails. */
    @Test
    fun aResultsMarkNotSavedLeavesAFavouriteSavedShowing() = runTest {
        provider.fail = { if (it.startsWith("played")) IOException("NAS away") else null }
        listOf(true, false).forEach { markFailsFirst ->
            val vm = SearchViewModel({ provider }, work = this)
            val collecting = backgroundScope.launch { vm.found.collect {} }
            "AME".forEach(vm::type)
            advanceUntilIdle()
            val amelie = found(vm).single()
            val marking = provider.gate("played amelie true")
            val favouring = provider.gate("favorite amelie true")
            vm.mark(amelie)
            runCurrent()
            vm.favorite(found(vm).single())
            runCurrent()
            assertEquals(amelie.markedAs(true).favoredAs(true), found(vm).single())
            (if (markFailsFirst) marking else favouring).complete(Unit)
            advanceUntilIdle()
            (if (markFailsFirst) favouring else marking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", amelie.favoredAs(true), found(vm).single())
            assertEquals(NotSaved("amelie", UiError.OFFLINE), vm.notSaved.value)
            collecting.cancel()
        }
    }

    private fun found(vm: SearchViewModel) = checkNotNull(vm.found.value?.results?.valueOrNull).titles
}
