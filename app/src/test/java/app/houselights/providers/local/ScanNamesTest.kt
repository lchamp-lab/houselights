package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * What a scan makes of the names and folders a NAS holds: discs, extras, files holding several
 * episodes or naming none, shows under a folder of kinds or of two years.
 */
@RunWith(RobolectricTestRunner::class)
class ScanNamesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private lateinit var shows: SourceEntity
    private val nas get() = bench.nas
    private val showsNas get() = bench.showsNas

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
        films = bench.folder("Films")
        shows = bench.folder("Séries", SourceKind.SHOWS)
    }

    @After
    fun close() = bench.close()

    private suspend fun episodes() = bench.dao.filesOf(shows.id).sortedBy { it.episodeNumber }

    private suspend fun filmPaths() = bench.dao.filesOf(films.id).mapNotNull { it.filePath }.sorted()

    private fun putFile(path: String, text: String) = text.toByteArray().let { nas.put(path, size = it.size.toLong(), bytes = it) }

    private fun putImage(path: String) = nas.put(path, size = 100, bytes = ByteArray(100))

    /** A Blu-ray's folder is one film, named by its folder, opened by its largest stream, described beside it. */
    @Test
    fun aBluRayIsOneTitle() = runTest {
        nas.put("Inception (2010)/BDMV/STREAM/00000.m2ts", size = 300_000_000)
        nas.put("Inception (2010)/BDMV/STREAM/00800.m2ts", size = 38_000_000_000)
        nas.put("Inception (2010)/BDMV/STREAM/00801.m2ts", size = 2_000_000_000)
        putFile("Inception (2010)/movie.nfo", "<movie><plot>A thief who steals secrets.</plot></movie>")
        putImage("Inception (2010)/poster.jpg")
        bench.scan()

        val path = "Inception (2010)/BDMV/STREAM/00800.m2ts"
        assertEquals(listOf(path), filmPaths())
        val inception = bench.item(films, path)
        assertEquals("Inception" to 2010, inception.title to inception.year)
        assertEquals("Inception (2010)/movie.nfo", inception.nfoPath)
        assertTrue(inception.posterPath.isCachedArtwork())
    }

    /** What plays of a disc's folder may open on a menu or an intro: the player is followed as for a disc. */
    @Test
    fun aBluRayIsPlayedAsADisc() = runTest {
        nas.put("Inception (2010)/BDMV/STREAM/00800.m2ts", size = 38_000_000_000)
        bench.scan()

        val inception = bench.item(films, "Inception (2010)/BDMV/STREAM/00800.m2ts")
        val provider = LocalProvider(ApplicationProvider.getApplicationContext(), bench.dao, LocalProviderTest.NoEditor)
        val plan = provider.playback(provider.item(inception.id.toString()), resume = false, PlaybackOptions(emptyList(), false))
        assertTrue((plan as PlaybackPlan.Launch).disc)
    }

    /** A DVD's folder is one film, opened by the first part of its largest title set; its NFO may lie in VIDEO_TS. */
    @Test
    fun aDvdIsOneTitle() = runTest {
        nas.put("Films/Heat (1995)/VIDEO_TS/VIDEO_TS.VOB", size = 200_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_0.VOB", size = 100_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_1.VOB", size = 1_073_000_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_2.VOB", size = 1_073_000_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_3.VOB", size = 400_000_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_02_1.VOB", size = 1_073_000_000)
        putFile("Films/Heat (1995)/VIDEO_TS/VIDEO_TS.nfo", "<movie><plot>Los Angeles.</plot></movie>")
        bench.scan()

        val path = "Films/Heat (1995)/VIDEO_TS/VTS_01_1.VOB"
        assertEquals(listOf(path), filmPaths())
        val heat = bench.item(films, path)
        assertEquals("Heat" to 1995, heat.title to heat.year)
        assertEquals("Films/Heat (1995)/VIDEO_TS/VIDEO_TS.nfo", heat.nfoPath)
    }

    /**
     * Extras are no titles, and the film keeps the folder's NFO and artwork; a folder of the user's
     * at the source's root, or under a folder of kinds, stays a folder of films, whatever its name.
     */
    @Test
    fun extrasAreNoTitles() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Dune (2021)/Dune (2021)-trailer.mkv")
        nas.put("Dune (2021)/Featurettes/Making of.mkv")
        nas.put("Dune (2021)/Behind The Scenes/Sandworms.mkv")
        putFile("Dune (2021)/movie.nfo", "<movie><plot>Arrakis.</plot></movie>")
        putImage("Dune (2021)/poster.jpg")
        nas.put("Trailers/Trailers.mkv")
        nas.put("Shorts/Night Shift.mkv")
        nas.put("Animation/Shorts/Bao (2018).mkv")
        nas.put("The Big Short.mkv")
        nas.put("The Interview.mkv")
        bench.scan()

        val kept = listOf(
            "Animation/Shorts/Bao (2018).mkv", "Dune (2021)/Dune (2021).mkv", "Shorts/Night Shift.mkv", "The Big Short.mkv",
            "The Interview.mkv", "Trailers/Trailers.mkv",
        )
        assertEquals(kept, filmPaths())
        val dune = bench.item(films, "Dune (2021)/Dune (2021).mkv")
        assertEquals("Dune (2021)/movie.nfo", dune.nfoPath)
        assertTrue(dune.posterPath.isCachedArtwork())
    }

    /**
     * A folder named like a folder of extras holds extras only in its title's own folder, named by it or by
     * a year, or in a season's: under a folder of kinds that holds a loose film, its videos are films.
     */
    @Test
    fun foldersNamedLikeExtrasUnderAFolderOfKindsHoldFilms() = runTest {
        nas.put("Animation/Coco (2017).mkv")
        nas.put("Animation/Shorts/Bao (2018).mkv")
        nas.put("Kids/Kid Film.mkv")
        nas.put("Kids/Other/Film (2010).mkv")
        nas.put("Arrival/arrival.mkv")
        nas.put("Arrival/Extras/Interview.mkv")
        // A film in parts, named in its own folder without them.
        nas.put("Heat/Heat CD1.avi")
        nas.put("Heat/Heat CD2.avi")
        nas.put("Heat/Extras/Making of.avi")
        bench.scan()

        val kept = listOf(
            "Animation/Coco (2017).mkv", "Animation/Shorts/Bao (2018).mkv", "Arrival/arrival.mkv", "Heat/Heat CD1.avi",
            "Kids/Kid Film.mkv", "Kids/Other/Film (2010).mkv",
        )
        assertEquals(kept, filmPaths())
    }

    /**
     * A folder holding one film alone, named otherwise than its file and without a year, is still its own: its
     * featurettes, scenes and trailers, which never name films, are extras. A film in parts is one film there.
     */
    @Test
    fun aFilmAloneInAFolderNamedOtherwiseKeepsItsExtrasOut() = runTest {
        nas.put("Amélie/Le Fabuleux Destin d'Amélie Poulain (2001).mkv")
        nas.put("Amélie/Featurettes/Making of.mkv")
        nas.put("Le Seigneur des Anneaux/The.Lord.of.the.Rings.2001.mkv")
        nas.put("Le Seigneur des Anneaux/Behind The Scenes/Weta.mkv")
        nas.put("Le Parrain/The.Godfather.1972.CD1.avi")
        nas.put("Le Parrain/The.Godfather.1972.CD2.avi")
        nas.put("Le Parrain/Deleted Scenes/Horse.avi")
        bench.scan()

        val kept = listOf(
            "Amélie/Le Fabuleux Destin d'Amélie Poulain (2001).mkv", "Le Parrain/The.Godfather.1972.CD1.avi",
            "Le Seigneur des Anneaux/The.Lord.of.the.Rings.2001.mkv",
        )
        assertEquals(kept, filmPaths())
    }

    /** A Blu-ray's folder in a show's is the episode its folder names. */
    @Test
    fun aBluRayOfAnEpisodeIsThatEpisode() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01/BDMV/STREAM/00001.m2ts", size = 9_000_000_000)
        showsNas.put("Severance/Season 1/Severance - S01E01/BDMV/STREAM/00002.m2ts", size = 90_000_000)
        bench.scan()

        val pilot = episodes().single()
        assertEquals("Severance/Season 1/Severance - S01E01/BDMV/STREAM/00001.m2ts", pilot.filePath)
        assertEquals(1 to 1, pilot.seasonNumber to pilot.episodeNumber)
        assertEquals("Severance", bench.dao.item(checkNotNull(pilot.seriesId))?.title)
    }

    /** Extras of an episode, beside it or in its season's folder: no second episode of that number. */
    @Test
    fun extrasOfEpisodesAreNoEpisodes() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put("Severance/Season 1/Severance - S01E01-deleted.mkv")
        showsNas.put("Severance/Season 1/Deleted Scenes/Severance - S01E01 - Lumon.mkv")
        bench.scan()

        assertEquals(listOf("Severance/Season 1/Severance - S01E01.mkv"), episodes().map { it.filePath })
    }

    @Test
    fun aFileHoldingTwoEpisodesIsOneRowNamedForBoth() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01-E02 - Good News About Hell.mkv")
        showsNas.put("Severance/Season 1/Severance - S01E03 - In Perpetuity.mkv")
        bench.scan()

        val rows = episodes()
        assertEquals(listOf(1 to 2, 3 to null), rows.map { it.episodeNumber to it.lastEpisodeNumber })
        val double = LocalMapper(bench.dao, ApplicationProvider.getApplicationContext()).map(rows.first())
        assertEquals("S1 E1-E2", double.episodeLabel)
        assertEquals("1-2", double.episodeNumbers)
        assertEquals("Episodes 1-2", double.title)

        // Watched, it is watched whole: one row, and no other made for its second episode.
        bench.dao.setPlayed(rows.first().id, true, bench.now)
        assertEquals(listOf(true, false), episodes().map { it.played })
    }

    private suspend fun series() = bench.dao.filesOf(shows.id).mapNotNull { it.seriesId }.distinct()
        .map { checkNotNull(bench.dao.item(it)) }.sortedBy { it.matchKey }

    @Test
    fun twoShowsOfOneNameAndTwoYearsAreTwoShows() = runTest {
        showsNas.put("Doctor Who (1963)/Season 1/Doctor Who - S01E01 - An Unearthly Child.mkv")
        showsNas.put("Doctor Who (2005)/Season 1/Doctor Who - S01E01 - Rose.mkv")
        bench.scan()

        assertEquals(listOf("doctor who|1963" to 1963, "doctor who|2005" to 2005), series().map { it.matchKey to it.year })
    }

    /** French packs at the source's root, one for each season ("Saison 1", "Saison 2") and one whole ("Intégrale"): one show each. */
    @Test
    fun frenchPacksOfAShowAreOneShow() = runTest {
        showsNas.put("Les.Revenants.Saison.1.FRENCH.720p.HDTV/Les.Revenants.S01E01.FRENCH.720p.HDTV.mkv")
        showsNas.put("Les.Revenants.Saison.2.FRENCH.720p.HDTV/Les.Revenants.S02E01.FRENCH.720p.HDTV.mkv")
        showsNas.put("Kaamelott.Integrale.1080p/Saison 1/01 - Heat.mkv")
        bench.scan()

        assertEquals(listOf("kaamelott" to "Kaamelott", "les revenants" to "Les Revenants"), series().map { it.matchKey to it.title })
    }

    /** A folder of kinds above the shows: each show is its own, described by its own folder. */
    @Test
    fun showsUnderAFolderOfKindsAreTheirOwn() = runTest {
        showsNas.put("Animation/Arcane/tvshow.nfo", size = ARCANE.size.toLong(), bytes = ARCANE)
        showsNas.put("Animation/Arcane/Saison 1/Arcane.S01E01.MULTi.1080p.WEB.mkv")
        showsNas.put("Animation/Blue Eye Samurai/Saison 1/Blue.Eye.Samurai.S01E01.MULTi.1080p.WEB.mkv")
        bench.scan()

        val found = series()
        assertEquals(listOf("Arcane", "Blue Eye Samurai"), found.map { it.title })
        assertEquals("Animation/Arcane/tvshow.nfo", found.first().nfoPath)
        assertEquals(listOf(1, 1), bench.dao.filesOf(shows.id).map { it.episodeNumber })
    }

    @Test
    fun specialsAreSeasonZero() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put("Severance/Specials/01 - Main Title Sequence.mkv")
        bench.scan()

        val numbers = bench.dao.filesOf(shows.id).sortedBy { it.filePath }.map { it.seasonNumber to it.episodeNumber }
        assertEquals(listOf(1 to 1, 0 to 1), numbers)
        assertEquals(1, series().size)
    }

    /**
     * A show a library holds from before its key held the year keeps its row, and what the user did
     * with it, as new episodes come; a folder of the show that gives no year joins it.
     */
    @Test
    fun aShowKeyedWithoutItsYearKeepsItsRow() = runTest {
        val old = bench.dao.insert(
            ItemEntity(
                kind = ItemKind.SERIES, sourceId = shows.id, matchKey = "severance", title = "Severance", sortTitle = "severance",
                addedAt = 0,
            )
        )
        bench.dao.setFavorite(old, true)
        showsNas.put("Severance (2022)/Season 2/Severance - S02E01.mkv")
        bench.scan()
        val uhd = FakeRemoteBrowser().apply { put("Severance/Season 2/Severance - S02E02 2160p.mkv") }
        val second = bench.folder("Séries 4K", SourceKind.SHOWS, on = uhd)
        bench.scan()

        val row = checkNotNull(bench.dao.item(old))
        assertEquals("severance|2022", row.matchKey)
        assertTrue(row.favorite)
        assertEquals(listOf(old, old), (bench.dao.filesOf(shows.id) + bench.dao.filesOf(second.id)).map { it.seriesId })
    }

    @Test
    fun videosThatNameNoEpisodeAreToldOnceAPass() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put("Severance/Season 1/Severance - Pilot.mkv")
        ShadowLog.clear()
        bench.scan()

        assertEquals(1, episodes().size)
        val told = ShadowLog.getLogsForTag("LibraryScanner").filter { "name no episode" in it.msg }
        assertEquals(1, told.size)
        assertTrue(told.single().msg, "1 of 2 videos" in told.single().msg)
    }

    private companion object {
        val ARCANE = "<tvshow><title>Arcane</title><year>2021</year></tvshow>".toByteArray()
    }
}
