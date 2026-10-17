package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.Identity
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MetadataEditor
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.Quality
import app.houselights.core.Secrets
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.providers.local.TmdbClient.Companion.ImageType
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
    fun genreMatchesAWholeEntry() = runTest {
        movie("Heat", genres = listOf("Action", "Drame"))
        movie("Ratatouille", genres = listOf("Animation"))
        assertEquals(listOf("Heat"), page(LibraryFilter(genre = "Action")).items.map { it.title })
        assertEquals(emptyList<String>(), page(LibraryFilter(genre = "Act")).items.map { it.title })
    }

    @Test
    fun similarTitlesShareAWholeGenreAsTheGridFilterMatchesIt() = runTest {
        val heat = movie("Heat", genres = listOf("Drame", "Action"))
        movie("Collateral", genres = listOf("Thriller", "Drame"))
        movie("Les Parapluies de Cherbourg", genres = listOf("Mélodrame"))
        movie("Ronin", genres = listOf("drame"))
        val similar = provider.similar(provider.item(heat.toString())).map { it.title }
        assertEquals(listOf("Collateral"), similar)
        assertEquals(listOf("Collateral", "Heat"), page(LibraryFilter(genre = "Drame")).items.map { it.title })
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

    private suspend fun show(title: String) =
        Show(dao.insert(ItemEntity(kind = ItemKind.SERIES, sourceId = source, title = title, sortTitle = title.lowercase(), addedAt = 0)))

    /** A show of the library; [episode] adds an episode to it, and its season when new. */
    private inner class Show(val id: Long) {
        private val seasons = mutableMapOf<Int, Long>()

        suspend fun episode(season: Int, n: Int, badges: List<String> = emptyList()): Long {
            val parent = seasons.getOrPut(season) {
                dao.insert(
                    ItemEntity(
                        kind = ItemKind.SEASON, sourceId = source, parentId = id, seriesId = id, title = "Season $season",
                        sortTitle = "%03d".format(season), seasonNumber = season, addedAt = 0,
                    )
                )
            }
            return dao.insert(
                ItemEntity(
                    kind = ItemKind.EPISODE, sourceId = source, parentId = parent, seriesId = id, title = "S${season}E$n",
                    sortTitle = "%03d-%04d".format(season, n), seasonNumber = season, episodeNumber = n,
                    filePath = "$id/S${season}E$n.mkv", badges = badges, addedAt = 0,
                )
            )
        }
    }

    /** Sonarr's "Specials" folder holds S00E01: "Play" starts S01E01 all the same, and the specials once the rest is watched. */
    @Test
    fun aShowStartsWithItsFirstSeasonNotItsSpecials() = runTest {
        val show = show("MobLand")
        show.episode(0, 1)
        val pilot = show.episode(1, 1)
        show.episode(1, 2)
        val item = provider.item(show.id.toString())
        assertEquals("S1E1", provider.nextUp(item)?.title)

        dao.setSeasonPlayed(checkNotNull(dao.item(pilot)?.parentId), played = true, now = 1)
        assertEquals("S0E1", provider.nextUp(item)?.title)
    }

    /** Shows carry no technical badges of their own: 4K, HDR and Dolby Vision are their episodes'. */
    @Test
    fun aShowIsFilteredByTheQualityOfItsEpisodes() = runTest {
        show("MobLand").episode(1, 1, badges = listOf("1080p", "HEVC"))
        show("Severance").run {
            episode(1, 1, badges = listOf("1080p", "HEVC"))
            episode(2, 1, badges = listOf("4K", "Dolby Vision", "HDR10", "Atmos"))
        }
        show("Dark").episode(1, 1, badges = listOf("4K", "HDR10"))
        val shows = Library(LocalProvider.SHOWS, LibraryKind.SHOWS, null)
        suspend fun titles(quality: Quality) =
            provider.libraryPage(shows, SortOption.TITLE, LibraryFilter(quality = quality), 0, 50).items.map { it.title }

        assertEquals(listOf("Dark", "Severance"), titles(Quality.UHD_4K))
        assertEquals(listOf("Dark", "Severance"), titles(Quality.HDR))
        assertEquals(listOf("Severance"), titles(Quality.DOLBY_VISION))
        assertEquals(1, provider.libraryPage(shows, SortOption.TITLE, LibraryFilter(quality = Quality.DOLBY_VISION), 0, 50).total)
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

    private suspend fun godfather(positionMs: Long = 0): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = "Le Parrain", sortTitle = "parrain", year = 1972, runtimeMin = 175,
            filePath = "Le Parrain/Le Parrain CD1.avi", parts = listOf("Le Parrain/Le Parrain CD2.avi"), addedAt = 0,
            positionMs = positionMs, durationMs = 88 * 60_000L,
        )
    )

    @Test
    fun aFilmInPartsOffersEachPartToPlay() = runTest {
        val id = godfather(positionMs = 40 * 60_000L)
        val film = provider.item(id.toString())
        assertEquals(listOf("${id}p1", "${id}p2"), film.parts.map { it.id })
        assertEquals(listOf("Le Parrain CD1.avi", "Le Parrain CD2.avi"), film.parts.map { it.fileName })
        // It lasts as long as TMDB says, not as its first file.
        assertEquals(175 * 60_000L, film.runtimeMs)

        val options = PlaybackOptions(emptyList(), false)
        // Lire resumes the first part, where the resume point lies.
        val resumed = provider.playback(film, resume = true, options) as PlaybackPlan.Launch
        assertEquals("nfs://nas/media/films/Le Parrain/Le Parrain CD1.avi", resumed.uri)
        assertEquals(40 * 60_000L, resumed.startMs)
        assertEquals("Le Parrain · Part 1 of 2", resumed.title)

        val second = provider.playback(film.parts[1], resume = true, options) as PlaybackPlan.Launch
        assertEquals("nfs://nas/media/films/Le Parrain/Le Parrain CD2.avi", second.uri)
        assertEquals("from its start", 0L, second.startMs)
        assertEquals("Le Parrain · Part 2 of 2", second.title)
        assertEquals(LocalId.Item(id, part = 2), LocalId.parse(second.resumeKey!!))
    }

    @Test
    fun aFilmInPartsIsWatchedAtTheEndOfItsLastPart() = runTest {
        val id = godfather()
        val film = provider.item(id.toString())
        val options = PlaybackOptions(emptyList(), false)
        val first = provider.playback(film.parts[0], resume = false, options) as PlaybackPlan.Launch
        first.reporter!!.stopped(positionMs = 87 * 60_000L, durationMs = 88 * 60_000L)
        dao.item(id)!!.let {
            assertFalse("the end of its first part is not its end", it.played)
            assertEquals(87 * 60_000L, it.positionMs)
        }

        // A later part keeps the first's resume point; the app stopped meanwhile, its reporter comes back.
        val second = provider.playback(film.parts[1], resume = false, options) as PlaybackPlan.Launch
        second.reporter!!.progress(positionMs = 30 * 60_000L, durationMs = 87 * 60_000L, paused = false)
        assertEquals(87 * 60_000L, dao.item(id)!!.positionMs)
        provider.reporter(second.resumeKey!!)!!.stopped(positionMs = 86 * 60_000L, durationMs = 87 * 60_000L)
        dao.item(id)!!.let {
            assertTrue(it.played)
            assertEquals(0L, it.positionMs)
        }
    }

    /** Lire, then the player goes on by itself from the first file to the second: the film's next part, then its end. */
    @Test
    fun aFilmPlayedFromPartToPartIsWatchedAtTheEndOfTheLast() = runTest {
        val id = godfather()
        val film = provider.item(id.toString())
        val first = (provider.playback(film, resume = true, PlaybackOptions(emptyList(), false)) as PlaybackPlan.Launch).reporter!!
        first.progress(positionMs = 87 * 60_000L, durationMs = 88 * 60_000L, paused = false)

        val next = checkNotNull(first.next())
        assertEquals("Le Parrain/Le Parrain CD2.avi", next.path)
        assertEquals(LocalId.Item(id, part = 2), LocalId.parse(next.resumeKey!!))
        assertNull("the last part has none after it", next.reporter.next())
        next.reporter.progress(positionMs = 40 * 60_000L, durationMs = 87 * 60_000L, paused = false)
        assertFalse(dao.item(id)!!.played)
        next.reporter.stopped(positionMs = 86 * 60_000L, durationMs = 87 * 60_000L)
        dao.item(id)!!.let {
            assertTrue(it.played)
            assertEquals(0L, it.positionMs)
        }
        // A film of one file has no next part.
        assertNull(LocalReporter(dao, movie("Heat")).next())
    }

    /**
     * The player is another app: a fanart or a clearlogo kept by the app is out of its reach. It gets
     * the web address that stands for it (the automatic choice, else the NFO's), never nothing.
     */
    @Test
    fun thePlayerGetsWebArtworkForWhatTheAppKeepsItself() = runTest {
        val nfo = """<movie><title>Heat</title><fanart><thumb>http://assets.fanart.tv/heat.jpg</thumb></fanart></movie>"""
        val id = dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = source, title = "Heat", sortTitle = "heat", filePath = "Heat.mkv", addedAt = 0,
                // A sidecar fanart, kept as a file of the app's; the user chose a local logo over TMDB's.
                backdropPath = "file:///data/artwork/heat-fanart.jpg",
                logoPath = "/tmdbLogo.png",
                userLogoPath = "file:///data/artwork/heat-logo.png",
                nfoPath = "Heat.nfo",
                nfo = nfo,
            )
        )
        val item = provider.item(id.toString())
        // Screens still show the files the app keeps.
        assertEquals("file:///data/artwork/heat-fanart.jpg", item.backdrop?.url(1920))

        val plan = provider.playback(item, resume = false, PlaybackOptions(emptyList(), false)) as PlaybackPlan.Launch
        assertEquals("https://assets.fanart.tv/heat.jpg", plan.backdropUrl)
        val logo = plan.logoUrl.orEmpty()
        assertTrue("Not TMDB's logo: $logo", logo.startsWith("https://image.tmdb.org/") && logo.endsWith("/tmdbLogo.png"))
    }

    /**
     * An episode whose backdrop is a file the app keeps, with no web address of its own: the player
     * gets its show's from TMDB, never nothing. A logo the show chose none of stays none.
     */
    @Test
    fun anEpisodeGetsItsShowsWebArtworkWhenItsOwnIsOutOfReach() = runTest {
        val series = dao.insert(
            ItemEntity(
                kind = ItemKind.SERIES, sourceId = source, title = "Show", sortTitle = "show", addedAt = 0,
                backdropPath = "/showBackdrop.jpg", logoPath = "/showLogo.png", userLogoPath = "",
            )
        )
        val episode = dao.insert(
            ItemEntity(
                kind = ItemKind.EPISODE, sourceId = source, seriesId = series, title = "E1", sortTitle = "001-0001",
                seasonNumber = 1, episodeNumber = 1, filePath = "Show/S01E01.mkv", addedAt = 0,
                backdropPath = "file:///data/artwork/s01e01-fanart.jpg",
            )
        )
        val item = provider.item(episode.toString())
        assertEquals("file:///data/artwork/s01e01-fanart.jpg", item.backdrop?.url(1920))

        val plan = provider.playback(item, resume = false, PlaybackOptions(emptyList(), false)) as PlaybackPlan.Launch
        val backdrop = plan.backdropUrl.orEmpty()
        assertTrue("Not the show's: $backdrop", backdrop.startsWith("https://image.tmdb.org/") && backdrop.endsWith("/showBackdrop.jpg"))
        assertNull(plan.logoUrl)
    }

    /** A disc's image may open on a menu or an intro, a film's file never does: the player is followed accordingly. */
    @Test
    fun aDiscImageIsPlayedAsADisc() = runTest {
        val options = PlaybackOptions(emptyList(), false)
        val iso = dao.insert(
            ItemEntity(kind = ItemKind.MOVIE, sourceId = source, title = "Heat", sortTitle = "heat", filePath = "Heat.ISO", addedAt = 0)
        )
        assertTrue((provider.playback(provider.item(iso.toString()), resume = false, options) as PlaybackPlan.Launch).disc)
        val mkv = movie("Ronin")
        assertFalse((provider.playback(provider.item(mkv.toString()), resume = false, options) as PlaybackPlan.Launch).disc)
    }

    /** The user chose no logo (the title set in type): the player gets none either. */
    @Test
    fun noLogoChosenIsNoLogoForThePlayer() = runTest {
        val id = dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = source, title = "Heat", sortTitle = "heat", filePath = "Heat.mkv", addedAt = 0,
                logoPath = "/tmdbLogo.png", userLogoPath = "",
            )
        )
        val plan = provider.playback(provider.item(id.toString()), resume = false, PlaybackOptions(emptyList(), false))
        assertNull((plan as PlaybackPlan.Launch).logoUrl)
    }

    /** A card lasts as long as its file, read from its column: only a title's page decodes what its file holds. */
    @Test
    fun aCardLastsAsLongAsItsFile() = runTest {
        val read = MediaInfo(container = "MKV", durationMs = 9_300_000, probedWith = MediaProbe.VERSION)
        dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = source, title = "Dune", sortTitle = "dune", runtimeMin = 155, filePath = "Dune.mkv",
                addedAt = 0,
            ).withMedia(read)
        )
        val card = page().items.single()
        assertEquals(9_300_000L, card.runtimeMs)
        assertNull(card.media)
        assertEquals(read, provider.item(card.id).media)
    }

    /** An NFO's address on TMDB, written at the original size by Kodi or Radarr, is asked for at the size shown. */
    @Test
    fun nfoImagesOnTmdbAreAskedForAtTheSizeShown() {
        val original = "https://image.tmdb.org/t/p/original/poster.jpg"
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", LocalProvider.image(original, ImageType.POSTER).url(300))
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/fanart.jpg",
            LocalProvider.image("http://image.tmdb.org/t/p/original/fanart.jpg", ImageType.BACKDROP).url(1280),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w185/actor.jpg",
            LocalProvider.image("https://image.tmdb.org/t/p/w500/actor.jpg", ImageType.PROFILE).url(120),
        )
        // The picture TMDB's own path names: one key, one copy in the image cache.
        assertEquals(LocalProvider.image("/poster.jpg", ImageType.POSTER), LocalProvider.image(original, ImageType.POSTER))
        // Another server's address stays as it is, over HTTPS.
        val fanart = LocalProvider.image("http://assets.fanart.tv/heat.jpg", ImageType.BACKDROP)
        assertEquals("https://assets.fanart.tv/heat.jpg", fanart.url(1920))
    }

    @Test
    fun favouritesAndUnknownIds() = runTest {
        val id = movie("Heat")
        provider.setFavorite(provider.item(id.toString()), favorite = true)
        assertTrue(dao.item(id)!!.favorite)
        assertEquals(LocalId.Collection(12), LocalId.parse("c12"))
        assertEquals(LocalId.Item(12), LocalId.parse("12"))
        assertEquals(LocalId.Item(12, part = 2), LocalId.parse(LocalId.ofPart(12, 2)))
        assertNull(LocalId.parse("tt0113277"))
        assertNull(LocalId.parse("12p0"))
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
