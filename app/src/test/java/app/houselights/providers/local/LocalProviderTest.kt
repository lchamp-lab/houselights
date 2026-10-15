package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.Identity
import app.houselights.core.Definition
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaItem
import app.houselights.core.MetadataEditor
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.Secrets
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** The local library's queries and rules, on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
class LocalProviderTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var provider: LocalProvider
    private var source = 0L
    private var collectionMin = 2

    private val films = Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null)
    private val collections = Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)

    @Before
    fun open() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor) { collectionMin }
        source = dao.insertSource(
            SourceEntity(
                name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas", share = "media",
                path = "films", username = "", password = Secret(""),
            )
        )
    }

    @After
    fun close() = db.close()

    private suspend fun movie(
        title: String,
        year: Int? = 2020,
        genres: List<String> = emptyList(),
        played: Boolean = false,
        collectionId: Long? = null,
        runtimeMin: Int? = 100,
        addedAt: Long = 0,
    ): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = title, sortTitle = title.lowercase(), year = year,
            genres = genres, played = played, collectionId = collectionId, runtimeMin = runtimeMin,
            filePath = "$title.mkv", addedAt = addedAt,
        )
    )

    private suspend fun page(filter: LibraryFilter = LibraryFilter(), start: Int = 0, limit: Int = 50) =
        provider.libraryPage(films, SortOption.TITLE, filter, start, limit)

    @Test
    fun pagesNeverRepeatNorSkipTitlesThatSortTheSame() = runTest {
        repeat(7) { movie("Même titre") }
        val seen = (0 until 7 step 3).flatMap { page(start = it, limit = 3).items.map(MediaItem::id) }
        assertEquals(7, seen.size)
        assertEquals(7, seen.toSet().size)
        assertEquals(7, page().total)
    }

    @Test
    fun onlyWhatCameAfterTheFirstScanIsAddedLater() = runTest {
        movie("Alien", addedAt = 1_000)
        // Before the first scan has ended, nothing is told apart.
        assertEquals(listOf(null), page().items.map { it.addedAt })

        dao.updateSource(dao.sources().single().copy(lastScanAt = 2_000, firstScanAt = 2_000))
        movie("Aliens", addedAt = 3_000)
        val added = page().items.associate { it.title to it.addedAt }
        assertEquals(null, added["Alien"])
        assertEquals(3_000L, added["Aliens"])
    }

    @Test
    fun aLibrarySummaryCountsItsTitlesAndItsFilmsInUltraHd() = runTest {
        movie("A", addedAt = 1)
        val uhd = movie("B", addedAt = 2)
        dao.update(dao.item(uhd)!!.copy(badges = listOf(Definition.ULTRA_HD.token)))
        val summary = provider.librarySummary(films)
        assertEquals(2, summary.total)
        assertEquals(1, summary.uhd)
        // Collections are counted, not their definition.
        assertEquals(null, provider.librarySummary(collections).uhd)
    }

    @Test
    fun genreMatchesAWholeEntry() = runTest {
        movie("Heat", genres = listOf("Action", "Drame"))
        movie("Ratatouille", genres = listOf("Animation"))
        assertEquals(listOf("Heat"), page(LibraryFilter(genre = "Action")).items.map { it.title })
        assertEquals(emptyList<String>(), page(LibraryFilter(genre = "Act")).items.map { it.title })
    }

    @Test
    fun decadeAndWatchStatus() = runTest {
        movie("Alien", year = 1979, played = true)
        movie("Aliens", year = 1986)
        movie("Prometheus", year = 2012)
        assertEquals(listOf("Aliens"), page(LibraryFilter(decade = 1980)).items.map { it.title })
        assertEquals(listOf("Aliens", "Prometheus"), page(LibraryFilter(status = WatchStatus.UNWATCHED)).items.map { it.title })
        assertEquals(listOf("Alien"), page(LibraryFilter(status = WatchStatus.WATCHED)).items.map { it.title })
    }

    @Test
    fun collectionsNeedEnoughFilms() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:1", name = "Alien", sortTitle = "alien"))
        val alien = movie("Alien", collectionId = saga)
        val alienItem = provider.item(alien.toString())

        assertEquals(0, provider.libraryPage(collections, SortOption.TITLE, LibraryFilter(), 0, 10).total)
        assertNull(provider.collectionOf(alienItem))

        movie("Aliens", collectionId = saga)
        val page = provider.libraryPage(collections, SortOption.TITLE, LibraryFilter(), 0, 10)
        assertEquals(listOf("Alien"), page.items.map { it.title })
        val collection = provider.collectionOf(alienItem)!!
        assertEquals(collection.id, provider.item(collection.id).id)
        assertEquals(listOf("Alien", "Aliens"), provider.collectionItems(collection).map { it.title })

        collectionMin = 0
        assertNull(provider.collectionOf(alienItem))
    }

    @Test
    fun nextEpisodeIsTheOneInProgressElseTheOneAfterTheLastWatched() = runTest {
        val series = dao.insert(ItemEntity(kind = ItemKind.SERIES, sourceId = source, title = "Show", sortTitle = "show", addedAt = 0))
        val season = dao.insert(
            ItemEntity(
                kind = ItemKind.SEASON, sourceId = source, parentId = series, seriesId = series, title = "S1", sortTitle = "001",
                seasonNumber = 1, addedAt = 0,
            )
        )
        suspend fun episode(n: Int, played: Boolean = false, positionMs: Long = 0) = dao.insert(
            ItemEntity(
                kind = ItemKind.EPISODE, sourceId = source, parentId = season, seriesId = series, title = "E$n",
                sortTitle = "001-%04d".format(n), seasonNumber = 1, episodeNumber = n, played = played, positionMs = positionMs,
                filePath = "Show/S01E0$n.mkv", addedAt = 0,
            )
        )
        episode(1, played = true)
        episode(2, played = true)
        episode(3)
        val e4 = episode(4)
        val show = provider.item(series.toString())
        assertEquals("E3", provider.nextUp(show)?.title)

        dao.savePlayback(e4, positionMs = 600_000, durationMs = null, played = false, lastPlayedAt = 1)
        assertEquals("E4", provider.nextUp(show)?.title)

        provider.setPlayed(show, played = true)
        assertTrue(dao.episodesOfSeries(series).all { it.played })
        assertNull(provider.nextUp(show))
    }

    @Test
    fun aFilmWatchedPastNinetyPercentIsPlayed() = runTest {
        val id = movie("Heat", runtimeMin = 100)
        val plan = provider.playback(provider.item(id.toString()), resume = false, PlaybackOptions(emptyList(), false))
        plan as PlaybackPlan.Launch
        assertEquals("nfs://nas/media/films/Heat.mkv", plan.uri)

        plan.reporter!!.stopped(positionMs = 30 * 60_000L, durationMs = 100 * 60_000L)
        dao.item(id)!!.let {
            assertFalse(it.played)
            assertEquals(30 * 60_000L, it.positionMs)
        }

        // The app may have been stopped meanwhile: the reporter comes back from the resume key.
        provider.reporter(plan.resumeKey!!)!!.stopped(positionMs = 95 * 60_000L, durationMs = 100 * 60_000L)
        dao.item(id)!!.let {
            assertTrue(it.played)
            assertEquals(0L, it.positionMs)
        }
    }

    @Test
    fun favouritesAndUnknownIds() = runTest {
        val id = movie("Heat")
        provider.setFavorite(provider.item(id.toString()), favorite = true)
        assertTrue(dao.item(id)!!.favorite)
        assertEquals(LocalId.Collection(12), LocalId.parse("c12"))
        assertEquals(LocalId.Item(12), LocalId.parse("12"))
        assertNull(LocalId.parse("tt0113277"))
    }

    internal object NoEditor : MetadataEditor {
        override fun canEdit(item: MediaItem) = false
        override suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices = error("unused")
        override suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption) = error("unused")
        override suspend fun identity(item: MediaItem): Identity = error("unused")
        override suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate> = error("unused")
        override suspend fun identify(item: MediaItem, candidate: MatchCandidate?) = error("unused")
    }
}
