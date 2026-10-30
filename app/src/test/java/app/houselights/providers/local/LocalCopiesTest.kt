package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.AudioTrack
import app.houselights.core.Catalog
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.VideoInfo
import app.houselights.core.WatchStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A film in several copies on the local library: which copy it is held, started, watched and resumed as, on its walls,
 * their filters, its page, search, its saga, Reprendre, À suivre and Lire ([VersionRank.played]).
 */
@RunWith(RobolectricTestRunner::class)
class LocalCopiesTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var provider: LocalProvider
    private lateinit var catalog: Catalog
    private var source = 0L

    private val films = Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null)

    @Before
    fun open() = runBlocking {
        db = testDatabase { allowMainThreadQueries() }
        dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor) { 2 }
        catalog = provider.catalog
        source = dao.insertSource(nfsFolder())
    }

    @After
    fun close() = db.close()

    @Suppress("LongParameterList") // One per column a test sets.
    private suspend fun film(
        title: String,
        year: Int? = 2020,
        tmdbId: Int? = null,
        path: String = "$title.mkv",
        badges: List<String> = emptyList(),
        collectionId: Long? = null,
        played: Boolean = false,
        lastPlayedAt: Long? = null,
        positionMs: Long = 0,
        runtimeMin: Int? = null,
        media: MediaInfo? = null,
    ): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = title, sortTitle = MetadataMerge.sortKey(title), year = year,
            tmdbId = tmdbId, filePath = path, badges = badges, collectionId = collectionId, played = played,
            lastPlayedAt = lastPlayedAt, positionMs = positionMs, runtimeMin = runtimeMin, addedAt = 0, matchedAt = 1,
            mediaInfo = media?.let(MediaProbe::encode),
        ),
    )

    private fun item(id: Long) = MediaItem(id.toString(), MediaKind.MOVIE, "")

    /** Reprendre's cards on home; none without its row. */
    private suspend fun continueRow(): List<MediaItem> = provider.home().singleOrNull { it.type == SectionType.CONTINUE }?.items.orEmpty()

    /** The films wall, filtered on [status]. */
    private suspend fun filmsWall(status: WatchStatus = WatchStatus.ALL): List<MediaItem> =
        provider.libraryPage(films, SortOption.TITLE, LibraryFilter(status = status), 0, 10).items

    /** What Reprendre, or Lire, on film [id]'s page launches. */
    private suspend fun launch(id: Long): PlaybackPlan.Launch {
        val options = PlaybackOptions(emptyList(), forceHttp = false)
        return provider.playback(provider.item(id.toString()), resume = true, options) as PlaybackPlan.Launch
    }

    /**
     * A film in several copies is held as the copy Lire plays is, its card, page and Lire alike: the best unless one is
     * chosen, another copy's file missing or not; each copy as its own file is.
     */
    @Test
    fun aFilmInCopiesIsHeldAsTheCopyLirePlays() = runTest {
        val first = film("Heat", 1995, tmdbId = 949, path = "Heat.1080p.mkv", badges = listOf("1080p"))
        val uhd = film("Heat", 1995, tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"))
        dao.groupVersions()
        suspend fun heldSince() = provider.item(first.toString()).missingSince
        suspend fun wall() = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 10).items.map { it.missingSince }

        dao.setMissingSince(listOf(uhd), 5)
        assertEquals("the 4K plays, and is missing", 5L, heldSince())
        assertEquals(listOf<Long?>(5), wall())
        assertEquals(listOf(5L, null), catalog.versions(item(first)).map { it.item.missingSince })

        catalog.chooseVersion(item(first), catalog.versions(item(first)).single { it.key == first.toString() })
        assertNull("the 1080p chosen plays, and is there", heldSince())
        assertEquals(listOf<Long?>(null), wall())

        dao.setMissingSince(listOf(uhd), null)
        dao.setMissingSince(listOf(first), 7)
        assertEquals("the copy chosen missing", 7L, heldSince())
        catalog.chooseVersion(item(first), null)
        assertNull("the best plays again, and is there", heldSince())
    }

    /**
     * A film in several copies is started where the copy Lire plays was left: its walls, their filters and its saga read
     * that copy, which holds the resume point, not the copy found first, which holds the film; each copy keeps its own.
     */
    @Test
    fun aFilmInCopiesIsStartedAsTheCopyLirePlays() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:8091", tmdbId = 8091, name = "Alien", sortTitle = "alien"))
        film("Alien", 1979, tmdbId = 348, collectionId = saga, played = true)
        // Found first, the 1080p copy holds the film; the 4K one, better, is the one Lire plays.
        val first = film("Aliens", 1986, tmdbId = 679, collectionId = saga, badges = listOf("1080p"), runtimeMin = 137)
        val best = film("Aliens", 1986, tmdbId = 679, path = "Aliens 2160p.mkv", collectionId = saga, badges = listOf("4K"))
        dao.groupVersions()
        LocalReporter(dao, best).progress(positionMs = 2_400_000, durationMs = 9_240_000, paused = false)

        val aliens = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 10).items.single { it.title == "Aliens" }
        assertEquals(2_400_000L, aliens.user.positionMs)
        assertEquals(9_240_000L, aliens.runtimeMs)
        suspend fun titles(status: WatchStatus, grouped: Boolean = false) =
            provider.libraryPage(films, SortOption.TITLE, LibraryFilter(status = status), 0, 10, grouped).items.map { it.title }
        assertEquals(listOf("Aliens"), titles(WatchStatus.IN_PROGRESS))
        assertEquals(emptyList<String>(), titles(WatchStatus.UNWATCHED))
        assertEquals(listOf("Alien"), titles(WatchStatus.WATCHED))
        assertEquals(1, titles(WatchStatus.IN_PROGRESS, grouped = true).size)

        // Its saga resumes it, Reprendre in Lire's place.
        val next = provider.nextUp(provider.item(LocalId.ofCollection(saga)))
        assertEquals(first.toString(), next?.id)
        assertEquals(2_400_000L, next?.user?.positionMs)

        val versions = catalog.versions(aliens).associateBy { it.key }
        assertEquals(0L, versions.getValue(first.toString()).item.user.positionMs)
        assertEquals(137 * 60_000L, versions.getValue(first.toString()).runtimeMs)
        assertEquals(2_400_000L, versions.getValue(best.toString()).item.user.positionMs)
    }

    /**
     * A film watched, then given a better copy, stays watched: the copy Lire plays, added after, is not, but the film is
     * watched as any copy is, on its walls, their filters and its saga's page, as on its saga's card and in À suivre.
     */
    @Test
    fun aFilmWatchedStaysWatchedOnceABetterCopyComes() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:726871", tmdbId = 726871, name = "Dune", sortTitle = "dune"))
        film("Dune", 2021, tmdbId = 438631, badges = listOf("1080p"), played = true, collectionId = saga)
        val next = film("Dune Part Two", 2024, tmdbId = 693134, collectionId = saga)
        // Added once Dune was watched, the 4K copy comes unwatched, and is the one Lire plays.
        film("Dune", 2021, tmdbId = 438631, path = "Dune 2160p.mkv", badges = listOf("4K"), collectionId = saga)
        dao.groupVersions()

        suspend fun page(status: WatchStatus) =
            provider.libraryPage(films, SortOption.TITLE, LibraryFilter(status = status), 0, 10).items
        assertTrue(page(WatchStatus.ALL).single { it.title == "Dune" }.user.played)
        assertEquals(listOf("Dune"), page(WatchStatus.WATCHED).map { it.title })
        assertEquals(listOf("Dune Part Two"), page(WatchStatus.UNWATCHED).map { it.title })

        // Its saga goes on to the next film, Lire on it.
        assertEquals(next.toString(), provider.nextUp(provider.item(LocalId.ofCollection(saga)))?.id)
    }

    /**
     * Reprendre shows a film in several copies once, as its walls show it: its main row, started where the copy Lire
     * plays was left, in the place of its copy played last; not at all once a copy is watched, as the En cours filter
     * leaves it out.
     */
    @Test
    fun reprendreShowsAFilmInCopiesOnceAsItsWallsDo() = runTest {
        // Both copies of Heat started, the 1080p one last: Lire plays it, though the 4K one is better.
        val heat = film("Heat", 1995, tmdbId = 949, badges = listOf("1080p"), positionMs = 600_000, lastPlayedAt = 300)
        film("Heat", 1995, tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"), positionMs = 2_400_000, lastPlayedAt = 200)
        // Ronin's 1080p copy alone started: Lire resumes it rather than its 4K one from the start.
        film("Ronin", 1998, tmdbId = 8195, badges = listOf("1080p"), positionMs = 900_000, lastPlayedAt = 400)
        film("Ronin", 1998, tmdbId = 8195, path = "Ronin.2160p.mkv", badges = listOf("4K"))
        // Collateral watched in 1080p, then its 4K copy, added after, started.
        film("Collateral", 2004, tmdbId = 1538, badges = listOf("1080p"), played = true, lastPlayedAt = 50)
        film("Collateral", 2004, tmdbId = 1538, path = "Collateral.2160p.mkv", badges = listOf("4K"), positionMs = 1_200_000)
        film("Thief", 1981, positionMs = 60_000, lastPlayedAt = 100)
        dao.groupVersions()

        val resumed = continueRow()
        assertEquals(listOf("Ronin", "Heat", "Thief"), resumed.map { it.title })
        assertEquals(listOf(900_000L, 600_000L, 60_000L), resumed.map { it.user.positionMs })
        assertEquals(heat.toString(), resumed[1].id)
        assertEquals(filmsWall(WatchStatus.IN_PROGRESS).map { it.id }.toSet(), resumed.map { it.id }.toSet())

        // Reprendre resumes the copy Lire plays where it was left.
        val plan = provider.playback(resumed[1], resume = true, PlaybackOptions(emptyList(), forceHttp = false)) as PlaybackPlan.Launch
        assertTrue(plan.uri.endsWith("Heat.mkv"))
        assertEquals(600_000L, plan.startMs)
    }

    /**
     * A film started on its only copy stays where it was left once a better copy comes, nothing chosen in Versions:
     * Reprendre, En cours, its page and search show the copy started, which Lire resumes where it stopped and the sheet
     * marks as the one Lire plays.
     */
    @Test
    fun aFilmStartedOnItsOnlyCopyResumesItOnceABetterCopyComes() = runTest {
        // Watched 40 minutes while it was alone, the 1080p copy; then the 4K one, better, comes and is grouped with it.
        val first = film("Heat", tmdbId = 949, badges = listOf("1080p"), positionMs = 2_400_000, lastPlayedAt = 100)
        val uhd = film("Heat", tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"))
        dao.groupVersions()

        assertEquals(listOf(first.toString()), continueRow().map { it.id })
        assertEquals(2_400_000L, continueRow().single().user.positionMs)
        assertEquals(listOf("Heat"), filmsWall(WatchStatus.IN_PROGRESS).map { it.title })
        assertEquals(emptyList<String>(), filmsWall(WatchStatus.UNWATCHED).map { it.title })
        assertEquals(2_400_000L, filmsWall().single().user.positionMs)
        assertEquals(2_400_000L, provider.item(first.toString()).user.positionMs)
        assertEquals(2_400_000L, catalog.search("heat").titles.single().user.positionMs)

        val plan = launch(first)
        assertTrue(plan.uri.endsWith("Heat.mkv"))
        assertEquals(2_400_000L, plan.startMs)
        val versions = catalog.versions(item(first))
        // The best first, as ever: the copy Lire plays is marked, not moved.
        assertEquals(listOf(uhd, first), versions.map { it.key.toLong() })
        assertEquals(listOf(false, true), versions.map { it.chosen })
        assertEquals(listOf(true, false), versions.map { it.best })
    }

    /** Of two copies started, the one played last is the film's everywhere, and Lire resumes it where it stopped. */
    @Test
    fun ofTwoCopiesStartedTheOnePlayedLastPlays() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:8091", tmdbId = 8091, name = "Alien", sortTitle = "alien"))
        film("Alien", 1979, tmdbId = 348, collectionId = saga, played = true, lastPlayedAt = 50)
        // The 1080p copy stopped at 10 minutes after the 4K one, better, at 40.
        val first = film(
            "Aliens",
            1986,
            tmdbId = 679,
            path = "Aliens.1080p.mkv",
            badges = listOf("1080p"),
            collectionId = saga,
            positionMs = 600_000,
            lastPlayedAt = 300,
        )
        val uhd = film(
            "Aliens",
            1986,
            tmdbId = 679,
            path = "Aliens.2160p.mkv",
            badges = listOf("4K"),
            collectionId = saga,
            positionMs = 2_400_000,
            lastPlayedAt = 200,
        )
        dao.groupVersions()
        suspend fun positions() = listOf(
            continueRow().single().user.positionMs,
            filmsWall(WatchStatus.IN_PROGRESS).single().user.positionMs,
            provider.item(first.toString()).user.positionMs,
            catalog.search("aliens").titles.single().user.positionMs,
            provider.nextUp(provider.item(LocalId.ofCollection(saga)))?.user?.positionMs,
            launch(first).startMs,
        )

        assertEquals(List(6) { 600_000L }, positions())
        assertTrue(launch(first).uri.endsWith("Aliens.1080p.mkv"))
        assertEquals(first, catalog.versions(item(first)).single { it.chosen }.key.toLong())

        // The 4K copy played since, at 50 minutes: it is the film's now.
        dao.savePlayback(uhd, positionMs = 3_000_000, durationMs = null, played = false, lastPlayedAt = 400)
        assertEquals(List(6) { 3_000_000L }, positions())
        assertTrue(launch(first).uri.endsWith("Aliens.2160p.mkv"))
        assertEquals(uhd, catalog.versions(item(first)).single { it.chosen }.key.toLong())
    }

    /** A copy chosen in Versions plays, from its own resume point, though another copy is started: a choice comes first. */
    @Test
    fun aCopyChosenInVersionsPlaysThoughAnotherIsStarted() = runTest {
        val first = film("Heat", tmdbId = 949, badges = listOf("1080p"), positionMs = 2_400_000, lastPlayedAt = 100)
        val uhd = film("Heat", tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"))
        dao.groupVersions()
        // Started, the 1080p copy plays where it stopped, though the 4K one is better, until the 4K one is chosen.
        assertEquals(2_400_000L, launch(first).startMs)
        assertTrue(launch(first).uri.endsWith("Heat.mkv"))
        catalog.chooseVersion(item(first), catalog.versions(item(first)).single { it.key == uhd.toString() })

        assertEquals(emptyList<MediaItem>(), continueRow())
        assertEquals(emptyList<String>(), filmsWall(WatchStatus.IN_PROGRESS).map { it.title })
        assertEquals(listOf("Heat"), filmsWall(WatchStatus.UNWATCHED).map { it.title })
        assertEquals(0L, provider.item(first.toString()).user.positionMs)
        val plan = launch(first)
        assertTrue(plan.uri.endsWith("Heat.2160p.mkv"))
        assertEquals(0L, plan.startMs)
        assertEquals(listOf(true, false), catalog.versions(item(first)).map { it.chosen })

        // The 1080p copy kept where it stopped, and resumes there once chosen.
        catalog.chooseVersion(item(first), catalog.versions(item(first)).single { it.key == first.toString() })
        assertEquals(2_400_000L, launch(first).startMs)
    }

    /**
     * A film in several copies shows the formats of the copy Lire plays on its page, its picture, sound, badges and file:
     * the best one, added after, then the one chosen in Versions; its walls read no media info.
     */
    @Test
    fun aFilmInCopiesShowsTheFormatsOfTheCopyLirePlays() = runTest {
        val hd = MediaInfo(video = VideoInfo("AVC", 1920, 1080), audio = listOf(AudioTrack("DTS-HD MA", 6, "fre")))
        val uhd = MediaInfo(video = VideoInfo("HEVC", 3840, 2160), audio = listOf(AudioTrack("TrueHD", 8, "eng", atmos = true)))
        val first = film("Heat", tmdbId = 949, path = "Heat.mkv", badges = listOf("1080p"), media = hd)
        val best = film("Heat", tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"), media = uhd)
        dao.groupVersions()

        val page = provider.item(first.toString())
        assertEquals(uhd, page.media)
        assertEquals(listOf("4K"), page.badges)
        assertEquals("Heat.2160p.mkv", page.fileName)
        assertNull(filmsWall().single().media)

        catalog.chooseVersion(item(first), catalog.versions(item(first)).single { it.key == first.toString() })
        val chosen = provider.item(first.toString())
        assertEquals(hd, chosen.media)
        assertEquals(listOf("1080p"), chosen.badges)
        assertEquals("Heat.mkv", chosen.fileName)
        assertEquals(best.toString(), catalog.versions(item(first)).single { !it.chosen }.key)
    }

    /** With no copy started, the best plays, from its start: a copy played but not started does not count. */
    @Test
    fun withNoCopyStartedTheBestPlays() = runTest {
        // The 1080p copy, opened last, was left before a resume point was kept.
        val first = film("Heat", tmdbId = 949, badges = listOf("1080p"), lastPlayedAt = 300)
        val uhd = film("Heat", tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"))
        dao.groupVersions()

        val plan = launch(first)
        assertTrue(plan.uri.endsWith("Heat.2160p.mkv"))
        assertEquals(0L, plan.startMs)
        assertEquals(uhd, catalog.versions(item(first)).single { it.chosen }.key.toLong())
        assertEquals(emptyList<MediaItem>(), continueRow())
        assertEquals(listOf("Heat"), filmsWall(WatchStatus.UNWATCHED).map { it.title })
    }

    /** A film is watched once any copy is: it leaves Reprendre and En cours, though another copy is started. */
    @Test
    fun aFilmWatchedOnACopyLeavesReprendreThoughAnotherIsStarted() = runTest {
        // The 1080p copy stopped at 40 minutes; the 4K one, found apart, was watched to its end before both were grouped.
        val first = film("Heat", tmdbId = 949, badges = listOf("1080p"), positionMs = 2_400_000, lastPlayedAt = 100)
        film("Heat", tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"), played = true, lastPlayedAt = 200)
        dao.groupVersions()

        assertEquals(emptyList<MediaItem>(), continueRow())
        assertEquals(emptyList<String>(), filmsWall(WatchStatus.IN_PROGRESS).map { it.title })
        assertEquals(listOf("Heat"), filmsWall(WatchStatus.WATCHED).map { it.title })
        assertTrue(provider.item(first.toString()).user.played)
    }

    /**
     * A saga counts a film watched as soon as any copy is, its card on the sagas wall and on the film's page alike, as
     * its walls and its page show the film.
     */
    @Test
    fun aSagaCountsAFilmWatchedOnAnyCopy() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:1575", tmdbId = 1575, name = "Rocky", sortTitle = "rocky"))
        film("Rocky", 1976, tmdbId = 1366, collectionId = saga, played = true)
        // Rocky II's 4K copy was watched before its 1080p copy, found first, was known as the same film.
        val second = film("Rocky II", 1979, tmdbId = 1367, badges = listOf("1080p"), collectionId = saga)
        film("Rocky II", 1979, tmdbId = 1367, path = "Rocky II.2160p.mkv", badges = listOf("4K"), collectionId = saga, played = true)
        dao.groupVersions()

        val sagas = Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)
        val card = provider.libraryPage(sagas, SortOption.TITLE, LibraryFilter(), 0, 10).items.single()
        assertEquals(0, card.user.unplayedCount)
        assertTrue(card.user.played)
        assertEquals(0, provider.collectionOf(provider.item(second.toString()))?.user?.unplayedCount)
        assertTrue(provider.collectionItems(card).all { it.user.played })
    }

    /**
     * À suivre goes on with a saga as its page does: a film whose other copy alone was started, while Lire plays one not
     * started, chosen in Versions, is not in progress, as on its walls, so À suivre offers it.
     */
    @Test
    fun upNextOffersAFilmStartedOnACopyLireDoesNotPlay() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:31562", tmdbId = 31562, name = "Bourne", sortTitle = "bourne"))
        film("The Bourne Identity", 2002, tmdbId = 2501, collectionId = saga, played = true, lastPlayedAt = 100)
        // Its 1080p copy started, the second film plays in 4K, chosen in Versions, from the start.
        val second = film(
            "The Bourne Supremacy",
            2004,
            tmdbId = 2502,
            badges = listOf("1080p"),
            collectionId = saga,
            positionMs = 600_000,
            lastPlayedAt = 200,
        )
        val uhd = film(
            "The Bourne Supremacy",
            2004,
            tmdbId = 2502,
            path = "The Bourne Supremacy.2160p.mkv",
            badges = listOf("4K"),
            collectionId = saga,
        )
        dao.groupVersions()
        catalog.chooseVersion(item(second), catalog.versions(item(second)).single { it.key == uhd.toString() })

        val home = provider.home()
        assertEquals(listOf(second.toString()), home.single { it.type == SectionType.NEXT_UP }.items.map { it.id })
        assertEquals(second.toString(), provider.nextUp(provider.item(LocalId.ofCollection(saga)))?.id)
        assertTrue(home.none { it.type == SectionType.CONTINUE })
    }
}
