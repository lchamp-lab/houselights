package app.houselights.ui.person

import app.houselights.core.Job
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.core.PersonTitle
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
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

/** A person's page: read from the catalog, gone when none of their titles is left, kept when a reload fails. */
@OptIn(ExperimentalCoroutinesApi::class)
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
        val vm = PersonViewModel("tmdb:1") { provider }
        advanceUntilIdle()
        assertEquals(page, vm.state.value.page)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun noneOfTheirTitlesLeftSaysSo() = runTest {
        val vm = PersonViewModel("tmdb:2") { provider }
        advanceUntilIdle()
        assertEquals(UiError.PERSON_GONE, vm.state.value.error)
    }

    @Test
    fun aFailedReloadKeepsThePage() = runTest {
        val vm = PersonViewModel("tmdb:1") { provider }
        advanceUntilIdle()
        catalog.pages = emptyMap()
        provider.catalog = object : app.houselights.core.Catalog by catalog {
            override suspend fun person(id: String): PersonPage = throw java.io.IOException("NAS away")
        }
        vm.load()
        advanceUntilIdle()
        assertEquals(page, vm.state.value.page)
        assertNull(vm.state.value.error)
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
}
