package app.houselights.providers.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.Quality
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.UserState
import app.houselights.core.WatchStatus
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        db = testDatabase { allowMainThreadQueries() }
        dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor) { collectionMin }
        source = dao.insertSource(nfsFolder())
    }

    @After
    fun close() = db.close()

    /** A film in the library; [more] sets what else a test needs of its row. */
    private suspend fun movie(
        title: String,
        year: Int? = 2020,
        genres: List<String> = emptyList(),
        played: Boolean = false,
        collectionId: Long? = null,
        runtimeMin: Int? = 100,
        addedAt: Long = 0,
        path: String = "$title.mkv",
        badges: List<String> = emptyList(),
        more: ItemEntity.() -> ItemEntity = { this },
    ): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = title, sortTitle = title.lowercase(), year = year,
            genres = genres, played = played, collectionId = collectionId, runtimeMin = runtimeMin,
            filePath = path, badges = badges, addedAt = addedAt,
        ).more()
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
        // Until the first scan has ended, nothing counts as added.
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
        assertEquals(listOf("Collateral", "Ronin"), similar)
        assertEquals(listOf("Collateral", "Heat", "Ronin"), page(LibraryFilter(genre = "Drame")).items.map { it.title })
    }

    /** NFOs and TMDB spell a genre differently: the menu offers it once, and its filter keeps every spelling. */
    @Test
    fun aGenreIsOneWhateverItsCase() = runTest {
        movie("Heat", genres = listOf("Drame", "Action"))
        movie("Collateral", genres = listOf("Thriller", "Drame"))
        movie("Ronin", genres = listOf("drame", "action", "thriller"))
        movie("Le Samouraï", genres = listOf("thriller"))
        movie("Les Parapluies de Cherbourg", genres = listOf("Mélodrame"))
        movie("Alien", genres = listOf("Épouvante"))
        movie("The Thing", genres = listOf("épouvante"))
        val genres = provider.libraryFacets(films).genres
        // The spelling most titles use; on a tie, the word's own; in the order a reader looks for them, case and accents aside.
        assertEquals(listOf("Action", "Drame"), genres.take(2))
        assertEquals(listOf("Mélodrame", "thriller"), genres.takeLast(2))
        // Past ASCII, the menu joins spellings exactly as SQLite's lower() does for the filter, whichever way it reads "É".
        val horror = genres.drop(2).dropLast(2).flatMap { genre -> page(LibraryFilter(genre = genre)).items.map { it.title } }
        assertEquals(listOf("Alien", "The Thing"), horror)
        assertEquals(listOf("Collateral", "Heat", "Ronin"), page(LibraryFilter(genre = "Drame")).items.map { it.title })
        assertEquals(3, page(LibraryFilter(genre = "drame")).total)
        assertEquals(listOf("Collateral", "Le Samouraï", "Ronin"), page(LibraryFilter(genre = "thriller")).items.map { it.title })
    }

    @Test
    fun filmsAreFilteredByTheirDecadeAndWhetherTheyWereWatched() = runTest {
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
        val show = show("Show")
        show.episode(1, 1, played = true)
        show.episode(1, 2, played = true)
        show.episode(1, 3)
        val e4 = show.episode(1, 4)
        val item = provider.item(show.id.toString())
        assertEquals("S1E3", provider.nextUp(item)?.title)

        dao.savePlayback(e4, positionMs = 600_000, durationMs = null, played = false, lastPlayedAt = 1)
        assertEquals("S1E4", provider.nextUp(item)?.title)

        provider.setPlayed(item, played = true)
        assertTrue(dao.episodesOfSeries(show.id).all { it.played })
        assertNull(provider.nextUp(item))
    }

    private suspend fun show(title: String) =
        Show(dao.insert(ItemEntity(kind = ItemKind.SERIES, sourceId = source, title = title, sortTitle = title.lowercase(), addedAt = 0)))

    /** A show in the library; [episode] adds an episode to it, and its season if new. */
    private inner class Show(val id: Long) {
        private val seasons = mutableMapOf<Int, Long>()

        suspend fun episode(season: Int, n: Int, badges: List<String> = emptyList(), played: Boolean = false): Long {
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
                    filePath = "$id/S${season}E$n.mkv", badges = badges, played = played, addedAt = 0,
                )
            )
        }
    }

    /** Each season of a show reports its episodes and those not yet watched, in one query. */
    @Test
    fun seasonsSayHowFarTheyAreWatched() = runTest {
        val show = show("MobLand")
        val watched = (1..3).map { show.episode(1, it) } + show.episode(2, 1)
        show.episode(2, 2)
        show.episode(2, 3)
        show.episode(3, 1)
        watched.forEach { dao.savePlayback(it, positionMs = 0, durationMs = null, played = true, lastPlayedAt = 1) }
        val seasons = provider.seasons(provider.item(show.id.toString()))
        assertEquals(listOf(3, 3, 1), seasons.map { it.childCount })
        assertEquals(listOf(0, 2, 1), seasons.map { it.user.unplayedCount })
        assertEquals(listOf(true, false, false), seasons.map { it.user.played })
    }

    /** A season row whose episodes are all gone holds none, and is not watched, whatever its row says. */
    @Test
    fun aSeasonWithoutEpisodesIsNotWatched() = runTest {
        val show = show("MobLand")
        show.episode(1, 1, played = true)
        dao.insert(
            ItemEntity(
                kind = ItemKind.SEASON, sourceId = source, parentId = show.id, seriesId = show.id, title = "Season 2",
                sortTitle = "002", seasonNumber = 2, played = true, addedAt = 0,
            )
        )
        val seasons = provider.seasons(provider.item(show.id.toString()))
        assertEquals(listOf(1, 0), seasons.map { it.childCount })
        assertEquals(listOf(true, false), seasons.map { it.user.played })
    }

    /** A season is marked whole in one statement, its episodes back to their start; the other seasons stay as they are. */
    @Test
    fun aSeasonIsMarkedWithItsEpisodesAlone() = runTest {
        val show = show("MobLand")
        val pilot = show.episode(1, 1)
        show.episode(1, 2)
        show.episode(2, 1)
        dao.savePlayback(pilot, positionMs = 600_000, durationMs = null, played = false, lastPlayedAt = 1)
        val item = provider.item(show.id.toString())
        val first = provider.seasons(item).first()

        provider.setPlayed(first, played = true)
        assertEquals(listOf(true, false), provider.seasons(item).map { it.user.played })
        assertEquals(listOf(0, 1), provider.seasons(item).map { it.user.unplayedCount })
        assertEquals(listOf(0L, 0L), provider.episodes(item, first).map { it.user.positionMs })
        assertEquals(1, provider.item(item.id).user.unplayedCount)

        provider.setPlayed(first, played = false)
        assertEquals(listOf(false, false), provider.episodes(item, first).map { it.user.played })
        assertEquals(listOf(2, 1), provider.seasons(item).map { it.user.unplayedCount })
    }

    /** An episode marked unwatched starts over, as a film does. */
    @Test
    fun anEpisodeMarkedUnwatchedStartsOver() = runTest {
        val show = show("MobLand")
        val pilot = show.episode(1, 1)
        dao.savePlayback(pilot, positionMs = 600_000, durationMs = null, played = true, lastPlayedAt = 1)
        val episode = provider.item(pilot.toString())
        provider.setPlayed(episode, played = false)
        assertEquals(UserState(), provider.item(pilot.toString()).user)
    }

    /** Unmarked, an episode forgets it was played, as on Jellyfin: its show leaves À suivre unless another was played. */
    @Test
    fun anEpisodeUnmarkedLeavesUpNextUnlessAnotherOfItsShowWasPlayed() = runTest {
        val show = show("MobLand")
        val pilotRow = show.episode(1, 1)
        val pilot = provider.item(pilotRow.toString())
        val second = provider.item(show.episode(1, 2).toString())
        show.episode(1, 3)
        provider.setPlayed(pilot, played = true)
        assertEquals(listOf("S1E2"), upNext())

        provider.setPlayed(pilot, played = false)
        assertNull(dao.item(pilotRow)?.lastPlayedAt)
        assertEquals(emptyList<String>(), upNext())

        provider.setPlayed(pilot, played = true)
        provider.setPlayed(second, played = true)
        provider.setPlayed(second, played = false)
        assertEquals(listOf("S1E2"), upNext())
    }

    /** A season, a show and a film in copies unmarked forget they were played: none stays in À suivre. */
    @Test
    fun aTitleUnmarkedForgetsItWasPlayed() = runTest {
        val show = show("MobLand")
        show.episode(1, 1)
        show.episode(1, 2)
        show.episode(2, 1)
        val item = provider.item(show.id.toString())
        val first = provider.seasons(item).first()
        provider.setPlayed(first, played = true)
        assertEquals(listOf("S2E1"), upNext())
        provider.setPlayed(first, played = false)
        assertEquals(emptyList<String>(), upNext())

        provider.setPlayed(item, played = true)
        provider.setPlayed(item, played = false)
        assertTrue(dao.episodesOfSeries(show.id).all { it.lastPlayedAt == null })

        val copies = listOf("Heat.mkv", "Heat 4K.mkv").map { movie("Heat", path = it, more = { copy(tmdbId = 949) }) }
        dao.groupVersions()
        provider.setPlayed(provider.item(copies.last().toString()), played = true)
        assertTrue(copies.all { dao.item(it)?.lastPlayedAt != null })
        provider.setPlayed(provider.item(copies.first().toString()), played = false)
        assertTrue(copies.all { dao.item(it)?.lastPlayedAt == null })
    }

    /** What À suivre shows, by title. */
    private suspend fun upNext() = provider.home().singleOrNull { it.type == SectionType.NEXT_UP }?.items.orEmpty().map { it.title }

    /** Sonarr's "Specials" holds S00E01: "Play" still starts S01E01, and the specials once the rest is watched. */
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

    /** The menu offers a quality for the titles its filter keeps, matching the same whole badges. */
    @Test
    fun aQualityIsOfferedForTheFilmsItsFilterKeeps() = runTest {
        suspend fun film(title: String, vararg badges: String) = movie(title, badges = badges.toList())
        suspend fun titles(quality: Quality) = page(LibraryFilter(quality = quality)).items.map { it.title }

        film("Broadcast", "1080p", "HLG")
        assertEquals(emptyList<Quality>(), provider.libraryFacets(films).qualities)
        assertEquals(emptyList<String>(), titles(Quality.HDR))

        film("Heat", "4K", "HDR10+")
        film("Ronin", "1080p", "HDR")
        film("Dune", "4K", "Dolby Vision", "HDR10")
        assertEquals(listOf(Quality.UHD_4K, Quality.HDR, Quality.DOLBY_VISION), provider.libraryFacets(films).qualities)
        assertEquals(listOf("Dune", "Heat", "Ronin"), titles(Quality.HDR))
        assertEquals(listOf("Dune"), titles(Quality.DOLBY_VISION))
        assertEquals(listOf("Dune", "Heat"), titles(Quality.UHD_4K))
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

        // The app may have been killed meanwhile: the reporter is recreated from the resume key.
        provider.reporter(plan.resumeKey!!)!!.stopped(positionMs = 95 * 60_000L, durationMs = 100 * 60_000L)
        dao.item(id)!!.let {
            assertTrue(it.played)
            assertEquals(0L, it.positionMs)
        }
    }

    private suspend fun godfather(positionMs: Long = 0): Long =
        movie("Le Parrain", year = 1972, runtimeMin = 175, path = "Le Parrain/Le Parrain CD1.avi") {
            copy(parts = listOf("Le Parrain/Le Parrain CD2.avi"), positionMs = positionMs, durationMs = 88 * 60_000L)
        }

    @Test
    fun aFilmInPartsOffersEachPartToPlay() = runTest {
        val id = godfather(positionMs = 40 * 60_000L)
        val film = provider.item(id.toString())
        assertEquals(listOf("${id}p1", "${id}p2"), film.parts.map { it.id })
        assertEquals(listOf("Le Parrain CD1.avi", "Le Parrain CD2.avi"), film.parts.map { it.fileName })
        // Its duration is TMDB's, not its first file's.
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

        // A later part keeps the first part's resume point; the app was killed meanwhile, its reporter recreated.
        val second = provider.playback(film.parts[1], resume = false, options) as PlaybackPlan.Launch
        second.reporter!!.progress(positionMs = 30 * 60_000L, durationMs = 87 * 60_000L, paused = false)
        assertEquals(87 * 60_000L, dao.item(id)!!.positionMs)
        provider.reporter(second.resumeKey!!)!!.stopped(positionMs = 86 * 60_000L, durationMs = 87 * 60_000L)
        dao.item(id)!!.let {
            assertTrue(it.played)
            assertEquals(0L, it.positionMs)
        }
    }

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
        assertNull(LocalReporter(dao, movie("Heat")).next())
    }

    /** The player cannot read this app's files: it gets the web address (automatic choice, else the NFO's). */
    @Test
    fun thePlayerGetsWebArtworkForWhatTheAppKeepsItself() = runTest {
        val nfo = """<movie><title>Heat</title><fanart><thumb>http://assets.fanart.tv/heat.jpg</thumb></fanart></movie>"""
        val id = movie("Heat") {
            // A sidecar fanart, kept as a file of the app's; the user chose a local logo over TMDB's.
            copy(
                backdropPath = "file:///data/artwork/heat-fanart.jpg", logoPath = "/tmdbLogo.png",
                userLogoPath = "file:///data/artwork/heat-logo.png", nfoPath = "Heat.nfo", nfo = nfo,
            )
        }
        val item = provider.item(id.toString())
        // Screens still show the files the app keeps.
        assertEquals("file:///data/artwork/heat-fanart.jpg", item.backdrop?.url(1920))

        val plan = provider.playback(item, resume = false, PlaybackOptions(emptyList(), false)) as PlaybackPlan.Launch
        assertEquals("https://assets.fanart.tv/heat.jpg", plan.backdropUrl)
        val logo = plan.logoUrl.orEmpty()
        assertTrue("Not TMDB's logo: $logo", logo.startsWith("https://image.tmdb.org/") && logo.endsWith("/tmdbLogo.png"))
    }

    /** An episode whose backdrop is a local file gets its show's from TMDB; a logo the show lacks stays absent. */
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
        val iso = movie("Heat", path = "Heat.ISO")
        assertTrue((provider.playback(provider.item(iso.toString()), resume = false, options) as PlaybackPlan.Launch).disc)
        val mkv = movie("Ronin")
        assertFalse((provider.playback(provider.item(mkv.toString()), resume = false, options) as PlaybackPlan.Launch).disc)
    }

    /** The tracks the scan read in the file's order go with its launch, under its title's key; a disc's never do. */
    @Test
    fun theFilesTracksGoWithItsLaunch() = runTest {
        val options = PlaybackOptions(emptyList(), false)
        val read = readInOrder()
        val heat = movie("Heat") { withMedia(read).copy(tmdbId = 949) }
        val plan = provider.playback(provider.item(heat.toString()), resume = false, options) as PlaybackPlan.Launch
        assertEquals(read to "local:film:949", plan.media to plan.titleKey)
        val copy = movie("Heat", path = "Heat 2160p.mkv") { withMedia(read).copy(tmdbId = 949) }
        dao.groupVersions()
        val copyPlan = provider.play(copy, null, resume = false) as PlaybackPlan.Launch
        assertEquals("one choice for every copy", "local:film:949", copyPlan.titleKey)
        val unmatched = movie("Ronin") { withMedia(read) }
        assertEquals("local:$unmatched", (provider.play(unmatched, null, resume = false) as PlaybackPlan.Launch).titleKey)
        val iso = movie("Ronin", path = "Ronin.iso") { withMedia(read) }
        assertNull((provider.playback(provider.item(iso.toString()), resume = false, options) as PlaybackPlan.Launch).media)
    }

    /** A film's choice of tracks is kept under its TMDB id, which groups its copies: it outlives the copy it was made on. */
    @Test
    fun aChoiceOfTracksOutlivesTheCopyItWasMadeOn() = runTest {
        val first = movie("Dune", path = "Dune 1080p.mkv") { withMedia(readInOrder()).copy(tmdbId = 438631) }
        val copy = movie("Dune", path = "Dune 2160p.mkv") { withMedia(readInOrder()).copy(tmdbId = 438631) }
        dao.groupVersions()
        val key = checkNotNull((provider.play(first, null, resume = false) as PlaybackPlan.Launch).titleKey)
        db.trackChoices().save(TrackChoiceEntity(key, "eng", null, at = 5))
        // The 1080p file gone for good, as a scan settles it: the 4K copy becomes the title.
        dao.settleMissing(emptyList(), emptyList(), emptyList(), removed = listOf(first), emptyList(), now = 10)
        assertNull(dao.item(copy)?.versionOf)

        val plan = provider.playback(provider.item(copy.toString()), resume = false, PlaybackOptions(emptyList(), false))
        assertEquals("eng", (plan as PlaybackPlan.Launch).titleKey?.let { db.trackChoices().choice(it) }?.audio)
    }

    /** The library's cleanup deletes the choices no title keys any more: a matched film's, an unmatched one's and a show's stay. */
    @Test
    fun aChoiceOfTracksGoesWithItsTitle() = runTest {
        movie("Dune") { copy(tmdbId = 438631) }
        val heat = movie("Heat")
        val show = show("Severance")
        val kept = listOf("local:film:438631", "local:$heat", "local:${show.id}", "jellyfin:show1")
        val gone = listOf("local:film:949", "local:999", "local:film:$heat")
        for (key in kept + gone) db.trackChoices().save(TrackChoiceEntity(key, "eng", null, at = 5))

        val idle = CoroutineScope(Job().apply { cancel() })
        val artwork = ArtworkCache(ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("artwork"))
        LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {}).cleanup()
        assertEquals(kept, kept.filter { db.trackChoices().choice(it) != null })
        assertEquals(emptyList<String>(), gone.filter { db.trackChoices().choice(it) != null })
    }

    /** The cleanup keys titles as playback does ([tracksKey]): no choice saved under a row's own key is deleted. */
    @Test
    fun theCleanupKeepsEveryKeyPlaybackSavesUnder() = runTest {
        val first = movie("Dune", path = "Dune 1080p.mkv") { copy(tmdbId = 438631) }
        val copy = movie("Dune", path = "Dune 2160p.mkv") { copy(tmdbId = 438631) }
        val unmatched = movie("Heat")
        val show = show("Severance")
        val episode = show.episode(1, 1)
        dao.groupVersions()
        val rows = listOf(first, copy, unmatched, show.id, episode, checkNotNull(dao.item(episode)?.parentId))
            .map { checkNotNull(dao.item(it)) }
        assertEquals(setOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.SEASON, ItemKind.EPISODE), rows.map { it.kind }.toSet())
        assertNotNull("a copy", rows.firstOrNull { it.versionOf != null })
        val keys = rows.map(::tracksKey).distinct()
        for (key in keys) db.trackChoices().save(TrackChoiceEntity(key, "eng", null, at = 5))

        dao.deleteOrphanTrackChoices()
        assertEquals(keys, keys.filter { db.trackChoices().choice(it) != null })
    }

    /** French AC3 alone, read by the probe in the file's order. */
    private fun readInOrder() = MediaInfo(
        container = "MKV", audio = listOf(AudioTrack(Codecs.AC3, 6, "fre")), probedWith = MediaProbe.VERSION, tracksInOrder = true,
    )

    /** No logo chosen (the title set in type): the player gets none either. */
    @Test
    fun noLogoChosenIsNoLogoForThePlayer() = runTest {
        val id = movie("Heat") { copy(logoPath = "/tmdbLogo.png", userLogoPath = "") }
        val plan = provider.playback(provider.item(id.toString()), resume = false, PlaybackOptions(emptyList(), false))
        assertNull((plan as PlaybackPlan.Launch).logoUrl)
    }

    /** A card's duration is read from its column: only a title's page decodes the file's media info. */
    @Test
    fun aCardLastsAsLongAsItsFile() = runTest {
        val read = MediaInfo(container = "MKV", durationMs = 9_300_000, probedWith = MediaProbe.VERSION)
        movie("Dune", runtimeMin = 155) { withMedia(read) }
        val card = page().items.single()
        assertEquals(9_300_000L, card.runtimeMs)
        assertNull(card.media)
        assertEquals(read, provider.item(card.id).media)
    }

    /** An NFO's TMDB image address, written at original size by Kodi or Radarr, is requested at the size shown. */
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
        // The image TMDB's own path names: one key, one copy in the image cache.
        assertEquals(LocalProvider.image("/poster.jpg", ImageType.POSTER), LocalProvider.image(original, ImageType.POSTER))
        // Another server's address stays as it is, over HTTPS.
        val fanart = LocalProvider.image("http://assets.fanart.tv/heat.jpg", ImageType.BACKDROP)
        assertEquals("https://assets.fanart.tv/heat.jpg", fanart.url(1920))
    }

    /** Ids of other providers, or of a part 0, are not the library's. */
    @Test
    fun localIdsReadBackAsTheyAreWritten() {
        assertEquals(LocalId.Collection(12), LocalId.parse("c12"))
        assertEquals(LocalId.Item(12), LocalId.parse("12"))
        assertEquals(LocalId.Item(12, part = 2), LocalId.parse(LocalId.ofPart(12, 2)))
        assertNull(LocalId.parse("tt0113277"))
        assertNull(LocalId.parse("12p0"))
    }
}
