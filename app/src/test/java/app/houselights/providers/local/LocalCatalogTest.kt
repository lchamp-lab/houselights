package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Catalog
import app.houselights.core.FileTypes
import app.houselights.core.Job
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SectionType
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
import java.time.LocalDate

/** What the local library offers beyond titles ([LocalCatalog]): people, whole sagas, copies, numbers, search. */
@RunWith(RobolectricTestRunner::class)
class LocalCatalogTest {
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
        rating: Double? = null,
        votes: Int? = null,
        collectionId: Long? = null,
        played: Boolean = false,
        lastPlayedAt: Long? = null,
        positionMs: Long = 0,
        fileSize: Long? = null,
        runtimeMin: Int? = null,
        originalTitle: String? = null,
        genres: List<String> = emptyList(),
    ): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = title, sortTitle = MetadataMerge.sortKey(title), year = year,
            tmdbId = tmdbId, filePath = path, badges = badges, rating = rating, votes = votes, collectionId = collectionId,
            played = played, lastPlayedAt = lastPlayedAt, positionMs = positionMs, fileSize = fileSize, runtimeMin = runtimeMin,
            originalTitle = originalTitle, addedAt = 0, matchedAt = 1, genres = genres,
        ),
    )

    private suspend fun show(title: String): Long = dao.insert(
        ItemEntity(kind = ItemKind.SERIES, sourceId = source, title = title, sortTitle = title.lowercase(), addedAt = 0),
    )

    private fun credit(itemId: Long, job: Job, ord: Int, name: String, tmdbId: Int? = null, character: String? = null): CreditEntity {
        val folded = PersonKeys.fold(name)
        val key = tmdbId?.let(PersonKeys::tmdb) ?: PersonKeys.name(folded)
        return CreditEntity(itemId, job, ord, key, name, folded, character, "/$folded.jpg")
    }

    private suspend fun credits(itemId: Long, vararg credits: CreditEntity) = dao.replaceCredits(itemId, credits.toList(), at = 1)

    private fun item(id: Long) = MediaItem(id.toString(), MediaKind.MOVIE, "")

    // --- People ---

    /** Villeneuve directs two films and Deakins shoots one: Villeneuve's page opens, Deakins's does not. */
    @Test
    fun aTitlesCreditsGoByJobEachWithItsTitles() = runTest {
        val arrival = film("Arrival", 2016)
        val dune = film("Dune", 2021)
        credits(
            arrival,
            credit(arrival, Job.DIRECTOR, 0, "Denis Villeneuve", 137427),
            credit(arrival, Job.CAST, 0, "Amy Adams", 9273, "Louise Banks"),
        )
        credits(
            dune,
            credit(dune, Job.DIRECTOR, 0, "Denis Villeneuve", 137427),
            credit(dune, Job.CINEMATOGRAPHY, 0, "Greig Fraser", 1000),
            credit(dune, Job.COMPOSER, 0, "Hans Zimmer", 947),
            credit(dune, Job.CAST, 0, "Timothée Chalamet", 1190668, "Paul Atreides"),
        )

        val credits = catalog.credits(item(dune))
        assertEquals(listOf("Timothée Chalamet"), credits.cast.map { it.name })
        assertEquals("Paul Atreides", credits.cast.single().role)
        assertEquals(listOf(Job.DIRECTOR, Job.COMPOSER, Job.CINEMATOGRAPHY), credits.crew.keys.toList())
        assertEquals(2, credits.crew.getValue(Job.DIRECTOR).single().titleCount)
        assertEquals(PersonKeys.tmdb(137427), credits.crew.getValue(Job.DIRECTOR).single().id)
        assertEquals(listOf("Denis Villeneuve"), credits.recurring().map { it.name })
        // The cast row of a title's page has the same people, with their ids and counts.
        assertEquals(1, provider.item(dune.toString()).cast.single().titleCount)
    }

    @Test
    fun aPersonsPageHoldsTheirTitlesInTheOrderOfTheirYearsBeforeAndBehindTheCamera() = runTest {
        val unforgiven = film("Unforgiven", 1992)
        val mystic = film("Mystic River", 2003)
        val gran = film("Gran Torino", 2008)
        val eastwood = 190
        credits(
            unforgiven,
            credit(unforgiven, Job.DIRECTOR, 0, "Clint Eastwood", eastwood),
            credit(unforgiven, Job.CAST, 0, "Clint Eastwood", eastwood, "Will Munny"),
        )
        credits(mystic, credit(mystic, Job.DIRECTOR, 0, "Clint Eastwood", eastwood))
        credits(
            gran,
            credit(gran, Job.CAST, 0, "Clint Eastwood", eastwood, "Walt Kowalski"),
            credit(gran, Job.DIRECTOR, 0, "Clint Eastwood", eastwood),
        )

        val page = checkNotNull(catalog.person(PersonKeys.tmdb(eastwood)))
        assertEquals("Clint Eastwood", page.person.name)
        assertEquals(3, page.person.titleCount)
        assertEquals(listOf("Unforgiven", "Mystic River", "Gran Torino"), page.titles.map { it.item.title })
        assertEquals(listOf(Job.CAST, Job.DIRECTOR), page.titles.first().jobs)
        assertEquals("Will Munny", page.titles.first().role)
        assertEquals(mapOf(Job.DIRECTOR to 3, Job.CAST to 2), page.jobs)
        assertEquals(listOf(Job.DIRECTOR, Job.CAST), page.jobs.keys.toList())
        assertNull(catalog.person(PersonKeys.tmdb(1)))
    }

    /** Met by name alone on one NFO, then through TMDB on another title: one person, who takes TMDB's key. */
    @Test
    fun aPersonNamedByNameAloneTakesTheKeyTmdbGivesThem() = runTest {
        val heat = film("Heat", 1995)
        val collateral = film("Collateral", 2004)
        credits(heat, credit(heat, Job.DIRECTOR, 0, "Michael Mann"))
        credits(collateral, credit(collateral, Job.DIRECTOR, 0, "Michael Mann", 638))
        assertEquals(PersonKeys.tmdb(638), dao.creditsOf(heat).single().personKey)

        // A person later met by name alone takes the key at once.
        val thief = film("Thief", 1981)
        credits(thief, credit(thief, Job.DIRECTOR, 0, "Michael MANN"))
        assertEquals(PersonKeys.tmdb(638), dao.creditsOf(thief).single().personKey)
        assertEquals(3, checkNotNull(catalog.person(PersonKeys.tmdb(638))).titles.size)
    }

    @Test
    fun theDirectorsOfTheMostFilmsWriteHomesRows() = runTest {
        repeat(3) { i -> film("Nolan $i").let { credits(it, credit(it, Job.DIRECTOR, 0, "Christopher Nolan", 525)) } }
        repeat(2) { i -> film("Mann $i").let { credits(it, credit(it, Job.DIRECTOR, 0, "Michael Mann", 638)) } }
        film("Alone").let { credits(it, credit(it, Job.DIRECTOR, 0, "Someone Once", 1)) }
        val series = show("The Pitt")
        credits(series, credit(series, Job.DIRECTOR, 0, "Michael Mann", 638))

        val directors = catalog.people(Job.DIRECTOR)
        assertEquals(listOf("Christopher Nolan", "Michael Mann"), directors.map { it.name })
        assertEquals(listOf(3, 2), directors.map { it.titleCount })
        assertEquals(3, catalog.people(Job.DIRECTOR, kind = null).last().titleCount)
    }

    // --- Best rated ---

    @Test
    fun theBestRatedHaveEnoughVotesWhereTheirNumberIsKnown() = runTest {
        film("Obscure", rating = 9.5, votes = 12)
        film("Parasite", rating = 8.5, votes = 18_000)
        film("From an NFO", rating = 8.0)
        film("Average", rating = 6.0, votes = 4_000)
        film("Unrated")
        assertEquals(listOf("Parasite", "From an NFO", "Average"), catalog.bestRated().map { it.title })
    }

    // --- Sagas whole ---

    @Test
    fun aSagaHoldsTheFilmsTheLibraryLacksInTheOrderTheyCameOut() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:10", tmdbId = 10, name = "Mad Max - Saga", sortTitle = "mad max"))
        film("Mad Max", 1979, tmdbId = 9659, collectionId = saga)
        film("Fury Road", 2015, tmdbId = 76341, collectionId = saga)
        // In the library, but in another set of the user's own.
        film("Mad Max 2", 1981, tmdbId = 8810)
        // In the saga by its NFO's set alone.
        film("Mad Max: The Making Of", 1985, collectionId = saga)
        dao.replaceSagaParts(
            saga,
            CollectionRules.parts(
                saga,
                TmdbCollection(
                    10,
                    "Mad Max - Saga",
                    parts = listOf(
                        TmdbSearchResult(76341, title = "Fury Road", releaseDate = "2015-05-13"),
                        TmdbSearchResult(9659, title = "Mad Max", releaseDate = "1979-04-12"),
                        TmdbSearchResult(9355, title = "Beyond Thunderdome", releaseDate = "1985-06-29"),
                        TmdbSearchResult(8810, title = "The Road Warrior", releaseDate = "1981-12-24"),
                        TmdbSearchResult(786892, title = "Furiosa", releaseDate = "2099-05-22"),
                    ),
                ),
            ),
        )

        val whole = checkNotNull(catalog.saga(MediaItem(LocalId.ofCollection(saga), MediaKind.COLLECTION, "")))
        assertEquals("Mad Max", whole.collection.title)
        assertEquals(
            listOf("Mad Max", "Mad Max 2", "Beyond Thunderdome", "Mad Max: The Making Of", "Fury Road", "Furiosa"),
            whole.parts.map { it.title },
        )
        assertEquals(listOf(true, true, false, true, true, false), whole.parts.map { it.owned })
        assertEquals(listOf(true, true, true, true, true, false), whole.parts.map { it.released })
        assertEquals(4 to 5, whole.owned to whole.released)
        assertNull(whole.parts[2].item)
    }

    /** A saga not fetched from TMDB holds only the library's films of it. */
    @Test
    fun aSagaTmdbDoesNotKnowIsItsFilms() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "name:marvel", name = "Marvel", sortTitle = "marvel"))
        film("Iron Man", 2008, collectionId = saga)
        film("Thor", 2011, collectionId = saga)
        val whole = checkNotNull(catalog.saga(MediaItem(LocalId.ofCollection(saga), MediaKind.COLLECTION, "")))
        assertEquals(listOf("Iron Man", "Thor"), whole.parts.map { it.title })
        assertTrue(whole.parts.all { it.owned })
    }

    /** A film the library lacks keeps TMDB's poster and its whole release date: its greyed panel, « Sortie le … ». */
    @Test
    fun aMissingFilmKeepsItsPosterAndItsDate() = runTest {
        val saga = dao.insertCollection(
            CollectionEntity(matchKey = "tmdb:726871", tmdbId = 726871, name = "Dune Collection", sortTitle = "dune"),
        )
        film("Dune", 2021, tmdbId = 438631, collectionId = saga)
        dao.replaceSagaParts(
            saga,
            CollectionRules.parts(
                saga,
                TmdbCollection(
                    726871,
                    "Dune Collection",
                    parts = listOf(
                        TmdbSearchResult(438631, title = "Dune", releaseDate = "2021-09-15", posterPath = "/dune.jpg"),
                        TmdbSearchResult(1170608, title = "Dune : Troisième partie", releaseDate = "2099-12-15", posterPath = "/trois.jpg"),
                        TmdbSearchResult(1170609, title = "Dune Messiah"),
                    ),
                ),
            ),
        )

        val whole = checkNotNull(catalog.saga(MediaItem(LocalId.ofCollection(saga), MediaKind.COLLECTION, "")))
        assertEquals("TMDB's English name, without its « Collection »", "Dune", whole.collection.title)
        val (held, coming, announced) = whole.parts
        assertEquals(LocalDate.of(2021, 9, 15), held.releaseDate)
        assertTrue("a film held without a poster of its own shows TMDB's", held.poster?.url(POSTER_PX).orEmpty().contains("/dune.jpg"))
        assertNull(coming.item)
        assertTrue(coming.poster?.url(POSTER_PX).orEmpty().contains("/trois.jpg"))
        assertEquals(LocalDate.of(2099, 12, 15), coming.releaseDate)
        assertFalse(coming.released)
        assertNull("announced, undated: no poster, no date", announced.poster)
        assertNull(announced.releaseDate)
    }

    /** A set of the user's own, which TMDB does not know, keeps its name as its NFO writes it, « Collection » and all. */
    @Test
    fun aSetOfTheUsersOwnKeepsItsName() = runTest {
        val own = dao.insertCollection(
            CollectionEntity(matchKey = "name:criterion", name = "The Criterion Collection", sortTitle = "criterion"),
        )
        film("Le Samouraï", 1967, collectionId = own)
        film("Playtime", 1967, collectionId = own)
        val whole = checkNotNull(catalog.saga(MediaItem(LocalId.ofCollection(own), MediaKind.COLLECTION, "")))
        assertEquals("The Criterion Collection", whole.collection.title)
    }

    /** "À suivre": the film after the last one watched of a saga, the shows' next episodes with it, the latest first. */
    @Test
    fun theNextFilmOfAStartedSagaIsUpNext() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "name:alien", name = "Alien", sortTitle = "alien"))
        film("Alien", 1979, collectionId = saga, played = true, lastPlayedAt = 100)
        film("Aliens", 1986, collectionId = saga)
        film("Alien 3", 1992, collectionId = saga)
        val ended = dao.insertCollection(CollectionEntity(matchKey = "name:matrix", name = "Matrix", sortTitle = "matrix"))
        film("The Matrix", 1999, collectionId = ended, played = true, lastPlayedAt = 200)
        val inProgress = dao.insertCollection(CollectionEntity(matchKey = "name:dune", name = "Dune", sortTitle = "dune"))
        film("Dune", 2021, collectionId = inProgress, played = true, lastPlayedAt = 300)
        film("Dune Part Two", 2024, collectionId = inProgress, positionMs = 60_000, lastPlayedAt = 300)

        val next = provider.home().single { it.type == SectionType.NEXT_UP }.items
        assertEquals("the next of Dune plays in Continue", listOf("Aliens"), next.map { it.title })

        // Watched on a copy of its title, a film counts as watched; a copy in progress plays in Continue.
        val heat = dao.insertCollection(CollectionEntity(matchKey = "name:heat", name = "Heat", sortTitle = "heat"))
        film("Heat", 1995, tmdbId = 949, collectionId = heat)
        film("Heat", 1995, tmdbId = 949, path = "Heat 4K.mkv", collectionId = heat, played = true, lastPlayedAt = 400)
        film("Heat 2", 2026, tmdbId = 1, collectionId = heat)
        film("Heat 2", 2026, tmdbId = 1, path = "Heat 2 4K.mkv", collectionId = heat, positionMs = 60_000, lastPlayedAt = 400)
        dao.groupVersions()
        assertEquals(listOf("Aliens"), provider.home().single { it.type == SectionType.NEXT_UP }.items.map { it.title })
    }

    // --- Versions ---

    @Test
    fun theCopiesOfAFilmAreOneTitleTheBestPlayedUnlessOneIsChosen() = runTest {
        val first = film("Blade Runner", 1982, tmdbId = 78, path = "Blade Runner (1982).mkv", badges = listOf("1080p"), fileSize = 10)
        val final = film(
            "Blade Runner",
            1982,
            tmdbId = 78,
            path = "Blade Runner (1982) {edition-Final Cut}.mkv",
            badges = listOf("4K", "Dolby Vision", "TrueHD Atmos"),
            fileSize = 60,
        )
        film("Blade Runner 2049", 2017, tmdbId = 335984)
        dao.groupVersions()
        assertEquals(first, dao.item(final)?.versionOf)

        val page = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 10)
        assertEquals(listOf("Blade Runner", "Blade Runner 2049"), page.items.map { it.title })
        assertEquals(2, page.total)

        val versions = catalog.versions(item(final))
        assertEquals(listOf(final, first), versions.map { it.key.toLong() })
        assertEquals(listOf(true, false), versions.map { it.best })
        assertEquals(listOf(true, false), versions.map { it.chosen })

        catalog.chooseVersion(item(final), versions.last())
        val chosen = catalog.versions(item(first))
        assertEquals(first, chosen.first { it.chosen }.key.toLong())
        assertEquals(final, chosen.first { it.best }.key.toLong())
        assertEquals(emptyList<Any>(), catalog.versions(item(dao.latest(ItemKind.MOVIE, 10).first { it.tmdbId == 335984 }.id)))
    }

    /** Lire plays the chosen copy, else the best. */
    @Test
    fun aTitlePlaysItsChosenCopyElseItsBest() = runTest {
        val first = film("Heat", 1995, tmdbId = 949, path = "Heat.1080p.mkv", badges = listOf("1080p"), genres = listOf("Policier"))
        val uhd = film("Heat", 1995, tmdbId = 949, path = "Heat.2160p.mkv", badges = listOf("4K"), genres = listOf("Policier"))
        film("Ronin", 1998, tmdbId = 8195, genres = listOf("Policier"))
        dao.groupVersions()
        val options = PlaybackOptions(emptyList(), forceHttp = false)
        suspend fun played(item: MediaItem) = (provider.playback(item, resume = false, options) as PlaybackPlan.Launch).uri

        assertTrue(played(provider.item(first.toString())).endsWith("Heat.2160p.mkv"))
        val versions = catalog.versions(item(first))
        catalog.chooseVersion(item(first), versions.single { it.key == first.toString() })
        assertTrue(played(provider.item(first.toString())).endsWith("Heat.1080p.mkv"))
        assertEquals(listOf("Ronin"), provider.similar(provider.item(uhd.toString())).map { it.title })

        // Once its title is gone, a copy becomes a title of its own at once.
        dao.settleMissing(emptyList(), emptyList(), emptyList(), listOf(first), emptyList(), now = 1)
        assertNull(dao.item(uhd)?.versionOf)
        assertEquals(emptyList<Any>(), catalog.versions(item(uhd)))
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

    /** Watched and favourite apply to a film, so to all its copies, whichever is played or marked. */
    @Test
    fun aFilmsCopiesAreWatchedAndFavouredTogether() = runTest {
        val first = film("Heat", 1995, tmdbId = 949)
        val second = film("Heat", 1995, tmdbId = 949, path = "Heat (1995) 2160p.mkv")
        val other = film("Ronin", 1998, tmdbId = 8195)
        dao.groupVersions()
        provider.setPlayed(provider.item(second.toString()), true)
        provider.setFavorite(provider.item(first.toString()), true)
        assertEquals(listOf(true, true, false), listOf(first, second, other).map { checkNotNull(dao.item(it)).played })
        assertEquals(listOf(true, true, false), listOf(first, second, other).map { checkNotNull(dao.item(it)).favorite })

        // Watched to its end, one copy makes the other watched too, its resume point kept.
        provider.setPlayed(provider.item(first.toString()), false)
        dao.savePlayback(first, positionMs = 1_000_000, durationMs = null, played = false, lastPlayedAt = 1)
        LocalReporter(dao, second).stopped(positionMs = 6_000_000, durationMs = 6_500_000)
        assertTrue(checkNotNull(dao.item(first)).played)
        assertEquals(1_000_000L, checkNotNull(dao.item(first)).positionMs)
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

    // --- Filters and numbers ---

    @Test
    fun wallsFilterByPictureSoundKindOfFileAndPersonOnAnyCopy() = runTest {
        val dune = film("Dune", tmdbId = 1, badges = listOf("1080p", "DTS-HD MA"))
        film("Dune", tmdbId = 1, path = "Dune (2021) 2160p.mkv", badges = listOf("4K", "HDR10+", "TrueHD Atmos"))
        film("Disc", path = "Disc (2020)/BDMV/STREAM/00800.m2ts", badges = listOf("1080p"))
        film("Old", path = "Old.avi", badges = listOf("SD", "AC3"))
        credits(dune, credit(dune, Job.DIRECTOR, 0, "Denis Villeneuve", 137427))
        dao.groupVersions()

        suspend fun titles(filter: LibraryFilter) = provider.libraryPage(films, SortOption.TITLE, filter, 0, 10).items.map { it.title }
        assertEquals(listOf("Dune"), titles(LibraryFilter(picture = "HDR10+")))
        assertEquals(listOf("Dune"), titles(LibraryFilter(sound = "TrueHD Atmos")))
        assertEquals(listOf("Disc", "Dune"), titles(LibraryFilter(picture = "1080p")))
        assertEquals(emptyList<String>(), titles(LibraryFilter(picture = "HDR10+", sound = "DTS-HD MA")))
        assertEquals(listOf("Disc"), titles(LibraryFilter(fileType = FileTypes.BLU_RAY)))
        assertEquals(listOf("Old"), titles(LibraryFilter(fileType = "avi")))
        assertEquals(listOf("Dune"), titles(LibraryFilter(fileType = "mkv")))
        assertEquals(listOf("Dune"), titles(LibraryFilter(person = PersonKeys.tmdb(137427))))

        val facets = provider.libraryFacets(films)
        assertEquals(listOf("4K", "1080p", "SD", "HDR10+"), facets.pictures)
        assertEquals(listOf("TrueHD Atmos", "DTS-HD MA", "AC3"), facets.sounds)
        assertEquals(listOf("avi", FileTypes.BLU_RAY, "mkv"), facets.fileTypes)
    }

    @Test
    fun theLibraryInNumbersCountsEachFilmOnceAndEveryCopysRoom() = runTest {
        val dune = film("Dune", tmdbId = 1, badges = listOf("1080p", "DTS-HD MA"), fileSize = 20, runtimeMin = 155)
        val uhd = listOf("4K", "Dolby Vision", "TrueHD Atmos")
        film("Dune", tmdbId = 1, path = "Dune 2160p.mkv", badges = uhd, fileSize = 60, runtimeMin = 155)
        val heat = film("Heat", badges = listOf("1080p", "DTS-HD MA"), fileSize = 30, runtimeMin = 170)
        repeat(2) { i -> film("Nolan $i", fileSize = 1).let { credits(it, credit(it, Job.DIRECTOR, 0, "Christopher Nolan", 525)) } }
        credits(dune, credit(dune, Job.DIRECTOR, 0, "Denis Villeneuve", 137427))
        credits(heat, credit(heat, Job.DIRECTOR, 0, "Michael Mann", 638))
        dao.groupVersions()

        val numbers = catalog.numbers()
        assertEquals(4 to 0, numbers.films to numbers.shows)
        assertEquals(112L, numbers.sizeBytes)
        assertEquals((155 + 170) * 60_000L, numbers.runtimeMs)
        assertEquals(listOf("4K" to 1, "1080p" to 2, "Dolby Vision" to 1), numbers.picture.map { it.label to it.films })
        assertEquals(listOf("DTS-HD MA" to 2, "TrueHD Atmos" to 1), numbers.sound.map { it.label to it.films })
        assertEquals(LibraryFilter(sound = "DTS-HD MA"), numbers.sound.first().filter)
        assertEquals(listOf("mkv" to 4), numbers.files.map { it.label to it.films })
        assertEquals(listOf("Christopher Nolan"), numbers.directors.map { it.label })
        assertEquals(LibraryFilter(person = PersonKeys.tmdb(525)), numbers.directors.single().filter)
    }

    // --- Search ---

    @Test
    fun aSearchReadsTheStartOfWordsWhateverTheirAccentsAndCase() = runTest {
        val amelie = film("Le Fabuleux Destin d’Amélie Poulain", 2001)
        film("Amadeus", 1984, rating = 8.0)
        film("La La Land", 2016, originalTitle = "La La Land")
        film("Spider-Man", 2002)
        film("Mother", 2009, originalTitle = "마더")
        film("Blade Runner", 1982, tmdbId = 78)
        film("Blade Runner", 1982, tmdbId = 78, path = "Blade Runner 4K.mkv")
        dao.groupVersions()
        credits(amelie, credit(amelie, Job.CAST, 0, "Audrey Tautou", 2405), credit(amelie, Job.DIRECTOR, 0, "Jean-Pierre Jeunet", 2419))

        suspend fun titles(query: String) = catalog.search(query).titles.map { it.title }
        assertEquals(listOf("Le Fabuleux Destin d’Amélie Poulain"), titles("amel"))
        assertEquals(listOf("Le Fabuleux Destin d’Amélie Poulain"), titles("AMÉLIE fab"))
        assertEquals(listOf("Amadeus", "Le Fabuleux Destin d’Amélie Poulain"), titles("am"))
        assertEquals(listOf("Spider-Man"), titles("spiderman"))
        assertEquals(listOf("Spider-Man"), titles("man spi"))
        assertEquals(listOf("Mother"), titles("마더"))
        assertEquals("one title for a film's copies", listOf("Blade Runner"), titles("blade"))
        assertEquals(emptyList<String>(), titles("amelie zzz"))
        // A word as common as an article does not fill the result cap before the titles starting with it.
        repeat(450) { film("Le Film $it") }
        film("Léon", 1994)
        assertEquals("Léon", catalog.search("léon").titles.first().title)
        assertTrue(catalog.search("le", limit = 500).titles.any { it.title == "Léon" })
        assertTrue(catalog.search("  -  ").isEmpty())

        assertEquals(listOf("Jean-Pierre Jeunet"), catalog.search("jeun").people.map { it.name })
        assertEquals(listOf("Jean-Pierre Jeunet"), catalog.search("jeanpierre").people.map { it.name })
        assertEquals(PersonKeys.tmdb(2405), catalog.search("tautou").people.single().id)
    }

    @Test
    fun aSearchFindsSagasTheLibraryShowsAndTitlesRenamed() = runTest {
        val saga = dao.insertCollection(
            CollectionEntity(matchKey = "name:seigneur", name = "Le Seigneur des Anneaux", sortTitle = "seigneur"),
        )
        film("La Communauté de l'anneau", 2001, collectionId = saga)
        film("Les Deux Tours", 2002, collectionId = saga)
        val alone = dao.insertCollection(CollectionEntity(matchKey = "name:anneaux", name = "Anneaux seuls", sortTitle = "anneaux seuls"))
        film("Un seul", collectionId = alone)
        assertEquals(listOf("Le Seigneur des Anneaux"), catalog.search("anneau").sagas.map { it.title })

        // A title renamed by a scan is found by its new name only.
        val id = film("Old Name")
        dao.saveScanned(checkNotNull(dao.item(id)).copy(title = "Nouveau Nom", sortTitle = "nouveau nom"))
        assertEquals(listOf("Nouveau Nom"), catalog.search("nouv").titles.map { it.title })
        assertEquals(emptyList<String>(), catalog.search("old name").titles.map { it.title })
    }

    @Test
    fun aSagasTmdbSuffixIsNotShown() = runTest {
        val saga = dao.insertCollection(CollectionEntity(matchKey = "tmdb:1", tmdbId = 1, name = "Top Gun - Saga", sortTitle = "top gun"))
        film("Top Gun", 1986, collectionId = saga)
        film("Top Gun: Maverick", 2022, collectionId = saga)
        assertEquals("Top Gun", provider.collectionOf(provider.item(dao.latest(ItemKind.MOVIE, 1).single().id.toString()))?.title)
        assertFalse(CollectionRules.shownName("Saga", known = true).isEmpty())
    }

    private companion object {
        /** The width a saga's panel asks its poster at. */
        const val POSTER_PX = 494
    }
}
