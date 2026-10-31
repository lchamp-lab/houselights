package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a scan makes of a NAS's names and folders: discs, extras, multi-episode files, shows in shared folders. */
@RunWith(RobolectricTestRunner::class)
class ScanNamesTest {
    @get:Rule
    val scan = ScanBenchRule(tmdbKey = false)

    private val bench get() = scan.bench
    private lateinit var films: SourceEntity
    private lateinit var shows: SourceEntity
    private val nas get() = bench.nas
    private val showsNas get() = bench.showsNas

    @Before
    fun open() = runTest {
        films = bench.folder("Films")
        shows = bench.folder("Séries", SourceKind.SHOWS)
    }

    private suspend fun episodes() = bench.dao.filesOf(shows.id).sortedBy { it.episodeNumber }

    private suspend fun filmPaths() = bench.paths(films)

    /** A Blu-ray folder is one film, named by its folder, played from its largest stream, described beside it. */
    @Test
    fun aBluRayIsOneTitle() = runTest {
        nas.put("Inception (2010)/BDMV/STREAM/00000.m2ts", size = 300_000_000)
        nas.put("Inception (2010)/BDMV/STREAM/00800.m2ts", size = 38_000_000_000)
        nas.put("Inception (2010)/BDMV/STREAM/00801.m2ts", size = 2_000_000_000)
        nas.putText("Inception (2010)/movie.nfo", "<movie><plot>A thief who steals secrets.</plot></movie>")
        nas.putImage("Inception (2010)/poster.jpg")
        bench.scan()

        val path = "Inception (2010)/BDMV/STREAM/00800.m2ts"
        assertEquals(listOf(path), filmPaths())
        val inception = bench.item(films, path)
        assertEquals("Inception" to 2010, inception.title to inception.year)
        assertEquals("Inception (2010)/movie.nfo", inception.nfoPath)
        assertTrue(inception.posterPath.isCachedArtwork())
    }

    /** A disc folder may open on a menu or an intro: the player is followed as for a disc. */
    @Test
    fun aBluRayIsPlayedAsADisc() = runTest {
        nas.put("Inception (2010)/BDMV/STREAM/00800.m2ts", size = 38_000_000_000)
        bench.scan()

        val inception = bench.item(films, "Inception (2010)/BDMV/STREAM/00800.m2ts")
        val provider = LocalProvider(ApplicationProvider.getApplicationContext(), bench.dao, NoEditor)
        val plan = provider.playback(provider.item(inception.id.toString()), resume = false, PlaybackOptions(emptyList(), false))
        assertTrue((plan as PlaybackPlan.Launch).disc)
    }

    /** A DVD folder is one film, played from its largest title set's first part; its NFO may be in VIDEO_TS. */
    @Test
    fun aDvdIsOneTitle() = runTest {
        nas.put("Films/Heat (1995)/VIDEO_TS/VIDEO_TS.VOB", size = 200_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_0.VOB", size = 100_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_1.VOB", size = 1_073_000_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_2.VOB", size = 1_073_000_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_01_3.VOB", size = 400_000_000)
        nas.put("Films/Heat (1995)/VIDEO_TS/VTS_02_1.VOB", size = 1_073_000_000)
        nas.putText("Films/Heat (1995)/VIDEO_TS/VIDEO_TS.nfo", "<movie><plot>Los Angeles.</plot></movie>")
        bench.scan()

        val path = "Films/Heat (1995)/VIDEO_TS/VTS_01_1.VOB"
        assertEquals(listOf(path), filmPaths())
        val heat = bench.item(films, path)
        assertEquals("Heat" to 1995, heat.title to heat.year)
        assertEquals("Films/Heat (1995)/VIDEO_TS/VIDEO_TS.nfo", heat.nfoPath)
    }

    /** The film keeps its folder's NFO and artwork; a user folder at the root or in a kinds folder holds films. */
    @Test
    fun extrasAreNoTitles() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Dune (2021)/Dune (2021)-trailer.mkv")
        nas.put("Dune (2021)/Featurettes/Making of.mkv")
        nas.put("Dune (2021)/Behind The Scenes/Sandworms.mkv")
        nas.putText("Dune (2021)/movie.nfo", "<movie><plot>Arrakis.</plot></movie>")
        nas.putImage("Dune (2021)/poster.jpg")
        nas.put("Trailers/Trailers.mkv")
        nas.put("Shorts/Night Shift.mkv")
        nas.put("Animation/Shorts/Bao (2018).mkv")
        nas.put("The Big Short.mkv")
        nas.put("The Interview.mkv")
        bench.scan()

        val kept = listOf(
            "Animation/Shorts/Bao (2018).mkv",
            "Dune (2021)/Dune (2021).mkv",
            "Shorts/Night Shift.mkv",
            "The Big Short.mkv",
            "The Interview.mkv",
            "Trailers/Trailers.mkv",
        )
        assertEquals(kept, filmPaths())
        val dune = bench.item(films, "Dune (2021)/Dune (2021).mkv")
        assertEquals("Dune (2021)/movie.nfo", dune.nfoPath)
        assertTrue(dune.posterPath.isCachedArtwork())
    }

    /** An extras-named folder holds extras only in a title's or season's folder; in a folder of kinds, films. */
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
            "Animation/Coco (2017).mkv",
            "Animation/Shorts/Bao (2018).mkv",
            "Arrival/arrival.mkv",
            "Heat/Heat CD1.avi",
            "Kids/Kid Film.mkv",
            "Kids/Other/Film (2010).mkv",
        )
        assertEquals(kept, filmPaths())
    }

    /** A lone film in a folder named differently and without a year still owns it: its featurettes are extras. */
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
            "Amélie/Le Fabuleux Destin d'Amélie Poulain (2001).mkv",
            "Le Parrain/The.Godfather.1972.CD1.avi",
            "Le Seigneur des Anneaux/The.Lord.of.the.Rings.2001.mkv",
        )
        assertEquals(kept, filmPaths())
    }

    /** A Blu-ray folder inside a show's is the episode its folder names. */
    @Test
    fun aBluRayOfAnEpisodeIsThatEpisode() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01/BDMV/STREAM/00001.m2ts", size = 9_000_000_000)
        showsNas.put("Severance/Season 1/Severance - S01E01/BDMV/STREAM/00002.m2ts", size = 90_000_000)
        bench.scan()

        val path = "Severance/Season 1/Severance - S01E01/BDMV/STREAM/00001.m2ts"
        val pilot = episodes().single()
        assertEquals(path, pilot.filePath)
        assertEquals(1 to 1, pilot.seasonNumber to pilot.episodeNumber)
        assertEquals("Severance", bench.showOf(shows, path).title)
    }

    /** Extras of an episode, beside it or in its season's folder, are not a second episode of that number. */
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

        // Marked watched, it is watched whole: one row, none created for its second episode.
        bench.dao.setPlayed(rows.first().id, true, bench.now)
        assertEquals(listOf(true, false), episodes().map { it.played })
    }

    private suspend fun series() = bench.dao.filesOf(shows.id).mapNotNull { it.seriesId }.distinct()
        .map { checkNotNull(bench.dao.item(it)) }.sortedBy { it.matchKey }

    /**
     * Shows of one name and other years are shows of their own, however the folders hold them: side by side, under a
     * folder of their name (another beside it), or naming more than their year. Each layout is a folder on a NAS of its
     * own, read in one pass in that order: shows are keyed across folders, so the later layouts meet the shows the first
     * made.
     */
    @Test
    fun twoShowsOfOneNameAndTwoYearsAreTwoShows() = runTest {
        val layouts = listOf(
            listOf(
                "Doctor Who (1963)/Season 1/Doctor Who - S01E01 - An Unearthly Child.mkv",
                "Doctor Who (2005)/Season 1/Doctor Who - S01E01 - Rose.mkv",
            ),
            listOf(
                "Doctor Who/Doctor Who (1963)/Season 01/Doctor Who - S01E01 - An Unearthly Child.mkv",
                "Doctor Who/Doctor Who (2005)/Season 01/Doctor Who - S01E01 - Rose.mkv",
                "Doctor Who (2023)/Season 01/Doctor Who - S01E01 - Space Babies.mkv",
            ),
            listOf(
                "Doctor Who/Doctor Who (1963) 1080p/Season 01/Doctor Who - S01E01 - An Unearthly Child.mkv",
                "Doctor Who/Doctor Who (2005) - S01/Doctor Who - S01E01 - Rose.mkv",
            ),
        )
        val folders = layouts.mapIndexed { i, paths ->
            bench.folder("Séries $i", SourceKind.SHOWS, on = FakeRemoteBrowser().apply { paths.forEach { put(it) } }) to paths
        }
        bench.scan()

        for ((folder, paths) in folders) {
            val years = paths.map { bench.showOf(folder, it) }.map { it.matchKey to it.year }
            val expected = listOf(1963, 2005, 2023).take(paths.size).map { "doctor who|$it" to it }
            assertEquals(paths.first(), expected, years)
        }
    }

    /** French packs at the root, per season ("Saison 1") and whole ("Intégrale"), make one show. */
    @Test
    fun frenchPacksOfAShowAreOneShow() = runTest {
        showsNas.put("Les.Revenants.Saison.1.FRENCH.720p.HDTV/Les.Revenants.S01E01.FRENCH.720p.HDTV.mkv")
        showsNas.put("Les.Revenants.Saison.2.FRENCH.720p.HDTV/Les.Revenants.S02E01.FRENCH.720p.HDTV.mkv")
        showsNas.put("Kaamelott.Integrale.1080p/Saison 1/01 - Heat.mkv")
        bench.scan()

        assertEquals(listOf("kaamelott" to "Kaamelott", "les revenants" to "Les Revenants"), series().map { it.matchKey to it.title })
    }

    /** Season packs naming the show's year: one show, described by its folder, tvshow.nfo and season posters. */
    @Test
    fun aShowsSeasonPacksNamingItsYearStayInItsFolder() = runTest {
        showsNas.putText("Breaking Bad/tvshow.nfo", "<tvshow><title>Breaking Bad</title><year>2008</year></tvshow>")
        showsNas.putImage("Breaking Bad/season02-poster.jpg")
        showsNas.put("Breaking Bad/Breaking Bad (2008) Season 1 S01 (1080p BluRay x265 Silence)/Breaking Bad (2008) - S01E01 - Pilot.mkv")
        showsNas.put("Breaking Bad/Breaking Bad (2008) Season 2 S02 (1080p BluRay x265 Silence)/Breaking Bad (2008) - S02E01 - 737.mkv")
        bench.scan()

        val show = series().single()
        assertEquals("breaking bad|2008", show.matchKey)
        assertEquals("Breaking Bad/tvshow.nfo", show.nfoPath)
        assertTrue(bench.dao.season(show.id, 2)?.posterPath.isCachedArtwork())
    }

    @Test
    fun showsUnderAFolderOfKindsAreTheirOwn() = runTest {
        showsNas.putText("Animation/Arcane/tvshow.nfo", "<tvshow><title>Arcane</title><year>2021</year></tvshow>")
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
}
