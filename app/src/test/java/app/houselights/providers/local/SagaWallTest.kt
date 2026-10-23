package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.CollectionThreshold
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Page
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The films wall with each saga's films under its poster, and the sagas alone, filtered through their films. */
@RunWith(RobolectricTestRunner::class)
class SagaWallTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var provider: LocalProvider
    private var source = 0L
    private var collectionMin = CollectionThreshold.TWO_FILMS

    private val films = Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null)
    private val sagas = Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)

    @Before
    fun open() = runBlocking {
        db = testDatabase { allowMainThreadQueries() }
        dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor) { collectionMin }
        source = dao.insertSource(nfsFolder())
    }

    @After
    fun close() = db.close()

    private suspend fun saga(name: String): Long =
        dao.insertCollection(CollectionEntity(matchKey = "name:${name.lowercase()}", name = name, sortTitle = name.lowercase()))

    /** A film in the library, in [saga] if given; [more] sets what else a test needs of its row. */
    private suspend fun movie(
        title: String,
        saga: Long? = null,
        year: Int? = 2020,
        rating: Double? = null,
        addedAt: Long = 0,
        played: Boolean = false,
        genres: List<String> = emptyList(),
        badges: List<String> = emptyList(),
        more: ItemEntity.() -> ItemEntity = { this },
    ): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = title, sortTitle = title.lowercase(), year = year, rating = rating,
            addedAt = addedAt, played = played, genres = genres, badges = badges, collectionId = saga, filePath = "$title.mkv",
        ).more()
    )

    private suspend fun grouped(
        sort: SortOption = SortOption.TITLE,
        filter: LibraryFilter = LibraryFilter(),
        start: Int = 0,
        limit: Int = 50,
    ) = provider.libraryPage(films, sort, filter, start, limit, grouped = true)

    private suspend fun sagasAlone(sort: SortOption = SortOption.TITLE, filter: LibraryFilter = LibraryFilter()) =
        provider.libraryPage(sagas, sort, filter, 0, 50)

    /** Titles as the wall shows them, a saga's marked as one. */
    private fun Page.shown() = items.map { if (it.kind == MediaKind.COLLECTION) "saga ${it.title}" else it.title }

    @Test
    fun aSagasFilmsArePutUnderItsPosterAtItsPlaceAndCounted() = runTest {
        val alien = saga("Alien")
        movie("Alien", alien)
        movie("Aliens", alien)
        movie("Heat")
        movie("Blade Runner")
        val page = grouped()
        assertEquals(listOf("saga Alien", "Blade Runner", "Heat"), page.shown())
        assertEquals(2, page.items.first().childCount)
        assertEquals(3 to 1, page.total to page.sagas)
    }

    /** Below the setting's threshold a saga keeps its films; every saga, from one film; hidden, none. */
    @Test
    fun theSettingSaysFromHowManyFilmsASagaGathersThem() = runTest {
        val alien = saga("Alien")
        movie("Alien", alien)
        movie("Aliens", alien)
        movie("Mad Max", saga("Mad Max"))
        assertEquals(listOf("saga Alien", "Mad Max"), grouped().shown())

        collectionMin = CollectionThreshold.ANY
        assertEquals(listOf("saga Alien", "saga Mad Max"), grouped().shown())
        assertEquals(2, grouped().sagas)

        collectionMin = CollectionThreshold.HIDDEN
        val hidden = grouped()
        assertEquals(listOf("Alien", "Aliens", "Mad Max"), hidden.shown())
        assertNull("nothing grouped, no sagas to count", hidden.sagas)
    }

    /** A second copy of a film is the same film: it neither makes a saga nor shows on its own. */
    @Test
    fun aCopyIsNeitherCountedNorShown() = runTest {
        val dune = saga("Dune")
        val first = movie("Dune", dune)
        movie("Dune", dune) { copy(versionOf = first, filePath = "Dune 2160p.mkv") }
        movie("Heat")
        val page = grouped()
        assertEquals(listOf("Dune", "Heat"), page.shown())
        assertEquals(2 to 0, page.total to page.sagas)
    }

    @Test
    fun filtersShowASagaWhenOneOfItsFilmsPasses() = runTest {
        val alien = saga("Alien")
        movie("Alien", alien, played = true, genres = listOf("Horreur"), badges = listOf("4K"))
        movie("Aliens", alien, genres = listOf("Action"))
        val toys = saga("Toy Story")
        movie("Toy Story", toys, played = true, genres = listOf("Animation"))
        movie("Toy Story 2", toys, played = true, genres = listOf("Animation"))
        movie("Heat", genres = listOf("Action"))
        movie("Ronin", played = true)

        assertEquals(listOf("saga Alien", "Heat"), grouped(filter = LibraryFilter(status = WatchStatus.UNWATCHED)).shown())
        assertEquals(listOf("saga Alien", "Ronin", "saga Toy Story"), grouped(filter = LibraryFilter(status = WatchStatus.WATCHED)).shown())
        assertEquals(listOf("saga Alien"), grouped(filter = LibraryFilter(genre = "Horreur")).shown())
        // A saga's films failing the filter never show one by one: the saga stands for them.
        assertEquals(listOf("saga Alien", "Heat"), grouped(filter = LibraryFilter(genre = "Action")).shown())
        val ultraHd = grouped(filter = LibraryFilter(quality = Quality.UHD_4K))
        assertEquals(listOf("saga Alien"), ultraHd.shown())
        assertEquals(1 to 1, ultraHd.total to ultraHd.sagas)
    }

    /** Added: its latest film; year: its latest film; rating: its films' mean; title: its own name. */
    @Test
    fun eachSortRanksASagaByItsFilms() = runTest {
        val alien = saga("Alien")
        movie("Alien", alien, year = 1979, rating = 8.0, addedAt = 10)
        movie("Aliens", alien, year = 1986, rating = 6.0, addedAt = 50)
        movie("Heat", year = 1995, rating = 8.0, addedAt = 30)
        movie("Ronin", year = 1998, rating = 6.5, addedAt = 60)
        movie("Blow Out", year = 1981, rating = 7.5, addedAt = 20)
        assertEquals(listOf("saga Alien", "Blow Out", "Heat", "Ronin"), grouped(SortOption.TITLE).shown())
        assertEquals(listOf("Ronin", "saga Alien", "Heat", "Blow Out"), grouped(SortOption.ADDED).shown())
        assertEquals(listOf("Ronin", "Heat", "saga Alien", "Blow Out"), grouped(SortOption.YEAR).shown())
        assertEquals(listOf("Heat", "Blow Out", "saga Alien", "Ronin"), grouped(SortOption.RATING).shown())
    }

    /** Films and sagas tied on every key, a film's id and a saga's alike: pages neither overlap nor skip one. */
    @Test
    fun pagesNeverRepeatNorSkipFilmsAndSagasThatSortTheSame() = runTest {
        repeat(3) {
            val same = dao.insertCollection(CollectionEntity(matchKey = "name:même $it", name = "Même", sortTitle = "même"))
            repeat(2) { movie("Même", same) }
        }
        repeat(5) { movie("Même") }
        for (sort in SortOption.entries) {
            val seen = (0 until 8 step 3).flatMap { grouped(sort, start = it, limit = 3).items.map(MediaItem::id) }
            assertEquals("$sort", 8, seen.toSet().size)
            assertEquals("$sort", 8, seen.size)
        }
        assertEquals(8 to 3, grouped().let { it.total to it.sagas })
    }

    @Test
    fun theSagasAloneAreFilteredThroughTheirFilms() = runTest {
        val alien = saga("Alien")
        movie("Alien", alien, played = true, year = 1979)
        movie("Aliens", alien, year = 1986)
        val toys = saga("Toy Story")
        movie("Toy Story", toys, played = true, year = 1995)
        movie("Toy Story 2", toys, played = true, year = 1999)
        movie("Heat")
        assertEquals(listOf("saga Toy Story", "saga Alien"), sagasAlone(SortOption.YEAR).shown())
        val unwatched = sagasAlone(filter = LibraryFilter(status = WatchStatus.UNWATCHED))
        assertEquals(listOf("saga Alien"), unwatched.shown())
        assertEquals(1, unwatched.total)
        assertEquals(0, sagasAlone(filter = LibraryFilter(decade = 2010)).total)
    }

    @Test
    fun theSagasOfferTheirFilmsFilters() = runTest {
        val alien = saga("Alien")
        movie("Alien", alien, year = 1979, genres = listOf("Horreur"), badges = listOf("4K"))
        movie("Aliens", alien, year = 1986, genres = listOf("Action"))
        movie("Heat", year = 1995, genres = listOf("Policier"))
        val offered = provider.libraryFacets(sagas)
        assertEquals(provider.libraryFacets(films), offered)
        assertEquals(listOf(1990, 1980, 1970), offered.decades)
    }

    /** A wall opened on part of the library, or asked without its sagas, shows every film. */
    @Test
    fun ungroupedEveryFilmShowsOnItsOwn() = runTest {
        val alien = saga("Alien")
        movie("Alien", alien)
        movie("Aliens", alien)
        val page = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 50)
        assertEquals(listOf("Alien", "Aliens"), page.shown())
        assertNull(page.sagas)
    }
}
