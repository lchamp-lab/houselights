package app.houselights.providers.local

import app.houselights.core.ArtworkKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A saga's Automatique picture, alike on its cards (collectionMembers) and in its choices (collectionFilms). */
@RunWith(RobolectricTestRunner::class)
class SagaPicturesTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private var source = 0L

    @Before
    fun open() = runBlocking {
        db = testDatabase()
        dao = db.dao()
        source = dao.insertSource(nfsFolder())
    }

    @After
    fun close() = db.close()

    private suspend fun saga(poster: String? = null) =
        dao.insertCollection(CollectionEntity(matchKey = "name:alien", name = "Alien", sortTitle = "alien", posterPath = poster))

    private suspend fun film(saga: Long, title: String, year: Int?, poster: String, chosen: String? = null, backdrop: String? = null) =
        dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = source, title = title, sortTitle = title.lowercase(), year = year,
                collectionId = saga, filePath = "$poster.mkv", addedAt = 0, posterPath = poster, userPosterPath = chosen,
                backdropPath = backdrop,
            ),
        )

    /** Both readings of saga [id]'s Automatique of [kind]; they must agree. */
    private suspend fun automatic(id: Long, kind: ArtworkKind = ArtworkKind.POSTER): String? {
        val saga = dao.collection(id)!!
        val onCards = SagaPictures.automatic(saga, kind, dao.collectionMembers(listOf(id)).firstOrNull())
        val inChoices = SagaPictures.automatic(saga, kind, dao.collectionFilms(id).firstOrNull())
        assertEquals("cards and choices", onCards, inChoices)
        return onCards
    }

    /** Release order, undated films last, then sort title, then the oldest row: two films tied on both read alike. */
    @Test
    fun theFirstFilmIsTheSameInBothOrders() = runTest {
        val id = saga()
        film(id, "Undated", year = null, poster = "undated")
        film(id, "Aliens", year = 1986, poster = "aliens")
        film(id, "Alien", year = 1979, poster = "older")
        film(id, "Alien", year = 1979, poster = "newer")
        assertEquals(
            dao.collectionMembers(listOf(id)).map { it.posterPath },
            dao.collectionFilms(id).map { it.posterPath },
        )
        assertEquals(listOf("older", "newer", "aliens", "undated"), dao.collectionFilms(id).map { it.posterPath })
        assertEquals("older", automatic(id))
    }

    /** TMDB's picture of the saga first; else the first film's, its choice over its own. */
    @Test
    fun tmdbsPictureThenTheFirstFilmsAsItShowsIt() = runTest {
        val id = saga(poster = "saga")
        film(id, "Alien", year = 1979, poster = "alien", chosen = "chosen", backdrop = "stars")
        assertEquals("saga", automatic(id))
        assertEquals("the saga has no backdrop of its own", "stars", automatic(id, ArtworkKind.BACKDROP))
        val other = dao.insertCollection(CollectionEntity(matchKey = "name:dune", name = "Dune", sortTitle = "dune"))
        film(other, "Dune", year = 1984, poster = "dune", chosen = "chosen dune")
        assertEquals("chosen dune", automatic(other))
    }

    /** A first film whose picture was chosen as none shows none, and the saga none either: no later film stands in. */
    @Test
    fun anEmptyChoiceIsNone() = runTest {
        val id = saga()
        film(id, "Alien", year = 1979, poster = "alien", chosen = "")
        film(id, "Aliens", year = 1986, poster = "aliens")
        assertNull(automatic(id))
    }
}
