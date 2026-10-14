package app.houselights.providers.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Secrets
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
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/**
 * A library the size of a real one (1,000 films, 100 shows of 30 episodes, with NFO text): how many
 * queries the home screen and a library page cost. The counts guard against a query per title.
 */
@RunWith(RobolectricTestRunner::class)
class LargeLibraryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var provider: LocalProvider
    private val queries = AtomicInteger()

    @Before
    fun fill() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .setQueryCallback({ _, _ -> queries.incrementAndGet() }, Runnable::run)
            .allowMainThreadQueries()
            .build()
        val dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, LocalProviderTest.NoEditor)
        val films = dao.insertSource(source("Films", SourceKind.MOVIES))
        val shows = dao.insertSource(source("Séries", SourceKind.SHOWS))
        val nfo = "<movie><plot>${"x".repeat(NFO_BYTES)}</plot></movie>"
        db.withTransaction {
            run {
                repeat(FILMS) { i ->
                    val playedAt = if (i % 50 == 0) i.toLong() else null
                    dao.insert(item(ItemKind.MOVIE, films, "Film $i", addedAt = i.toLong(), nfo = nfo, lastPlayedAt = playedAt))
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
    fun close() = db.close()

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

    private inline fun <T> measure(label: String, block: () -> T): Pair<T, Int> {
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

    private companion object {
        const val FILMS = 1_000
        const val SHOWS = 100
        const val SEASONS = 3
        const val EPISODES_PER_SEASON = 10
        const val NFO_BYTES = 20_000
        const val PAGE = 120
        const val HOME_QUERIES = 30
        const val PAGE_QUERIES = 5
    }
}
