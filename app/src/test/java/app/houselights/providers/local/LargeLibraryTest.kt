package app.houselights.providers.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Job
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Secrets
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/**
 * A library the size of a real one (1,000 films in 200 sagas, 100 shows of 30 episodes, with NFO text):
 * how many queries the home screen and a library page cost. The counts guard against a query per title.
 */
@RunWith(RobolectricTestRunner::class)
class LargeLibraryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var provider: LocalProvider
    private val queries = AtomicInteger()

    /**
     * Room's query executor, one thread. After each write, Room's invalidation tracker checks there what
     * it changed, a moment later (four statements): [measure] waits for it, so they are not the screen's.
     */
    private lateinit var roomWork: ExecutorService

    @Before
    fun fill() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        roomWork = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .setQueryCallback({ _, _ -> queries.incrementAndGet() }, Runnable::run)
            .setQueryExecutor(roomWork)
            .allowMainThreadQueries()
            .build()
        val dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, LocalProviderTest.NoEditor)
        val films = dao.insertSource(source("Films", SourceKind.MOVIES))
        val shows = dao.insertSource(source("Séries", SourceKind.SHOWS))
        val nfo = "<movie><plot>${"x".repeat(NFO_BYTES)}</plot></movie>"
        db.withTransaction {
            run {
                val sagas = (0 until SAGAS).map {
                    dao.insertCollection(CollectionEntity(matchKey = "name:saga $it", name = "Saga $it", sortTitle = "saga $it"))
                }
                repeat(FILMS) { i ->
                    val playedAt = if (i % 50 == 0) i.toLong() else null
                    val film = item(ItemKind.MOVIE, films, "Film $i", addedAt = i.toLong(), nfo = nfo, lastPlayedAt = playedAt)
                    val id = dao.insert(film.copy(collectionId = sagas[i % SAGAS], year = 1980 + i / SAGAS, tmdbId = 10_000 + i))
                    // A director of five films each, a cast of thirty out of two thousand actors.
                    val credits = listOf(credit(id, Job.DIRECTOR, 0, i % DIRECTORS)) +
                        (0 until CAST).map { credit(id, Job.CAST, it, DIRECTORS + (i * CAST + it * 7) % ACTORS) }
                    dao.insertCredits(credits)
                    dao.insertSearchWords(credits.flatMap { SearchWords.rows(SearchKind.PERSON, it.personKey, it.name) })
                }
                sagas.forEachIndexed { s, saga ->
                    val parts = (0 until FILMS / SAGAS + 2).map { n ->
                        SagaPartEntity(saga, tmdbId = 10_000 + s + n * SAGAS, ord = n, title = "Part $n", releaseDate = "${1980 + n}-01-01")
                    }
                    dao.insertSagaParts(parts)
                }
                repeat(SHOWS) { s ->
                    val series = dao.insert(item(ItemKind.SERIES, shows, "Show $s", addedAt = s.toLong()))
                    repeat(SEASONS) { n ->
                        val season = dao.insert(item(ItemKind.SEASON, shows, "", parentId = series, seriesId = series, season = n + 1))
                        repeat(EPISODES_PER_SEASON) { e ->
                            val played = s % 3 == 0 && n == 0
                            dao.insert(
                                item(
                                    ItemKind.EPISODE, shows, "Episode $e", parentId = season, seriesId = series, season = n + 1,
                                    episode = e + 1, nfo = nfo, played = played, lastPlayedAt = if (played) (s * 100L + e) else null,
                                )
                            )
                        }
                    }
                }
            }
        }
        queries.set(0)
    }

    @After
    fun close() {
        db.close()
        roomWork.shutdown()
    }

    private fun source(name: String, kind: SourceKind) = SourceEntity(
        name = name, kind = kind, protocol = Protocol.NFS, host = "nas", share = "media", path = name, username = "",
        password = Secret(""),
    )

    @Suppress("LongParameterList")
    private fun item(
        kind: ItemKind, source: Long, title: String, parentId: Long? = null, seriesId: Long? = null, season: Int? = null,
        episode: Int? = null, addedAt: Long = 0, nfo: String? = null, played: Boolean = false, lastPlayedAt: Long? = null,
    ) = ItemEntity(
        kind = kind, sourceId = source, title = title, sortTitle = title.lowercase(), parentId = parentId, seriesId = seriesId,
        seasonNumber = season, episodeNumber = episode, addedAt = addedAt, nfo = nfo, nfoPath = nfo?.let { "$title.nfo" },
        filePath = if (kind == ItemKind.MOVIE || kind == ItemKind.EPISODE) "$title.mkv" else null, played = played,
        lastPlayedAt = lastPlayedAt, genres = listOf("Drame"),
    )

    /** The credit of person [person] on the film [itemId]. */
    private fun credit(itemId: Long, job: Job, ord: Int, person: Int) =
        CreditEntity(itemId, job, ord, PersonKeys.tmdb(person), "Person $person", "person $person", null, "/p$person.jpg")

    private inline fun <T> measure(label: String, block: () -> T): Pair<T, Int> {
        // Room's tracker, still checking the last write, would be counted as the screen's.
        roomWork.submit {}.get()
        queries.set(0)
        val start = System.nanoTime()
        val result = block()
        println("$label: ${(System.nanoTime() - start) / 1_000_000} ms, ${queries.get()} queries")
        return result to queries.get()
    }

    @Test
    fun homeCostsAFewQueries() = runTest {
        val (sections, count) = measure("home") { provider.home() }
        assertTrue(sections.isNotEmpty())
        assertTrue("home ran $count queries", count <= HOME_QUERIES)
    }

    @Test
    fun aPageOfShowsCostsAFewQueries() = runTest {
        val shows = Library(LocalProvider.SHOWS, LibraryKind.SHOWS, null)
        val (page, count) = measure("shows page") { provider.libraryPage(shows, SortOption.TITLE, LibraryFilter(), 0, SHOWS) }
        assertEquals(SHOWS, page.items.size)
        assertEquals(EPISODES_PER_SEASON * SEASONS, page.items.first { it.title == "Show 1" }.user.unplayedCount)
        assertEquals(EPISODES_PER_SEASON * (SEASONS - 1), page.items.first { it.title == "Show 0" }.user.unplayedCount)
        assertEquals(SEASONS, page.items.first().childCount)
        assertTrue("shows page ran $count queries", count <= PAGE_QUERIES)
    }

    @Test
    fun aPageOfFilmsCostsAFewQueries() = runTest {
        val films = Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null)
        val (page, count) = measure("films page") { provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, PAGE) }
        assertEquals(PAGE, page.items.size)
        assertTrue(page.items.all { it.hasNfo })
        assertTrue("films page ran $count queries", count <= PAGE_QUERIES)
    }

    @Test
    fun aPageOfSagasCostsAFewQueries() = runTest {
        val sagas = Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)
        val (page, count) = measure("sagas page") { provider.libraryPage(sagas, SortOption.TITLE, LibraryFilter(), 0, PAGE) }
        assertEquals(PAGE, page.items.size)
        assertEquals(SAGAS, page.total)
        val first = page.items.first { it.title == "Saga 0" }
        assertEquals(FILMS / SAGAS to 1980, first.childCount to first.year)
        assertTrue("sagas page ran $count queries", count <= PAGE_QUERIES)
    }

    /** "À suivre": where every episode of the shows watched last stands, then the next of each. */
    @Test
    fun nextUpCostsAFewQueries() = runTest {
        val (sections, count) = measure("home") { provider.home() }
        val next = sections.single { it.type == SectionType.NEXT_UP }.items
        // Shows 0, 3, 6…: their first season watched, the next episode is the second season's first.
        assertEquals(NEXT_UP_SHOWS, next.size)
        assertTrue(next.all { it.seasonNumber == 2 && it.episodeNumber == 1 })
        assertTrue("home ran $count queries", count <= HOME_QUERIES)
    }

    @Test
    fun aTitlesCreditsCostAFewQueries() = runTest {
        val film = provider.libraryPage(Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null), SortOption.TITLE, LibraryFilter(), 0, 1)
            .items.single()
        val (credits, count) = measure("credits") { provider.catalog.credits(film) }
        assertEquals(CAST, credits.cast.size)
        assertEquals(FILMS / DIRECTORS, credits.crew.getValue(Job.DIRECTOR).single().titleCount)
        assertTrue("credits ran $count queries", count <= 2)
        val (_, page) = measure("title page") { provider.item(film.id) }
        assertTrue("a title's page ran $page queries", page <= PAGE_QUERIES)
    }

    @Test
    fun aPersonsPageCostsAFewQueries() = runTest {
        val actor = PersonKeys.tmdb(DIRECTORS + 7)
        val (page, count) = measure("person") { checkNotNull(provider.catalog.person(actor)) }
        assertTrue(page.titles.size > 1)
        assertTrue("a person's page ran $count queries", count <= PAGE_QUERIES)
        val (directors, top) = measure("directors") { provider.catalog.people(Job.DIRECTOR, limit = 10) }
        assertEquals(10, directors.size)
        assertTrue("home's directors ran $top queries", top <= 1)
    }

    @Test
    fun aSearchCostsAFewQueries() = runTest {
        val (found, count) = measure("search") { provider.catalog.search("film 1") }
        assertTrue(found.titles.isNotEmpty())
        assertTrue("a search ran $count queries", count <= SEARCH_QUERIES)
        val (people, again) = measure("search people") { provider.catalog.search("person 3") }
        assertTrue(people.people.isNotEmpty())
        assertTrue("a search ran $again queries", again <= SEARCH_QUERIES)
    }

    @Test
    fun theLibraryInNumbersAndASagaWholeCostAFewQueries() = runTest {
        val (numbers, count) = measure("numbers") { provider.catalog.numbers() }
        assertEquals(FILMS to SHOWS, numbers.films to numbers.shows)
        assertEquals(SHOWS * SEASONS * EPISODES_PER_SEASON, numbers.episodes)
        assertTrue("numbers ran $count queries", count <= 3)
        val sagas = Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)
        val saga = provider.libraryPage(sagas, SortOption.TITLE, LibraryFilter(), 0, 1).items.single()
        val (whole, parts) = measure("saga") { checkNotNull(provider.catalog.saga(saga)) }
        assertEquals(FILMS / SAGAS to FILMS / SAGAS + 2, whole.owned to whole.parts.size)
        assertTrue("a saga whole ran $parts queries", parts <= PAGE_QUERIES + 2)
    }

    private companion object {
        const val DIRECTORS = 200
        const val ACTORS = 2_000
        const val CAST = 30
        const val SEARCH_QUERIES = 8
        const val FILMS = 1_000
        const val SAGAS = 200
        const val NEXT_UP_SHOWS = 20
        const val SHOWS = 100
        const val SEASONS = 3
        const val EPISODES_PER_SEASON = 10
        const val NFO_BYTES = 20_000
        const val PAGE = 120
        const val HOME_QUERIES = 10
        const val PAGE_QUERIES = 5
    }
}
