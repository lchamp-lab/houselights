package app.houselights.ui.person

import app.houselights.core.Job
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.core.PersonTitle
import app.houselights.ui.library.NotSaved
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import app.houselights.ui.util.valueOrNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** A person's page: read from the catalog, gone when none of their titles is left, kept when a reload fails. */
@RunWith(RobolectricTestRunner::class)
class PersonViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val melville = Person("Jean-Pierre Melville", null, null, id = "tmdb:1", titleCount = 3)
    private val page = PersonPage(
        melville,
        mapOf(Job.DIRECTOR to 3, Job.CAST to 1),
        listOf("Le Silence de la mer", "Le Samouraï", "Le Cercle rouge").map { PersonTitle(film(it), listOf(Job.DIRECTOR), null) },
    )
    private val catalog = FakeCatalog().apply { pages = mapOf("tmdb:1" to page) }
    private val provider = FakeProvider().apply { catalog = this@PersonViewModelTest.catalog }

    @Test
    fun readsThePage() = runTest {
        val vm = PersonViewModel("tmdb:1", { provider })
        advanceUntilIdle()
        assertEquals(LoadState.Ready(page), vm.state.value)
    }

    @Test
    fun noneOfTheirTitlesLeftSaysSo() = runTest {
        val vm = PersonViewModel("tmdb:2", { provider })
        advanceUntilIdle()
        assertEquals(LoadState.Failed(UiError.PERSON_GONE), vm.state.value)
    }

    /** Their last title gone while the page shows, the page says so in its place. */
    @Test
    fun aPageWhoseTitlesWentSaysSo() = runTest {
        val vm = PersonViewModel("tmdb:1", { provider })
        advanceUntilIdle()
        catalog.pages = emptyMap()
        vm.load()
        advanceUntilIdle()
        assertEquals(LoadState.Failed(UiError.PERSON_GONE), vm.state.value)
    }

    /** A failed read shows its error, kept with its button busy while it is read again. */
    @Test
    fun aFailureStaysShownWhileRetrying() = runTest {
        catalog.fail = { IOException("NAS away") }
        val vm = PersonViewModel("tmdb:1", { provider })
        advanceUntilIdle()
        val failed = vm.state.value as LoadState.Failed
        vm.load()
        assertEquals(failed.copy(retrying = true), vm.state.value)
        catalog.fail = { null }
        advanceUntilIdle()
        assertEquals(LoadState.Ready(page), vm.state.value)
    }

    @Test
    fun aFailedReloadKeepsThePage() = runTest {
        val vm = PersonViewModel("tmdb:1", { provider })
        advanceUntilIdle()
        catalog.fail = { IOException("NAS away") }
        vm.load()
        advanceUntilIdle()
        assertEquals(LoadState.Ready(page), vm.state.value)
    }

    @Test
    fun itsJobsBehindTheCameraFirst() {
        assertEquals(listOf(Job.DIRECTOR, Job.CAST), personJobs(page))
    }

    @Test
    fun underTwoLinesItIsASmallLibrary() {
        assertTrue(smallPage(14))
        assertFalse(smallPage(15))
    }

    /** A title marked from its poster shows so at once, in its place, the page filtering nothing; then it is saved and told. */
    @Test
    fun aTitleMarkedShowsAtOnceInItsPlaceThenIsSavedAndTold() = runTest {
        val events = FakeEvents()
        val vm = PersonViewModel("tmdb:1", { provider }, events = events, work = this)
        advanceUntilIdle()
        val samourai = titles(vm)[1]
        vm.mark(samourai)
        assertEquals(listOf(false, true, false), titles(vm).map { it.user.played })
        advanceUntilIdle()
        assertTrue("played ${samourai.id} true" in provider.calls)
        assertEquals(1L, events.libraryVersion.value)
    }

    /** A favourite not saved comes undone, and why is said for its title. */
    @Test
    fun aFavouriteNotSavedIsUndoneAndSaid() = runTest {
        provider.fail = { if (it.startsWith("favorite")) IOException("NAS away") else null }
        val vm = PersonViewModel("tmdb:1", { provider }, work = this)
        advanceUntilIdle()
        val samourai = titles(vm)[1]
        vm.favorite(samourai)
        advanceUntilIdle()
        assertEquals(samourai, titles(vm)[1])
        assertEquals(NotSaved(samourai.id, UiError.OFFLINE), vm.notSaved.value)
    }

    private fun titles(vm: PersonViewModel) = checkNotNull(vm.state.value.valueOrNull).titles.map { it.item }
}
