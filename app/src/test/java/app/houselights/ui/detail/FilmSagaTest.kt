package app.houselights.ui.detail

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A film's page and its saga: whole when the source knows it, else the films held, never asked twice. */
@RunWith(RobolectricTestRunner::class)
class FilmSagaTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val heat = film("m1", "Heat")
    private val saga = MediaItem("saga", MediaKind.COLLECTION, "Heat")
    private val catalog = FakeCatalog()
    private val provider = FakeProvider().apply {
        items[heat.id] = heat
        catalog = this@FilmSagaTest.catalog
    }

    /** [provider] whose films are all of [saga]: the one it holds and another. */
    private val source = object : MediaProvider by provider {
        override suspend fun collectionOf(item: MediaItem) = saga

        override suspend fun collectionItems(collection: MediaItem) = listOf(heat, film("m2"))
    }

    /** The films the library lacks in their place, when the source knows them. */
    @Test
    fun aFilmsSagaIsWhole() = runTest {
        val whole = Saga(saga, listOf(SagaPart(heat, "Heat", 1995), SagaPart(null, "Heat 2", 2027, released = false)))
        catalog.sagas = mapOf(saga.id to whole)
        provider.capabilities = provider.capabilities.copy(sagaParts = true)
        val vm = DetailViewModel(heat.id, { source }, FakeEvents(), this)
        advanceUntilIdle()
        assertEquals(whole, vm.state.value.saga)
        assertEquals(listOf("saga ${saga.id}"), catalog.calls.filter { it.startsWith("saga") })
    }

    /** A source that lists a saga's films held alone is not asked for them twice: the row holds those films. */
    @Test
    fun aSourceThatKnowsOnlyTheFilmsHeldIsAskedOnce() = runTest {
        catalog.sagas = mapOf(saga.id to Saga(saga, emptyList()))
        val vm = DetailViewModel(heat.id, { source }, FakeEvents(), this)
        advanceUntilIdle()
        assertNull(vm.state.value.saga)
        assertEquals(emptyList<String>(), catalog.calls.filter { it.startsWith("saga") })
        assertEquals(listOf("m1", "m2"), vm.state.value.collectionFilms.map { it.id })
    }

    /**
     * A saga's page shows neither titles like it nor people, and asks for neither, a server's two requests spared; a
     * film's page still asks for both.
     */
    @Test
    fun aSagaAsksForNoSimilarTitlesNorPeople() = runTest {
        provider.items[saga.id] = saga
        provider.capabilities = provider.capabilities.copy(similar = true)
        val asked = mutableListOf<String>()
        val noting = object : MediaProvider by source {
            override suspend fun similar(item: MediaItem): List<MediaItem> = emptyList<MediaItem>().also { asked += item.id }
        }
        val page = DetailViewModel(saga.id, { noting }, FakeEvents(), this)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), page.state.value.collectionFilms.map { it.id })
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<String>(), catalog.calls.filter { it.startsWith("credits") })

        DetailViewModel(heat.id, { noting }, FakeEvents(), this)
        advanceUntilIdle()
        assertEquals(listOf(heat.id), asked)
        assertEquals(listOf("credits ${heat.id}"), catalog.calls.filter { it.startsWith("credits") })
    }
}
